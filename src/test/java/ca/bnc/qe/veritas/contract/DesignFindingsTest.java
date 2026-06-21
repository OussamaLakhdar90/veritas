package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** A5: the LLM reconcile pass contributes L5 (design-quality) + L6 (test-basis) findings, persisted. */
@SpringBootTest
class DesignFindingsTest {

    @Autowired private ContractValidationService contract;
    @Autowired private FindingRecordRepository findings;

    @Test
    void reconcileAddsL5AndL6Findings() throws Exception {
        Path repo = Path.of(getClass().getClassLoader().getResource("fixtures/policies").toURI());
        String spec = Files.readString(
                Path.of(getClass().getClassLoader().getResource("fixtures/policies-spec.yaml").toURI()));

        ValidationResult r = contract.validate(new ValidationRequest("ciam-policies", null, null, null, repo,
                List.of(new SpecInput("repo-spec", spec)), true, "tester"));   // llmEnabled=true → reconcile runs

        List<FindingRecord> persisted = findings.findByScanId(r.scanId());
        assertThat(persisted).anyMatch(f -> "DESIGN_QUALITY".equals(f.getType()) && "L5".equals(f.getLayer()));
        assertThat(persisted).anyMatch(f -> "TEST_BASIS_GAP".equals(f.getType()) && "L6".equals(f.getLayer()));
    }
}
