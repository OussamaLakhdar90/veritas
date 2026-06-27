package ca.bnc.qe.veritas.codegen.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Set;
import ca.bnc.qe.veritas.codegen.CodegenService;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService.RepoRef;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Generate clones both repos, generates into the output working copy with the scope, and keeps that copy for publish. */
@ExtendWith(MockitoExtension.class)
class TestGenServiceTest {

    @Mock private WorkspaceService workspace;
    @Mock private CodegenService codegen;
    private TestGenService service;

    @BeforeEach
    void init() {
        service = new TestGenService(workspace, codegen);
    }

    @Test
    void generatesIntoTheOutputCopyAndCleansUpOnlyTheServiceClone() {
        Path svc = Path.of("svc");
        Path out = Path.of("out");
        when(workspace.resolve(eq("APP1"), eq("ciam"), any(), any())).thenReturn(svc);
        when(workspace.resolve(eq("APP1"), eq("ciam-tests"), any(), any())).thenReturn(out);
        CodegenRun run = new CodegenRun();
        Set<String> scope = Set.of("POST /policies");
        when(codegen.generate("ciam", svc, null, out, "alice", scope)).thenReturn(run);

        CodegenRun result = service.generate("ciam",
                new RepoRef("APP1", "ciam", "develop", null),
                new RepoRef("APP1", "ciam-tests", "develop", null), scope, "alice");

        assertThat(result).isSameAs(run);
        verify(codegen).generate("ciam", svc, null, out, "alice", scope);   // tests written into the output copy
        verify(workspace).cleanup(svc);                                     // read-only service clone dropped
        verify(workspace, never()).cleanup(out);                            // output copy kept alive for the gated publish
    }

    @Test
    void stillCleansTheServiceCloneWhenGenerationFails() {
        Path svc = Path.of("svc");
        Path out = Path.of("out");
        when(workspace.resolve(eq("APP1"), eq("ciam"), any(), any())).thenReturn(svc);
        when(workspace.resolve(eq("APP1"), eq("ciam-tests"), any(), any())).thenReturn(out);
        when(codegen.generate(any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("llm down"));

        assertThatThrownBy(() -> service.generate("ciam",
                new RepoRef("APP1", "ciam", null, null),
                new RepoRef("APP1", "ciam-tests", null, null), Set.of(), "alice"))
                .isInstanceOf(RuntimeException.class);

        verify(workspace).cleanup(svc);
    }
}
