package ca.bnc.qe.veritas.integration.jira;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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

/** Verifies the Jira Server/DC v2 client (wiki-markup descriptions, Bearer PAT) against WireMock. */
class JiraServerClientWireMockTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider secrets = key -> Optional.of("pat-123");
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

    private JiraServerClient client() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl("http://localhost:" + wm.port());
        p.getJira().setEdition("SERVER_DC");
        p.getJira().setAuthType("BEARER");
        return new JiraServerClient(p, secrets, mapper, retries, new CorpHttp(retries));
    }

    @Test
    void usesBearerAuthAndWikiMarkupDescription() throws Exception {
        JiraServerClient c = client();
        assertThat(c.authHeader()).isEqualTo("Bearer pat-123");
        String payload = c.buildCreatePayload(new JiraCreateRequest(
                "CIAM", "Bug", "ciam-policies — POST /x — missing",
                List.of("*Actual:* 404", "*Expected:* 200"), List.of("contract-validation")));
        var node = mapper.readTree(payload);
        // v2 description is a plain wiki string, NOT an ADF object
        assertThat(node.path("fields").path("description").isTextual()).isTrue();
        assertThat(node.path("fields").path("description").asText()).contains("*Actual:* 404").contains("*Expected:* 200");
        assertThat(node.path("fields").path("project").path("key").asText()).isEqualTo("CIAM");
    }

    @Test
    void createIssueHitsV2AndReturnsKey() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"key\":\"CIAM-7\"}")));
        String key = client().createIssue(new JiraCreateRequest(
                "CIAM", "Bug", "summary", List.of("desc"), List.of("contract-validation")));
        assertThat(key).isEqualTo("CIAM-7");
    }

    @Test
    void getStatusHitsV2() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CIAM-1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"fields\":{\"status\":{\"name\":\"Done\",\"statusCategory\":{\"key\":\"done\"}}}}")));
        JiraStatus s = client().getStatus("CIAM-1");
        assertThat(s.name()).isEqualTo("Done");
        assertThat(s.categoryKey()).isEqualTo("done");
    }

    @Test
    void searchHitsV2() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issues\":[{\"key\":\"CIAM-9\",\"fields\":{\"summary\":\"Release item\"}}]}")));
        List<JiraIssue> issues = client().search("fixVersion = \"1.0\"", List.of("summary"), 50);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).key()).isEqualTo("CIAM-9");
    }

    @Test
    void searchPagesUntilTotalReached() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).inScenario("paging")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"total\":3,\"issues\":[{\"key\":\"CIAM-1\",\"fields\":{\"summary\":\"a\"}},"
                                + "{\"key\":\"CIAM-2\",\"fields\":{\"summary\":\"b\"}}]}"))
                .willSetStateTo("page2"));
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).inScenario("paging")
                .whenScenarioStateIs("page2")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"total\":3,\"issues\":[{\"key\":\"CIAM-3\",\"fields\":{\"summary\":\"c\"}}]}")));
        List<JiraIssue> issues = client().search("fixVersion = \"1.0\"", List.of("summary"), 200);
        assertThat(issues).hasSize(3);
        assertThat(issues).extracting(JiraIssue::key).containsExactly("CIAM-1", "CIAM-2", "CIAM-3");
        wm.verify(2, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/rest/api/2/search")));
    }

    @Test
    void listTransitionsParsesAvailableTransitions() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CIAM-1/transitions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"transitions\":[{\"id\":\"11\",\"name\":\"In Progress\"},"
                        + "{\"id\":\"31\",\"name\":\"Done\"}]}")));
        List<JiraTransition> ts = client().listTransitions("CIAM-1");
        assertThat(ts).extracting(JiraTransition::name).containsExactly("In Progress", "Done");
        assertThat(ts.get(1).id()).isEqualTo("31");
    }

    @Test
    void transitionPostsTheTransitionId() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue/CIAM-1/transitions"))
                .willReturn(aResponse().withStatus(204)));
        client().transition("CIAM-1", "31");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/issue/CIAM-1/transitions"))
                .withRequestBody(containing("\"id\":\"31\"")));
    }

    @Test
    void addCommentHitsV2() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue/CIAM-1/comment")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"10\"}")));
        client().addComment("CIAM-1", "Fix applied — please re-verify.");
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/rest/api/2/issue/CIAM-1/comment")));
    }

    @Test
    void attachFileHitsAttachmentsEndpoint() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue/CIAM-1/attachments")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("[{\"id\":\"5\"}]")));
        client().attachFile("CIAM-1", "corrected-openapi.yaml", "openapi: 3.0.0\n");
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/rest/api/2/issue/CIAM-1/attachments"))
                .withHeader("X-Atlassian-Token", com.github.tomakehurst.wiremock.client.WireMock.equalTo("no-check")));
    }

    @Test
    void listVersionsParsesProjectVersions() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/project/CIAM/versions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"id\":\"1\",\"name\":\"8.2\",\"released\":false,\"archived\":false}]")));
        List<JiraVersion> versions = client().listVersions("CIAM");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).name()).isEqualTo("8.2");
        assertThat(versions.get(0).released()).isFalse();
    }

    @Test
    void createMetaDiscoversAllowedAndCustomFields() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"projects\":[{\"issuetypes\":[{\"fields\":{"
                        + "\"summary\":{\"name\":\"Summary\"},"
                        + "\"customfield_10001\":{\"name\":\"Epic Link\"},"
                        + "\"customfield_10002\":{\"name\":\"Team\"}}}]}]}")));
        CreateMeta meta = client().createMeta("CIAM", "Bug");
        assertThat(meta.allowedFields()).contains("summary", "customfield_10001", "customfield_10002");
        assertThat(meta.epicLinkFieldKey()).isEqualTo("customfield_10001");
        assertThat(meta.teamFieldKey()).isEqualTo("customfield_10002");
    }

    @Test
    void createMetaFallsBackToTheV9EndpointsWhenTheClassicOneIsGone() {
        // Jira 9.x REMOVED the classic createmeta (?projectKeys&expand) — it 404s. The client must fall back to the
        // paginated /createmeta/{project}/issuetypes[/{id}] endpoints so Epic-Link discovery keeps working.
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta/CIAM/issuetypes")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"values\":[{\"id\":\"10100\",\"name\":\"Task\"},{\"id\":\"10001\",\"name\":\"Bug\"}]}")));
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta/CIAM/issuetypes/10001")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"values\":[{\"fieldId\":\"summary\",\"name\":\"Summary\"},"
                        + "{\"fieldId\":\"customfield_10014\",\"name\":\"Epic Link\"}]}")));

        CreateMeta meta = client().createMeta("CIAM", "Bug");

        assertThat(meta.allowedFields()).contains("summary", "customfield_10014");
        assertThat(meta.epicLinkFieldKey()).isEqualTo("customfield_10014");
    }

    @Test
    void createIssueStillSucceedsWhenCreateMetaFailsForTheEpicLink() {
        // A create-meta failure (404 classic + broken v9) must NOT sink the whole create — file the ticket UNLINKED
        // rather than losing it entirely (the reported regression: a createMeta 404 aborted the app-ticket launch).
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta/CIAM/issuetypes"))
                .willReturn(aResponse().withStatus(500)));
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"key\":\"CIAM-9\"}")));

        String key = client().createIssue(new JiraCreateRequest(
                "CIAM", "Task", "summary", List.of("desc"), List.of("veritas"), "CIAM-100"));

        assertThat(key).isEqualTo("CIAM-9");   // created (unlinked) despite create-meta being unavailable
    }
}
