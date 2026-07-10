package ca.bnc.qe.veritas.evolve;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Advisory LLM classifier for Engine Evolution. Given a not-yet-classified {@link FindingType} + the field evidence
 * (how humans classified findings of this type across services), it applies the SAME consumer-impact rubric the
 * engine's {@code DiffEngine.severityOf} uses (Spectral/oasdiff/OWASP-API/ISTQB) and SUGGESTS a severity + a
 * rationale for the promotion PR. It never picks a severity in per-finding review and never writes code — the
 * {@link SeverityCatalogEditor} renders the deterministic diff. Degrades gracefully: offline / on any error it
 * returns {@link Suggestion#unavailable()} and the caller defaults to the field consensus. Billed to the cost ledger.
 */
@Service
@Slf4j
public class ClassificationAdvisor {

    private static final String OUTPUT_CONTRACT = """
            Emit ONLY a single fenced ```json block, nothing else:
            {"suggestedSeverity": "BLOCKER|CRITICAL|MAJOR|MINOR|INFO", "rationale": string}
            The rationale must justify the severity by CONSUMER IMPACT using the rubric, in 1-3 sentences.
            """;

    private final LlmGateway llm;
    private final PromptComposer composer;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ObjectMapper mapper;

    public ClassificationAdvisor(LlmGateway llm, PromptComposer composer, ModelSelector modelSelector,
                                 CostRecorder costRecorder, JsonBlockExtractor jsonExtractor,
                                 ResponseSchemaValidator schemaValidator, ObjectMapper mapper) {
        this.llm = llm;
        this.composer = composer;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.mapper = mapper;
    }

    /** @return the AI's rubric-based suggestion, or {@link Suggestion#unavailable()} when Copilot is offline / errored. */
    public Suggestion suggest(FindingType type, Map<Severity, Integer> voteBreakdown, int distinctServices, String owner) {
        if (llm == null || !llm.isAvailable()) {
            return Suggestion.unavailable();
        }
        try {
            String evidence = "Human classifications so far (severity -> votes), across " + distinctServices
                    + " service(s):\n" + voteBreakdown.entrySet().stream()
                            .map(e -> "  " + e.getKey().name() + " -> " + e.getValue())
                            .collect(Collectors.joining("\n"));
            String inputs = composer.data("FINDING_TYPE", type.name()) + composer.data("FIELD_EVIDENCE", evidence);
            String model = modelSelector.resolveTier(ModelTier.DEEP);
            String prompt = composer.compose("[CLASSIFY-FINDING-TYPE]", "classify-finding-type.prompt.md",
                    Set.of(), inputs, OUTPUT_CONTRACT, modelSelector.promptTokenCap(model));
            String raw = llm.complete(prompt, model);
            costRecorder.record("engine-evolution", "classify-finding-type", model, prompt, raw, owner, type.name());
            JsonNode node = mapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "classification-proposal.schema.json");
            return new Suggestion(true, Severity.valueOf(node.path("suggestedSeverity").asText()),
                    node.path("rationale").asText(""));
        } catch (Exception e) {
            // A judge failure must never block the loop — degrade and let the caller default to the field consensus.
            log.warn("Classification advisor failed for {}: {}", type, e.getMessage());
            return Suggestion.unavailable();
        }
    }

    /**
     * The advisor's verdict: {@code available == true} carries a rubric-based severity + rationale; {@code false}
     * means the caller should fall back to the field consensus.
     */
    public record Suggestion(boolean available, Severity severity, String rationale) {
        static Suggestion unavailable() {
            return new Suggestion(false, null, null);
        }
    }
}
