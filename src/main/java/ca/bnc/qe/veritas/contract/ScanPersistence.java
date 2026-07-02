package ca.bnc.qe.veritas.contract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomically persists a finished scan: the finding rows and the COMPLETED scan are written in a single
 * transaction (via {@link TransactionTemplate} so the bounded SQLITE_BUSY retry can sit OUTSIDE the
 * transaction — an {@code @Transactional} method can't retry itself). If the JVM dies mid-scan,
 * {@link ScanReconciler} recovers the scan stuck in RUNNING on the next start.
 */
@Component
public class ScanPersistence {

    private static final int MAX_ATTEMPTS = 3;

    private final ScanRepository scanRepository;
    private final FindingRecordRepository findingRepository;
    private final TransactionTemplate tx;

    public ScanPersistence(ScanRepository scanRepository, FindingRecordRepository findingRepository,
                           PlatformTransactionManager txManager) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Write the findings and the scan together so a reader never sees a COMPLETED scan with no findings.
     * Retries briefly on an SQLite write-lock collision: with concurrent scans (or a fix train) writing at the
     * same moment, the finishing writer can hit SQLITE_BUSY — without the retry a fully successful scan would
     * be reported FAILED with a raw database error. Each attempt is its own transaction (full rollback between
     * attempts), so a retry never duplicates rows.
     */
    public void complete(Scan scan, List<Finding> findings, Map<String, JsonNode> enrich) {
        for (int attempt = 1; ; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    findingRepository.saveAll(toRecords(scan.getId(), findings, enrich));
                    scanRepository.save(scan);
                });
                return;
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS || !isSqliteBusy(e)) {
                    throw e;
                }
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** SQLITE_BUSY surfaces as "database is locked" / "SQLITE_BUSY" somewhere in the cause chain. */
    static boolean isSqliteBusy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("SQLITE_BUSY") || m.contains("database is locked"))) {
                return true;
            }
        }
        return false;
    }

    private List<FindingRecord> toRecords(String scanId, List<Finding> findings, Map<String, JsonNode> enrich) {
        // Carry-forward dispositions in ONE query for the whole scan (was an N+1: one lookup per finding). Newest
        // first → keep the first row per fingerprint as the most recent disposition.
        List<String> fingerprints = findings.stream().map(Finding::getFindingId).filter(Objects::nonNull).distinct().toList();
        Map<String, FindingRecord> priorByFingerprint = new HashMap<>();
        if (!fingerprints.isEmpty()) {
            for (FindingRecord prior : findingRepository.findPriorDispositions(fingerprints, scanId)) {
                priorByFingerprint.putIfAbsent(prior.getFingerprint(), prior);
            }
        }
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
            r.setAffectedEndpoints(f.getAffectedEndpoints() == null || f.getAffectedEndpoints().size() <= 1
                    ? null : String.join(",", f.getAffectedEndpoints()));
            r.setSpecSource(f.getSpecSource());
            r.setSummary(f.getSummary());
            r.setCurrentYamlFragment(f.getCurrentYamlFragment());
            r.setProposedFix(f.getProposedFix());
            r.setCitation(f.getCitation());
            r.setStatus(f.getStatus());
            r.setAiDisputed(f.isAiDisputed());
            r.setAiDisputeReason(f.getAiDisputeReason());
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
            carryForwardStatus(r, priorByFingerprint.get(r.getFingerprint()));
            records.add(r);
        }
        return records;
    }

    /** Carry a prior finding's disposition (status + who/when/why audit) forward to the same fingerprint on a re-scan. */
    private void carryForwardStatus(FindingRecord r, FindingRecord prior) {
        if (prior == null) {
            return;
        }
        r.setStatus(prior.getStatus());
        r.setReviewedBy(prior.getReviewedBy());
        r.setReviewedAt(prior.getReviewedAt());
        r.setReviewNote(prior.getReviewNote());
    }
}
