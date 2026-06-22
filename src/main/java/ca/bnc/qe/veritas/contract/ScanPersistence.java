package ca.bnc.qe.veritas.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically persists a finished scan: the finding rows and the COMPLETED scan are written in a single
 * transaction (a separate bean so the {@code @Transactional} proxy actually applies — self-invocation from
 * ContractValidationService would be a no-op). If the JVM dies mid-scan, {@link ScanReconciler} recovers the
 * scan stuck in RUNNING on the next start.
 */
@Component
public class ScanPersistence {

    private final ScanRepository scanRepository;
    private final FindingRecordRepository findingRepository;

    public ScanPersistence(ScanRepository scanRepository, FindingRecordRepository findingRepository) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
    }

    /** Write the findings and the scan together so a reader never sees a COMPLETED scan with no findings. */
    @Transactional
    public void complete(Scan scan, List<Finding> findings, Map<String, JsonNode> enrich) {
        findingRepository.saveAll(toRecords(scan.getId(), findings, enrich));
        scanRepository.save(scan);
    }

    private List<FindingRecord> toRecords(String scanId, List<Finding> findings, Map<String, JsonNode> enrich) {
        List<FindingRecord> records = new ArrayList<>();
        for (Finding f : findings) {
            FindingRecord r = new FindingRecord();
            r.setScanId(scanId);
            r.setFingerprint(f.getFindingId());
            r.setType(f.getType().name());
            r.setLayer(f.getLayer() != null ? f.getLayer().name() : null);
            r.setSeverity(f.getSeverity().name());
            r.setConfidence(f.getConfidence() != null ? f.getConfidence().name() : null);
            r.setOrigin(f.getOrigin());
            r.setEndpoint(f.getEndpoint());
            r.setSpecSource(f.getSpecSource());
            r.setSummary(f.getSummary());
            r.setCurrentYamlFragment(f.getCurrentYamlFragment());
            r.setProposedFix(f.getProposedFix());
            r.setCitation(f.getCitation());
            r.setStatus(f.getStatus());
            SourceRef ref = f.getCodeEvidence();
            if (ref != null) {
                r.setCodeFile(ref.location());
                r.setCodeStartLine(ref.startLine());
                r.setCodeEndLine(ref.endLine());
                r.setCodeSnippet(ref.snippet());
            }
            JsonNode e = enrich.get(f.getFindingId());
            if (e != null) {
                if (e.hasNonNull("explanation")) {
                    r.setExplanation(e.get("explanation").asText());
                }
                if (e.hasNonNull("proposedFix")) {
                    r.setProposedFix(e.get("proposedFix").asText());
                }
                // citation is set deterministically (StandardsReference) — never from the LLM.
            }
            carryForwardStatus(r, scanId);
            records.add(r);
        }
        return records;
    }

    /** Carry a prior finding's triage status forward to the same fingerprint on a re-scan. */
    private void carryForwardStatus(FindingRecord r, String scanId) {
        if (r.getFingerprint() == null) {
            return;
        }
        findingRepository.findByFingerprintOrderByCreatedAtDesc(r.getFingerprint()).stream()
                .filter(prior -> !scanId.equals(prior.getScanId()))
                .filter(prior -> prior.getStatus() != null && !"OPEN".equals(prior.getStatus()))
                .findFirst()
                .ifPresent(prior -> r.setStatus(prior.getStatus()));
    }
}
