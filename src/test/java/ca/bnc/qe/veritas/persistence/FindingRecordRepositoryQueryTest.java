package ca.bnc.qe.veritas.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Validates the new JPQL (findPriorDispositions + deleteByCreatedAtBefore) against a real persistence context. */
@SpringBootTest
@Transactional
class FindingRecordRepositoryQueryTest {

    @Autowired
    private FindingRecordRepository repo;

    private FindingRecord finding(String scanId, String fingerprint, String status) {
        FindingRecord r = new FindingRecord();
        r.setScanId(scanId);
        r.setFingerprint(fingerprint);
        r.setStatus(status);
        r.setSeverity("MAJOR");
        r.setType("MISSING_ENDPOINT");
        return r;
    }

    @Test
    void findPriorDispositionsReturnsOnlyOtherScanNonOpenRows() {
        String fp = "fp-" + System.nanoTime();
        repo.saveAndFlush(finding("scan-old", fp, "WONT_FIX"));   // a real prior disposition
        repo.saveAndFlush(finding("scan-old", fp, "OPEN"));       // excluded: OPEN
        repo.saveAndFlush(finding("scan-old", fp, null));         // excluded: null status
        repo.saveAndFlush(finding("scan-current", fp, "WONT_FIX")); // excluded: same scan

        List<FindingRecord> prior = repo.findPriorDispositions(List.of(fp), "scan-current");

        assertThat(prior).hasSize(1);
        assertThat(prior.get(0).getScanId()).isEqualTo("scan-old");
        assertThat(prior.get(0).getStatus()).isEqualTo("WONT_FIX");
    }

    @Test
    void deleteByCreatedAtBeforeRemovesRowsOlderThanTheCutoff() {
        String fp = "del-" + System.nanoTime();
        repo.saveAndFlush(finding("scan-x", fp, "OPEN"));
        long before = repo.findByFingerprintOrderByCreatedAtDesc(fp).size();
        assertThat(before).isEqualTo(1);

        // A cutoff in the past deletes nothing (the row is fresh)…
        assertThat(repo.deleteByCreatedAtBefore(Instant.now().minus(1, ChronoUnit.DAYS))).isZero();
        assertThat(repo.findByFingerprintOrderByCreatedAtDesc(fp)).hasSize(1);

        // …a cutoff in the future sweeps it.
        int deleted = repo.deleteByCreatedAtBefore(Instant.now().plus(1, ChronoUnit.DAYS));
        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(repo.findByFingerprintOrderByCreatedAtDesc(fp)).isEmpty();
    }
}
