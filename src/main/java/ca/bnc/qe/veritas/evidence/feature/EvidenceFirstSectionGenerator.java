package ca.bnc.qe.veritas.evidence.feature;

import java.util.Optional;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Generates ONE feature-scoped strategy section, evidence-first (design §4a). The control flow the existing
 * single-shot generator does NOT do:
 *
 * <ol>
 *   <li>retrieve the feature's closed-world slice + facts card ({@link EvidenceRetriever});</li>
 *   <li>one cost-routed LLM call constrained to cite ONLY the feature's allowed ids;</li>
 *   <li>schema-validate the reply, then {@link CitationValidator} (ids exist + quotes grounded);</li>
 *   <li>on failure, <b>regenerate once</b> with the validator's problems fed back; still failing → <b>drop</b> the
 *       section (return empty) — never abort the whole strategy.</li>
 * </ol>
 *
 * The {@code content} shape is the caller's concern (risk register, test approach, …); this enforces the evidence
 * envelope and grounding around it, and records cost per (section, feature).
 */
@Component
@Slf4j
public class EvidenceFirstSectionGenerator {

    private static final int MAX_ATTEMPTS = 2;   // first try + one regenerate

    private final LlmGateway llm;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final EvidenceRetriever retriever;
    private final CitationValidator citationValidator;

    public EvidenceFirstSectionGenerator(LlmGateway llm, ModelSelector modelSelector, CostRecorder costRecorder,
                                         PromptComposer promptComposer, JsonBlockExtractor jsonExtractor,
                                         ResponseSchemaValidator schemaValidator, ObjectMapper objectMapper,
                                         EvidenceRetriever retriever, CitationValidator citationValidator) {
        this.llm = llm;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.retriever = retriever;
        this.citationValidator = citationValidator;
    }

    /**
     * @return the validated section node ({@code {feature, evidence[], content}}) plus the cost spent, or an empty
     *         node when it can't be grounded (the LLM is unavailable, the feature has no citable evidence, or the
     *         reply stays invalid after the one retry). A dropped section still reports the cost it burned.
     */
    public SectionResult generate(String sectionKey, String instruction, Set<String> pack, ModelTier tier,
                                  FeatureIndex index, String featureId, String owner) {
        Set<String> allowed = retriever.allowedIds(index, featureId);
        if (allowed.isEmpty() || !llm.isAvailable()) {
            return SectionResult.empty();   // nothing to cite, or no LLM — no spend, the caller carries on
        }

        String basis = retriever.forFeature(index, featureId);
        String facts = retriever.factsCard(index, featureId);
        String contract = "Generate ONLY the \"" + sectionKey + "\" section. " + instruction
                + " Cite ONLY these unit ids: [" + String.join(", ", allowed) + "]."
                + " Reply with one fenced json block:"
                + " {\"feature\":\"...\",\"evidence\":[{\"unitId\":\"...\",\"quote\":\"...\",\"gloss\":\"...\"}],\"content\": ...}."
                + " No prose after.";

        String feedback = "";
        double cost = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String inputs = promptComposer.data("TEST_BASIS", basis) + promptComposer.data("FACTS", facts)
                    + (feedback.isEmpty() ? "" : promptComposer.data("FIX_THESE", feedback));
            String prompt = promptComposer.compose("[EVIDENCE-SECTION:" + sectionKey + "]",
                    "strategy-section.prompt.md", pack, inputs, contract);
            String model = modelSelector.resolveTier(tier);
            String raw = llm.complete(prompt, model);
            cost += costRecorder.record("test-strategy", "section:" + sectionKey + ":" + featureId, model, prompt, raw, owner)
                    .estCostUsd();
            try {
                JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
                schemaValidator.validate(node, "test-strategy-section.schema.json");
                CitationValidator.Result cv = citationValidator.validate(node.path("evidence"), index.unitsById(), allowed);
                if (cv.valid()) {
                    return new SectionResult(Optional.of(node), cost);
                }
                feedback = "Your previous reply cited invalid evidence: " + String.join("; ", cv.problems())
                        + ". Cite ONLY: " + String.join(", ", allowed)
                        + ", and use a short verbatim quote copied from the cited unit's text.";
            } catch (Exception e) {
                feedback = "Your previous reply was unusable (" + e.getMessage()
                        + "). Reply with exactly one fenced json block matching the contract.";
            }
        }
        log.warn("Dropping section '{}' for feature {} — couldn't ground it after a retry.", sectionKey, featureId);
        return new SectionResult(Optional.empty(), cost);
    }
}
