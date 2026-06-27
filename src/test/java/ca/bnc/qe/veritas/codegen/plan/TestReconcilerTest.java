package ca.bnc.qe.veritas.codegen.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import org.junit.jupiter.api.Test;

/** The deterministic GAP / CURRENT / ORPHAN reconciliation, with the empty-inventory (from-scratch) collapse. */
class TestReconcilerTest {

    private final TestReconciler reconciler = new TestReconciler();

    private static Endpoint ep(HttpMethod method, String path) {
        return new Endpoint(method, path, method + " " + path, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), null);
    }

    private static ApiModel api(Endpoint... eps) {
        return new ApiModel("code", "svc", "1", null, List.of(eps), Map.of(), List.of());
    }

    private static TestInventory inv(TestReference... refs) {
        return new TestInventory(List.of(refs), 3);
    }

    @Test
    void emptyInventoryMakesEveryEndpointAGapInScratchMode() {
        TestPlan plan = reconciler.reconcile("svc",
                api(ep(HttpMethod.GET, "/policies"), ep(HttpMethod.POST, "/policies")), TestInventory.empty());

        assertThat(plan.mode()).isEqualTo(TestPlan.SCRATCH);
        assertThat(plan.gaps()).isEqualTo(2);
        assertThat(plan.current()).isZero();
        assertThat(plan.stale()).isZero();   // STALE is deferred to the refactor stage — never emitted here
        assertThat(plan.filesScanned()).isZero();
        assertThat(plan.items()).extracting(TestPlanItem::status).containsOnly(TestPlanItem.GAP);
        assertThat(plan.items().get(0).reason()).contains("No tests yet");
    }

    @Test
    void coveredEndpointIsCurrentWhenPathAndMethodMatch() {
        TestPlan plan = reconciler.reconcile("svc", api(ep(HttpMethod.POST, "/policies")),
                inv(new TestReference(HttpMethod.POST, "/policies", "PolicyCreateTest.java")));

        assertThat(plan.mode()).isEqualTo(TestPlan.REFACTOR);
        assertThat(plan.items()).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(TestPlanItem.CURRENT);
            assertThat(i.existingRef()).isEqualTo("PolicyCreateTest.java");
        });
    }

    @Test
    void uncoveredEndpointIsAGapEvenWhenASiblingVerbOnTheSamePathIsTested() {
        // POST /policies is tested; GET /policies must be a GAP, never mislabelled because the path is shared.
        TestPlan plan = reconciler.reconcile("svc",
                api(ep(HttpMethod.GET, "/policies"), ep(HttpMethod.POST, "/policies")),
                inv(new TestReference(HttpMethod.POST, "/policies", "PolicyCreateTest.java")));

        assertThat(plan.gaps()).isEqualTo(1);
        assertThat(plan.current()).isEqualTo(1);
        TestPlanItem get = plan.items().stream().filter(i -> "GET".equals(i.method())).findFirst().orElseThrow();
        assertThat(get.status()).isEqualTo(TestPlanItem.GAP);
    }

    @Test
    void restAssuredTemplatePathMatchesItsEndpointExactly() {
        TestPlan plan = reconciler.reconcile("svc", api(ep(HttpMethod.GET, "/policies/{id}")),
                inv(new TestReference(HttpMethod.GET, "/policies/{id}", "GetPolicyTest.java")));

        assertThat(plan.items()).singleElement()
                .extracting(TestPlanItem::status).isEqualTo(TestPlanItem.CURRENT);
    }

    @Test
    void aBasePathPrefixStillCountsAsCoverage() {
        // The test hits the gateway URL /api/v1/policies; the controller mapping is /policies — still a match.
        TestPlan plan = reconciler.reconcile("svc", api(ep(HttpMethod.GET, "/policies")),
                inv(new TestReference(HttpMethod.GET, "/api/v1/policies", "ListPoliciesTest.java")));

        assertThat(plan.items()).singleElement()
                .extracting(TestPlanItem::status).isEqualTo(TestPlanItem.CURRENT);
    }

    @Test
    void unknownVerbReferenceStillCoversTheEndpoint() {
        TestPlan plan = reconciler.reconcile("svc", api(ep(HttpMethod.GET, "/health")),
                inv(new TestReference(null, "/health", "HealthTest.java")));

        assertThat(plan.items()).singleElement()
                .extracting(TestPlanItem::status).isEqualTo(TestPlanItem.CURRENT);
    }

    @Test
    void aTestPathInNoEndpointIsAnOrphanDedupedByPath() {
        TestPlan plan = reconciler.reconcile("svc", api(ep(HttpMethod.GET, "/policies")),
                inv(new TestReference(HttpMethod.GET, "/legacy/export", "LegacyExportTest.java"),
                        new TestReference(HttpMethod.GET, "/legacy/export/", "LegacyExportTest.java")));

        assertThat(plan.orphan()).isEqualTo(1);   // the trailing-slash duplicate collapses
        TestPlanItem orphan = plan.items().stream()
                .filter(i -> TestPlanItem.ORPHAN.equals(i.status())).findFirst().orElseThrow();
        assertThat(orphan.path()).isEqualTo("/legacy/export");
        assertThat(orphan.reason()).contains("not in the current API");
    }

    @Test
    void distinctSubResourcePathsAreNotConflated() {
        // /policies/{id} and /policies/{id}/items are different endpoints; a test for one must not cover the other.
        TestPlan plan = reconciler.reconcile("svc",
                api(ep(HttpMethod.GET, "/policies/{id}"), ep(HttpMethod.GET, "/policies/{id}/items")),
                inv(new TestReference(HttpMethod.GET, "/policies/42", "GetPolicyTest.java")));

        assertThat(plan.current()).isEqualTo(1);
        assertThat(plan.gaps()).isEqualTo(1);
        TestPlanItem items = plan.items().stream()
                .filter(i -> i.path().endsWith("/items")).findFirst().orElseThrow();
        assertThat(items.status()).isEqualTo(TestPlanItem.GAP);
    }

    @Test
    void nullApiYieldsAnEmptyScratchPlan() {
        TestPlan plan = reconciler.reconcile("svc", null, TestInventory.empty());
        assertThat(plan.items()).isEmpty();
        assertThat(plan.mode()).isEqualTo(TestPlan.SCRATCH);
    }
}
