package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Computes the deterministic ($0) {@link StrategyScorecard} for an assembled multi-source strategy (design §5b).
 * Each check turns a silent-failure surface of the pipeline into an explicit pass/fail: a dropped section, a
 * feature with a risk register but no test approach (cross-section drift), an implemented feature with no strategy,
 * a pending feature never raised as a gap, or a section that cites no evidence. Any failure degrades the run.
 *
 * <p>Sections in the deliverable carry both the feature <b>display name</b> ({@code feature}) and the stable
 * {@code featureId}; gaps carry the {@code featureId} ({@code gaps[].feature}). The checks key on {@code featureId}
 * (display names can collide after LLM canonicalisation) and surface display names only in the detail text.
 */
@Component
public class StrategyScorecardEngine {

    public StrategyScorecard score(JsonNode deliverable, FeatureIndexResult result, List<String> droppedSections) {
        FeatureIndex index = result.index();
        Map<String, String> nameById = new LinkedHashMap<>();
        index.features().values().forEach(f -> nameById.put(f.featureId(), f.displayName()));

        Set<String> inRisk = featureIds(deliverable.path("riskRegister"), "featureId");
        Set<String> inApproach = featureIds(deliverable.path("testApproach"), "featureId");
        Set<String> covered = new LinkedHashSet<>(inRisk);
        covered.addAll(inApproach);
        Set<String> gapFeatureIds = featureIds(deliverable.path("gaps"), "feature");   // gaps carry featureId here
        List<String> dropped = droppedSections == null ? List.of() : droppedSections;

        List<StrategyScorecard.Check> checks = new ArrayList<>();

        // 1. Every section was grounded (nothing dropped for lack of citable evidence).
        checks.add(check("All sections grounded in evidence", dropped.isEmpty(),
                "No sections were dropped.", dropped.size() + " section(s) dropped: " + String.join(", ", dropped)));

        // 2. Cross-section drift: every covered feature has BOTH a risk register and a test approach.
        List<String> asymmetric = covered.stream()
                .filter(id -> !(inRisk.contains(id) && inApproach.contains(id)))
                .map(id -> nameById.getOrDefault(id, id)).sorted().toList();
        checks.add(check("Each covered feature has both a risk register and a test approach", asymmetric.isEmpty(),
                "All covered features have both sections.", "Asymmetric coverage for: " + String.join(", ", asymmetric)));

        // 3. Every IMPLEMENTED/PARTIAL feature has a strategy section.
        List<String> uncoveredBuilt = index.features().values().stream()
                .filter(f -> f.status() == FeatureStatus.IMPLEMENTED || f.status() == FeatureStatus.PARTIAL)
                .filter(f -> !covered.contains(f.featureId())).map(Feature::displayName).sorted().toList();
        checks.add(check("Every implemented / partial feature has a strategy section", uncoveredBuilt.isEmpty(),
                "All built features are covered.", "No section for: " + String.join(", ", uncoveredBuilt)));

        // 4. Every PLANNED/COVERAGE_GAP feature is raised as a gap (pending work is never silently dropped).
        List<String> pendingMissingGap = index.features().values().stream()
                .filter(f -> f.status() == FeatureStatus.PLANNED || f.status() == FeatureStatus.COVERAGE_GAP)
                .filter(f -> !gapFeatureIds.contains(f.featureId())).map(Feature::displayName).sorted().toList();
        checks.add(check("Every planned / coverage-gap feature is raised as a gap", pendingMissingGap.isEmpty(),
                "All pending features are raised.", "Not raised as a gap: " + String.join(", ", pendingMissingGap)));

        // 5. Every generated section cites at least one piece of evidence.
        long ungrounded = sectionsWithoutEvidence(deliverable.path("riskRegister"))
                + sectionsWithoutEvidence(deliverable.path("testApproach"));
        checks.add(check("Every generated section cites evidence", ungrounded == 0,
                "All sections cite at least one unit.", ungrounded + " section(s) cite no evidence."));

        long passed = checks.stream().filter(StrategyScorecard.Check::passed).count();
        int confidence = checks.isEmpty() ? 0 : (int) Math.round(100.0 * passed / checks.size());
        String verdict = passed == checks.size() ? StrategyScorecard.OK : StrategyScorecard.DEGRADED;
        return new StrategyScorecard(verdict, confidence, checks, covered.size(), dropped.size());
    }

    private static StrategyScorecard.Check check(String name, boolean passed, String okDetail, String failDetail) {
        return new StrategyScorecard.Check(name, passed, passed ? okDetail : failDetail);
    }

    private static Set<String> featureIds(JsonNode array, String field) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode n : array) {
            String id = n.path(field).asText("");
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static long sectionsWithoutEvidence(JsonNode array) {
        long count = 0;
        for (JsonNode n : array) {
            JsonNode evidence = n.path("evidence");
            if (!evidence.isArray() || evidence.isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
