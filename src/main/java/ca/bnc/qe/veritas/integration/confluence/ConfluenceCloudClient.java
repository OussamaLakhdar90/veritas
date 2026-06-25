package ca.bnc.qe.veritas.integration.confluence;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.HttpFactory;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Confluence Cloud client: fetch a page's storage-format XHTML (Basic auth = email + API token). */
@Component
public class ConfluenceCloudClient implements ConfluenceClient {

    private final ConnectionsProperties connections;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.builder().requestFactory(HttpFactory.bounded()).build();
    private final Retries retries;

    public ConfluenceCloudClient(ConnectionsProperties connections, SecretProvider secrets, ObjectMapper mapper, Retries retries) {
        this.connections = connections;
        this.secrets = secrets;
        this.mapper = mapper;
        this.retries = retries;
    }

    @Override
    public ConfluencePage getPage(String pageRef) {
        String id = pageId(pageRef);   // accept a full page URL or a bare id
        try {
            String resp = retries.call(() -> http.get()
                    .uri(URI.create(apiBase() + "/content/" + id + "?expand=body.storage"))
                    .header("Authorization", authHeader())
                    .retrieve().body(String.class));
            JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
            return new ConfluencePage(
                    root.path("id").asText(id),
                    root.path("title").asText(""),
                    root.path("body").path("storage").path("value").asText(""));
        } catch (Exception e) {
            throw new IllegalStateException("Confluence getPage failed for " + pageRef + ": " + e.getMessage(), e);
        }
    }

    /** REST root: Confluence Cloud serves under /wiki; Server/DC serves at the host root. */
    private String apiBase() {
        String prefix = "SERVER_DC".equalsIgnoreCase(connections.getConfluence().getEdition()) ? "" : "/wiki";
        return base() + prefix + "/rest/api";
    }

    /** Extract the numeric page id from a Confluence URL (…/pages/<id>/…), or return a bare id as-is. */
    static String pageId(String ref) {
        if (ref == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/pages/(\\d+)").matcher(ref);
        return m.find() ? m.group(1) : ref.trim();
    }

    @Override
    public List<ConfluencePage> getPagesBySpace(String spaceKey) {
        List<ConfluencePage> pages = new ArrayList<>();
        String uri = apiBase() + "/content?spaceKey="
                + URLEncoder.encode(spaceKey, StandardCharsets.UTF_8) + "&type=page&limit=100";
        try {
            while (uri != null) {
                final String pageUri = uri;
                String resp = retries.call(() -> http.get().uri(URI.create(pageUri))
                        .header("Authorization", authHeader())
                        .retrieve().body(String.class));
                JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
                for (JsonNode v : root.path("results")) {
                    pages.add(new ConfluencePage(v.path("id").asText(""), v.path("title").asText(""), ""));
                }
                String next = root.path("_links").path("next").asText(null);   // Cloud: relative next-page link
                uri = (next == null || next.isBlank()) ? null : base() + next;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Confluence getPagesBySpace failed for '" + spaceKey + "': " + e.getMessage(), e);
        }
        return pages;
    }

    @Override
    public List<ConfluencePage> descendants(String rootPageRef, int maxPages) {
        String rootId = pageId(rootPageRef);   // accept a full URL or a bare id
        List<ConfluencePage> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(rootId);
        try {
            // Breadth-first over the page tree (the v1 child/page endpoint works on both Server and Cloud via apiBase),
            // bounded by maxPages and a visited-set cycle guard so a deep or self-referential tree can't run away.
            while (!queue.isEmpty() && out.size() < maxPages) {
                String id = queue.poll();
                if (!seen.add(id)) {
                    continue;
                }
                out.add(new ConfluencePage(id, "", ""));   // id only — the adapter fetches each page's body later
                String uri = apiBase() + "/content/" + id + "/child/page?limit=100";
                while (uri != null && out.size() + queue.size() < maxPages) {
                    final String pageUri = uri;
                    String resp = retries.call(() -> http.get().uri(URI.create(pageUri))
                            .header("Authorization", authHeader())
                            .retrieve().body(String.class));
                    JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
                    for (JsonNode v : root.path("results")) {
                        String childId = v.path("id").asText("");
                        if (!childId.isEmpty() && !seen.contains(childId)) {
                            queue.add(childId);
                        }
                    }
                    String next = root.path("_links").path("next").asText(null);
                    uri = (next == null || next.isBlank()) ? null : base() + next;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Confluence descendants failed for '" + rootPageRef + "': " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public String whoAmI() {
        try {
            String resp = retries.call(() -> http.get()
                    .uri(URI.create(apiBase() + "/user/current"))
                    .header("Authorization", authHeader())
                    .retrieve().body(String.class));
            return mapper.readTree(resp == null ? "{}" : resp).path("displayName").asText("authenticated");
        } catch (Exception e) {
            throw new IllegalStateException("Confluence current-user failed: " + e.getMessage(), e);
        }
    }

    String authHeader() {
        // Honor the configured auth-type (was hardcoded Basic). BNC + the application.yml default is BEARER (PAT),
        // matching the Jira client; classic Confluence Cloud (email + API token) still works via auth-type: BASIC.
        String type = connections.getConfluence().getAuthType();
        if (type != null && type.equalsIgnoreCase("BASIC")) {
            String basic = secret("JIRA_USERNAME") + ":" + secret("CONFLUENCE_API_TOKEN");
            return "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + secret("CONFLUENCE_API_TOKEN");
    }

    private String base() {
        String b = connections.getConfluence().getBaseUrl();
        if (b == null) {
            return "";
        }
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private String secret(String key) {
        return secrets.get(key).orElse("");
    }
}
