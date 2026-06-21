package ca.bnc.qe.veritas.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class LogMaskerTest {

    @Test
    void masksKnownSecretsAndAuthHeaders() {
        String masked = LogMasker.mask(
                "calling with token=s3cr3t and Authorization: Bearer abc.def.ghi and Basic dXNlcjpwYXNz",
                Set.of("s3cr3t"));

        assertThat(masked).doesNotContain("s3cr3t");
        assertThat(masked).doesNotContain("abc.def.ghi");
        assertThat(masked).doesNotContain("dXNlcjpwYXNz");
        assertThat(masked).contains("Bearer ***");
        assertThat(masked).contains("Basic ***");
    }
}
