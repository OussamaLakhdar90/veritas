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
        // Carry-forward dispositions in ONE query for the whole scan (was an N+1: one lookup per finding). Newest
        // first → keep the first row per fingerprint as the most recent disposition.
        List<String> fingerprints = findings.stream().map(Finding::getFindingId).filter(Objects::nonNull).distinct().toList();
        Map<String, FindingRecord> priorByFingerprint = new HashMap<>();
        if (!fingerprints.isEmpty()) {
            for (FindingRecord prior : findingRepository.findPriorDispositions(fingerprints, scanId)) {
                priorByFingerprint.putIfAbsent(prior.getFingerprint(), prior);
            }
        }
        // Locus fallback: carry a disposition forward when a finding's summary (hence fingerprint) changed but the
        // logical defect (type+endpoint+specSource) is the same — but ONLY when the locus is unambiguous on BOTH sides
        // (exactly one current finding and one prior disposition share it), so it can never land on the wrong finding.
        Map<String, Long> currentLocusCounts = new HashMap<>();
        for (Finding f : findings) {
            currentLocusCounts.merge(locusKey(f), 1L, Long::sum);
        }
        Map<String, FindingRecord> priorByLocus = new HashMap<>();
        java.util.Set<String> ambiguousLocus = new java.util.HashSet<>();
        List<String> locusKeys = findings.stream().map(this::locusKey).distinct().toList();
        if (!locusKeys.isEmpty()) {
            for (FindingRecord prior : findingRepository.findPriorDispositionsByLocus(locusKeys, scanId)) {
                if (prior.getLocusKey() == null) {
                    continue;
                }
                if (priorByLocus.putIfAbsent(prior.getLocusKey(), prior) != null) {
                    ambiguousLocus.add(prior.getLocusKey());   // 2+ prior dispositions share this locus → can't choose
                }
            }
        }
        List<FindingRecord> records = new ArrayList<>();
        for (Finding f : findings) {
            FindingRecord r = new FindingRecord();
            r.setScanId(scanId);
            r.setFingerprint(f.getFindingId());
            r.setLocusKey(locusKey(f));
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
            FindingRecord prior = priorByFingerprint.get(r.getFingerprint());
            if (prior == null) {
                // No exact match — try the locus fallback, but only when it's unambiguous on both sides.
                String lk = r.getLocusKey();
                if (currentLocusCounts.getOrDefault(lk, 0L) == 1L && !ambiguousLocus.contains(lk)) {
                    prior = priorByLocus.get(lk);
                }
            }
            carryForwardStatus(r, prior);
            records.add(r);
        }
        return records;
    }

    /** The logical-defect locus (type + endpoint + specSource) — the fingerprint minus the volatile summary. */
    private String locusKey(Finding f) {
        return Integer.toHexString(Objects.hash(
                f.getType() == null ? "" : f.getType().name(),
                f.getEndpoint() == null ? "" : f.getEndpoint(),
                f.getSpecSource() == null ? "" : f.getSpecSource()));
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
