package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.ExtractionResult;
import ca.bnc.qe.veritas.evidence.FetchProvenance;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage companion to {@link FeatureEditReplayTest}, exercising the uncovered edge cases of the §3.2
 * lineage carry-forward engine: bestOverlap ties/zero-overlap, merges that collapse to fewer than two surviving
 * targets (name-fallback vs un-replayable note), multi-group merges, absorbed-extra-units, pin/unpin onto split
 * features, the null/blank-name guards, and empty/null edit inputs. Assertions check concrete values (membership,
 * ids, display names, pins, notes) so they survive mutation testing.
 */
class FeatureEditReplayBranchTest {

    private static final String A = "U-A";
    private static final String B = "U-B";
    private static final String C = "U-C";
    private static final String D = "U-D";
    private static final String X = "U-X";
    private static final String Y = "U-Y";

    private static EvidenceUnit unit(String id) {
        return EvidenceUnit.of(id, SourceKind.CODE, UnitType.ENDPOINT, id, "body of " + id, null, Set.of());
    }

    private static Feature feature(String id, String name, Map<String, EvidenceUnit> units, String... unitIds) {
        return new Feature(id, name, List.of(unitIds),
                FeatureStatusEngine.statusOf(List.of(unitIds).stream().map(units::get).toList()));
    }

    private static FeatureIndexResult fresh(Map<String, EvidenceUnit> units, Feature... features) {
        Map<String, Feature> byId = new LinkedHashMap<>();
        for (Feature f : features) {
            byId.put(f.featureId(), f);
        }
        SourceMix mix = new SourceMix(true, false, false);
        FeatureIndex index = new FeatureIndex(byId, units, Set.of(), Set.of(), mix, "digest");
        ExtractionResult extraction = new ExtractionResult(List.copyOf(units.values()),
                new FetchProvenance(Map.of(SourceKind.CODE, new FetchProvenance.Counts(units.size(), units.size(), List.of()))),
                mix, 0, Set.of(SourceKind.CODE));
        return new FeatureIndexResult(index, new GapDetector().detect(index), extraction);
    }

    private static Map<String, EvidenceUnit> unitsOf(String... ids) {
        Map<String, EvidenceUnit> m = new LinkedHashMap<>();
        for (String id : ids) {
            m.put(id, unit(id));
        }
        return m;
    }

    // ---- null / empty inputs ------------------------------------------------------------------------------------

    @Test
    void nullEditListIsTreatedAsEmptyIdentityCarryForward() {
        Map<String, EvidenceUnit> units = unitsOf(A);
        FeatureIndexResult result = fresh(units, feature("feat-A", "A", units, A));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, null, new GapDetector());

        assertThat(out.result().index().features()).isEqualTo(result.index().features());
        assertThat(out.pins()).isEmpty();
        assertThat(out.notes()).isEmpty();
    }

    @Test
    void renameWithEmptyUnitGroupsHasNoOverlapAndIsReported() {
        Map<String, EvidenceUnit> units = unitsOf(A);
        FeatureIndexResult result = fresh(units, feature("feat-A", "A", units, A));

        // A rename whose recorded unit group is empty → bestOverlap over an empty wanted-set returns null.
        FeatureEdit emptyRename = new FeatureEdit(FeatureEdit.Kind.RENAME, List.of(), "Whatever", null);

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, List.of(emptyRename), new GapDetector());

        assertThat(out.result().index().features().get("feat-A").displayName()).isEqualTo("A");   // untouched
        assertThat(out.notes()).hasSize(1);
        assertThat(out.notes().get(0)).contains("rename").contains("Whatever");
    }

    @Test
    void pinWithEmptyUnitGroupsHasNoOverlapAndIsReported() {
        Map<String, EvidenceUnit> units = unitsOf(A);
        FeatureIndexResult result = fresh(units, feature("feat-A", "A", units, A));

        FeatureEdit emptyPin = new FeatureEdit(FeatureEdit.Kind.PIN, List.of(), null, Boolean.TRUE);

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, List.of(emptyPin), new GapDetector());

        assertThat(out.pins()).isEmpty();
        assertThat(out.notes()).hasSize(1);
        assertThat(out.notes().get(0)).contains("pin");
    }

    // ---- bestOverlap: tie picks FIRST when sizes are equal, zero-overlap features are skipped -------------------

    @Test
    void bestOverlapTieWithEqualSizeRenamesExactlyOneAndReportsTheSplit() {
        // Two single-unit features each overlap the recorded {A,B} by exactly 1, with equal size (1) — a genuine tie.
        // Which one wins is map-order dependent (not part of the contract), so assert only the order-INDEPENDENT
        // behaviour: exactly one of the tied features is renamed, and the split is reported.
        Map<String, EvidenceUnit> units = unitsOf(A, B);
        FeatureIndexResult result = fresh(units,
                feature("feat-first", "First", units, A),
                feature("feat-second", "Second", units, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.rename(List.of(A, B), "Renamed")), new GapDetector());

        long renamed = out.result().index().features().values().stream()
                .filter(f -> f.displayName().equals("Renamed")).count();
        assertThat(renamed).isEqualTo(1);
        assertThat(out.notes()).anyMatch(n -> n.contains("split"));
    }

    @Test
    void bestOverlapPrefersHigherOverlapCountOverASmallerButLessOverlappingFeature() {
        // feat-pair holds {A,B} (overlap 2, size 2); feat-single holds {C} (overlap 1, size 1). Higher count wins
        // even though the other is smaller — exercises the `count > bestCount` branch displacing an earlier best.
        Map<String, EvidenceUnit> units = unitsOf(A, B, C);
        FeatureIndexResult result = fresh(units,
                feature("feat-single", "Single", units, C),
                feature("feat-pair", "Pair", units, A, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.rename(List.of(A, B, C), "Renamed")), new GapDetector());

        assertThat(out.result().index().features().get("feat-pair").displayName()).isEqualTo("Renamed");
        assertThat(out.result().index().features().get("feat-single").displayName()).isEqualTo("Single");
    }

    @Test
    void renameOnAnUnsplitFeatureAddsNoSplitNote() {
        // Single fresh feature fully containing the recorded units → splitAcrossFeatures is false, no note.
        Map<String, EvidenceUnit> units = unitsOf(A, B);
        FeatureIndexResult result = fresh(units, feature("feat-A", "A", units, A, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.rename(List.of(A, B), "Renamed")), new GapDetector());

        assertThat(out.result().index().features().get("feat-A").displayName()).isEqualTo("Renamed");
        assertThat(out.notes()).isEmpty();
    }

    // ---- renameFeature null/blank guards -------------------------------------------------------------------------

    @Test
    void renameWithBlankNameLeavesTheDisplayNameUnchanged() {
        Map<String, EvidenceUnit> units = unitsOf(A);
        FeatureIndexResult result = fresh(units, feature("feat-A", "Original", units, A));

        // Found by overlap, but the new name is blank → renameFeature's guard keeps the original label.
        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.rename(List.of(A), "   ")), new GapDetector());

        assertThat(out.result().index().features().get("feat-A").displayName()).isEqualTo("Original");
        assertThat(out.notes()).isEmpty();
    }

    // ---- merge: fewer than two surviving targets -----------------------------------------------------------------

    @Test
    void mergeCollapsingToOneNamedSurvivorRelabelsThatSurvivor() {
        // Reviewer merged {A}+{B}, but on re-run both ids already sit in ONE fresh feature (so only one target).
        // With a non-blank merge name we relabel that single survivor instead of reporting it un-replayable.
        Map<String, EvidenceUnit> units = unitsOf(A, B);
        FeatureIndexResult result = fresh(units, feature("feat-AB", "AB", units, A, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.merge(List.of(List.of(A), List.of(B)), "Combined")), new GapDetector());

        assertThat(out.result().index().features()).hasSize(1).containsKey("feat-AB");
        assertThat(out.result().index().features().get("feat-AB").displayName()).isEqualTo("Combined");
        assertThat(out.notes()).isEmpty();
    }

    @Test
    void mergeCollapsingToOneUnnamedSurvivorIsReportedUnReplayable() {
        // Same single-survivor situation, but the merge had no name (null) → cannot honour intent, report it.
        Map<String, EvidenceUnit> units = unitsOf(A, B);
        FeatureIndexResult result = fresh(units, feature("feat-AB", "AB", units, A, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.merge(List.of(List.of(A), List.of(B)), null)), new GapDetector());

        assertThat(out.result().index().features().get("feat-AB").displayName()).isEqualTo("AB");   // untouched
        assertThat(out.notes()).hasSize(1);
        assertThat(out.notes().get(0)).contains("Couldn't re-apply a merge").contains("no longer");
    }

    @Test
    void mergeWithBlankNameAndOneSurvivorIsReportedUnReplayable() {
        // size==1 but name is blank → the name-fallback guard fails, so it falls through to the un-replayable note.
        Map<String, EvidenceUnit> units = unitsOf(A, B);
        FeatureIndexResult result = fresh(units, feature("feat-AB", "AB", units, A, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.merge(List.of(List.of(A), List.of(B)), "  ")), new GapDetector());

        assertThat(out.result().index().features().get("feat-AB").displayName()).isEqualTo("AB");
        assertThat(out.notes()).hasSize(1);
        assertThat(out.notes().get(0)).contains("Couldn't re-apply a merge");
    }

    @Test
    void mergeWhoseUnitsAllVanishedHasZeroTargetsAndIsReported() {
        // Neither merged unit survived the re-extraction → zero targets → un-replayable note (size==0 branch).
        Map<String, EvidenceUnit> units = unitsOf(C);
        FeatureIndexResult result = fresh(units, feature("feat-C", "C", units, C));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.merge(List.of(List.of(A), List.of(B)), "Gone")), new GapDetector());

        assertThat(out.result().index().features().get("feat-C").displayName()).isEqualTo("C");   // untouched
        assertThat(out.notes()).hasSize(1);
        assertThat(out.notes().get(0)).contains("Couldn't re-apply a merge").contains("Gone");
    }

    // ---- merge: multi-group + largestName fallback ---------------------------------------------------------------

    @Test
    void multiGroupMergeOfThreeFeaturesUnionsAllMembersAndUsesGivenName() {
        Map<String, EvidenceUnit> units = unitsOf(A, B, C);
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, A),
                feature("feat-B", "B", units, B),
                feature("feat-C", "C", units, C));

        FeatureEdit merge = FeatureEdit.merge(List.of(List.of(A), List.of(B), List.of(C)), "Tri");
        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, List.of(merge), new GapDetector());

        String expectedId = "feat-" + EvidenceId.hash8(A + "|" + B + "|" + C);
        assertThat(out.result().index().features()).hasSize(1).containsKey(expectedId);
        Feature merged = out.result().index().features().get(expectedId);
        assertThat(merged.displayName()).isEqualTo("Tri");
        assertThat(merged.unitIds()).containsExactly(A, B, C);
        assertThat(out.notes()).isEmpty();
    }

    @Test
    void unnamedMergeDefaultsToTheLargestSourceFeaturesName() {
        // No merge name → largestName: feat-big (2 units) beats feat-small (1 unit), so "Big" becomes the label.
        Map<String, EvidenceUnit> units = unitsOf(A, B, C);
        FeatureIndexResult result = fresh(units,
                feature("feat-small", "Small", units, C),
                feature("feat-big", "Big", units, A, B));

        FeatureEdit merge = FeatureEdit.merge(List.of(List.of(C), List.of(A, B)), null);
        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, List.of(merge), new GapDetector());

        String expectedId = "feat-" + EvidenceId.hash8(A + "|" + B + "|" + C);
        assertThat(out.result().index().features()).hasSize(1).containsKey(expectedId);
        assertThat(out.result().index().features().get(expectedId).displayName()).isEqualTo("Big");
    }

    // ---- merge: absorbed-extra-units transparency note -----------------------------------------------------------

    @Test
    void multiGroupMergeAbsorbingUnseenUnitsAcrossTwoFeaturesIsReported() {
        // Both target features carry an extra unit (X, Y) the reviewer never recorded → whole-feature merge absorbs
        // them and surfaces the "additional units" note.
        Map<String, EvidenceUnit> units = unitsOf(A, B, X, Y);
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, A, X),
                feature("feat-B", "B", units, B, Y));

        FeatureEdit merge = FeatureEdit.merge(List.of(List.of(A), List.of(B)), "Combined");
        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, List.of(merge), new GapDetector());

        String expectedId = "feat-" + EvidenceId.hash8(A + "|" + B + "|" + X + "|" + Y);
        assertThat(out.result().index().features()).hasSize(1).containsKey(expectedId);
        assertThat(out.result().index().features().get(expectedId).unitIds())
                .containsExactly(A, B, X, Y);
        assertThat(out.notes()).anyMatch(n -> n.contains("additional units"));
    }

    // ---- merge: pin propagation branches -------------------------------------------------------------------------

    @Test
    void mergeOfUnpinnedFeaturesProducesNoPin() {
        Map<String, EvidenceUnit> units = unitsOf(A, B);
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, A),
                feature("feat-B", "B", units, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.merge(List.of(List.of(A), List.of(B)), "Combined")), new GapDetector());

        assertThat(out.pins()).isEmpty();   // wasPinned==false branch
    }

    @Test
    void mergeLeavesAnUnrelatedPinUntouchedWhileNotPinningTheMerge() {
        // A third feature is pinned (via a prior pin edit) and is NOT part of the merge — it must remain pinned,
        // and the (unpinned) merge must not gain a pin. Exercises pins.removeAll(targetIds) keeping non-targets.
        Map<String, EvidenceUnit> units = unitsOf(A, B, C);
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, A),
                feature("feat-B", "B", units, B),
                feature("feat-C", "C", units, C));

        List<FeatureEdit> edits = List.of(
                FeatureEdit.pin(List.of(C), true),
                FeatureEdit.merge(List.of(List.of(A), List.of(B)), "Combined"));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, edits, new GapDetector());

        assertThat(out.pins()).containsExactly("feat-C");
        String mergedId = "feat-" + EvidenceId.hash8(A + "|" + B);
        assertThat(out.pins()).doesNotContain(mergedId);
    }

    // ---- pin / unpin onto split features -------------------------------------------------------------------------

    @Test
    void unpinReTargetsByOverlapAndRemovesAnExistingPin() {
        // Pin feat-NEW (holds A), then unpin via a recorded group of {A}: the unpin re-finds feat-NEW by overlap.
        Map<String, EvidenceUnit> units = unitsOf(A, C);
        FeatureIndexResult result = fresh(units, feature("feat-NEW", "GET /a", units, A, C));

        List<FeatureEdit> edits = List.of(
                FeatureEdit.pin(List.of(A), true),
                FeatureEdit.pin(List.of(A), false));   // unpin → pins.remove branch

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, edits, new GapDetector());

        assertThat(out.pins()).isEmpty();
        assertThat(out.notes()).isEmpty();
    }

    @Test
    void pinOnASplitFeatureTargetsThePurestPartAndIsReported() {
        // Recorded feature {A,B} split into a big cluster (A+X+Y, first) and a small one (B). The pin lands on the
        // purer (smaller) part and the split is reported.
        Map<String, EvidenceUnit> units = unitsOf(A, B, X, Y);
        FeatureIndexResult result = fresh(units,
                feature("feat-big", "Big", units, A, X, Y),
                feature("feat-small", "Small", units, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.pin(List.of(A, B), true)), new GapDetector());

        assertThat(out.pins()).containsExactly("feat-small");
        assertThat(out.notes()).anyMatch(n -> n.contains("split"));
    }

    @Test
    void unpinningAFeatureThatWasNotPinnedIsANoOpWithNoNote() {
        Map<String, EvidenceUnit> units = unitsOf(A);
        FeatureIndexResult result = fresh(units, feature("feat-A", "A", units, A));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.pin(List.of(A), false)), new GapDetector());

        assertThat(out.pins()).isEmpty();
        assertThat(out.notes()).isEmpty();   // found by overlap, single feature, not split
    }

    @Test
    void pinWhoseFeatureVanishedIsReportedNotSilentlyLost() {
        Map<String, EvidenceUnit> units = unitsOf(A);
        FeatureIndexResult result = fresh(units, feature("feat-A", "A", units, A));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.pin(List.of(B), true)), new GapDetector());

        assertThat(out.pins()).isEmpty();
        assertThat(out.notes()).hasSize(1);
        assertThat(out.notes().get(0)).contains("pin").contains("no longer present");
    }
}