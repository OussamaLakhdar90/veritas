package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.evidence.SourceMix;

/**
 * The §6 wizard preview: what the multi-source pipeline extracted and clustered, BEFORE the expensive synthesis,
 * so a reviewer can sanity-check the feature clustering and the cost, edit it, and decide whether to generate.
 *
 * @param snapshotId      the persisted, editable feature index this preview reflects — the id the wizard edits and
 *                        then generates from (so generate reuses the index instead of re-running the pipeline)
 * @param features        the clustered features, each with its units (by source), implementation status, and pin
 * @param gaps            the deterministic coverage gaps detected
 * @param mix             which sources actually contributed
 * @param redactionCount  PII/secret spans redacted across all sources (for QE attestation)
 * @param fetchFailures   per-item fetch failures (blind-spot banner)
 * @param hardFail        true if a selected source returned nothing usable (generating would be refused)
 * @param estimatedCostUsd a rough estimate of what generating the full strategy will cost (clearly an estimate)
 * @param carryForwardNotes on a "re-run keeping my edits" preview, any reviewer edits that could NOT be re-applied
 *                        because their features vanished from the new extraction (empty on a normal preview)
 */
public record StrategyPreview(
        String snapshotId,
        List<FeatureView> features,
        List<GapView> gaps,
        SourceMix mix,
        int redactionCount,
        List<String> fetchFailures,
        boolean hardFail,
        double estimatedCostUsd,
        List<String> carryForwardNotes) {

    public record FeatureView(String featureId, String displayName, String status, List<UnitView> units,
                              boolean pinned) {}

    public record UnitView(String id, String source, String type, String title) {}

    public record GapView(String kind, String feature, String message) {}
}
