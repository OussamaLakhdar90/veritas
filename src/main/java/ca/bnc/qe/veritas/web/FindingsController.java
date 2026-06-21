package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read API the dashboard consumes: contract-validation scans and their findings. */
@RestController
@RequestMapping("/api/v1")
public class FindingsController {

    private final ScanRepository scanRepository;
    private final FindingRecordRepository findingRepository;

    public FindingsController(ScanRepository scanRepository, FindingRecordRepository findingRepository) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
    }

    @GetMapping("/scans")
    public List<Scan> scans() {
        return scanRepository.findAllByOrderByStartedAtDesc();
    }

    @GetMapping("/scans/{id}/findings")
    public List<FindingRecord> findings(@PathVariable String id) {
        return findingRepository.findByScanId(id);
    }

    private static final Set<String> ALLOWED_STATUS =
            Set.of("OPEN", "TRIAGED", "JIRA_CREATED", "FIXED", "WONT_FIX", "FALSE_POSITIVE");

    /** Triage a finding: update its status (e.g. WONT_FIX / FALSE_POSITIVE / TRIAGED). */
    @PatchMapping("/findings/{id}")
    public ResponseEntity<FindingRecord> patch(@PathVariable String id, @RequestBody FindingPatch patch) {
        FindingRecord f = findingRepository.findById(id).orElse(null);
        if (f == null) {
            return ResponseEntity.notFound().build();
        }
        if (patch.status() != null) {
            String s = patch.status().toUpperCase(java.util.Locale.ROOT);
            if (!ALLOWED_STATUS.contains(s)) {
                throw new IllegalArgumentException("Unknown finding status '" + patch.status()
                        + "'. Allowed: " + ALLOWED_STATUS);
            }
            f.setStatus(s);
        }
        return ResponseEntity.ok(findingRepository.save(f));
    }

    public record FindingPatch(String status) {}
}
