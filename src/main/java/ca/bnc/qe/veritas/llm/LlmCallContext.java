package ca.bnc.qe.veritas.llm;

import org.springframework.stereotype.Component;

/**
 * Carries whether the most recent {@link LlmGateway#complete} on this thread was served from the
 * {@link PromptCache}. Set by {@link CachingLlmGateway} and consumed by the cost recorder right after, so a
 * cache HIT is billed as zero (no tokens were spent) instead of a full-cost ledger row. Same-thread, set-then-
 * consume: every cost record reflects the immediately-preceding complete() call.
 */
@Component
public class LlmCallContext {

    private final ThreadLocal<Boolean> lastCached = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public void markCached(boolean cached) {
        lastCached.set(cached);
    }

    /** Returns whether the last call was a cache hit, then resets to false. */
    public boolean consumeCached() {
        boolean v = Boolean.TRUE.equals(lastCached.get());
        lastCached.set(Boolean.FALSE);
        return v;
    }
}
