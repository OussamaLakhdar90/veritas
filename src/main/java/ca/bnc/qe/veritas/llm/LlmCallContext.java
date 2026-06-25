package ca.bnc.qe.veritas.llm;

import org.springframework.stereotype.Component;

/**
 * Carries side-channel facts about the most recent {@link LlmGateway#complete} on this thread for the cost
 * recorder, set-then-consumed on the same thread right after the call:
 * <ul>
 *   <li>whether it was a {@link PromptCache} HIT (set by {@code CachingLlmGateway}) → billed as zero;</li>
 *   <li>the <b>actual</b> token {@link Usage} the provider reported (set by the real gateway when the API returns
 *       a {@code usage} object) → billed from real counts instead of the ~4-chars/token estimate.</li>
 * </ul>
 */
@Component
public class LlmCallContext {

    /** Real token counts reported by the provider for one call. */
    public record Usage(long promptTokens, long completionTokens) {}

    private final ThreadLocal<Boolean> lastCached = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<Usage> lastUsage = new ThreadLocal<>();

    public void markCached(boolean cached) {
        lastCached.set(cached);
    }

    /** Returns whether the last call was a cache hit, then resets to false. */
    public boolean consumeCached() {
        boolean v = Boolean.TRUE.equals(lastCached.get());
        lastCached.set(Boolean.FALSE);
        return v;
    }

    /** Record the provider-reported token usage for the call just made on this thread. */
    public void markUsage(long promptTokens, long completionTokens) {
        lastUsage.set(new Usage(promptTokens, completionTokens));
    }

    /** Returns the last call's real usage (or null if the provider didn't report any), then clears it. */
    public Usage consumeUsage() {
        Usage u = lastUsage.get();
        lastUsage.remove();
        return u;
    }
}
