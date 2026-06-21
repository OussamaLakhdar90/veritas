package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BuildVerifierTest {

    private final BuildVerifier verifier = new BuildVerifier();

    @Test
    void skipsWhenNoCommand() {
        assertThat(verifier.verify(Path.of("."), "").status()).isEqualTo("SKIPPED");
        assertThat(verifier.verify(Path.of("."), null).status()).isEqualTo("SKIPPED");
    }

    @Test
    void failsOnUnrunnableCommand() {
        assertThat(verifier.verify(Path.of("."), "veritas-no-such-command-xyz123").status()).isEqualTo("FAIL");
    }
}
