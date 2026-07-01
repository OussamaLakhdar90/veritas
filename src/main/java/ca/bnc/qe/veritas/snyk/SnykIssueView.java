package ca.bnc.qe.veritas.snyk;

/**
 * Dashboard view of one vulnerability. {@code fixedIn} is the safe version to upgrade to, or null when Snyk has
 * no supported fix (the row is then shown as "tracked only").
 */
public record SnykIssueView(
        String projectName,
        String issueId,
        String severity,
        String title,
        String pkgName,
        String pkgVersion,
        String cve,
        String cwe,
        double cvss,
        int riskScore,
        boolean fixable,
        String fixedIn) {
}
