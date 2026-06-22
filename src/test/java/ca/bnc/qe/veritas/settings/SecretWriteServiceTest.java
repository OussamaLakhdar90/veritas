package ca.bnc.qe.veritas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import ca.bnc.qe.veritas.secret.EncryptedFileSecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class SecretWriteServiceTest {

    private EncryptedFileSecretStore store(String passphrase, Path file) {
        EncryptedFileSecretStore s = new EncryptedFileSecretStore();
        ReflectionTestUtils.setField(s, "passphrase", passphrase);
        ReflectionTestUtils.setField(s, "filePath", file.toString());
        return s;
    }

    @Test
    void writesSecretAndReportsStatusWithoutExposingValues(@TempDir Path dir) {
        EncryptedFileSecretStore store = store("test-pass", dir.resolve("secrets.enc"));
        SecretWriteService svc = new SecretWriteService(store, store);   // store is its own SecretProvider

        assertThat(svc.status().get("JIRA_API_TOKEN")).isFalse();
        svc.set("JIRA_API_TOKEN", "pat-123");
        assertThat(svc.status().get("JIRA_API_TOKEN")).isTrue();
        assertThat(store.get("JIRA_API_TOKEN")).contains("pat-123");   // round-trips through the encrypted store
        assertThat(svc.status()).containsKeys("GIT_TOKEN", "XRAY_API_TOKEN");   // all known keys reported
    }

    @Test
    void rejectsUnknownKeyAndBlankValue(@TempDir Path dir) {
        SecretWriteService svc = new SecretWriteService(store("p", dir.resolve("s.enc")), store("p", dir.resolve("s.enc")));
        assertThatThrownBy(() -> svc.set("BOGUS_KEY", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.set("GIT_TOKEN", "  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledStoreSurfacesAsPrecondition(@TempDir Path dir) {
        EncryptedFileSecretStore disabled = store("", dir.resolve("s.enc"));   // no passphrase → disabled
        SecretWriteService svc = new SecretWriteService(disabled, disabled);
        assertThatThrownBy(() -> svc.set("GIT_TOKEN", "x")).isInstanceOf(PreconditionException.class);
    }
}
