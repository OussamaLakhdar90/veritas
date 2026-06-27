package ca.bnc.qe.veritas.codegen.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService.RepoRef;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Wiring: clone (read) both repos, extract + scan, reconcile, and always clean up the temp clones. */
@ExtendWith(MockitoExtension.class)
class TestPlanServiceTest {

    @Mock private WorkspaceService workspace;
    @Mock private JavaSpringExtractor extractor;
    @Mock private TestInventoryExtractor inventoryExtractor;
    private final TestReconciler reconciler = new TestReconciler();   // real — cheap, exercises the integration
    private TestPlanService service;

    @BeforeEach
    void init() {
        service = new TestPlanService(workspace, extractor, inventoryExtractor, reconciler);
    }

    private static ApiModel api(Endpoint... eps) {
        return new ApiModel("code", "svc", "1", null, List.of(eps), Map.of(), List.of());
    }

    private static Endpoint ep(HttpMethod method, String path) {
        return new Endpoint(method, path, method + " " + path, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), null);
    }

    @Test
    void noTestRepoIsAFromScratchPlanAndNeverScans() {
        Path svc = Path.of("svc");
        when(workspace.resolve(eq("APP1"), eq("ciam"), any(), any())).thenReturn(svc);
        when(extractor.extract(svc)).thenReturn(api(ep(HttpMethod.GET, "/policies")));

        TestPlan plan = service.plan("ciam",
                new RepoRef("APP1", "ciam", "develop", null),
                new RepoRef("APP1", null, null, null));   // no test repo → from-scratch

        assertThat(plan.mode()).isEqualTo(TestPlan.SCRATCH);
        assertThat(plan.gaps()).isEqualTo(1);
        verifyNoInteractions(inventoryExtractor);
        verify(workspace).cleanup(svc);
    }

    @Test
    void existingTestRepoIsScannedAndReconciled() {
        Path svc = Path.of("svc");
        Path tst = Path.of("tst");
        when(workspace.resolve(eq("APP1"), eq("ciam"), any(), any())).thenReturn(svc);
        when(workspace.resolve(eq("APP1"), eq("ciam-tests"), any(), any())).thenReturn(tst);
        when(extractor.extract(svc)).thenReturn(api(ep(HttpMethod.POST, "/policies")));
        when(inventoryExtractor.scan(tst)).thenReturn(
                new TestInventory(List.of(new TestReference(HttpMethod.POST, "/policies", "T.java")), 1));

        TestPlan plan = service.plan("ciam",
                new RepoRef("APP1", "ciam", "develop", null),
                new RepoRef("APP1", "ciam-tests", "develop", null));

        assertThat(plan.mode()).isEqualTo(TestPlan.REFACTOR);
        assertThat(plan.current()).isEqualTo(1);
        verify(workspace).cleanup(svc);
        verify(workspace).cleanup(tst);   // both clones cleaned up
    }

    @Test
    void cleansUpTheCloneEvenWhenExtractionFails() {
        Path svc = Path.of("svc");
        when(workspace.resolve(any(), any(), any(), any())).thenReturn(svc);
        when(extractor.extract(svc)).thenThrow(new RuntimeException("parse blew up"));

        assertThatThrownBy(() -> service.plan("ciam", new RepoRef("APP1", "ciam", null, null), null))
                .isInstanceOf(RuntimeException.class);

        verify(workspace).cleanup(svc);   // the finally block ran
    }
}
