package ca.bnc.qe.veritas.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecretProviderTest {

    @Test
    void envReadsSystemProperty() {
        System.setProperty("VERITAS_TEST_SECRET", "abc");
        try {
            assertThat(new EnvSecretProvider().get("VERITAS_TEST_SECRET")).contains("abc");
        } finally {
            System.clearProperty("VERITAS_TEST_SECRET");
        }
    }

    @Test
    void envMissingIsEmpty() {
        assertThat(new EnvSecretProvider().get("VERITAS_NO_SUCH_KEY_123")).isEmpty();
    }

    @Test
    void chainedReturnsFirstHit() {
        SecretProvider a = key -> key.equals("X") ? Optional.of("a") : Optional.empty();
        SecretProvider b = key -> Optional.of("b");
        ChainedSecretProvider chained = new ChainedSecretProvider(List.of(a, b));
        assertThat(chained.get("X")).contains("a");
        assertThat(chained.get("Y")).contains("b");
    }
}
