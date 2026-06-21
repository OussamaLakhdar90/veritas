package ca.bnc.qe.veritas.cli;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.testmgmt.BasisBuilder;
import ca.bnc.qe.veritas.testmgmt.CreateTestCasesService;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Generate ISTQB Test Analyst test cases from the codebase or Jira/Confluence; optionally push to Xray. */
@Component
@Command(name = "create-test-cases", description = "Generate test cases (ISTQB Test Analyst); optionally push to Xray.")
public class CreateTestCasesCommand implements Callable<Integer> {

    private final CreateTestCasesService casesService;
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

    @Option(names = "--confluence", description = "Comma-separated Confluence page ids.")
    private String confluencePages;

    @Option(names = "--project", description = "Xray/Jira project key (required for --push).")
    private String project;

    @Option(names = "--push", description = "Push generated cases to Xray as Test issues.")
    private boolean push;

    public CreateTestCasesCommand(CreateTestCasesService casesService, BasisBuilder basisBuilder, WorkspaceService workspace) {
        this.casesService = casesService;
        this.basisBuilder = basisBuilder;
        this.workspace = workspace;
    }

    @Override
    public Integer call() {
        String basis;
        if (repo != null || (appId != null && repoSlug != null)) {
            Path repoPath = workspace.resolve(appId, repoSlug, branch, repo == null ? null : repo.toString());
            basis = basisBuilder.fromRepo(repoPath);
        } else {
            List<String> pages = confluencePages == null ? List.of() : Arrays.asList(confluencePages.split(","));
            basis = basisBuilder.fromIngest(jql, pages);
        }
        if (push && (project == null || project.isBlank())) {
            System.err.println("--push requires --project <key> (the Jira/Xray project to create tests in).");
            return 2;
        }
        List<TestCase> cases = casesService.generate(name, basis, "local");
        double cost = cases.stream().mapToDouble(TestCase::getEstCostUsd).sum();
        if (push && project != null) {
            for (TestCase tc : cases) {
                casesService.pushToXray(tc, project, "local");
            }
        }
        System.out.println("Generated " + cases.size() + " test case(s)" + (push ? " and pushed to Xray (" + project + ")" : ""));
        System.out.printf("Est. LLM cost: $%.4f%n", cost);
        return 0;
    }
}
