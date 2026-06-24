package ca.bnc.qe.veritas.evidence;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What each source fetch actually yielded, so a partial failure degrades gracefully instead of aborting the run,
 * and the realised {@link SourceMix} is computed from successes (design §1.3). Ingestion fetches each issue / page
 * / file independently; a failure is recorded here (and as a blind spot), not thrown.
 */
public record FetchProvenance(Map<SourceKind, Counts> bySource) {

    /**
     * @param requested how many items were asked for from this source
     * @param fetched   how many came back usable
     * @param failed    human-readable reasons for the items that failed (for the blind-spot banner)
     */
    public record Counts(int requested, int fetched, List<String> failed) {
        public Counts {
            failed = failed == null ? List.of() : List.copyOf(failed);
        }

        public boolean anyFetched() {
            return fetched > 0;
        }
    }

    public FetchProvenance {
        bySource = bySource == null ? Map.of() : Map.copyOf(bySource);
    }

    public boolean fetchedAny(SourceKind kind) {
        Counts c = bySource.get(kind);
        return c != null && c.anyFetched();
    }

    /**
     * A source the user requested ({@code requested > 0}) that fetched nothing — the §1.3 hard-fail trigger
     * (a selected source returning zero usable units should stop the run before any spend, not silently degrade).
     */
    public boolean requestedButEmpty(SourceKind kind) {
        Counts c = bySource.get(kind);
        return c != null && c.requested() > 0 && c.fetched() == 0;
    }

    /**
     * The run-level §1.3 gate: did any <b>user-selected</b> source come back empty? {@code POLICY} is excluded —
     * it is pre-authored, never user-selected, so it must never carry {@code requested > 0} and must not trip this.
     */
    public boolean hasHardFail(Set<SourceKind> selected) {
        if (selected == null) {
            return false;
        }
        return selected.stream().filter(k -> k != SourceKind.POLICY).anyMatch(this::requestedButEmpty);
    }

    /** The realised mix — true only for sources that actually returned usable evidence. */
    public SourceMix toMix() {
        return new SourceMix(
                fetchedAny(SourceKind.CODE),
                fetchedAny(SourceKind.JIRA),
                fetchedAny(SourceKind.CONFLUENCE));
    }
}
