package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.Scope;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.ServiceAuthGroup;
import org.junit.jupiter.api.Test;

/** SERVICE_AUTH_SPEC — N token groups, each its own Okta token source (WorldKey.{NAME}_TOKEN + path mapping). */
class ServiceAuthSpecTest {

    @Test
    void twoGroupsRenderPerGroupWorldKeysOktaFactsScopesAndPaths() {
        ServiceAuthSpec spec = new ServiceAuthSpec(List.of(
                new ServiceAuthGroup("tpps", "https://okta/tpps/v1/token", "0oaTPPS", "TPPS_PRIVATE_KEY",
                        "oktaCredentials.json", List.of(new Scope("READ", "tpps:read")), List.of("/tpps")),
                new ServiceAuthGroup("apps", "https://okta/apps/v1/token", "0oaAPPS", "APPS_PRIVATE_KEY",
                        "oktaCredentials.json", List.of(new Scope("WRITE", "apps:write")), List.of("/apps"))));

        String block = spec.toPromptBlock();

        assertThat(spec.isEmpty()).isFalse();
        assertThat(block)
                .contains("2 token group").contains("RobotToken").contains("PRIVATE-KEY JWT")
                .contains("WorldKey.TPPS_TOKEN").contains("WorldKey.APPS_TOKEN")
                .contains("TppsTokenHelper").contains("AppsTokenHelper")
                .contains("0oaTPPS").contains("0oaAPPS").contains("TPPS_PRIVATE_KEY")
                .contains("tpps:read").contains("apps:write").contains("/tpps").contains("/apps")
                .doesNotContain("service_auth").doesNotContain("client_secret");
    }

    @Test
    void emptyPathPrefixesMeanAllEndpoints() {
        ServiceAuthSpec spec = new ServiceAuthSpec(List.of(
                new ServiceAuthGroup(null, "u", "0oaX", "PK", null, List.of(), List.of())));
        String block = spec.toPromptBlock();
        assertThat(block).contains("WorldKey.ROBOT_TOKEN").contains("[all endpoints]").contains("oktaCredentials.json");
    }

    @Test
    void publicServiceDeclaresNoToken() {
        assertThat(ServiceAuthSpec.none().isEmpty()).isTrue();
        assertThat(ServiceAuthSpec.none().toPromptBlock())
                .contains("PUBLIC").contains("no token")
                .doesNotContain("WorldKey").doesNotContain("RobotToken");
    }
}
