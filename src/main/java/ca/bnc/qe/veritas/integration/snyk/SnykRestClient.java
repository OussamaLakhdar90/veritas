package ca.bnc.qe.veritas.integration.snyk;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Snyk SaaS client. Reads use the versioned <b>REST</b> API (orgs / targets / projects); per-project vulnerability
 * detail uses the <b>v1</b> {@code aggregated-issues} endpoint (it carries {@code fixInfo.fixedIn} — the safe
 * version — in one call). Auth is the personal API token / PAT as {@code Authorization: token <key>}. Routed through
 * {@link CorpHttp} so it inherits the corporate-firewall PowerShell fallback like every other outbound call.
 */
@Component
@ConditionalOnProperty(name = "veritas.snyk.mock", havingValue = "false", matchIfMissing = true)
public class SnykRestClient implements SnykClient {

    private static final int PAGE_LIMIT = 100;

    private final ConnectionsProperties connections;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final CorpHttp corp;
    private final String apiVersion;

    public SnykRestClient(ConnectionsProperties connections, SecretProvider secrets, ObjectMapper mapper,
                          CorpHttp corp, @Value("${veritas.snyk.api-version:2024-10-15}") String apiVersion) {
        this.connections = connections;
        this.secrets = secrets;
        this.mapper = mapper;
        this.corp = corp;
        this.apiVersion = apiVersion;
    }

    @Override
    public String whoAmI() {
        try {
            String resp = corp.get(base() + "/rest/self?version=" + apiVersion, authHeaders());
            JsonNode attrs = mapper.readTree(resp == null ? "{}" : resp).path("data").path("attributes");
            String name = attrs.path("name").asText("");
            if (name.isBlank()) {
                name = attrs.path("username").asText("authenticated");
            }
            return name.isBlank() ? "authenticated" : name;
        } catch (Exception e) {
            throw new IllegalStateException("Snyk /rest/self failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SnykOrg> listOrgs() {
        List<SnykOrg> out = new ArrayList<>();
        for (JsonNode node : paginate("/rest/orgs?version=" + apiVersion + "&limit=" + PAGE_LIMIT, "Snyk listOrgs")) {
            JsonNode attrs = node.path("attributes");
            out.add(new SnykOrg(node.path("id").asText(""), attrs.path("slug").asText(""),
                    attrs.path("name").asText("")));
        }
        return out;
    }

    @Override
    public List<SnykTarget> listTargets(String orgId) {
        String path = "/rest/orgs/" + enc(orgId) + "/targets?version=" + apiVersion + "&limit=" + PAGE_LIMIT;
        List<SnykTarget> out = new ArrayList<>();
        for (JsonNode node : paginate(path, "Snyk listTargets")) {
            JsonNode attrs = node.path("attributes");
            String name = attrs.path("display_name").asText("");
            if (name.isBlank()) {
                name = attrs.path("url").asText(node.path("id").asText(""));
            }
            out.add(new SnykTarget(node.path("id").asText(""), name));
        }
        return out;
    }

    @Override
    public List<SnykProjectRef> listProjects(String orgId, String targetId) {
        String path = "/rest/orgs/" + enc(orgId) + "/projects?version=" + apiVersion + "&limit=" + PAGE_LIMIT
                + "&target_id=" + enc(targetId);
        List<SnykProjectRef> out = new ArrayList<>();
        for (JsonNode node : paginate(path, "Snyk listProjects")) {
            JsonNode attrs = node.path("attributes");
            out.add(new SnykProjectRef(node.path("id").asText(""), attrs.path("name").asText(""),
                    attrs.path("target_file").asText(""), attrs.path("target_reference").asText("")));
        }
        return out;
    }

    @Override
    public List<SnykIssue> aggregatedIssues(String orgId, String projectId) {
        try {
            String url = base() + "/v1/org/" + enc(orgId) + "/project/" + enc(projectId) + "/aggregated-issues";
            String body = mapper.writeValueAsString(Map.of(
                    "includeDescription", false,
                    "includeIntroducedThrough", false,
                    "filters", Map.of(
                            "severities", List.of("critical", "high", "medium", "low"),
                            "types", List.of("vuln"),
                            "ignored", false,
                            "patched", false)));
            String resp = corp.post(url, authHeaders(), body, "application/json");
            JsonNode issues = mapper.readTree(resp == null ? "{}" : resp).path("issues");
            List<SnykIssue> out = new ArrayList<>();
            for (JsonNode issue : issues) {
                out.add(toIssue(issue));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Snyk aggregatedIssues failed for project " + projectId
                    + ": " + e.getMessage(), e);
        }
    }

    private SnykIssue toIssue(JsonNode issue) {
        JsonNode data = issue.path("issueData");
        JsonNode fix = issue.path("fixInfo");
        List<String> fixedIn = new ArrayList<>();
        for (JsonNode v : fix.path("fixedIn")) {
            fixedIn.add(v.asText());
        }
        boolean fixable = fix.path("isFixable").asBoolean(false)
                || fix.path("isUpgradable").asBoolean(false) || !fixedIn.isEmpty();
        return new SnykIssue(
                data.path("id").asText(issue.path("id").asText("")),
                data.path("severity").asText(""),
                data.path("title").asText(""),
                issue.path("pkgName").asText(""),
                issue.path("pkgVersions").path(0).asText(""),
                data.path("identifiers").path("CVE").path(0).asText(""),
                data.path("identifiers").path("CWE").path(0).asText(""),
                data.path("cvssScore").asDouble(0.0),
                issue.path("priorityScore").asInt(0),
                fixable,
                fixedIn);
    }

    /** GET a paged REST collection, following {@code links.next} until exhausted; returns the flattened {@code data}. */
    private List<JsonNode> paginate(String startPath, String call) {
        try {
            List<JsonNode> out = new ArrayList<>();
            String path = startPath;
            int guard = 0;
            while (path != null && guard++ < 1000) {
                String resp = corp.get(base() + path, authHeaders());
                JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
                for (JsonNode node : root.path("data")) {
                    out.add(node);
                }
                String next = root.path("links").path("next").asText("");
                path = next.isBlank() ? null : (next.startsWith("http") ? stripBase(next) : next);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(call + " failed: " + e.getMessage(), e);
        }
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "token " + secrets.require("SNYK_API_TOKEN"), "Accept", "application/json");
    }

    private String base() {
        String b = connections.getSnyk().getBaseUrl();
        if (b == null || b.isBlank()) {
            return "https://api.snyk.io";
        }
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** A {@code links.next} may be absolute; reduce it to a base-relative path so {@link #base()} is authoritative. */
    private String stripBase(String url) {
        int slash = url.indexOf('/', url.indexOf("://") + 3);
        return slash < 0 ? url : url.substring(slash);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
