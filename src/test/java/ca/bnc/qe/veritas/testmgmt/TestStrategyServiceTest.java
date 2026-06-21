package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.persistence.TestStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TestStrategyServiceTest {

    @Autowired
    private TestStrategyService strategyService;

    @Test
    void generatesAndPersistsStrategy() {
        TestStrategy strategy = strategyService.generate(
                "ciam-policies", "Endpoints (from code):\n- GET /api/v1/policies/{id}\n", "CODE", "tester");

        assertThat(strategy.getId()).isNotBlank();
        assertThat(strategy.getContentMarkdown()).contains("Test Strategy");
        assertThat(strategy.getStatus()).isEqualTo("DRAFT");
        assertThat(strategy.getEstCostUsd()).isGreaterThanOrEqualTo(0.0);
        // structured deliverable: risk register + self-review confidence persisted
        assertThat(strategy.getConfidence()).isEqualTo(80.0);
        assertThat(strategy.getDeliverableJson()).contains("riskRegister").contains("selfReview");
    }
}
