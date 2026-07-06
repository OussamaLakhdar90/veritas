package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/**
 * A "Fix vulnerabilities" batch: the vulnerabilities to fix, grouped by application, plus the Jira destination.
 * Design: every application's fix trains link to ONE shared Jira story — an existing OPEN story chosen under the epic,
 * or a new one created under it — so a bulk run lands all its fixes on a single tracking story inside the chosen (or
 * newly created) epic, rather than a ticket per app.
 *
 * @param project      the Jira project key the epic + story live in
 * @param epicKey      an existing epic to file under (or the parent of a new story), or...
 * @param createEpic   ...{@code true} to create a new epic in {@code project}
 * @param epicSummary  the title for the new epic (used only when {@code createEpic})
 * @param storyKey     an existing OPEN story under the epic to file every fix under, or...
 * @param createStory  ...{@code true} to create a new story under the epic
 * @param storySummary the title for the new story (used only when {@code createStory})
 * @param reviewers    shared PR reviewers applied to every fix (validated Bitbucket usernames; optional)
 * @param apps         the selected vulnerabilities grouped by application
 */
public record SnykBulkFixRequest(
        String project,
        String epicKey,
        boolean createEpic,
        String epicSummary,
        String storyKey,
        boolean createStory,
        String storySummary,
        List<String> reviewers,
        List<AppSelection> apps) {

    /** One application (a watched Snyk org → its Bitbucket project) and the fixable issues selected under it. */
    public record AppSelection(String appId, String watchId, List<IssueSelection> issues) {}

    /** One fixable vulnerability to start a fix train for. */
    public record IssueSelection(String issueId, String coordinate, String oldVersion, String fixedIn,
                                 String severity) {}
}
