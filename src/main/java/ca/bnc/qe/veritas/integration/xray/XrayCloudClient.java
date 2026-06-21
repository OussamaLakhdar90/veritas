package ca.bnc.qe.veritas.integration.xray;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Xray Cloud GraphQL client: authenticate (client_id/secret → bearer), query tests, create tests, update
 * steps, attach to a test plan. Active when {@code veritas.connections.xray.edition=CLOUD}; the default is the
 * Server/DC "Raven" REST client ({@link XrayServerClient}), which matches the BNC contract-validator app.
 */
@Component
@ConditionalOnProperty(name = "veritas.connections.xray.edition", havingValue = "CLOUD")
public class XrayCloudClient implements XrayClient {

    private final ConnectionsProperties connections;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.builder().build();
    private final Retries retries;
    private volatile String token;

    public XrayCloudClient(ConnectionsProperties connections, SecretProvider secrets, ObjectMapper mapper, Retries retries) {
        this.connections = connections;
        this.secrets = secrets;
        this.mapper = mapper;
        this.retries = retries;
    }

    public synchronized String authenticate() {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "client_id", secret("XRAY_CLIENT_ID"), "client_secret", secret("XRAY_CLIENT_SECRET")));
            String resp = retries.call(() -> http.post().uri(URI.create(base() + "/api/v2/authenticate"))
                    .header("Content-Type", "application/json").body(body)
                    .retrieve().body(String.class));
            token = resp == null ? "" : resp.trim().replaceAll("^\"|\"$", "");
            return token;
        } catch (Exception e) {
            throw new IllegalStateException("Xray authenticate failed: " + e.getMessage(), e);
        }
    }

    public List<XrayTest> getTestsByJql(String jql) {
        JsonNode data = graphql(buildGetTestsQuery(jql));
        List<XrayTest> tests = new ArrayList<>();
        for (JsonNode r : data.path("getTests").path("results")) {
            List<XrayStep> steps = new ArrayList<>();
            for (JsonNode s : r.path("steps")) {
                steps.add(new XrayStep(s.path("action").asText(""), s.path("data").asText(""), s.path("result").asText("")));
            }
            tests.add(new XrayTest(r.path("jira").path("key").asText(""), r.path("issueId").asText(""),
                    r.path("jira").path("summary").asText(""), r.path("testType").path("name").asText(""), steps));
        }
        return tests;
    }

    public List<XrayStep> getTestSteps(String testKey) {
        List<XrayTest> t = getTestsByJql("key = " + testKey);
        return t.isEmpty() ? List.of() : t.get(0).steps();
    }

    public String createTest(XrayTestSpec spec) {
        JsonNode data = graphql(buildCreateTestMutation(spec));
        return data.path("createTest").path("test").path("jira").path("key").asText("");
    }

    /** Additive step update (adds the provided steps to the test); full replace is a live-validated enhancement. */
    public void updateTestSteps(String testKey, List<XrayStep> steps) {
        String issueId = resolveIssueId(testKey);
        for (XrayStep s : steps) {
            graphql("mutation { addTestStep(issueId: \"" + issueId + "\", step: { action: \""
                    + esc(s.action()) + "\", data: \"" + esc(s.data()) + "\", result: \"" + esc(s.result())
                    + "\" }) { id } }");
        }
    }

    @Override
    public void addTestsToTestPlan(String planKey, List<String> testKeys) {
        String planId = resolveIssueId(planKey);
        List<String> ids = new ArrayList<>();
        for (String key : testKeys) {
            String id = resolveIssueId(key);
            if (!id.isBlank()) {
                ids.add("\"" + id + "\"");
            }
        }
        graphql("mutation { addTestsToTestPlan(issueId: \"" + planId + "\", testIssueIds: ["
                + String.join(",", ids) + "]) { warning } }");
    }

    // ---- testable builders ----

    public String buildGetTestsQuery(String jql) {
        return "{ getTests(jql: \"" + esc(jql) + "\", limit: 100) { results { issueId "
                + "jira(fields: [\"key\", \"summary\"]) testType { name } steps { action data result } } } }";
    }

    public String buildCreateTestMutation(XrayTestSpec spec) {
        return "mutation { createTest(testType: {name: \"" + esc(spec.testType()) + "\"}, steps: ["
                + stepsGql(spec.steps()) + "], jira: { fields: { summary: \"" + esc(spec.summary())
                + "\", project: {key: \"" + esc(spec.projectKey()) + "\"} } }) { test { jira(fields: [\"key\"]) } warnings } }";
    }

    private String stepsGql(List<XrayStep> steps) {
        if (steps == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (XrayStep s : steps) {
            parts.add("{ action: \"" + esc(s.action()) + "\", data: \"" + esc(s.data())
                    + "\", result: \"" + esc(s.result()) + "\" }");
        }
        return String.join(", ", parts);
    }

    private JsonNode graphql(String query) {
        ensureToken();
        try {
            final String reqBody = mapper.writeValueAsString(Map.of("query", query));
            String resp = retries.call(() -> http.post().uri(URI.create(base() + "/api/v2/graphql"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(reqBody)
                    .retrieve().body(String.class));
            return mapper.readTree(resp == null ? "{}" : resp).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("Xray GraphQL call failed: " + e.getMessage(), e);
        }
    }

    private void ensureToken() {
        if (token == null || token.isBlank()) {
            authenticate();
        }
    }

    private String resolveIssueId(String key) {
        List<XrayTest> t = getTestsByJql("key = " + key);
        return t.isEmpty() ? "" : t.get(0).issueId();
    }

    private String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private String base() {
        String b = connections.getXray().getBaseUrl();
        if (b == null || b.isBlank()) {
            b = "https://xray.cloud.getxray.app";
        }
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private String secret(String key) {
        return secrets.get(key).orElse("");
    }
}
