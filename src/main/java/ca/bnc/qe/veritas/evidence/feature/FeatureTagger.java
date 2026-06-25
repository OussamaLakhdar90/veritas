package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The ONE cheap LLM step of the feature index (design §3.2): it canonicalises the deterministic
 * {@link FeatureSeeder} seed by <b>merging</b> buckets that describe the same capability — semantic synonyms the
 * stemmer can't (login/authentication) and the same feature seen from different sources (a Jira requirement and
 * the endpoint that implements it) — and giving each a business-readable name. It only ever MERGES (the seed is
 * conservative / under-clustered); it never splits.
 *
 * <p>Sends only {@code {ref, name, sample titles}} per seed bucket — never full unit text — so the call is small
 * (ECONOMY tier). <b>Degrades gracefully:</b> when the LLM is unavailable, there's ≤1 feature to merge, or the
 * reply is unusable, it returns the deterministic seed unchanged — the pipeline never breaks for want of the LLM.
 */
@Component
@Slf4j
public class FeatureTagger {

    private final LlmGateway llm;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;

    public FeatureTagger(LlmGateway llm, ModelSelector modelSelector, CostRecorder costRecorder,
                         PromptComposer promptComposer, JsonBlockExtractor jsonExtractor,
                         ResponseSchemaValidator schemaValidator, ObjectMapper objectMapper) {
        this.llm = llm;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
    }

    /** Canonicalise the seed via one ECONOMY call; returns the seed unchanged when the LLM can't help. */
    public FeatureIndex tag(FeatureIndex seed, String owner) {
        if (seed.features().size() <= 1 || !llm.isAvailable()) {
            return seed;   // nothing to merge, or no LLM — the deterministic seed stands
        }
        try {
            String inputs = buildInputs(seed);
            String contract = "Reply with exactly one fenced ```json block and no prose after it: "
                    + "{\"features\":[{\"name\":\"<concise business name>\",\"refs\":[\"<ref>\", ...]}]}. "
                    + "Use ONLY the ref ids listed above; merge refs that are the same capability; omit a ref to keep it as-is.";
            String prompt = promptComposer.compose("[FEATURE-TAGGER]", "feature-tagger.prompt.md", Set.of(), inputs, contract);
            String model = modelSelector.resolveTier(ModelTier.ECONOMY);
            String raw = llm.complete(prompt, model);
            costRecorder.record("feature-index", "tag", model, prompt, raw, owner);

            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "feature-tagger.schema.json");
            return apply(seed, node);
        } catch (Exception e) {
            log.warn("Feature tagging failed ({}); using the deterministic seed.", e.getMessage());
            return seed;
        }
    }

    /** One line per seed bucket: ref + rough name + up to 4 sample unit titles (titles only — never full text). */
    private String buildInputs(FeatureIndex seed) {
        StringBuilder sb = new StringBuilder();
        for (Feature f : seed.features().values()) {
            List<String> titles = seed.unitsOf(f.featureId()).stream()
                    .map(EvidenceUnit::title).filter(t -> t != null && !t.isBlank()).limit(4).toList();
            sb.append("- ref=").append(f.featureId())
                    .append(" | name=").append(f.displayName())
                    .append(" | titles: ").append(String.join("; ", titles)).append("\n");
        }
        return promptComposer.data("SEED_FEATURES", sb.toString());
    }

    /** Build a new index from the LLM merge groups; un-grouped seeds are kept as-is, no unit is dropped. */
    private FeatureIndex apply(FeatureIndex seed, JsonNode node) {
        Map<String, Feature> result = new LinkedHashMap<>();
        Set<String> consumed = new HashSet<>();

        for (JsonNode group : node.path("features")) {
            List<Feature> members = new ArrayList<>();
            for (JsonNode ref : group.path("refs")) {
                String refId = ref.asText();
                // A ref already merged by an earlier group is not merged again (the model must not place a unit in
                // two features); the first group to claim it wins, keeping every unit in exactly one feature.
                if (consumed.contains(refId)) {
                    continue;
                }
                Feature m = seed.features().get(refId);
                if (m != null) {
                    members.add(m);
                    consumed.add(m.featureId());
                }
            }
            if (members.isEmpty()) {
                continue;   // a group referencing only unknown / already-consumed refs is ignored
            }
            // Filter to ids actually present in unitsById so Feature.unitIds and the status basis can't diverge
            // (a dangling seed id would otherwise leave a phantom id and compute status from a strict subset).
            List<String> ids = members.stream().flatMap(m -> m.unitIds().stream())
                    .filter(seed.unitsById()::containsKey).distinct().sorted().toList();
            List<EvidenceUnit> units = ids.stream().map(seed.unitsById()::get).filter(Objects::nonNull).toList();
            String featureId = "feat-" + EvidenceId.hash8(String.join("|", ids));
            String name = group.path("name").asText("");
            if (name.isBlank()) {
                name = members.get(0).displayName();
            }
            result.put(featureId, new Feature(featureId, name, ids, FeatureStatusEngine.statusOf(units)));
        }

        // Seeds the LLM didn't merge are kept verbatim.
        for (Feature f : seed.features().values()) {
            if (!consumed.contains(f.featureId())) {
                result.putIfAbsent(f.featureId(), f);
            }
        }

        // Completeness: any clusterable unit not landing in a feature is surfaced (must normally be empty).
        Set<String> assigned = new HashSet<>();
        result.values().forEach(f -> assigned.addAll(f.unitIds()));
        Set<String> unassigned = new LinkedHashSet<>();
        for (String id : seed.unitsById().keySet()) {
            if (!seed.crossCuttingIds().contains(id) && !assigned.contains(id)) {
                unassigned.add(id);
            }
        }
        if (!unassigned.isEmpty()) {
            log.warn("Feature tagging left {} unit(s) unassigned: {}", unassigned.size(), unassigned);
        }
        return new FeatureIndex(result, seed.unitsById(), seed.crossCuttingIds(), unassigned, seed.mix(), seed.sourceDigest());
    }
}
