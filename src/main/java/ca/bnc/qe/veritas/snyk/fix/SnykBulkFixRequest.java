package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/**
 * A "Fix vulnerabilities" batch: the vulnerabilities to fix, grouped by application, plus the Jira destination.
 * Design: one Jira ticket per affected application, all filed under a chosen (or newly created) epic in
 * {@code project}. Each application's fix trains then link to that application's ticket (via the train's Jira key),
 * so a bulk run produces a handful of app tickets under one epic — not one ticket per vulnerability.
 *
 * @param project     the Jira project key the epic + per-app tickets live in
 * @param epicKey     an existing epic to file under, or...
 * @param createEpic  ...{@code true} to create a new epic in {@code project}
 * @param epicSummary the title for the new epic (used only when {@code createEpic})
 * @param reviewers   shared PR reviewers applied to every fix (optional)
 * @param apps        the selected vulnerabilities grouped by application
 */
public record SnykBulkFixRequest(
        String project,
        String epicKey,
        boolean createEpic,
        String epicSummary,
        List<String> reviewers,
        List<AppSelection> apps) {

    /** One application (a watched Snyk org → its Bitbucket project) and the fixable issues selected under it. */
    public record AppSelection(String appId, String watchId, List<IssueSelection> issues) {}

    /** One fixable vulnerability to start a fix train for. */
    public record IssueSelection(String issueId, String coordinate, String oldVersion, String fixedIn,
                                 String severity) {}
}
