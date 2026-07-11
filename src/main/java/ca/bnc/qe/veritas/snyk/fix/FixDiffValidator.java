package ca.bnc.qe.veritas.snyk.fix;

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
import org.springframework.stereotype.Service;

/**
 * Advisory AI cross-check: reads the actual before→after BOM diff a fix applied and explains, in plain language, WHAT
 * it changed and WHETHER that fixes the reported vulnerability. This answers the user's "before commit an AI should
 * validate what was done" — but as an <b>explanation</b>, never a gate: the deterministic effective-version check
 * ({@link FixValidator}) is authoritative, and this degrades gracefully to "unavailable" when Copilot is offline (a
 * judge failure must never block a fix). Mirrors {@link BreakingChangeService}'s LLM plumbing; billed to the ledger.
 */
@Service
@Slf4j
public class FixDiffValidator {

    private static final String OUTPUT_CONTRACT = """
            Emit ONLY a single fenced ```json block, nothing else:
            {"fixesTheVuln": boolean, "whatChanged": string, "reason": string}
            """;

    /** Cap the diff fed to the model so a huge pom can't blow the prompt budget (the vuln pin lives near the top). */
    private static final int MAX_POM_CHARS = 12000;

    private final LlmGateway llm;
    private final PromptComposer composer;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ObjectMapper mapper;

    public FixDiffValidator(LlmGateway llm, PromptComposer composer, ModelSelector modelSelector,
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

    /** Explain the applied BOM diff and whether it fixes {@code coordinate} at {@code fixedIn}. Advisory only. */
    public FixDiffVerdict explain(String coordinate, String oldVersion, String fixedIn, String oldBomPom,
                                  String newBomPom, String owner, String refId) {
        if (llm == null || !llm.isAvailable()) {
            return FixDiffVerdict.unavailable("Copilot not connected — the AI change summary was skipped (advisory only).");
        }
        try {
            String intent = coordinate + "\nold version: " + oldVersion + "\nmust reach (fixedIn): " + fixedIn;
            String diff = "BEFORE (old pom.xml):\n" + cap(oldBomPom) + "\n\nAFTER (new pom.xml):\n" + cap(newBomPom);
            String inputs = composer.data("INTENT", intent) + composer.data("DIFF", diff);
            String model = modelSelector.resolveTier(ModelTier.STANDARD);
            String prompt = composer.compose("[FIX-DIFF-VALIDATE]", "fix-diff-validate.prompt.md",
                    Set.of(), inputs, OUTPUT_CONTRACT, modelSelector.promptTokenCap(model));
            String raw = llm.complete(prompt, model);
            costRecorder.record("snyk-fix", "fix-diff-validate", model, prompt, raw, owner, refId);
            JsonNode node = mapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "fix-diff-validate.schema.json");
            return new FixDiffVerdict(true, node.path("fixesTheVuln").asBoolean(false),
                    node.path("whatChanged").asText(""), node.path("reason").asText(""));
        } catch (Exception e) {
            // Advisory only — a judge failure must never block a fix. The deterministic gate already validated it.
            log.warn("Fix-diff validator failed for {} (-> {}): {}", coordinate, fixedIn, e.getMessage());
            return FixDiffVerdict.unavailable("AI change summary unavailable (" + e.getMessage() + ").");
        }
    }

    private static String cap(String pom) {
        if (pom == null) {
            return "(pom not available)";
        }
        return pom.length() <= MAX_POM_CHARS ? pom : pom.substring(0, MAX_POM_CHARS) + "\n…[truncated]…";
    }
}
