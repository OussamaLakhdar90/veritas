package ca.bnc.qe.veritas.secret;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Makes the encrypted secret store "just work" for a non-technical LOCAL user without editing YAML: before
 * {@code @Value} binding, if {@code veritas.secret.passphrase} is unset and {@code veritas.secret.auto-passphrase}
 * is true (default), generate (once) a machine-bound key file ({@code ~/.veritas/secret.key}, owner-only) and
 * inject it as the passphrase. The crypto class ({@link EncryptedFileSecretStore}) is untouched — it just sees a
 * passphrase and enables itself.
 *
 * <p>On the {@code server} (EC2) profile this is disabled ({@code auto-passphrase=false}); a home-dir key is
 * unacceptable on a shared host, where per-user Vault/KMS keys take over.
 */
public class SettingsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        boolean auto = env.getProperty("veritas.secret.auto-passphrase", Boolean.class, true);
        boolean already = !env.getProperty("veritas.secret.passphrase", "").isBlank();
        if (!auto || already) {
            return;
        }
        String passphrase = loadOrCreateMachineKey();
        if (passphrase != null) {
            Map<String, Object> props = new HashMap<>();
            props.put("veritas.secret.passphrase", passphrase);
            // addFirst so it wins over the empty default; real env/yaml passphrase (checked above) is never overridden.
            env.getPropertySources().addFirst(new MapPropertySource("veritas-machine-key", props));
        }
    }

    /** Read the machine key, creating it on first run. Returns null if it can't be created (store stays disabled). */
    private String loadOrCreateMachineKey() {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".veritas");
            Path keyFile = dir.resolve("secret.key");
            if (Files.exists(keyFile)) {
                String existing = Files.readString(keyFile).trim();
                return existing.isBlank() ? null : existing;
            }
            Files.createDirectories(dir);
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String key = Base64.getEncoder().encodeToString(raw);
            Files.writeString(keyFile, key);
            restrictToOwner(keyFile);
            return key;
        } catch (Exception e) {
            return null;   // pre-context: no logger; failing silent leaves the store disabled (in-app entry will 422)
        }
    }

    private void restrictToOwner(Path f) {
        try {
            Files.setPosixFilePermissions(f, EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException ignore) {
            // non-POSIX (Windows): the file lives under the per-user profile dir
        }
    }
}
