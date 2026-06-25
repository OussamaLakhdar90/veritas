package ca.bnc.qe.veritas.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import ca.bnc.qe.veritas.evidence.feature.MultiSourceStrategyService;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import picocli.CommandLine;

/** The multi-source CLI: builds the SourceSelection from any source combo, guards no-source, cleans up the clone. */
class MultiSourceStrategyCommandTest {

    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final JavaSpringExtractor extractor = mock(JavaSpringExtractor.class);
    private final MultiSourceStrategyService strategyService = mock(MultiSourceStrategyService.class);
    private final ca.bnc.qe.veritas.evidence.SourceExpander sourceExpander =
            mock(ca.bnc.qe.veritas.evidence.SourceExpander.class);

    private int run(String... args) {
        return new CommandLine(new MultiSourceStrategyCommand(workspace, extractor, strategyService, sourceExpander))
                .execute(args);
    }

    @Test
    void jiraOnlyGeneratesWithoutCloning() {
        TestStrategy s = new TestStrategy();
        s.setId("strat-1");
        when(strategyService.generate(eq("ciam"), any(), eq("local"))).thenReturn(s);

        assertThat(run("--name", "ciam", "--jql", "project = CIAM")).isZero();

        ArgumentCaptor<SourceSelection> sel = ArgumentCaptor.forClass(SourceSelection.class);
        verify(strategyService).generate(eq("ciam"), sel.capture(), eq("local"));
        assertThat(sel.getValue().hasJira()).isTrue();
        assertThat(sel.getValue().hasCode()).isFalse();
        verifyNoInteractions(workspace);   // no code arm → no clone
    }

    @Test
    void noSourceReturns2AndGeneratesNothing() {
        assertThat(run("--name", "ciam")).isEqualTo(2);
        verifyNoInteractions(strategyService);
    }

    @Test
    void anEpicKeyExpandsToAChildIssuesJqlSelection() {
        when(sourceExpander.jqlForEpic("CIAM-100")).thenReturn("parent = \"CIAM-100\"");
        when(strategyService.generate(eq("ciam"), any(), eq("local"))).thenReturn(new TestStrategy());

        assertThat(run("--name", "ciam", "--epic", "CIAM-100")).isZero();

        ArgumentCaptor<SourceSelection> sel = ArgumentCaptor.forClass(SourceSelection.class);
        verify(strategyService).generate(eq("ciam"), sel.capture(), eq("local"));
        assertThat(sel.getValue().jql()).isEqualTo("parent = \"CIAM-100\"");
    }

    @Test
    void aConfluenceRootExpandsToDescendantPageIds() {
        when(sourceExpander.pageIdsForRoot("987")).thenReturn(List.of("987", "988"));
        when(strategyService.generate(eq("ciam"), any(), eq("local"))).thenReturn(new TestStrategy());

        assertThat(run("--name", "ciam", "--confluence-root", "987")).isZero();

        ArgumentCaptor<SourceSelection> sel = ArgumentCaptor.forClass(SourceSelection.class);
        verify(strategyService).generate(eq("ciam"), sel.capture(), eq("local"));
        assertThat(sel.getValue().pageIds()).contains("987", "988");
    }

    @Test
    void theCodeArmClonesExtractsAndCleansUp() {
        when(workspace.resolve("APP", "repo", null, null)).thenReturn(Path.of("/tmp/clone"));
        when(extractor.extract(any())).thenReturn(new ApiModel("code", "ciam", "1", null, List.of(), Map.of()));
        TestStrategy s = new TestStrategy();
        s.setId("strat-2");
        when(strategyService.generate(eq("ciam"), any(), eq("local"))).thenReturn(s);

        assertThat(run("--name", "ciam", "--app-id", "APP", "--repo-slug", "repo")).isZero();

        verify(workspace).resolve("APP", "repo", null, null);
        verify(extractor).extract(Path.of("/tmp/clone"));
        verify(workspace).cleanup(Path.of("/tmp/clone"));   // the cloned temp dir is dropped after extraction
    }
}
