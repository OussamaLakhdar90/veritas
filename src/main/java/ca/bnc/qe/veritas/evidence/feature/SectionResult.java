package ca.bnc.qe.veritas.evidence.feature;

import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One evidence-first section generation: the validated section node (empty when it couldn't be grounded and was
 * dropped) plus the USD cost actually spent on it — summed across the first try + any regenerate, including a
 * dropped section (its tokens were still spent). The assembler totals these onto the strategy's {@code estCostUsd}.
 */
public record SectionResult(Optional<JsonNode> node, double costUsd) {

    public boolean isPresent() {
        return node.isPresent();
    }

    static SectionResult empty() {
        return new SectionResult(Optional.empty(), 0.0);
    }
}
