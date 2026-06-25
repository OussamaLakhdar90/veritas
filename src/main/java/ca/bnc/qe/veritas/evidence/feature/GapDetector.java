package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import org.springframework.stereotype.Component;

/**
 * Deterministic ($0) coverage-gap analysis over a {@link FeatureIndex} (design §3.3). It turns the per-feature
 * status and the clustering into explicit, citable gaps — so the "Jira says X, code does Y" risk is a guaranteed
 * output, not something the LLM has to happen to notice:
 *
 * <ul>
 *   <li><b>Presence gaps</b> straight from status — {@code PLANNED} (specified, not built) and
 *       {@code UNDOCUMENTED} (built, unspecified).</li>
 *   <li><b>Mis-cluster guard</b> — an intent-only feature and a code-only feature that share at least
 *       {@code FeatureSeeder.MIN_SHARED_HINTS} stemmed hints (a <em>lexical near-miss</em> the conservative seed
 *       couldn't bridge via any single unit pair) probably describe the same capability; flag it, because otherwise
 *       the intent↔implementation gap is invisible. True <em>synonyms</em> (login/auth — zero shared stems) are the
 *       LLM tagger's job, not this deterministic guard.</li>
 *   <li><b>Contradiction-check markers</b> — every {@code IMPLEMENTED} feature is flagged so synthesis must assert
 *       agreement or emit a gap-risk (§3.3c), never silently assume the spec and the code match.</li>
 * </ul>
 */
@Component
public class GapDetector {

    public GapReport detect(FeatureIndex index) {
        List<Gap> gaps = new ArrayList<>();
        Set<String> contradiction = new LinkedHashSet<>();

        Map<String, Set<String>> hintsByFeature = new LinkedHashMap<>();
        List<Feature> planned = new ArrayList<>();
        List<Feature> undocumented = new ArrayList<>();

        for (Feature f : index.features().values()) {
            List<EvidenceUnit> units = index.unitsOf(f.featureId());
            if (units.isEmpty()) {
                continue;   // an empty feature can't be cited — evidence-first, skip it
            }
            Set<String> hints = new LinkedHashSet<>();
            for (EvidenceUnit u : units) {
                hints.addAll(Stemmer.stemAll(u.hints()));
            }
            hintsByFeature.put(f.featureId(), hints);

            switch (f.status()) {
                case PLANNED -> {
                    gaps.add(new Gap(GapKind.PLANNED_NOT_IMPLEMENTED, f.featureId(),
                            "\"" + f.displayName() + "\" is specified but no implementing code was found — "
                                    + "its tests are pending until it's built.", f.unitIds()));
                    planned.add(f);
                }
                case UNDOCUMENTED -> {
                    gaps.add(new Gap(GapKind.IMPLEMENTED_UNDOCUMENTED, f.featureId(),
                            "\"" + f.displayName() + "\" is implemented but unspecified — confirm the intent "
                                    + "(possible scope creep).", f.unitIds()));
                    undocumented.add(f);
                }
                case IMPLEMENTED -> contradiction.add(f.featureId());
                default -> {
                    // PARTIAL / COVERAGE_GAP need the Jira lifecycle (deferred with the field widening).
                }
            }
        }

        // Mis-cluster guard: an intent-only (PLANNED) and a code-only (UNDOCUMENTED) feature that share a hint most
        // likely describe the same capability the tagger failed to merge — surface it so the gap isn't lost.
        for (Feature p : planned) {
            for (Feature u : undocumented) {
                Set<String> shared = intersect(hintsByFeature.get(p.featureId()), hintsByFeature.get(u.featureId()));
                // Match the seeder's bar (>= MIN_SHARED_HINTS): a single shared generic noun is exactly the weak
                // signal the seed correctly refused to merge on, so flagging it here would just be noise.
                if (shared.size() >= FeatureSeeder.MIN_SHARED_HINTS) {
                    List<String> cited = new ArrayList<>();
                    if (!p.unitIds().isEmpty()) {
                        cited.add(p.unitIds().get(0));
                    }
                    if (!u.unitIds().isEmpty()) {
                        cited.add(u.unitIds().get(0));
                    }
                    gaps.add(new Gap(GapKind.POSSIBLE_MISCLUSTER, p.featureId(),
                            "Intent \"" + p.displayName() + "\" and endpoint \"" + u.displayName() + "\" share '"
                                    + String.join(", ", shared) + "' but are in different features — the gap between "
                                    + "what's specified and what's built may be invisible; review the clustering.", cited));
                }
            }
        }

        return new GapReport(gaps, contradiction);
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.retainAll(b);
        return out;
    }
}
