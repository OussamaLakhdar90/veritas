package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;

/** Source-presence status axis (Phase 3a): intent+code → IMPLEMENTED, intent-only → PLANNED, code-only → UNDOCUMENTED. */
class FeatureStatusEngineTest {

    private static EvidenceUnit u(SourceKind src, UnitType type) {
        return EvidenceUnit.of(src + "-" + type, src, type, "t", "text", null, Set.of());
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
}
