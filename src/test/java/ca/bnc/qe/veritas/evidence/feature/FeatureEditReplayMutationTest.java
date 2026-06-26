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
 * Mutation-killing tests for {@link FeatureEditReplay}. These target PIT survivors and no-coverage mutants the
 * existing {@code FeatureEditReplayTest} leaves alive: the {@code bestOverlap} count/size boundary tie-break,
 * the single-survivor "keep the label" merge branch, the merged-id collision drop, {@code largestName} for an
 * unnamed merge, and the status of a merged feature. Every assertion checks a concrete observable value that
 * differs between the original code and the mutant.
 */
class FeatureEditReplayMutationTest {

    private static EvidenceUnit code(String id) {
        return EvidenceUnit.of(id, SourceKind.CODE, UnitType.ENDPOINT, id, "body of " + id, null, Set.of());
    }

    private static EvidenceUnit jira(String id, String lifecycle) {
        return new EvidenceUnit(id, SourceKind.JIRA, UnitType.REQUIREMENT, id, "body of " + id, null,
                lifecycle, null, Set.of(), Set.of());
    }

    private static Feature feature(String id, String name, Map<String, EvidenceUnit> units, String... unitIds) {
        return new Feature(id, name, List.of(unitIds),
                FeatureStatusEngine.statusOf(List.of(unitIds).stream().map(units::get).toList()));
    }

    /** A fresh result over the given features + units (the engine just passes the extraction through). */
    private static FeatureIndexResult fresh(Map<String, EvidenceUnit> units, Feature... features) {
        Map<String, Feature> byId = new LinkedHashMap<>();
        for (Feature f : features) {
            byId.put(f.featureId(), f);
        }
        SourceMix mix = new SourceMix(true, false, false);
        FeatureIndex index = new FeatureIndex(byId, units, Set.of(), Set.of(), mix, "digest");
        ExtractionResult extraction = new ExtractionResult(List.copyOf(units.values()),
                new FetchProvenance(Map.of(SourceKind.CODE,
                        new FetchProvenance.Counts(units.size(), units.size(), List.of()))),
                mix, 0, Set.of(SourceKind.CODE));
        return new FeatureIndexResult(index, new GapDetector().detect(index), extraction);
    }

    private static FeatureEditReplay.Outcome apply(FeatureIndexResult result, FeatureEdit... edits) {
        return FeatureEditReplay.apply(result, List.of(edits), new GapDetector());
    }

    // ---------------------------------------------------------------------------------------------------------
    // bestOverlap — the count/size tie-break boundary (line 208: count > bestCount || (count==bestCount && size<bestSize))
    // ---------------------------------------------------------------------------------------------------------

    /**
     * On EQUAL overlap count, the strictly SMALLER (purer) feature must win the rename — the {@code size < bestSize}
     * tie-break. This is order-independent: "feat-pure" has a unique smallest size, so it wins no matter which order
     * the unordered feature map yields. Kills the {@code count == bestCount} negate and reinforces the size tie-break.
     */
    @Test
    void bestOverlapPicksTheUniqueSmallestFeatureOnACountTie() {
        String a = "U-A";
        String b = "U-B";
        String x = "U-X";
        Map<String, EvidenceUnit> units = Map.of(a, code(a), b, code(b), x, code(x));
        // pure holds just A (count 1, size 1 — the unique smallest); wide holds B + filler (count 1, size 2).
        FeatureIndexResult result = fresh(units,
                feature("feat-pure", "Pure", units, a),
                feature("feat-wide", "Wide", units, b, x));

        FeatureEditReplay.Outcome out = apply(result, FeatureEdit.rename(List.of(a, b), "Winner"));

        // Both overlap the recorded {A,B} by one unit (count tie). The smaller feature wins the size tie-break.
        assertThat(out.result().index().features().get("feat-pure").displayName()).isEqualTo("Winner");
        assertThat(out.result().index().features().get("feat-wide").displayName()).isEqualTo("Wide");
    }

    /**
     * On EQUAL overlap count, a feature with a LARGER membership must NOT displace the smaller incumbent — the
     * {@code count > bestCount} stays strict so the count tie falls through to the size tie-break. Mutating that
     * {@code >} to {@code >=} would let the larger, later feature overwrite the smaller (purer) winner.
     */
    @Test
    void bestOverlapPrefersThePurerSmallerFeatureOnACountTie() {
        String a = "U-A";
        String b = "U-B";
        String x = "U-X";
        String y = "U-Y";
        Map<String, EvidenceUnit> units = Map.of(a, code(a), b, code(b), x, code(x), y, code(y));
        // pure holds just A (count 1, size 1); broad holds B + 2 fillers (count 1, size 3) and comes LATER.
        FeatureIndexResult result = fresh(units,
                feature("feat-pure", "Pure", units, a),
                feature("feat-broad", "Broad", units, b, x, y));

        FeatureEditReplay.Outcome out = apply(result, FeatureEdit.rename(List.of(a, b), "Winner"));

        // Strict "count >" → the count tie resolves by size → the smaller "feat-pure" wins.
        // "count >=" would let the later, larger "feat-broad" overwrite it.
        assertThat(out.result().index().features().get("feat-pure").displayName()).isEqualTo("Winner");
        assertThat(out.result().index().features().get("feat-broad").displayName()).isEqualTo("Broad");
    }

    // ---------------------------------------------------------------------------------------------------------
    // applyMerge — single-survivor "keep the reviewer's label" branch (lines 74-75)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * When a merge collapses to a single surviving feature AND the reviewer named the merge, the engine keeps that
     * label on the survivor (closest honour of intent) and adds NO un-replayable note. Covers the conjunction on
     * line 74 ({@code size==1 && name!=null && !name.isBlank()}) and the {@code renameFeature} call on line 75.
     */
    @Test
    void aNamedMergeWithOnlyOneSurvivingFeatureKeepsTheLabelAndDoesNotComplain() {
        String a = "U-A";
        Map<String, EvidenceUnit> units = Map.of(a, code(a));   // B's unit vanished from the new extraction
        FeatureIndexResult result = fresh(units, feature("feat-A", "Original", units, a));

        // Reviewer had merged {A}+{B}; only A's feature survives → keep the named label on it.
        FeatureEditReplay.Outcome out = apply(result,
                FeatureEdit.merge(List.of(List.of(a), List.of("U-B")), "Kept Name"));

        assertThat(out.result().index().features().get("feat-A").displayName()).isEqualTo("Kept Name");
        assertThat(out.notes()).isEmpty();
    }

    /**
     * The mirror of the above: a single-survivor merge with a BLANK name must NOT rename anything — it reports the
     * merge as un-replayable. This distinguishes the {@code name != null && !name.isBlank()} sub-conditions on line 74
     * (a mutant that drops the blank check would rename the survivor to the blank label).
     */
    @Test
    void aBlankNamedMergeWithOneSurvivorIsReportedAndLeavesTheLabelUntouched() {
        String a = "U-A";
        Map<String, EvidenceUnit> units = Map.of(a, code(a));
        FeatureIndexResult result = fresh(units, feature("feat-A", "Original", units, a));

        FeatureEditReplay.Outcome out = apply(result,
                FeatureEdit.merge(List.of(List.of(a), List.of("U-B")), "   "));   // blank name

        assertThat(out.result().index().features().get("feat-A").displayName()).isEqualTo("Original");
        assertThat(out.notes()).hasSize(1);
        assertThat(out.notes().get(0)).contains("Couldn't re-apply a merge");
    }

    // ---------------------------------------------------------------------------------------------------------
    // applyMerge — merged feature status uses the resolved units (line 94: if (u != null))
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The merged feature's status is computed from the units actually resolved by id. With a CODE unit and a JIRA
     * unit that is still active (IN_PROGRESS), {@code statusOf} → PARTIAL. If the {@code u != null} guard on line 94
     * were negated, {@code mergedUnits} would stay empty and the status would collapse to PLANNED — a different value.
     */
    @Test
    void aMergedFeatureGetsTheStatusOfItsResolvedUnitsNotAnEmptyList() {
        String a = "U-A";
        String b = "U-B";
        Map<String, EvidenceUnit> units = Map.of(a, code(a), b, jira(b, "IN_PROGRESS"));
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, a),
                feature("feat-B", "B", units, b));

        FeatureEditReplay.Outcome out = apply(result,
                FeatureEdit.merge(List.of(List.of(a), List.of(b)), "Combined"));

        String mergedId = "feat-" + EvidenceId.hash8(a + "|" + b);
        Feature merged = out.result().index().features().get(mergedId);
        // code + active Jira intent → PARTIAL. An empty mergedUnits list would yield PLANNED.
        assertThat(merged.status()).isEqualTo(FeatureStatus.PARTIAL);
        assertThat(merged.unitIds()).containsExactly(a, b);
    }

    // ---------------------------------------------------------------------------------------------------------
    // applyMerge — merged-id collision drop (line 128: else if (!e.getKey().equals(mergedId)))
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The rebuild on line 128 ({@code else if (!e.getKey().equals(mergedId))}) keeps every non-target feature whose
     * id is NOT the merged id, and drops a pre-existing feature that happens to share the merged id. Negating that
     * guard inverts the {@code else}: it would DROP all the unrelated bystander features and only ever re-add a
     * collision. The untouched bystander "feat-bystander" below must survive the merge — that is order-independent.
     */
    @Test
    void aMergeKeepsUnrelatedFeaturesAndDropsAStaleCollidingId() {
        String a = "U-A";
        String b = "U-B";
        String w = "U-W";
        String z = "U-Z";
        String collisionId = "feat-" + EvidenceId.hash8(a + "|" + b);   // the id the {A}+{B} merge will compute
        Map<String, EvidenceUnit> units = Map.of(a, code(a), b, code(b), w, code(w), z, code(z));
        FeatureIndexResult result = fresh(units,
                feature("feat-A", "A", units, a),
                feature("feat-B", "B", units, b),
                feature("feat-bystander", "Bystander", units, w),    // unrelated → must survive the merge
                feature(collisionId, "StaleCollision", units, z));   // pre-existing feature sharing the merged id

        FeatureEditReplay.Outcome out = apply(result,
                FeatureEdit.merge(List.of(List.of(a), List.of(b)), "Combined"));

        Map<String, Feature> after = out.result().index().features();
        // Exactly two features remain: the merged one and the untouched bystander. The stale collision was dropped.
        // Negating line 128 would drop the bystander (and keep the stale {Z}) — failing both assertions below.
        assertThat(after).hasSize(2).containsKey(collisionId).containsKey("feat-bystander");
        assertThat(after.get("feat-bystander").displayName()).isEqualTo("Bystander");
        assertThat(after.get("feat-bystander").unitIds()).containsExactly(w);
        Feature merged = after.get(collisionId);
        assertThat(merged.displayName()).isEqualTo("Combined");
        assertThat(merged.unitIds()).containsExactly(a, b);
    }

    // ---------------------------------------------------------------------------------------------------------
    // largestName — the unnamed-merge default name (lines 228, 233)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * An UNNAMED merge takes its display name from {@code largestName}: the name of the target with the most units.
     * Order-independent because the larger target is unique (size 2 vs 1). This kills line 228's {@code size() > max}
     * negate (which would leave {@code name} null → merged name null) and the {@code f != null} negate, plus line
     * 233's {@code EmptyObjectReturnVals} (which would return "" instead of "BigName").
     */
    @Test
    void anUnnamedMergePicksTheLargerTargetsNameWhenSizesDiffer() {
        String a = "U-A";
        String b = "U-B";
        String x = "U-X";
        Map<String, EvidenceUnit> units = Map.of(a, code(a), b, code(b), x, code(x));
        // small target {A} (size 1); big target {B,X} (size 2 — the unique largest, so its name must win).
        FeatureIndexResult result = fresh(units,
                feature("feat-small", "SmallName", units, a),
                feature("feat-big", "BigName", units, b, x));

        FeatureEditReplay.Outcome out = apply(result,
                FeatureEdit.merge(List.of(List.of(a), List.of(b, x)), null));   // no name → largestName

        String mergedId = "feat-" + EvidenceId.hash8(a + "|" + b + "|" + x);
        Feature merged = out.result().index().features().get(mergedId);
        // The bigger "feat-big" feature donates the name. A frozen/empty largestName would give null/"".
        assertThat(merged.displayName()).isEqualTo("BigName");
        assertThat(merged.unitIds()).containsExactly(a, b, x);
    }
}