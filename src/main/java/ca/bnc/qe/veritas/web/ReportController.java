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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public ResponseEntity<String> report(@PathVariable String id,
                                         @RequestParam(name = "download", defaultValue = "false") boolean download)
            throws Exception {
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
                return html(renderer.renderHtml(scan, findings, frenchMap(scan)), download, scan);
            }
        }
        // Fallback: the as-scanned HTML written at scan time (older scans, or no persisted findings yet). Try the
        // human-readable name (reconstructed from the scan) first, then the legacy UUID name for older reports.
        Path base = Path.of("out").toAbsolutePath().normalize();
        Path file = null;
        if (scan != null) {
            Path named = base.resolve(ca.bnc.qe.veritas.report.ReportNaming.baseName(scan) + ".html").normalize();
            if (named.startsWith(base) && Files.exists(named)) {
                file = named;
            }
        }
        if (file == null) {
            Path legacy = base.resolve("contract-report-" + id + ".html").normalize();   // pre-rename reports
            if (legacy.startsWith(base) && Files.exists(legacy)) {
                file = legacy;
            }
        }
        // Defence in depth: the resolved file must stay under the report directory even if the charset check changed.
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return html(Files.readString(file), download, scan);
    }

    /** HTML body, served inline by default or as a named download when {@code download=true}. The report's
     *  accept/reject controls are self-contained JS, so a downloaded copy stays fully interactive offline. */
    private ResponseEntity<String> html(String body, boolean download, Scan scan) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.TEXT_HTML);
        if (download) {
            String name = (scan != null ? ca.bnc.qe.veritas.report.ReportNaming.baseName(scan) : "contract-report")
                    + ".html";
            b.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"");
        }
        return b.body(body);
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
