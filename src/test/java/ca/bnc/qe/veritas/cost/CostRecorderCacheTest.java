package ca.bnc.qe.veritas.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.bnc.qe.veritas.llm.LlmCallContext;
import ca.bnc.qe.veritas.persistence.CostEntry;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import org.junit.jupiter.api.Test;

/** A PromptCache hit must be billed as zero, not a full-cost ledger row (whole-project review HIGH). */
class CostRecorderCacheTest {

    @Test
    void cacheHitRecordsZeroCostAndTagsTheEntry() {
        CostEstimator estimator = mock(CostEstimator.class);
        when(estimator.estimate(any(), any(), any()))
                .thenReturn(new CostResult("m", BillingMode.PER_REQUEST, 5, 100, 200, 0.40));
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
}
