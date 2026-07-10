package ca.bnc.qe.veritas.evolve;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.finding.FindingType;

/**
 * A computed rollup of the reconcile LLM's disputed findings for one {@link FindingType} — the precision half of the
 * engine's learning debt. Read-only + derived (no table): the aggregation dedupes by fingerprint so {@code count}
 * reconciles with the disputed KPI, and a maintainer can record a per-finding verdict (was the dispute right?).
 * {@code verdictBreakdown} tallies those verdicts; a high {@code NEEDS_DETECTION_FIX} count ranks a type for the
 * Channel-2 detection backlog. A few {@link Example}s back the drill-down + a deep-link to the finding in its scan.
 *
 * @param count            distinct disputed fingerprints of this type (the type's share of the disputed KPI).
 * @param distinctServices how many services this type is disputed across.
 * @param verdictBreakdown maintainer verdicts recorded so far, by verdict name → count (empty until any are set).
 */
public record DisputeCluster(
        FindingType findingType,
        int count,
        int distinctServices,
        Map<String, Integer> verdictBreakdown,
        List<Example> examples) {

    /**
     * One representative disputed finding for the drill-down. {@code id} is the finding row id (the PATCH target for
     * recording a verdict); {@code scanId} deep-links to {@code /findings/{scanId}} in the dashboard.
     */
    public record Example(
            String id,
            String scanId,
            String service,
            String endpoint,
            String summary,
            String reason,
            String verdict) {
    }
}
