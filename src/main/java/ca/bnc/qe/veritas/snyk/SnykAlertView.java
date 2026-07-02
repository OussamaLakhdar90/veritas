package ca.bnc.qe.veritas.snyk;

import java.time.Instant;

/** Dashboard view of a Snyk alert — a stable wire contract that doesn't leak the entity's audit columns. */
public record SnykAlertView(String id, String watchId, String orgSlug, String repoSlug, String severity,
                            String message, boolean seen, Instant createdAt) {

    static SnykAlertView of(SnykAlert a) {
        return new SnykAlertView(a.getId(), a.getWatchId(), a.getOrgSlug(), a.getRepoSlug(),
                a.getSeverity(), a.getMessage(), a.isSeen(), a.getCreatedAt());
    }
}
