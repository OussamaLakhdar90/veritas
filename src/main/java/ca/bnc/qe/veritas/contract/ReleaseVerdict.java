package ca.bnc.qe.veritas.contract;

import java.util.List;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.report.FidelityScore;

/**
 * The one place the release verdict is computed — {@code ContractReportRenderer} (the HTML report) and
 * {@code ExecutiveSummaryController} (the dashboard) both consume THIS, so the two can never disagree.
 *
 * Semantics (mirrors the report's bottom line): {@code blocking} counts BLOCKER/CRITICAL findings among the
 * counted set (needs-attention findings — LLM-origin/low-confidence/AI-disputed — are excluded from gating,
 * exactly like the fidelity score); {@code breaking} counts findings whose type would break a running
 * consumer ({@link DiffEngine#isBreaking}); a sub-gate score with ZERO breaking findings is still
 * release-safe (additive/documentation drift — the business decision recorded in PR #230).
 */
public record ReleaseVerdict(int score, int counted, long blocking, long breaking, boolean allNonBreaking,
                             long aiDisputed) {

    public static ReleaseVerdict of(List<Finding> findings) {
        List<Finding> counted = findings.stream().filter(f -> !FidelityScore.isNeedsAttention(f)).toList();
        long blocking = counted.stream().filter(f -> f.getSeverity() != null
                && ("BLOCKER".equals(f.getSeverity().name()) || "CRITICAL".equals(f.getSeverity().name()))).count();
        long breaking = counted.stream().filter(f -> f.getType() != null && DiffEngine.isBreaking(f.getType())).count();
        long disputed = findings.stream().filter(Finding::isAiDisputed).count();
        return new ReleaseVerdict(FidelityScore.of(findings), counted.size(), blocking, breaking,
                breaking == 0, disputed);
    }

    /** PASS = safe to release (gate met, or every counted finding is additive documentation drift);
     *  WARN = hold for fixes; FAIL = blocking findings — do not release. */
    public String releaseSafe() {
        if (blocking > 0) {
            return "FAIL";
        }
        if (score >= FidelityScore.PASS_THRESHOLD || allNonBreaking) {
            return "PASS";
        }
        return "WARN";
    }
}
