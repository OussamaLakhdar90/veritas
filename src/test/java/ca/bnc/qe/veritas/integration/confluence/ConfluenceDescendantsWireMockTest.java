package ca.bnc.qe.veritas.integration.confluence;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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

/** The Confluence page-tree walk: root-first BFS, edition-aware path, cycle guard + a hard page cap. */
class ConfluenceDescendantsWireMockTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider secrets = key -> Optional.of("pat-1");
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

    private ConfluenceCloudClient client(String edition) {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getConfluence().setBaseUrl("http://localhost:" + wm.port());
        p.getConfluence().setEdition(edition);
        p.getConfluence().setAuthType("BEARER");
        return new ConfluenceCloudClient(p, secrets, mapper, retries);
    }

    private void stubChildren(String prefix, String id, String body) {
        wm.stubFor(get(urlPathEqualTo(prefix + "/content/" + id + "/child/page"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private List<String> ids(ConfluenceCloudClient c, String root, int max) {
        return c.descendants(root, max).stream().map(ConfluencePage::id).toList();
    }

    @Test
    void serverDcWalksTheTreeRootFirst() {
        stubChildren("/rest/api", "100", "{\"results\":[{\"id\":\"101\"},{\"id\":\"102\"}],\"_links\":{}}");
        stubChildren("/rest/api", "101", "{\"results\":[],\"_links\":{}}");
        stubChildren("/rest/api", "102", "{\"results\":[],\"_links\":{}}");

        assertThat(ids(client("SERVER_DC"), "100", 500)).containsExactly("100", "101", "102");
    }

    @Test
    void cloudUsesTheWikiPrefixedPath() {
        stubChildren("/wiki/rest/api", "100", "{\"results\":[{\"id\":\"101\"}],\"_links\":{}}");
        stubChildren("/wiki/rest/api", "101", "{\"results\":[],\"_links\":{}}");

        assertThat(ids(client("CLOUD"), "100", 500)).containsExactly("100", "101");
    }

    @Test
    void aBackLinkToTheRootIsVisitedOnlyOnce() {
        stubChildren("/rest/api", "100", "{\"results\":[{\"id\":\"101\"}],\"_links\":{}}");
        stubChildren("/rest/api", "101", "{\"results\":[{\"id\":\"100\"}],\"_links\":{}}");   // 101 → back to root

        assertThat(ids(client("SERVER_DC"), "100", 500)).containsExactly("100", "101");
    }

    @Test
    void theMaxPagesCapBoundsAWideTree() {
        // 100 has three children; with a cap of 2 only the root + one child are returned and the deeper fetch is skipped.
        stubChildren("/rest/api", "100", "{\"results\":[{\"id\":\"101\"},{\"id\":\"102\"},{\"id\":\"103\"}],\"_links\":{}}");

        assertThat(ids(client("SERVER_DC"), "100", 2)).containsExactly("100", "101");
    }
}
