package ca.bnc.qe.veritas.codegen.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Set;
import ca.bnc.qe.veritas.codegen.CodegenService;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService.RepoRef;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Generate clones both repos, generates into the output copy with the scope, records the Jira key, keeps it for publish. */
@ExtendWith(MockitoExtension.class)
class TestGenServiceTest {

    @Mock private WorkspaceService workspace;
    @Mock private CodegenService codegen;
    @Mock private CodegenRunRepository runs;
    private TestGenService service;

    @BeforeEach
    void init() {
        service = new TestGenService(workspace, codegen, runs);
    }

    @Test
    void generatesIntoTheOutputCopyRecordsTheJiraKeyAndCleansUpOnlyTheServiceClone() {
        Path svc = Path.of("svc");
        Path out = Path.of("out");
        when(workspace.resolve(eq("APP1"), eq("ciam"), any(), any())).thenReturn(svc);
        when(workspace.resolve(eq("APP1"), eq("ciam-tests"), any(), any())).thenReturn(out);
        CodegenRun run = new CodegenRun();
        Set<String> scope = Set.of("POST /policies");
        when(codegen.generate("ciam", svc, null, out, "alice", scope, ServiceAuthSpec.none())).thenReturn(run);
        when(runs.save(run)).thenReturn(run);

        CodegenRun result = service.generate("ciam",
                new RepoRef("APP1", "ciam", "develop", null),
                new RepoRef("APP1", "ciam-tests", "develop", null), scope, "alice", "CIAM-1842");

        assertThat(result).isSameAs(run);
        assertThat(run.getJiraKey()).isEqualTo("CIAM-1842");               // recorded for the gated publish
        verify(codegen).generate("ciam", svc, null, out, "alice", scope, ServiceAuthSpec.none());  // written into the output copy
        verify(workspace).cleanup(svc);                                    // read-only service clone dropped
        verify(workspace, never()).cleanup(out);                           // output copy kept alive for publish
    }

    @Test
    void aBlankJiraKeyIsRejectedBeforeAnyCloneOrGeneration() {
        assertThatThrownBy(() -> service.generate("ciam",
                new RepoRef("APP1", "ciam", null, null),
                new RepoRef("APP1", "ciam-tests", null, null), Set.of(), "alice", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Jira ticket is required");

        verifyNoInteractions(workspace, codegen, runs);   // failed fast — nothing cloned or generated
    }

    @Test
    void stillCleansTheServiceCloneWhenGenerationFails() {
        Path svc = Path.of("svc");
        Path out = Path.of("out");
        when(workspace.resolve(eq("APP1"), eq("ciam"), any(), any())).thenReturn(svc);
        when(workspace.resolve(eq("APP1"), eq("ciam-tests"), any(), any())).thenReturn(out);
        when(codegen.generate(any(), any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("llm down"));

        assertThatThrownBy(() -> service.generate("ciam",
                new RepoRef("APP1", "ciam", null, null),
                new RepoRef("APP1", "ciam-tests", null, null), Set.of(), "alice", "CIAM-1842"))
                .isInstanceOf(RuntimeException.class);

        verify(workspace).cleanup(svc);
    }
}
