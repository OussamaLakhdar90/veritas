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
 * The lineage carry-forward engine (§3.2): a recorded log of reviewer overrides re-applied to a FRESHLY
 * re-extracted feature index, re-targeted by unit-id overlap so the edits survive the content-derived
 * {@code featureId} changing — and anything that can't be re-applied is reported, never silently lost.
 */
class FeatureEditReplayTest {

    private static final String A = "U-A";
    private static final String B = "U-B";
    private static final String C = "U-C";

    private static EvidenceUnit unit(String id) {
        return EvidenceUnit.of(id, SourceKind.CODE, UnitType.ENDPOINT, id, "body of " + id, null, Set.of());
    }

    private static Feature feature(String id, String name, Map<String, EvidenceUnit> units, String... unitIds) {
        return new Feature(id, name, List.of(unitIds),
                FeatureStatusEngine.statusOf(List.of(unitIds).stream().map(units::get).toList()));
    }

    /** A fresh result over the given features + units (minimal extraction provenance — the engine just passes it through). */
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

    @Test
    void renameAndPinCarryForwardEvenWhenTheFeatureIdChanged() {
        Map<String, EvidenceUnit> units = Map.of(A, unit(A), C, unit(C));
        // Fresh re-extraction: the feature that held A now also holds a new unit C → its content-derived id differs.
        Feature freshFeature = feature("feat-NEW", "GET /a", units, A, C);
        FeatureIndexResult result = fresh(units, freshFeature);

        List<FeatureEdit> edits = List.of(
                FeatureEdit.rename(List.of(A), "My Endpoint"),   // recorded against the OLD single-unit feature
                FeatureEdit.pin(List.of(A), true));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, edits, new GapDetector());

        // Re-targeted by overlap on A → the renamed/pinned fresh feature is "feat-NEW", not the vanished old id.
        assertThat(out.result().index().features().get("feat-NEW").displayName()).isEqualTo("My Endpoint");
        assertThat(out.pins()).containsExactly("feat-NEW");
        assertThat(out.notes()).isEmpty();
    }

    @Test
    void mergeIsReplayedWhenFreshClusteringSplitTheFeaturesApart() {
        Map<String, EvidenceUnit> units = Map.of(A, unit(A), B, unit(B));
        // The reviewer had merged A+B; the fresh extraction clustered them as two separate features again.
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, A),
                feature("feat-B", "B", units, B));

        List<FeatureEdit> edits = List.of(FeatureEdit.merge(List.of(List.of(A), List.of(B)), "Combined"));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, edits, new GapDetector());

        String expectedId = "feat-" + EvidenceId.hash8(A + "|" + B);   // sorted union, the seeder's id scheme
        assertThat(out.result().index().features()).hasSize(1).containsKey(expectedId);
        Feature merged = out.result().index().features().get(expectedId);
        assertThat(merged.displayName()).isEqualTo("Combined");
        assertThat(merged.unitIds()).containsExactly(A, B);
        assertThat(out.notes()).isEmpty();
    }

    @Test
    void editsApplyInOrderSoARenameAfterAMergeReTargetsTheMergedFeature() {
        Map<String, EvidenceUnit> units = Map.of(A, unit(A), B, unit(B));
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, A),
                feature("feat-B", "B", units, B));

        List<FeatureEdit> edits = List.of(
                FeatureEdit.merge(List.of(List.of(A), List.of(B)), "Combined"),
                FeatureEdit.rename(List.of(A, B), "Final"));   // recorded against the merged feature's units

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, edits, new GapDetector());

        assertThat(out.result().index().features()).hasSize(1);
        assertThat(out.result().index().features().values().iterator().next().displayName()).isEqualTo("Final");
    }

    @Test
    void aPinBeforeAMergeFollowsOntoTheMergedFeature() {
        Map<String, EvidenceUnit> units = Map.of(A, unit(A), B, unit(B));
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, A),
                feature("feat-B", "B", units, B));

        List<FeatureEdit> edits = List.of(
                FeatureEdit.pin(List.of(A), true),
                FeatureEdit.merge(List.of(List.of(A), List.of(B)), "Combined"));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, edits, new GapDetector());

        String expectedId = "feat-" + EvidenceId.hash8(A + "|" + B);
        assertThat(out.pins()).containsExactly(expectedId);   // the pin moved onto the merged feature
    }

    @Test
    void aRenameOnASplitFeatureTargetsThePurestPartAndIsReported() {
        Map<String, EvidenceUnit> units = Map.of(A, unit(A), B, unit(B), "U-X", unit("U-X"), "U-Y", unit("U-Y"));
        // The recorded feature {A,B} was split on re-extraction: A landed in a big new cluster (listed FIRST), B alone.
        FeatureIndexResult result = fresh(units,
                feature("feat-big", "Big", units, A, "U-X", "U-Y"),
                feature("feat-small", "Small", units, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.rename(List.of(A, B), "Renamed")), new GapDetector());

        // Tie on overlap count (1 each) → the smaller, purer feature wins, not the first-in-order big one.
        assertThat(out.result().index().features().get("feat-small").displayName()).isEqualTo("Renamed");
        assertThat(out.result().index().features().get("feat-big").displayName()).isEqualTo("Big");   // untouched
        assertThat(out.notes()).anyMatch(n -> n.contains("split"));
    }

    @Test
    void aMergeThatAbsorbsNewUnitsIsReported() {
        Map<String, EvidenceUnit> units = Map.of(A, unit(A), B, unit(B), "U-X", unit("U-X"));
        // Reviewer merged {A}+{B}; on re-run A's feature had also gained a brand-new unit X.
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, A, "U-X"),
                feature("feat-B", "B", units, B));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(
                result, List.of(FeatureEdit.merge(List.of(List.of(A), List.of(B)), "Combined")), new GapDetector());

        assertThat(out.result().index().features()).hasSize(1);
        assertThat(out.result().index().features().values().iterator().next().unitIds())
                .containsExactly(A, B, "U-X");   // the new unit was absorbed (whole-feature merge)
        assertThat(out.notes()).anyMatch(n -> n.contains("additional units"));
    }

    @Test
    void anEditWhoseFeatureVanishedIsReportedNotSilentlyLost() {
        Map<String, EvidenceUnit> units = Map.of(A, unit(A));
        FeatureIndexResult result = fresh(units, feature("feat-A", "A", units, A));

        // The reviewer renamed a feature whose only unit (B) no longer exists in the new extraction.
        List<FeatureEdit> edits = List.of(FeatureEdit.rename(List.of(B), "Gone"));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, edits, new GapDetector());

        assertThat(out.result().index().features().get("feat-A").displayName()).isEqualTo("A");   // untouched
        assertThat(out.notes()).hasSize(1);
        assertThat(out.notes().get(0)).contains("rename").contains("Gone");
    }

    @Test
    void emptyEditLogIsAnIdentityCarryForward() {
        Map<String, EvidenceUnit> units = Map.of(A, unit(A));
        FeatureIndexResult result = fresh(units, feature("feat-A", "A", units, A));

        FeatureEditReplay.Outcome out = FeatureEditReplay.apply(result, List.of(), new GapDetector());

        assertThat(out.result().index().features()).isEqualTo(result.index().features());
        assertThat(out.pins()).isEmpty();
        assertThat(out.notes()).isEmpty();
    }
}
