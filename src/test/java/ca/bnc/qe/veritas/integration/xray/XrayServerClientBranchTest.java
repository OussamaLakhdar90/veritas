package ca.bnc.qe.veritas.integration.xray;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.CorpHttp;
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
 * Branch-maximising coverage for {@link XrayServerClient} (Xray Server/DC "Raven" REST). Complements
 * {@link XrayServerClientWireMockTest} (happy paths) and {@link XrayServerAuthTypeTest} (auth knob) by
 * exercising the JQL defaulting, null/empty/array-shaped responses, the step field fallbacks, the
 * create-then-push-steps branch, the null-arg defensive branches, token fallback, base-URL trimming,
 * and every error path (each method wraps failures in IllegalStateException with a method-specific prefix).
 */
class XrayServerClientBranchTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    // No-retry CorpHttp so an error stub surfaces immediately (matches the sibling WireMock test).
    private final CorpHttp corp = new CorpHttp(new Retries(RetryTemplate.builder().maxAttempts(1).build()));

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    /** Secrets that resolve XRAY_API_TOKEN (preferred) and JIRA_API_TOKEN (fallback), plus a username for BASIC. */
    private SecretProvider secrets(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key));
    }

    private final SecretProvider patSecrets =
            secrets(Map.of("XRAY_API_TOKEN", "xray-pat", "JIRA_API_TOKEN", "jira-pat", "JIRA_USERNAME", "alice"));

    private XrayServerClient client(SecretProvider s) {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl("http://localhost:" + wm.port());   // Raven lives on the Jira host
        return new XrayServerClient(p, s, mapper, corp);
    }

    private XrayServerClient client() {
        return client(patSecrets);
    }

    // getTestsByJql — JQL defaulting + parsing branches
    @Test
    void getTestsByJqlNullJqlUsesDefaultIssuetypeFilterAndSendsBearer() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"issues\":[]}")));
        List<XrayTest> tests = client().getTestsByJql(null);
        assertThat(tests).isEmpty();
        wm.verify(getRequestedFor(urlPathEqualTo("/rest/api/2/search"))
                .withQueryParam("jql", equalTo("issuetype = Test"))
                .withQueryParam("maxResults", equalTo("200"))
                .withQueryParam("fields", equalTo("summary,labels,status"))
                .withHeader("Authorization", equalTo("Bearer xray-pat"))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void getTestsByJqlBlankJqlUsesDefaultIssuetypeFilter() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"issues\":[]}")));
        assertThat(client().getTestsByJql("   ")).isEmpty();
        wm.verify(getRequestedFor(urlPathEqualTo("/rest/api/2/search"))
                .withQueryParam("jql", equalTo("issuetype = Test")));
    }

    @Test
    void getTestsByJqlNonBlankJqlIsAndedNotReplacedAndUrlEncoded() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"issues\":[]}")));
        client().getTestsByJql("project = CIAM AND labels = api");
        wm.verify(getRequestedFor(urlPathEqualTo("/rest/api/2/search"))
                .withQueryParam("jql", equalTo("project = CIAM AND labels = api")));
    }

    @Test
    void getTestsByJqlPagesThroughAllResultsUsingStartAtAndTotal() {
        // total=3 with PAGE_SIZE rows per page: page 1 (startAt=0) returns 2, page 2 (startAt=2) returns the 3rd.
        // Pre-fix this capped at one page and silently dropped tests beyond it.
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).withQueryParam("startAt", equalTo("0"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"total\":3,\"issues\":["
                                + "{\"key\":\"CIAM-1\",\"id\":\"1\",\"fields\":{\"summary\":\"a\"}},"
                                + "{\"key\":\"CIAM-2\",\"id\":\"2\",\"fields\":{\"summary\":\"b\"}}]}")));
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).withQueryParam("startAt", equalTo("2"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"total\":3,\"issues\":["
                                + "{\"key\":\"CIAM-3\",\"id\":\"3\",\"fields\":{\"summary\":\"c\"}}]}")));

        List<XrayTest> tests = client().getTestsByJql("project = CIAM");

        assertThat(tests).extracting(XrayTest::key).containsExactly("CIAM-1", "CIAM-2", "CIAM-3");
        wm.verify(2, getRequestedFor(urlPathEqualTo("/rest/api/2/search")));   // exactly two pages, then stop
    }

    /** A client whose CorpHttp retries transient failures up to 3× — to prove the read/write retry distinction. */
    private XrayServerClient retryingClient() {
        CorpHttp retryingCorp = new CorpHttp(new Retries(RetryTemplate.builder()
                .maxAttempts(3).fixedBackoff(1)
                .retryOn(org.springframework.web.client.RestClientException.class).build()));
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl("http://localhost:" + wm.port());
        return new XrayServerClient(p, patSecrets, mapper, retryingCorp);
    }

    @Test
    void createTestIsNotRetriedOnServerErrorSoNoDuplicateXrayTest() {
        // A 5xx on createTest (non-idempotent write) must NOT be replayed: the server may already have created the
        // Test, so a retry would create a duplicate in the bank's real tracker. Exactly one POST, then surface.
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> retryingClient().createTest(new XrayTestSpec("CIAM", "T", "Manual", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("createTest failed");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/issue")));
    }

    @Test
    void getTestsByJqlIsRetriedOnServerErrorSinceReadsAreIdempotent() {
        // The contrast: a GET read IS idempotent, so the same retrying client replays it to exhaustion (3×).
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> retryingClient().getTestsByJql("project = CIAM"))
                .isInstanceOf(IllegalStateException.class);
        wm.verify(3, getRequestedFor(urlPathEqualTo("/rest/api/2/search")));
    }

    @Test
    void getTestsByJqlMapsKeyIdSummaryAndStripsHtmlSteps() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issues\":[{\"key\":\"CIAM-1\",\"id\":\"1001\","
                        + "\"fields\":{\"summary\":\"Validate create policy\"}}]}")));
        wm.stubFor(get(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-1/steps")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"step\":\"<p>Call POST /policies</p>\",\"data\":\" valid \",\"result\":\"201\"}]")));
        List<XrayTest> tests = client().getTestsByJql("project = CIAM");
        assertThat(tests).hasSize(1);
        XrayTest t = tests.get(0);
        assertThat(t.key()).isEqualTo("CIAM-1");
        assertThat(t.issueId()).isEqualTo("1001");
        assertThat(t.summary()).isEqualTo("Validate create policy");
        assertThat(t.testType()).isEqualTo("Manual");
        assertThat(t.steps()).hasSize(1);
        assertThat(t.steps().get(0).action()).isEqualTo("Call POST /policies");
        assertThat(t.steps().get(0).data()).isEqualTo("valid");
        assertThat(t.steps().get(0).result()).isEqualTo("201");
    }

    @Test
    void getTestsByJqlIssueMissingKeyIdSummaryDefaultsToEmptyAndStepsTolerateFailure() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"issues\":[{}]}")));
        wm.stubFor(get(urlPathEqualTo("/rest/raven/1.0/api/test//steps"))
                .willReturn(aResponse().withStatus(404)));
        List<XrayTest> tests = client().getTestsByJql("project = CIAM");
        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).key()).isEmpty();
        assertThat(tests.get(0).issueId()).isEmpty();
        assertThat(tests.get(0).summary()).isEmpty();
        assertThat(tests.get(0).steps()).isEmpty();
    }

    @Test
    void getTestsByJqlNullBodyTreatedAsEmptyObjectYieldsNoTests() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse().withStatus(204)));
        assertThat(client().getTestsByJql("anything")).isEmpty();
    }

    @Test
    void getTestsByJqlWrapsTransportFailure() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().getTestsByJql("project = CIAM"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("getTestsByJql failed");
    }

    // fetchSteps (via getTestsByJql)
    @Test
    void fetchStepsReadsWrappedStepsObjectAndFallsBackOnAlternateFieldNames() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issues\":[{\"key\":\"CIAM-2\",\"id\":\"2\",\"fields\":{\"summary\":\"s\"}}]}")));
        wm.stubFor(get(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-2/steps")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"steps\":[{\"action\":\"<b>Do thing</b>\",\"data\":\"d\","
                        + "\"expectedResult\":\"<i>ok</i>\"}]}")));
        List<XrayTest> tests = client().getTestsByJql("project = CIAM");
        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).steps()).hasSize(1);
        XrayStep s = tests.get(0).steps().get(0);
        assertThat(s.action()).isEqualTo("Do thing");
        assertThat(s.data()).isEqualTo("d");
        assertThat(s.result()).isEqualTo("ok");
    }

    @Test
    void fetchStepsTopLevelArrayShapeIsHonored() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issues\":[{\"key\":\"CIAM-3\",\"id\":\"3\",\"fields\":{\"summary\":\"s\"}}]}")));
        wm.stubFor(get(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-3/steps")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"step\":\"a1\",\"data\":\"d1\",\"result\":\"r1\"},"
                        + "{\"step\":\"a2\",\"data\":\"d2\",\"result\":\"r2\"}]")));
        List<XrayStep> steps = client().getTestsByJql("project = CIAM").get(0).steps();
        assertThat(steps).hasSize(2);
        assertThat(steps).extracting(XrayStep::action).containsExactly("a1", "a2");
        assertThat(steps).extracting(XrayStep::result).containsExactly("r1", "r2");
    }

    @Test
    void fetchStepsNullBodyYieldsNoStepsButTestStillReturned() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issues\":[{\"key\":\"CIAM-4\",\"id\":\"4\",\"fields\":{\"summary\":\"s\"}}]}")));
        wm.stubFor(get(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-4/steps"))
                .willReturn(aResponse().withStatus(204)));
        List<XrayTest> tests = client().getTestsByJql("project = CIAM");
        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).steps()).isEmpty();
    }

    // createTest
    @Test
    void createTestPostsIssueWithExpectedFieldsAndReturnsKeyNoStepsWhenEmpty() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"key\":\"CIAM-9\"}")));
        String key = client().createTest(new XrayTestSpec("CIAM", "New manual test", "Manual", List.of()));
        assertThat(key).isEqualTo("CIAM-9");
        wm.verify(postRequestedFor(urlPathEqualTo("/rest/api/2/issue"))
                .withRequestBody(containing("\"project\""))
                .withRequestBody(containing("\"CIAM\""))
                .withRequestBody(containing("\"name\":\"Test\""))
                .withRequestBody(containing("\"summary\":\"New manual test\"")));
        wm.verify(0, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-9/step")));
    }

    @Test
    void createTestWithStepsAlsoPushesEachStepToRaven() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"key\":\"CIAM-10\"}")));
        wm.stubFor(post(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-10/step")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":1}")));
        String key = client().createTest(new XrayTestSpec("CIAM", "T", "Manual",
                List.of(new XrayStep("a1", "d1", "r1"), new XrayStep("a2", "d2", "r2"))));
        assertThat(key).isEqualTo("CIAM-10");
        wm.verify(2, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-10/step")));
    }

    @Test
    void updateTestStepsReplacesExistingStepsByDeletingThemFirst() {
        // The existing steps (ids 11, 12) must be DELETEd before the new step is POSTed — a review-apply replaces,
        // it doesn't stack corrected steps on top of the originals (which corrupted the test).
        wm.stubFor(get(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-5/steps")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"id\":11,\"step\":\"old A\"},{\"id\":12,\"step\":\"old B\"}]")));
        wm.stubFor(delete(urlPathMatching("/rest/raven/1.0/api/test/CIAM-5/step/.*"))
                .willReturn(aResponse().withStatus(204)));
        wm.stubFor(post(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-5/step")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":99}")));

        client().updateTestSteps("CIAM-5", List.of(new XrayStep("new step", "data", "result")));

        wm.verify(deleteRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-5/step/11")));
        wm.verify(deleteRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-5/step/12")));
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-5/step"))
                .withRequestBody(containing("new step")));
    }

    @Test
    void createTestWithNullStepsSkipsRavenAndReturnsKey() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"key\":\"CIAM-11\"}")));
        String key = client().createTest(new XrayTestSpec("CIAM", "T", "Manual", null));
        assertThat(key).isEqualTo("CIAM-11");
        wm.verify(0, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-11/step")));
    }

    @Test
    void createTestBlankKeyResponseSkipsStepsAndReturnsEmpty() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{}")));
        String key = client().createTest(new XrayTestSpec("CIAM", "T", "Manual",
                List.of(new XrayStep("a", "d", "r"))));
        assertThat(key).isEmpty();
        wm.verify(0, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test//step")));
    }

    @Test
    void createTestNullBodyResponseYieldsEmptyKey() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse().withStatus(204)));
        assertThat(client().createTest(new XrayTestSpec("CIAM", "T", "Manual", List.of()))).isEmpty();
    }

    @Test
    void createTestWrapsTransportFailure() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().createTest(new XrayTestSpec("CIAM", "T", "Manual", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("createTest failed");
    }

    // updateTestSteps
    @Test
    void updateTestStepsNullListReturnsEarlyWithoutCalling() {
        client().updateTestSteps("CIAM-1", null);
        wm.verify(0, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-1/step")));
    }

    @Test
    void updateTestStepsEmptyListPostsNothing() {
        client().updateTestSteps("CIAM-1", List.of());
        wm.verify(0, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-1/step")));
    }

    @Test
    void updateTestStepsCoalescesNullFieldsToEmptyStrings() {
        wm.stubFor(post(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-1/step")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":7}")));
        client().updateTestSteps("CIAM-1", List.of(new XrayStep(null, null, null)));
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-1/step"))
                .withRequestBody(containing("\"step\":\"\""))
                .withRequestBody(containing("\"data\":\"\""))
                .withRequestBody(containing("\"result\":\"\"")));
    }

    @Test
    void updateTestStepsSendsProvidedFieldValues() {
        wm.stubFor(post(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-2/step")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":8}")));
        client().updateTestSteps("CIAM-2", List.of(new XrayStep("Call endpoint", "boundary", "400")));
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-2/step"))
                .withRequestBody(containing("\"step\":\"Call endpoint\""))
                .withRequestBody(containing("\"data\":\"boundary\""))
                .withRequestBody(containing("\"result\":\"400\"")));
    }

    @Test
    void updateTestStepsWrapsTransportFailureWithTestKey() {
        wm.stubFor(post(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-X/step"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().updateTestSteps("CIAM-X", List.of(new XrayStep("a", "d", "r"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("updateTestSteps failed for CIAM-X");
    }

    // linkTestToRequirement
    @Test
    void linkTestToRequirementPostsIssueLinkWithTestsType() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issueLink")).willReturn(aResponse().withStatus(201)));
        client().linkTestToRequirement("CIAM-9", "CIAM-1");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/issueLink"))
                .withRequestBody(containing("\"name\":\"Tests\""))
                .withRequestBody(containing("\"outwardIssue\""))
                .withRequestBody(containing("\"CIAM-9\""))
                .withRequestBody(containing("\"inwardIssue\""))
                .withRequestBody(containing("\"CIAM-1\"")));
    }

    @Test
    void linkTestToRequirementWrapsFailureWithBothKeys() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issueLink")).willReturn(aResponse().withStatus(400)));
        assertThatThrownBy(() -> client().linkTestToRequirement("CIAM-9", "CIAM-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("linkTestToRequirement failed for CIAM-9 -> CIAM-1");
    }

    // addTestsToTestPlan
    @Test
    void addTestsToTestPlanPostsAddArrayToRaven() {
        wm.stubFor(post(urlPathEqualTo("/rest/raven/1.0/api/testplan/CIAM-TP1/test"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
        client().addTestsToTestPlan("CIAM-TP1", List.of("CIAM-1", "CIAM-2"));
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/raven/1.0/api/testplan/CIAM-TP1/test"))
                .withRequestBody(containing("\"add\""))
                .withRequestBody(containing("\"CIAM-1\""))
                .withRequestBody(containing("\"CIAM-2\"")));
    }

    @Test
    void addTestsToTestPlanWrapsFailureWithPlanKey() {
        wm.stubFor(post(urlPathEqualTo("/rest/raven/1.0/api/testplan/CIAM-TP1/test"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().addTestsToTestPlan("CIAM-TP1", List.of("CIAM-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("addTestsToTestPlan failed for CIAM-TP1");
    }

    // Auth header / token fallback
    @Test
    void authHeaderBasicEncodesUsernameColonToken() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl("http://localhost:" + wm.port());
        p.getXray().setAuthType("basic");
        XrayServerClient c = new XrayServerClient(p, patSecrets, mapper, corp);
        String header = c.authHeader();
        assertThat(header).startsWith("Basic ");
        String decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length())),
                StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("alice:xray-pat");
    }

    @Test
    void authHeaderBearerUsesXrayTokenWhenPresent() {
        assertThat(client().authHeader()).isEqualTo("Bearer xray-pat");
    }

    @Test
    void authHeaderBearerFallsBackToJiraTokenWhenXrayBlank() {
        SecretProvider s = secrets(Map.of("XRAY_API_TOKEN", "   ", "JIRA_API_TOKEN", "jira-pat"));
        assertThat(client(s).authHeader()).isEqualTo("Bearer jira-pat");
    }

    @Test
    void authHeaderBearerEmptyTokenWhenNoSecrets() {
        SecretProvider none = key -> Optional.empty();
        assertThat(client(none).authHeader()).isEqualTo("Bearer ");
    }

    @Test
    void authHeaderBasicEmptyUsernameAndTokenStillEncodes() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getXray().setAuthType("BASIC");
        XrayServerClient c = new XrayServerClient(p, key -> Optional.empty(), mapper, corp);
        String header = c.authHeader();
        String decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length())),
                StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(":");
    }

    // base()
    @Test
    void baseTrimsTrailingSlashSoPathHasNoDoubleSlash() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issueLink")).willReturn(aResponse().withStatus(201)));
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl("http://localhost:" + wm.port() + "/");
        XrayServerClient c = new XrayServerClient(p, patSecrets, mapper, corp);
        c.linkTestToRequirement("T-1", "R-1");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/issueLink")));
    }

    @Test
    void baseNullBaseUrlProducesRelativeUriThatFailsAndIsWrapped() {
        ConnectionsProperties p = new ConnectionsProperties();
        XrayServerClient c = new XrayServerClient(p, patSecrets, mapper, corp);
        assertThatThrownBy(() -> c.linkTestToRequirement("T-1", "R-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("linkTestToRequirement failed for T-1 -> R-1");
    }

    // stripHtml
    @Test
    void stripHtmlNullReturnsEmpty() {
        assertThat(XrayServerClient.stripHtml(null)).isEmpty();
    }

    @Test
    void stripHtmlRemovesTagsAndTrims() {
        assertThat(XrayServerClient.stripHtml("  <p>hi <b>there</b></p>  ")).isEqualTo("hi there");
    }

    @Test
    void stripHtmlPlainTextWithoutTagsIsJustTrimmed() {
        assertThat(XrayServerClient.stripHtml("  no tags  ")).isEqualTo("no tags");
    }
}