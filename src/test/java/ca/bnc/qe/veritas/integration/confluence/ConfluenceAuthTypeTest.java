package ca.bnc.qe.veritas.integration.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.secret.SecretProvider;
import org.junit.jupiter.api.Test;

/** Confluence client must honor the auth-type knob (audit gap: it was hardcoded Basic). Default = BEARER (PAT). */
class ConfluenceAuthTypeTest {

    private final SecretProvider secrets = key -> Optional.of(Map.of(
            "JIRA_USERNAME", "alice", "CONFLUENCE_API_TOKEN", "conf-pat").getOrDefault(key, ""));

    private ConfluenceCloudClient client(String authType) {
        ConnectionsProperties c = new ConnectionsProperties();
        c.getConfluence().setAuthType(authType);
        return new ConfluenceCloudClient(c, secrets, null, null);
    }

    @Test
    void bearerByDefaultBasicWhenConfigured() {
        assertThat(client("BEARER").authHeader()).isEqualTo("Bearer conf-pat");
        assertThat(client(null).authHeader()).isEqualTo("Bearer conf-pat");          // default = Bearer
        assertThat(client("BASIC").authHeader()).startsWith("Basic ").isNotEqualTo("Basic ");   // base64(alice:conf-pat)
    }
}
