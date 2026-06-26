package ca.bnc.qe.veritas.integration.confluence;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.Map;
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
 * Branch coverage for {@link ConfluenceCloudClient}: getPage / getPagesBySpace paging / whoAmI,
 * the Cloud-vs-Server apiBase prefix, BASIC-vs-BEARER auth header, and the error / null-body / blank-next guards.
 * Mirrors {@link ConfluenceDescendantsWireMockTest} construction. Does not touch the existing tests.
 */
class ConfluenceCloudClientBranchTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider secrets = key -> Optional.of(Map.of(
            "JIRA_USERNAME", "alice", "CONFLUENCE_API_TOKEN", "conf-pat").getOrDefault(key, ""));
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

    private ConfluenceCloudClient client(String edition, String authType) {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getConfluence().setBaseUrl("http://localhost:" + wm.port());
        p.getConfluence().setEdition(edition);
        p.getConfluence().setAuthType(authType);
        return new ConfluenceCloudClient(p, secrets, mapper, retries);
    }

    // ---- getPage --------------------------------------------------------------------------------

    @Test
    void getPageReturnsParsedFieldsAndSendsBearerHeaderOnServerEdition() {
        wm.stubFor(get(urlEqualTo("/rest/api/content/4242?expand=body.storage"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"id\":\"4242\",\"title\":\"Hello\","
                                + "\"body\":{\"storage\":{\"value\":\"<p>hi</p>\"}}}")));

        ConfluencePage page = client("SERVER_DC", "BEARER").getPage("4242");

        assertThat(page.id()).isEqualTo("4242");
        assertThat(page.title()).isEqualTo("Hello");
        assertThat(page.storageXhtml()).isEqualTo("<p>hi</p>");
        wm.verify(getRequestedFor(urlEqualTo("/rest/api/content/4242?expand=body.storage"))
                .withHeader("Authorization", equalTo("Bearer conf-pat")));
    }

    @Test
    void getPageAcceptsAFullUrlAndExtractsTheNumericId() {
        wm.stubFor(get(urlEqualTo("/wiki/rest/api/content/1725186990?expand=body.storage"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"1725186990\",\"title\":\"From URL\"}")));

        ConfluencePage page = client("CLOUD", "BEARER")
                .getPage("https://wiki.bnc.ca/spaces/IAMAS/pages/1725186990/Some+Title");

        assertThat(page.id()).isEqualTo("1725186990");
        assertThat(page.title()).isEqualTo("From URL");
    }

    @Test
    void getPageFallsBackToRefIdAndEmptyStringsWhenFieldsMissing() {
        // Body without id/title/body — exercises the asText(id) and asText("") default branches.
        wm.stubFor(get(urlPathEqualTo("/rest/api/content/77"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));

        ConfluencePage page = client("SERVER_DC", "BEARER").getPage("77");

        assertThat(page.id()).isEqualTo("77");          // falls back to the requested id
        assertThat(page.title()).isEmpty();
        assertThat(page.storageXhtml()).isEmpty();
    }

    @Test
    void getPageTreatsAnEmptyBodyAsEmptyJsonAndStillFallsBackToId() {
        // 204-style empty body → resp == null → mapper reads "{}" (the null-body branch).
        wm.stubFor(get(urlPathEqualTo("/rest/api/content/88"))
                .willReturn(aResponse().withStatus(200)));   // no body

        ConfluencePage page = client("SERVER_DC", "BEARER").getPage("88");

        assertThat(page.id()).isEqualTo("88");
        assertThat(page.title()).isEmpty();
        assertThat(page.storageXhtml()).isEmpty();
    }

    @Test
    void getPageWrapsHttpErrorsInIllegalStateWithTheRef() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/content/500"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client("SERVER_DC", "BEARER").getPage("500"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Confluence getPage failed for 500");
    }

    @Test
    void getPageWrapsMalformedJsonInIllegalState() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/content/9"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("not-json{")));

        assertThatThrownBy(() -> client("SERVER_DC", "BEARER").getPage("9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Confluence getPage failed for 9");
    }

    // ---- getPagesBySpace ------------------------------------------------------------------------

    @Test
    void getPagesBySpaceFollowsTheCloudNextLinkAcrossPages() {
        // First page on the wiki-prefixed path returns a relative next link; second page closes the loop.
        wm.stubFor(get(urlEqualTo("/wiki/rest/api/content?spaceKey=DOCS&type=page&limit=100"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"results\":[{\"id\":\"1\",\"title\":\"A\"},{\"id\":\"2\",\"title\":\"B\"}],"
                                + "\"_links\":{\"next\":\"/wiki/rest/api/content?cursor=p2\"}}")));
        wm.stubFor(get(urlEqualTo("/wiki/rest/api/content?cursor=p2"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"results\":[{\"id\":\"3\",\"title\":\"C\"}],\"_links\":{}}")));

        List<ConfluencePage> pages = client("CLOUD", "BEARER").getPagesBySpace("DOCS");

        assertThat(pages).extracting(ConfluencePage::id).containsExactly("1", "2", "3");
        assertThat(pages).extracting(ConfluencePage::title).containsExactly("A", "B", "C");
        assertThat(pages).extracting(ConfluencePage::storageXhtml).containsOnly("");   // body left empty here
    }

    @Test
    void getPagesBySpaceStopsWhenNextLinkIsBlank() {
        // A present-but-blank next link must terminate paging (the next.isBlank() branch), not loop forever.
        wm.stubFor(get(urlEqualTo("/rest/api/content?spaceKey=DOCS&type=page&limit=100"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"results\":[{\"id\":\"1\",\"title\":\"A\"}],\"_links\":{\"next\":\"   \"}}")));

        List<ConfluencePage> pages = client("SERVER_DC", "BEARER").getPagesBySpace("DOCS");

        assertThat(pages).extracting(ConfluencePage::id).containsExactly("1");
    }

    @Test
    void getPagesBySpaceUrlEncodesTheSpaceKeyAndReturnsEmptyOnNoResults() {
        // URLEncoder.encode renders a space as '+' and '&' as %26, proving the key is encoded (not raw).
        wm.stubFor(get(urlEqualTo("/rest/api/content?spaceKey=A+B%26C&type=page&limit=100"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"results\":[],\"_links\":{}}")));

        List<ConfluencePage> pages = client("SERVER_DC", "BEARER").getPagesBySpace("A B&C");

        assertThat(pages).isEmpty();
    }

    @Test
    void getPagesBySpaceTreatsEmptyBodyAsEmptyJson() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/content"))
                .willReturn(aResponse().withStatus(200)));   // null body → "{}" → no results, single pass

        assertThat(client("SERVER_DC", "BEARER").getPagesBySpace("DOCS")).isEmpty();
    }

    @Test
    void getPagesBySpaceWrapsErrorsWithTheSpaceKey() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/content"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client("SERVER_DC", "BEARER").getPagesBySpace("BROKEN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Confluence getPagesBySpace failed for 'BROKEN'");
    }

    // ---- whoAmI ---------------------------------------------------------------------------------

    @Test
    void whoAmIReturnsTheDisplayName() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/user/current"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"displayName\":\"Alice Smith\"}")));

        assertThat(client("SERVER_DC", "BEARER").whoAmI()).isEqualTo("Alice Smith");
    }

    @Test
    void whoAmIDefaultsToAuthenticatedWhenDisplayNameMissing() {
        wm.stubFor(get(urlPathEqualTo("/wiki/rest/api/user/current"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));

        assertThat(client("CLOUD", "BEARER").whoAmI()).isEqualTo("authenticated");
    }

    @Test
    void whoAmIDefaultsToAuthenticatedOnEmptyBody() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/user/current"))
                .willReturn(aResponse().withStatus(200)));   // null body branch

        assertThat(client("SERVER_DC", "BEARER").whoAmI()).isEqualTo("authenticated");
    }

    @Test
    void whoAmIWrapsErrorsInIllegalState() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/user/current"))
                .willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client("SERVER_DC", "BEARER").whoAmI())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Confluence current-user failed");
    }

    // ---- apiBase prefix + auth header on the wire -----------------------------------------------

    @Test
    void cloudEditionUsesWikiPrefixAndBasicAuthHeaderWhenConfigured() {
        wm.stubFor(get(urlPathEqualTo("/wiki/rest/api/user/current"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"displayName\":\"Bob\"}")));

        assertThat(client("CLOUD", "BASIC").whoAmI()).isEqualTo("Bob");

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:conf-pat".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wm.verify(getRequestedFor(urlPathEqualTo("/wiki/rest/api/user/current"))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void editionMatchIsCaseInsensitiveForServerDc() {
        // lower-case "server_dc" must still hit the host-root path (equalsIgnoreCase branch), not /wiki.
        wm.stubFor(get(urlPathEqualTo("/rest/api/user/current"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"displayName\":\"Carol\"}")));

        assertThat(client("server_dc", "BEARER").whoAmI()).isEqualTo("Carol");
    }

    @Test
    void unknownEditionFallsBackToTheCloudWikiPrefix() {
        // Any non-SERVER_DC edition (here null) takes the /wiki prefix branch.
        wm.stubFor(get(urlPathEqualTo("/wiki/rest/api/user/current"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"displayName\":\"Dave\"}")));

        assertThat(client(null, "BEARER").whoAmI()).isEqualTo("Dave");
    }
}
