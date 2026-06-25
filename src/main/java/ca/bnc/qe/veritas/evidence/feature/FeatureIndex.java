package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceMix;

/**
 * The feature-clustered view of a run's evidence — the spine the per-section retrieval reads from (design §3).
 * Cross-cutting units (GLOBAL_CAVEAT/STANDARD) sit in their own set and are injected into every feature's slice
 * rather than clustered. Re-run identity is keyed by {@code sourceDigest}: an unchanged digest means the cached
 * index can be reused as-is.
 *
 * @param features        featureId → {@link Feature}
 * @param unitsById       every clusterable + cross-cutting unit, by id
 * @param crossCuttingIds GLOBAL_CAVEAT/STANDARD unit ids, force-injected into every feature slice
 * @param unassignedUnitIds completeness escape hatch — clusterable units that landed in no feature (must be empty
 *                        in the deterministic Phase-3a seed; the LLM tagger may populate it, surfaced fail-loud)
 * @param mix             the realised source mix
 * @param sourceDigest    content digest for cache/re-run identity
 */
public record FeatureIndex(
        Map<String, Feature> features,
        Map<String, EvidenceUnit> unitsById,
        Set<String> crossCuttingIds,
        Set<String> unassignedUnitIds,
        SourceMix mix,
        String sourceDigest) {

    public FeatureIndex {
        features = features == null ? Map.of() : Map.copyOf(features);
        unitsById = unitsById == null ? Map.of() : Map.copyOf(unitsById);
        crossCuttingIds = crossCuttingIds == null ? Set.of() : Set.copyOf(crossCuttingIds);
        unassignedUnitIds = unassignedUnitIds == null ? Set.of() : Set.copyOf(unassignedUnitIds);
    }

    /** The units belonging to a feature (excluding cross-cutting). */
    public List<EvidenceUnit> unitsOf(String featureId) {
        Feature f = features.get(featureId);
        if (f == null) {
            return List.of();
        }
        return resolve(f.unitIds());
    }

    /** The retrieval slice for a feature: its own units PLUS the cross-cutting (caveats/standards) injected everywhere. */
    public List<EvidenceUnit> sliceOf(String featureId) {
        List<EvidenceUnit> slice = new ArrayList<>(unitsOf(featureId));
        slice.addAll(resolve(crossCuttingIds));
        return slice;
    }

    private List<EvidenceUnit> resolve(Iterable<String> ids) {
        List<EvidenceUnit> out = new ArrayList<>();
        for (String id : ids) {
            EvidenceUnit u = unitsById.get(id);
            if (u != null) {
                out.add(u);
            }
        }
        return out;
    }
}
