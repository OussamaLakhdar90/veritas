package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Identical (model+prompt) is a zero-token cache hit; distinct prompts still reach the delegate. */
class PromptCacheTest {

    @Test
    void cachesIdenticalPromptsAndDelegatesMisses() {
        LlmGateway delegate = mock(LlmGateway.class);
        when(delegate.isAvailable()).thenReturn(true);
        when(delegate.complete("p1", "m")).thenReturn("r1");
        when(delegate.complete("p2", "m")).thenReturn("r2");

        PromptCache cache = new PromptCache();
        CachingLlmGateway gw = new CachingLlmGateway(List.of(delegate), cache, new LlmCallContext());

        assertThat(gw.complete("p1", "m")).isEqualTo("r1");
        assertThat(gw.complete("p1", "m")).isEqualTo("r1");   // served from cache
        verify(delegate, times(1)).complete("p1", "m");       // delegate hit once only

        assertThat(gw.complete("p2", "m")).isEqualTo("r2");   // different prompt → miss → delegate
        verify(delegate, times(1)).complete("p2", "m");

        assertThat(cache.hits()).isEqualTo(1);
        assertThat(cache.misses()).isEqualTo(2);
        assertThat(gw.isAvailable()).isTrue();
    }

    @Test
    void sameTextDifferentModelIsADistinctKey() {
        assertThat(PromptCache.key("modelA", "p")).isNotEqualTo(PromptCache.key("modelB", "p"));
        assertThat(PromptCache.key("m", "p")).isEqualTo(PromptCache.key("m", "p"));
    }
}
