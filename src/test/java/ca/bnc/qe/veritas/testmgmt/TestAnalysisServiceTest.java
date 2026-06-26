package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Test analysis: derives a prioritized Test Condition List from the service's strategy + basis (mock LLM). */
@SpringBootTest
class TestAnalysisServiceTest {

    @Autowired private TestStrategyService strategyService;
    @Autowired private TestAnalysisService analysisService;

    private static final String BASIS = "API surface (from code):\n- POST /policies\n- GET /policies/{id}\n";

    @Test
    void identifiesAndPersistsTestConditionsTracedToTheStrategy() {
        TestStrategy strategy = strategyService.generate("analysis-svc", BASIS, "CODE", "tester");

        List<TestCondition> conditions = analysisService.analyze("analysis-svc", BASIS, "tester");

        assertThat(conditions).isNotEmpty();
        assertThat(conditions).allMatch(c -> c.getId() != null);
        assertThat(conditions).allMatch(c -> "PROPOSED".equals(c.getStatus()));
        // traceability spine: every condition pins to the strategy it derived from
        assertThat(conditions).allMatch(c -> strategy.getId().equals(c.getTestStrategyId()));
        // each carries a list id, a risk reference, and an automation candidacy
        assertThat(conditions).allMatch(c -> c.getConditionRef() != null && c.getConditionRef().startsWith("TCD-"));
        assertThat(conditions).anyMatch(c -> "R1".equals(c.getRiskRef()));
        assertThat(conditions).allMatch(c -> c.getConfidence() != null && c.getConfidence() == 81.0);
        // the auto/manual split is decided per condition
        assertThat(conditions).anyMatch(c -> "AUTOMATED".equals(c.getAutomation()));
        assertThat(conditions).anyMatch(c -> "MANUAL".equals(c.getAutomation()));
    }

    @Test
    void regeneratingSupersedesThePriorBatchForTheSameStrategy() {
        strategyService.generate("analysis-rerun-svc", BASIS, "CODE", "tester");

        List<TestCondition> first = analysisService.analyze("analysis-rerun-svc", BASIS, "tester");
        List<TestCondition> second = analysisService.analyze("analysis-rerun-svc", BASIS, "tester");

        // a re-run replaces, it does not accumulate: same count, fresh ids
        assertThat(second).hasSameSizeAs(first);
        assertThat(second).extracting(TestCondition::getId).doesNotContainAnyElementsOf(
                first.stream().map(TestCondition::getId).toList());
    }

    @Test
    void failsClearlyWhenNoStrategyExistsForTheService() {
        assertThatThrownBy(() -> analysisService.analyze("svc-with-no-strategy", BASIS, "tester"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("No test strategy found");
    }
}
