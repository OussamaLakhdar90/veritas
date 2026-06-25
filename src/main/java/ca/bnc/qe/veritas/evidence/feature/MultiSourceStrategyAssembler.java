package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.evidence.SourceMix;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final EvidenceRetriever retriever;
    private final ObjectMapper objectMapper;

    public MultiSourceStrategyAssembler(EvidenceFirstSectionGenerator sectionGenerator, EvidenceRetriever retriever,
                                        ObjectMapper objectMapper) {
        this.sectionGenerator = sectionGenerator;
        this.retriever = retriever;
        this.objectMapper = objectMapper;
    }

    /**
     * The prior generated strategy to reuse from on a lineage re-run (design §3.2): its source index (to fingerprint
     * each feature's grounding) and its deliverable (whose per-feature sections we can splice when the grounding is
     * unchanged). Null = full synthesis, every section regenerated.
     */
    public record ReuseContext(FeatureIndexResult priorIndex, JsonNode priorDeliverable) {}

    public AssembledStrategy assemble(String serviceName, FeatureIndexResult result, String owner) {
        return assemble(serviceName, result, owner, null);
    }

    /**
     * Assemble the strategy, optionally REUSING a prior strategy's per-feature sections for features whose grounding
     * (own units + cross-cutting, by content) is byte-identical — paying the LLM only for features whose evidence
     * actually changed. A reused node is re-stamped with the CURRENT feature name / status (so a carried-forward
     * rename or a PLANNED→IMPLEMENTED flip is reflected) even though its body is reused for $0. The reuse path can
     * never ship a stale section: the fingerprint covers the full citable set including each unit's text, and a
     * cross-cutting change invalidates every feature. Falls through to generation whenever a node can't be reused.
     */
    public AssembledStrategy assemble(String serviceName, FeatureIndexResult result, String owner, ReuseContext reuse) {
        FeatureIndex index = result.index();
        ObjectNode deliverable = objectMapper.createObjectNode();
        Map<String, ArrayNode> arrays = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        Map<String, JsonNode> priorByKey = reuse == null ? Map.of() : priorSectionNodes(reuse.priorDeliverable());
        double cost = 0;
        int reused = 0;

        for (Feature feature : index.features().values()) {
            boolean groundingUnchanged = reuse != null && sameGrounding(reuse, index, feature.featureId());
            for (SectionSpec spec : FEATURE_SECTIONS) {
                JsonNode cached = groundingUnchanged
                        ? priorByKey.get(sectionKey(feature.featureId(), spec.key())) : null;
                ObjectNode node;
                if (cached != null) {
                    node = (ObjectNode) cached.deepCopy();   // reuse verbatim — $0 (grounding is byte-identical)
                    reused++;
                } else {
                    SectionResult section = sectionGenerator.generate(spec.key(), spec.instruction(), spec.pack(),
                            spec.tier(), index, feature.featureId(), owner);
                    cost += section.costUsd();
                    if (section.node().isEmpty()) {
                        dropped.add(spec.key() + " / " + feature.displayName());
                        continue;
                    }
                    node = (ObjectNode) section.node().get();
                }
                node.put("feature", feature.displayName());          // canonical name over the LLM's
                node.put("featureId", feature.featureId());          // stable key (display names can collide)
                node.put("featureStatus", feature.status().name());  // re-stamped so a reused node reflects today's status
                arrays.computeIfAbsent(spec.key(), k -> objectMapper.createArrayNode()).add(node);
            }
        }
        arrays.forEach(deliverable::set);
        deliverable.set("gaps", gapsNode(result.gaps()));
        deliverable.put("summary", summary(serviceName, index, result));
        deliverable.put("estCostUsd", cost);

        log.info("Assembled strategy for {}: {} feature(s), {} reused section(s), {} dropped, est cost ${}",
                serviceName, index.features().size(), reused, dropped.size(), String.format("%.4f", cost));
        return new AssembledStrategy(deliverable, dropped, index.features().size(), cost, reused);
    }

    /** True when a feature's grounding fingerprint is identical in the prior index and the fresh one. */
    private boolean sameGrounding(ReuseContext reuse, FeatureIndex freshIndex, String featureId) {
        FeatureIndex prior = reuse.priorIndex().index();
        if (!prior.features().containsKey(featureId)) {
            return false;   // a new feature (or a re-clustered id) — nothing to reuse
        }
        return retriever.groundingFingerprint(prior, featureId)
                .equals(retriever.groundingFingerprint(freshIndex, featureId));
    }

    /** Index the prior deliverable's feature-scoped section nodes by (featureId, sectionKey) for splicing. */
    private Map<String, JsonNode> priorSectionNodes(JsonNode priorDeliverable) {
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        if (priorDeliverable == null) {
            return byKey;
        }
        for (SectionSpec spec : FEATURE_SECTIONS) {
            for (JsonNode node : priorDeliverable.path(spec.key())) {
                String fid = node.path("featureId").asText(null);
                if (fid != null) {
                    byKey.put(sectionKey(fid, spec.key()), node);
                }
            }
        }
        return byKey;
    }

    private static String sectionKey(String featureId, String sectionKey) {
        return featureId + "|" + sectionKey;
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
