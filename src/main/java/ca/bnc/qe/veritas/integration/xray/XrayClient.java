package ca.bnc.qe.veritas.integration.xray;

import java.util.List;

/**
 * Edition-agnostic Xray access used by the test-management skills. Two implementations: {@link XrayCloudClient}
 * (Cloud GraphQL) and {@link XrayServerClient} (Server/DC "Raven" REST — Jira {@code /rest/api/2} +
 * {@code /rest/raven/1.0}, matching the BNC contract-validator app). Selected by
 * {@code veritas.connections.xray.edition}.
 */
public interface XrayClient {

    /** Find Xray Test issues by JQL, with their manual steps. */
    List<XrayTest> getTestsByJql(String jql);

    /** Create an Xray Test; returns the new test issue key. */
    String createTest(XrayTestSpec spec);

    /** Add the given steps to a test (additive). */
    void updateTestSteps(String testKey, List<XrayStep> steps);

    /** Attach existing tests to a Test Plan (outward write — gate upstream). */
    void addTestsToTestPlan(String planKey, List<String> testKeys);

    /**
     * Establish requirement coverage: link a Test to the requirement it verifies (outward write — gate upstream).
     * On Server/DC this is a Jira "Tests" issue link; on Cloud it is not exposed by the GraphQL client.
     */
    void linkTestToRequirement(String testKey, String requirementKey);
}
