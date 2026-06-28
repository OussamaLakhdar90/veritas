package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.Scope;
import org.junit.jupiter.api.Test;

/** The deterministic SERVICE_AUTH_SPEC the codegen prompt consumes — Okta private-key-JWT facts (lsist framework). */
class ServiceAuthSpecTest {

    @Test
    void authenticatedSpecRendersOktaFactsAndRobotToken() {
        ServiceAuthSpec spec = new ServiceAuthSpec(true,
                "https://okta.example/oauth2/auth-server/v1/token", "0oaABC123", "MY_API_PRIVATE_KEY",
                "oktaCredentials.json",
                List.of(new Scope("READ", "myapi:resource:read"), new Scope("WRITE", "myapi:resource:write")));

        String block = spec.toPromptBlock();

        assertThat(spec.isEmpty()).isFalse();
        assertThat(block)
                .contains("WorldKey.ROBOT_TOKEN").contains("RobotToken").contains("PRIVATE-KEY JWT")
                .contains("https://okta.example/oauth2/auth-server/v1/token").contains("0oaABC123")
                .contains("oktaCredentials.json").contains("MY_API_PRIVATE_KEY")
                .contains("READ").contains("myapi:resource:read").contains("WRITE")
                // never the old (wrong-framework) model
                .doesNotContain("service_auth").doesNotContain("client_secret").doesNotContain("pathPrefix");
    }

    @Test
    void defaultsCredentialsFileAndTodosMissingScopes() {
        ServiceAuthSpec spec = new ServiceAuthSpec(true, "u", "c", "K", null, List.of());
        assertThat(spec.credentialsFileOrDefault()).isEqualTo("oktaCredentials.json");
        assertThat(spec.toPromptBlock()).contains("oktaCredentials.json").contains("TODO-FILL");
    }

    @Test
    void publicServiceDeclaresNoToken() {
        assertThat(ServiceAuthSpec.none().isEmpty()).isTrue();
        assertThat(ServiceAuthSpec.none().toPromptBlock())
                .contains("PUBLIC").contains("no token")
                .doesNotContain("WorldKey").doesNotContain("RobotToken");
    }
}
