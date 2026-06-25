package ca.bnc.qe.veritas.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bnc.qe.veritas.llm.LlmCallContext;
import ca.bnc.qe.veritas.persistence.CostEntry;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import org.junit.jupiter.api.Test;

/** Cost recording: a cache hit bills zero; provider-reported usage overrides the char estimate. */
class CostRecorderCacheTest {

    @Test
    void cacheHitRecordsZeroCostAndTagsTheEntry() {
        CostEstimator estimator = mock(CostEstimator.class);
        when(estimator.estimate(any(), any(), any()))
                .thenReturn(new CostResult("m", BillingMode.PER_REQUEST, 5, 100, 200, 0.40, false));
        CostEntryRepository repository = mock(CostEntryRepository.class);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        LlmCallContext ctx = new LlmCallContext();
        CostRecorder recorder = new CostRecorder(estimator, repository, ctx);

        // miss → full estimated cost
        CostResult miss = recorder.record("skill", "act", "m", "p", "r", "owner");
        assertThat(miss.estCostUsd()).isEqualTo(0.40);

        // hit → zero cost, and consuming the flag resets it
        ctx.markCached(true);
        CostResult hit = recorder.record("skill", "act", "m", "p", "r", "owner");
        assertThat(hit.estCostUsd()).isZero();
        assertThat(hit.premiumRequests()).isZero();

        // a subsequent record with no new hit is full cost again (flag was consumed)
        assertThat(recorder.record("skill", "act", "m", "p", "r", "owner").estCostUsd()).isEqualTo(0.40);
    }

    @Test
    void providerReportedUsageOverridesTheCharEstimate() {
        CostEstimator estimator = mock(CostEstimator.class);
        when(estimator.fromActualUsage("m", 11, 7))
                .thenReturn(new CostResult("m", BillingMode.USAGE_CREDITS, 0, 11, 7, 0.02, true));
        CostEntryRepository repository = mock(CostEntryRepository.class);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        LlmCallContext ctx = new LlmCallContext();
        CostRecorder recorder = new CostRecorder(estimator, repository, ctx);

        ctx.markUsage(11, 7);   // the gateway reported real token counts for this call
        CostResult r = recorder.record("skill", "act", "m", "a long prompt with many characters", "resp", "owner");

        assertThat(r.tokensActual()).isTrue();
        assertThat(r.estTokensIn()).isEqualTo(11);
        assertThat(r.estTokensOut()).isEqualTo(7);
        verify(estimator, never()).estimate(any(), any(), any());   // didn't fall back to the char estimate

        // usage was consumed → the next call (no usage) falls back to the char estimate
        when(estimator.estimate(any(), any(), any()))
                .thenReturn(new CostResult("m", BillingMode.USAGE_CREDITS, 0, 9, 1, 0.01, false));
        assertThat(recorder.record("skill", "act", "m", "p", "r", "owner").tokensActual()).isFalse();
    }
}
