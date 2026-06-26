package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.ExtractionResult;
import ca.bnc.qe.veritas.evidence.FetchProvenance;
import ca.bnc.qe.veritas.evidence.SourceMix;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Mutation-killing tests for {@link StrategyScorecardEngine}. These target the two PIT survivors that the
 * happy-path/degraded tests in {@code StrategyScorecardEngineTest} could not distinguish, both {@code MathMutator}
 * mutants in the "every section cites evidence" check:
 *
 * <ul>
 *   <li><b>line 66</b> — {@code riskUngrounded + approachUngrounded} mutated to {@code -}. The original tests only
 *       ever put an ungrounded section in ONE of the two arrays, so {@code n - 0 == n} and the mutant survives.
 *       {@link #bothArraysUngroundedSumsTheTwoCounts()} puts one ungrounded section in EACH array: the real engine
 *       reports {@code 2} and degrades; the {@code -} mutant computes {@code 0}, passes the check, and would score
 *       a perfect 100 / OK — so the exact count text and the verdict both distinguish them.</li>
 *   <li><b>line 96</b> — {@code count++} mutated to {@code count--}. With a single ungrounded section the real
 *       engine counts {@code 1} while the mutant counts {@code -1}; both are {@code != 0} so the verdict alone can't
 *       tell them apart. {@link #aSingleUngroundedSectionCountsExactlyOne()} pins the detail text to the exact
 *       {@code "1 section(s) cite no evidence."} value, which the {@code -1} mutant cannot produce.</li>
 * </ul>
 *
 * <p>The remaining tests pin the verdict/confidence threshold arithmetic (line 71/72) to exact values so the
 * boundary survives no replacement.
 */
class StrategyScorecardEngineMutationTest {

    private final StrategyScorecardEngine engine = new StrategyScorecardEngine();
    private final ObjectMapper m = new ObjectMapper();

    /** A section that CITES evidence (one unit) — counts as grounded. */
    private ObjectNode grounded(String featureId) {
        ObjectNode node = m.createObjectNode();
        node.put("featureId", featureId);
        node.put("feature", "name-" + featureId);
        node.putArray("evidence").add(m.createObjectNode().put("unitId", "U-" + featureId));
        return node;
    }

    /** A section with an EMPTY evidence array — counts as ungrounded (sectionsWithoutEvidence increments). */
    private ObjectNode ungrounded(String featureId) {
        ObjectNode node = m.createObjectNode();
        node.put("featureId", featureId);
        node.put("feature", "name-" + featureId);
        node.putArray("evidence");   // empty -> ungrounded
        return node;
    }

    private ArrayNode arr(ObjectNode... nodes) {
        ArrayNode a = m.createArrayNode();
        for (ObjectNode n : nodes) {
            a.add(n);
        }
        return a;
    }

    private ObjectNode deliverable(ArrayNode risk, ArrayNode approach, ArrayNode gaps) {
        ObjectNode d = m.createObjectNode();
        d.set("riskRegister", risk);
        d.set("testApproach", approach);
        d.set("gaps", gaps);
        return d;
    }

    private FeatureIndexResult index(Feature... features) {
        Map<String, Feature> fmap = new LinkedHashMap<>();
        for (Feature f : features) {
            fmap.put(f.featureId(), f);
        }
        FeatureIndex idx = new FeatureIndex(fmap, Map.<String, EvidenceUnit>of(), Set.of(), Set.of(),
                new SourceMix(true, true, false), "src");
        return new FeatureIndexResult(idx, new GapReport(List.of(), Set.of()),
                new ExtractionResult(List.of(), new FetchProvenance(Map.of()), new SourceMix(true, true, false), 0, Set.of()));
    }

    private static Feature feat(String id, String name, FeatureStatus status) {
        return new Feature(id, name, List.of("u-" + id), status);
    }

    private StrategyScorecard.Check citesEvidenceCheck(StrategyScorecard sc) {
        return sc.checks().stream()
                .filter(c -> c.name().contains("cites evidence"))
                .findFirst()
                .orElseThrow();
    }

    // ---- Survivor 1: line 66  riskUngrounded + approachUngrounded  (the '+' must not become '-') ----

    @Test
    void bothArraysUngroundedSumsTheTwoCounts() {
        // one ungrounded section in riskRegister AND one in testApproach.
        ObjectNode d = deliverable(arr(ungrounded("f1")), arr(ungrounded("f1")), arr());
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)), List.of());

        StrategyScorecard.Check cites = citesEvidenceCheck(sc);
        // real engine: 1 + 1 == 2 -> check fails with the exact count. '-' mutant: 1 - 1 == 0 -> would pass.
        assertThat(cites.passed()).isFalse();
        assertThat(cites.detail()).isEqualTo("2 section(s) cite no evidence.");
        assertThat(sc.verdict()).isEqualTo(StrategyScorecard.DEGRADED);
        assertThat(sc.degraded()).isTrue();
    }

    @Test
    void plusIsNotMinus_onlyApproachUngroundedStillCountsOne() {
        // risk grounded, approach ungrounded -> 0 + 1 == 1.  '-' mutant: 0 - 1 == -1 (a different, non-canonical text).
        ObjectNode d = deliverable(arr(grounded("f1")), arr(ungrounded("f1")), arr());
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)), List.of());

        StrategyScorecard.Check cites = citesEvidenceCheck(sc);
        assertThat(cites.passed()).isFalse();
        assertThat(cites.detail()).isEqualTo("1 section(s) cite no evidence.");
    }

    // ---- Survivor 2: line 96  count++  (the increment must not become a decrement) ----

    @Test
    void aSingleUngroundedSectionCountsExactlyOne() {
        // exactly one ungrounded section. real engine counts 1; the count-- mutant counts -1.
        ObjectNode d = deliverable(arr(ungrounded("f1")), arr(grounded("f1")), arr());
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)), List.of());

        StrategyScorecard.Check cites = citesEvidenceCheck(sc);
        assertThat(cites.passed()).isFalse();
        // "-1 section(s) cite no evidence." would be produced by the count-- mutant: pin the exact positive value.
        assertThat(cites.detail()).isEqualTo("1 section(s) cite no evidence.");
        assertThat(sc.verdict()).isEqualTo(StrategyScorecard.DEGRADED);
    }

    @Test
    void twoUngroundedSectionsInOneArrayCountTwo() {
        // two ungrounded sections in riskRegister -> real engine counts 2; count-- mutant counts -2.
        ObjectNode d = deliverable(arr(ungrounded("f1"), ungrounded("f2")), arr(grounded("f1"), grounded("f2")), arr());
        StrategyScorecard sc = engine.score(d,
                index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED), feat("f2", "Set policy", FeatureStatus.IMPLEMENTED)),
                List.of());

        StrategyScorecard.Check cites = citesEvidenceCheck(sc);
        assertThat(cites.passed()).isFalse();
        assertThat(cites.detail()).isEqualTo("2 section(s) cite no evidence.");
    }

    // ---- Verdict / confidence threshold arithmetic (line 71/72) pinned to exact values ----

    @Test
    void allFivePassYields100AndOk() {
        ObjectNode d = deliverable(arr(grounded("f1")), arr(grounded("f1")), arr());
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)), List.of());

        assertThat(sc.confidence()).isEqualTo(100);
        assertThat(sc.verdict()).isEqualTo(StrategyScorecard.OK);
        assertThat(sc.checks().stream().filter(StrategyScorecard.Check::passed).count()).isEqualTo(5);
    }

    @Test
    void oneOfFiveFailsYields80AndDegraded() {
        // only the "cites evidence" check fails -> 4/5 passed -> round(100*4/5) == 80.
        ObjectNode d = deliverable(arr(ungrounded("f1")), arr(grounded("f1")), arr());
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)), List.of());

        long passed = sc.checks().stream().filter(StrategyScorecard.Check::passed).count();
        assertThat(passed).isEqualTo(4);
        assertThat(sc.confidence()).isEqualTo(80);
        assertThat(sc.verdict()).isEqualTo(StrategyScorecard.DEGRADED);
    }
}