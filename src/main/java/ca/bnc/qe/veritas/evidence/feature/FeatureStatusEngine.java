package ca.bnc.qe.veritas.evidence.feature;

import java.util.Collection;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;

/**
 * Deterministic per-feature status (design §3.1). Phase 3a computes the <b>source-presence</b> axis only —
 * {@link FeatureStatus#IMPLEMENTED} (intent + code), {@link FeatureStatus#PLANNED} (intent only),
 * {@link FeatureStatus#UNDOCUMENTED} (code only). The {@link FeatureStatus#PARTIAL} and
 * {@link FeatureStatus#COVERAGE_GAP} states need per-endpoint code presence and the Jira lifecycle, which the
 * Jira field-widening follow-up supplies; until then they're never emitted (documented limitation).
 */
public final class FeatureStatusEngine {

    private FeatureStatusEngine() {
    }

    public static FeatureStatus statusOf(Collection<EvidenceUnit> units) {
        boolean hasCode = false;
        boolean hasIntent = false;
        for (EvidenceUnit u : units) {
            if (u.source() == SourceKind.CODE) {
                hasCode = true;
            } else if (u.source() == SourceKind.JIRA || u.source() == SourceKind.CONFLUENCE) {
                hasIntent = true;
            }
        }
        if (hasCode && hasIntent) {
            return FeatureStatus.IMPLEMENTED;
        }
        if (hasCode) {
            return FeatureStatus.UNDOCUMENTED;
        }
        return FeatureStatus.PLANNED;   // intent-only (or empty) → planned/pending
    }
}
