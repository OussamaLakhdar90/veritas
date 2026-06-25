package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.Hints;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;

/**
 * The deterministic seed is <b>conservative</b>: it links only on ≥2 shared stemmed hints, so related units
 * cluster but a single shared noun never chains unrelated features into one blob. Cross-source / semantic merging
 * (intent↔endpoint, login↔auth) is the LLM tagger's job (Phase 3b), not the seed's.
 */
class FeatureSeederTest {

    private final FeatureSeeder seeder = new FeatureSeeder();

    /** A unit whose hints are derived from realistic free text by the real {@link Hints#fromText}. */
    private static EvidenceUnit textUnit(String id, SourceKind src, UnitType type, String text) {
        return EvidenceUnit.of(id, src, type, text, text, null, Hints.fromText(text));
    }

    private static EvidenceUnit hintUnit(String id, SourceKind src, UnitType type, Set<String> hints) {
        return EvidenceUnit.of(id, src, type, id, "text", null, hints);
    }

    @Test
    void relatedIntentUnitsSharingTwoHintsClusterAsPlanned() {
        EvidenceUnit jira = textUnit("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT,
                "Reset forgotten password via email link");
        EvidenceUnit conf = textUnit("CONF#reset-1", SourceKind.CONFLUENCE, UnitType.DESIGN,
                "Password reset flow sends an email token");   // shares password, reset, email
        FeatureIndex idx = seeder.seed(List.of(jira, conf), new SourceMix(false, true, true));

        assertThat(idx.features()).hasSize(1);
        Feature f = idx.features().values().iterator().next();
        assertThat(f.unitIds()).containsExactlyInAnyOrder("JIRA-1", "CONF#reset-1");
        assertThat(f.status()).isEqualTo(FeatureStatus.PLANNED);   // intent only, no code
    }

    @Test
    void sameControllerCodeEndpointsClusterViaClassAndPathHints() {
        EvidenceUnit get = hintUnit("CODE:PolicyController#GET /policies", SourceKind.CODE, UnitType.ENDPOINT,
                Set.of("policies", "policycontroller"));
        EvidenceUnit post = hintUnit("CODE:PolicyController#POST /policies", SourceKind.CODE, UnitType.ENDPOINT,
                Set.of("policies", "policycontroller"));
        FeatureIndex idx = seeder.seed(List.of(get, post), new SourceMix(true, false, false));

        assertThat(idx.features()).hasSize(1);
        assertThat(idx.features().values().iterator().next().status()).isEqualTo(FeatureStatus.UNDOCUMENTED);
    }

    @Test
    void unrelatedFeaturesDoNotChainThroughASingleSharedNoun() {
        // Six units across four genuinely distinct features; unrelated ones share at most one content word.
        List<EvidenceUnit> corpus = List.of(
                textUnit("L1", SourceKind.JIRA, UnitType.REQUIREMENT, "Login authenticates a member session"),
                textUnit("L2", SourceKind.JIRA, UnitType.REQUIREMENT, "Login session timeout after inactivity"),
                textUnit("T1", SourceKind.JIRA, UnitType.REQUIREMENT, "Transfer funds to a beneficiary"),
                textUnit("T2", SourceKind.JIRA, UnitType.REQUIREMENT, "Transfer funds daily limit validation"),
                textUnit("S1", SourceKind.JIRA, UnitType.REQUIREMENT, "Generate the monthly statement document"),
                textUnit("C1", SourceKind.JIRA, UnitType.REQUIREMENT, "Capture marketing consent preference"));

        FeatureIndex idx = seeder.seed(corpus, new SourceMix(false, true, false));

        // The catastrophic failure would be ONE giant feature. Assert it stays granular: 4 features, none > 2 units.
        assertThat(idx.features()).hasSize(4);
        assertThat(idx.features().values()).allSatisfy(f -> assertThat(f.unitIds().size()).isLessThanOrEqualTo(2));
        // Login pair co-clusters; transfer pair co-clusters; they are NOT in the same feature.
        Feature login = featureContaining(idx, "L1");
        assertThat(login.unitIds()).containsExactlyInAnyOrder("L1", "L2");
        assertThat(featureContaining(idx, "T1").unitIds()).doesNotContain("L1");
    }

    @Test
    void crossCuttingUnitsAreSetAsideAndInjectedIntoEverySlice() {
        EvidenceUnit a = hintUnit("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, Set.of("policy", "rule"));
        EvidenceUnit b = hintUnit("CONF#1", SourceKind.CONFLUENCE, UnitType.DESIGN, Set.of("policy", "rule"));
        EvidenceUnit caveat = hintUnit("CODE:caveat-x", SourceKind.CODE, UnitType.GLOBAL_CAVEAT, Set.of("cross-cutting"));
        FeatureIndex idx = seeder.seed(List.of(a, b, caveat), new SourceMix(true, true, false));

        assertThat(idx.features()).hasSize(1);
        assertThat(idx.crossCuttingIds()).containsExactly("CODE:caveat-x");
        assertThat(idx.unassignedUnitIds()).isEmpty();
        String fid = idx.features().keySet().iterator().next();
        assertThat(idx.sliceOf(fid)).extracting(EvidenceUnit::id).contains("CODE:caveat-x");
    }

    @Test
    void descopedUnitsAreExcludedFromTheIndexEntirely() {
        EvidenceUnit live = EvidenceUnit.jira("JIRA-1", UnitType.REQUIREMENT, "Get policy", "Get policy by id",
                null, "IN_PROGRESS", null, Set.of(), Hints.fromText("Get policy by id"));
        EvidenceUnit descoped = EvidenceUnit.jira("JIRA-2", UnitType.REQUIREMENT, "Old idea", "Won't build this",
                null, "DESCOPED", null, Set.of(), Hints.fromText("Won't build this"));

        FeatureIndex idx = seeder.seed(List.of(live, descoped), new SourceMix(false, true, false));

        assertThat(idx.unitsById()).containsKey("JIRA-1").doesNotContainKey("JIRA-2");
        assertThat(idx.features().values().stream().flatMap(f -> f.unitIds().stream()))
                .contains("JIRA-1").doesNotContain("JIRA-2");
    }

    @Test
    void isDeterministicAndContentDigestedForReRunIdentity() {
        List<EvidenceUnit> corpus = List.of(
                hintUnit("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, Set.of("policy", "rule")),
                hintUnit("CONF#1", SourceKind.CONFLUENCE, UnitType.DESIGN, Set.of("policy", "rule")));
        FeatureIndex a = seeder.seed(corpus, new SourceMix(false, true, true));
        FeatureIndex b = seeder.seed(corpus, new SourceMix(false, true, true));
        assertThat(b.sourceDigest()).isEqualTo(a.sourceDigest());
        assertThat(b.features().keySet()).isEqualTo(a.features().keySet());

        FeatureIndex c = seeder.seed(List.of(corpus.get(0)), new SourceMix(false, true, false));
        assertThat(c.sourceDigest()).isNotEqualTo(a.sourceDigest());
    }

    private static Feature featureContaining(FeatureIndex idx, String unitId) {
        return idx.features().values().stream().filter(f -> f.unitIds().contains(unitId)).findFirst().orElseThrow();
    }
}
