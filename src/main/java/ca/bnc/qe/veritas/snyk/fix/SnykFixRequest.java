package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/**
 * What the user asked to fix: one fixable Snyk issue, the app-ids to propagate to, and the Jira ticket to drive it
 * (a provided key, or a project to create one in). {@code reviewersOverride}, when set, replaces the suggested
 * reviewers on every PR. {@code autoConfirm=false} (the default from the wizard) pauses after planning at
 * {@code AWAITING_CONFIRM} so the user reviews the cascade + edits versions/reviewers before it runs.
 */
public record SnykFixRequest(
        String watchId,
        String issueId,
        String coordinate,      // groupId:artifactId
        String oldVersion,
        String fixedIn,         // the safe version to upgrade to
        String severity,
        List<String> appIds,
        String jiraKey,         // use this ticket, or...
        String jiraProject,     // ...create one in this project
        String jiraIssueType,
        List<String> reviewersOverride,
        String owner,
        boolean autoConfirm,
        String storyKey) {       // the shared bulk story this fix belongs to (null for a single-issue fix) — groups a batch

    /** A copy with the Jira key set — used to carry a prior incomplete train's ticket (and thus its branch) forward
     *  when relaunching after a cancel/fail with no key supplied. */
    public SnykFixRequest withJiraKey(String key) {
        return new SnykFixRequest(watchId, issueId, coordinate, oldVersion, fixedIn, severity, appIds, key,
                jiraProject, jiraIssueType, reviewersOverride, owner, autoConfirm, storyKey);
    }
}
