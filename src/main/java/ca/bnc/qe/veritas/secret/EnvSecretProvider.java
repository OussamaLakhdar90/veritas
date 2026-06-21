package ca.bnc.qe.veritas.secret;

import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Reads secrets from environment variables. Last link in the chain (local fallback). */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class EnvSecretProvider implements SecretProvider {

    @Override
    public Optional<String> get(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return Optional.ofNullable(value).filter(v -> !v.isBlank());
    }
}
