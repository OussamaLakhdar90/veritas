package ca.bnc.qe.veritas.evolve;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository.DisputeRow;
import org.springframework.stereotype.Service;

/**
 * Rolls the reconcile LLM's disputed findings up by {@link FindingType} — the precision half of the learning debt
 * (Channel 2). Deterministic + read-only: it dedupes by fingerprint (newest wins) so each type's {@code count}
 * reconciles with the disputed KPI, tallies any maintainer verdicts, and keeps a few examples for the drill-down.
 * This is a triage/visibility surface only; it never changes engine behaviour. Mirrors
 * {@link ClassificationProposalService}'s aggregation, minus the evidence bar — every disputed type is shown,
 * however thin the signal, because triage should hide nothing.
 */
@Service
public class DisputeClusterService {

    /** How many representative findings to carry per type for the drill-down (a hot type can have hundreds). */
    static final int MAX_EXAMPLES = 5;

    private final FindingRecordRepository findingRepository;

    public DisputeClusterService(FindingRecordRepository findingRepository) {
        this.findingRepository = findingRepository;
    }

    /** One cluster per disputed FindingType, most-disputed first. */
    public List<DisputeCluster> computeClusters() {
        Map<FindingType, Integer> countByType = new EnumMap<>(FindingType.class);
        Map<FindingType, Set<String>> servicesByType = new EnumMap<>(FindingType.class);
        Map<FindingType, Map<String, Integer>> verdictsByType = new EnumMap<>(FindingType.class);
        Map<FindingType, List<DisputeCluster.Example>> examplesByType = new EnumMap<>(FindingType.class);
        Set<String> seenFingerprints = new HashSet<>();
        // Rows arrive newest-first; keep only the LATEST row per fingerprint so a finding re-disputed across re-scans
        // counts once (one finding = one dispute) and carries its most recent verdict.
        for (DisputeRow row : findingRepository.findDisputedRows()) {
            String fp = row.getFingerprint();
            if (fp != null && !seenFingerprints.add(fp)) {
                continue;
            }
            FindingType type = parse(row.getType());
            if (type == null) {
                continue;
            }
            countByType.merge(type, 1, Integer::sum);
            if (row.getService() != null && !row.getService().isBlank()) {
                servicesByType.computeIfAbsent(type, k -> new HashSet<>()).add(row.getService());
            }
            if (row.getVerdict() != null && !row.getVerdict().isBlank()) {
                verdictsByType.computeIfAbsent(type, k -> new LinkedHashMap<>())
                        .merge(row.getVerdict(), 1, Integer::sum);
            }
            List<DisputeCluster.Example> examples = examplesByType.computeIfAbsent(type, k -> new ArrayList<>());
            if (examples.size() < MAX_EXAMPLES) {
                examples.add(new DisputeCluster.Example(row.getId(), row.getScanId(), row.getService(),
                        row.getEndpoint(), row.getSummary(), row.getReason(), row.getVerdict()));
            }
        }

        List<DisputeCluster> clusters = new ArrayList<>();
        for (Map.Entry<FindingType, Integer> e : countByType.entrySet()) {
            FindingType type = e.getKey();
            clusters.add(new DisputeCluster(type, e.getValue(),
                    servicesByType.getOrDefault(type, Set.of()).size(),
                    verdictsByType.getOrDefault(type, Map.of()),
                    examplesByType.getOrDefault(type, List.of())));
        }
        // Worst precision offenders lead the triage list.
        clusters.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return clusters;
    }

    private static FindingType parse(String name) {
        if (name == null) {
            return null;
        }
        try {
            return FindingType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
