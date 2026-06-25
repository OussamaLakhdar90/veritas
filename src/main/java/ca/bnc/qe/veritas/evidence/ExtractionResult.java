package ca.bnc.qe.veritas.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The result of a multi-source extraction: the combined evidence (text already redacted), the per-source
 * {@link FetchProvenance}, the realised {@link SourceMix} (from successes only), the total redaction count for
 * QE attestation, and which sources were selected (for the §1.3 hard-fail gate).
 */
public record ExtractionResult(
        List<EvidenceUnit> units,
        FetchProvenance provenance,
        SourceMix mix,
        int redactionCount,
        Set<SourceKind> selected) {

    public ExtractionResult {
        units = units == null ? List.of() : List.copyOf(units);
        selected = selected == null ? Set.of() : Set.copyOf(selected);
    }

    /** A selected source returned nothing usable — the run should stop before spend (§1.3). POLICY is exempt. */
    public boolean hasHardFail() {
        return provenance.hasHardFail(selected);
    }

    /** Human-readable per-item fetch failures across all sources (for the §6 preview blind-spot banner). */
    public List<String> fetchFailures() {
        List<String> all = new ArrayList<>();
        provenance.bySource().values().forEach(c -> all.addAll(c.failed()));
        return all;
    }
}
