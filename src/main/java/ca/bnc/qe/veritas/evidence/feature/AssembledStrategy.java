package ca.bnc.qe.veritas.evidence.feature;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A multi-source test strategy assembled from the feature index: the deliverable JSON (per-feature, evidence-first
 * sections merged into flat arrays + the deterministic gaps + a summary + the run cost), the sections that were
 * dropped because they couldn't be grounded, how many features were covered, and the total {@code estCostUsd}
 * (summed across every section's LLM spend — the answer to "what did this strategy cost").
 */
public record AssembledStrategy(JsonNode deliverable, List<String> droppedSections, int featuresCovered, double estCostUsd) {

    public AssembledStrategy {
        droppedSections = droppedSections == null ? List.of() : List.copyOf(droppedSections);
    }
}
