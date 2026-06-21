package ca.bnc.qe.veritas.llm;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link LlmGateway} every service injects: transparently caches via {@link PromptCache} and delegates
 * misses to the real gateway (Mock / Copilot-HTTP / Copilot-CLI). Marked {@link Primary} so callers get caching
 * for free; the real gateway is the single non-caching {@code LlmGateway} bean. Disable with
 * {@code veritas.llm.cache=false}.
 */
@Component
@Primary
public class CachingLlmGateway implements LlmGateway {

    private final LlmGateway delegate;
    private final PromptCache cache;

    @Value("${veritas.llm.cache:true}")
    private boolean enabled = true;

    public CachingLlmGateway(List<LlmGateway> gateways, PromptCache cache) {
        this.delegate = gateways.stream()
                .filter(g -> !(g instanceof CachingLlmGateway))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No underlying LlmGateway to wrap"));
        this.cache = cache;
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public String complete(String prompt, String model) {
        if (!enabled) {
            return delegate.complete(prompt, model);
        }
        return cache.get(model, prompt).orElseGet(() -> {
            String response = delegate.complete(prompt, model);
            cache.put(model, prompt, response);
            return response;
        });
    }
}
