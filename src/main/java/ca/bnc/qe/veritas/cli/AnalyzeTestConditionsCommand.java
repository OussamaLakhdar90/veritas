package ca.bnc.qe.veritas.cli;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.testmgmt.BasisBuilder;
import ca.bnc.qe.veritas.testmgmt.TestAnalysisService;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Identify and prioritize test conditions (ISTQB test analysis) from the codebase or Jira/Confluence, against the
 * service's approved strategy. The Test Condition List is the work product between the strategy and the test cases.
 */
@Component
@Command(name = "analyze-test-conditions",
        description = "Identify & prioritize test conditions (ISTQB test analysis) from the approved strategy + basis.")
public class AnalyzeTestConditionsCommand implements Callable<Integer> {

    private final TestAnalysisService analysisService;
    private final BasisBuilder basisBuilder;
    private final WorkspaceService workspace;

    @Option(names = {"-n", "--name"}, required = true, description = "Service name (must already have a strategy).")
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

    public AnalyzeTestConditionsCommand(TestAnalysisService analysisService, BasisBuilder basisBuilder,
                                        WorkspaceService workspace) {
        this.analysisService = analysisService;
        this.basisBuilder = basisBuilder;
        this.workspace = workspace;
    }

    @Override
    public Integer call() {
        String basis;
        if (repo != null || (appId != null && repoSlug != null)) {
            Path repoPath = workspace.resolve(appId, repoSlug, branch, repo == null ? null : repo.toString());
            try {
                basis = basisBuilder.fromRepo(repoPath);
            } finally {
                workspace.cleanup(repoPath);
            }
        } else {
            List<String> pages = confluencePages == null ? List.of() : Arrays.asList(confluencePages.split(","));
            basis = basisBuilder.fromIngest(jql, pages);
        }
        List<TestCondition> conditions = analysisService.analyze(name, basis, "local");
        double cost = conditions.stream().mapToDouble(TestCondition::getEstCostUsd).sum();
        System.out.println("Identified " + conditions.size() + " test condition(s) for " + name);
        long automated = conditions.stream().filter(c -> "AUTOMATED".equals(c.getAutomation())).count();
        long manual = conditions.stream().filter(c -> "MANUAL".equals(c.getAutomation())).count();
        System.out.println("Automation split: " + automated + " AUTOMATED, " + manual + " MANUAL, "
                + (conditions.size() - automated - manual) + " CANDIDATE");
        System.out.printf("Est. LLM cost: $%.4f%n", cost);
        return 0;
    }
}
