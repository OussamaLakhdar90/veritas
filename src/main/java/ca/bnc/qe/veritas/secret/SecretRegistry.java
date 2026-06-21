package ca.bnc.qe.veritas.secret;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide set of resolved secret values, fed by {@link ChainedSecretProvider} as tokens are read, and
 * consumed by the log-masking converter so any secret that has ever been used is redacted from log output.
 * Values shorter than 4 chars are ignored (too generic to mask safely).
 */
public final class SecretRegistry {

    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();

    private SecretRegistry() {
    }

    public static void remember(String value) {
        if (value != null && value.length() >= 4) {
            SECRETS.add(value);
        }
    }

    public static Set<String> snapshot() {
        return Set.copyOf(SECRETS);
    }
}
