package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.Mechanism;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.ServiceAuthGroup;
import org.junit.jupiter.api.Test;

/** The deterministic SERVICE_AUTH_SPEC the codegen prompt consumes — group→WorldKey, $sensitive env refs, path map. */
class ServiceAuthSpecTest {

    @Test
    void twoGroupSpecRendersWorldKeysEnvRefsAndPathPrefixes() {
        ServiceAuthSpec spec = new ServiceAuthSpec(List.of(
                new ServiceAuthGroup("tpps", Mechanism.PRIVATE_KEY,
                        Map.of("privateKey", "CIAM_TPPS_PRIVATE_KEY"), List.of("/tpps"), "CIAM-100"),
                new ServiceAuthGroup("apps", Mechanism.BASIC_AUTH,
                        Map.of("basicAuth", "CIAM_APPS_BASIC_AUTH"), List.of("/apps"), null)));

        String block = spec.toPromptBlock();

        assertThat(spec.isEmpty()).isFalse();
        assertThat(block)
                .contains("WorldKey.TPPS_TOKEN").contains("WorldKey.APPS_TOKEN")
                .contains("$sensitive:CIAM_TPPS_PRIVATE_KEY").contains("$sensitive:CIAM_APPS_BASIC_AUTH")
                .contains("PRIVATE_KEY").contains("BASIC_AUTH")
                .contains("/tpps").contains("/apps")
                .contains("CIAM-100")
                // never leak a raw secret — only env-var NAMES as $sensitive refs
                .doesNotContain("password");
    }

    @Test
    void emptyPathPrefixesMeanAllEndpoints() {
        ServiceAuthSpec spec = new ServiceAuthSpec(List.of(
                new ServiceAuthGroup(null, Mechanism.PRIVATE_KEY,
                        Map.of("privateKey", "SVC_KEY"), List.of(), null)));

        String block = spec.toPromptBlock();

        assertThat(block).contains("WorldKey.PRIMARY_TOKEN").contains("[all endpoints]");
    }

    @Test
    void publicServiceDeclaresNoTokenAndNoServiceAuth() {
        assertThat(ServiceAuthSpec.none().isEmpty()).isTrue();
        assertThat(ServiceAuthSpec.none().toPromptBlock())
                .contains("PUBLIC").contains("no token")
                .doesNotContain("WorldKey").doesNotContain("service_auth.{group}");
    }
}
