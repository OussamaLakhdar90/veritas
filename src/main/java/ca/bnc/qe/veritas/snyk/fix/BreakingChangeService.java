package ca.bnc.qe.veritas.snyk.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Advisory LLM judge: is upgrading a dependency to Snyk's safe version likely to break the code that uses it?
 * Fed the old→new version + semver delta + the consumer usage sites; returns a structured verdict. It never gates
 * the fix on its own — the reactor {@code mvn test} is the real gate — and it degrades gracefully to "unavailable"
 * when Copilot is offline (the cascade then trusts the build). Billed to the cost ledger like every other LLM call.
 */
@Service
@Slf4j
public class BreakingChangeService {

    private static final String OUTPUT_CONTRACT = """
            Emit ONLY a single fenced ```json block, nothing else:
            {"breaking": boolean, "confidence": 0-100 integer, "reasons": [string], "migrationNotes": string}
            """;

    private final LlmGateway llm;
    private final PromptComposer composer;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ObjectMapper mapper;

    public BreakingChangeService(LlmGateway llm, PromptComposer composer, ModelSelector modelSelector,
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

    public BreakingVerdict judge(String coordinate, String oldVersion, String newVersion, String usageSites,
                                 String owner, String refId) {
        if (llm == null || !llm.isAvailable()) {
            return BreakingVerdict.unavailable("Copilot not connected — breaking-change verdict skipped (advisory only).");
        }
        try {
            String upgrade = coordinate + "\nold version: " + oldVersion + "\nnew version: " + newVersion
                    + "\nmajor version bump: " + (isMajorBump(oldVersion, newVersion) ? "yes" : "no");
            String inputs = composer.data("UPGRADE", upgrade)
                    + composer.data("USAGE_SITES", usageSites == null || usageSites.isBlank()
                            ? "(no usage sites found in the analysed sources)" : usageSites);
            String model = modelSelector.resolveTier(ModelTier.STANDARD);
            String prompt = composer.compose("[BREAKING-CHANGE-JUDGE]", "breaking-change-judge.prompt.md",
                    Set.of(), inputs, OUTPUT_CONTRACT, modelSelector.promptTokenCap(model));
            String raw = llm.complete(prompt, model);
            costRecorder.record("snyk-fix", "breaking-change-judge", model, prompt, raw, owner, refId);
            JsonNode node = mapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "breaking-change-judge.schema.json");
            List<String> reasons = new ArrayList<>();
            for (JsonNode r : node.path("reasons")) {
                reasons.add(r.asText());
            }
            return new BreakingVerdict(true, node.path("breaking").asBoolean(false),
                    node.path("confidence").asInt(0), reasons, node.path("migrationNotes").asText(""));
        } catch (Exception e) {
            // A judge failure must never block a fix — degrade to "unavailable" and let the reactor build decide.
            log.warn("Breaking-change judge failed for {} ({} -> {}): {}", coordinate, oldVersion, newVersion, e.getMessage());
            return BreakingVerdict.unavailable("Breaking-change verdict unavailable (" + e.getMessage() + ").");
        }
    }

    /** True when the leading numeric segment increases (e.g. 2.x → 3.x). Conservative: unknown shapes → not major. */
    static boolean isMajorBump(String oldV, String newV) {
        Integer o = leadingMajor(oldV);
        Integer n = leadingMajor(newV);
        return o != null && n != null && n > o;
    }

    private static Integer leadingMajor(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String head = v.trim().split("\\.")[0];
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
