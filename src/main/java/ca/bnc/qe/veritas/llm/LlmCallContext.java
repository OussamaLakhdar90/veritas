package ca.bnc.qe.veritas.llm;

import org.springframework.stereotype.Component;

/**
 * Carries side-channel facts about the most recent {@link LlmGateway#complete} on this thread for the cost
 * recorder, set-then-consumed on the same thread right after the call:
 * <ul>
 *   <li>whether it was a {@link PromptCache} HIT (set by {@code CachingLlmGateway}) → billed as zero;</li>
 *   <li>the <b>actual</b> token {@link Usage} the provider reported (set by the real gateway when the API returns
 *       a {@code usage} object) → billed from real counts instead of the ~4-chars/token estimate.</li>
 *   <li>an optional {@link ProgressSink} a caller arms before a streaming call, so the gateway can report
 *       incremental output length as tokens arrive (drives the live "AI generating…" progress detail).</li>
 * </ul>
 */
@Component
public class LlmCallContext {

    /** Real token counts reported by the provider for one call. */
    public record Usage(long promptTokens, long completionTokens) {}

    /** A throttled progress callback a caller arms around a streaming {@code complete}; notified as output grows. */
    @FunctionalInterface
    public interface ProgressSink {
        void onProgress(long chars);
    }

    private final ThreadLocal<Boolean> lastCached = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<Usage> lastUsage = new ThreadLocal<>();
    private final ThreadLocal<ProgressSink> progressSink = new ThreadLocal<>();

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

    /** Arm a progress sink for the next streaming call on this thread (caller MUST {@link #clearProgressSink} after). */
    public void armProgressSink(ProgressSink sink) {
        progressSink.set(sink);
    }

    /** Remove any armed progress sink — call in a {@code finally} so a pooled thread never reuses a stale sink. */
    public void clearProgressSink() {
        progressSink.remove();
    }

    /** Notify the armed sink (if any) of the current output length. Never throws — progress must not break the call. */
    public void reportProgress(long chars) {
        ProgressSink sink = progressSink.get();
        if (sink != null) {
            try {
                sink.onProgress(chars);
            } catch (RuntimeException ignore) {
                // a failing progress update (e.g. a DB hiccup) must never abort the streaming read
            }
        }
    }
}
