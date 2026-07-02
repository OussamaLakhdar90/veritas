package ca.bnc.qe.veritas.snyk;

/**
 * A managerial roll-up of the Snyk module: what's being watched, what's currently OPEN (found), and what Veritas
 * has DONE about it (fixes started/merged, PRs opened, and the LLM spend on the breaking-change checks). Drives the
 * executive dashboard's security-posture card — "what we found vs what we fixed".
 */
public record SnykSummaryView(
        int watchedApps,
        int projects,
        // Currently open (latest snapshot across all watches), by severity.
        int critical,
        int high,
        int medium,
        int low,
        int fixable,
        long unseenAlerts,
        // What Veritas has done about it.
        long fixesStarted,
        long fixesInProgress,
        long fixesMerged,
        long fixesBreaking,
        long prsOpened,
        long llmChecks,
        double llmCostUsd) {

    public int openTotal() {
        return critical + high + medium + low;
    }
}
