package ca.bnc.qe.veritas.testmgmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.integration.xray.XrayStep;
import ca.bnc.qe.veritas.integration.xray.XrayTestSpec;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.persistence.TestCaseRepository;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.persistence.TestConditionRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.skill.GateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Generates ISTQB Test Analyst test cases from a basis; optionally pushes them to Xray as Test issues. */
@Service
public class CreateTestCasesService {

    private final LlmGateway llm;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final TestCaseRepository repository;
    private final TestConditionRepository conditionRepository;
    private final XrayClient xray;
    private final GateService gateService;
    private final ObjectMapper objectMapper;
    private final Preflight preflight;

    public CreateTestCasesService(LlmGateway llm, JsonBlockExtractor jsonExtractor, ResponseSchemaValidator schemaValidator,
                                  ModelSelector modelSelector, CostRecorder costRecorder, PromptComposer promptComposer,
                                  TestCaseRepository repository, TestConditionRepository conditionRepository,
                                  XrayClient xray, GateService gateService, ObjectMapper objectMapper, Preflight preflight) {
        this.llm = llm;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.repository = repository;
        this.conditionRepository = conditionRepository;
        this.xray = xray;
        this.gateService = gateService;
        this.objectMapper = objectMapper;
        this.preflight = preflight;
    }

    public List<TestCase> generate(String serviceName, String basisText, String owner) {
        preflight.createTestCases(serviceName, basisText);
        preflight.requireLlm(llm, "create-test-cases");
        try {
            // ISTQB test design derives cases FROM test conditions, when an analysis has been run for this service.
            // With conditions present, we feed them in and ask each case to cite the condition it satisfies — closing
            // the basis ↔ condition ↔ case traceability. With none, behaviour is unchanged (cases straight from basis).
            List<TestCondition> conditions = currentConditions(serviceName);
            String outputContract = "Generate test cases applying black-box techniques (EP, BVA, decision "
                    + "tables, state transition). Each case: title, technique, priority, type, a one-line rationale "
                    + "(why this technique, ISTQB cite), the covered requirementKey, and steps. Add a SELF-REVIEW "
                    + "(confidence 0–100 + blind-spots). One fenced ```json block last: {\"cases\":[{\"title\":string,"
                    + "\"technique\":string,\"priority\":string,\"type\":string,\"rationale\":string,"
                    + "\"requirementKey\":string"
                    + (conditions.isEmpty() ? "" : ",\"conditionRef\":string")
                    + ",\"steps\":[{\"action\":string,\"data\":string,\"expected\":string}]}],"
                    + "\"selfReview\":{\"confidence\":number,\"blindSpots\":[string]}}. No prose after."
                    + (conditions.isEmpty() ? ""
                    : " Design cases to cover the TEST_CONDITIONS provided; set conditionRef to the id of the test "
                    + "condition each case satisfies.");
            String inputs = promptComposer.data("TEST_BASIS", basisText)
                    + (conditions.isEmpty() ? "" : promptComposer.data("TEST_CONDITIONS", renderConditions(conditions)));
            String prompt = promptComposer.compose("[TEST-CASES]", "generate-test-artifacts.prompt.md",
                    Set.of("1", "5", "6", "12"),   // terminology, ISO 25010, techniques, API heuristics
                    inputs, outputContract);
            String model = modelSelector.resolveTier(ModelTier.STANDARD);
            String raw = llm.complete(prompt, model);
            CostResult cost = costRecorder.record("create-test-cases", "generate", model, prompt, raw, owner);   // bill before parse
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "test-cases.schema.json");

            // Indexes for linking a case back to its condition: by explicit ref, and by basis item (fallback).
            Map<String, String> idByRef = new LinkedHashMap<>();
            Map<String, String> idBySource = new LinkedHashMap<>();
            for (TestCondition tcd : conditions) {
                if (tcd.getConditionRef() != null) {
                    idByRef.putIfAbsent(tcd.getConditionRef().toUpperCase(Locale.ROOT), tcd.getId());
                }
                if (tcd.getSourceBasisItem() != null && !tcd.getSourceBasisItem().isBlank()) {
                    idBySource.putIfAbsent(tcd.getSourceBasisItem().toUpperCase(Locale.ROOT), tcd.getId());
                }
            }

            JsonNode cases = node.path("cases");
            double perCase = cases.size() > 0 ? cost.estCostUsd() / cases.size() : cost.estCostUsd();
            double confidence = node.path("selfReview").path("confidence").asDouble(0);
            List<TestCase> out = new ArrayList<>();
            for (JsonNode c : cases) {
                TestCase tc = new TestCase();
                tc.setServiceName(serviceName);
                tc.setTitle(c.path("title").asText(""));
                tc.setTechnique(c.path("technique").asText(null));
                tc.setPriority(c.path("priority").asText(null));
                tc.setType(c.path("type").asText(null));
                tc.setRationale(c.hasNonNull("rationale") ? c.path("rationale").asText() : null);
                String requirementKey = c.hasNonNull("requirementKey") ? c.path("requirementKey").asText() : null;
                String linkedConditionId = linkCondition(c, idByRef, idBySource);
                // Reject, don't null-and-keep: when there ARE conditions to design from, a requirementKey that links to
                // none of them is an unverifiable trace — drop it rather than persist a fabricated link.
                if (!conditions.isEmpty() && linkedConditionId == null) {
                    requirementKey = null;
                }
                tc.setLinkedRequirement(requirementKey);
                tc.setTestConditionId(linkedConditionId);
                tc.setConfidence(confidence);
                tc.setStepsJson(c.path("steps").toString());
                tc.setStatus("PROPOSED");
                tc.setOwner(owner);
                tc.setEstCostUsd(perCase);
                out.add(repository.save(tc));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("create-test-cases failed: " + e.getMessage(), e);
        }
    }

    /** The current (latest-batch) test conditions for a service, or empty if none have been analyzed yet. */
    private List<TestCondition> currentConditions(String serviceName) {
        List<TestCondition> all = conditionRepository.findByServiceNameOrderByCreatedAtDesc(serviceName);
        if (all.isEmpty()) {
            return List.of();
        }
        // findBy...DescByCreatedAt → first is newest; keep only its batch (same source strategy) for a coherent set.
        String latestStrategy = all.get(0).getTestStrategyId();
        if (latestStrategy == null) {
            return all;
        }
        List<TestCondition> batch = new ArrayList<>();
        for (TestCondition c : all) {
            if (latestStrategy.equals(c.getTestStrategyId())) {
                batch.add(c);
            }
        }
        return batch;
    }

    /** Compact condition list fed to the design step: {@code TCD-001 [P1] desc (source: X)}. */
    private String renderConditions(List<TestCondition> conditions) {
        StringBuilder sb = new StringBuilder("Test conditions to design cases for (cite conditionRef):\n");
        for (TestCondition c : conditions) {
            sb.append("- ").append(c.getConditionRef());
            if (c.getPriority() != null) {
                sb.append(" [").append(c.getPriority()).append("]");
            }
            sb.append(" ").append(c.getDescription() == null ? "" : c.getDescription());
            if (c.getSourceBasisItem() != null && !c.getSourceBasisItem().isBlank()) {
                sb.append(" (source: ").append(c.getSourceBasisItem()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Link a generated case to its condition: by the cited conditionRef, else by basis item == requirementKey. */
    private String linkCondition(JsonNode c, Map<String, String> idByRef, Map<String, String> idBySource) {
        if (c.hasNonNull("conditionRef")) {
            String id = idByRef.get(c.path("conditionRef").asText("").toUpperCase(Locale.ROOT));
            if (id != null) {
                return id;
            }
        }
        if (c.hasNonNull("requirementKey")) {
            return idBySource.get(c.path("requirementKey").asText("").toUpperCase(Locale.ROOT));
        }
        return null;
    }

    /** Create the test in Xray (outward write) — gated for human approval before it runs. */
    public TestCase pushToXray(TestCase tc, String projectKey, String owner) {
        GateService.Decision gate = gateService.await(tc.getId(), "XRAY_CREATE_TEST", owner);
        if (!gate.approved()) {
            throw new IllegalStateException("Xray test creation for " + tc.getId()
                    + " awaiting approval (gate " + gate.gateId() + ")");
        }
        preflight.requireXrayWriteScope();   // fail fast on a missing/insufficient token, not mid-write
        String key = xray.createTest(new XrayTestSpec(projectKey, tc.getTitle(), "Manual", parseSteps(tc.getStepsJson())));
        tc.setXrayKey(key);
        tc.setStatus("CREATED_IN_XRAY");
        return repository.save(tc);
    }

    private List<XrayStep> parseSteps(String stepsJson) {
        List<XrayStep> steps = new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(stepsJson == null ? "[]" : stepsJson);
            for (JsonNode n : arr) {
                steps.add(new XrayStep(n.path("action").asText(""), n.path("data").asText(""), n.path("expected").asText("")));
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return steps;
    }
}
