package ca.bnc.qe.veritas.integration.jira;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

/**
 * Branch/edge-case coverage for {@link JiraServerClient}, complementing {@link JiraServerClientWireMockTest}.
 * Targets the paths the happy-path test misses: full widened-issue parsing, both auth types, null/empty guards,
 * paging caps, base-URL normalization, createMeta field discovery (no epic/team), and every error branch.
 */
class JiraServerClientBranchTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider bearerSecrets = key -> Optional.of("pat-123");
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
        return clientWith("BEARER", bearerSecrets, "http://localhost:" + wm.port());
    }

    private JiraServerClient clientWith(String authType, SecretProvider secrets, String baseUrl) {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl(baseUrl);
        p.getJira().setEdition("SERVER_DC");
        p.getJira().setAuthType(authType);
        return new JiraServerClient(p, secrets, mapper, retries, new CorpHttp(retries));
    }

    // ---------------------------------------------------------------- auth branches

    @Test
    void basicAuthBuildsBase64UsernameColonToken() {
        SecretProvider secrets = key -> switch (key) {
            case "JIRA_USERNAME" -> Optional.of("alice");
            case "JIRA_API_TOKEN" -> Optional.of("s3cr3t");
            default -> Optional.empty();
        };
        JiraServerClient c = clientWith("basic", secrets, "http://localhost:" + wm.port());
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:s3cr3t".getBytes(StandardCharsets.UTF_8));
        assertThat(c.authHeader()).isEqualTo(expected);
    }

    @Test
    void nullAuthTypeFallsBackToBearer() {
        JiraServerClient c = clientWith(null, bearerSecrets, "http://localhost:" + wm.port());
        assertThat(c.authHeader()).isEqualTo("Bearer pat-123");
    }

    @Test
    void unknownAuthTypeFallsBackToBearer() {
        JiraServerClient c = clientWith("OAUTH", bearerSecrets, "http://localhost:" + wm.port());
        assertThat(c.authHeader()).isEqualTo("Bearer pat-123");
    }

    @Test
    void missingSecretYieldsBearerWithEmptyToken() {
        JiraServerClient c = clientWith("BEARER", key -> Optional.empty(), "http://localhost:" + wm.port());
        assertThat(c.authHeader()).isEqualTo("Bearer ");
    }

    // ---------------------------------------------------------------- base() normalization

    @Test
    void trailingSlashBaseUrlIsStrippedBeforePath() {
        // base ends with '/', so it must be trimmed — otherwise the URL would have a double slash and 404.
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"displayName\":\"Bob\"}")));
        JiraServerClient c = clientWith("BEARER", bearerSecrets, "http://localhost:" + wm.port() + "/");
        assertThat(c.whoAmI()).isEqualTo("Bob");
    }

    @Test
    void nullBaseUrlProducesEmptyBaseAndFailsCleanly() {
        JiraServerClient c = clientWith("BEARER", bearerSecrets, null);
        // base() returns "" so the URI is a bare path "/rest/api/2/myself" → connection failure wrapped as state.
        assertThatThrownBy(c::whoAmI)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira /myself failed");
    }

    // ---------------------------------------------------------------- whoAmI

    @Test
    void whoAmIReturnsDisplayName() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"displayName\":\"Jira Bot\"}")));
        assertThat(client().whoAmI()).isEqualTo("Jira Bot");
    }

    @Test
    void whoAmIDefaultsToAuthenticatedWhenDisplayNameMissing() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"name\":\"jbot\"}")));
        assertThat(client().whoAmI()).isEqualTo("authenticated");
    }

    @Test
    void whoAmIWrapsHttpErrorAsIllegalState() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/myself")).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client().whoAmI())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira /myself failed");
    }

    // ---------------------------------------------------------------- getIssue full parse

    @Test
    void getIssueParsesSummaryDescriptionAndWidenedMetadata() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CIAM-42")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"CIAM-42\",\"fields\":{"
                        + "\"summary\":\"Token endpoint mismatch\","
                        + "\"description\":\"*Actual:* 404\","
                        + "\"status\":{\"name\":\"In Progress\",\"statusCategory\":{\"key\":\"indeterminate\"}},"
                        + "\"priority\":{\"name\":\"High\"},"
                        + "\"labels\":[\"contract-validation\",\"ciam\"],"
                        + "\"components\":[{\"name\":\"auth\"}],"
                        + "\"issuelinks\":[{\"outwardIssue\":{\"key\":\"CIAM-99\"}}]}}")));
        JiraIssue issue = client().getIssue("CIAM-42");
        assertThat(issue.key()).isEqualTo("CIAM-42");
        assertThat(issue.summary()).isEqualTo("Token endpoint mismatch");
        assertThat(issue.description()).isNotNull();
        assertThat(issue.description().asText()).isEqualTo("*Actual:* 404");
        assertThat(issue.lifecycle()).isEqualTo("IN_PROGRESS");
        assertThat(issue.priority()).isEqualTo("High");
        assertThat(issue.labels()).containsExactly("contract-validation", "ciam");
        assertThat(issue.components()).containsExactly("auth");
        assertThat(issue.links()).containsExactly("CIAM-99");
    }

    @Test
    void getIssueWithNullDescriptionYieldsNullDescriptionAndEmptyMetadata() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CIAM-43")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"CIAM-43\",\"fields\":{\"summary\":\"No desc\",\"description\":null}}")));
        JiraIssue issue = client().getIssue("CIAM-43");
        assertThat(issue.key()).isEqualTo("CIAM-43");
        assertThat(issue.summary()).isEqualTo("No desc");
        assertThat(issue.description()).isNull();
        assertThat(issue.lifecycle()).isNull();
        assertThat(issue.priority()).isNull();
        assertThat(issue.labels()).isEmpty();
        assertThat(issue.components()).isEmpty();
        assertThat(issue.links()).isEmpty();
    }

    @Test
    void getIssueWrapsErrorWithKeyInMessage() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CIAM-404")).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> client().getIssue("CIAM-404"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira getIssue failed for CIAM-404");
    }

    // ---------------------------------------------------------------- getStatus

    @Test
    void getStatusDefaultsToEmptyWhenStatusFieldAbsent() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CIAM-5")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"fields\":{}}")));
        JiraStatus s = client().getStatus("CIAM-5");
        assertThat(s.name()).isEmpty();
        assertThat(s.categoryKey()).isEmpty();
    }

    @Test
    void getStatusWrapsErrorWithKeyInMessage() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CIAM-6")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().getStatus("CIAM-6"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira getStatus failed for CIAM-6");
    }

    // ---------------------------------------------------------------- createIssue

    @Test
    void createIssueDefaultsToEmptyKeyWhenAbsent() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{}")));
        String key = client().createIssue(new JiraCreateRequest(
                "CIAM", "Bug", "s", List.of("d"), List.of()));
        assertThat(key).isEmpty();
    }

    @Test
    void createIssueWrapsErrorAsIllegalState() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse().withStatus(400)));
        assertThatThrownBy(() -> client().createIssue(new JiraCreateRequest(
                "CIAM", "Bug", "s", List.of("d"), List.of("l"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira createIssue failed");
    }

    @Test
    void explainCreateFailure_turnsNotOnScreenIntoActionableProjectHint() {
        String raw = "400 Bad Request: {\"errors\":{\"summary\":\"Field 'summary' cannot be set. It is not on the "
                + "appropriate screen, or unknown.\"}}";
        String msg = JiraClient.explainCreateFailure(
                new JiraCreateRequest("CIAM", "Task", "s", List.of(), List.of()), raw);
        assertThat(msg).contains("project 'CIAM'").contains("'Task'").contains("Settings");
        // a generic (non-screen) error is passed through unchanged, keeping the old wrapping
        assertThat(JiraClient.explainCreateFailure(
                new JiraCreateRequest("CIAM", "Task", "s", null, null), "500 Server Error"))
                .isEqualTo("Jira createIssue failed: 500 Server Error");
    }

    @Test
    void createIssueFieldNotOnScreen_surfacesTheActionableHint() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errorMessages\":[],\"errors\":{\"summary\":\"Field 'summary' cannot be set. "
                        + "It is not on the appropriate screen, or unknown.\"}}")));
        assertThatThrownBy(() -> client().createIssue(new JiraCreateRequest(
                "CIAM", "Task", "s", List.of("d"), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project 'CIAM'")
                .hasMessageContaining("'Task'");
    }

    // ---------------------------------------------------------------- addComment

    @Test
    void addCommentWithNullBodySendsEmptyString() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue/CIAM-1/comment")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"1\"}")));
        client().addComment("CIAM-1", null);
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/issue/CIAM-1/comment"))
                .withRequestBody(equalToJson("{\"body\":\"\"}")));
    }

    @Test
    void addCommentWrapsErrorWithKeyInMessage() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue/CIAM-1/comment"))
                .willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> client().addComment("CIAM-1", "hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira addComment failed for CIAM-1");
    }

    // ---------------------------------------------------------------- attachFile

    @Test
    void attachFileWithNullContentStillPostsMultipart() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue/CIAM-1/attachments")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("[{\"id\":\"9\"}]")));
        client().attachFile("CIAM-1", "empty.yaml", null);
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/issue/CIAM-1/attachments"))
                .withHeader("X-Atlassian-Token", WireMock.equalTo("no-check")));
    }

    @Test
    void attachFileWrapsErrorWithKeyInMessage() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue/CIAM-1/attachments"))
                .willReturn(aResponse().withStatus(413)));
        assertThatThrownBy(() -> client().attachFile("CIAM-1", "big.yaml", "data"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira attachFile failed for CIAM-1");
    }

    // ---------------------------------------------------------------- listVersions

    @Test
    void listVersionsParsesReleasedAndArchivedFlags() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/project/CIAM/versions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"id\":\"1\",\"name\":\"8.1\",\"released\":true,\"archived\":true},"
                        + "{\"id\":\"2\",\"name\":\"8.2\",\"released\":false,\"archived\":false}]")));
        List<JiraVersion> versions = client().listVersions("CIAM");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).id()).isEqualTo("1");
        assertThat(versions.get(0).name()).isEqualTo("8.1");
        assertThat(versions.get(0).released()).isTrue();
        assertThat(versions.get(0).archived()).isTrue();
        assertThat(versions.get(1).released()).isFalse();
        assertThat(versions.get(1).archived()).isFalse();
    }

    @Test
    void listVersionsReturnsEmptyForEmptyArray() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/project/CIAM/versions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("[]")));
        assertThat(client().listVersions("CIAM")).isEmpty();
    }

    @Test
    void listVersionsEncodesProjectKeyAndWrapsError() {
        // A project key with a space exercises URLEncoder; the 500 then exercises the error branch.
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/project/MY+PROJ/versions"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().listVersions("MY PROJ"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira listVersions failed for MY PROJ");
    }

    // ---------------------------------------------------------------- createMeta

    @Test
    void createMetaWithoutEpicOrTeamFieldsReturnsNulls() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"projects\":[{\"issuetypes\":[{\"fields\":{"
                        + "\"summary\":{\"name\":\"Summary\"},"
                        + "\"description\":{\"name\":\"Description\"}}}]}]}")));
        CreateMeta meta = client().createMeta("CIAM", "Bug");
        assertThat(meta.allowedFields()).containsExactlyInAnyOrder("summary", "description");
        assertThat(meta.epicLinkFieldKey()).isNull();
        assertThat(meta.teamFieldKey()).isNull();
    }

    @Test
    void createMetaWithNoProjectsReturnsEmptyMeta() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"projects\":[]}")));
        CreateMeta meta = client().createMeta("CIAM", "Bug");
        assertThat(meta.allowedFields()).isEmpty();
        assertThat(meta.epicLinkFieldKey()).isNull();
        assertThat(meta.teamFieldKey()).isNull();
    }

    @Test
    void createMetaKeepsFirstEpicLinkAndTeamWhenDuplicatesPresent() {
        // Two epic-link-named and two team-named fields: only the FIRST of each is kept (null-guard branch).
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"projects\":[{\"issuetypes\":[{\"fields\":{"
                        + "\"customfield_1\":{\"name\":\"Epic Link\"},"
                        + "\"customfield_2\":{\"name\":\"Epic   link\"},"
                        + "\"customfield_3\":{\"name\":\"Team\"},"
                        + "\"customfield_4\":{\"name\":\"team\"}}}]}]}")));
        CreateMeta meta = client().createMeta("CIAM", "Bug");
        assertThat(meta.epicLinkFieldKey()).isEqualTo("customfield_1");
        assertThat(meta.teamFieldKey()).isEqualTo("customfield_3");
        assertThat(meta.allowedFields())
                .containsExactlyInAnyOrder("customfield_1", "customfield_2", "customfield_3", "customfield_4");
    }

    @Test
    void createMetaWrapsErrorWithProjectInMessage() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta"))
                .willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> client().createMeta("CIAM", "Bug"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira createMeta failed for CIAM");
    }

    // ---------------------------------------------------------------- search paging caps

    @Test
    void searchWithNonPositiveMaxResultsUsesUnboundedCapAndStopsAtTotal() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\":1,\"issues\":[{\"key\":\"CIAM-1\",\"fields\":{\"summary\":\"only\"}}]}")));
        List<JiraIssue> issues = client().search("project = CIAM", List.of("summary"), 0);
        assertThat(issues).extracting(JiraIssue::key).containsExactly("CIAM-1");
        // First page returned 1 == total → loop breaks after one request even though cap is MAX_VALUE.
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/search")));
    }

    @Test
    void searchStopsWhenCapReachedEvenIfMorePagesExist() {
        // cap=2; first page returns 2 issues (total=10) → issues.size() reaches cap, while-loop exits without a 2nd call.
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\":10,\"issues\":["
                        + "{\"key\":\"CIAM-1\",\"fields\":{\"summary\":\"a\"}},"
                        + "{\"key\":\"CIAM-2\",\"fields\":{\"summary\":\"b\"}}]}")));
        List<JiraIssue> issues = client().search("project = CIAM", List.of("summary"), 2);
        assertThat(issues).extracting(JiraIssue::key).containsExactly("CIAM-1", "CIAM-2");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/search")));
    }

    @Test
    void searchBreaksOnEmptyPageEvenWhenTotalClaimsMore() {
        // total says 5 but the page is empty → returned==0 guard breaks the loop (no infinite loop).
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\":5,\"issues\":[]}")));
        List<JiraIssue> issues = client().search("project = CIAM", List.of("summary"), 50);
        assertThat(issues).isEmpty();
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/search")));
    }

    @Test
    void searchSecondPageSizeRespectsRemainingCap() throws Exception {
        // cap=150, PAGE_SIZE=100: first page asks for 100, second page must ask for the remaining 50.
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).inScenario("cap")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(pageBody(0, 100, 200)))
                .willSetStateTo("page2"));
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).inScenario("cap")
                .whenScenarioStateIs("page2")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(pageBody(100, 50, 200))));
        List<JiraIssue> issues = client().search("project = CIAM", List.of("summary"), 150);
        assertThat(issues).hasSize(150);
        wm.verify(2, postRequestedFor(urlPathEqualTo("/rest/api/2/search")));
        // The second request must cap maxResults at the remaining 50 (cap - already-fetched), not the full PAGE_SIZE.
        wm.verify(postRequestedFor(urlPathEqualTo("/rest/api/2/search"))
                .withRequestBody(WireMock.matchingJsonPath("$[?(@.maxResults == 50)]")));
    }

    @Test
    void searchWrapsErrorAsIllegalState() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().search("bad jql", List.of("summary"), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira search failed");
    }

    // ---------------------------------------------------------------- buildCreatePayload guards

    @Test
    void buildCreatePayloadOmitsLabelsWhenNullOrEmptyAndJoinsParagraphs() throws Exception {
        JiraServerClient c = client();
        // null labels + null description paragraphs → labels array absent, description empty.
        String nullLabels = c.buildCreatePayload(new JiraCreateRequest("CIAM", "Bug", "s", null, null));
        JsonNode n1 = mapper.readTree(nullLabels);
        assertThat(n1.path("fields").has("labels")).isFalse();
        assertThat(n1.path("fields").path("description").asText()).isEmpty();
        assertThat(n1.path("fields").path("issuetype").path("name").asText()).isEqualTo("Bug");

        // empty labels list → still no labels array.
        String emptyLabels = c.buildCreatePayload(new JiraCreateRequest(
                "CIAM", "Bug", "s", List.of("p1", "p2"), List.of()));
        JsonNode n2 = mapper.readTree(emptyLabels);
        assertThat(n2.path("fields").has("labels")).isFalse();
        // Paragraphs are joined with a blank line.
        assertThat(n2.path("fields").path("description").asText()).isEqualTo("p1\n\np2");

        // non-empty labels → array populated.
        String withLabels = c.buildCreatePayload(new JiraCreateRequest(
                "CIAM", "Bug", "s", List.of("p"), List.of("a", "b")));
        JsonNode n3 = mapper.readTree(withLabels);
        assertThat(n3.path("fields").path("labels")).hasSize(2);
        assertThat(n3.path("fields").path("labels").get(0).asText()).isEqualTo("a");
    }

    // ---------------------------------------------------------------- epic link (Server/DC "Epic Link" field)

    @Test
    void buildCreatePayloadSetsEpicLinkWhenFieldKeyAndParentPresent() throws Exception {
        String json = client().buildCreatePayload(
                new JiraCreateRequest("CIAM", "Task", "s", List.of("p"), List.of("l"), "CIAM-100"),
                "customfield_10011");
        JsonNode fields = mapper.readTree(json).path("fields");
        assertThat(fields.path("customfield_10011").asText()).isEqualTo("CIAM-100");
    }

    @Test
    void buildCreatePayloadOmitsEpicLinkWhenParentOrFieldKeyAbsent_regression() throws Exception {
        JiraServerClient c = client();
        // The no-epic payload (5-arg request, the common case) is the byte-for-byte baseline every guard compares to.
        String baseline = c.buildCreatePayload(new JiraCreateRequest("CIAM", "Task", "s", List.of("p"), List.of("l")));
        // A field key but no parent → identical to baseline (nothing to link).
        assertThat(c.buildCreatePayload(
                new JiraCreateRequest("CIAM", "Task", "s", List.of("p"), List.of("l")), "customfield_10011"))
                .isEqualTo(baseline);
        // A parent but no discovered field key → identical to baseline (can't link, don't fabricate a field).
        assertThat(c.buildCreatePayload(
                new JiraCreateRequest("CIAM", "Task", "s", List.of("p"), List.of("l"), "CIAM-100"), null))
                .isEqualTo(baseline);
    }

    @Test
    void createIssueUnderEpicDiscoversEpicLinkFieldAndSetsIt() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"projects\":[{\"issuetypes\":[{\"fields\":{"
                        + "\"customfield_10011\":{\"name\":\"Epic Link\"}}}]}]}")));
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"key\":\"CIAM-201\"}")));
        String key = client().createIssue(new JiraCreateRequest(
                "CIAM", "Task", "Bump jackson", List.of("body"), List.of("snyk"), "CIAM-100"));
        assertThat(key).isEqualTo("CIAM-201");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/2/issue"))
                .withRequestBody(matchingJsonPath("$.fields.customfield_10011", WireMock.equalTo("CIAM-100"))));
    }

    @Test
    void createIssueUnderEpicStillCreatesWhenNoEpicLinkFieldDiscovered() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/issue/createmeta")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"projects\":[{\"issuetypes\":[{\"fields\":{\"summary\":{\"name\":\"Summary\"}}}]}]}")));
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"key\":\"CIAM-202\"}")));
        // Soft-fail: no Epic Link field on the create screen → the ticket is still created (no epic field on it;
        // the byte-identical guard above proves the field is omitted when the key can't be discovered).
        String key = client().createIssue(new JiraCreateRequest(
                "CIAM", "Task", "s", List.of("b"), List.of(), "CIAM-100"));
        assertThat(key).isEqualTo("CIAM-202");
    }

    // ---------------------------------------------------------------- listProjects

    @Test
    void listProjectsReturnsKeyAndNamePairs() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/project")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"key\":\"CIAM\",\"name\":\"CIAM Access\"},{\"key\":\"APP\",\"name\":\"App\"}]")));
        List<JiraProject> projects = client().listProjects();
        assertThat(projects).extracting(JiraProject::key).containsExactly("CIAM", "APP");
        assertThat(projects).extracting(JiraProject::name).containsExactly("CIAM Access", "App");
    }

    @Test
    void listProjectsWrapsErrorAsIllegalState() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/2/project")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().listProjects())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira listProjects failed");
    }

    /** Builds a Jira search response page with {@code count} sequential issue keys starting at {@code start}. */
    private static String pageBody(int start, int count, int total) {
        StringBuilder sb = new StringBuilder("{\"total\":").append(total).append(",\"issues\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"key\":\"CIAM-").append(start + i).append("\",\"fields\":{\"summary\":\"s\"}}");
        }
        return sb.append("]}").toString();
    }

    /** Sanity: the search request body carries the jql + fields we passed (covers the writeValueAsString branch). */
    @Test
    void searchSerializesJqlAndFieldsIntoRequestBody() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/2/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"total\":0,\"issues\":[]}")));
        client().search("fixVersion = 8.2", List.of("summary", "status"), 25);
        wm.verify(postRequestedFor(urlPathEqualTo("/rest/api/2/search"))
                .withRequestBody(WireMock.matchingJsonPath("$[?(@.jql == 'fixVersion = 8.2')]"))
                .withRequestBody(WireMock.matchingJsonPath("$.fields[0]", WireMock.equalTo("summary"))));
    }
}