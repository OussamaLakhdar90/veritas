package ca.bnc.qe.veritas.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.settings.CurrentUser;
import ca.bnc.qe.veritas.vcs.BitbucketLinkBuilder;
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
    private final BitbucketLinkBuilder linkBuilder;

    public FindingsController(ScanRepository scanRepository, FindingRecordRepository findingRepository,
                             CurrentUser currentUser, BitbucketLinkBuilder linkBuilder) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
        this.currentUser = currentUser;
        this.linkBuilder = linkBuilder;
    }

    /** Scans, newest first. Optionally scope to one service ({@code ?service=ciam-policies}). */
    @GetMapping("/scans")
    public List<Scan> scans(@RequestParam(required = false) String service) {
        return (service == null || service.isBlank())
                ? scanRepository.findAllByOrderByStartedAtDesc()
                : scanRepository.findByServiceNameOrderByStartedAtDesc(service);
    }

    /** Daily scan activity over the last {@code days} (zero-filled, oldest→newest) — backs the findings trend + weekly delta. */
    @GetMapping("/scans/trend")
    public List<ScanTrendPoint> scansTrend(@RequestParam(defaultValue = "30") int days) {
        int d = Math.max(1, Math.min(days, 365));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(d - 1L);
        Map<LocalDate, int[]> acc = new HashMap<>();   // [0] = scans, [1] = findings
        for (Scan s : scanRepository.findAll()) {
            Instant st = s.getStartedAt();
            if (st == null) {
                continue;
            }
            LocalDate day = st.atZone(ZoneOffset.UTC).toLocalDate();
            if (day.isBefore(from) || day.isAfter(today)) {
                continue;
            }
            int[] a = acc.computeIfAbsent(day, k -> new int[2]);
            a[0] += 1;
            a[1] += s.getTotalFindings();
        }
        List<ScanTrendPoint> out = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            int[] a = acc.getOrDefault(day, new int[2]);
            out.add(new ScanTrendPoint(day.toString(), a[0], a[1]));
        }
        return out;
    }

    /** One day of scan activity. */
    public record ScanTrendPoint(String date, int scans, int findings) {}

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
        List<FindingRecord> findings = findingRepository.findByScanId(id).stream()
                .filter(f -> matches(severity, f.getSeverity()))
                .filter(f -> matches(layer, f.getLayer()))
                .filter(f -> matches(status, f.getStatus()))
                .toList();
        // Decorate each finding with a clickable Bitbucket deep link to its code evidence (best-effort, no network).
        Scan scan = scanRepository.findById(id).orElse(null);
        if (scan != null) {
            for (FindingRecord f : findings) {
                linkBuilder.fileLink(scan.getAppId(), scan.getRepoSlug(), scan.getGitRef(),
                                f.getCodeFile(), f.getCodeStartLine())
                        .ifPresent(f::setCodeUrl);
            }
        }
        return findings;
    }

    /** Case-insensitive equality facet; a null/blank filter matches everything. */
    private static boolean matches(String filter, String value) {
        return filter == null || filter.isBlank()
                || (value != null && value.equalsIgnoreCase(filter));
    }

    private static final Set<String> ALLOWED_STATUS =
            Set.of("OPEN", "TRIAGED", "ACCEPTED", "REJECTED", "JIRA_CREATED", "FIXED", "WONT_FIX", "FALSE_POSITIVE");

    /** Severities a human may override a finding to — NOT UNSPECIFIED, which is the engine's fail-safe, not a choice. */
    private static final Set<String> ALLOWED_SEVERITY = Set.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO");

    /**
     * Disposition a finding: set its status (ACCEPTED / REJECTED / TRIAGED / WONT_FIX / FALSE_POSITIVE …) and/or a
     * human severity OVERRIDE. This is the system of record — it captures WHO acted, WHEN, and an optional WHY for an
     * audit trail; both the disposition and the severity override are carried forward to the same finding on the next
     * scan. The override wins over the engine severity at the release gate (breaking-ness stays type-derived, so an
     * override can never hide a consumer-breaking change).
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
        if (patch.severity() != null) {
            String sv = patch.severity().toUpperCase(java.util.Locale.ROOT);
            if (!ALLOWED_SEVERITY.contains(sv)) {
                throw new IllegalArgumentException("Unknown severity '" + patch.severity()
                        + "'. Allowed: " + ALLOWED_SEVERITY);
            }
            f.setUserSeverity(sv);
            f.setReviewedBy(currentUser.principalId());
            f.setReviewedAt(java.time.Instant.now());
        }
        return ResponseEntity.ok(findingRepository.save(f));
    }

    public record FindingPatch(String status, String note, String severity) {}
}
