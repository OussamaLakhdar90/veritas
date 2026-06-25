package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;

/** The per-feature slice + facts card + digest + the closed-world allowed-id set that synthesis reads. */
class EvidenceRetrieverTest {

    private final EvidenceRetriever retriever = new EvidenceRetriever();

    private FeatureIndex index() {
        EvidenceUnit jira = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "Lockout",
                "Account locks after 5 attempts", null, Set.of());
        EvidenceUnit code = EvidenceUnit.of("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "POST /login",
                "no rate-limit annotation", null, Set.of());
        EvidenceUnit caveat = EvidenceUnit.of("CODE:caveat-1", SourceKind.CODE, UnitType.GLOBAL_CAVEAT, "caveat",
                "authorization is centralized", null, Set.of());
        Feature f = new Feature("feat-1", "login", List.of("JIRA-1", "CODE-1"), FeatureStatus.IMPLEMENTED);
        return new FeatureIndex(Map.of("feat-1", f),
                Map.of("JIRA-1", jira, "CODE-1", code, "CODE:caveat-1", caveat),
                Set.of("CODE:caveat-1"), Set.of(), new SourceMix(true, true, false), "src");
    }

    @Test
    void forFeatureIncludesTheFeatureUnitsAndTheCrossCuttingSlice() {
        String basis = retriever.forFeature(index(), "feat-1");
        assertThat(basis)
                .contains("login [IMPLEMENTED]")
                .contains("[JIRA-1]").contains("Account locks after 5 attempts")
                .contains("[CODE-1]").contains("no rate-limit annotation")
                .contains("[CODE:caveat-1]").contains("authorization is centralized");   // cross-cutting injected
    }

    @Test
    void factsCardCarriesTheNameStatusAndAllowedIds() {
        String card = retriever.factsCard(index(), "feat-1");
        assertThat(card).contains("login").contains("IMPLEMENTED")
                .contains("JIRA-1").contains("CODE-1").contains("CODE:caveat-1");
    }

    @Test
    void allowedIdsAreTheFeatureUnitsPlusCrossCutting() {
        assertThat(retriever.allowedIds(index(), "feat-1"))
                .containsExactlyInAnyOrder("JIRA-1", "CODE-1", "CODE:caveat-1");
    }

    @Test
    void allowedIdsExcludeIdsAbsentFromUnitsById() {
        // A feature whose unitIds include a dangling id → the allowed set must not offer what forFeature can't show.
        EvidenceUnit jira = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "t", "text", null, Set.of());
        FeatureIndex idx = new FeatureIndex(
                Map.of("feat-x", new Feature("feat-x", "x", List.of("JIRA-1", "DANGLING"), FeatureStatus.PLANNED)),
                Map.of("JIRA-1", jira), Set.of(), Set.of(), new SourceMix(false, true, false), "src");
        assertThat(retriever.allowedIds(idx, "feat-x")).containsExactly("JIRA-1");   // DANGLING excluded
    }

    @Test
    void digestListsEachFeatureWithStatusButNoFullText() {
        String digest = retriever.digest(index());
        assertThat(digest).contains("login [IMPLEMENTED]").contains("2 unit(s)")
                .doesNotContain("Account locks after 5 attempts");   // no full text in the digest
    }
}
