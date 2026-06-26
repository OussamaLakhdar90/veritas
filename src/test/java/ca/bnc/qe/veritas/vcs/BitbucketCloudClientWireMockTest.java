package ca.bnc.qe.veritas.vcs;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
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
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

/**
 * Drives the Bitbucket <b>Cloud</b> 2.0 REST client against WireMock: discoverRepos (with {@code next}
 * pagination + repo field mapping), listBranches, whoAmI (username/display_name/fallback), openPullRequest
 * (html link vs id fallback), the Basic vs Bearer auth header on the wire, and every error path.
 */
class BitbucketCloudClientWireMockTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider secrets = key -> Optional.of(key.equals("GIT_USERNAME") ? "alice" : "s3cr3t");
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

    /** Build a client pointed at WireMock. baseUrl deliberately ends with '/' to exercise base() trimming. */
    private BitbucketCloudClient client(String authType) {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getBitbucket().setBaseUrl("http://localhost:" + wm.port() + "/");
        p.getBitbucket().setWorkspace("bnc");
        p.getBitbucket().setAuthType(authType);
        return new BitbucketCloudClient(p, secrets, mapper, retries);
    }

    // ---------- discoverRepos ----------

    @Test
    void discoverReposMapsAllFieldsAndPicksHttpsCloneUrl() {
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"values\":[{"
                        + "\"slug\":\"ciam-policies-tests\","
                        + "\"name\":\"CIAM Policies Tests\","
                        + "\"description\":\"contract tests\","
                        + "\"mainbranch\":{\"name\":\"main\"},"
                        + "\"project\":{\"key\":\"APP7571\"},"
                        + "\"updated_on\":\"2026-06-01T00:00:00Z\","
                        + "\"links\":{\"clone\":["
                        + "{\"name\":\"ssh\",\"href\":\"git@ssh.bitbucket.org:bnc/ciam.git\"},"
                        + "{\"name\":\"https\",\"href\":\"https://bitbucket.org/bnc/ciam.git\"}]}}]}")));

        List<RepoInfo> repos = client("APP_PASSWORD").discoverRepos("APP7571");

        assertThat(repos).hasSize(1);
        RepoInfo r = repos.get(0);
        assertThat(r.slug()).isEqualTo("ciam-policies-tests");
        assertThat(r.name()).isEqualTo("CIAM Policies Tests");
        assertThat(r.description()).isEqualTo("contract tests");
        assertThat(r.defaultBranch()).isEqualTo("main");
        assertThat(r.cloneUrl()).isEqualTo("https://bitbucket.org/bnc/ciam.git");
        assertThat(r.projectKey()).isEqualTo("APP7571");
        assertThat(r.updatedOn()).isEqualTo("2026-06-01T00:00:00Z");
    }

    @Test
    void discoverReposSlugFallsBackToNameAndCloneUrlBlankWhenNoHttps() {
        // no slug -> falls back to name; clone list has only ssh -> cloneUrl stays ""
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"values\":[{"
                        + "\"name\":\"only-name\","
                        + "\"links\":{\"clone\":[{\"name\":\"ssh\",\"href\":\"git@x\"}]}}]}")));

        List<RepoInfo> repos = client("APP_PASSWORD").discoverRepos("APP1");

        assertThat(repos).hasSize(1);
        assertThat(repos.get(0).slug()).isEqualTo("only-name");
        assertThat(repos.get(0).cloneUrl()).isEmpty();
        assertThat(repos.get(0).defaultBranch()).isEmpty();
    }

    @Test
    void discoverReposFollowsNextPageThenStopsWhenNextNull() {
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc")).inScenario("disco")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[{\"slug\":\"r1\"}],"
                                + "\"next\":\"http://localhost:" + wm.port() + "/2.0/repositories/bnc?page=2\"}"))
                .willSetStateTo("p2"));
        // page 2 carries an explicit null next -> loop terminates
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc")).withQueryParam("page", equalTo("2"))
                .inScenario("disco").whenScenarioStateIs("p2")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[{\"slug\":\"r2\"}],\"next\":null}")));

        List<RepoInfo> repos = client("APP_PASSWORD").discoverRepos("APP7571");

        assertThat(repos).extracting(RepoInfo::slug).containsExactly("r1", "r2");
        wm.verify(2, getRequestedFor(urlPathEqualTo("/2.0/repositories/bnc")));
    }

    @Test
    void discoverReposSendsBasicAuthHeaderOnTheWire() {
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"values\":[]}")));

        client("APP_PASSWORD").discoverRepos("APP7571");

        // Basic base64("alice:s3cr3t")
        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("alice:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wm.verify(getRequestedFor(urlPathEqualTo("/2.0/repositories/bnc"))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void discoverReposWrapsHttpErrorInIllegalState() {
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> client("APP_PASSWORD").discoverRepos("APPX"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bitbucket discovery failed for app-id 'APPX'");
    }

    // ---------- listBranches ----------

    @Test
    void listBranchesParsesNamesAndFollowsNext() {
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc/ciam/refs/branches")).inScenario("br")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[{\"name\":\"main\"},{\"name\":\"develop\"}],"
                                + "\"next\":\"http://localhost:" + wm.port()
                                + "/2.0/repositories/bnc/ciam/refs/branches?page=2\"}"))
                .willSetStateTo("p2"));
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc/ciam/refs/branches"))
                .withQueryParam("page", equalTo("2")).inScenario("br").whenScenarioStateIs("p2")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[{\"name\":\"release/8.2\"}]}")));   // missing next -> stop

        List<String> branches = client("OAUTH").listBranches("APP7571", "ciam");

        assertThat(branches).containsExactly("main", "develop", "release/8.2");
        wm.verify(2, getRequestedFor(urlPathEqualTo("/2.0/repositories/bnc/ciam/refs/branches")));
    }

    @Test
    void listBranchesSendsBearerHeaderForOauth() {
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc/ciam/refs/branches")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"values\":[]}")));

        client("OAUTH").listBranches("APP7571", "ciam");

        wm.verify(getRequestedFor(urlPathEqualTo("/2.0/repositories/bnc/ciam/refs/branches"))
                .withHeader("Authorization", equalTo("Bearer s3cr3t")));
    }

    @Test
    void listBranchesWrapsHttpErrorInIllegalState() {
        wm.stubFor(get(urlPathEqualTo("/2.0/repositories/bnc/ciam/refs/branches"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client("OAUTH").listBranches("APP7571", "ciam"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bitbucket branch listing failed for 'ciam'");
    }

    // ---------- whoAmI ----------

    @Test
    void whoAmIReturnsUsername() {
        wm.stubFor(get(urlPathEqualTo("/2.0/user")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"username\":\"alice\",\"display_name\":\"Alice A\"}")));

        assertThat(client("OAUTH").whoAmI()).isEqualTo("alice");
    }

    @Test
    void whoAmIFallsBackToDisplayNameWhenNoUsername() {
        wm.stubFor(get(urlPathEqualTo("/2.0/user")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"display_name\":\"Alice A\"}")));

        assertThat(client("OAUTH").whoAmI()).isEqualTo("Alice A");
    }

    @Test
    void whoAmIFallsBackToAuthenticatedWhenNeitherPresent() {
        wm.stubFor(get(urlPathEqualTo("/2.0/user")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{}")));

        assertThat(client("OAUTH").whoAmI()).isEqualTo("authenticated");
    }

    @Test
    void whoAmIWrapsHttpErrorInIllegalState() {
        wm.stubFor(get(urlPathEqualTo("/2.0/user")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client("OAUTH").whoAmI())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bitbucket /2.0/user failed");
    }

    // ---------- openPullRequest ----------

    @Test
    void openPullRequestReturnsHtmlLinkAndPostsPayload() {
        wm.stubFor(post(urlPathEqualTo("/2.0/repositories/bnc/ciam/pullrequests")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":42,\"links\":{\"html\":{\"href\":\"https://bitbucket.org/bnc/ciam/pull-requests/42\"}}}")));

        String url = client("APP_PASSWORD").openPullRequest("ciam", "veritas/gen", "main",
                "Add generated API tests", "Generated by Veritas");

        assertThat(url).isEqualTo("https://bitbucket.org/bnc/ciam/pull-requests/42");
        wm.verify(postRequestedFor(urlPathEqualTo("/2.0/repositories/bnc/ciam/pullrequests"))
                .withHeader("Content-Type", matching("application/json.*"))
                .withRequestBody(matching(".*\"veritas/gen\".*"))
                .withRequestBody(matching(".*\"main\".*"))
                .withRequestBody(matching(".*Generated by Veritas.*")));
    }

    @Test
    void openPullRequestFallsBackToIdWhenHtmlLinkBlank() {
        wm.stubFor(post(urlPathEqualTo("/2.0/repositories/bnc/ciam/pullrequests")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":42}")));   // no links.html.href -> blank -> id used

        String url = client("APP_PASSWORD").openPullRequest("ciam", "veritas/gen", null,
                "Add generated API tests", null);

        assertThat(url).isEqualTo("42");
    }

    @Test
    void openPullRequestWrapsHttpErrorInIllegalState() {
        wm.stubFor(post(urlPathEqualTo("/2.0/repositories/bnc/ciam/pullrequests"))
                .willReturn(aResponse().withStatus(400).withBody("bad")));

        assertThatThrownBy(() -> client("APP_PASSWORD")
                .openPullRequest("ciam", "veritas/gen", "main", "t", "d"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bitbucket PR creation failed for 'ciam'");
    }
}