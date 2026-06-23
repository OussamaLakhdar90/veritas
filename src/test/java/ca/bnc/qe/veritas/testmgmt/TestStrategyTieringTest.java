package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.cost.ModelTier;
import org.junit.jupiter.api.Test;

/**
 * Guards the model-fitness audit's one calibration: the adversarial self-critique section must NOT run on the cost
 * floor (ECONOMY), where small models rubber-stamp the assembled strategy with inflated confidence and shallow blind
 * spots. The heavy synthesis section (riskRegister) stays DEEP. A silent revert here would quietly undo the quality win.
 */
class TestStrategyTieringTest {

    @Test
    void selfReviewRunsAtStandardNotTheCostFloor() {
        assertThat(TestStrategyService.sectionTier("selfReview")).isEqualTo(ModelTier.STANDARD);
        assertThat(TestStrategyService.sectionTier("selfReview")).isNotEqualTo(ModelTier.ECONOMY);
    }

    @Test
    void riskRegisterStaysDeep() {
        assertThat(TestStrategyService.sectionTier("riskRegister")).isEqualTo(ModelTier.DEEP);
    }
}
