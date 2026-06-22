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

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    @Override
    public Optional<String> get(String key) {
        if (disabled()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(load().get(key)).filter(v -> !v.isBlank());
        } catch (Exception e) {
            return Optional.empty();   // wrong passphrase / corrupt file → treat as "not found"
        }
    }

    public void put(String key, String value) {
        if (disabled()) {
            throw new IllegalStateException("Encrypted secret store is disabled — set veritas.secret.passphrase.");
        }
        try {
            Map<String, String> secrets = Files.exists(file()) ? load() : new LinkedHashMap<>();
            secrets.put(key, value);
            save(secrets);
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
            return Path.of(filePath);
        }
        return Path.of(System.getProperty("user.home"), ".veritas", "secrets.enc");
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
