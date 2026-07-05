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
        // Guarantee ~/.veritas exists BEFORE the SQLite datasource opens ${user.home}/.veritas/veritas.db (SQLite
        // creates the db file but not missing parent dirs). Anchoring the DB in the home dir — not the process cwd —
        // is what keeps scan history (and the report Trend line) stable across rebuilds/relaunches from any directory.
        // Runs first so it applies even when a passphrase is already supplied (the early-return below is skipped for it).
        ensureHomeDir();

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

        // Overlay persisted connection settings BEFORE binding so a saved `edition` re-wires the
        // @ConditionalOnProperty client beans (which are decided at context build, before ApplicationRunner).
        Map<String, Object> conn = loadPersistedConnections();
        if (!conn.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("veritas-connections-file", conn));
        }

        // Overlay the persisted LLM engine (chooses the @ConditionalOnProperty gateway bean) — but an explicit
        // -Dveritas.llm.mode / VERITAS_LLM_MODE always wins, so a developer's flag is never overridden by the file.
        boolean modeExplicit = System.getProperty("veritas.llm.mode") != null
                || System.getenv("VERITAS_LLM_MODE") != null;
        if (!modeExplicit) {
            String mode = loadPersistedLlmMode();
            if (mode != null && !mode.isBlank()) {
                env.getPropertySources().addFirst(new MapPropertySource("veritas-llm-file",
                        Map.of("veritas.llm.mode", mode)));
            }
        }
    }

    /** Read the persisted engine ({@code mode}) from {@code ~/.veritas/llm.json}; null when absent/corrupt. */
    @SuppressWarnings("unchecked")
    private String loadPersistedLlmMode() {
        try {
            Path file = Path.of(System.getProperty("user.home"), ".veritas", "llm.json");
            if (!Files.exists(file)) {
                return null;
            }
            Object mode = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(Files.readString(file), Map.class).get("mode");
            return mode == null ? null : mode.toString();
        } catch (Exception ignore) {
            return null;
        }
    }

    /** Flatten {@code ~/.veritas/connections.json} into {@code veritas.connections.<svc>.<field>} props. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadPersistedConnections() {
        Map<String, Object> out = new HashMap<>();
        try {
            Path file = Path.of(System.getProperty("user.home"), ".veritas", "connections.json");
            if (!Files.exists(file)) {
                return out;
            }
            Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(Files.readString(file), Map.class);
            Map<String, String> fieldToProp = Map.of(
                    "baseUrl", "base-url", "edition", "edition", "workspace", "workspace", "authType", "auth-type");
            for (Map.Entry<String, Object> svc : root.entrySet()) {
                if (!(svc.getValue() instanceof Map<?, ?> fields)) {
                    continue;
                }
                fieldToProp.forEach((field, prop) -> {
                    Object v = fields.get(field);
                    if (v != null && !v.toString().isBlank()) {
                        out.put("veritas.connections." + svc.getKey() + "." + prop, v.toString());
                    }
                });
            }
        } catch (Exception ignore) {
            // corrupt/unreadable → fall back to yaml defaults (no crash, pre-context so no logger)
        }
        return out;
    }

    /** Ensure {@code ~/.veritas} exists so the default SQLite datasource can open its db file there on first run. */
    private void ensureHomeDir() {
        try {
            Files.createDirectories(Path.of(System.getProperty("user.home"), ".veritas"));
        } catch (Exception ignore) {
            // pre-context (no logger). Only the default local SQLite path needs this dir; tests override the URL and
            // the 'server' profile uses Postgres, so a failure here is non-fatal to those paths.
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
