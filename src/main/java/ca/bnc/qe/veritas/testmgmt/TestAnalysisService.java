package ca.bnc.qe.veritas.testmgmt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.persistence.TestConditionRepository;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ISTQB <b>test analysis</b>: identifies and prioritizes the <b>test conditions</b> ("what to test") from the test
 * basis, against the APPROVED test strategy's risk register. This is the missing work product between the strategy
 * (Test Manager) and the cases (Test Analyst). Conditions are persisted as the auditable Test Condition List, each
 * tracing a basis item + a risk and carrying an automation-candidacy decision. One LLM step; cost tracked.
 *
 * <p>The Test Condition List is a living artifact pinned to its strategy: regenerating for a (service, strategy)
 * supersedes the previous batch (per-condition edits — status / automation — are made afterwards via the API, and
 * stand until the next regeneration).
 */
@Service
@Slf4j
public class TestAnalysisService {

    private final LlmGateway llm;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final ObjectMapper objectMapper;
    private final Preflight preflight;
    private final TestConditionRepository repository;
    private final TestStrategyRepository strategyRepository;

    public TestAnalysisService(LlmGateway llm, JsonBlockExtractor jsonExtractor, ResponseSchemaValidator schemaValidator,
                               ModelSelector modelSelector, CostRecorder costRecorder, PromptComposer promptComposer,
                               ObjectMapper objectMapper, Preflight preflight, TestConditionRepository repository,
                               TestStrategyRepository strategyRepository) {
        this.llm = llm;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.objectMapper = objectMapper;
        this.preflight = preflight;
        this.repository = repository;
        this.strategyRepository = strategyRepository;
    }

    public List<TestCondition> analyze(String serviceName, String basisText, String owner) {
        preflight.testAnalysis(serviceName, basisText);
        preflight.requireLlm(llm, "analyze-test-conditions");

        // Conditions are identified during test analysis but PRIORITIZED against the strategy's risk register — the
        // ISTQB spine (test analysis is risk-based). Hard dependency: fail clearly if no strategy exists, and feed it.
        List<TestStrategy> strategies = strategyRepository.findByServiceNameOrderByCreatedAtDesc(serviceName);
        if (strategies.isEmpty()) {
            throw new PreconditionException("analyze-test-conditions", List.of(
                    "No test strategy found for '" + serviceName + "'. Create a strategy first (test-strategy) — test "
                            + "conditions are identified during test analysis and prioritized against the strategy's "
                            + "risk register."));
        }
        TestStrategy strategy = selectStrategy(serviceName, strategies);
        String strategyBasis = strategy.getDeliverableJson() != null && !strategy.getDeliverableJson().isBlank()
                ? strategy.getDeliverableJson() : strategy.getContentMarkdown();

        // Closed-world risk ids: the only risk references a condition may legitimately carry are the ids in the
        // strategy's own risk register. We inject them so the model is constrained BEFORE generation, then drop any
        // fabricated riskRef AFTER (evidence-first, not just "please cite a risk").
        Set<String> allowedRiskIds = riskIdsOf(strategy);

        try {
            String riskRule = allowedRiskIds.isEmpty() ? ""
                    : " riskRef MUST be one of these risk-register ids (use none if a condition maps to no listed risk): "
                    + String.join(", ", allowedRiskIds) + ".";
            String outputContract = "Perform ISTQB TEST ANALYSIS: identify and prioritize the TEST CONDITIONS "
                    + "(\"what to test\") from the test basis — a test condition is a single testable aspect, NOT a test "
                    + "case (do not write steps). Trace each condition to a basis item (sourceBasisItem) AND align it to "
                    + "a risk from the strategy's risk register (riskRef); prioritize by the strategy's risk levels (a "
                    + "HIGH/VERY-HIGH risk → P1). For each condition also give the ISTQB design technique that fits, the "
                    + "ISO 25010 quality characteristic, and an AUTOMATION candidacy — AUTOMATED (stable, repeatable, "
                    + "regression-valuable), MANUAL (exploratory / one-off / oracle-hard), or CANDIDATE (automate once "
                    + "stable) — with a one-line rationale (risk × repeatability × stability). Reply with exactly one "
                    + "fenced ```json block: {\"conditions\":[{\"ref\":string,\"description\":string,"
                    + "\"sourceBasisItem\":string,\"priority\":string,\"riskRef\":string,\"qualityCharacteristic\":"
                    + "string,\"technique\":string,\"automation\":string,\"automationRationale\":string}],"
                    + "\"selfReview\":{\"confidence\":number,\"blindSpots\":[string]}}. No prose after." + riskRule;
            String inputs = promptComposer.data("TEST_BASIS", basisText)
                    + promptComposer.data("TEST_STRATEGY", strategyBasis == null ? "" : strategyBasis);
            String prompt = promptComposer.compose("[TEST-ANALYSIS]", "generate-test-artifacts.prompt.md",
                    Set.of("1", "6", "9", "12"),   // terminology+traceability, techniques, risk management, API heuristics
                    inputs, outputContract);
            String model = modelSelector.resolveTier(ModelTier.STANDARD);   // analysis is load-bearing, like testApproach
            String raw = llm.complete(prompt, model);
            CostResult cost = costRecorder.record("analyze-test-conditions", "analyze", model, prompt, raw, owner);
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "test-conditions.schema.json");

            JsonNode conditions = node.path("conditions");

            // Drop any riskRef the model invented (not in the strategy's risk register) so a fabricated trace is never
            // persisted on the auditable condition. Cap confidence when we had to, so a fabrication-laden batch can't
            // present itself as high-confidence.
            int droppedRisk = 0;
            if (!allowedRiskIds.isEmpty()) {
                for (JsonNode c : conditions) {
                    if (c instanceof ObjectNode co && co.hasNonNull("riskRef")) {
                        String rr = co.path("riskRef").asText("");
                        if (!rr.isBlank() && !allowedRiskIds.contains(rr.toUpperCase(Locale.ROOT))) {
                            co.remove("riskRef");
                            droppedRisk++;
                        }
                    }
                }
            }
            double confidence = node.path("selfReview").path("confidence").asDouble(0);
            if (droppedRisk > 0) {
                log.warn("analyze-test-conditions for '{}': dropped {} fabricated riskRef(s) not in the strategy's "
                        + "risk register; capping confidence.", serviceName, droppedRisk);
                confidence = Math.min(confidence, 50);
            }
            double perCondition = conditions.size() > 0 ? cost.estCostUsd() / conditions.size() : cost.estCostUsd();

            // Supersede the prior batch for this (service, strategy) — the list is a living artifact pinned to its strategy.
            List<TestCondition> prior = repository.findByServiceNameAndTestStrategyId(serviceName, strategy.getId());
            if (!prior.isEmpty()) {
                repository.deleteAll(prior);
            }

            List<TestCondition> out = new ArrayList<>();
            int seq = 1;
            for (JsonNode c : conditions) {
                TestCondition tc = new TestCondition();
                tc.setServiceName(serviceName);
                String ref = c.path("ref").asText("");
                tc.setConditionRef(ref.isBlank() ? String.format(Locale.ROOT, "TCD-%03d", seq) : ref);
                tc.setDescription(c.path("description").asText(""));
                tc.setSourceBasisItem(text(c, "sourceBasisItem"));
                tc.setPriority(text(c, "priority"));
                tc.setRiskRef(text(c, "riskRef"));
                tc.setQualityCharacteristic(text(c, "qualityCharacteristic"));
                tc.setTechnique(text(c, "technique"));
                tc.setAutomation(normalizeAutomation(text(c, "automation")));
                tc.setAutomationRationale(text(c, "automationRationale"));
                tc.setStatus("PROPOSED");
                tc.setTestStrategyId(strategy.getId());
                tc.setConfidence(confidence);
                tc.setOwner(owner);
                tc.setEstCostUsd(perCondition);
                out.add(repository.save(tc));
                seq++;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("analyze-test-conditions failed: " + e.getMessage(), e);
        }
    }

    /**
     * Pick the strategy this analysis derives from: the APPROVED, signed-off one — not merely the newest-created.
     * Mirrors {@code ReleaseTestPlanService.selectStrategy} so analysis and the release plan rest on the same baseline.
     * {@code strategies} is createdAt-desc, so the first APPROVED is the latest approved; with none approved we fall
     * back to the most recent (logged, so it's visible the approval gate wasn't crossed).
     */
    static TestStrategy selectStrategy(String serviceName, List<TestStrategy> strategies) {
        TestStrategy strategy = strategies.stream()
                .filter(s -> "APPROVED".equalsIgnoreCase(s.getStatus()))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("No APPROVED test strategy for '{}'; deriving test conditions from the most recent "
                            + "strategy (status={}). Approve a strategy so the conditions rest on a signed-off baseline.",
                            serviceName, strategies.get(0).getStatus());
                    return strategies.get(0);
                });
        log.info("Test analysis for '{}' uses strategy {} (v{}, status={}).",
                serviceName, strategy.getId(), strategy.getVersion(), strategy.getStatus());
        return strategy;
    }

    /** The risk-register ids of a strategy's deliverable (upper-cased) — the closed-world set a condition may cite. */
    private Set<String> riskIdsOf(TestStrategy strategy) {
        Set<String> ids = new LinkedHashSet<>();
        String json = strategy.getDeliverableJson();
        if (json == null || json.isBlank()) {
            return ids;   // no structured register to check against — can't verify, so we won't drop
        }
        try {
            JsonNode d = objectMapper.readTree(json);
            for (JsonNode r : d.path("riskRegister")) {
                String id = r.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id.toUpperCase(Locale.ROOT));
                }
            }
        } catch (Exception ignored) {
            // unparseable deliverable → treat as no verifiable risk ids
        }
        return ids;
    }

    /** Map an LLM-supplied automation tag to the canonical set; unknown/blank defaults to CANDIDATE (review later). */
    static String normalizeAutomation(String raw) {
        if (raw == null) {
            return "CANDIDATE";
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "AUTOMATED", "AUTOMATE", "AUTO" -> "AUTOMATED";
            case "MANUAL" -> "MANUAL";
            default -> "CANDIDATE";
        };
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.path(field).asText() : null;
    }
}
