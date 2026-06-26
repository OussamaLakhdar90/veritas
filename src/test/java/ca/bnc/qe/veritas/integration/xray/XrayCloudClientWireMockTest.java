package ca.bnc.qe.veritas.integration.xray;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

/**
 * Verifies the Xray <b>Cloud</b> GraphQL client against WireMock: the client_id/secret → bearer token exchange,
 * the auto-authenticate-before-graphql flow, createTest / getTests / step + test-plan mutations, and the
 * error / unsupported paths.
 */
class XrayCloudClientWireMockTest {

    private static final String TOKEN = "tok-abc-123";

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider secrets = key -> Optional.of("XRAY_CLIENT_ID".equals(key) ? "cid" : "csecret");

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private XrayCloudClient client() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getXray().setBaseUrl("http://localhost:" + wm.port());
        p.getXray().setEdition("CLOUD");
        return new XrayCloudClient(p, secrets, mapper,
                new Retries(RetryTemplate.builder().maxAttempts(1).build()));
    }

    /** Xray returns the token as a JSON string literal: {@code "tok"}; the client must strip the surrounding quotes. */
    private void stubAuth(String quotedToken) {
        wm.stubFor(post(urlPathEqualTo("/api/v2/authenticate")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(quotedToken)));
    }

    private void stubGraphql(String dataBody) {
        wm.stubFor(post(urlPathEqualTo("/api/v2/graphql")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(dataBody)));
    }

    // ---- authenticate ----

    @Test
    void authenticateExchangesClientCredentialsAndStripsQuotes() {
        stubAuth("\"" + TOKEN + "\"");

        String token = client().authenticate();

        assertThat(token).isEqualTo(TOKEN);   // surrounding quotes stripped
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/v2/authenticate"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(containing("\"client_id\":\"cid\""))
                .withRequestBody(containing("\"client_secret\":\"csecret\"")));
    }

    @Test
    void authenticateTreatsNullBodyAsEmptyToken() {
        // 204 → RestClient yields a null body; the client maps null to an empty token rather than NPE.
        wm.stubFor(post(urlPathEqualTo("/api/v2/authenticate")).willReturn(aResponse().withStatus(204)));

        String token = client().authenticate();

        assertThat(token).isEmpty();
    }

    @Test
    void authenticateWrapsHttpErrorInIllegalState() {
        wm.stubFor(post(urlPathEqualTo("/api/v2/authenticate")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"bad creds\"}")));

        assertThatThrownBy(() -> client().authenticate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Xray authenticate failed");
    }

    // ---- createTest (auto-authenticate then graphql) ----

    @Test
    void createTestAuthenticatesThenReturnsCreatedKey() {
        stubAuth("\"" + TOKEN + "\"");
        stubGraphql("{\"data\":{\"createTest\":{\"test\":{\"jira\":{\"key\":\"CIAM-42\"}},\"warnings\":[]}}}");

        String key = client().createTest(new XrayTestSpec("CIAM", "Validate create policy", "Manual",
                List.of(new XrayStep("POST /policies", "{...}", "201"))));

        assertThat(key).isEqualTo("CIAM-42");
        // ensureToken() must have triggered the auth exchange first, then the graphql call carries the bearer.
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/v2/authenticate")));
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/v2/graphql"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withRequestBody(containing("createTest"))
                .withRequestBody(containing("summary: \\\"Validate create policy\\\"")));
    }

    @Test
    void createTestReturnsEmptyKeyWhenResponseLacksKey() {
        stubAuth("\"" + TOKEN + "\"");
        stubGraphql("{\"data\":{\"createTest\":{\"warnings\":[\"dup\"]}}}");

        String key = client().createTest(new XrayTestSpec("CIAM", "X", "Manual", null));

        assertThat(key).isEmpty();   // missing test.jira.key path → asText("") default
    }

    @Test
    void graphqlErrorStatusIsWrappedInIllegalState() {
        stubAuth("\"" + TOKEN + "\"");
        wm.stubFor(post(urlPathEqualTo("/api/v2/graphql")).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json").withBody("{\"errors\":[\"boom\"]}")));

        assertThatThrownBy(() -> client().createTest(new XrayTestSpec("CIAM", "X", "Manual", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Xray GraphQL call failed");
    }

    // ---- getTestsByJql / getTestSteps ----

    @Test
    void getTestsByJqlParsesResultsAndSteps() {
        stubAuth("\"" + TOKEN + "\"");
        stubGraphql("{\"data\":{\"getTests\":{\"results\":[{"
                + "\"issueId\":\"10001\","
                + "\"jira\":{\"key\":\"CIAM-1\",\"summary\":\"Validate create policy\"},"
                + "\"testType\":{\"name\":\"Manual\"},"
                + "\"steps\":[{\"action\":\"Call POST /policies\",\"data\":\"valid\",\"result\":\"201\"}]"
                + "}]}}}");

        List<XrayTest> tests = client().getTestsByJql("project = CIAM");

        assertThat(tests).hasSize(1);
        XrayTest t = tests.get(0);
        assertThat(t.key()).isEqualTo("CIAM-1");
        assertThat(t.issueId()).isEqualTo("10001");
        assertThat(t.summary()).isEqualTo("Validate create policy");
        assertThat(t.testType()).isEqualTo("Manual");
        assertThat(t.steps()).hasSize(1);
        assertThat(t.steps().get(0).action()).isEqualTo("Call POST /policies");
        assertThat(t.steps().get(0).data()).isEqualTo("valid");
        assertThat(t.steps().get(0).result()).isEqualTo("201");
    }

    @Test
    void getTestsByJqlReturnsEmptyWhenNoResults() {
        stubAuth("\"" + TOKEN + "\"");
        stubGraphql("{\"data\":{\"getTests\":{\"results\":[]}}}");

        assertThat(client().getTestsByJql("project = NONE")).isEmpty();
    }

    @Test
    void getTestStepsReturnsStepsOfFirstMatch() {
        stubAuth("\"" + TOKEN + "\"");
        stubGraphql("{\"data\":{\"getTests\":{\"results\":[{"
                + "\"issueId\":\"10001\",\"jira\":{\"key\":\"CIAM-1\"},\"testType\":{\"name\":\"Manual\"},"
                + "\"steps\":[{\"action\":\"do\",\"data\":\"d\",\"result\":\"r\"}]}]}}}");

        List<XrayStep> steps = client().getTestSteps("CIAM-1");

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).action()).isEqualTo("do");
    }

    @Test
    void getTestStepsReturnsEmptyWhenNoMatch() {
        stubAuth("\"" + TOKEN + "\"");
        stubGraphql("{\"data\":{\"getTests\":{\"results\":[]}}}");

        assertThat(client().getTestSteps("CIAM-404")).isEmpty();
    }

    // ---- updateTestSteps (resolve issueId then addTestStep per step) ----

    @Test
    void updateTestStepsResolvesIssueIdThenAddsEachStep() {
        stubAuth("\"" + TOKEN + "\"");
        // First graphql = resolveIssueId (getTests), subsequent = addTestStep mutations.
        wm.stubFor(post(urlPathEqualTo("/api/v2/graphql"))
                .withRequestBody(containing("getTests"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"getTests\":{\"results\":[{\"issueId\":\"55501\","
                                + "\"jira\":{\"key\":\"CIAM-7\"},\"testType\":{\"name\":\"Manual\"},\"steps\":[]}]}}}")));
        wm.stubFor(post(urlPathEqualTo("/api/v2/graphql"))
                .withRequestBody(containing("addTestStep"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"addTestStep\":{\"id\":\"1\"}}}")));

        client().updateTestSteps("CIAM-7", List.of(
                new XrayStep("a1", "d1", "r1"),
                new XrayStep("a2", "d2", "r2")));

        // two addTestStep mutations, each targeting the resolved issueId 55501
        wm.verify(2, postRequestedFor(urlPathEqualTo("/api/v2/graphql"))
                .withRequestBody(containing("addTestStep(issueId: \\\"55501\\\"")));
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/v2/graphql"))
                .withRequestBody(containing("action: \\\"a1\\\"")));
    }

    // ---- addTestsToTestPlan (resolve plan + each test, filter blanks) ----

    @Test
    void addTestsToTestPlanResolvesIdsAndSkipsUnresolved() {
        stubAuth("\"" + TOKEN + "\"");
        // resolveIssueId(plan) and resolveIssueId(each key) all go through getTests; map by jql key.
        wm.stubFor(post(urlPathEqualTo("/api/v2/graphql")).withRequestBody(containing("key = CIAM-TP1"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"getTests\":{\"results\":[{\"issueId\":\"9000\","
                                + "\"jira\":{\"key\":\"CIAM-TP1\"},\"testType\":{\"name\":\"Test Plan\"},\"steps\":[]}]}}}")));
        wm.stubFor(post(urlPathEqualTo("/api/v2/graphql")).withRequestBody(containing("key = CIAM-1"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"getTests\":{\"results\":[{\"issueId\":\"9001\","
                                + "\"jira\":{\"key\":\"CIAM-1\"},\"testType\":{\"name\":\"Manual\"},\"steps\":[]}]}}}")));
        // CIAM-2 resolves to nothing → blank id → filtered out of the mutation.
        wm.stubFor(post(urlPathEqualTo("/api/v2/graphql")).withRequestBody(containing("key = CIAM-2"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"getTests\":{\"results\":[]}}}")));
        wm.stubFor(post(urlPathEqualTo("/api/v2/graphql")).withRequestBody(containing("addTestsToTestPlan"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"addTestsToTestPlan\":{\"warning\":null}}}")));

        client().addTestsToTestPlan("CIAM-TP1", List.of("CIAM-1", "CIAM-2"));

        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/v2/graphql"))
                .withRequestBody(containing("addTestsToTestPlan(issueId: \\\"9000\\\"")) // resolved plan id
                .withRequestBody(containing("\\\"9001\\\"")));                            // CIAM-1 resolved
        // CIAM-2 (unresolved) must NOT appear in the mutation's id list.
        wm.verify(0, postRequestedFor(urlPathEqualTo("/api/v2/graphql"))
                .withRequestBody(containing("addTestsToTestPlan"))
                .withRequestBody(containing("9002")));
    }

    // ---- linkTestToRequirement (unsupported on Cloud) ----

    @Test
    void linkTestToRequirementIsUnsupportedOnCloud() {
        assertThatThrownBy(() -> client().linkTestToRequirement("CIAM-9", "CIAM-1"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not supported by the Xray Cloud GraphQL")
                .hasMessageContaining("CIAM-9 -> CIAM-1");
    }

    // ---- token caching: a second graphql call reuses the cached token (no re-auth) ----

    @Test
    void tokenIsCachedAcrossCallsSoAuthHappensOnce() {
        stubAuth("\"" + TOKEN + "\"");
        stubGraphql("{\"data\":{\"getTests\":{\"results\":[]}}}");

        XrayCloudClient c = client();
        c.getTestsByJql("project = A");
        c.getTestsByJql("project = B");

        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/v2/authenticate")));
        wm.verify(2, postRequestedFor(urlPathEqualTo("/api/v2/graphql")));
    }

    // ---- builders / escaping (branch coverage of esc + stepsGql) ----

    @Test
    void buildCreateTestMutationEscapesQuotesAndBackslashesAndHandlesNullSteps() {
        XrayCloudClient c = client();

        String withSteps = c.buildCreateTestMutation(new XrayTestSpec(
                "CIAM", "say \"hi\"\\path", "Manual", List.of(new XrayStep("a\nb", "d", "r"))));
        // " -> \" and \ -> \\ ; the surrounding summary delimiters are literal unescaped quotes.
        assertThat(withSteps).contains("summary: \"say \\\"hi\\\"\\\\path\"");
        assertThat(withSteps).contains("action: \"a\\nb\"");   // newline escaped to \n

        // null step list → stepsGql returns "" (empty steps array), no NPE.
        String nullSteps = c.buildCreateTestMutation(new XrayTestSpec("CIAM", "s", "Manual", null));
        assertThat(nullSteps).contains("steps: []");
    }

    @Test
    void buildGetTestsQueryEscapesJql() {
        String q = client().buildGetTestsQuery("summary ~ \"a\"");
        assertThat(q).contains("getTests(jql: \"summary ~ \\\"a\\\"\"");
        assertThat(q).contains("limit: 100");
    }
}
