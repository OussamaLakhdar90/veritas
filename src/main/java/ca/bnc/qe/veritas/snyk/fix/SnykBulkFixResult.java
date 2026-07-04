package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/**
 * The outcome of a bulk fix launch: the epic everything was filed under, and per application the ticket that was
 * created plus the fix trains that were started. {@code error} is set (and {@code jiraKey}/{@code trainIds} empty)
 * for an application that could not be launched, so one failing app never sinks the rest of the batch.
 */
public record SnykBulkFixResult(String epicKey, List<AppResult> apps) {

    public record AppResult(String appId, String jiraKey, List<String> trainIds, String error) {}
}
