package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Test analysis: derives a prioritized Test Condition List from the service's strategy + basis (mock LLM). */
@SpringBootTest
class TestAnalysisServiceTest {

    @Autowired private TestStrategyService strategyService;
    @Autowired private TestAnalysisService analysisService;
    @Autowired private ca.bnc.qe.veritas.persistence.TestStrategyRepository strategyRepository;

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
    void dropsAFabricatedRiskRefNotInTheStrategyRegisterAndCapsConfidence() {
        // Strategy's register only contains RX; the mock conditions cite R1 → R1 is not a real risk for this strategy.
        ca.bnc.qe.veritas.persistence.TestStrategy s = new ca.bnc.qe.veritas.persistence.TestStrategy();
        s.setServiceName("analysis-riskdrop-svc");
        s.setStatus("APPROVED");
        s.setDeliverableJson("{\"riskRegister\":[{\"id\":\"RX\",\"level\":\"HIGH\"}],\"selfReview\":{\"confidence\":90}}");
        strategyRepository.save(s);

        List<TestCondition> conditions = analysisService.analyze("analysis-riskdrop-svc", BASIS, "tester");

        assertThat(conditions).isNotEmpty();
        assertThat(conditions).allMatch(c -> c.getRiskRef() == null);                 // fabricated R1 dropped
        assertThat(conditions).allMatch(c -> c.getConfidence() != null && c.getConfidence() <= 50.0);  // confidence capped
    }

    @Test
    void autoGeneratesADraftStrategyWhenNoneExistsSoTheCodeFirstPathWorks() {
        // No strategy seeded → analysis auto-creates a DRAFT from the basis and still produces conditions (code-first).
        List<TestCondition> conditions = analysisService.analyze("svc-codefirst", BASIS, "CODE", "tester");

        assertThat(conditions).isNotEmpty();
        List<TestStrategy> created = strategyRepository.findByServiceNameOrderByCreatedAtDesc("svc-codefirst");
        assertThat(created).isNotEmpty();                                  // a DRAFT strategy now exists
        assertThat(created.get(0).getStatus()).isEqualTo("DRAFT");
        assertThat(conditions).allMatch(c -> created.get(0).getId().equals(c.getTestStrategyId()));   // traced to it
    }
}
