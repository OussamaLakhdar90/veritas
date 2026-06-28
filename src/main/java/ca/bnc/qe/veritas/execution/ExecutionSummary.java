package ca.bnc.qe.veritas.execution;

import java.util.List;

/**
 * A test-completion summary: what was designed vs what actually ran (read back from Xray), with the deviations a
 * completion report must call out. This is the piece that lets "coverage" mean <em>verified</em>, not just designed.
 *
 * @param serviceName the service under test
 * @param jql         the JQL that selected the tests
 * @param total       how many tests the JQL matched
 * @param passed      outcome normalised to PASSED
 * @param failed      outcome normalised to FAILED
 * @param blocked     outcome normalised to BLOCKED
 * @param notRun      not yet executed (TODO / executing / unknown)
 * @param deviations  the failed + blocked tests — what a completion report must explain
 * @param verdict     a one-line plain-language verdict
 */
public record ExecutionSummary(String serviceName, String jql, int total,
                               int passed, int failed, int blocked, int notRun,
                               List<TestOutcome> deviations, String verdict) {

    /** One test's normalised outcome. */
    public record TestOutcome(String testKey, String rawStatus, String outcome) {}
}
