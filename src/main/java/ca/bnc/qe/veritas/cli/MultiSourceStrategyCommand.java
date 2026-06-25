package ca.bnc.qe.veritas.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import ca.bnc.qe.veritas.evidence.feature.MultiSourceStrategyService;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Generate a multi-source test strategy from any combination of code + Jira + Confluence — the CLI peer of
 * {@code POST /services/{svc}/multi-source-strategy}. The code arm reuses the contract-validation clone+extract
 * flow ({@link WorkspaceService} → {@link JavaSpringExtractor}); the evidence pipeline then clusters the sources
 * into features and synthesizes an evidence-first strategy. At least one source must be supplied.
 */
@Component
@Command(name = "multi-source-strategy",
        description = "Generate a test strategy from any combination of code + Jira + Confluence.")
public class MultiSourceStrategyCommand implements Callable<Integer> {

    @Option(names = {"-n", "--name"}, required = true, description = "Service name for the strategy.")
    private String name;

    @Option(names = {"-r", "--repo"}, description = "Local path to the Spring Boot source root (code arm).")
    private Path repo;

    @Option(names = "--app-id", description = "Bitbucket app-id (project key) to discover & clone the repo from.")
    private String appId;

    @Option(names = "--repo-slug", description = "Repo slug to clone under the app-id.")
    private String repoSlug;

    @Option(names = "--branch", description = "Branch to clone (default: repo default branch).")
    private String branch;

    @Option(names = "--jql", description = "Jira JQL selecting the intent issues (Jira arm).")
    private String jql;

    @Option(names = "--max-results", defaultValue = "50", description = "Max Jira issues to fetch (default 50).")
    private int maxResults;

    @Option(names = "--confluence", split = ",", description = "Confluence page ids (comma-separated; Confluence arm).")
    private List<String> confluencePages;

    private final WorkspaceService workspace;
    private final JavaSpringExtractor extractor;
    private final MultiSourceStrategyService strategyService;

    public MultiSourceStrategyCommand(WorkspaceService workspace, JavaSpringExtractor extractor,
                                      MultiSourceStrategyService strategyService) {
        this.workspace = workspace;
        this.extractor = extractor;
        this.strategyService = strategyService;
    }

    @Override
    public Integer call() {
        ApiModel code = null;
        if (repo != null || (appId != null && repoSlug != null)) {
            Path repoPath = workspace.resolve(appId, repoSlug, branch, repo == null ? null : repo.toString());
            try {
                code = extractor.extract(repoPath);
            } finally {
                workspace.cleanup(repoPath);   // the API model is in memory now; drop the cloned temp dir
            }
        }
        List<String> pages = confluencePages == null ? List.of() : confluencePages;
        SourceSelection selection = new SourceSelection(code, jql, maxResults, pages);
        if (selection.selected().isEmpty()) {
            System.err.println("Select at least one source: --repo (or --app-id + --repo-slug), --jql, or --confluence.");
            return 2;
        }

        TestStrategy strategy = strategyService.generate(name, selection, "local");
        System.out.println("Strategy " + strategy.getId() + " created for " + name + " (multi-source)");
        System.out.printf("Est. LLM cost: $%.4f%n", strategy.getEstCostUsd());
        return 0;
    }
}
