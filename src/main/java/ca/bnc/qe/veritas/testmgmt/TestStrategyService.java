package ca.bnc.qe.veritas.testmgmt;

import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Generates a global test strategy (ISTQB Test Manager). Basis is built by {@link BasisBuilder} (code or
 * Jira/Confluence); the single LLM step synthesizes the strategy. Cost is tracked.
 */
@Service
public class TestStrategyService {

    private final LlmGateway llm;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final TestStrategyRepository repository;
    private final ObjectMapper objectMapper;
    private final Preflight preflight;

    public TestStrategyService(LlmGateway llm, JsonBlockExtractor jsonExtractor, ResponseSchemaValidator schemaValidator,
                               ModelSelector modelSelector, CostRecorder costRecorder, PromptComposer promptComposer,
                               TestStrategyRepository repository, ObjectMapper objectMapper, Preflight preflight) {
        this.llm = llm;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.preflight = preflight;
    }

    public TestStrategy generate(String serviceName, String basisText, String source, String owner) {
        preflight.testStrategy(serviceName, basisText);
        try {
            String outputContract = "Produce a global test strategy (scope, test levels, test types, techniques, "
                    + "risk-based prioritization, entry & exit criteria) AND a SELF-REVIEW (confidence 0–100 + "
                    + "blind-spots). Reply with exactly one fenced ```json block last: {\"markdown\": string, "
                    + "\"summary\": string, \"riskRegister\": [{\"id\":string,\"description\":string,\"level\":string,"
                    + "\"citation\":string}], \"testApproach\": {\"levels\":[string],\"types\":[string]}, "
                    + "\"exitCriteria\": [{\"criterion\":string,\"metric\":string,\"citation\":string}], "
                    + "\"selfReview\": {\"confidence\":number,\"blindSpots\":[string]}}. markdown = the full strategy. "
                    + "No prose after.";
            String prompt = promptComposer.compose("[TEST-STRATEGY]", "generate-test-artifacts.prompt.md",
                    Set.of("1", "5", "6", "8", "9", "10"),   // terms, ISO 25010, techniques, planning, risk, monitoring
                    promptComposer.data("TEST_BASIS", basisText), outputContract);
            String model = modelSelector.resolveTier(ModelTier.DEEP);
            String raw = llm.complete(prompt, model);
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "test-strategy.schema.json");
            CostResult cost = costRecorder.record("test-strategy", "synthesize", model, prompt, raw, owner);

            TestStrategy strategy = new TestStrategy();
            strategy.setServiceName(serviceName);
            strategy.setContentMarkdown(node.path("markdown").asText(""));
            strategy.setDeliverableJson(node.toString());
            strategy.setConfidence(node.path("selfReview").path("confidence").asDouble(0));
            strategy.setStatus("DRAFT");
            strategy.setSource(source);
            strategy.setOwner(owner);
            strategy.setEstCostUsd(cost.estCostUsd());
            return repository.save(strategy);
        } catch (Exception e) {
            throw new IllegalStateException("test-strategy generation failed: " + e.getMessage(), e);
        }
    }
}
