package ca.bnc.qe.veritas.secret;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AES-GCM encrypted-file secret store (key derived from a passphrase via PBKDF2). Sits ahead of env in the
 * {@link ChainedSecretProvider} chain. Disabled (returns empty) until {@code veritas.secret.passphrase} is
 * set. The encrypted file holds the per-user tokens; the OS keychain provider slots in later with higher
 * precedence.
 */
@Component
@Order(100)
@Slf4j
public class EncryptedFileSecretStore implements SecretProvider {

    private static final int ITERATIONS = 65_536;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    @Value("${veritas.secret.passphrase:}")
    private String passphrase;

    @Value("${veritas.secret.file:}")
    private String filePath;

    /** When true (server/multi-user), each principal gets its own encrypted file so sessions can't read each other. */
    @Value("${veritas.secret.per-principal:false}")
    private boolean perPrincipal;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** Lazy so the singleton store works with a request-scoped CurrentUser on the server profile. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<ca.bnc.qe.veritas.settings.CurrentUser> currentUser;

    @Override
    public Optional<String> get(String key) {
        if (disabled()) {
            return Optional.empty();
        }
        Path f = file();
        if (!Files.exists(f)) {
            return Optional.empty();   // genuine "never set" for this principal — silent, not an error
        }
        try {
            return Optional.ofNullable(load().get(key)).filter(v -> !v.isBlank());
        } catch (Exception e) {
            // File EXISTS but couldn't be read (wrong passphrase / AEADBadTag / corrupt/truncated). This is NOT
            // "never set" — flag it so a silently-unreadable store is diagnosable. Never log the secret value; the
            // exception message is masked in case it echoes ciphertext-derived content.
            log.warn("Encrypted secret store present but unreadable at {} (principal '{}') — treating '{}' as not set: {}",
                    f, safePrincipal(), key,
                    LogMasker.mask(e.getMessage(), SecretRegistry.snapshot()));
            return Optional.empty();
        }
    }

    public void put(String key, String value) {
        if (disabled()) {
            throw new IllegalStateException("Encrypted secret store is disabled — set veritas.secret.passphrase.");
        }
        try {
            Path f = file();
            Map<String, String> secrets = Files.exists(f) ? load() : new LinkedHashMap<>();
            secrets.put(key, value);
            save(secrets);
            // Key name + resolved path/principal only — never the value (which SecretRegistry masks in logs anyway).
            log.debug("Stored secret '{}' for principal '{}' at {}", key, safePrincipal(), f);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store secret '" + key + "': " + e.getMessage(), e);
        }
    }

    private boolean disabled() {
        return passphrase == null || passphrase.isBlank();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> load() throws Exception {
        Path f = file();
        if (!Files.exists(f)) {
            return new LinkedHashMap<>();
        }
        String[] parts = Files.readString(f).trim().split("\\.");
        byte[] salt = b64d(parts[0]);
        byte[] iv = b64d(parts[1]);
        byte[] ct = b64d(parts[2]);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
        return mapper.readValue(cipher.doFinal(ct), Map.class);
    }

    private void save(Map<String, String> secrets) throws Exception {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(mapper.writeValueAsBytes(secrets));
        Path f = file();
        if (f.getParent() != null) {
            Files.createDirectories(f.getParent());
        }
        Files.writeString(f, b64(salt) + "." + b64(iv) + "." + b64(ct));
        restrictToOwner(f);   // owner-only perms (best-effort; no-op on non-POSIX filesystems like Windows)
    }

    /** Narrow the secret file to owner read/write where the OS supports it (POSIX); silently skip on Windows. */
    private void restrictToOwner(Path f) {
        try {
            Files.setPosixFilePermissions(f, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException ignore) {
            // non-POSIX (Windows) — file already lives under the per-user home; ACL hardening is out of scope here
        }
    }

    private SecretKey deriveKey(byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private Path file() {
        if (filePath != null && !filePath.isBlank()) {
            return Path.of(filePath);   // explicit single-file override (local/tests)
        }
        Path base = Path.of(System.getProperty("user.home"), ".veritas");
        if (perPrincipal) {
            // Per-user file under ~/.veritas/secrets/<principal>.enc so one session never decrypts another's tokens.
            return base.resolve("secrets").resolve(safePrincipal() + ".enc");
        }
        return base.resolve("secrets.enc");
    }

    /** Filesystem-safe principal id (defaults to "local" when no CurrentUser is wired or no request is bound). */
    private String safePrincipal() {
        String id = "local";
        if (currentUser != null) {
            try {
                ca.bnc.qe.veritas.settings.CurrentUser u = currentUser.getIfAvailable();
                if (u != null && u.principalId() != null && !u.principalId().isBlank()) {
                    id = u.principalId();
                }
            } catch (RuntimeException outsideRequestScope) {
                id = "local";   // request-scoped principal resolved outside a request → safe default
            }
        }
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private byte[] b64d(String s) {
        return Base64.getDecoder().decode(s);
    }
}
