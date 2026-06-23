package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.settings.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read API the dashboard consumes: contract-validation scans and their findings. */
@RestController
@RequestMapping("/api/v1")
public class FindingsController {

    private final ScanRepository scanRepository;
    private final FindingRecordRepository findingRepository;
    private final CurrentUser currentUser;

    public FindingsController(ScanRepository scanRepository, FindingRecordRepository findingRepository,
                             CurrentUser currentUser) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
        this.currentUser = currentUser;
    }

    /** Scans, newest first. Optionally scope to one service ({@code ?service=ciam-policies}). */
    @GetMapping("/scans")
    public List<Scan> scans(@RequestParam(required = false) String service) {
        return (service == null || service.isBlank())
                ? scanRepository.findAllByOrderByStartedAtDesc()
                : scanRepository.findByServiceNameOrderByStartedAtDesc(service);
    }

    /** One scan — its live {@code stage}/{@code status} drive the dashboard progress stepper while it runs. */
    @GetMapping("/scans/{id}")
    public ResponseEntity<Scan> scan(@PathVariable String id) {
        return scanRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** A scan's findings, optionally faceted by severity / layer / status (server-side filtering for scale). */
    @GetMapping("/scans/{id}/findings")
    public List<FindingRecord> findings(@PathVariable String id,
                                        @RequestParam(required = false) String severity,
                                        @RequestParam(required = false) String layer,
                                        @RequestParam(required = false) String status) {
        return findingRepository.findByScanId(id).stream()
                .filter(f -> matches(severity, f.getSeverity()))
                .filter(f -> matches(layer, f.getLayer()))
                .filter(f -> matches(status, f.getStatus()))
                .toList();
    }

    /** Case-insensitive equality facet; a null/blank filter matches everything. */
    private static boolean matches(String filter, String value) {
        return filter == null || filter.isBlank()
                || (value != null && value.equalsIgnoreCase(filter));
    }

    private static final Set<String> ALLOWED_STATUS =
            Set.of("OPEN", "TRIAGED", "ACCEPTED", "REJECTED", "JIRA_CREATED", "FIXED", "WONT_FIX", "FALSE_POSITIVE");

    /**
     * Disposition a finding: set its status (ACCEPTED / REJECTED / TRIAGED / WONT_FIX / FALSE_POSITIVE …). This is the
     * system of record — it captures WHO dispositioned it, WHEN, and an optional WHY for an audit trail, and the
     * disposition is carried forward to the same finding on the next scan.
     */
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
            f.setReviewedBy(currentUser.principalId());
            f.setReviewedAt(java.time.Instant.now());
            if (patch.note() != null) {
                f.setReviewNote(patch.note());
            }
        }
        return ResponseEntity.ok(findingRepository.save(f));
    }

    public record FindingPatch(String status, String note) {}
}
