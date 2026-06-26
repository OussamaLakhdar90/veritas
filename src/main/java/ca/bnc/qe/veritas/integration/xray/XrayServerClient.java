package ca.bnc.qe.veritas.integration.xray;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Xray <b>Server/Data-Center</b> client over "Raven" REST, mirroring the BNC contract-validator app:
 * <ul>
 *   <li>read tests: Jira REST {@code GET /rest/api/2/search?jql=… AND issuetype = Test} + per-test steps via
 *       {@code GET /rest/raven/1.0/api/test/{key}/steps};</li>
 *   <li>create test: Jira {@code POST /rest/api/2/issue} (issuetype Test) then push steps;</li>
 *   <li>update steps: additive {@code POST /rest/raven/1.0/api/test/{key}/step}.</li>
 * </ul>
 * Bearer auth (Xray token, falling back to the Jira token). Active when
 * {@code veritas.connections.xray.edition=SERVER_DC} (the default). Raven lives on the Jira host, so the base
 * URL is the configured Jira base. Step bodies are HTML-stripped (Xray stores rich text).
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "veritas.connections.xray.edition", havingValue = "SERVER_DC", matchIfMissing = true)
public class XrayServerClient implements XrayClient {

    /** Jira REST page size for the test search. */
    private static final int PAGE_SIZE = 200;
    /** Hard cap so a wrong/missing {@code total} can never spin forever; 50 × 200 = 10k tests covers any real project. */
    private static final int MAX_PAGES = 50;

    private final ConnectionsProperties connections;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final CorpHttp corp;

    public XrayServerClient(ConnectionsProperties connections, SecretProvider secrets, ObjectMapper mapper, CorpHttp corp) {
        this.connections = connections;
        this.secrets = secrets;
        this.mapper = mapper;
        this.corp = corp;
    }

    @Override
    public List<XrayTest> getTestsByJql(String jql) {
        try {
            String testJql = jql == null || jql.isBlank() ? "issuetype = Test" : jql;
            String encoded = URLEncoder.encode(testJql, StandardCharsets.UTF_8);
            List<XrayTest> tests = new ArrayList<>();
            // Page through the Jira search (startAt/total) instead of capping at one 200-row page. A project with
            // >200 tests was silently truncated → false coverage gaps, missed orphans, and duplicate test creation.
            int startAt = 0;
            int total = 0;
            int page = 0;
            do {
                String uri = base() + "/rest/api/2/search?jql=" + encoded
                        + "&startAt=" + startAt + "&maxResults=" + PAGE_SIZE + "&fields=summary,labels,status";
                String resp = corp.get(uri, authHeaders());
                JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
                JsonNode issues = root.path("issues");
                for (JsonNode issue : issues) {
                    String key = issue.path("key").asText("");
                    tests.add(new XrayTest(key, issue.path("id").asText(""),
                            issue.path("fields").path("summary").asText(""), "Manual", fetchSteps(key)));
                }
                total = root.path("total").asInt(0);
                int fetched = issues.size();   // advance by what the server actually returned (it may cap maxResults)
                startAt += fetched;
                if (fetched == 0) {
                    break;
                }
                if (++page >= MAX_PAGES) {
                    log.warn("Xray getTestsByJql hit the {}-page cap ({} tests) for jql '{}'; results truncated at {}.",
                            MAX_PAGES, tests.size(), testJql, total);
                    break;
                }
            } while (startAt < total);
            return tests;
        } catch (Exception e) {
            throw new IllegalStateException("Xray (Raven) getTestsByJql failed: " + e.getMessage(), e);
        }
    }

    /** Per-test manual steps via the Raven endpoint; best-effort (a test may have none). */
    private List<XrayStep> fetchSteps(String testKey) {
        List<XrayStep> steps = new ArrayList<>();
        try {
            String resp = corp.get(base() + "/rest/raven/1.0/api/test/" + testKey + "/steps", authHeaders());
            JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
            JsonNode arr = root.isArray() ? root : root.path("steps");
            for (JsonNode s : arr) {
                steps.add(new XrayStep(
                        stripHtml(s.path("step").asText(s.path("action").asText(""))),
                        stripHtml(s.path("data").asText("")),
                        stripHtml(s.path("result").asText(s.path("expectedResult").asText("")))));
            }
        } catch (Exception ignored) {
            // steps fetch failed — return what we have
        }
        return steps;
    }

    @Override
    public String createTest(XrayTestSpec spec) {
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("project", Map.of("key", spec.projectKey()));
            fields.put("issuetype", Map.of("name", "Test"));
            fields.put("summary", spec.summary());
            String body = mapper.writeValueAsString(Map.of("fields", fields));
            String resp = corp.postWrite(base() + "/rest/api/2/issue", authHeaders(), body, "application/json");
            String key = mapper.readTree(resp == null ? "{}" : resp).path("key").asText("");
            if (!key.isBlank() && spec.steps() != null && !spec.steps().isEmpty()) {
                updateTestSteps(key, spec.steps());
            }
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Xray (Raven) createTest failed: " + e.getMessage(), e);
        }
    }

    /**
     * Set the test's steps to exactly {@code steps} — REPLACE, not append. Delete the existing Raven steps first,
     * then add the new ones. The old additive behaviour stacked the corrected steps on top of the originals when a
     * reviewer applied a review, corrupting the test (duplicated steps). On a new test (createTest) there are no
     * existing steps, so this degrades to add-only.
     */
    @Override
    public void updateTestSteps(String testKey, List<XrayStep> steps) {
        if (steps == null) {
            return;
        }
        try {
            for (String stepId : fetchStepIds(testKey)) {
                corp.delete(base() + "/rest/raven/1.0/api/test/" + testKey + "/step/" + stepId, authHeaders());
            }
            for (XrayStep s : steps) {
                String body = mapper.writeValueAsString(Map.of(
                        "step", s.action() == null ? "" : s.action(),
                        "data", s.data() == null ? "" : s.data(),
                        "result", s.result() == null ? "" : s.result()));
                corp.postWrite(base() + "/rest/raven/1.0/api/test/" + testKey + "/step", authHeaders(), body, "application/json");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Xray (Raven) updateTestSteps failed for " + testKey + ": " + e.getMessage(), e);
        }
    }

    /** The ids of a test's existing Raven steps (best-effort: a missing/empty steps list yields none → add-only). */
    private List<String> fetchStepIds(String testKey) {
        List<String> ids = new ArrayList<>();
        try {
            String resp = corp.get(base() + "/rest/raven/1.0/api/test/" + testKey + "/steps", authHeaders());
            JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
            JsonNode arr = root.isArray() ? root : root.path("steps");
            for (JsonNode s : arr) {
                String id = s.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        } catch (Exception ignored) {
            // can't list existing steps (no steps / endpoint error) → add-only, never throw here
        }
        return ids;
    }

    /**
     * Requirement coverage on Server/DC = a Jira "Tests" issue link: the Test {@code tests} the requirement.
     * (outwardIssue = test "tests" inwardIssue = requirement "is tested by"). Non-Xray-specific — uses the Jira
     * REST on the same host.
     */
    @Override
    public void linkTestToRequirement(String testKey, String requirementKey) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "type", Map.of("name", "Tests"),
                    "outwardIssue", Map.of("key", testKey),
                    "inwardIssue", Map.of("key", requirementKey)));
            corp.postWrite(base() + "/rest/api/2/issueLink", authHeaders(), body, "application/json");
        } catch (Exception e) {
            throw new IllegalStateException("Xray (Raven) linkTestToRequirement failed for " + testKey
                    + " -> " + requirementKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void addTestsToTestPlan(String planKey, List<String> testKeys) {
        try {
            String body = mapper.writeValueAsString(Map.of("add", testKeys));
            corp.postWrite(base() + "/rest/raven/1.0/api/testplan/" + planKey + "/test", authHeaders(), body, "application/json");
        } catch (Exception e) {
            throw new IllegalStateException("Xray (Raven) addTestsToTestPlan failed for " + planKey + ": " + e.getMessage(), e);
        }
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", authHeader(), "Accept", "application/json");
    }

    String authHeader() {
        // Honor the configured auth-type (was hardcoded Bearer). BNC + the application.yml default is BEARER (PAT);
        // a Basic-auth Raven host works via auth-type: BASIC.
        String type = connections.getXray().getAuthType();
        if (type != null && type.equalsIgnoreCase("BASIC")) {
            String basic = secrets.get("JIRA_USERNAME").orElse("") + ":" + token();
            return "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(basic.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return "Bearer " + token();
    }

    private String token() {
        return secrets.get("XRAY_API_TOKEN").filter(t -> !t.isBlank())
                .or(() -> secrets.get("JIRA_API_TOKEN")).orElse("");
    }

    /** Raven runs on the Jira host. */
    private String base() {
        String b = connections.getJira().getBaseUrl();
        if (b == null) {
            return "";
        }
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    static String stripHtml(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").trim();
    }
}
