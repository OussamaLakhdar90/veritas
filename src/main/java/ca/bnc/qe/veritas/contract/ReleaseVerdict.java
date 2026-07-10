package ca.bnc.qe.veritas.contract;

import java.util.List;
import ca.bnc.qe.veritas.config.GateProperties;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.finding.CountedFindings;
import ca.bnc.qe.veritas.finding.Finding;

/**
 * The one place the release verdict is computed — {@code ContractReportRenderer} (the HTML report) and
 * {@code ExecutiveSummaryController} (the dashboard) both consume THIS, so the two can never disagree.
 *
 * <p>A categorical, per-severity quality gate (SonarQube-style), NOT a composite score. It operates on the
 * <b>counted</b> set only — {@link CountedFindings#isNeedsAttention needs-attention} findings (LLM-origin /
 * low-confidence / design-quality / AI-disputed) are excluded from gating, exactly as before. {@code breaking}
 * counts findings whose type would break a running consumer ({@link DiffEngine#isBreaking} — semver-major); the
 * additive/documentation drift the engine deliberately treats as release-safe is non-breaking and never gates.</p>
 *
 * <p>Verdict, against {@link GateProperties} thresholds (defaults 0/0/0 = zero tolerance for anything breaking):
 * <b>FAIL</b> when any severity cap is exceeded; <b>WARN</b> when only non-breaking MAJOR/MINOR drift remains;
 * <b>PASS</b> when clean (or INFO-only). Grounded in oasdiff/openapi-diff breaking classification, OWASP API1/2/5,
 * and semver backward-compatibility — see {@code DiffEngine.severityOf}.</p>
 */
public record ReleaseVerdict(int counted, long blocker, long critical, long breaking, long major, long minor,
                             long unspecified, long aiDisputed, String releaseSafe) {

    public static ReleaseVerdict of(List<Finding> findings, GateProperties gate) {
        List<Finding> counted = findings.stream().filter(f -> !CountedFindings.isNeedsAttention(f)).toList();
        long blocker = sev(counted, "BLOCKER");
        long critical = sev(counted, "CRITICAL");
        long major = sev(counted, "MAJOR");
        long minor = sev(counted, "MINOR");
        long unspecified = sev(counted, "UNSPECIFIED");
        long breaking = counted.stream()
                .filter(f -> f.getType() != null && DiffEngine.isBreaking(f.getType())).count();
        long disputed = findings.stream().filter(Finding::isAiDisputed).count();
        return new ReleaseVerdict(counted.size(), blocker, critical, breaking, major, minor, unspecified, disputed,
                verdict(blocker, critical, breaking, major, minor, unspecified, gate));
    }

    private static long sev(List<Finding> counted, String name) {
        return counted.stream().filter(f -> f.getSeverity() != null && name.equals(f.getSeverity().name())).count();
    }

    /**
     * FAIL above any severity cap (a breaking change / security or endpoint break must not ship); else WARN while any
     * non-breaking MAJOR/MINOR drift remains (safe to release, clean-up recommended); else PASS.
     */
    private static String verdict(long blocker, long critical, long breaking, long major, long minor,
                                  long unspecified, GateProperties gate) {
        if (blocker > gate.getMaxBlocker() || critical > gate.getMaxCritical() || breaking > gate.getMaxBreaking()) {
            return "FAIL";
        }
        // A non-breaking MAJOR/MINOR drift, or an UNSPECIFIED (not-yet-classified) finding, holds the verdict at WARN —
        // an unclassified finding can never yield a clean PASS until a human classifies it.
        return (major + minor + unspecified > 0) ? "WARN" : "PASS";
    }

    /** BLOCKER + CRITICAL among the counted set — the "release-blocking" count the dashboard/report surface. */
    public long blocking() {
        return blocker + critical;
    }

    /** True when nothing in the counted set would break a running consumer (all drift is additive/documentation). */
    public boolean allNonBreaking() {
        return breaking == 0;
    }
}
