package ca.bnc.qe.veritas.evidence.feature;

import java.util.List;

/**
 * One reviewer override recorded as a replayable operation (design §3.2 — "the override layer survives a
 * re-extraction"). The §6 wizard's edits — {@link Kind#RENAME rename}, {@link Kind#MERGE merge},
 * {@link Kind#PIN pin} — are applied in place for the current snapshot AND appended here as an ordered log, so
 * that when the code/Jira/Confluence sources are re-extracted (code landed, a ticket moved) the reviewer's work
 * can be carried forward onto the fresh feature index instead of being lost.
 *
 * <p>The crux: a feature's {@code featureId} is content-derived (a hash of its member unit ids), so it changes
 * the moment the underlying evidence changes — it is <b>not</b> a durable handle for an edit. So an edit is keyed
 * by the <b>member unit ids</b> of the feature(s) it touched ({@code unitGroups}); on re-extraction the engine
 * re-finds the affected feature(s) by unit-id overlap (units of an <i>unchanged</i> endpoint/ticket keep their
 * content-derived ids), which is robust to re-clustering. A {@code RENAME}/{@code PIN} has exactly one group (the
 * renamed/pinned feature's units); a {@code MERGE} has one group per source feature it combined.
 *
 * <p>Recording the operation explicitly — rather than diffing the edited names against a pristine index — is what
 * makes carry-forward unambiguous: a name in the index could be the reviewer's rename or the tagger's original,
 * and only the log knows which.
 *
 * @param kind       which override this is
 * @param unitGroups the member unit ids of the affected feature(s): one group for rename/pin, one per source
 *                   feature for merge
 * @param name       the new/merged display name (rename, merge); {@code null} for pin
 * @param pinned     {@code true} to pin / {@code false} to unpin (pin); {@code null} otherwise
 */
public record FeatureEdit(Kind kind, List<List<String>> unitGroups, String name, Boolean pinned) {

    public enum Kind { RENAME, MERGE, PIN }

    public FeatureEdit {
        unitGroups = unitGroups == null ? List.of()
                : unitGroups.stream().map(g -> g == null ? List.<String>of() : List.copyOf(g)).toList();
    }

    public static FeatureEdit rename(List<String> unitIds, String name) {
        return new FeatureEdit(Kind.RENAME, List.of(unitIds), name, null);
    }

    public static FeatureEdit merge(List<List<String>> sourceUnitGroups, String name) {
        return new FeatureEdit(Kind.MERGE, sourceUnitGroups, name, null);
    }

    public static FeatureEdit pin(List<String> unitIds, boolean pinned) {
        return new FeatureEdit(Kind.PIN, List.of(unitIds), null, pinned);
    }

    /** Every member unit id this edit references, flattened across its groups. */
    public List<String> allUnitIds() {
        return unitGroups.stream().flatMap(List::stream).toList();
    }
}
