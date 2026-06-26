package ca.bnc.qe.veritas.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.bnc.qe.veritas.settings.CurrentUser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Branch-maximising coverage for {@link EncryptedFileSecretStore}: disabled/enabled gating, put/get
 * round-trips and overwrite, corrupt/empty/missing-file handling, blank-value filtering, the
 * {@code file()} path-selection branches (explicit override / default / per-principal), and the
 * {@code safePrincipal()} CurrentUser-resolution branches. New class — does not touch the existing
 * {@code EncryptedFileSecretStoreTest}.
 */
class EncryptedFileSecretStoreBranchTest {

    private EncryptedFileSecretStore store(String passphrase, Path file) {
        EncryptedFileSecretStore s = new EncryptedFileSecretStore();
        ReflectionTestUtils.setField(s, "passphrase", passphrase);
        ReflectionTestUtils.setField(s, "filePath", file == null ? "" : file.toString());
        return s;
    }

    // ---------- disabled() branches ----------

    @Test
    void getDisabledWhenPassphraseNull(@TempDir Path dir) {
        EncryptedFileSecretStore s = store(null, dir.resolve("secrets.enc"));
        assertThat(s.get("GIT_TOKEN")).isEmpty();
    }

    @Test
    void getDisabledWhenPassphraseBlank(@TempDir Path dir) {
        EncryptedFileSecretStore s = store("   ", dir.resolve("secrets.enc"));
        assertThat(s.get("GIT_TOKEN")).isEmpty();
    }

    @Test
    void putDisabledThrowsIllegalState(@TempDir Path dir) {
        EncryptedFileSecretStore s = store("", dir.resolve("secrets.enc"));
        assertThatThrownBy(() -> s.put("GIT_TOKEN", "v"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled")
                .hasMessageContaining("veritas.secret.passphrase");
    }

    // ---------- put/get happy paths + overwrite ----------

    @Test
    void putThenGetRoundTripsAcrossInstances(@TempDir Path dir) {
        Path file = dir.resolve("secrets.enc");
        store("correct-horse", file).put("GIT_TOKEN", "s3cr3t-value");

        // A fresh instance with the same passphrase decrypts the persisted value.
        assertThat(store("correct-horse", file).get("GIT_TOKEN")).contains("s3cr3t-value");
        // File was actually written.
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void putExistingFileLoadsAndPreservesOtherKeys(@TempDir Path dir) {
        Path file = dir.resolve("secrets.enc");
        EncryptedFileSecretStore s = store("pw", file);
        s.put("A", "first");
        // Second put: file already exists -> load() branch in put(), keeping "A".
        s.put("B", "second");

        EncryptedFileSecretStore reader = store("pw", file);
        assertThat(reader.get("A")).contains("first");
        assertThat(reader.get("B")).contains("second");
    }

    @Test
    void putOverwritesExistingKey(@TempDir Path dir) {
        Path file = dir.resolve("secrets.enc");
        EncryptedFileSecretStore s = store("pw", file);
        s.put("K", "old");
        s.put("K", "new");
        assertThat(store("pw", file).get("K")).contains("new");
    }

    // ---------- get() value-filter + not-found branches ----------

    @Test
    void getBlankStoredValueIsFiltered(@TempDir Path dir) {
        Path file = dir.resolve("secrets.enc");
        EncryptedFileSecretStore s = store("pw", file);
        s.put("BLANK", "   ");           // stored but blank -> filter(v -> !v.isBlank()) drops it
        s.put("REAL", "x");
        assertThat(store("pw", file).get("BLANK")).isEmpty();
        assertThat(store("pw", file).get("REAL")).contains("x");
    }

    @Test
    void getMissingKeyFromExistingFileIsEmpty(@TempDir Path dir) {
        Path file = dir.resolve("secrets.enc");
        store("pw", file).put("PRESENT", "y");
        assertThat(store("pw", file).get("ABSENT")).isEmpty();
    }

    @Test
    void getOnMissingFileIsEmpty(@TempDir Path dir) {
        // No file written -> load() hits the !Files.exists(f) branch -> empty map -> not found.
        Path file = dir.resolve("nope.enc");
        assertThat(store("pw", file).get("ANY")).isEmpty();
    }

    // ---------- get() exception branches (wrong passphrase / corrupt / empty) ----------

    @Test
    void getWrongPassphraseIsEmpty(@TempDir Path dir) {
        Path file = dir.resolve("secrets.enc");
        store("right", file).put("GIT_TOKEN", "v");
        // Wrong passphrase -> AEAD tag mismatch -> exception -> empty.
        assertThat(store("wrong", file).get("GIT_TOKEN")).isEmpty();
    }

    @Test
    void getCorruptFileIsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("secrets.enc");
        // Three valid base64 segments but garbage ciphertext -> decrypt/tag failure -> exception -> empty.
        Files.writeString(file, "AAAA.BBBB.CCCC");
        assertThat(store("pw", file).get("ANY")).isEmpty();
    }

    @Test
    void getEmptyFileIsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("secrets.enc");
        // Empty content -> split yields <3 parts -> ArrayIndexOutOfBounds -> caught -> empty.
        Files.writeString(file, "");
        assertThat(store("pw", file).get("ANY")).isEmpty();
    }

    @Test
    void getNonBase64FileIsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("secrets.enc");
        // Bad base64 in the salt segment -> IllegalArgumentException from decoder -> caught -> empty.
        Files.writeString(file, "!!!.@@@.###");
        assertThat(store("pw", file).get("ANY")).isEmpty();
    }

    // ---------- put() failure path wraps in IllegalState ----------

    @Test
    void putWrappingFailureThrowsIllegalState(@TempDir Path dir) throws Exception {
        // Make file() point at a path whose parent is an existing *file*, so createDirectories fails.
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "i am a file");
        Path target = blocker.resolve("child").resolve("secrets.enc");
        EncryptedFileSecretStore s = store("pw", target);
        assertThatThrownBy(() -> s.put("K", "V"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to store secret 'K'");
    }

    // ---------- save() creates parent directories ----------

    @Test
    void putCreatesMissingParentDirectories(@TempDir Path dir) {
        Path nested = dir.resolve("a").resolve("b").resolve("c").resolve("secrets.enc");
        store("pw", nested).put("K", "V");
        assertThat(Files.exists(nested)).isTrue();
        assertThat(store("pw", nested).get("K")).contains("V");
    }

    // ---------- file() path-selection branches ----------

    @Test
    void fileUsesExplicitOverrideWhenSet(@TempDir Path dir) {
        Path override = dir.resolve("explicit.enc");
        EncryptedFileSecretStore s = store("pw", override);
        Path resolved = (Path) ReflectionTestUtils.invokeMethod(s, "file");
        assertThat(resolved).isEqualTo(override);
    }

    @Test
    void fileFallsBackToHomeDefaultWhenNoOverride(@TempDir Path dir) {
        String prev = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        try {
            EncryptedFileSecretStore s = store("pw", null);      // blank filePath -> default branch
            ReflectionTestUtils.setField(s, "perPrincipal", false);
            Path resolved = (Path) ReflectionTestUtils.invokeMethod(s, "file");
            assertThat(resolved).isEqualTo(dir.resolve(".veritas").resolve("secrets.enc"));
        } finally {
            System.setProperty("user.home", prev);
        }
    }

    @Test
    void filePerPrincipalUsesPrincipalScopedPath(@TempDir Path dir) {
        String prev = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        try {
            EncryptedFileSecretStore s = store("pw", null);
            ReflectionTestUtils.setField(s, "perPrincipal", true);

            CurrentUser user = mock(CurrentUser.class);
            when(user.principalId()).thenReturn("alice@bnc.ca");
            @SuppressWarnings("unchecked")
            ObjectProvider<CurrentUser> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(user);
            ReflectionTestUtils.setField(s, "currentUser", provider);

            Path resolved = (Path) ReflectionTestUtils.invokeMethod(s, "file");
            // "alice@bnc.ca" sanitised: '@' is allowed-out so it becomes '_'.
            assertThat(resolved).isEqualTo(
                    dir.resolve(".veritas").resolve("secrets").resolve("alice_bnc.ca.enc"));
        } finally {
            System.setProperty("user.home", prev);
        }
    }

    // ---------- safePrincipal() branches ----------

    @Test
    void safePrincipalDefaultsToLocalWhenProviderNull() {
        EncryptedFileSecretStore s = store("pw", null);
        // currentUser left null (no @Autowired in a plain new instance).
        String id = (String) ReflectionTestUtils.invokeMethod(s, "safePrincipal");
        assertThat(id).isEqualTo("local");
    }

    @Test
    void safePrincipalDefaultsToLocalWhenProviderEmpty() {
        EncryptedFileSecretStore s = store("pw", null);
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentUser> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);   // u == null branch
        ReflectionTestUtils.setField(s, "currentUser", provider);
        String id = (String) ReflectionTestUtils.invokeMethod(s, "safePrincipal");
        assertThat(id).isEqualTo("local");
    }

    @Test
    void safePrincipalDefaultsToLocalWhenPrincipalIdNull() {
        EncryptedFileSecretStore s = store("pw", null);
        CurrentUser user = mock(CurrentUser.class);
        when(user.principalId()).thenReturn(null);          // principalId() == null branch
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentUser> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(user);
        ReflectionTestUtils.setField(s, "currentUser", provider);
        String id = (String) ReflectionTestUtils.invokeMethod(s, "safePrincipal");
        assertThat(id).isEqualTo("local");
    }

    @Test
    void safePrincipalDefaultsToLocalWhenPrincipalIdBlank() {
        EncryptedFileSecretStore s = store("pw", null);
        CurrentUser user = mock(CurrentUser.class);
        when(user.principalId()).thenReturn("  ");          // isBlank() branch
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentUser> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(user);
        ReflectionTestUtils.setField(s, "currentUser", provider);
        String id = (String) ReflectionTestUtils.invokeMethod(s, "safePrincipal");
        assertThat(id).isEqualTo("local");
    }

    @Test
    void safePrincipalSanitisesIllegalCharacters() {
        EncryptedFileSecretStore s = store("pw", null);
        CurrentUser user = mock(CurrentUser.class);
        when(user.principalId()).thenReturn("a/b c:d\\e");   // all illegal chars -> '_'
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentUser> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(user);
        ReflectionTestUtils.setField(s, "currentUser", provider);
        String id = (String) ReflectionTestUtils.invokeMethod(s, "safePrincipal");
        assertThat(id).isEqualTo("a_b_c_d_e");
    }

    @Test
    void safePrincipalKeepsAllowedCharacters() {
        EncryptedFileSecretStore s = store("pw", null);
        CurrentUser user = mock(CurrentUser.class);
        when(user.principalId()).thenReturn("User.Name-123_ok");   // all allowed -> unchanged
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentUser> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(user);
        ReflectionTestUtils.setField(s, "currentUser", provider);
        String id = (String) ReflectionTestUtils.invokeMethod(s, "safePrincipal");
        assertThat(id).isEqualTo("User.Name-123_ok");
    }

    @Test
    void safePrincipalDefaultsToLocalWhenProviderThrows() {
        EncryptedFileSecretStore s = store("pw", null);
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentUser> provider = mock(ObjectProvider.class);
        // Request-scoped principal resolved outside a request -> RuntimeException -> "local".
        when(provider.getIfAvailable()).thenThrow(new RuntimeException("outside request scope"));
        ReflectionTestUtils.setField(s, "currentUser", provider);
        String id = (String) ReflectionTestUtils.invokeMethod(s, "safePrincipal");
        assertThat(id).isEqualTo("local");
    }
}