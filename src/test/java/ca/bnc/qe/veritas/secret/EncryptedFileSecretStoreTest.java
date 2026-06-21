package ca.bnc.qe.veritas.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EncryptedFileSecretStoreTest {

    private EncryptedFileSecretStore store(String passphrase, Path file) {
        EncryptedFileSecretStore s = new EncryptedFileSecretStore();
        ReflectionTestUtils.setField(s, "passphrase", passphrase);
        ReflectionTestUtils.setField(s, "filePath", file.toString());
        return s;
    }

    @Test
    void roundTripsAndRejectsWrongPassphrase() throws Exception {
        Path file = Files.createTempDirectory("veritas-sec-").resolve("secrets.enc");

        store("correct-horse", file).put("GIT_TOKEN", "s3cr3t-value");

        assertThat(store("correct-horse", file).get("GIT_TOKEN")).contains("s3cr3t-value");
        assertThat(store("wrong-passphrase", file).get("GIT_TOKEN")).isEmpty();
        assertThat(store("correct-horse", file).get("MISSING")).isEmpty();
    }

    @Test
    void disabledWithoutPassphrase() {
        EncryptedFileSecretStore s = new EncryptedFileSecretStore();
        ReflectionTestUtils.setField(s, "passphrase", "");
        assertThat(s.get("GIT_TOKEN")).isEmpty();
    }
}
