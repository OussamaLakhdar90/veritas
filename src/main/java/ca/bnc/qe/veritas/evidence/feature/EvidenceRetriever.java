package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import org.springframework.stereotype.Component;

/**
 * Turns the feature index into the trimmed, citable material each synthesis section reads (design §3b/§4). Three
 * shapes:
 *
 * <ul>
 *   <li>{@link #forFeature} — the {@code TEST_BASIS} for a feature-scoped section: the feature's units PLUS the
 *       cross-cutting (caveats/standards) injected into every slice, each as a citable line.</li>
 *   <li>{@link #factsCard} — a tiny deterministic header (feature, status, the canonical unit ids) injected into
 *       every section prompt for a feature, so independent section calls share the same hard facts (§3b consistency).</li>
 *   <li>{@link #digest} — feature names + status + counts (no full text) for the global sections.</li>
 * </ul>
 *
 * {@link #allowedIds} is the closed-world citation set for a feature — exactly the ids a section may cite (§4b).
 * Text is already redacted upstream (the {@code Redactor} runs in the adapters), so the retriever just formats it.
 */
@Component
public class EvidenceRetriever {

    /** The citable evidence block for a feature-scoped section: the feature's units + the cross-cutting units. */
    public String forFeature(FeatureIndex index, String featureId) {
        Feature f = index.features().get(featureId);
        StringBuilder sb = new StringBuilder();
        if (f != null) {
            sb.append("Feature: ").append(f.displayName()).append(" [").append(f.status()).append("]\n");
        }
        for (EvidenceUnit u : index.sliceOf(featureId)) {
            sb.append("[").append(u.id()).append("] (").append(u.source()).append('/').append(u.type()).append(") ")
                    .append(nz(u.title())).append(": ").append(nz(u.text())).append('\n');
        }
        return sb.toString();
    }

    /** Canonical facts (ids + status) for a feature — injected verbatim into every one of its section prompts. */
    public String factsCard(FeatureIndex index, String featureId) {
        Feature f = index.features().get(featureId);
        if (f == null) {
            return "";
        }
        return "Facts — " + f.displayName() + " [" + f.status() + "]; cite only: "
                + String.join(", ", allowedIds(index, featureId)) + ".";
    }

    /** A whole-index digest for the global sections: one line per feature, no full text. */
    public String digest(FeatureIndex index) {
        StringBuilder sb = new StringBuilder();
        for (Feature f : index.features().values()) {
            sb.append("- ").append(f.displayName()).append(" [").append(f.status()).append("] — ")
                    .append(f.unitIds().size()).append(" unit(s)\n");
        }
        return sb.toString();
    }

    /**
     * The closed-world citation set for a feature: its own unit ids + the cross-cutting ids injected everywhere —
     * filtered to ids actually resolvable in {@code unitsById}, so the allowed set is <b>exactly</b> what
     * {@link #forFeature} shows the model (it can never be told to cite an id it wasn't shown).
     */
    public Set<String> allowedIds(FeatureIndex index, String featureId) {
        Set<String> ids = new LinkedHashSet<>();
        Feature f = index.features().get(featureId);
        if (f != null) {
            ids.addAll(f.unitIds());
        }
        ids.addAll(index.crossCuttingIds());
        ids.removeIf(id -> !index.unitsById().containsKey(id));
        return ids;
    }

    /**
     * A content fingerprint over a feature's <b>entire citable set</b> (its own units PLUS the cross-cutting units),
     * including each unit's title + text — exactly the material {@link #forFeature} shows the model. Two runs with
     * the same fingerprint for a feature would synthesize from byte-identical grounding, so a prior section can be
     * reused verbatim (incremental regen, design §3.2). Because the cross-cutting ids are folded in, a changed
     * caveat/standard flips the fingerprint of <b>every</b> feature — correctly forcing a full re-synthesis.
     */
    public String groundingFingerprint(FeatureIndex index, String featureId) {
        String sep = String.valueOf((char) 1);   // a delimiter that can't appear in ids/titles/text, so fields can't bleed
        List<String> ids = new ArrayList<>(new TreeSet<>(allowedIds(index, featureId)));   // sorted, stable
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            EvidenceUnit u = index.unitsById().get(id);
            sb.append(id).append(sep)
                    .append(u == null ? "" : u.source()).append(sep)
                    .append(u == null ? "" : u.type()).append(sep)
                    .append(u == null ? "" : nz(u.title())).append(sep)
                    .append(u == null ? "" : nz(u.text())).append(sep);
        }
        return EvidenceId.hash8(sb.toString());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
