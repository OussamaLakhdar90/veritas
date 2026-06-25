package ca.bnc.qe.veritas.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import ca.bnc.qe.veritas.evidence.feature.AsyncStrategyGenerator;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndex;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexBuilder;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexResult;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexSnapshotService;
import ca.bnc.qe.veritas.evidence.feature.MultiSourceStrategyService;
import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshot;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.settings.CurrentUser;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generates a multi-source test strategy from any combination of code + Jira + Confluence. Assembles a
 * {@link SourceSelection} from the request — for the <b>code arm</b> it reuses the contract-validation clone flow
 * ({@link WorkspaceService#resolve} → {@link JavaSpringExtractor#extract}) — then runs the pipeline via
 * {@link MultiSourceStrategyService} and returns the persisted {@link TestStrategy}.
 *
 * <p>The §6 wizard flow is preview → (edit) → generate, backed by a persisted {@link FeatureIndexSnapshot}:
 * <b>preview</b> extracts + clusters once and persists the editable feature index; the wizard then
 * <b>rename</b>/<b>merge</b>/<b>pin</b> features on that snapshot; <b>generate-from-snapshot</b> synthesizes from
 * the same (edited) index — so the expensive extract + repo clone is not re-run on generate. The one-shot
 * {@code POST .../multi-source-strategy} (extract + generate together) is kept for the API/CLI.
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
    private final FeatureIndexSnapshotService snapshotService;
    private final AsyncStrategyGenerator asyncStrategyGenerator;
    private final CurrentUser currentUser;

    public MultiSourceStrategyController(WorkspaceService workspace, JavaSpringExtractor extractor,
                                        FeatureIndexBuilder featureIndexBuilder,
                                        MultiSourceStrategyService strategyService,
                                        FeatureIndexSnapshotService snapshotService,
                                        AsyncStrategyGenerator asyncStrategyGenerator, CurrentUser currentUser) {
        this.workspace = workspace;
        this.extractor = extractor;
        this.featureIndexBuilder = featureIndexBuilder;
        this.strategyService = strategyService;
        this.snapshotService = snapshotService;
        this.asyncStrategyGenerator = asyncStrategyGenerator;
        this.currentUser = currentUser;
    }

    /**
     * Build the feature index but stop BEFORE the expensive per-feature synthesis — the §6 "extract + preview"
     * step — and PERSIST it as an editable snapshot. Returns the features (with their units, by source), the
     * detected gaps, the realised source mix, the redaction count, any fetch failures, the hard-fail flag, a rough
     * cost estimate, and the {@code snapshotId} the wizard edits and then generates from.
     */
    @PostMapping("/services/{serviceName}/multi-source-strategy/preview")
    public StrategyPreview preview(@PathVariable String serviceName, @RequestBody MultiSourceStrategyRequest request,
                                   @RequestParam(required = false) String carryForwardFrom) {
        SourceSelection selection = buildSelection(request);
        FeatureIndexResult result = featureIndexBuilder.build(selection, currentUser.principalId());
        if (carryForwardFrom == null || carryForwardFrom.isBlank()) {
            return toPreview(snapshotService.create(serviceName, result, currentUser.principalId()), List.of());
        }
        // Lineage re-run (§3.2): re-extracted the same sources — carry the reviewer's edits forward onto the fresh
        // index. Only the owner of the prior preview may carry it forward (else 404, same as snapshot enumeration).
        FeatureIndexSnapshot prior = ownedSnapshot(carryForwardFrom)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No prior preview '" + carryForwardFrom + "' to carry edits forward from."));
        FeatureIndexSnapshotService.CarryForward carried =
                snapshotService.createCarryingForward(serviceName, result, currentUser.principalId(), prior);
        return toPreview(carried.snapshot(), carried.notes());
    }

    /** Re-fetch a persisted preview snapshot (e.g. on wizard reload). */
    @GetMapping("/multi-source-strategy/snapshots/{id}")
    public ResponseEntity<StrategyPreview> snapshot(@PathVariable String id) {
        return ownedSnapshot(id)
                .map(s -> ResponseEntity.ok(toPreview(s, List.of())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Rename one feature's display label. */
    @PatchMapping("/multi-source-strategy/snapshots/{id}/rename")
    public ResponseEntity<StrategyPreview> rename(@PathVariable String id, @RequestBody RenameRequest req) {
        return ownedSnapshot(id)
                .map(s -> ResponseEntity.ok(toPreview(snapshotService.rename(s, req.featureId(), req.name()), List.of())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Merge two or more features into one. */
    @PatchMapping("/multi-source-strategy/snapshots/{id}/merge")
    public ResponseEntity<StrategyPreview> merge(@PathVariable String id, @RequestBody MergeRequest req) {
        return ownedSnapshot(id)
                .map(s -> ResponseEntity.ok(toPreview(snapshotService.merge(s, req.featureIds(), req.name()), List.of())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Pin (reviewer-confirm/lock) or unpin a feature. */
    @PatchMapping("/multi-source-strategy/snapshots/{id}/pin")
    public ResponseEntity<StrategyPreview> pin(@PathVariable String id, @RequestBody PinRequest req) {
        if (req.pinned() == null) {
            throw new IllegalArgumentException("'pinned' (true or false) is required.");
        }
        return ownedSnapshot(id)
                .map(s -> ResponseEntity.ok(toPreview(snapshotService.pin(s, req.featureId(), req.pinned()), List.of())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Generate the strategy from a (possibly edited) snapshot — reuses the index, no second pipeline run. Generation
     * is one-shot per snapshot: the claim is taken atomically (under the snapshot's optimistic lock) BEFORE the
     * paid synthesis, so a concurrent or repeat POST is a conflict (409) rather than a duplicate, paid run that
     * would also re-point the audit link. Start a fresh preview to generate again.
     */
    @PostMapping("/multi-source-strategy/snapshots/{id}/strategy")
    public ResponseEntity<StrategyAccepted> generateFromSnapshot(@PathVariable String id) {
        if (ownedSnapshot(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Claim synchronously (atomic one-shot → 409 if already taken/generated) BEFORE handing off, so a duplicate or
        // concurrent generate is rejected fast and the paid synthesis runs at most once. Then synthesize ASYNC and
        // return 202 — the wizard polls GET .../snapshots/{id} until generatedStrategyId (done) or generationError
        // (failed). A large real run makes several DEEP LLM calls, so it must not block the HTTP request.
        FeatureIndexSnapshot snapshot = snapshotService.claimForGeneration(id);
        asyncStrategyGenerator.submit(snapshot);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new StrategyAccepted(id, "GENERATING"));
    }

    /** 202 body for an async generate: the snapshot to poll, and a status the wizard shows while it waits. */
    public record StrategyAccepted(String snapshotId, String status) {}

    /**
     * Resolve a snapshot only if the current principal owns it — a non-owner (in the multi-user 'server' profile)
     * gets 404, not the row, so snapshot ids can't be enumerated across users. Local-first is unaffected (every
     * row's owner and the current principal are both {@code local}). A null owner (defensive) is treated as visible.
     */
    private Optional<FeatureIndexSnapshot> ownedSnapshot(String id) {
        String principal = currentUser.principalId();
        return snapshotService.find(id).filter(s -> s.getOwner() == null || s.getOwner().equals(principal));
    }

    @PostMapping("/services/{serviceName}/multi-source-strategy")
    public ResponseEntity<TestStrategy> generate(@PathVariable String serviceName,
                                                 @RequestBody MultiSourceStrategyRequest request) {
        SourceSelection selection = buildSelection(request);
        TestStrategy strategy = strategyService.generate(serviceName, selection, currentUser.principalId());
        return ResponseEntity.status(HttpStatus.CREATED).body(strategy);
    }

    public record RenameRequest(String featureId, String name) {}

    public record MergeRequest(List<String> featureIds, String name) {}

    public record PinRequest(String featureId, Boolean pinned) {}

    /** Assemble the {@link SourceSelection} from the request, cloning + extracting the code arm when present. */
    private SourceSelection buildSelection(MultiSourceStrategyRequest request) {
        ApiModel code = null;
        if (request.code() != null && request.code().selected()) {
            // Code arm: clone (or use a local path) then extract the API model — the contract-validation flow.
            Path repo = workspace.resolve(request.code().appId(), request.code().repoSlug(),
                    request.code().branch(), request.code().repoPath());
            try {
                code = extractor.extract(repo);
            } finally {
                workspace.cleanup(repo);   // the API model is in memory now; drop the cloned temp dir
            }
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

    private StrategyPreview toPreview(FeatureIndexSnapshot snapshot, List<String> carryForwardNotes) {
        FeatureIndexResult result = snapshotService.resultOf(snapshot);
        Set<String> pinned = snapshotService.pinnedOf(snapshot);
        FeatureIndex index = result.index();
        List<StrategyPreview.FeatureView> features = index.features().values().stream()
                .map(f -> new StrategyPreview.FeatureView(f.featureId(), f.displayName(), f.status().name(),
                        index.unitsOf(f.featureId()).stream()
                                .map(u -> new StrategyPreview.UnitView(u.id(), u.source().name(), u.type().name(), u.title()))
                                .toList(),
                        pinned.contains(f.featureId())))
                .toList();
        List<StrategyPreview.GapView> gaps = result.gaps().gaps().stream()
                .map(g -> new StrategyPreview.GapView(g.kind().name(), g.featureId(), g.message()))
                .toList();
        double estimatedCost = index.features().size() * APPROX_COST_PER_FEATURE_USD;
        return new StrategyPreview(snapshot.getId(), features, gaps, index.mix(), result.redactionCount(),
                result.fetchFailures(), result.hasHardFail(), estimatedCost, List.copyOf(carryForwardNotes),
                snapshot.getGeneratedStrategyId(), snapshot.getGenerationStartedAt(), snapshot.getGenerationError());
    }
}
