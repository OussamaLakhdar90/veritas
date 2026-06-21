package ca.bnc.qe.veritas.secret;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Resolves a secret from the first provider that has it (OS keychain → encrypted file → env → Vault, as
 * those providers are added). For now the chain is env-backed; new providers slot in by {@code @Order}.
 */
@Primary
@Component
public class ChainedSecretProvider implements SecretProvider {

    private final List<SecretProvider> providers;

    public ChainedSecretProvider(List<SecretProvider> providers) {
        // Exclude self to avoid recursion; Spring injects all SecretProvider beans including this one.
        this.providers = providers.stream().filter(p -> !(p instanceof ChainedSecretProvider)).toList();
    }

    @Override
    public Optional<String> get(String key) {
        for (SecretProvider provider : providers) {
            Optional<String> value = provider.get(key);
            if (value.isPresent()) {
                SecretRegistry.remember(value.get());   // so it gets masked if it ever reaches a log line
                return value;
            }
        }
        return Optional.empty();
    }
}
