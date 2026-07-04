package ca.bnc.qe.veritas.integration.jira;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.integration.HttpFactory;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Jira <b>Server/Data-Center</b> REST v2 client — descriptions are <b>wiki markup</b> (plain string), auth is
 * a <b>Bearer PAT</b> (or Basic if configured). This matches the BNC contract-validator app, which talks to
 * {@code /rest/api/2} with a token (see docs/reference-contract-validator.md). Active when
 * {@code veritas.connections.jira.edition=SERVER_DC} (the default); the Cloud v3/ADF client is used for CLOUD.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "veritas.connections.jira.edition", havingValue = "SERVER_DC", matchIfMissing = true)
public class JiraServerClient implements JiraClient {

    private final ConnectionsProperties connections;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.builder().requestFactory(HttpFactory.bounded()).build();
    private final Retries retries;
    private final CorpHttp corp;

    /** Per-request page size for paged JQL fetches (Jira Server caps a page at its configured maximum). */
    private static final int PAGE_SIZE = 100;

    public JiraServerClient(ConnectionsProperties connections, SecretProvider secrets, ObjectMapper mapper,
                            Retries retries, CorpHttp corp) {
        this.connections = connections;
        this.secrets = secrets;
        this.mapper = mapper;
        this.retries = retries;
        this.corp = corp;
    }

    @Override
    public String whoAmI() {
        try {
            String resp = corp.get(base() + "/rest/api/2/myself", authHeaders());
            return mapper.readTree(resp == null ? "{}" : resp).path("displayName").asText("authenticated");
        } catch (Exception e) {
            throw new IllegalStateException("Jira /myself failed: " + e.getMessage(), e);
        }
    }

    private java.util.Map<String, String> authHeaders() {
        return java.util.Map.of("Authorization", authHeader(), "Accept", "application/json");
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
                String body = mapper.writeValueAsString(Map.of(
                        "jql", jql, "fields", fields, "startAt", startAt, "maxResults", pageSize));
                String resp = corp.post(base() + "/rest/api/2/search", authHeaders(), body, "application/json");
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
            String resp = corp.get(base() + "/rest/api/2/issue/" + JiraKeys.issueKey(key) + "?fields=summary,description", authHeaders());
            return toIssue(mapper.readTree(resp == null ? "{}" : resp));
        } catch (Exception e) {
            throw new IllegalStateException("Jira getIssue failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String createIssue(JiraCreateRequest request) {
        try {
            // Link to an epic only when asked. On Server/DC the link is the classic "Epic Link" custom field, whose
            // key varies per instance — discover it via create-meta. If it can't be found, still create the issue (a
            // tracking ticket with no epic beats no ticket) and warn, rather than failing the whole fix.
            String epicField = null;
            if (request.parentEpicKey() != null && !request.parentEpicKey().isBlank()) {
                epicField = createMeta(request.projectKey(), request.issueType()).epicLinkFieldKey();
                if (epicField == null) {
                    log.warn("Jira create: no Epic Link field on the {}/{} create screen — creating '{}' without "
                            + "linking it to epic {}.", request.projectKey(), request.issueType(),
                            request.summary(), request.parentEpicKey());
                }
            }
            String payload = buildCreatePayload(request, epicField);
            String resp = corp.postWrite(base() + "/rest/api/2/issue", authHeaders(), payload, "application/json");
            return mapper.readTree(resp == null ? "{}" : resp).path("key").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("Jira createIssue failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<JiraTransition> listTransitions(String key) {
        try {
            String resp = corp.get(base() + "/rest/api/2/issue/" + JiraKeys.issueKey(key) + "/transitions", authHeaders());
            JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
            List<JiraTransition> out = new ArrayList<>();
            for (JsonNode t : root.path("transitions")) {
                out.add(new JiraTransition(t.path("id").asText(""), t.path("name").asText(""),
                        t.path("to").path("name").asText("")));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Jira listTransitions failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void transition(String key, String transitionId) {
        try {
            String body = mapper.writeValueAsString(Map.of("transition", Map.of("id", transitionId)));
            corp.postWrite(base() + "/rest/api/2/issue/" + JiraKeys.issueKey(key) + "/transitions", authHeaders(), body, "application/json");
        } catch (Exception e) {
            throw new IllegalStateException("Jira transition failed for " + key + " (id " + transitionId + "): "
                    + e.getMessage(), e);
        }
    }

    @Override
    public JiraStatus getStatus(String key) {
        try {
            String resp = corp.get(base() + "/rest/api/2/issue/" + JiraKeys.issueKey(key) + "?fields=status", authHeaders());
            JsonNode status = mapper.readTree(resp == null ? "{}" : resp).path("fields").path("status");
            return new JiraStatus(
                    status.path("name").asText(""),
                    status.path("statusCategory").path("key").asText(""));
        } catch (Exception e) {
            throw new IllegalStateException("Jira getStatus failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void addComment(String key, String wikiBody) {
        try {
            String body = mapper.writeValueAsString(Map.of("body", wikiBody == null ? "" : wikiBody));
            corp.postWrite(base() + "/rest/api/2/issue/" + JiraKeys.issueKey(key) + "/comment", authHeaders(), body, "application/json");
        } catch (Exception e) {
            throw new IllegalStateException("Jira addComment failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void attachFile(String key, String fileName, String content) {
        try {
            String boundary = "----veritasBoundary7MA4YWxkTrZu0gW";
            // The attachment is text (a corrected YAML), so the multipart body is a String — route it through
            // CorpHttp so attachments inherit the same PowerShell firewall fallback as every other Server/DC call
            // (the direct RestClient path bypassed it). postWrite = connect-only retry (non-idempotent write).
            String body = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n"
                    + (content == null ? "" : content)
                    + "\r\n--" + boundary + "--\r\n";
            corp.postWrite(base() + "/rest/api/2/issue/" + JiraKeys.issueKey(key) + "/attachments",
                    java.util.Map.of("Authorization", authHeader(), "X-Atlassian-Token", "no-check"),
                    body, "multipart/form-data; boundary=" + boundary);
        } catch (Exception e) {
            throw new IllegalStateException("Jira attachFile failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<JiraVersion> listVersions(String projectKey) {
        try {
            String resp = corp.get(base() + "/rest/api/2/project/"
                    + URLEncoder.encode(projectKey, StandardCharsets.UTF_8) + "/versions", authHeaders());
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

    @Override
    public List<JiraProject> listProjects() {
        try {
            String resp = corp.get(base() + "/rest/api/2/project", authHeaders());
            JsonNode root = mapper.readTree(resp == null ? "[]" : resp);
            List<JiraProject> out = new ArrayList<>();
            for (JsonNode p : root) {
                out.add(new JiraProject(p.path("key").asText(""), p.path("name").asText("")));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Jira listProjects failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CreateMeta createMeta(String projectKey, String issueType) {
        try {
            String uri = base() + "/rest/api/2/issue/createmeta?projectKeys="
                    + URLEncoder.encode(projectKey, StandardCharsets.UTF_8)
                    + "&issuetypeNames=" + URLEncoder.encode(issueType, StandardCharsets.UTF_8)
                    + "&expand=projects.issuetypes.fields";
            String resp = corp.get(uri, authHeaders());
            JsonNode fields = mapper.readTree(resp == null ? "{}" : resp)
                    .path("projects").path(0).path("issuetypes").path(0).path("fields");
            List<String> allowed = new ArrayList<>();
            String epicLink = null;
            String team = null;
            var it = fields.fields();
            while (it.hasNext()) {
                var entry = it.next();
                String fieldKey = entry.getKey();
                allowed.add(fieldKey);
                String name = entry.getValue().path("name").asText("");
                if (epicLink == null && name.matches("(?i).*epic\\s*link.*")) {
                    epicLink = fieldKey;
                }
                if (team == null && name.equalsIgnoreCase("team")) {
                    team = fieldKey;
                }
            }
            return new CreateMeta(allowed, epicLink, team);
        } catch (Exception e) {
            throw new IllegalStateException("Jira createMeta failed for " + projectKey + ": " + e.getMessage(), e);
        }
    }

    /** v2 description is wiki markup (a plain string), not ADF — paragraphs are joined with blank lines. */
    public String buildCreatePayload(JiraCreateRequest request) throws Exception {
        return buildCreatePayload(request, null);
    }

    /**
     * @param epicLinkFieldKey the discovered "Epic Link" custom-field key (e.g. {@code customfield_10001}); when
     *        non-null AND the request carries a {@code parentEpicKey}, the epic KEY is written to that field to file
     *        the new issue under the epic. Null (the default) → no epic link, byte-for-byte the previous payload.
     */
    public String buildCreatePayload(JiraCreateRequest request, String epicLinkFieldKey) throws Exception {
        ObjectNode fields = mapper.createObjectNode();
        fields.set("project", mapper.createObjectNode().put("key", request.projectKey()));
        fields.set("issuetype", mapper.createObjectNode().put("name", request.issueType()));
        fields.put("summary", request.summary());
        List<String> paras = request.descriptionParagraphs() == null ? List.of() : request.descriptionParagraphs();
        fields.put("description", String.join("\n\n", paras));
        if (request.labels() != null && !request.labels().isEmpty()) {
            ArrayNode labels = fields.putArray("labels");
            request.labels().forEach(labels::add);
        }
        if (epicLinkFieldKey != null && !epicLinkFieldKey.isBlank()
                && request.parentEpicKey() != null && !request.parentEpicKey().isBlank()) {
            // Classic "Epic Link" takes the epic's KEY as a string (validated so it can't inject a foreign field).
            fields.put(epicLinkFieldKey, JiraKeys.issueKey(request.parentEpicKey()));
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.set("fields", fields);
        return mapper.writeValueAsString(payload);
    }

    private JiraIssue toIssue(JsonNode issue) {
        JsonNode fields = issue.path("fields");
        JsonNode description = fields.get("description");   // wiki string on v2 (TextNode), or null
        return new JiraIssue(issue.path("key").asText(""), fields.path("summary").asText(""),
                description != null && !description.isNull() ? description : null,
                JiraFieldParser.lifecycle(fields), JiraFieldParser.priority(fields),
                JiraFieldParser.labels(fields), JiraFieldParser.components(fields), JiraFieldParser.links(fields));
    }

    String authHeader() {
        String type = connections.getJira().getAuthType();
        if (type != null && type.equalsIgnoreCase("BASIC")) {
            String basic = secret("JIRA_USERNAME") + ":" + secret("JIRA_API_TOKEN");
            return "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + secret("JIRA_API_TOKEN");
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
