package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/**
 * The outcome of a bulk fix launch: the epic and the shared story everything was filed under, and per application the
 * fix trains that were started (each linked to that story). {@code error} is set (and {@code trainIds} empty) for an
 * application that could not be launched, so one failing app never sinks the rest of the batch. {@code jiraKey} is the
 * shared story key the app's fixes were filed on. {@code epicUrl}/{@code storyUrl}/{@code jiraUrl} are the clickable
 * Jira browse links (null when the Jira base URL isn't configured) so the dashboard can show the created ticket
 * numbers as links instead of just text.
 */
public record SnykBulkFixResult(String epicKey, String storyKey, String epicUrl, String storyUrl,
                                List<AppResult> apps) {

    public record AppResult(String appId, String jiraKey, String jiraUrl, List<String> trainIds, String error) {}
}
