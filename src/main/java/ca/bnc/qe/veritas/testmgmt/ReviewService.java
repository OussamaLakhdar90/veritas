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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.regex.Pattern;
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
            String outputContract = "Review this single Xray test from its SUMMARY and STEPS ONLY, against the ISTQB "
                    + "Test Analyst rubric. Score and raise gaps ONLY for what these reveal — technique fit, the "
                    + "structural completeness of the STEPS, and BVA/EP coverage in the step data. You were NOT given "
                    + "the test's priority, preconditions, linked requirement, or the test basis — do NOT raise gaps "
                    + "about traceability, priority, preconditions, or risk mapping (you cannot see them). Propose "
                    + "corrected steps and self-review (confidence 0–100 + blind-spots). One fenced ```json block last: "
                    + "{\"score\":number,\"verdict\":string,"
                    + "\"gaps\":[{\"severity\":string,\"issue\":string,\"cite\":string}],"
                    + "\"rubric\":[{\"criterion\":string,\"score\":number,\"note\":string}],"
                    + "\"correctedSteps\":[{\"action\":string,\"data\":string,\"expected\":string}],"
                    + "\"selfReview\":{\"confidence\":number,\"blindSpots\":[string]}}. No prose after.";
            // Feed the visible context we actually have (summary + type + steps). Deliberately NOT the priority /
            // preconditions / linked requirement / basis — we don't fetch them, so the rubric must not score them.
            String context = "Summary: " + nz(test.summary()) + " (type: " + nz(test.testType()) + ")\nSteps:\n"
                    + renderSteps(test.steps());
            String inputs = promptComposer.data("TEST_" + test.key(), context);
            String prompt = promptComposer.compose("[TEST-CASE-REVIEW]", "score-test-artifacts.prompt.md",
                    Set.of("1", "5", "6", "7", "11"),   // terminology, ISO 25010, techniques, reviews, defects
                    inputs, outputContract);
            String model = modelSelector.resolveTier(ModelTier.STANDARD);
            String raw = llm.complete(prompt, model);
            CostResult cost = costRecorder.record("review-test-cases", "review", model, prompt, raw, owner, test.key());   // bill before parse
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "test-case-review.schema.json");

            // Backstop the prompt scoping deterministically: drop any gap about an attribute the reviewer was never
            // shown (priority / preconditions / requirement traceability / risk mapping) — from summary + steps alone
            // such a gap can only be fabricated. Keep the report and the persisted gaps consistent.
            List<String> droppedGaps = new ArrayList<>();
            ArrayNode keptGaps = suppressUnscoreableGaps(node.path("gaps"), droppedGaps);
            if (node instanceof ObjectNode on) {
                on.set("gaps", keptGaps);
                if (!droppedGaps.isEmpty()) {
                    ObjectNode self = on.path("selfReview").isObject()
                            ? (ObjectNode) on.get("selfReview") : on.putObject("selfReview");
                    ArrayNode bs = self.path("blindSpots").isArray()
                            ? (ArrayNode) self.get("blindSpots") : self.putArray("blindSpots");
                    bs.add("Suppressed " + droppedGaps.size() + " gap(s) about attributes not supplied to the reviewer "
                            + "(priority / preconditions / requirement traceability) — not assessable from the test's "
                            + "summary and steps.");
                }
            }

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

    /** Gaps the reviewer cannot legitimately raise from summary + steps alone (the attributes it was never shown). */
    private static final Pattern UNSCOREABLE_GAP = Pattern.compile(
            "(?i)(traceab|linked requirement|requirement (id|key|link|reference|trace)|not (traced|linked)|"
            + "priorit|preconditions?|postconditions?|risk (level|rating|mapping|coverage|id))");

    /** Keep only gaps assessable from the supplied summary + steps; collect the suppressed (fabricated) ones. */
    private ArrayNode suppressUnscoreableGaps(JsonNode gaps, List<String> dropped) {
        ArrayNode kept = objectMapper.createArrayNode();
        if (gaps != null && gaps.isArray()) {
            for (JsonNode g : gaps) {
                String issue = g.path("issue").asText("");
                if (UNSCOREABLE_GAP.matcher(issue).find()) {
                    dropped.add(issue);
                } else {
                    kept.add(g);
                }
            }
        }
        return kept;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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
