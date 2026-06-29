package ca.bnc.qe.veritas.report;

import java.util.List;
import ca.bnc.qe.veritas.finding.Finding;

/**
 * Deterministic Contract Fidelity Score (0–100) — shared by the report renderer (display) and the validation
 * service (persistence + trend), so both always agree. Only <b>counted</b> findings affect the score; items
 * flagged for manual review (AI-origin / low-confidence / design) are excluded, matching the report's split.
 */
public final class FidelityScore {

    private FidelityScore() {
    }

    /** Quality-gate threshold: scores at or above this pass. */
    public static final int PASS_THRESHOLD = 90;

    public static int of(List<Finding> findings) {
        int penalty = 0;
        for (Finding f : findings) {
            if (isNeedsAttention(f)) {
                continue;
            }
            switch (f.getSeverity() != null ? f.getSeverity().name() : "") {
                case "BLOCKER" -> penalty += 25;
                case "CRITICAL" -> penalty += 15;
                case "MAJOR" -> penalty += 8;
                case "MINOR" -> penalty += 3;
                default -> { }
            }
        }
        return Math.max(0, 100 - penalty);
    }

    /** Unconfirmed items (AI-origin / low confidence / design-quality / AI-disputed) — excluded from the score. */
    public static boolean isNeedsAttention(Finding f) {
        String type = f.getType() != null ? f.getType().name() : "";
        boolean designOnly = type.equals("DESIGN_QUALITY") || type.equals("TEST_BASIS_GAP");
        boolean llm = f.getOrigin() != null && !f.getOrigin().equalsIgnoreCase("DETERMINISTIC");
        boolean lowConf = f.getConfidence() != null && f.getConfidence().name().equals("LOW");
        // The reconcile LLM flagged this as a likely false positive: it leaves the score + release gate (still listed
        // for a human, severity intact). This is the single chokepoint both the report split and blocking count use.
        boolean aiDisputed = f.isAiDisputed();
        return designOnly || llm || lowConf || aiDisputed;
    }
}
