package ca.bnc.qe.veritas.evidence.feature;

import java.util.Collection;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;

/**
 * Deterministic two-axis per-feature status (design §1.2/§3.1): <b>source presence</b> (code and/or intent) ×
 * the dominant <b>Jira lifecycle</b> of the intent units.
 *
 * <ul>
 *   <li>code + intent, intent still <b>active</b> (to-do/in-progress) → {@link FeatureStatus#PARTIAL}
 *       (being built — doesn't flip fully green on one code unit);</li>
 *   <li>code + intent otherwise (Jira done, or no lifecycle known) → {@link FeatureStatus#IMPLEMENTED};</li>
 *   <li>code only → {@link FeatureStatus#UNDOCUMENTED};</li>
 *   <li>intent only, Jira <b>done</b> → {@link FeatureStatus#COVERAGE_GAP} (marked done, no code — the RTM signal);</li>
 *   <li>intent only, otherwise → {@link FeatureStatus#PLANNED}.</li>
 * </ul>
 *
 * <p>The finer {@code PARTIAL}/{@code COVERAGE_GAP} states only appear once the Jira lifecycle is populated; a unit
 * with no lifecycle (Confluence, or Jira fetched without status) makes <b>no</b> lifecycle claim, so the engine
 * falls back to the source-presence result (backward-compatible). {@code DESCOPED} units are excluded upstream
 * ({@code FeatureSeeder}), so they never reach here.
 */
public final class FeatureStatusEngine {

    private FeatureStatusEngine() {
    }

    public static FeatureStatus statusOf(Collection<EvidenceUnit> units) {
        boolean hasCode = false;
        boolean hasIntent = false;
        boolean intentActive = false;   // any Jira intent in to-do/in-progress
        boolean intentDone = false;     // any Jira intent done/closed
        for (EvidenceUnit u : units) {
            if (u.source() == SourceKind.CODE) {
                hasCode = true;
            } else if (u.source() == SourceKind.JIRA || u.source() == SourceKind.CONFLUENCE) {
                hasIntent = true;
                if ("DONE".equals(u.lifecycle())) {
                    intentDone = true;
                } else if ("TO_DO".equals(u.lifecycle()) || "IN_PROGRESS".equals(u.lifecycle())) {
                    intentActive = true;
                }
            }
        }
        if (hasCode && hasIntent) {
            // Still being built (Jira active) → PARTIAL; otherwise (done, or unknown lifecycle) → IMPLEMENTED.
            return intentActive ? FeatureStatus.PARTIAL : FeatureStatus.IMPLEMENTED;
        }
        if (hasCode) {
            return FeatureStatus.UNDOCUMENTED;
        }
        // Intent only: "done in Jira, no code" is the coverage gap; anything still active/unknown is planned.
        return (intentDone && !intentActive) ? FeatureStatus.COVERAGE_GAP : FeatureStatus.PLANNED;
    }
}
