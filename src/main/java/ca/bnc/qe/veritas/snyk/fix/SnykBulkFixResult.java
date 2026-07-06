package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/**
 * The outcome of a bulk fix launch: the epic and the shared story everything was filed under, and per application the
 * fix trains that were started (each linked to that story). {@code error} is set (and {@code trainIds} empty) for an
 * application that could not be launched, so one failing app never sinks the rest of the batch. {@code jiraKey} is the
 * shared story key the app's fixes were filed on.
 */
public record SnykBulkFixResult(String epicKey, String storyKey, List<AppResult> apps) {

    public record AppResult(String appId, String jiraKey, List<String> trainIds, String error) {}
}
