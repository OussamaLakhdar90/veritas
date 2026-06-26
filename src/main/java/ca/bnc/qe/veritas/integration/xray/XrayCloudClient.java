package ca.bnc.qe.veritas.integration.xray;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.HttpFactory;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Xray Cloud GraphQL client: authenticate (client_id/secret → bearer), query tests, create tests, update
 * steps, attach to a test plan. Active when {@code veritas.connections.xray.edition=CLOUD}; the default is the
 * Server/DC "Raven" REST client ({@link XrayServerClient}), which matches the BNC contract-validator app.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "veritas.connections.xray.edition", havingValue = "CLOUD")
public class XrayCloudClient implements XrayClient {

    /** Xray Cloud GraphQL page size for getTests (the API itself caps a single page at 100). */
    private static final int PAGE_SIZE = 100;
    /** Hard cap so a wrong/missing {@code total} can never spin forever; 100 × 100 = 10k tests covers any real project. */
    private static final int MAX_PAGES = 100;

    private final ConnectionsProperties connections;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.builder().requestFactory(HttpFactory.bounded()).build();
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
        List<XrayTest> tests = new ArrayList<>();
        // Page through getTests (start/total) instead of capping at one 100-row page. A project with >100 tests
        // was silently truncated → false coverage gaps, missed orphans, and duplicate test creation on outward writes.
        int start = 0;
        int total = 0;
        int page = 0;
        do {
            JsonNode getTests = graphql(buildGetTestsQuery(jql, start, PAGE_SIZE)).path("getTests");
            JsonNode results = getTests.path("results");
            for (JsonNode r : results) {
                List<XrayStep> steps = new ArrayList<>();
                for (JsonNode s : r.path("steps")) {
                    steps.add(new XrayStep(s.path("action").asText(""), s.path("data").asText(""), s.path("result").asText("")));
                }
                tests.add(new XrayTest(r.path("jira").path("key").asText(""), r.path("issueId").asText(""),
                        r.path("jira").path("summary").asText(""), r.path("testType").path("name").asText(""), steps));
            }
            total = getTests.path("total").asInt(0);
            int fetched = results.size();
            start += fetched;
            if (fetched == 0) {
                break;
            }
            if (++page >= MAX_PAGES) {
                log.warn("Xray getTestsByJql hit the {}-page cap ({} tests) for jql '{}'; results truncated at {}.",
                        MAX_PAGES, tests.size(), jql, total);
                break;
            }
        } while (start < total);
        return tests;
    }

    public String createTest(XrayTestSpec spec) {
        JsonNode data = graphqlWrite(buildCreateTestMutation(spec));
        return data.path("createTest").path("test").path("jira").path("key").asText("");
    }

    /**
     * Set the test's steps to exactly {@code steps} — REPLACE, not append: remove the existing steps first, then add
     * the new ones. The old additive behaviour stacked corrected steps on top of the originals on a review-apply,
     * corrupting the test. A new test has no steps, so this degrades to add-only.
     */
    public void updateTestSteps(String testKey, List<XrayStep> steps) {
        String issueId = resolveIssueId(testKey);
        for (String stepId : fetchStepIds(testKey)) {
            graphqlWrite("mutation { removeTestStep(stepId: \"" + esc(stepId) + "\") }");
        }
        for (XrayStep s : steps) {
            graphqlWrite("mutation { addTestStep(issueId: \"" + issueId + "\", step: { action: \""
                    + esc(s.action()) + "\", data: \"" + esc(s.data()) + "\", result: \"" + esc(s.result())
                    + "\" }) { id } }");
        }
    }

    /** The ids of a test's existing steps (best-effort: none → add-only). */
    private List<String> fetchStepIds(String testKey) {
        List<String> ids = new ArrayList<>();
        JsonNode results = graphql("{ getTests(jql: \"key = " + esc(testKey)
                + "\", start: 0, limit: 1) { results { steps { id } } } }").path("getTests").path("results");
        for (JsonNode r : results) {
            for (JsonNode s : r.path("steps")) {
                String id = s.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
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
        graphqlWrite("mutation { addTestsToTestPlan(issueId: \"" + planId + "\", testIssueIds: ["
                + String.join(",", ids) + "]) { warning } }");
    }

    @Override
    public void linkTestToRequirement(String testKey, String requirementKey) {
        // Xray Cloud derives requirement coverage from a Jira "Tests" issue link, which the GraphQL API does not
        // expose. BNC runs Server/DC (where this IS implemented); fail clearly rather than pretend it linked.
        throw new UnsupportedOperationException("linkTestToRequirement is not supported by the Xray Cloud GraphQL "
                + "client — create a Jira 'Tests' issue link (" + testKey + " -> " + requirementKey + ") instead.");
    }

    // ---- testable builders ----

    public String buildGetTestsQuery(String jql) {
        return buildGetTestsQuery(jql, 0, PAGE_SIZE);
    }

    /** Paged variant: requests {@code [start, start+limit)} and selects {@code total} so the caller can page. */
    public String buildGetTestsQuery(String jql, int start, int limit) {
        return "{ getTests(jql: \"" + esc(jql) + "\", start: " + start + ", limit: " + limit + ") { total results { issueId "
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

    /** Idempotent GraphQL query (getTests) — safe to retry on any transient failure. */
    private JsonNode graphql(String query) {
        return graphql(query, false);
    }

    /**
     * Non-idempotent GraphQL mutation (createTest/addTestStep/addTestsToTestPlan) — retried only on a connection
     * failure, never replayed on a 5xx/read-timeout, so a mutation can't create a duplicate Xray test/step.
     */
    private JsonNode graphqlWrite(String query) {
        return graphql(query, true);
    }

    private JsonNode graphql(String query, boolean write) {
        ensureToken();
        try {
            final String reqBody = mapper.writeValueAsString(Map.of("query", query));
            java.util.function.Supplier<String> op = () -> http.post().uri(URI.create(base() + "/api/v2/graphql"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(reqBody)
                    .retrieve().body(String.class);
            String resp = write ? retries.callWrite(op) : retries.call(op);
            JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
            // GraphQL returns HTTP 200 with an errors[] array on failure; surface it instead of reading a blank
            // data node (which silently poisoned coverage/dedup — a "no tests" reply that was really an auth/query error).
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                List<String> msgs = new ArrayList<>();
                errors.forEach(e -> msgs.add(e.path("message").asText(e.toString())));
                throw new IllegalStateException("GraphQL error: " + String.join("; ", msgs));
            }
            return root.path("data");
        } catch (IllegalStateException e) {
            throw e;
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
