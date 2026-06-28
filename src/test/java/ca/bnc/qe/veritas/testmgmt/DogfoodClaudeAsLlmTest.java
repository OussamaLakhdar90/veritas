package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.persistence.TestCaseRepository;
import ca.bnc.qe.veritas.persistence.TestConditionRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.skill.GateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Dogfood: run Veritas's REAL test-case generation pipeline (real {@link JsonBlockExtractor} + real
 * {@link ResponseSchemaValidator}) with Claude itself standing in as the LLM — Copilot CLI runs on Claude, so the
 * model that would answer in production is the same one acting here. The {@code MODEL_RESPONSE} below is a genuine
 * Claude answer to Veritas's {@code [TEST-CASES]} contract for the real {@code fixtures/policies/PolicyController}
 * ({@code POST /api/v1/policies} 201 with {@code name @NotBlank}, {@code code @Size(max=10)}; {@code GET /{id}} 200/404).
 *
 * <p>The assertions prove the real application accepts and parses a real-model artifact end-to-end — not a mock.
 */
@ExtendWith(MockitoExtension.class)
class DogfoodClaudeAsLlmTest {

    @Mock ModelSelector modelSelector;
    @Mock CostRecorder costRecorder;
    @Mock PromptComposer promptComposer;
    @Mock TestCaseRepository repository;
    @Mock TestConditionRepository conditionRepository;
    @Mock XrayClient xray;
    @Mock GateService gateService;
    @Mock Preflight preflight;

    /** What I (Claude) actually answer to Veritas's [TEST-CASES] contract for the PolicyController — grounded in the
     *  real constraints: EP + BVA, positive + negative, the @Size(max=10) boundary on {@code code}, both endpoints. */
    private static final String MODEL_RESPONSE = """
            Here are the ISTQB test cases for the policies API, grounded in the supplied endpoints and DTO constraints.

            ```json
            {
              "cases": [
                {"title": "Create policy with valid name and code returns 201",
                 "technique": "Equivalence Partitioning", "priority": "P1", "type": "Functional",
                 "rationale": "Valid-class EP for the create happy path (CTFL — Equivalence Partitioning).",
                 "requirementKey": "POLICY-CREATE",
                 "steps": [{"action": "POST /api/v1/policies", "data": "{\\"name\\":\\"Retail\\",\\"code\\":\\"RET-001\\"}", "expected": "201 Created; body has id, name=Retail, version"}]},
                {"title": "Reject create when name is blank",
                 "technique": "Equivalence Partitioning", "priority": "P1", "type": "Negative",
                 "rationale": "Invalid-class EP on @NotBlank name (CTFL — Equivalence Partitioning).",
                 "requirementKey": "POLICY-CREATE",
                 "steps": [{"action": "POST /api/v1/policies", "data": "{\\"name\\":\\"\\",\\"code\\":\\"RET-001\\"}", "expected": "400 Bad Request (name must not be blank)"}]},
                {"title": "Accept code at the 10-character boundary",
                 "technique": "Boundary Value Analysis", "priority": "P2", "type": "Functional",
                 "rationale": "Upper valid boundary of @Size(max=10) on code (CTFL — Boundary Value Analysis).",
                 "requirementKey": "POLICY-CREATE",
                 "steps": [{"action": "POST /api/v1/policies", "data": "{\\"name\\":\\"Retail\\",\\"code\\":\\"ABCDEFGHIJ\\"}", "expected": "201 Created (code length 10 is valid)"}]},
                {"title": "Reject code exceeding the 10-character boundary",
                 "technique": "Boundary Value Analysis", "priority": "P1", "type": "Negative",
                 "rationale": "First invalid boundary above @Size(max=10) on code (CTFL — Boundary Value Analysis).",
                 "requirementKey": "POLICY-CREATE",
                 "steps": [{"action": "POST /api/v1/policies", "data": "{\\"name\\":\\"Retail\\",\\"code\\":\\"ABCDEFGHIJK\\"}", "expected": "400 Bad Request (code exceeds max length 10)"}]},
                {"title": "Create policy without optional code returns 201",
                 "technique": "Equivalence Partitioning", "priority": "P3", "type": "Functional",
                 "rationale": "code carries only @Size, not @NotBlank, so absence is a valid class (CTFL — Equivalence Partitioning).",
                 "requirementKey": "POLICY-CREATE",
                 "steps": [{"action": "POST /api/v1/policies", "data": "{\\"name\\":\\"Retail\\"}", "expected": "201 Created"}]},
                {"title": "Get existing policy by id returns 200",
                 "technique": "Equivalence Partitioning", "priority": "P1", "type": "Functional",
                 "rationale": "Valid-class EP for the read happy path (CTFL — Equivalence Partitioning).",
                 "requirementKey": "POLICY-READ",
                 "steps": [{"action": "GET /api/v1/policies/{id}", "data": "id of a created policy", "expected": "200 OK; body has id, name, version"}]},
                {"title": "Get unknown policy id returns 404",
                 "technique": "Equivalence Partitioning", "priority": "P2", "type": "Negative",
                 "rationale": "Invalid-class EP for a non-existent resource (CTFL — Equivalence Partitioning).",
                 "requirementKey": "POLICY-READ",
                 "steps": [{"action": "GET /api/v1/policies/does-not-exist", "data": "unknown id", "expected": "404 Not Found"}]}
              ],
              "selfReview": {
                "confidence": 88,
                "blindSpots": [
                  "The supplied basis does not define the error-body schema for 400/404 responses.",
                  "No authentication/authorization requirements were supplied for these endpoints.",
                  "code constraints beyond max length (character set / pattern) are unknown."
                ]
              }
            }
            ```
            """;

    @Test
    void claudeAsTheLlmProducesSchemaValidTestCasesThroughTheRealPipeline() {
        // REAL extractor + REAL schema validator + REAL object mapper — only the LLM is swapped for Claude (me).
        JsonBlockExtractor jsonExtractor = new JsonBlockExtractor();
        ResponseSchemaValidator schemaValidator = new ResponseSchemaValidator(new DefaultResourceLoader());
        ObjectMapper objectMapper = new ObjectMapper();
        LlmGateway claude = new LlmGateway() {
            public boolean isAvailable() { return true; }
            public String complete(String prompt, String model) { return MODEL_RESPONSE; }   // Claude answers Veritas
        };

        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("claude-opus-4-8");
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(CostResult.zero("claude-opus-4-8"));
        lenient().when(promptComposer.data(anyString(), any())).thenReturn("DATA");
        lenient().when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("PROMPT");
        when(conditionRepository.findByServiceNameOrderByCreatedAtDesc("policies")).thenReturn(List.of());
        when(repository.save(any(TestCase.class))).thenAnswer(inv -> {
            TestCase tc = inv.getArgument(0);
            tc.setId("tc-" + Math.abs(tc.getTitle().hashCode()));
            return tc;
        });

        CreateTestCasesService service = new CreateTestCasesService(claude, jsonExtractor, schemaValidator,
                modelSelector, costRecorder, promptComposer, repository, conditionRepository, xray, gateService,
                objectMapper, preflight);

        String basis = "POST /api/v1/policies (201) — PolicyRequest{name @NotBlank, code @Size(max=10)} -> "
                + "PolicyResponse{id, name, version}; GET /api/v1/policies/{id} (200, 404).";
        List<TestCase> cases = service.generate("policies", basis, "claude");

        // The real pipeline accepted and parsed Claude's artifact: 7 schema-valid cases with real techniques + steps.
        assertThat(cases).hasSize(7);
        assertThat(cases).extracting(TestCase::getTechnique)
                .contains("Equivalence Partitioning", "Boundary Value Analysis");   // EP + BVA both present
        assertThat(cases).anyMatch(c -> c.getTitle().contains("10-character boundary"));   // grounded in @Size(max=10)
        assertThat(cases).allMatch(c -> c.getStepsJson() != null && c.getStepsJson().contains("/api/v1/policies"));
        assertThat(cases).allMatch(c -> "PROPOSED".equals(c.getStatus()));

        System.out.println("\n=== Veritas generated " + cases.size() + " test cases (Claude as the LLM) ===");
        cases.forEach(c -> System.out.println("  • [" + c.getTechnique() + " / " + c.getPriority() + "] " + c.getTitle()));
    }
}
