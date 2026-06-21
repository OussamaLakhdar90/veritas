package ca.bnc.qe.veritas.cli;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.testmgmt.BasisBuilder;
import ca.bnc.qe.veritas.testmgmt.TestStrategyService;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Generate a global ISTQB Test Manager strategy from the codebase, or from Jira/Confluence user stories. */
@Component
@Command(name = "test-strategy", description = "Generate a global test strategy (ISTQB Test Manager).")
public class TestStrategyCommand implements Callable<Integer> {

    private final TestStrategyService strategyService;
    private final BasisBuilder basisBuilder;
    private final WorkspaceService workspace;

    @Option(names = {"-n", "--name"}, required = true, description = "Service name.")
    private String name;

    @Option(names = "--repo", description = "Local repo path (codebase basis).")
    private Path repo;

    @Option(names = "--app-id", description = "Bitbucket app-id (codebase basis via clone).")
    private String appId;

    @Option(names = "--repo-slug", description = "Repo slug under the app-id.")
    private String repoSlug;

    @Option(names = "--branch", description = "Branch to clone.")
    private String branch;

    @Option(names = "--jql", description = "Jira JQL (no-codebase basis).")
    private String jql;

    @Option(names = "--confluence", description = "Comma-separated Confluence page ids (no-codebase basis).")
    private String confluencePages;

    public TestStrategyCommand(TestStrategyService strategyService, BasisBuilder basisBuilder, WorkspaceService workspace) {
        this.strategyService = strategyService;
        this.basisBuilder = basisBuilder;
        this.workspace = workspace;
    }

    @Override
    public Integer call() {
        String basis;
        String source;
        if (repo != null || (appId != null && repoSlug != null)) {
            Path repoPath = workspace.resolve(appId, repoSlug, branch, repo == null ? null : repo.toString());
            basis = basisBuilder.fromRepo(repoPath);
            source = "CODE";
        } else {
            List<String> pages = confluencePages == null ? List.of() : Arrays.asList(confluencePages.split(","));
            basis = basisBuilder.fromIngest(jql, pages);
            source = "JIRA_CONFLUENCE";
        }
        TestStrategy strategy = strategyService.generate(name, basis, source, "local");
        System.out.println("Strategy " + strategy.getId() + " created for " + name + " (source " + source + ")");
        System.out.printf("Est. LLM cost: $%.4f%n", strategy.getEstCostUsd());
        return 0;
    }
}
