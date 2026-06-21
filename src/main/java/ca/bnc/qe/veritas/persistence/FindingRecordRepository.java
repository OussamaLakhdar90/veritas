package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRecordRepository extends JpaRepository<FindingRecord, String> {
    List<FindingRecord> findByScanIdOrderBySeverityAsc(String scanId);
    List<FindingRecord> findByScanId(String scanId);
    List<FindingRecord> findByFingerprintOrderByCreatedAtDesc(String fingerprint);
}
