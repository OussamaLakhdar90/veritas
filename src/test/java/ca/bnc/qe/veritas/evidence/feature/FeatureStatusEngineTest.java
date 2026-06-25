package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;

/**
 * Two-axis status (source presence × Jira lifecycle): null-lifecycle keeps the source-presence result
 * (IMPLEMENTED/PLANNED/UNDOCUMENTED, backward-compatible); a populated lifecycle yields PARTIAL / COVERAGE_GAP.
 */
class FeatureStatusEngineTest {

    private static EvidenceUnit u(SourceKind src, UnitType type) {
        return EvidenceUnit.of(src + "-" + type, src, type, "t", "text", null, Set.of());
    }

    private static EvidenceUnit jira(UnitType type, String lifecycle) {
        return EvidenceUnit.jira("JIRA-" + lifecycle + "-" + type, type, "t", "text", null,
                lifecycle, null, Set.of(), Set.of());
    }

    @Test
    void implementedWhenIntentAndCodeBothPresent() {
        assertThat(FeatureStatusEngine.statusOf(List.of(
                u(SourceKind.JIRA, UnitType.REQUIREMENT), u(SourceKind.CODE, UnitType.ENDPOINT))))
                .isEqualTo(FeatureStatus.IMPLEMENTED);
    }

    @Test
    void plannedWhenIntentOnly() {
        assertThat(FeatureStatusEngine.statusOf(List.of(
                u(SourceKind.JIRA, UnitType.REQUIREMENT), u(SourceKind.CONFLUENCE, UnitType.DESIGN))))
                .isEqualTo(FeatureStatus.PLANNED);
    }

    @Test
    void undocumentedWhenCodeOnly() {
        assertThat(FeatureStatusEngine.statusOf(List.of(
                u(SourceKind.CODE, UnitType.ENDPOINT), u(SourceKind.CODE, UnitType.DTO_CONSTRAINT))))
                .isEqualTo(FeatureStatus.UNDOCUMENTED);
    }

    @Test
    void partialWhenCodeExistsButJiraIntentIsStillActive() {
        assertThat(FeatureStatusEngine.statusOf(List.of(
                jira(UnitType.REQUIREMENT, "IN_PROGRESS"), u(SourceKind.CODE, UnitType.ENDPOINT))))
                .isEqualTo(FeatureStatus.PARTIAL);
    }

    @Test
    void implementedWhenCodeExistsAndJiraIsDone() {
        assertThat(FeatureStatusEngine.statusOf(List.of(
                jira(UnitType.REQUIREMENT, "DONE"), u(SourceKind.CODE, UnitType.ENDPOINT))))
                .isEqualTo(FeatureStatus.IMPLEMENTED);
    }

    @Test
    void coverageGapWhenJiraIsDoneButNoCode() {
        assertThat(FeatureStatusEngine.statusOf(List.of(jira(UnitType.REQUIREMENT, "DONE"))))
                .isEqualTo(FeatureStatus.COVERAGE_GAP);
    }

    @Test
    void plannedWhenJiraActiveAndNoCode() {
        assertThat(FeatureStatusEngine.statusOf(List.of(jira(UnitType.REQUIREMENT, "TO_DO"))))
                .isEqualTo(FeatureStatus.PLANNED);
    }

    @Test
    void aDoneJiraWithUndatedConfluenceIntentAndNoCodeIsACoverageGap() {
        // Deliberate: a done Jira item with no code is the coverage signal even when joined by undated Confluence
        // design (the Confluence unit carries no lifecycle, so it neither activates nor suppresses the gap).
        assertThat(FeatureStatusEngine.statusOf(List.of(
                jira(UnitType.REQUIREMENT, "DONE"), u(SourceKind.CONFLUENCE, UnitType.DESIGN))))
                .isEqualTo(FeatureStatus.COVERAGE_GAP);
    }

    @Test
    void anyActiveIntentWithCodeIsPartialNotImplemented() {
        assertThat(FeatureStatusEngine.statusOf(List.of(
                jira(UnitType.REQUIREMENT, "DONE"), jira(UnitType.ACCEPTANCE_CRITERIA, "IN_PROGRESS"),
                u(SourceKind.CODE, UnitType.ENDPOINT))))
                .isEqualTo(FeatureStatus.PARTIAL);
    }
}
