package ca.bnc.qe.veritas.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.contract.FindingMapper;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.report.ContractReportRenderer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the management report HTML. It re-renders LIVE from the persisted findings so the report always reflects
 * the current disposition (accept/reject recorded in the dashboard), falling back to the as-scanned file on disk. */
@RestController
@RequestMapping("/api/v1")
public class ReportController {

    /** Scan ids are UUIDs; constrain to a safe charset so {id} can never escape the report directory. */
    private static final java.util.regex.Pattern SAFE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final ScanRepository scanRepository;
    private final FindingRecordRepository findingRepository;
    private final ContractReportRenderer renderer;
    private final ObjectMapper objectMapper;

    public ReportController(ScanRepository scanRepository, FindingRecordRepository findingRepository,
                            ContractReportRenderer renderer, ObjectMapper objectMapper) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/scans/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(@PathVariable String id) throws Exception {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            return ResponseEntity.notFound().build();   // reject path-traversal / unexpected ids outright
        }
        // Live view: re-render from persisted findings so a disposition recorded after the scan shows up.
        Scan scan = scanRepository.findById(id).orElse(null);
        if (scan != null) {
            // Deterministic order (severity asc) so the live report is reproducible, not in raw DB order.
            List<FindingRecord> records = findingRepository.findByScanIdOrderBySeverityAsc(id);
            if (!records.isEmpty()) {
                List<Finding> findings = records.stream().map(FindingMapper::toFinding).toList();
                return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                        .body(renderer.renderHtml(scan, findings, frenchMap(scan)));
            }
        }
        // Fallback: the as-scanned HTML written at scan time (older scans, or no persisted findings yet).
        Path base = Path.of("out").toAbsolutePath().normalize();
        Path file = base.resolve("contract-report-" + id + ".html").normalize();
        // Defence in depth: the resolved file must stay under the report directory even if the charset check changed.
        if (!file.startsWith(base) || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(Files.readString(file));
    }

    /** The EN→FR translation map captured at scan time, so the live re-render stays bilingual (empty if none). */
    private Map<String, String> frenchMap(Scan scan) {
        if (scan.getTranslationsJson() == null || scan.getTranslationsJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(scan.getTranslationsJson(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
