package ca.bnc.qe.veritas.evolve;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository.ClassificationVoteRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Computes Engine-Evolution classification proposals from the field signal: aggregates human severity votes on
 * still-{@code UNSPECIFIED} findings (per type, across services), enforces the evidence BAR (min votes across min
 * distinct services), and asks {@link ClassificationAdvisor} to SUGGEST a severity by the rubric. The aggregation
 * is deterministic + read-only; the AI adds only the suggestion + rationale (and degrades to the field consensus
 * when offline). Below the bar a type yields no proposal — thin/one-service signal never drives a code change.
 */
@Service
public class ClassificationProposalService {

    private final FindingRecordRepository findingRepository;
    private final ClassificationAdvisor advisor;
    private final int minVotes;
    private final int minDistinctServices;

    public ClassificationProposalService(FindingRecordRepository findingRepository, ClassificationAdvisor advisor,
                                         @Value("${veritas.evolve.min-votes:3}") int minVotes,
                                         @Value("${veritas.evolve.min-distinct-services:2}") int minDistinctServices) {
        this.findingRepository = findingRepository;
        this.advisor = advisor;
        this.minVotes = minVotes;
        this.minDistinctServices = minDistinctServices;
    }

    /** One proposal per pending FindingType whose field evidence meets the bar. */
    public List<ClassificationProposal> computeProposals(String owner) {
        Map<FindingType, Map<Severity, Integer>> votesByType = new EnumMap<>(FindingType.class);
        Map<FindingType, Set<String>> servicesByType = new EnumMap<>(FindingType.class);
        for (ClassificationVoteRow row : findingRepository.findUnspecifiedClassificationVotes()) {
            FindingType type = parse(FindingType.class, row.getType());
            Severity sev = parse(Severity.class, row.getSeverity());
            if (type == null || sev == null) {
                continue;
            }
            votesByType.computeIfAbsent(type, k -> new EnumMap<>(Severity.class))
                    .merge(sev, (int) row.getVotes(), Integer::sum);
            servicesByType.computeIfAbsent(type, k -> new HashSet<>()).add(row.getService());
        }

        List<ClassificationProposal> proposals = new ArrayList<>();
        for (Map.Entry<FindingType, Map<Severity, Integer>> e : votesByType.entrySet()) {
            FindingType type = e.getKey();
            Map<Severity, Integer> breakdown = e.getValue();
            int voteCount = breakdown.values().stream().mapToInt(Integer::intValue).sum();
            int services = servicesByType.getOrDefault(type, Set.of()).size();
            if (voteCount < minVotes || services < minDistinctServices) {
                continue;
            }
            Severity consensus = majority(breakdown);
            ClassificationAdvisor.Suggestion s = advisor.suggest(type, breakdown, services, owner);
            Severity suggested = s.available() && s.severity() != null ? s.severity() : consensus;
            String rationale = s.available()
                    ? s.rationale()
                    : "AI unavailable — suggestion defaulted to the field consensus (" + consensus + " on "
                            + voteCount + " votes across " + services + " services).";
            proposals.add(new ClassificationProposal(type, suggested, s.available(), rationale, voteCount, services,
                    breakdown));
        }
        return proposals;
    }

    private static Severity majority(Map<Severity, Integer> breakdown) {
        return breakdown.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Severity.UNSPECIFIED);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
