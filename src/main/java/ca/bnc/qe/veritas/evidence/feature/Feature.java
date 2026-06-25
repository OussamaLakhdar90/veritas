package ca.bnc.qe.veritas.evidence.feature;

import java.util.List;

/**
 * One feature: a cluster of evidence unit ids that describe the same capability (e.g. "Get policy"). The
 * {@code featureId} is content-derived (a hash of the sorted member unit ids) so the same logical feature keeps
 * its identity across re-runs even when the LLM renames the {@code displayName} (design §3).
 *
 * @param featureId   stable, content-derived id ({@code feat-<hash>})
 * @param displayName human-readable label (deterministic dominant hint in Phase 3a; LLM-canonicalised later)
 * @param unitIds     the member evidence unit ids (sorted, stable)
 * @param status      implementation status (see {@link FeatureStatus})
 */
public record Feature(String featureId, String displayName, List<String> unitIds, FeatureStatus status) {

    public Feature {
        unitIds = unitIds == null ? List.of() : List.copyOf(unitIds);
    }
}
