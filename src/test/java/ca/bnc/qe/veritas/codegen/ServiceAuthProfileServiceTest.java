package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The per-service auth profile round-trips by appId+slug, stores the Okta spec (no private key), and upserts. */
@SpringBootTest
class ServiceAuthProfileServiceTest {

    @Autowired
    private ServiceAuthProfileService profiles;

    @Test
    void roundTripsTheOktaSpecByAppAndRepo() {
        ServiceAuthSpec spec = new ServiceAuthSpec(true,
                "https://okta.example/oauth2/auth/v1/token", "0oaX", "CIAM_PRIVATE_KEY", "oktaCredentials.json",
                List.of(new Scope("READ", "ciam:read"), new Scope("WRITE", "ciam:write")));

        profiles.save("APP7", "ciam-svc", spec);
        ServiceAuthSpec got = profiles.find("APP7", "ciam-svc");

        assertThat(got.authenticated()).isTrue();
        assertThat(got.clientId()).isEqualTo("0oaX");
        assertThat(got.privateKeyField()).isEqualTo("CIAM_PRIVATE_KEY");
        assertThat(got.scopes()).hasSize(2);
        assertThat(got.scopes().get(0).name()).isEqualTo("READ");
    }

    @Test
    void unknownServiceAndMissingSlugReturnPublic() {
        assertThat(profiles.find("APP7", "never-declared").isEmpty()).isTrue();
        assertThat(profiles.find("APP7", null).isEmpty()).isTrue();   // local-path dev: no slug → no lookup
    }

    @Test
    void resaveUpdatesInPlace() {
        profiles.save("APP8", "svc", ServiceAuthSpec.none());
        profiles.save("APP8", "svc", new ServiceAuthSpec(true, "u", "0oaY", "PK", "oktaCredentials.json",
                List.of(new Scope("WRITE", "w"))));

        ServiceAuthSpec got = profiles.find("APP8", "svc");
        assertThat(got.authenticated()).isTrue();
        assertThat(got.clientId()).isEqualTo("0oaY");
    }
}
