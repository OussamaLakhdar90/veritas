package ca.bnc.qe.veritas.evidence.adapter;

import java.util.List;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.FetchProvenance;
import ca.bnc.qe.veritas.evidence.SourceKind;

/**
 * What one source adapter produced in a run: the evidence units (text already redacted), the fetch counts for
 * {@link FetchProvenance}, and how many spans the {@code Redactor} replaced (for the attestation total).
 */
public record SourceExtraction(
        SourceKind kind,
        List<EvidenceUnit> units,
        int requested,
        int fetched,
        List<String> failed,
        int redactions) {

    public SourceExtraction {
        units = units == null ? List.of() : List.copyOf(units);
        failed = failed == null ? List.of() : List.copyOf(failed);
    }

    public FetchProvenance.Counts counts() {
        return new FetchProvenance.Counts(requested, fetched, failed);
    }
}
