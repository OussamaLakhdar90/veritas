package ca.bnc.qe.veritas.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable endpoints for every integration (base URL + edition + workspace + auth type). No hostnames
 * are hardcoded; tokens are NOT here (they live in the per-user {@code SecretProvider}). See docs/configuration.md.
 */
@Component
@ConfigurationProperties(prefix = "veritas.connections")
@Getter
@Setter
public class ConnectionsProperties {

    private Endpoint bitbucket = new Endpoint();
    private Endpoint jira = new Endpoint();
    private Endpoint confluence = new Endpoint();
    private Endpoint xray = new Endpoint();

    @Getter
    @Setter
    public static class Endpoint {
        private String baseUrl;
        private String edition = "SERVER_DC";    // SERVER_DC (default, matches the active beans) | CLOUD
        private String workspace;            // Bitbucket Cloud: repos live under a workspace
        private String authType;             // APP_PASSWORD | OAUTH | BEARER | BASIC | CLIENT_CREDENTIALS
    }
}
