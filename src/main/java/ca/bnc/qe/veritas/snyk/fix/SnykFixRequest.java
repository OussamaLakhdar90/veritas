package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/**
 * What the user asked to fix: one fixable Snyk issue, the app-ids to propagate to, and the Jira ticket to drive it
 * (a provided key, or a project to create one in). {@code reviewersOverride}, when set, replaces the suggested
 * reviewers on every PR.
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
        String owner) {
}
