package ca.bnc.qe.veritas.evolve;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository.ClassificationVoteRow;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-DB proof of the Engine-Evolution signal query: the finding→scan theta-join, the null-service exclusion,
 * and the debt counters run correctly against the actual schema (unit tests only mock the projection rows).
 * {@code @Transactional} rolls the inserts back so this doesn't pollute the shared test DB.
 */
@SpringBootTest
@Transactional
class ClassificationVoteQueryTest {

    @Autowired private FindingRecordRepository findings;
    @Autowired private ScanRepository scans;

    @Test
    void collectsVotesJoinedToServiceExcludingNullServiceAndCountsDebt() {
        long baseUnspecified = findings.countDistinctUnspecified();
        long baseDisputed = findings.countDistinctDisputed();

        Scan a = scan("svc-a");
        Scan b = scan("svc-a");
        Scan c = scan("svc-b");
        Scan noService = scan(null);
        vote(a.getId(), "fX", "CRITICAL");     // fX overridden CRITICAL then…
        vote(b.getId(), "fX", "MINOR");        // …MINOR on a re-scan (both rows returned; dedupe is service-side)
        vote(c.getId(), "fY", "MINOR");
        vote(noService.getId(), "fZ", "MAJOR"); // no service name → excluded from the cross-project signal
        disputed(a.getId(), "gD");

        var rows = findings.findUnspecifiedClassificationVotes().stream()
                .filter(r -> Set.of("fX", "fY", "fZ").contains(r.getFingerprint()))
                .toList();
        // fZ (null service) is excluded by the join filter; fX (both scan rows) + fY come back with their service.
        assertThat(rows).extracting(ClassificationVoteRow::getFingerprint)
                .containsExactlyInAnyOrder("fX", "fX", "fY");
        assertThat(rows).allSatisfy(r -> assertThat(r.getService()).isIn("svc-a", "svc-b"));

        // Debt counts DISTINCT fingerprints: fX once, fY, fZ → +3 unspecified; the disputed finding → +1.
        assertThat(findings.countDistinctUnspecified()).isEqualTo(baseUnspecified + 3);
        assertThat(findings.countDistinctDisputed()).isEqualTo(baseDisputed + 1);
    }

    private Scan scan(String service) {
        Scan s = new Scan();
        s.setServiceName(service);
        return scans.save(s);
    }

    private void vote(String scanId, String fingerprint, String userSeverity) {
        FindingRecord f = new FindingRecord();
        f.setScanId(scanId);
        f.setType("PARAM_MISSING");
        f.setFingerprint(fingerprint);
        f.setSeverity("UNSPECIFIED");
        f.setUserSeverity(userSeverity);
        findings.save(f);
    }

    private void disputed(String scanId, String fingerprint) {
        FindingRecord f = new FindingRecord();
        f.setScanId(scanId);
        f.setType("PARAM_MISSING");
        f.setFingerprint(fingerprint);
        f.setSeverity("MAJOR");
        f.setAiDisputed(true);
        findings.save(f);
    }
}
