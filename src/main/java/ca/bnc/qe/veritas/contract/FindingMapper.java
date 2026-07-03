package ca.bnc.qe.veritas.contract;

import java.util.Arrays;
import java.util.List;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.FindingRecord;

/**
 * Rebuilds an in-memory {@link Finding} from a persisted {@link FindingRecord} — the inverse of
 * {@link ScanPersistence}'s write — so a report can be re-rendered from the source of truth and reflect the
 * CURRENT disposition (status + who/when). Enum parsing is guarded: an unknown stored value yields null rather
 * than throwing, so a legacy/odd row never breaks a re-render.
 */
public final class FindingMapper {

    private FindingMapper() {
    }

    public static Finding toFinding(FindingRecord r) {
        SourceRef evidence = r.getCodeFile() == null ? null
                : SourceRef.code(r.getCodeFile(),
                        r.getCodeStartLine() == null ? 0 : r.getCodeStartLine(),
                        r.getCodeEndLine() == null ? 0 : r.getCodeEndLine(),
                        r.getCodeSnippet());
        return Finding.builder()
                .findingId(r.getFingerprint())
                .type(parse(FindingType.class, r.getType()))
                .layer(parse(Layer.class, r.getLayer()))
                .severity(parse(Severity.class, r.getSeverity()))
                .confidence(parse(Confidence.class, r.getConfidence()))
                .origin(r.getOrigin())
                .endpoint(r.getEndpoint())
                .affectedEndpoints(splitCsv(r.getAffectedEndpoints()))
                .specLocus(r.getSpecLocus())
                .specSource(r.getSpecSource())
                .summary(r.getSummary())
                .explanation(r.getExplanation())
                .codeEvidence(evidence)
                .currentYamlFragment(r.getCurrentYamlFragment())
                .proposedFix(r.getProposedFix())
                .citation(r.getCitation())
                .status(r.getStatus() == null ? "OPEN" : r.getStatus())
                .reviewedBy(r.getReviewedBy())
                .reviewedAt(r.getReviewedAt())
                .aiDisputed(r.isAiDisputed())
                .aiDisputeReason(r.getAiDisputeReason())
                .build();
    }

    /** CSV → list of affected endpoints (empty when null/blank). Inverse of {@code String.join(",", …)}. */
    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static <E extends Enum<E>> E parse(Class<E> cls, String v) {
        if (v == null) {
            return null;
        }
        try {
            return Enum.valueOf(cls, v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
