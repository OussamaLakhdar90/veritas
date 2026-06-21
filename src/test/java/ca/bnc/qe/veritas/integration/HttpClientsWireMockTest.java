package ca.bnc.qe.veritas.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.contract.SpecInput;
import ca.bnc.qe.veritas.contract.SpecResolver;
import ca.bnc.qe.veritas.contract.SpecSource;
import ca.bnc.qe.veritas.contract.SpecSourceKind;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceCloudClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import ca.bnc.qe.veritas.integration.jira.JiraCloudClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.jira.JiraStatus;
import ca.bnc.qe.veritas.integration.xray.XrayCloudClient;
import ca.bnc.qe.veritas.integration.xray.XrayTest;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.vcs.RepoInfo;
import ca.bnc.qe.veritas.vcs.BitbucketCloudClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

/** HTTP-contract tests: each REST client is pointed at a WireMock server and verified end to end. */
class HttpClientsWireMockTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider secrets = key -> Optional.of("x");
    private final Retries retries = new Retries(RetryTemplate.builder().maxAttempts(1).build());

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private String baseUrl() {
        return "http://localhost:" + wm.port();
    }

    private ConnectionsProperties props() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl(baseUrl());
        p.getConfluence().setBaseUrl(baseUrl());
        p.getXray().setBaseUrl(baseUrl());
        p.getBitbucket().setBaseUrl(baseUrl());
        p.getBitbucket().setWorkspace("bnc");
        p.getBitbucket().setAuthType("APP_PASSWORD");
        return p;
    }

    @Test
    void jiraGetStatusParsesNameAndCategory() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/CIAM-1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"fields\":{\"status\":{\"name\":\"In Progress\","
                        + "\"statusCategory\":{\"key\":\"indeterminate\"}}}}")));

        JiraStatus status = new JiraCloudClient(props(), secrets, mapper, retries).getStatus("CIAM-1");
        assertThat(status.name()).isEqualTo("In Progress");
        assertThat(status.categoryKey()).isEqualTo("indeterminate");
    }

    @Test
    void jiraCreateIssueReturnsKey() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"CIAM-42\"}")));

        String key = new JiraCloudClient(props(), secrets, mapper, retries).createIssue(new JiraCreateRequest(
                "CIAM", "Bug", "ciam-policies — POST /x — missing", List.of("actual vs expected"), List.of("contract")));
        assertThat(key).isEqualTo("CIAM-42");
    }

    @Test
    void confluenceGetPageParsesStorageBody() {
        wm.stubFor(get(urlPathEqualTo("/wiki/rest/api/content/777")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"777\",\"title\":\"Spec\",\"body\":{\"storage\":{\"value\":\"<p>hi</p>\"}}}")));

        ConfluencePage page = new ConfluenceCloudClient(props(), secrets, mapper, retries).getPage("777");
        assertThat(page.title()).isEqualTo("Spec");
        assertThat(page.storageXhtml()).isEqualTo("<p>hi</p>");
    }

    @Test
    void bitbucketDiscoverReposParsesValues() {
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"values\":[{\"slug\":\"ciam-policies\",\"name\":\"ciam-policies\","
                        + "\"project\":{\"key\":\"APP7571\"},\"links\":{\"clone\":[{\"name\":\"https\","
                        + "\"href\":\"https://x/ciam-policies.git\"}]}}]}")));

        List<RepoInfo> repos = new BitbucketCloudClient(props(), secrets, mapper, retries).discoverRepos("APP7571");
        assertThat(repos).hasSize(1);
        assertThat(repos.get(0).slug()).isEqualTo("ciam-policies");
        assertThat(repos.get(0).cloneUrl()).endsWith("ciam-policies.git");
    }

    @Test
    void bitbucketOpenPullRequestReturnsHtmlUrl() {
        wm.stubFor(post(urlPathEqualTo("/2.0/repositories/bnc/ciam-policies-tests/pullrequests"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"links\":{\"html\":{\"href\":"
                                + "\"https://bitbucket.org/bnc/ciam-policies-tests/pull-requests/1\"}}}")));

        String url = new BitbucketCloudClient(props(), secrets, mapper, retries)
                .openPullRequest("ciam-policies-tests", "veritas/generated", "main", "Add tests", "by Veritas");
        assertThat(url).isEqualTo("https://bitbucket.org/bnc/ciam-policies-tests/pull-requests/1");
    }

    @Test
    void xrayAuthenticatesThenQueriesTests() {
        wm.stubFor(post(urlPathEqualTo("/api/v2/authenticate"))
                .willReturn(aResponse().withBody("\"tok-123\"")));
        wm.stubFor(post(urlPathEqualTo("/api/v2/graphql")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":{\"getTests\":{\"results\":[{\"issueId\":\"1001\","
                        + "\"jira\":{\"key\":\"CIAM-1\",\"summary\":\"Validate create policy\"},"
                        + "\"testType\":{\"name\":\"Manual\"},"
                        + "\"steps\":[{\"action\":\"POST /policies\",\"data\":\"{}\",\"result\":\"201\"}]}]}}}")));

        List<XrayTest> tests = new XrayCloudClient(props(), secrets, mapper, retries).getTestsByJql("key = CIAM-1");
        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).key()).isEqualTo("CIAM-1");
        assertThat(tests.get(0).steps()).hasSize(1);
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/api/v2/authenticate")));
    }

    @Test
    void specResolverFetchesLiveApiDocs() {
        wm.stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"openapi\":\"3.0.1\",\"paths\":{}}")));

        SpecInput spec = new SpecResolver(id -> null)
                .resolve(new SpecSource(SpecSourceKind.LIVE_DOCS, baseUrl() + "/v3/api-docs"), null);
        assertThat(spec.id()).isEqualTo("live-api-docs");
        assertThat(spec.content()).contains("\"openapi\"");
    }
}
