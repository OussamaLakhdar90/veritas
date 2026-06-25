package ca.bnc.qe.veritas.web;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import ca.bnc.qe.veritas.evidence.feature.MultiSourceStrategyService;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.settings.CurrentUser;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generates a multi-source test strategy from any combination of code + Jira + Confluence. Assembles a
 * {@link SourceSelection} from the request — for the <b>code arm</b> it reuses the contract-validation clone flow
 * ({@link WorkspaceService#resolve} → {@link JavaSpringExtractor#extract}) — then runs the pipeline via
 * {@link MultiSourceStrategyService} and returns the persisted {@link TestStrategy}.
 *
 * <p>Synchronous for now (fast in mock mode / for small corpora). A large real run clones a repo and makes
 * several DEEP LLM calls, so an async variant mirroring {@code POST /scans} (202 + poll) is the production
 * follow-up.
 */
@RestController
@RequestMapping("/api/v1")
@Slf4j
public class MultiSourceStrategyController {

    private final WorkspaceService workspace;
    private final JavaSpringExtractor extractor;
    private final MultiSourceStrategyService strategyService;
    private final CurrentUser currentUser;

    public MultiSourceStrategyController(WorkspaceService workspace, JavaSpringExtractor extractor,
                                        MultiSourceStrategyService strategyService, CurrentUser currentUser) {
        this.workspace = workspace;
        this.extractor = extractor;
        this.strategyService = strategyService;
        this.currentUser = currentUser;
    }

    @PostMapping("/services/{serviceName}/multi-source-strategy")
    public ResponseEntity<TestStrategy> generate(@PathVariable String serviceName,
                                                 @RequestBody MultiSourceStrategyRequest request) {
        ApiModel code = null;
        if (request.code() != null && request.code().selected()) {
            // Code arm: clone (or use a local path) then extract the API model — the contract-validation flow.
            Path repo = workspace.resolve(request.code().appId(), request.code().repoSlug(),
                    request.code().branch(), request.code().repoPath());
            code = extractor.extract(repo);
        }
        String jql = request.jira() != null ? request.jira().jql() : null;
        int maxResults = request.jira() != null && request.jira().maxResults() != null
                ? request.jira().maxResults() : 50;
        List<String> pageIds = request.confluence() != null && request.confluence().pageIds() != null
                ? request.confluence().pageIds() : List.of();

        SourceSelection selection = new SourceSelection(code, jql, maxResults, pageIds);
        if (selection.selected().isEmpty()) {
            throw new IllegalArgumentException("Select at least one source: code (app-id + repo-slug, or repo-path), "
                    + "jira (a jql), or confluence (page ids).");
        }

        TestStrategy strategy = strategyService.generate(serviceName, selection, currentUser.principalId());
        return ResponseEntity.status(HttpStatus.CREATED).body(strategy);
    }
}
