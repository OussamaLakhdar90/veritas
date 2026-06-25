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

/** The deterministic quality scorecard: a clean strategy scores OK; each silent-failure surface degrades it. */
class StrategyScorecardEngineTest {

    private final StrategyScorecardEngine engine = new StrategyScorecardEngine();
    private final ObjectMapper m = new ObjectMapper();

    private ArrayNode sections(String... featureIds) {
        ArrayNode arr = m.createArrayNode();
        for (String id : featureIds) {
            ObjectNode node = m.createObjectNode();
            node.put("featureId", id);                    // the scorecard keys on this (display names can collide)
            node.put("feature", "name-" + id);
            node.putArray("evidence").add(m.createObjectNode().put("unitId", "U-" + id));
            arr.add(node);
        }
        return arr;
    }

    private ArrayNode gaps(String... featureIds) {
        ArrayNode arr = m.createArrayNode();
        for (String id : featureIds) {
            arr.add(m.createObjectNode().put("feature", id).put("kind", "PLANNED_NOT_IMPLEMENTED"));
        }
        return arr;
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

    @Test
    void aCompleteStrategyScoresOk() {
        ObjectNode d = deliverable(sections("f1"), sections("f1"), gaps());
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)), List.of());

        assertThat(sc.verdict()).isEqualTo(StrategyScorecard.OK);
        assertThat(sc.confidence()).isEqualTo(100);
        assertThat(sc.checks()).allMatch(StrategyScorecard.Check::passed);
    }

    @Test
    void aDroppedSectionDegradesTheRun() {
        ObjectNode d = deliverable(sections("f1"), sections("f1"), gaps());
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)),
                List.of("riskRegister / Get policy"));

        assertThat(sc.degraded()).isTrue();
        assertThat(sc.droppedSections()).isEqualTo(1);
        assertThat(sc.checks()).anyMatch(c -> !c.passed() && c.name().contains("grounded"));
    }

    @Test
    void aFeatureWithARiskRegisterButNoTestApproachIsFlaggedAsDrift() {
        ObjectNode d = deliverable(sections("f1"), sections(), gaps());   // risk only
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)), List.of());

        assertThat(sc.degraded()).isTrue();
        assertThat(sc.checks()).anyMatch(c -> !c.passed() && c.detail().contains("Get policy"));
    }

    @Test
    void anImplementedFeatureWithNoStrategySectionDegrades() {
        ObjectNode d = deliverable(sections(), sections(), gaps());   // no sections at all
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)), List.of());

        assertThat(sc.degraded()).isTrue();
        assertThat(sc.checks()).anyMatch(c -> !c.passed() && c.name().contains("implemented"));
    }

    @Test
    void aPlannedFeatureNotRaisedAsAGapDegrades() {
        ObjectNode d = deliverable(sections(), sections(), gaps());   // no gap referencing f1
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Create policy", FeatureStatus.PLANNED)), List.of());

        assertThat(sc.degraded()).isTrue();
        assertThat(sc.checks()).anyMatch(c -> !c.passed() && c.name().contains("gap"));
    }

    @Test
    void aPlannedFeatureRaisedAsAGapPasses() {
        ObjectNode d = deliverable(sections(), sections(), gaps("f1"));   // gap references the featureId
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Create policy", FeatureStatus.PLANNED)), List.of());

        assertThat(sc.verdict()).isEqualTo(StrategyScorecard.OK);
    }

    @Test
    void aSectionThatCitesNoEvidenceDegrades() {
        ObjectNode riskNode = m.createObjectNode();
        riskNode.put("featureId", "f1");
        riskNode.put("feature", "Get policy");
        riskNode.putArray("evidence");   // empty evidence
        ArrayNode risk = m.createArrayNode();
        risk.add(riskNode);
        ObjectNode d = deliverable(risk, sections("f1"), gaps());
        StrategyScorecard sc = engine.score(d, index(feat("f1", "Get policy", FeatureStatus.IMPLEMENTED)), List.of());

        assertThat(sc.degraded()).isTrue();
        assertThat(sc.checks()).anyMatch(c -> !c.passed() && c.name().contains("cites evidence"));
    }
}
