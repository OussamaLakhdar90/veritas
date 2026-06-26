package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;

/**
 * Mutation-killing tests for {@link FeatureStatusEngine} (added, never edits the existing
 * {@code FeatureStatusEngineTest}). Each test pins a concrete status for an input whose result DIFFERS
 * under the surviving conditional mutants in {@code statusOf} — in particular the
 * {@code u.source() == SourceKind.JIRA} half of the intent guard on line 38 (negating it would stop a
 * JIRA-only unit from registering as intent, flipping COVERAGE_GAP/PLANNED into the wrong bucket).
 */
class FeatureStatusEngineMutationTest {

    private static EvidenceUnit jira(String id, UnitType type, String lifecycle) {
        return EvidenceUnit.jira(id, type, "t", "text", null, lifecycle, null, Set.of(), Set.of());
    }

    private static EvidenceUnit code(String id) {
        return EvidenceUnit.of(id, SourceKind.CODE, UnitType.ENDPOINT, "t", "text", null, Set.of());
    }

    private static EvidenceUnit confluence(String id) {
        return EvidenceUnit.of(id, SourceKind.CONFLUENCE, UnitType.DESIGN, "t", "text", null, Set.of());
    }

    // ---- Line 38: the JIRA half of the intent guard (the surviving NegateConditionals mutant) ----

    /**
     * A single DONE JIRA unit, no code, no Confluence. Original: JIRA registers as intent + done →
     * COVERAGE_GAP. If {@code source()==JIRA} is negated, the unit is no longer treated as intent, so
     * hasIntent stays false and the engine returns PLANNED — this assertion distinguishes the two.
     */
    @Test
    void singleDoneJiraOnlyIsCoverageGapNotPlanned() {
        FeatureStatus s = FeatureStatusEngine.statusOf(List.of(jira("JIRA-1", UnitType.REQUIREMENT, "DONE")));
        assertThat(s).isEqualTo(FeatureStatus.COVERAGE_GAP);
        assertThat(s).isNotEqualTo(FeatureStatus.PLANNED);
    }

    /**
     * A single TO_DO JIRA unit, no code. Original: JIRA registers as active intent → PLANNED (and the
     * intent branch was taken). If the JIRA guard is negated the unit is ignored entirely; the result is
     * still PLANNED, so to separate the mutants we also assert a DONE+TODO mix below.
     */
    @Test
    void singleActiveJiraOnlyIsPlanned() {
        assertThat(FeatureStatusEngine.statusOf(List.of(jira("JIRA-1", UnitType.REQUIREMENT, "TO_DO"))))
                .isEqualTo(FeatureStatus.PLANNED);
    }

    /**
     * Two JIRA units — one DONE, one IN_PROGRESS — no code. Original: intentDone && intentActive both true,
     * and line 55 requires {@code intentDone && !intentActive} → PLANNED (active suppresses the gap). A
     * single covering test that pins this exact done+active combination keeps the {@code !intentActive}
     * half of line 55 honest. Negating the JIRA guard would drop both units to PLANNED too, so this pairs
     * with the single-DONE-JIRA case (COVERAGE_GAP) which a JIRA-drop would break.
     */
    @Test
    void doneAndActiveJiraWithoutCodeIsPlannedNotCoverageGap() {
        FeatureStatus s = FeatureStatusEngine.statusOf(List.of(
                jira("JIRA-DONE", UnitType.REQUIREMENT, "DONE"),
                jira("JIRA-WIP", UnitType.ACCEPTANCE_CRITERIA, "IN_PROGRESS")));
        assertThat(s).isEqualTo(FeatureStatus.PLANNED);
        assertThat(s).isNotEqualTo(FeatureStatus.COVERAGE_GAP);
    }

    // ---- code + intent branch (line 47/49): active vs done lifecycle ----

    /** DONE JIRA + code → IMPLEMENTED (intentActive false). Distinguishes IMPLEMENTED from PARTIAL on line 49. */
    @Test
    void doneJiraWithCodeIsImplemented() {
        assertThat(FeatureStatusEngine.statusOf(List.of(
                jira("JIRA-1", UnitType.REQUIREMENT, "DONE"), code("CODE-1"))))
                .isEqualTo(FeatureStatus.IMPLEMENTED);
    }

    /** Active JIRA + code → PARTIAL (still in flight). The other side of the line-49 ternary. */
    @Test
    void activeJiraWithCodeIsPartial() {
        assertThat(FeatureStatusEngine.statusOf(List.of(
                jira("JIRA-1", UnitType.REQUIREMENT, "TO_DO"), code("CODE-1"))))
                .isEqualTo(FeatureStatus.PARTIAL);
    }

    // ---- code-only (line 51) ----

    @Test
    void codeOnlyIsUndocumented() {
        assertThat(FeatureStatusEngine.statusOf(List.of(code("CODE-1"))))
                .isEqualTo(FeatureStatus.UNDOCUMENTED);
    }

    // ---- Confluence-only intent: no lifecycle claim → PLANNED (not COVERAGE_GAP) ----

    @Test
    void confluenceOnlyIntentIsPlanned() {
        assertThat(FeatureStatusEngine.statusOf(List.of(confluence("CONF-1"))))
                .isEqualTo(FeatureStatus.PLANNED);
    }

    /**
     * DONE JIRA joined by a lifecycle-less Confluence unit, no code → COVERAGE_GAP. Confluence registers as
     * intent (line 38 CONFLUENCE half) but makes no lifecycle claim, so it neither activates nor suppresses
     * the gap; the JIRA DONE still wins.
     */
    @Test
    void doneJiraPlusLifecyclelessConfluenceIsCoverageGap() {
        assertThat(FeatureStatusEngine.statusOf(List.of(
                jira("JIRA-1", UnitType.REQUIREMENT, "DONE"), confluence("CONF-1"))))
                .isEqualTo(FeatureStatus.COVERAGE_GAP);
    }

    /**
     * Code PLUS a Confluence-only intent (no JIRA) → IMPLEMENTED. This is the input that kills the surviving
     * line-38 mutant: negating {@code u.source() == SourceKind.CONFLUENCE} turns the guard into
     * {@code == JIRA || != CONFLUENCE}, which stops the Confluence unit from registering as intent — so
     * hasIntent would be false and the engine would return UNDOCUMENTED (code only) instead of IMPLEMENTED.
     * A confluence-only feature with no lifecycle and no code stays PLANNED either way (intent-or-not both
     * yield PLANNED), so it is precisely the CODE+CONFLUENCE pairing that distinguishes the mutant.
     */
    @Test
    void codePlusConfluenceIntentIsImplementedNotUndocumented() {
        FeatureStatus s = FeatureStatusEngine.statusOf(List.of(code("CODE-1"), confluence("CONF-1")));
        assertThat(s).isEqualTo(FeatureStatus.IMPLEMENTED);
        assertThat(s).isNotEqualTo(FeatureStatus.UNDOCUMENTED);
    }
}