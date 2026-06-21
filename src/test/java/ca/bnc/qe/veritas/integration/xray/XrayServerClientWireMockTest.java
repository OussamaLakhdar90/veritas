package ca.bnc.qe.veritas.integration.xray;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/** Verifies the Xray Server/DC "Raven" REST client (Jira /rest/api/2 + /rest/raven/1.0) against WireMock. */
class XrayServerClientWireMockTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider secrets = key -> Optional.of("pat-1");
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

    private XrayServerClient client() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl("http://localhost:" + wm.port());   // Raven lives on the Jira host
        return new XrayServerClient(p, secrets, mapper, corp);
    }

    @Test
    void getTestsByJqlReadsJiraSearchThenRavenSteps() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issues\":[{\"key\":\"CIAM-1\",\"id\":\"1001\",\"fields\":{\"summary\":\"Validate create policy\"}}]}")));
        wm.stubFor(get(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-1/steps")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"index\":1,\"step\":\"<p>Call POST /policies</p>\",\"data\":\"valid\",\"result\":\"201\"}]")));

        List<XrayTest> tests = client().getTestsByJql("project = CIAM");
        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).key()).isEqualTo("CIAM-1");
        assertThat(tests.get(0).steps()).hasSize(1);
        assertThat(tests.get(0).steps().get(0).action()).isEqualTo("Call POST /policies");   // HTML stripped
        assertThat(tests.get(0).steps().get(0).result()).isEqualTo("201");
    }

    @Test
    void createTestPostsJiraIssueAndReturnsKey() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"key\":\"CIAM-9\"}")));
        String key = client().createTest(new XrayTestSpec("CIAM", "New manual test", "Manual", List.of()));
        assertThat(key).isEqualTo("CIAM-9");
    }

    @Test
    void updateTestStepsPostsToRaven() {
        wm.stubFor(post(urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-1/step")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":5}")));
        client().updateTestSteps("CIAM-1", List.of(new XrayStep("Call endpoint", "boundary", "400")));
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/rest/raven/1.0/api/test/CIAM-1/step")));
    }

    @Test
    void addTestsToTestPlanPostsToRaven() {
        wm.stubFor(post(urlPathEqualTo("/rest/raven/1.0/api/testplan/CIAM-TP1/test")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{}")));
        client().addTestsToTestPlan("CIAM-TP1", List.of("CIAM-1", "CIAM-2"));
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/rest/raven/1.0/api/testplan/CIAM-TP1/test")));
    }

    @Test
    void stripHtmlRemovesTags() {
        assertThat(XrayServerClient.stripHtml("<p>hi <b>there</b></p>")).isEqualTo("hi there");
    }
}
