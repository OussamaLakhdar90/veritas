package ca.bnc.qe.veritas.integration.xray;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.secret.SecretProvider;
import org.junit.jupiter.api.Test;

/** Xray Server (Raven) client must honor the auth-type knob (audit gap: it was hardcoded Bearer). Default = BEARER. */
class XrayServerAuthTypeTest {

    private final SecretProvider secrets = key -> Optional.of(Map.of(
            "JIRA_USERNAME", "alice", "XRAY_API_TOKEN", "xray-pat", "JIRA_API_TOKEN", "jira-pat")
            .getOrDefault(key, ""));

    private XrayServerClient client(String authType) {
        ConnectionsProperties c = new ConnectionsProperties();
        c.getXray().setAuthType(authType);
        return new XrayServerClient(c, secrets, null, null);
    }

    @Test
    void bearerByDefaultBasicWhenConfigured() {
        assertThat(client("BEARER").authHeader()).isEqualTo("Bearer xray-pat");   // Xray token preferred over Jira
        assertThat(client(null).authHeader()).isEqualTo("Bearer xray-pat");       // default = Bearer
        assertThat(client("BASIC").authHeader()).startsWith("Basic ").isNotEqualTo("Basic ");   // base64(alice:xray-pat)
    }
}
