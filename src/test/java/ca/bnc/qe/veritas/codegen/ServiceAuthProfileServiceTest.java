package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.Scope;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.ServiceAuthGroup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The per-service auth profile round-trips the token groups (Okta sources) by appId+slug — never the private key. */
@SpringBootTest
class ServiceAuthProfileServiceTest {

    @Autowired
    private ServiceAuthProfileService profiles;

    @Test
    void roundTripsTheTokenGroupsByAppAndRepo() {
        ServiceAuthSpec spec = new ServiceAuthSpec(List.of(
                new ServiceAuthGroup("tpps", "https://okta/tpps/v1/token", "0oaTPPS", "TPPS_PRIVATE_KEY",
                        "oktaCredentials.json", List.of(new Scope("READ", "tpps:read")), List.of("/tpps"))));

        profiles.save("APP7", "ciam-svc", spec);
        ServiceAuthSpec got = profiles.find("APP7", "ciam-svc");

        assertThat(got.groups()).hasSize(1);
        assertThat(got.groups().get(0).name()).isEqualTo("tpps");
        assertThat(got.groups().get(0).clientId()).isEqualTo("0oaTPPS");
        assertThat(got.groups().get(0).scopes()).hasSize(1);
        assertThat(got.groups().get(0).pathPrefixes()).containsExactly("/tpps");
    }

    @Test
    void unknownServiceAndMissingSlugReturnPublic() {
        assertThat(profiles.find("APP7", "never-declared").isEmpty()).isTrue();
        assertThat(profiles.find("APP7", null).isEmpty()).isTrue();   // local-path dev: no slug → no lookup
    }

    @Test
    void resaveUpdatesInPlace() {
        profiles.save("APP8", "svc", ServiceAuthSpec.none());
        profiles.save("APP8", "svc", new ServiceAuthSpec(List.of(
                new ServiceAuthGroup("apps", "u", "0oaY", "PK", "oktaCredentials.json",
                        List.of(new Scope("WRITE", "w")), List.of()))));

        ServiceAuthSpec got = profiles.find("APP8", "svc");
        assertThat(got.groups()).hasSize(1);
        assertThat(got.groups().get(0).name()).isEqualTo("apps");
    }
}
