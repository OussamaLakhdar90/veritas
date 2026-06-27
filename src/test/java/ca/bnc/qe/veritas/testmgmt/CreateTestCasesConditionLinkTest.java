package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.persistence.TestConditionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * When test conditions exist for a service, create-test-cases links each generated case back to its condition —
 * restoring the basis ↔ condition ↔ case traceability. With no conditions, behaviour is unchanged (covered by
 * {@code CreateTestCasesServiceTest}).
 */
@SpringBootTest
class CreateTestCasesConditionLinkTest {

    @Autowired private CreateTestCasesService casesService;
    @Autowired private TestConditionRepository conditionRepository;

    @Test
    void linksGeneratedCasesToTheirTestCondition() {
        // Seed a condition whose basis item matches the requirementKey the mock case carries (CIAM-1).
        TestCondition c = new TestCondition();
        c.setServiceName("cases-link-svc");
        c.setConditionRef("TCD-001");
        c.setSourceBasisItem("CIAM-1");
        c.setTestStrategyId("strat-x");
        c.setAutomation("AUTOMATED");
        c.setStatus("PROPOSED");
        TestCondition saved = conditionRepository.save(c);

        List<TestCase> cases = casesService.generate("cases-link-svc", "Endpoints:\n- POST /policies\n", "tester");

        assertThat(cases).isNotEmpty();
        // the mock case requirementKey=CIAM-1 → linked to the condition via its sourceBasisItem
        assertThat(cases).anyMatch(tc -> saved.getId().equals(tc.getTestConditionId()));
    }

    @Test
    void dropsAnUnverifiableRequirementKeyWhenConditionsExistButNoneMatch() {
        // A condition exists for the service, but its basis item does not match the mock case's requirementKey (CIAM-1),
        // so the key is an unverifiable trace → rejected (not persisted), rather than null-and-keep.
        TestCondition c = new TestCondition();
        c.setServiceName("cases-reject-svc");
        c.setConditionRef("TCD-X");
        c.setSourceBasisItem("OTHER-9");
        c.setTestStrategyId("strat-y");
        c.setStatus("PROPOSED");
        conditionRepository.save(c);

        List<TestCase> cases = casesService.generate("cases-reject-svc", "Endpoints:\n- POST /policies\n", "tester");

        assertThat(cases).isNotEmpty();
        assertThat(cases).allMatch(tc -> tc.getTestConditionId() == null);     // nothing linked
        assertThat(cases).allMatch(tc -> tc.getLinkedRequirement() == null);   // fabricated key dropped
    }
}
