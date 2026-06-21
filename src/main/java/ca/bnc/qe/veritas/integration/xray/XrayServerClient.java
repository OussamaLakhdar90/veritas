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
@ConditionalOnProperty(name = "veritas.connections.xray.edition", havingValue = "SERVER_DC", matchIfMissing = true)
public class XrayServerClient implements XrayClient {

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
            String uri = base() + "/rest/api/2/search?jql=" + URLEncoder.encode(testJql, StandardCharsets.UTF_8)
                    + "&maxResults=200&fields=summary,labels,status";
            String resp = corp.get(uri, authHeaders());
            JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
            List<XrayTest> tests = new ArrayList<>();
            for (JsonNode issue : root.path("issues")) {
                String key = issue.path("key").asText("");
                tests.add(new XrayTest(key, issue.path("id").asText(""),
                        issue.path("fields").path("summary").asText(""), "Manual", fetchSteps(key)));
            }
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
            String resp = corp.post(base() + "/rest/api/2/issue", authHeaders(), body, "application/json");
            String key = mapper.readTree(resp == null ? "{}" : resp).path("key").asText("");
            if (!key.isBlank() && spec.steps() != null && !spec.steps().isEmpty()) {
                updateTestSteps(key, spec.steps());
            }
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Xray (Raven) createTest failed: " + e.getMessage(), e);
        }
    }

    /** Additive: POST each step to the Raven step endpoint (mirrors the Cloud client's additive semantics). */
    @Override
    public void updateTestSteps(String testKey, List<XrayStep> steps) {
        if (steps == null) {
            return;
        }
        try {
            for (XrayStep s : steps) {
                String body = mapper.writeValueAsString(Map.of(
                        "step", s.action() == null ? "" : s.action(),
                        "data", s.data() == null ? "" : s.data(),
                        "result", s.result() == null ? "" : s.result()));
                corp.post(base() + "/rest/raven/1.0/api/test/" + testKey + "/step", authHeaders(), body, "application/json");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Xray (Raven) updateTestSteps failed for " + testKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void addTestsToTestPlan(String planKey, List<String> testKeys) {
        try {
            String body = mapper.writeValueAsString(Map.of("add", testKeys));
            corp.post(base() + "/rest/raven/1.0/api/testplan/" + planKey + "/test", authHeaders(), body, "application/json");
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
