package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.evidence.SourceMix;

/**
 * The §6 wizard preview: what the multi-source pipeline extracted and clustered, BEFORE the expensive synthesis,
 * so a reviewer can sanity-check the feature clustering and the cost and decide whether to generate.
 *
 * @param features        the clustered features, each with its units (by source) and implementation status
 * @param gaps            the deterministic coverage gaps detected
 * @param mix             which sources actually contributed
 * @param redactionCount  PII/secret spans redacted across all sources (for QE attestation)
 * @param fetchFailures   per-item fetch failures (blind-spot banner)
 * @param hardFail        true if a selected source returned nothing usable (generating would be refused)
 * @param estimatedCostUsd a rough estimate of what generating the full strategy will cost (clearly an estimate)
 */
public record StrategyPreview(
        List<FeatureView> features,
        List<GapView> gaps,
        SourceMix mix,
        int redactionCount,
        List<String> fetchFailures,
        boolean hardFail,
        double estimatedCostUsd) {

    public record FeatureView(String featureId, String displayName, String status, List<UnitView> units) {}

    public record UnitView(String id, String source, String type, String title) {}

    public record GapView(String kind, String feature, String message) {}
}
