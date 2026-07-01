package ca.bnc.qe.veritas.snyk;

import java.time.Instant;

/** Dashboard view of a watched repo: identity + the latest snapshot's severity counts and last-polled time. */
public record SnykWatchView(
        String id,
        String orgId,
        String orgSlug,
        String orgName,
        String targetId,
        String repoSlug,
        boolean enabled,
        int critical,
        int high,
        int medium,
        int low,
        int fixable,
        int projectCount,
        Instant lastPolled) {
}
