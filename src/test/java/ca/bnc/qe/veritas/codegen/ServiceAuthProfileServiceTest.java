package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.Mechanism;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.ServiceAuthGroup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The per-service auth profile round-trips by appId+slug, stores names only, and upserts in place. */
@SpringBootTest
class ServiceAuthProfileServiceTest {

    @Autowired
    private ServiceAuthProfileService profiles;

    @Test
    void roundTripsTheDeclaredGroupsByAppAndRepo() {
        ServiceAuthSpec spec = new ServiceAuthSpec(List.of(
                new ServiceAuthGroup("tpps", Mechanism.PRIVATE_KEY,
                        Map.of("privateKey", "CIAM_TPPS_PRIVATE_KEY"), List.of("/tpps"), null)));

        profiles.save("APP7", "ciam-svc", spec);
        ServiceAuthSpec got = profiles.find("APP7", "ciam-svc");

        assertThat(got.groups()).hasSize(1);
        assertThat(got.groups().get(0).name()).isEqualTo("tpps");
        assertThat(got.groups().get(0).mechanism()).isEqualTo(Mechanism.PRIVATE_KEY);
        assertThat(got.groups().get(0).envVars()).containsEntry("privateKey", "CIAM_TPPS_PRIVATE_KEY");
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
                new ServiceAuthGroup("apps", Mechanism.BASIC_AUTH, Map.of("basicAuth", "X"), List.of(), null))));

        ServiceAuthSpec got = profiles.find("APP8", "svc");
        assertThat(got.groups()).hasSize(1);
        assertThat(got.groups().get(0).name()).isEqualTo("apps");
    }
}
