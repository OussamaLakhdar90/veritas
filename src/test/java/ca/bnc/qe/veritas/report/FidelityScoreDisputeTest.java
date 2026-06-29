package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import org.junit.jupiter.api.Test;

/**
 * An AI-disputed deterministic finding leaves the score + release gate (it is "needs attention"), while an identical
 * non-disputed one is counted and penalised. This is the single chokepoint both the report split and the blocking
 * count route through, so proving it here proves the gate exclusion end to end.
 */
class FidelityScoreDisputeTest {

    private static Finding blocker(boolean disputed) {
        return Finding.builder()
                .findingId("b-" + disputed)
                .type(FindingType.STATUS_CODE_MISSING)   // not a design type
                .layer(Layer.L4)
                .severity(Severity.BLOCKER)
                .confidence(Confidence.HIGH)             // not LOW
                .origin("DETERMINISTIC")                 // not LLM
                .summary("500 not in spec")
                .aiDisputed(disputed)
                .aiDisputeReason(disputed ? "the @ControllerAdvice maps 500 here" : null)
                .build();
    }

    @Test
    void aDisputedBlockerIsExcludedFromTheScoreWhileAnIdenticalOneIsPenalised() {
        assertThat(FidelityScore.isNeedsAttention(blocker(true))).isTrue();
        assertThat(FidelityScore.isNeedsAttention(blocker(false))).isFalse();

        assertThat(FidelityScore.of(List.of(blocker(true)))).isEqualTo(100);   // disputed → no penalty
        assertThat(FidelityScore.of(List.of(blocker(false)))).isEqualTo(75);   // counted BLOCKER → 100 - 25
    }
}
