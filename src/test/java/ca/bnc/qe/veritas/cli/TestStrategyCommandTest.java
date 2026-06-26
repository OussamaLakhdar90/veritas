package ca.bnc.qe.veritas.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.testmgmt.BasisBuilder;
import ca.bnc.qe.veritas.testmgmt.TestStrategyService;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import picocli.CommandLine;

/**
 * Branch coverage for {@link TestStrategyCommand} driven through picocli's {@code execute(args)}:
 * the {@code --name} guard, the two code-arm branches (--repo / --app-id+--repo-slug) with their
 * {@code finally} cleanup, the Jira/Confluence ingest arm with both the null and comma-split page
 * branches, and the SOURCE tag each arm tags the generated strategy with. The service is mocked so
 * we assert on the exact {@code generate(name, basis, source, owner)} arguments, exit codes, and which
 * workspace/basis-builder calls fire.
 */
class TestStrategyCommandTest {

    private final TestStrategyService strategyService = mock(TestStrategyService.class);
    private final BasisBuilder basisBuilder = mock(BasisBuilder.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);

    private int run(String... args) {
        return new CommandLine(new TestStrategyCommand(strategyService, basisBuilder, workspace)).execute(args);
    }

    private TestStrategy strategy(String id, double cost) {
        TestStrategy s = new TestStrategy();
        s.setId(id);
        s.setEstCostUsd(cost);
        return s;
    }

    // ============================ --name guard ============================

    @Test
    void missingNameIsAUsageErrorAndNothingRuns() {
        // --name is required -> picocli aborts before call(), exit code 2, no collaborators touched.
        assertThat(run("--jql", "project = CIAM")).isEqualTo(2);
        verifyNoInteractions(strategyService, basisBuilder, workspace);
    }

    // ============================ code arm: --repo ============================

    @Test
    void localRepoBuildsCodeBasisTagsCodeAndCleansUp() {
        Path repo = Path.of("/tmp/repo");
        when(workspace.resolve(null, null, null, repo.toString())).thenReturn(repo);
        when(basisBuilder.fromRepo(repo)).thenReturn("CODE-BASIS");
        when(strategyService.generate("ciam", "CODE-BASIS", "CODE", "local")).thenReturn(strategy("strat-1", 0.1234));

        assertThat(run("--name", "ciam", "--repo", repo.toString())).isZero();

        verify(workspace).resolve(null, null, null, repo.toString());
        verify(basisBuilder).fromRepo(repo);
        verify(strategyService).generate("ciam", "CODE-BASIS", "CODE", "local");
        verify(workspace).cleanup(repo);               // basis is in memory now -> drop the clone
        verify(basisBuilder, never()).fromIngest(any(), any());   // the ingest arm never ran
    }

    // ============================ code arm: --app-id + --repo-slug (+ --branch) ============================

    @Test
    void appIdAndRepoSlugClonesExtractsTagsCodeAndCleansUp() {
        Path clone = Path.of("/tmp/clone");
        // repo is null here -> resolve is called with appId/repoSlug/branch and a null repoPath.
        when(workspace.resolve("APP", "repo", "main", null)).thenReturn(clone);
        when(basisBuilder.fromRepo(clone)).thenReturn("CLONE-BASIS");
        when(strategyService.generate("ciam", "CLONE-BASIS", "CODE", "local")).thenReturn(strategy("strat-2", 0.5));

        assertThat(run("--name", "ciam", "--app-id", "APP", "--repo-slug", "repo", "--branch", "main")).isZero();

        verify(workspace).resolve("APP", "repo", "main", null);
        verify(basisBuilder).fromRepo(clone);
        verify(strategyService).generate("ciam", "CLONE-BASIS", "CODE", "local");
        verify(workspace).cleanup(clone);
    }

    @Test
    void codeArmCleansUpEvenWhenBasisBuildThrows() {
        Path repo = Path.of("/tmp/repo");
        when(workspace.resolve(null, null, null, repo.toString())).thenReturn(repo);
        when(basisBuilder.fromRepo(repo)).thenThrow(new IllegalStateException("boom"));

        // fromRepo throws inside the try -> call() propagates (non-zero picocli exit) but finally still cleans up.
        assertThat(run("--name", "ciam", "--repo", repo.toString())).isNotZero();
        verify(workspace).cleanup(repo);                 // finally always runs
        verifyNoInteractions(strategyService);           // generation never reached
    }

    // ============================ guard: only one of app-id / repo-slug -> falls to the ingest arm ============================

    @Test
    void appIdWithoutRepoSlugFallsThroughToIngestArm() {
        // appId set but repoSlug null -> the code-arm condition is false -> ingest arm (no workspace touched).
        when(basisBuilder.fromIngest(null, List.of())).thenReturn("INGEST");
        when(strategyService.generate("ciam", "INGEST", "JIRA_CONFLUENCE", "local")).thenReturn(strategy("s", 0.0));

        assertThat(run("--name", "ciam", "--app-id", "APP")).isZero();

        verify(basisBuilder).fromIngest(null, List.of());
        verify(strategyService).generate("ciam", "INGEST", "JIRA_CONFLUENCE", "local");
        verifyNoInteractions(workspace);   // no code arm -> no clone/resolve/cleanup
    }

    // ============================ ingest arm: --jql + --confluence (comma split) ============================

    @Test
    void jqlAndConfluencePagesSplitOnCommaAndTagJiraConfluence() {
        when(basisBuilder.fromIngest("project = CIAM", List.of("111", "222"))).thenReturn("INGEST-BASIS");
        when(strategyService.generate("ciam", "INGEST-BASIS", "JIRA_CONFLUENCE", "local"))
                .thenReturn(strategy("strat-3", 0.02));

        assertThat(run("--name", "ciam", "--jql", "project = CIAM", "--confluence", "111,222")).isZero();

        verify(basisBuilder).fromIngest("project = CIAM", List.of("111", "222"));
        verify(strategyService).generate("ciam", "INGEST-BASIS", "JIRA_CONFLUENCE", "local");
        verify(basisBuilder, never()).fromRepo(any());
        verifyNoInteractions(workspace);
    }

    // ============================ ingest arm: --jql only -> confluencePages == null -> empty list ============================

    @Test
    void jqlWithoutConfluenceUsesEmptyPageList() {
        when(basisBuilder.fromIngest("project = CIAM", List.of())).thenReturn("INGEST-BASIS");
        when(strategyService.generate("ciam", "INGEST-BASIS", "JIRA_CONFLUENCE", "local"))
                .thenReturn(strategy("strat-4", 0.0));

        assertThat(run("--name", "ciam", "--jql", "project = CIAM")).isZero();

        verify(basisBuilder).fromIngest("project = CIAM", List.of());   // null --confluence -> List.of()
        verify(strategyService).generate("ciam", "INGEST-BASIS", "JIRA_CONFLUENCE", "local");
    }

    // ============================ ingest arm: --confluence only (jql null) ============================

    @Test
    void confluenceOnlyPassesNullJqlAndSplitPages() {
        when(basisBuilder.fromIngest(null, List.of("987"))).thenReturn("CONF-ONLY");
        when(strategyService.generate("ciam", "CONF-ONLY", "JIRA_CONFLUENCE", "local"))
                .thenReturn(strategy("strat-5", 0.01));

        assertThat(run("--name", "ciam", "--confluence", "987")).isZero();

        verify(basisBuilder).fromIngest(null, List.of("987"));
        verifyNoInteractions(workspace);
    }

    // ============================ ingest arm: no source at all (only --name) ============================

    @Test
    void onlyNameStillRunsTheIngestArmWithNullJqlAndEmptyPages() {
        // No code source and no jql/confluence -> still the else arm: fromIngest(null, List.of()).
        when(basisBuilder.fromIngest(null, List.of())).thenReturn("EMPTY");
        when(strategyService.generate("ciam", "EMPTY", "JIRA_CONFLUENCE", "local")).thenReturn(strategy("s", 0.0));

        assertThat(run("--name", "ciam")).isZero();

        verify(basisBuilder).fromIngest(null, List.of());
        verify(strategyService).generate("ciam", "EMPTY", "JIRA_CONFLUENCE", "local");
        verifyNoInteractions(workspace);
    }

    // ============================ printout uses the returned id + cost ============================

    @Test
    void successCarriesTheServiceNameThroughToGenerate() {
        Path repo = Path.of("/tmp/r");
        when(workspace.resolve(null, null, null, repo.toString())).thenReturn(repo);
        when(basisBuilder.fromRepo(repo)).thenReturn("B");
        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        when(strategyService.generate(eq("my-svc"), eq("B"), eq("CODE"), eq("local"))).thenReturn(strategy("id-9", 1.5));

        assertThat(run("--name", "my-svc", "--repo", repo.toString())).isZero();

        verify(strategyService).generate(nameCap.capture(), eq("B"), eq("CODE"), eq("local"));
        assertThat(nameCap.getValue()).isEqualTo("my-svc");
    }
}