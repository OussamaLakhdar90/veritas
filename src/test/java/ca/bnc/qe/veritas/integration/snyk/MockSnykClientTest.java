package ca.bnc.qe.veritas.integration.snyk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards the demo/offline fixture's contract so the mock stays consistent with the real BNC shape. */
class MockSnykClientTest {

    private final MockSnykClient client = new MockSnykClient();

    @Test
    void exposesTheApplicationTestsTargetAndAFixableIssueCarryingASafeVersion() {
        assertThat(client.listOrgs()).extracting(SnykOrg::slug).contains("app7576");
        assertThat(client.listTargets("org-7576")).extracting(SnykTarget::displayName).contains("application-tests");

        var issues = client.aggregatedIssues("org-7576", "proj-profile");
        // Every fixable issue must advertise a safe version; non-fixable ones legitimately don't.
        assertThat(issues).filteredOn(SnykIssue::fixable)
                .isNotEmpty()
                .allSatisfy(i -> assertThat(i.safeVersion()).isNotBlank());
        // The critical jackson-databind is the (common) no-supported-fix case.
        assertThat(issues).anySatisfy(i -> {
            assertThat(i.severity()).isEqualTo("critical");
            assertThat(i.fixable()).isFalse();
        });
    }
}
