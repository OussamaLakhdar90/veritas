package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.springframework.stereotype.Component;

/**
 * Deterministic ($0) feature clustering — the seed pass of the feature index (design §3). Units are grouped into
 * features by <b>shared stemmed hint</b> (so "Get policy" / "GET /policies" co-cluster across Jira/Confluence/code
 * via the {@link Stemmer}, without an LLM). Cross-cutting units (GLOBAL_CAVEAT/STANDARD) are set aside, not
 * clustered. The result is a complete {@link FeatureIndex} with content-derived {@code featureId}s, a
 * source-presence status per feature, and a content {@code sourceDigest} for re-run identity.
 *
 * <p><b>Conservative by design:</b> two units are linked only when they share at least {@link #MIN_SHARED_HINTS}
 * stemmed hints — a single shared generic noun ("user", "policy") is NOT enough, because over the free-text hints
 * of prose sources a single bridge would transitively collapse unrelated features into one blob. Under-clustering
 * is the recoverable direction: the (later) LLM {@code FeatureTagger} MERGES semantic synonyms the stemmer/seed
 * can't (login/authentication, intent↔endpoint across sources) and canonicalises display names — it never splits,
 * so the seed must not over-merge. Until the tagger lands this is a usable, fully-deterministic conservative index.
 *
 * <p>{@code DESCOPED} units (a won't-do/rejected Jira resolution, §1.2) are excluded from the index here so they
 * don't inflate the plan; the lifecycle drives the {@code PARTIAL}/{@code COVERAGE_GAP} statuses in
 * {@link FeatureStatusEngine}.
 */
@Component
public class FeatureSeeder {

    /** Minimum shared stemmed hints to link two units — the over-merge guard (single-bridge chaining killer). */
    static final int MIN_SHARED_HINTS = 2;

    public FeatureIndex seed(List<EvidenceUnit> units, SourceMix mix) {
        Map<String, EvidenceUnit> byId = new LinkedHashMap<>();
        for (EvidenceUnit u : units) {
            if ("DESCOPED".equals(u.lifecycle())) {
                continue;   // §1.2 — won't-do/rejected intent is out of scope; excluded from the index entirely
            }
            byId.putIfAbsent(u.id(), u);
        }

        // Cross-cutting caveats/standards are injected into every feature, never clustered.
        Set<String> crossCutting = new LinkedHashSet<>();
        List<EvidenceUnit> clusterable = new ArrayList<>();
        for (EvidenceUnit u : byId.values()) {
            if (u.type() == UnitType.GLOBAL_CAVEAT || u.type() == UnitType.STANDARD) {
                crossCutting.add(u.id());
            } else {
                clusterable.add(u);
            }
        }

        // Union-find: link two units only when they share >= MIN_SHARED_HINTS stemmed hints (the over-merge guard).
        int n = clusterable.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        List<Set<String>> stemmed = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            stemmed.add(Stemmer.stemAll(clusterable.get(i).hints()));
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sharedCount(stemmed.get(i), stemmed.get(j)) >= MIN_SHARED_HINTS) {
                    union(parent, i, j);
                }
            }
        }

        // Group by component root → one feature each.
        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            components.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        Map<String, Feature> features = new LinkedHashMap<>();
        for (List<Integer> comp : components.values()) {
            List<String> ids = comp.stream().map(i -> clusterable.get(i).id()).sorted().toList();
            List<EvidenceUnit> compUnits = comp.stream().map(clusterable::get).toList();
            String featureId = "feat-" + EvidenceId.hash8(String.join("|", ids));
            String name = dominantHint(comp, stemmed);
            if (name == null) {   // hint-less cluster → fall back to a unit title, then the feature id
                name = compUnits.stream().map(EvidenceUnit::title).filter(t -> t != null && !t.isBlank())
                        .findFirst().orElse(featureId);
            }
            features.put(featureId, new Feature(featureId, name, ids, FeatureStatusEngine.statusOf(compUnits)));
        }

        String digest = "src-" + EvidenceId.hash8(
                byId.keySet().stream().sorted().collect(Collectors.joining("|")));
        // The deterministic seed assigns every clusterable unit to a component, so nothing is unassigned here.
        return new FeatureIndex(features, byId, crossCutting, Set.of(), mix, digest);
    }

    /** Most frequent stemmed hint in the component (ties → longer then alphabetical); null when the cluster has no hints. */
    private static String dominantHint(List<Integer> comp, List<Set<String>> stemmed) {
        Map<String, Integer> freq = new HashMap<>();
        for (int i : comp) {
            for (String h : stemmed.get(i)) {
                freq.merge(h, 1, Integer::sum);
            }
        }
        return freq.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing((Map.Entry<String, Integer> e) -> -e.getKey().length())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** Number of stemmed hints two units have in common. */
    private static int sharedCount(Set<String> a, Set<String> b) {
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> big = small == a ? b : a;
        int c = 0;
        for (String s : small) {
            if (big.contains(s)) {
                c++;
            }
        }
        return c;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];   // path halving
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);   // deterministic: lower index becomes root
        }
    }
}
