package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CreateTestCasesServiceTest {

    @Autowired
    private CreateTestCasesService casesService;

    @Test
    void generatesAndPersistsCases() {
        List<TestCase> cases = casesService.generate("ciam-policies", "Endpoints:\n- POST /policies\n", "tester");

        assertThat(cases).isNotEmpty();
        assertThat(cases).anyMatch(c -> c.getTitle().equals("Validate happy path"));
        assertThat(cases).allMatch(c -> "PROPOSED".equals(c.getStatus()));
        assertThat(cases).allMatch(c -> c.getId() != null);
        // structured deliverable: rationale + traceability + self-review confidence
        assertThat(cases).allMatch(c -> c.getConfidence() != null && c.getConfidence() == 82.0);
        assertThat(cases).anyMatch(c -> c.getRationale() != null && c.getRationale().contains("EP"));
        assertThat(cases).anyMatch(c -> "CIAM-1".equals(c.getLinkedRequirement()));
    }
}
