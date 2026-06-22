package ca.bnc.qe.veritas.integration.jira;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.HttpFactory;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Jira Cloud REST v3 client (descriptions are ADF). Basic auth = email + API token from the per-user
 * {@link SecretProvider}. Active when {@code veritas.connections.jira.edition=CLOUD}; the default is the
 * Server/DC v2 client ({@link JiraServerClient}), which matches the BNC contract-validator app.
 */
@Component
@ConditionalOnProperty(name = "veritas.connections.jira.edition", havingValue = "CLOUD")
public class JiraCloudClient implements JiraClient {

    private final ConnectionsProperties connections;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.builder().requestFactory(HttpFactory.bounded()).build();
    private final Retries retries;

    /** Per-request page size for paged JQL fetches. */
    private static final int PAGE_SIZE = 100;

    public JiraCloudClient(ConnectionsProperties connections, SecretProvider secrets, ObjectMapper mapper, Retries retries) {
        this.connections = connections;
        this.secrets = secrets;
        this.mapper = mapper;
        this.retries = retries;
    }

    @Override
    public List<JiraIssue> search(String jql, List<String> fields, int maxResults) {
        try {
            // B3 — page through startAt/maxResults so large releases aren't truncated at the first page.
            int cap = maxResults <= 0 ? Integer.MAX_VALUE : maxResults;
            List<JiraIssue> issues = new ArrayList<>();
            int startAt = 0;
            while (issues.size() < cap) {
                int pageSize = Math.min(cap - issues.size(), PAGE_SIZE);
                int from = startAt;
                String body = mapper.writeValueAsString(Map.of(
                        "jql", jql, "fields", fields, "startAt", from, "maxResults", pageSize));
                String resp = retries.call(() -> http.post().uri(URI.create(base() + "/rest/api/3/search"))
                        .header("Authorization", authHeader())
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve().body(String.class));
                JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
                JsonNode page = root.path("issues");
                int returned = page.size();
                for (JsonNode issue : page) {
                    issues.add(toIssue(issue));
                }
                int total = root.path("total").asInt(issues.size());
                startAt += returned;
                if (returned == 0 || startAt >= total) {
                    break;
                }
            }
            return issues;
        } catch (Exception e) {
            throw new IllegalStateException("Jira search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public JiraIssue getIssue(String key) {
        try {
            String resp = retries.call(() -> http.get()
                    .uri(URI.create(base() + "/rest/api/3/issue/" + key + "?fields=summary,description"))
                    .header("Authorization", authHeader())
                    .retrieve().body(String.class));
            return toIssue(mapper.readTree(resp == null ? "{}" : resp));
        } catch (Exception e) {
            throw new IllegalStateException("Jira getIssue failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String createIssue(JiraCreateRequest request) {
        try {
            String payload = buildCreatePayload(request);
            String resp = retries.call(() -> http.post().uri(URI.create(base() + "/rest/api/3/issue"))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve().body(String.class));
            return mapper.readTree(resp == null ? "{}" : resp).path("key").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("Jira createIssue failed: " + e.getMessage(), e);
        }
    }

    @Override
    public JiraStatus getStatus(String key) {
        try {
            String resp = retries.call(() -> http.get()
                    .uri(URI.create(base() + "/rest/api/3/issue/" + key + "?fields=status"))
                    .header("Authorization", authHeader())
                    .retrieve().body(String.class));
            JsonNode status = mapper.readTree(resp == null ? "{}" : resp).path("fields").path("status");
            return new JiraStatus(
                    status.path("name").asText(""),
                    status.path("statusCategory").path("key").asText(""));
        } catch (Exception e) {
            throw new IllegalStateException("Jira getStatus failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<JiraVersion> listVersions(String projectKey) {
        try {
            String resp = retries.call(() -> http.get()
                    .uri(URI.create(base() + "/rest/api/3/project/" + projectKey + "/versions"))
                    .header("Authorization", authHeader())
                    .retrieve().body(String.class));
            JsonNode root = mapper.readTree(resp == null ? "[]" : resp);
            List<JiraVersion> out = new ArrayList<>();
            for (JsonNode v : root) {
                out.add(new JiraVersion(v.path("id").asText(""), v.path("name").asText(""),
                        v.path("released").asBoolean(false), v.path("archived").asBoolean(false)));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Jira listVersions failed for " + projectKey + ": " + e.getMessage(), e);
        }
    }

    public String buildCreatePayload(JiraCreateRequest request) throws Exception {
        ObjectNode fields = mapper.createObjectNode();
        fields.set("project", mapper.createObjectNode().put("key", request.projectKey()));
        fields.set("issuetype", mapper.createObjectNode().put("name", request.issueType()));
        fields.put("summary", request.summary());
        fields.set("description", AdfBuilder.doc(request.descriptionParagraphs()));
        if (request.labels() != null && !request.labels().isEmpty()) {
            ArrayNode labels = fields.putArray("labels");
            request.labels().forEach(labels::add);
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.set("fields", fields);
        return mapper.writeValueAsString(payload);
    }

    private JiraIssue toIssue(JsonNode issue) {
        JsonNode fields = issue.path("fields");
        JsonNode description = fields.get("description");
        return new JiraIssue(issue.path("key").asText(""), fields.path("summary").asText(""),
                description != null && !description.isNull() ? description : null);
    }

    String authHeader() {
        String basic = secret("JIRA_USERNAME") + ":" + secret("JIRA_API_TOKEN");
        return "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
    }

    private String base() {
        String b = connections.getJira().getBaseUrl();
        if (b == null) {
            return "";
        }
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private String secret(String key) {
        return secrets.get(key).orElse("");
    }
}
