package ca.bnc.qe.veritas.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import ca.bnc.qe.veritas.secret.EncryptedFileSecretStore;
import ca.bnc.qe.veritas.secret.KnownSecretKeys;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.secret.SecretRegistry;
import org.springframework.stereotype.Service;

/**
 * Writes a per-user secret into the encrypted store (the in-app token entry) and reports which keys are SET —
 * never the values. The value is registered for log masking on write; reads go through the chained
 * {@link SecretProvider} so a key supplied via env still shows as configured.
 */
@Service
public class SecretWriteService {

    private final EncryptedFileSecretStore store;
    private final SecretProvider secrets;

    public SecretWriteService(EncryptedFileSecretStore store, SecretProvider secrets) {
        this.store = store;
        this.secrets = secrets;
    }

    public void set(String key, String value) {
        if (!KnownSecretKeys.isKnown(key)) {
            throw new IllegalArgumentException("Unknown secret key '" + key + "'. Known keys: " + KnownSecretKeys.ALL);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Secret value for '" + key + "' must not be blank.");
        }
        try {
            store.put(key, value);
        } catch (IllegalStateException disabled) {
            // Store has no passphrase (the machine key file could not be created) → a precondition, not a 500.
            throw new PreconditionException("settings-secrets", List.of(disabled.getMessage()
                    + " Check write access to ~/.veritas, or set veritas.secret.passphrase."));
        }
        SecretRegistry.remember(value);
    }

    /** {key -> isSet}: which known secrets are configured (via the full chain), values never exposed. */
    public Map<String, Boolean> status() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String key : KnownSecretKeys.ALL) {
            out.put(key, secrets.get(key).filter(v -> !v.isBlank()).isPresent());
        }
        return out;
    }
}
