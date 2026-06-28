package ca.bnc.qe.veritas.defect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.persistence.DefectLink;

/**
 * Aggregate defect metrics for the dashboard: totals, open vs closed, and distributions by severity, Jira status
 * category, and service (per-service density). Computed deterministically from the linked defects.
 *
 * <p>Note: Defect Removal Efficiency (DRE) is intentionally NOT reported — it needs post-release defect counts that
 * Veritas does not hold, so reporting it from validation findings alone would be misleading.
 */
public record DefectMetrics(long total, long open, long closed,
                            Map<String, Long> bySeverity,
                            Map<String, Long> byStatusCategory,
                            Map<String, Long> byService) {

    public static DefectMetrics of(List<DefectLink> links) {
        long total = links.size();
        long closed = links.stream().filter(d -> "done".equalsIgnoreCase(nz(d.getJiraStatusCategory(), ""))).count();
        return new DefectMetrics(total, total - closed, closed,
                group(links, d -> nz(d.getSeverity(), "UNKNOWN")),
                group(links, d -> nz(d.getJiraStatusCategory(), "unsynced")),
                group(links, d -> nz(d.getServiceName(), "unknown")));
    }

    private static Map<String, Long> group(List<DefectLink> links, Function<DefectLink, String> key) {
        return links.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.counting()));
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
