package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;

/** Deterministic gap analysis: presence gaps from status, the contradiction marker, and the mis-cluster guard. */
class GapDetectorTest {

    private final GapDetector detector = new GapDetector();

    private static EvidenceUnit u(String id, SourceKind src, UnitType type, String hint) {
        return EvidenceUnit.of(id, src, type, id, "t", null, Set.of(hint));
    }

    private static FeatureIndex index(List<EvidenceUnit> units, List<Feature> features) {
        Map<String, EvidenceUnit> byId = units.stream()
                .collect(Collectors.toMap(EvidenceUnit::id, x -> x, (a, b) -> a, LinkedHashMap::new));
        Map<String, Feature> fmap = features.stream()
                .collect(Collectors.toMap(Feature::featureId, x -> x, (a, b) -> a, LinkedHashMap::new));
        return new FeatureIndex(fmap, byId, Set.of(), Set.of(), new SourceMix(true, true, false), "src");
    }

    @Test
    void plannedFeatureProducesANotImplementedGap() {
        EvidenceUnit jira = u("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "policy");
        FeatureIndex idx = index(List.of(jira), List.of(new Feature("f1", "policy", List.of("JIRA-1"), FeatureStatus.PLANNED)));
        GapReport r = detector.detect(idx);
        assertThat(r.gapsOfKind(GapKind.PLANNED_NOT_IMPLEMENTED)).hasSize(1);
        assertThat(r.gaps().get(0).citedUnitIds()).contains("JIRA-1");
        assertThat(r.contradictionCheckFeatureIds()).isEmpty();
    }

    @Test
    void undocumentedFeatureProducesAScopeCreepGap() {
        EvidenceUnit code = u("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "policies");
        FeatureIndex idx = index(List.of(code), List.of(new Feature("f1", "policies", List.of("CODE-1"), FeatureStatus.UNDOCUMENTED)));
        GapReport r = detector.detect(idx);
        assertThat(r.gapsOfKind(GapKind.IMPLEMENTED_UNDOCUMENTED)).hasSize(1);
        assertThat(r.gaps().get(0).message()).contains("scope creep");
    }

    @Test
    void implementedFeatureIsMarkedForContradictionCheckWithNoPresenceGap() {
        EvidenceUnit jira = u("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "policy");
        EvidenceUnit code = u("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "policies");
        FeatureIndex idx = index(List.of(jira, code),
                List.of(new Feature("f1", "policy", List.of("JIRA-1", "CODE-1"), FeatureStatus.IMPLEMENTED)));
        GapReport r = detector.detect(idx);
        assertThat(r.contradictionCheckFeatureIds()).containsExactly("f1");
        assertThat(r.gaps()).isEmpty();   // intent + code present → no presence gap, but it must be contradiction-checked
    }

    @Test
    void intentAndCodeFeaturesSharingTwoHintsAreFlaggedAsPossibleMiscluster() {
        // An intent feature and a code feature that share >=2 stems across their units — a lexical near-miss the
        // conservative seed couldn't bridge via any single unit pair, so they wrongly sit in separate features.
        EvidenceUnit a1 = u("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "policy");
        EvidenceUnit a2 = u("JIRA-2", SourceKind.JIRA, UnitType.ACCEPTANCE_CRITERIA, "rule");
        EvidenceUnit b1 = u("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "policy");
        EvidenceUnit b2 = u("CODE-2", SourceKind.CODE, UnitType.DTO_CONSTRAINT, "rule");
        FeatureIndex idx = index(List.of(a1, a2, b1, b2), List.of(
                new Feature("feat-intent", "policy", List.of("JIRA-1", "JIRA-2"), FeatureStatus.PLANNED),
                new Feature("feat-code", "policy", List.of("CODE-1", "CODE-2"), FeatureStatus.UNDOCUMENTED)));
        GapReport r = detector.detect(idx);

        List<Gap> mis = r.gapsOfKind(GapKind.POSSIBLE_MISCLUSTER);
        assertThat(mis).hasSize(1);
        assertThat(mis.get(0).citedUnitIds()).contains("JIRA-1", "CODE-1");
        assertThat(mis.get(0).message()).doesNotContain("[");   // shared terms joined, not a raw Set toString
        assertThat(r.gapsOfKind(GapKind.PLANNED_NOT_IMPLEMENTED)).hasSize(1);
        assertThat(r.gapsOfKind(GapKind.IMPLEMENTED_UNDOCUMENTED)).hasSize(1);
    }

    @Test
    void noMisclusterOnASingleSharedHint() {
        // One shared stem ("policy") is exactly the weak signal the seeder refused to merge on — not a mis-cluster.
        EvidenceUnit a1 = u("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "policy");
        EvidenceUnit a2 = u("JIRA-2", SourceKind.JIRA, UnitType.ACCEPTANCE_CRITERIA, "rule");
        EvidenceUnit b1 = u("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "policy");
        EvidenceUnit b2 = u("CODE-2", SourceKind.CODE, UnitType.DTO_CONSTRAINT, "audit");
        FeatureIndex idx = index(List.of(a1, a2, b1, b2), List.of(
                new Feature("feat-intent", "policy", List.of("JIRA-1", "JIRA-2"), FeatureStatus.PLANNED),
                new Feature("feat-code", "policy", List.of("CODE-1", "CODE-2"), FeatureStatus.UNDOCUMENTED)));
        assertThat(detector.detect(idx).gapsOfKind(GapKind.POSSIBLE_MISCLUSTER)).isEmpty();
    }

    @Test
    void coverageGapFeatureProducesADoneButNotBuiltGap() {
        EvidenceUnit jira = u("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "policy");
        FeatureIndex idx = index(List.of(jira),
                List.of(new Feature("f1", "policy", List.of("JIRA-1"), FeatureStatus.COVERAGE_GAP)));
        GapReport r = detector.detect(idx);
        assertThat(r.gapsOfKind(GapKind.COVERAGE_GAP)).hasSize(1);
        assertThat(r.gaps().get(0).message()).contains("marked done in Jira");
        assertThat(r.contradictionCheckFeatureIds()).isEmpty();
    }

    @Test
    void partialFeatureIsContradictionCheckedLikeImplemented() {
        EvidenceUnit jira = u("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "policy");
        EvidenceUnit code = u("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "policies");
        FeatureIndex idx = index(List.of(jira, code),
                List.of(new Feature("f1", "policy", List.of("JIRA-1", "CODE-1"), FeatureStatus.PARTIAL)));
        assertThat(detector.detect(idx).contradictionCheckFeatureIds()).containsExactly("f1");
    }

    @Test
    void unitLessFeatureProducesNoUncitablePresenceGap() {
        FeatureIndex idx = index(List.of(),
                List.of(new Feature("ghost", "ghost", List.of("MISSING-1"), FeatureStatus.PLANNED)));
        assertThat(detector.detect(idx).gaps()).isEmpty();   // no resolvable units → no uncitable gap emitted
    }
}
