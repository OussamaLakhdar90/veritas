package ca.bnc.qe.veritas.testmgmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.integration.xray.XrayStep;
import ca.bnc.qe.veritas.integration.xray.XrayTest;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.ReviewResult;
import ca.bnc.qe.veritas.persistence.ReviewResultRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.skill.GateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Reviews Xray test cases (ISTQB Test Analyst rubric): loads tests by JQL, scores each + proposes corrected
 * steps via the LLM, persists a {@link ReviewResult}, and (when applied) updates the test in Xray. Per-case cost.
 */
@Service
public class ReviewService {

    private final LlmGateway llm;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final ReviewResultRepository repository;
    private final XrayClient xray;
    private final GateService gateService;
    private final ObjectMapper objectMapper;
    private final Preflight preflight;

    public ReviewService(LlmGateway llm, JsonBlockExtractor jsonExtractor, ResponseSchemaValidator schemaValidator,
                         ModelSelector modelSelector, CostRecorder costRecorder, PromptComposer promptComposer,
                         ReviewResultRepository repository, XrayClient xray, GateService gateService,
                         ObjectMapper objectMapper, Preflight preflight) {
        this.llm = llm;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.repository = repository;
        this.xray = xray;
        this.gateService = gateService;
        this.objectMapper = objectMapper;
        this.preflight = preflight;
    }

    public List<ReviewResult> reviewByJql(String jql, String owner, boolean apply) {
        preflight.reviewTestCases(jql);
        preflight.requireLlm(llm, "review-test-cases");
        List<ReviewResult> results = new ArrayList<>();
        for (XrayTest test : xray.getTestsByJql(jql)) {
            results.add(reviewOne(test, owner, apply));
        }
        return results;
    }

    private ReviewResult reviewOne(XrayTest test, String owner, boolean apply) {
        try {
            String outputContract = "Review this test case against the ISTQB Test Analyst C1–C6 rubric, score it, "
                    + "propose corrected steps, AND self-review (confidence 0–100 + blind-spots). One fenced ```json "
                    + "block last: {\"score\":number,\"verdict\":string,"
                    + "\"gaps\":[{\"severity\":string,\"issue\":string,\"cite\":string}],"
                    + "\"rubric\":[{\"criterion\":string,\"score\":number,\"note\":string}],"
                    + "\"correctedSteps\":[{\"action\":string,\"data\":string,\"expected\":string}],"
                    + "\"selfReview\":{\"confidence\":number,\"blindSpots\":[string]}}. No prose after.";
            String inputs = promptComposer.data("TEST_" + test.key() + "_STEPS", renderSteps(test.steps()));
            String prompt = promptComposer.compose("[TEST-CASE-REVIEW]", "score-test-artifacts.prompt.md",
                    Set.of("1", "5", "6", "7", "11"),   // terminology, ISO 25010, techniques, reviews, defects
                    inputs, outputContract);
            String model = modelSelector.resolveTier(ModelTier.STANDARD);
            String raw = llm.complete(prompt, model);
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "test-case-review.schema.json");
            CostResult cost = costRecorder.record("review-test-cases", "review", model, prompt, raw, owner, test.key());

            ReviewResult result = new ReviewResult();
            result.setTargetType("XRAY_TEST");
            result.setTargetKey(test.key());
            result.setScore(node.path("score").asDouble(0));
            result.setVerdict(node.path("verdict").asText(""));
            result.setGapsJson(node.path("gaps").toString());
            result.setCorrectedJson(node.path("correctedSteps").toString());
            result.setDeliverableJson(node.toString());
            result.setConfidence(node.path("selfReview").path("confidence").asDouble(node.path("score").asDouble(0)));
            result.setOwner(owner);
            result.setEstCostUsd(cost.estCostUsd());

            if (apply && node.has("correctedSteps")) {
                GateService.Decision gate = gateService.await(test.key(), "XRAY_UPDATE_STEPS", owner);
                if (!gate.approved()) {
                    throw new IllegalStateException("Xray step update for " + test.key()
                            + " awaiting approval (gate " + gate.gateId() + ")");
                }
                xray.updateTestSteps(test.key(), parseSteps(node.path("correctedSteps")));
                result.setApplied(true);
            }
            return repository.save(result);
        } catch (Exception e) {
            throw new IllegalStateException("review-test-cases failed for " + test.key() + ": " + e.getMessage(), e);
        }
    }

    private String renderSteps(List<XrayStep> steps) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (XrayStep s : steps) {
            sb.append(i++).append(". ").append(s.action()).append(" | ").append(s.data()).append(" | ").append(s.result()).append("\n");
        }
        return sb.toString();
    }

    private List<XrayStep> parseSteps(JsonNode arr) {
        List<XrayStep> steps = new ArrayList<>();
        if (arr != null) {
            for (JsonNode n : arr) {
                steps.add(new XrayStep(n.path("action").asText(""), n.path("data").asText(""), n.path("expected").asText("")));
            }
        }
        return steps;
    }
}
