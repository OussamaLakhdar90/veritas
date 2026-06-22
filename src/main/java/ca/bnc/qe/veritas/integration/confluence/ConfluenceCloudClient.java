package ca.bnc.qe.veritas.integration.confluence;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    public ConfluencePage getPage(String pageId) {
        try {
            String resp = retries.call(() -> http.get()
                    .uri(URI.create(base() + "/wiki/rest/api/content/" + pageId + "?expand=body.storage"))
                    .header("Authorization", authHeader())
                    .retrieve().body(String.class));
            JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
            return new ConfluencePage(
                    root.path("id").asText(pageId),
                    root.path("title").asText(""),
                    root.path("body").path("storage").path("value").asText(""));
        } catch (Exception e) {
            throw new IllegalStateException("Confluence getPage failed for " + pageId + ": " + e.getMessage(), e);
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
