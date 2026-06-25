package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.evidence.SourceMix;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Assembles a multi-source test strategy from a {@link FeatureIndexResult} (design §3b/§4): for each feature it
 * generates the feature-scoped sections via {@link EvidenceFirstSectionGenerator} (evidence-first, citation-validated,
 * regenerate-once, drop-on-fail), merges the surviving sections into the flat deliverable arrays, folds in the
 * deterministic {@link GapReport} gaps, and totals the cost. Cost-routed per design §7 — {@code riskRegister} is
 * the load-bearing DEEP call, {@code testApproach} STANDARD.
 *
 * <p>This is the synthesis itself; it does not persist (that's the wiring layer) — it returns the deliverable + cost.
 */
@Service
@Slf4j
public class MultiSourceStrategyAssembler {

    private record SectionSpec(String key, ModelTier tier, String instruction, Set<String> pack) {}

    /** The feature-scoped sections, cost-routed (§7): DEEP only for the load-bearing risk register. */
    private static final List<SectionSpec> FEATURE_SECTIONS = List.of(
            new SectionSpec("riskRegister", ModelTier.DEEP,
                    "List this feature's product and project risks; for each give id, description, likelihood, "
                            + "impact, level, mitigation.", Set.of("1", "9")),
            new SectionSpec("testApproach", ModelTier.STANDARD,
                    "Describe the test approach for this feature: levels, types, and techniques, each tied to a risk.",
                    Set.of("1", "6")));

    private final EvidenceFirstSectionGenerator sectionGenerator;
    private final ObjectMapper objectMapper;

    public MultiSourceStrategyAssembler(EvidenceFirstSectionGenerator sectionGenerator, ObjectMapper objectMapper) {
        this.sectionGenerator = sectionGenerator;
        this.objectMapper = objectMapper;
    }

    public AssembledStrategy assemble(String serviceName, FeatureIndexResult result, String owner) {
        FeatureIndex index = result.index();
        ObjectNode deliverable = objectMapper.createObjectNode();
        Map<String, ArrayNode> arrays = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        double cost = 0;

        for (Feature feature : index.features().values()) {
            for (SectionSpec spec : FEATURE_SECTIONS) {
                SectionResult section = sectionGenerator.generate(spec.key(), spec.instruction(), spec.pack(),
                        spec.tier(), index, feature.featureId(), owner);
                cost += section.costUsd();
                if (section.node().isPresent()) {
                    ObjectNode node = (ObjectNode) section.node().get();
                    node.put("feature", feature.displayName());          // canonical name over the LLM's
                    node.put("featureId", feature.featureId());          // stable key (display names can collide)
                    node.put("featureStatus", feature.status().name());
                    arrays.computeIfAbsent(spec.key(), k -> objectMapper.createArrayNode()).add(node);
                } else {
                    dropped.add(spec.key() + " / " + feature.displayName());
                }
            }
        }
        arrays.forEach(deliverable::set);
        deliverable.set("gaps", gapsNode(result.gaps()));
        deliverable.put("summary", summary(serviceName, index, result));
        deliverable.put("estCostUsd", cost);

        log.info("Assembled strategy for {}: {} feature(s), {} dropped section(s), est cost ${}",
                serviceName, index.features().size(), dropped.size(), String.format("%.4f", cost));
        return new AssembledStrategy(deliverable, dropped, index.features().size(), cost);
    }

    private ArrayNode gapsNode(GapReport report) {
        ArrayNode gaps = objectMapper.createArrayNode();
        for (Gap g : report.gaps()) {
            ObjectNode gn = objectMapper.createObjectNode();
            gn.put("kind", g.kind().name());
            gn.put("feature", g.featureId());
            gn.put("message", g.message());
            ArrayNode cited = objectMapper.createArrayNode();
            g.citedUnitIds().forEach(cited::add);
            gn.set("citedUnitIds", cited);
            gaps.add(gn);
        }
        return gaps;
    }

    private String summary(String serviceName, FeatureIndex index, FeatureIndexResult result) {
        long implemented = count(index, FeatureStatus.IMPLEMENTED);
        long planned = count(index, FeatureStatus.PLANNED);
        long undocumented = count(index, FeatureStatus.UNDOCUMENTED);
        SourceMix m = index.mix();
        return "Multi-source test strategy for " + serviceName + ": " + index.features().size() + " feature(s) ("
                + implemented + " implemented, " + planned + " planned, " + undocumented + " undocumented), "
                + result.gaps().gaps().size() + " gap(s) detected. Sources — code:" + m.code() + " jira:" + m.jira()
                + " confluence:" + m.confluence() + ".";
    }

    private static long count(FeatureIndex index, FeatureStatus status) {
        return index.features().values().stream().filter(f -> f.status() == status).count();
    }
}
