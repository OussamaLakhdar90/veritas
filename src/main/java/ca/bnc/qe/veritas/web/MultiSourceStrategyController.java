package ca.bnc.qe.veritas.web;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndex;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexBuilder;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexResult;
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

    /** Rough per-feature synthesis cost (§7 budget: ~$0.21 for ~6 features) — the preview's estimate, clearly labelled. */
    private static final double APPROX_COST_PER_FEATURE_USD = 0.035;

    private final WorkspaceService workspace;
    private final JavaSpringExtractor extractor;
    private final FeatureIndexBuilder featureIndexBuilder;
    private final MultiSourceStrategyService strategyService;
    private final CurrentUser currentUser;

    public MultiSourceStrategyController(WorkspaceService workspace, JavaSpringExtractor extractor,
                                        FeatureIndexBuilder featureIndexBuilder,
                                        MultiSourceStrategyService strategyService, CurrentUser currentUser) {
        this.workspace = workspace;
        this.extractor = extractor;
        this.featureIndexBuilder = featureIndexBuilder;
        this.strategyService = strategyService;
        this.currentUser = currentUser;
    }

    /**
     * Build the feature index but stop BEFORE the expensive per-feature synthesis — the §6 "extract + preview"
     * step. Returns the features (with their units, by source), the detected gaps, the realised source mix,
     * the redaction count, any fetch failures, the hard-fail flag, and a rough estimate of what generating the
     * full strategy will cost — so a reviewer can sanity-check the clustering and decide before any DEEP spend.
     */
    @PostMapping("/services/{serviceName}/multi-source-strategy/preview")
    public StrategyPreview preview(@PathVariable String serviceName, @RequestBody MultiSourceStrategyRequest request) {
        SourceSelection selection = buildSelection(request);
        FeatureIndexResult result = featureIndexBuilder.build(selection, currentUser.principalId());
        return toPreview(result);
    }

    @PostMapping("/services/{serviceName}/multi-source-strategy")
    public ResponseEntity<TestStrategy> generate(@PathVariable String serviceName,
                                                 @RequestBody MultiSourceStrategyRequest request) {
        SourceSelection selection = buildSelection(request);
        TestStrategy strategy = strategyService.generate(serviceName, selection, currentUser.principalId());
        return ResponseEntity.status(HttpStatus.CREATED).body(strategy);
    }

    /** Assemble the {@link SourceSelection} from the request, cloning + extracting the code arm when present. */
    private SourceSelection buildSelection(MultiSourceStrategyRequest request) {
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
        return selection;
    }

    private StrategyPreview toPreview(FeatureIndexResult result) {
        FeatureIndex index = result.index();
        List<StrategyPreview.FeatureView> features = index.features().values().stream()
                .map(f -> new StrategyPreview.FeatureView(f.featureId(), f.displayName(), f.status().name(),
                        index.unitsOf(f.featureId()).stream()
                                .map(u -> new StrategyPreview.UnitView(u.id(), u.source().name(), u.type().name(), u.title()))
                                .toList()))
                .toList();
        List<StrategyPreview.GapView> gaps = result.gaps().gaps().stream()
                .map(g -> new StrategyPreview.GapView(g.kind().name(), g.featureId(), g.message()))
                .toList();
        double estimatedCost = index.features().size() * APPROX_COST_PER_FEATURE_USD;
        return new StrategyPreview(features, gaps, index.mix(), result.redactionCount(), result.fetchFailures(),
                result.hasHardFail(), estimatedCost);
    }
}
