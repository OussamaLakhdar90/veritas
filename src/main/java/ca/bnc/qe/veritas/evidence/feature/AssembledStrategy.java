package ca.bnc.qe.veritas.evidence.feature;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A multi-source test strategy assembled from the feature index: the deliverable JSON (per-feature, evidence-first
 * sections merged into flat arrays + the deterministic gaps + a summary + the run cost), the sections that were
 * dropped because they couldn't be grounded, how many features were covered, the total {@code estCostUsd} (summed
 * across every section's LLM spend), and how many sections were <b>reused</b> verbatim from a prior strategy because
 * the feature's grounding was unchanged (incremental regen — those cost $0).
 */
public record AssembledStrategy(JsonNode deliverable, List<String> droppedSections, int featuresCovered,
                                double estCostUsd, int reusedSections) {

    public AssembledStrategy {
        droppedSections = droppedSections == null ? List.of() : List.copyOf(droppedSections);
    }

    /** Back-compat for callers that don't do incremental reuse: {@code reusedSections = 0}. */
    public AssembledStrategy(JsonNode deliverable, List<String> droppedSections, int featuresCovered, double estCostUsd) {
        this(deliverable, droppedSections, featuresCovered, estCostUsd, 0);
    }
}
