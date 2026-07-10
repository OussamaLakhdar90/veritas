package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

    @Test
    void carriesADisputeVerdictToNextScanEvenWhileTheFindingStaysOpen() throws Exception {
        Path repo = Path.of(getClass().getClassLoader().getResource("fixtures/policies").toURI());
        String spec = Files.readString(
                Path.of(getClass().getClassLoader().getResource("fixtures/policies-spec.yaml").toURI()));
        ValidationRequest req = new ValidationRequest("ciam-policies", null, null, null,
                repo, List.of(new SpecInput("repo-spec", spec)), false, "test");

        ValidationResult first = service.validate(req);
        List<FindingRecord> firstFindings = findings.findByScanId(first.scanId());
        assertThat(firstFindings).isNotEmpty();

        FindingRecord verdicted = firstFindings.get(0);
        String fingerprint = verdicted.getFingerprint();
        // Record the dispute verdict + its rationale + who/when — but leave the lifecycle status OPEN. The disposition
        // carry-forward (status <> OPEN) would miss all of it; the verdict has its own broader carry-forward, so the
        // verdict, its note, AND its reviewer audit must all still survive the next scan (no churn, no lost authorship).
        verdicted.setDisputeVerdict("NEEDS_DETECTION_FIX");
        verdicted.setDisputeVerdictNote("int32 vs integer normalization gap");
        verdicted.setReviewedBy("alice");
        verdicted.setReviewedAt(Instant.now());
        findings.save(verdicted);

        ValidationResult second = service.validate(req);
        FindingRecord carried = findings.findByScanId(second.scanId()).stream()
                .filter(f -> fingerprint.equals(f.getFingerprint()))
                .findFirst().orElseThrow();

        assertThat(carried.getDisputeVerdict()).isEqualTo("NEEDS_DETECTION_FIX");
        assertThat(carried.getDisputeVerdictNote()).isEqualTo("int32 vs integer normalization gap");
        assertThat(carried.getReviewedBy()).isEqualTo("alice");   // authorship survives (verdict-audit carry-forward)
        assertThat(carried.getReviewedAt()).isNotNull();
    }
}
