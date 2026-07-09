package ca.bnc.qe.veritas.finding;

/**
 * The single chokepoint for "does this finding COUNT toward the release gate?" — shared by {@code ReleaseVerdict}
 * (the gate) and {@code ContractReportRenderer} (the report split) so the two can never disagree. Findings flagged
 * for manual review are excluded from gating: AI-authored (non-deterministic origin), LOW-confidence, design-quality
 * advisories, and any the reconcile LLM disputed as a likely false positive. They stay listed for a human — their
 * severity is intact — they just don't move the verdict.
 */
public final class CountedFindings {

    private CountedFindings() {
    }

    /** Unconfirmed items (AI-origin / low confidence / design-quality / AI-disputed) — excluded from the gate. */
    public static boolean isNeedsAttention(Finding f) {
        String type = f.getType() != null ? f.getType().name() : "";
        boolean designOnly = type.equals("DESIGN_QUALITY") || type.equals("TEST_BASIS_GAP");
        boolean llm = f.getOrigin() != null && !f.getOrigin().equalsIgnoreCase("DETERMINISTIC");
        boolean lowConf = f.getConfidence() != null && f.getConfidence().name().equals("LOW");
        // The reconcile LLM flagged this as a likely false positive: it leaves the gate (still listed for a human,
        // severity intact). This is the single chokepoint both the report split and the release verdict use.
        boolean aiDisputed = f.isAiDisputed();
        return designOnly || llm || lowConf || aiDisputed;
    }
}
