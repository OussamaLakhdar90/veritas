package ca.bnc.qe.veritas.integration.jira;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

/**
 * Verifies the Jira Cloud REST v3 client (ADF descriptions, Basic auth = email:token) against WireMock.
 * Exercises every endpoint the class calls (search w/ paging, getIssue, whoAmI, createIssue, getStatus,
 * listVersions), the auth-header encoding, base-URL normalization, and the error / null-body branches.
 */
class JiraCloudClientWireMockTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    // Cloud Basic auth uses email + API token; provide distinct values per key so the header is verifiable.
    private final SecretProvider secrets = key -> Optional.of(
            "JIRA_USERNAME".equals(key) ? "alice@bnc.ca" : "tok-987");
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

    private JiraCloudClient client() {
        return client("http://localhost:" + wm.port());
    }

    private JiraCloudClient client(String baseUrl) {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl(baseUrl);
        p.getJira().setEdition("CLOUD");
        p.getJira().setAuthType("BASIC");
        return new JiraCloudClient(p, secrets, mapper, retries);
    }

    private String expectedBasicHeader() {
        String raw = "alice@bnc.ca:tok-987";
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- transitions (v3)

    @Test
    void listTransitionsParsesIdNameAndDestinationStatus() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/CIAM-1/transitions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"transitions\":[{\"id\":\"31\",\"name\":\"Ship it\",\"to\":{\"name\":\"Done\"}}]}")));
        List<JiraTransition> ts = client().listTransitions("CIAM-1");
        assertThat(ts).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo("31");
            assertThat(t.name()).isEqualTo("Ship it");
            assertThat(t.toStatus()).isEqualTo("Done");
        });
    }

    @Test
    void transitionPostsTheTransitionIdToV3() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/issue/CIAM-1/transitions"))
                .willReturn(aResponse().withStatus(204)));
        client().transition("CIAM-1", "31");
        wm.verify(postRequestedFor(urlPathEqualTo("/rest/api/3/issue/CIAM-1/transitions"))
                .withRequestBody(containing("\"id\":\"31\"")));
    }

    // ---------------------------------------------------------------- auth + payload

    @Test
    void authHeaderIsBasicBase64OfEmailAndToken() {
        assertThat(client().authHeader()).isEqualTo(expectedBasicHeader());
    }

    @Test
    void authHeaderWithMissingSecretsFallsBackToColonOnly() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl("http://localhost:" + wm.port());
        JiraCloudClient c = new JiraCloudClient(p, key -> Optional.empty(), mapper, retries);
        // both secrets resolve to "" → "Basic " + base64(":")
        String expected = "Basic " + Base64.getEncoder().encodeToString(":".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(c.authHeader()).isEqualTo(expected);
    }

    @Test
    void buildCreatePayloadUsesAdfObjectDescriptionAndLabels() throws Exception {
        JiraCloudClient c = client();
        String payload = c.buildCreatePayload(new JiraCreateRequest(
                "CIAM", "Bug", "ciam-policies — POST /x — missing",
                List.of("*Actual:* 404", "*Expected:* 200"), List.of("contract-validation", "auto")));
        JsonNode node = mapper.readTree(payload);
        JsonNode fields = node.path("fields");
        assertThat(fields.path("project").path("key").asText()).isEqualTo("CIAM");
        assertThat(fields.path("issuetype").path("name").asText()).isEqualTo("Bug");
        assertThat(fields.path("summary").asText()).isEqualTo("ciam-policies — POST /x — missing");
        // v3 description is an ADF object (type=doc), NOT a plain wiki string
        JsonNode desc = fields.path("description");
        assertThat(desc.isObject()).isTrue();
        assertThat(desc.path("type").asText()).isEqualTo("doc");
        assertThat(desc.path("version").asInt()).isEqualTo(1);
        assertThat(desc.path("content")).hasSize(2);
        assertThat(desc.path("content").get(0).path("content").get(0).path("text").asText())
                .isEqualTo("*Actual:* 404");
        // labels present → labels array set
        assertThat(fields.path("labels").isArray()).isTrue();
        assertThat(fields.path("labels")).hasSize(2);
        assertThat(fields.path("labels").get(0).asText()).isEqualTo("contract-validation");
    }

    @Test
    void buildCreatePayloadOmitsLabelsWhenNullOrEmpty() throws Exception {
        JiraCloudClient c = client();
        // null labels branch
        String p1 = c.buildCreatePayload(new JiraCreateRequest(
                "CIAM", "Bug", "s", List.of("d"), null));
        assertThat(mapper.readTree(p1).path("fields").has("labels")).isFalse();
        // empty labels branch
        String p2 = c.buildCreatePayload(new JiraCreateRequest(
                "CIAM", "Bug", "s", List.of("d"), List.of()));
        assertThat(mapper.readTree(p2).path("fields").has("labels")).isFalse();
    }

    // ---------------------------------------------------------------- epic link (Cloud `parent` field)

    @Test
    void buildCreatePayloadSetsParentWhenEpicPresent() throws Exception {
        String payload = client().buildCreatePayload(new JiraCreateRequest(
                "CIAM", "Task", "s", List.of("d"), List.of("l"), "CIAM-100"));
        JsonNode fields = mapper.readTree(payload).path("fields");
        assertThat(fields.path("parent").path("key").asText()).isEqualTo("CIAM-100");
    }

    @Test
    void buildCreatePayloadOmitsParentWhenNoEpic_regression() throws Exception {
        JiraCloudClient c = client();
        String baseline = c.buildCreatePayload(new JiraCreateRequest("CIAM", "Task", "s", List.of("d"), List.of("l")));
        // A 6-arg request with a null epic → byte-for-byte identical to the old payload (no `parent`).
        assertThat(c.buildCreatePayload(new JiraCreateRequest("CIAM", "Task", "s", List.of("d"), List.of("l"), null)))
                .isEqualTo(baseline);
        assertThat(mapper.readTree(baseline).path("fields").has("parent")).isFalse();
    }

    // ---------------------------------------------------------------- listProjects (paged project/search)

    @Test
    void listProjectsPagesThroughProjectSearch() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/project/search")).withQueryParam("startAt", equalTo("0"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"isLast\":false,\"values\":[{\"key\":\"CIAM\",\"name\":\"Access\"}]}")));
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/project/search")).withQueryParam("startAt", equalTo("1"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"isLast\":true,\"values\":[{\"key\":\"APP\",\"name\":\"App\"}]}")));
        List<JiraProject> projects = client().listProjects();
        assertThat(projects).extracting(JiraProject::key).containsExactly("CIAM", "APP");
        assertThat(projects).extracting(JiraProject::name).containsExactly("Access", "App");
    }

    @Test
    void listProjectsWrapsErrorAsIllegalState() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/project/search")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().listProjects())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira listProjects failed");
    }

    // ---------------------------------------------------------------- createIssue

    @Test
    void createIssueHitsV3AndReturnsKey() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"key\":\"CIAM-7\"}")));
        String key = client().createIssue(new JiraCreateRequest(
                "CIAM", "Bug", "summary", List.of("desc"), List.of("contract-validation")));
        assertThat(key).isEqualTo("CIAM-7");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/3/issue"))
                .withHeader("Authorization", equalTo(expectedBasicHeader()))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(containing("\"type\":\"doc\"")));
    }

    @Test
    void createIssueReturnsEmptyStringWhenKeyAbsent() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/issue")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"10001\"}")));
        String key = client().createIssue(new JiraCreateRequest(
                "CIAM", "Bug", "summary", List.of("desc"), List.of()));
        assertThat(key).isEmpty();
    }

    @Test
    void createIssueWrapsErrorOn5xx() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/issue")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().createIssue(new JiraCreateRequest(
                "CIAM", "Bug", "summary", List.of("desc"), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira createIssue failed");
    }

    // ---------------------------------------------------------------- getStatus

    @Test
    void getStatusHitsV3AndParsesNameAndCategory() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/CIAM-1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"fields\":{\"status\":{\"name\":\"Done\",\"statusCategory\":{\"key\":\"done\"}}}}")));
        JiraStatus s = client().getStatus("CIAM-1");
        assertThat(s.name()).isEqualTo("Done");
        assertThat(s.categoryKey()).isEqualTo("done");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/rest/api/3/issue/CIAM-1"))
                .withQueryParam("fields", equalTo("status"))
                .withHeader("Authorization", equalTo(expectedBasicHeader())));
    }

    @Test
    void getStatusDefaultsToEmptyWhenFieldsMissing() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/CIAM-2")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{}")));
        JiraStatus s = client().getStatus("CIAM-2");
        assertThat(s.name()).isEmpty();
        assertThat(s.categoryKey()).isEmpty();
    }

    @Test
    void getStatusHandlesNullBodyResponse() {
        // 204-like empty body → resp == null → mapper reads "{}" → empty status
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/CIAM-3")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")));
        JiraStatus s = client().getStatus("CIAM-3");
        assertThat(s.name()).isEmpty();
        assertThat(s.categoryKey()).isEmpty();
    }

    @Test
    void getStatusWrapsErrorOn404() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/NOPE-9")).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> client().getStatus("NOPE-9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira getStatus failed for NOPE-9");
    }

    // ---------------------------------------------------------------- getIssue

    @Test
    void getIssueParsesKeySummaryAndAdfDescription() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/CIAM-10")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"CIAM-10\",\"fields\":{\"summary\":\"Login policy\","
                        + "\"description\":{\"type\":\"doc\",\"version\":1,\"content\":[]},"
                        + "\"labels\":[\"sec\",\"auth\"],"
                        + "\"priority\":{\"name\":\"High\"},"
                        + "\"status\":{\"statusCategory\":{\"key\":\"indeterminate\"}},"
                        + "\"components\":[{\"name\":\"gateway\"}],"
                        + "\"issuelinks\":[{\"outwardIssue\":{\"key\":\"CIAM-99\"}}]}}")));
        JiraIssue issue = client().getIssue("CIAM-10");
        assertThat(issue.key()).isEqualTo("CIAM-10");
        assertThat(issue.summary()).isEqualTo("Login policy");
        assertThat(issue.description()).isNotNull();
        assertThat(issue.description().path("type").asText()).isEqualTo("doc");
        assertThat(issue.lifecycle()).isEqualTo("IN_PROGRESS");
        assertThat(issue.priority()).isEqualTo("High");
        assertThat(issue.labels()).containsExactly("sec", "auth");
        assertThat(issue.components()).containsExactly("gateway");
        assertThat(issue.links()).containsExactly("CIAM-99");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/rest/api/3/issue/CIAM-10"))
                .withQueryParam("fields", equalTo("summary,description")));
    }

    @Test
    void getIssueLeavesDescriptionNullWhenAbsentOrJsonNull() {
        // description field entirely absent
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/CIAM-11")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"CIAM-11\",\"fields\":{\"summary\":\"No desc\"}}")));
        JiraIssue noDesc = client().getIssue("CIAM-11");
        assertThat(noDesc.description()).isNull();
        assertThat(noDesc.summary()).isEqualTo("No desc");
        assertThat(noDesc.lifecycle()).isNull();

        // description present but JSON null → also mapped to null
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/CIAM-12")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"CIAM-12\",\"fields\":{\"summary\":\"Null desc\",\"description\":null}}")));
        assertThat(client().getIssue("CIAM-12").description()).isNull();
    }

    @Test
    void getIssueDefaultsKeyAndSummaryWhenMissing() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/CIAM-13")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{}")));
        JiraIssue issue = client().getIssue("CIAM-13");
        assertThat(issue.key()).isEmpty();
        assertThat(issue.summary()).isEmpty();
        assertThat(issue.description()).isNull();
        assertThat(issue.labels()).isEmpty();
    }

    @Test
    void getIssueWrapsErrorOn500() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/issue/BOOM-1")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().getIssue("BOOM-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira getIssue failed for BOOM-1");
    }

    // ---------------------------------------------------------------- whoAmI

    @Test
    void whoAmIReturnsDisplayName() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"displayName\":\"Alice Tester\"}")));
        assertThat(client().whoAmI()).isEqualTo("Alice Tester");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/rest/api/3/myself"))
                .withHeader("Authorization", equalTo(expectedBasicHeader())));
    }

    @Test
    void whoAmIFallsBackToAuthenticatedWhenNoDisplayName() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"accountId\":\"abc\"}")));
        assertThat(client().whoAmI()).isEqualTo("authenticated");
    }

    @Test
    void whoAmIWrapsErrorOn401() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/myself")).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client().whoAmI())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira /myself failed");
    }

    // ---------------------------------------------------------------- listVersions

    @Test
    void listVersionsParsesProjectVersions() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/project/CIAM/versions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"id\":\"1\",\"name\":\"8.2\",\"released\":false,\"archived\":false},"
                        + "{\"id\":\"2\",\"name\":\"8.1\",\"released\":true,\"archived\":true}]")));
        List<JiraVersion> versions = client().listVersions("CIAM");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).id()).isEqualTo("1");
        assertThat(versions.get(0).name()).isEqualTo("8.2");
        assertThat(versions.get(0).released()).isFalse();
        assertThat(versions.get(0).archived()).isFalse();
        assertThat(versions.get(1).released()).isTrue();
        assertThat(versions.get(1).archived()).isTrue();
    }

    @Test
    void listVersionsHandlesEmptyAndNullBody() {
        // explicit empty array
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/project/EMPTY/versions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("[]")));
        assertThat(client().listVersions("EMPTY")).isEmpty();

        // null body → resp == null → mapper reads "[]" → empty list
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/project/NULLB/versions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")));
        assertThat(client().listVersions("NULLB")).isEmpty();
    }

    @Test
    void listVersionsDefaultsMissingFields() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/project/SPARSE/versions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("[{}]")));
        List<JiraVersion> versions = client().listVersions("SPARSE");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).id()).isEmpty();
        assertThat(versions.get(0).name()).isEmpty();
        assertThat(versions.get(0).released()).isFalse();
        assertThat(versions.get(0).archived()).isFalse();
    }

    @Test
    void listVersionsWrapsErrorOn500() {
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/project/BOOM/versions")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().listVersions("BOOM"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira listVersions failed for BOOM");
    }

    // ---------------------------------------------------------------- search

    @Test
    void searchHitsV3SinglePageAndParses() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\":1,\"issues\":[{\"key\":\"CIAM-9\","
                        + "\"fields\":{\"summary\":\"Release item\"}}]}")));
        List<JiraIssue> issues = client().search("fixVersion = \"1.0\"", List.of("summary"), 50);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).key()).isEqualTo("CIAM-9");
        assertThat(issues.get(0).summary()).isEqualTo("Release item");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/3/search"))
                .withHeader("Authorization", equalTo(expectedBasicHeader()))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(containing("\"startAt\":0"))
                .withRequestBody(containing("\"maxResults\":50")));
    }

    @Test
    void searchPagesUntilTotalReached() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).inScenario("paging")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"total\":3,\"issues\":[{\"key\":\"CIAM-1\",\"fields\":{\"summary\":\"a\"}},"
                                + "{\"key\":\"CIAM-2\",\"fields\":{\"summary\":\"b\"}}]}"))
                .willSetStateTo("page2"));
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).inScenario("paging")
                .whenScenarioStateIs("page2")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"total\":3,\"issues\":[{\"key\":\"CIAM-3\",\"fields\":{\"summary\":\"c\"}}]}")));
        List<JiraIssue> issues = client().search("fixVersion = \"1.0\"", List.of("summary"), 200);
        assertThat(issues).extracting(JiraIssue::key).containsExactly("CIAM-1", "CIAM-2", "CIAM-3");
        wm.verify(2, postRequestedFor(urlPathEqualTo("/rest/api/3/search")));
    }

    @Test
    void searchStopsWhenPageReturnsZeroIssues() {
        // total claims more than returned, but a page of 0 issues must break the loop (returned == 0 guard).
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\":99,\"issues\":[]}")));
        List<JiraIssue> issues = client().search("project = CIAM", List.of("summary"), 200);
        assertThat(issues).isEmpty();
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/3/search")));
    }

    @Test
    void searchCapsResultsAtMaxResultsAcrossPages() {
        // First page returns 2 issues with a high total; cap=3 must stop after one extra page yielding 1 more.
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).inScenario("cap")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"total\":100,\"issues\":[{\"key\":\"K-1\",\"fields\":{\"summary\":\"a\"}},"
                                + "{\"key\":\"K-2\",\"fields\":{\"summary\":\"b\"}}]}"))
                .willSetStateTo("p2"));
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).inScenario("cap")
                .whenScenarioStateIs("p2")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"total\":100,\"issues\":[{\"key\":\"K-3\",\"fields\":{\"summary\":\"c\"}},"
                                + "{\"key\":\"K-4\",\"fields\":{\"summary\":\"d\"}}]}")));
        List<JiraIssue> issues = client().search("project = CIAM", List.of("summary"), 3);
        // cap=3 → after 2 from page 1, requests pageSize 1; even though stub returns 2, loop exits once size>=cap
        assertThat(issues).extracting(JiraIssue::key).containsExactly("K-1", "K-2", "K-3", "K-4");
        // second request must ask for the remaining 1 (cap - already)
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/3/search"))
                .withRequestBody(containing("\"maxResults\":1").and(containing("\"startAt\":2"))));
    }

    @Test
    void searchWithUnlimitedMaxResultsUsesPageSizeAndStopsAtTotal() {
        // maxResults <= 0 → cap = MAX_VALUE; pageSize becomes PAGE_SIZE (100). One page satisfies total.
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\":1,\"issues\":[{\"key\":\"CIAM-50\",\"fields\":{\"summary\":\"x\"}}]}")));
        List<JiraIssue> issues = client().search("project = CIAM", List.of("summary"), 0);
        assertThat(issues).hasSize(1);
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/3/search"))
                .withRequestBody(containing("\"maxResults\":100")));
    }

    @Test
    void searchTotalDefaultsToSizeWhenAbsentSoLoopTerminates() {
        // No "total" → root.path("total").asInt(issues.size()) defaults to issues.size(); startAt >= total breaks.
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issues\":[{\"key\":\"CIAM-77\",\"fields\":{\"summary\":\"y\"}}]}")));
        List<JiraIssue> issues = client().search("project = CIAM", List.of("summary"), 200);
        assertThat(issues).extracting(JiraIssue::key).containsExactly("CIAM-77");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/3/search")));
    }

    @Test
    void searchHandlesNullBody() {
        // empty body → resp == null → mapper reads "{}" → no issues, returned==0 breaks immediately.
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")));
        List<JiraIssue> issues = client().search("project = CIAM", List.of("summary"), 50);
        assertThat(issues).isEmpty();
    }

    @Test
    void searchWrapsErrorOn500() {
        wm.stubFor(post(urlPathEqualTo("/rest/api/3/search")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client().search("project = CIAM", List.of("summary"), 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira search failed");
    }

    // ---------------------------------------------------------------- base() normalization

    @Test
    void baseUrlTrailingSlashIsTrimmed() {
        // baseUrl ends with '/'; the client must strip it so the path is not doubled.
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl("http://localhost:" + wm.port() + "/");
        JiraCloudClient c = new JiraCloudClient(p, secrets, mapper, retries);
        wm.stubFor(get(urlPathEqualTo("/rest/api/3/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"displayName\":\"Bob\"}")));
        assertThat(c.whoAmI()).isEqualTo("Bob");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/rest/api/3/myself")));
    }

    @Test
    void nullBaseUrlYieldsEmptyBaseAndFailsGracefully() {
        // baseUrl null → base() returns "" → URI "/rest/api/3/myself" is relative/invalid → wrapped error.
        ConnectionsProperties p = new ConnectionsProperties();
        p.getJira().setBaseUrl(null);
        JiraCloudClient c = new JiraCloudClient(p, secrets, mapper, retries);
        assertThatThrownBy(c::whoAmI)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira /myself failed");
    }
}
