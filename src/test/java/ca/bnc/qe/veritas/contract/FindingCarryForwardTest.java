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

@SpringBootTest
class FindingCarryForwardTest {

    @Autowired
    private ContractValidationService service;

    @Autowired
    private FindingRecordRepository findings;

    @Test
    void carriesTriageStatusToNextScan() throws Exception {
        Path repo = Path.of(getClass().getClassLoader().getResource("fixtures/policies").toURI());
        String spec = Files.readString(
                Path.of(getClass().getClassLoader().getResource("fixtures/policies-spec.yaml").toURI()));
        ValidationRequest req = new ValidationRequest("ciam-policies", null, null, null,
                repo, List.of(new SpecInput("repo-spec", spec)), false, "test");

        ValidationResult first = service.validate(req);
        List<FindingRecord> firstFindings = findings.findByScanId(first.scanId());
        assertThat(firstFindings).isNotEmpty();

        FindingRecord triaged = firstFindings.get(0);
        String fingerprint = triaged.getFingerprint();
        triaged.setStatus("WONT_FIX");
        findings.save(triaged);

        ValidationResult second = service.validate(req);
        FindingRecord carried = findings.findByScanId(second.scanId()).stream()
                .filter(f -> fingerprint.equals(f.getFingerprint()))
                .findFirst().orElseThrow();

        assertThat(carried.getStatus()).isEqualTo("WONT_FIX");
    }
}
