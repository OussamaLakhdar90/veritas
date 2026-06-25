package ca.bnc.qe.veritas.evidence.feature;

import java.util.List;
import ca.bnc.qe.veritas.evidence.ExtractionResult;

/**
 * The output of the evidence pipeline: the feature {@link FeatureIndex}, its deterministic {@link GapReport}, and
 * the underlying {@link ExtractionResult} (source provenance, redaction count, hard-fail signal). The synthesis
 * (Phase 4) and the §6 preview consume this single bundle.
 */
public record FeatureIndexResult(FeatureIndex index, GapReport gaps, ExtractionResult extraction) {

    /** A selected source returned nothing usable — the caller should surface this rather than ship a thin strategy. */
    public boolean hasHardFail() {
        return extraction.hasHardFail();
    }

    /** Total PII/secret spans redacted across all sources, for QE attestation in the preview. */
    public int redactionCount() {
        return extraction.redactionCount();
    }

    /** Per-item fetch failures (for the §6 blind-spot banner). */
    public List<String> fetchFailures() {
        return extraction.fetchFailures();
    }
}
