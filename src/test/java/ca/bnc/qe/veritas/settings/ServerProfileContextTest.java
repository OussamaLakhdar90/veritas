package ca.bnc.qe.veritas.settings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** The server profile context boots with the auth filter + request-scoped principal, and drops the local one. */
@SpringBootTest
@ActiveProfiles("server")
@TestPropertySource(properties = {
        "veritas.server.api-token=test-token",
        "veritas.secret.passphrase=test-pass"
})
class ServerProfileContextTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void serverSecurityBeansWiredAndLocalPrincipalDropped() {
        assertThat(ctx.getBeanNamesForType(ServerApiAuthFilter.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(ServerCurrentUser.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(LocalCurrentUser.class)).isEmpty();   // @Profile("!server")
    }
}
