package ca.bnc.qe.veritas.integration.snyk;

import java.util.List;

/**
 * One aggregated Snyk vulnerability on a project: severity, the vulnerable {@code package@version}, its CVE/CWE +
 * CVSS, the Snyk priority (risk) score, and — <b>only when Snyk has a supported fix</b> — the safe {@code fixedIn}
 * versions. {@code fixable} is false for the common "no supported fix" issues; those are watched, never auto-upgraded.
 */
public record SnykIssue(
        String issueId,
        String severity,      // critical | high | medium | low
        String title,
        String pkgName,       // e.g. com.fasterxml.jackson.core:jackson-databind
        String pkgVersion,    // e.g. 3.1.1
        String cve,
        String cwe,
        double cvss,
        int riskScore,
        boolean fixable,
        List<String> fixedIn) {

    /** The recommended safe version to upgrade to, or {@code null} when Snyk has no supported fix. */
    public String safeVersion() {
        return fixedIn == null || fixedIn.isEmpty() ? null : fixedIn.get(0);
    }
}
