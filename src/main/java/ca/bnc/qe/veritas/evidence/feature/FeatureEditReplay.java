package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;

/**
 * Replays an ordered log of {@link FeatureEdit reviewer overrides} onto a <b>freshly re-extracted</b>
 * {@link FeatureIndexResult}, so that when the sources are re-run (code landed, a ticket moved) the reviewer's
 * renames / merges / pins are carried forward instead of being lost (design §3.2 lineage re-run).
 *
 * <p>Deterministic and $0 — pure index surgery, no LLM. Each edit is re-targeted by <b>unit-id overlap</b>, not by
 * the volatile content-derived {@code featureId}: the units of an unchanged endpoint/ticket keep their ids across a
 * re-extraction, so the edit re-finds its feature(s) even when re-clustering split or renamed them. Edits are
 * applied in their recorded order, so a rename or pin that followed a merge re-targets the just-merged feature.
 *
 * <p>What can't be re-applied (its units all vanished from the new extraction, or a merge's features are no longer
 * separable) is <b>skipped and reported</b> in {@link Outcome#notes} — never guessed at — so the reviewer is told
 * exactly which of their edits the new code/tickets invalidated rather than silently losing them.
 */
public final class FeatureEditReplay {

    private FeatureEditReplay() {}

    /**
     * @param result the carried-forward result (fresh index with the edits re-applied + freshly-detected gaps)
     * @param pins   the carried-forward pinned feature ids (re-targeted onto the fresh features)
     * @param notes  human-readable lines for any edit that could not be re-applied (empty if all carried cleanly)
     */
    public record Outcome(FeatureIndexResult result, Set<String> pins, List<String> notes) {}

    /** Carry {@code edits} forward onto the freshly-built {@code fresh} result, re-detecting gaps over the result. */
    public static Outcome apply(FeatureIndexResult fresh, List<FeatureEdit> edits, GapDetector gapDetector) {
        FeatureIndex idx = fresh.index();
        Map<String, EvidenceUnit> unitsById = idx.unitsById();
        Map<String, Feature> features = new LinkedHashMap<>(idx.features());   // mutable working copy, order-preserving
        Set<String> pins = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        for (FeatureEdit edit : edits == null ? List.<FeatureEdit>of() : edits) {
            switch (edit.kind()) {
                case MERGE -> applyMerge(edit, features, unitsById, pins, notes);
                case RENAME -> applyRename(edit, features, notes);
                case PIN -> applyPin(edit, features, pins, notes);
            }
        }

        FeatureIndex edited = new FeatureIndex(features, unitsById, idx.crossCuttingIds(), idx.unassignedUnitIds(),
                idx.mix(), idx.sourceDigest());
        return new Outcome(new FeatureIndexResult(edited, gapDetector.detect(edited), fresh.extraction()), pins, notes);
    }

    private static void applyMerge(FeatureEdit edit, Map<String, Feature> features,
                                   Map<String, EvidenceUnit> unitsById, Set<String> pins, List<String> notes) {
        // Every still-present unit the reviewer had merged, and the fresh features that now hold them.
        Set<String> wanted = new LinkedHashSet<>();
        for (String u : edit.allUnitIds()) {
            if (unitsById.containsKey(u)) {
                wanted.add(u);
            }
        }
        LinkedHashSet<String> targetIds = featuresIntersecting(features, wanted);

        if (targetIds.size() < 2) {
            // The merged features didn't survive as separable features (one or both gone, or already one feature).
            // If exactly one remains and the reviewer named the merge, keep that label on it — the closest we can
            // honour their intent — otherwise report the merge as un-replayable.
            if (targetIds.size() == 1 && edit.name() != null && !edit.name().isBlank()) {
                renameFeature(features, targetIds.iterator().next(), edit.name());
            } else {
                notes.add("Couldn't re-apply a merge (\"" + edit.name() + "\"): those features are no longer "
                        + "separate features in the new extraction.");
            }
            return;
        }

        // Union the surviving member units (sorted, stable) → recompute the content-derived id, mirroring the seeder.
        Set<String> unitIdSet = new TreeSet<>();
        for (String id : targetIds) {
            unitIdSet.addAll(features.get(id).unitIds());
        }
        List<String> mergedUnitIds = List.copyOf(unitIdSet);
        String mergedId = "feat-" + EvidenceId.hash8(String.join("|", mergedUnitIds));

        List<EvidenceUnit> mergedUnits = new ArrayList<>();
        for (String uid : mergedUnitIds) {
            EvidenceUnit u = unitsById.get(uid);
            if (u != null) {
                mergedUnits.add(u);
            }
        }
        String mergedName = (edit.name() != null && !edit.name().isBlank())
                ? edit.name().strip() : largestName(features, targetIds);
        Feature merged = new Feature(mergedId, mergedName, mergedUnitIds, FeatureStatusEngine.statusOf(mergedUnits));

        // Transparency: merging whole features means inheriting their full membership. If the new extraction had
        // grouped units the reviewer never saw into a target feature, the merge absorbs them — surface that rather
        // than silently growing the merged feature (a unit-cluster feature can't be partially merged).
        Set<String> recorded = new LinkedHashSet<>(edit.allUnitIds());
        if (mergedUnitIds.stream().anyMatch(u -> !recorded.contains(u))) {
            notes.add("Re-applied a merge (\"" + mergedName + "\"): the new extraction had grouped additional units "
                    + "into those features, so the merged feature now includes them.");
        }

        // A merged feature is pinned iff any of its sources was pinned (mirrors the live merge).
        boolean wasPinned = targetIds.stream().anyMatch(pins::contains);
        pins.removeAll(targetIds);
        if (wasPinned) {
            pins.add(mergedId);
        }

        // Rebuild the map: drop the merged-away features; drop the merged id if it already existed elsewhere (so the
        // union can't collide with a pre-existing feature of the same membership); place the new one at the first slot.
        Map<String, Feature> rebuilt = new LinkedHashMap<>();
        boolean placed = false;
        for (Map.Entry<String, Feature> e : features.entrySet()) {
            if (targetIds.contains(e.getKey())) {
                if (!placed) {
                    rebuilt.put(mergedId, merged);
                    placed = true;
                }
            } else if (!e.getKey().equals(mergedId)) {
                rebuilt.put(e.getKey(), e.getValue());
            }
        }
        features.clear();
        features.putAll(rebuilt);
    }

    private static void applyRename(FeatureEdit edit, Map<String, Feature> features, List<String> notes) {
        List<String> recorded = edit.unitGroups().isEmpty() ? List.of() : edit.unitGroups().get(0);
        String fid = bestOverlap(features, recorded);
        if (fid == null) {
            notes.add("Couldn't re-apply a rename (\"" + edit.name() + "\"): that feature is no longer present in "
                    + "the new extraction.");
            return;
        }
        renameFeature(features, fid, edit.name());
        if (splitAcrossFeatures(features, recorded)) {
            notes.add("Re-applied a rename (\"" + edit.name() + "\") to the largest remaining part — that feature was "
                    + "split across several features in the new extraction.");
        }
    }

    private static void applyPin(FeatureEdit edit, Map<String, Feature> features, Set<String> pins, List<String> notes) {
        List<String> recorded = edit.unitGroups().isEmpty() ? List.of() : edit.unitGroups().get(0);
        String fid = bestOverlap(features, recorded);
        if (fid == null) {
            notes.add("Couldn't re-apply a pin: that feature is no longer present in the new extraction.");
            return;
        }
        if (Boolean.TRUE.equals(edit.pinned())) {
            pins.add(fid);
        } else {
            pins.remove(fid);
        }
        if (splitAcrossFeatures(features, recorded)) {
            notes.add("Re-applied a pin to the largest remaining part — that feature was split across several "
                    + "features in the new extraction.");
        }
    }

    private static void renameFeature(Map<String, Feature> features, String fid, String name) {
        Feature f = features.get(fid);
        if (f != null && name != null && !name.isBlank()) {
            features.put(fid, new Feature(f.featureId(), name.strip(), f.unitIds(), f.status()));
        }
    }

    /** The fresh features (in map order) whose membership intersects any of {@code wanted}. */
    private static LinkedHashSet<String> featuresIntersecting(Map<String, Feature> features, Set<String> wanted) {
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (Map.Entry<String, Feature> e : features.entrySet()) {
            for (String u : e.getValue().unitIds()) {
                if (wanted.contains(u)) {
                    hits.add(e.getKey());
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * The fresh feature that best inherited a recorded feature's units: the most overlapping units, and on a tie the
     * <b>smallest</b> feature — i.e. the one whose membership is most purely the original (highest overlap ratio),
     * which avoids re-targeting a rename/pin onto an unrelated feature that merely shares one unit. Null if none overlap.
     */
    private static String bestOverlap(Map<String, Feature> features, List<String> wantedUnits) {
        Set<String> wanted = new LinkedHashSet<>(wantedUnits);
        String best = null;
        int bestCount = 0;
        int bestSize = Integer.MAX_VALUE;
        for (Map.Entry<String, Feature> e : features.entrySet()) {
            int count = 0;
            for (String u : e.getValue().unitIds()) {
                if (wanted.contains(u)) {
                    count++;
                }
            }
            int size = e.getValue().unitIds().size();
            if (count > 0 && (count > bestCount || (count == bestCount && size < bestSize))) {
                bestCount = count;
                bestSize = size;
                best = e.getKey();
            }
        }
        return best;
    }

    /** True when a recorded feature's units are now spread across more than one fresh feature (it was split). */
    private static boolean splitAcrossFeatures(Map<String, Feature> features, List<String> recordedUnits) {
        return featuresIntersecting(features, new LinkedHashSet<>(recordedUnits)).size() > 1;
    }

    /** The display name of the largest (most-units) of the target features — the most representative default. */
    private static String largestName(Map<String, Feature> features, Set<String> ids) {
        String name = null;
        int max = -1;
        for (String id : ids) {
            Feature f = features.get(id);
            if (f != null && f.unitIds().size() > max) {
                max = f.unitIds().size();
                name = f.displayName();
            }
        }
        return name;
    }
}
