package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Translation uses the cheapest tier, batches, caches, and falls back to English — no reasoning model. */
class TranslationServiceTest {

    @Test
    void translatesBatchOnCheapestTierAndCaches() {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(any(), any())).thenReturn("```json\n{\"0\":\"Bonjour\",\"1\":\"Le monde\"}\n```");
        ModelSelector ms = mock(ModelSelector.class);
        when(ms.resolveTier(ModelTier.ECONOMY)).thenReturn("economy-model");
        CostRecorder cr = mock(CostRecorder.class);

        TranslationService t = new TranslationService(llm, ms, cr, new JsonBlockExtractor(), new ObjectMapper());

        Map<String, String> out = t.toFrench(List.of("Hello", "World"), "me");
        assertThat(out).containsEntry("Hello", "Bonjour").containsEntry("World", "Le monde");
        verify(ms).resolveTier(ModelTier.ECONOMY);   // cheapest tier, not DEEP

        // Cached: re-requesting an already-translated string does NOT call the LLM again.
        t.toFrench(List.of("Hello"), "me");
        verify(llm, times(1)).complete(any(), any());
    }

    @Test
    void fallsBackToEnglishWhenLlmUnavailable() {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.isAvailable()).thenReturn(false);
        ModelSelector ms = mock(ModelSelector.class);
        CostRecorder cr = mock(CostRecorder.class);

        TranslationService t = new TranslationService(llm, ms, cr, new JsonBlockExtractor(), new ObjectMapper());
        Map<String, String> out = t.toFrench(List.of("Hello"), "me");

        assertThat(out).containsEntry("Hello", "Hello");   // English fallback
        verify(llm, times(0)).complete(any(), any());       // no cost when unavailable
    }
}
