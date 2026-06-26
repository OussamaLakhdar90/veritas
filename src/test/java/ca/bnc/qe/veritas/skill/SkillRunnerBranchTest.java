package ca.bnc.qe.veritas.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.cost.BillingMode;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptAssembler;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.RunStep;
import ca.bnc.qe.veritas.persistence.RunStepRepository;
import ca.bnc.qe.veritas.persistence.SkillRun;
import ca.bnc.qe.veritas.persistence.SkillRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Pure-Mockito branch coverage for {@link SkillRunner}: every step kind, the {@code when} guard truth
 * table, input validation, cost roll-up, output storage, gate denial and step failure → FAILED. Uses an
 * explicit constructor (the production bean has 12 collaborators incl. a handler {@code Map}) so each
 * collaborator is independently stubbed/verified. No Spring context, no production code touched.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillRunnerBranchTest {

    @Mock SkillManifestLoader loader;
    @Mock LlmGateway llm;
    @Mock PromptAssembler promptAssembler;
    @Mock JsonBlockExtractor jsonExtractor;
    @Mock ResponseSchemaValidator schemaValidator;
    @Mock GateService gateService;
    @Mock ModelSelector modelSelector;
    @Mock CostRecorder costRecorder;
    @Mock SkillRunRepository runRepository;
    @Mock RunStepRepository stepRepository;
    @Mock StepHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, StepHandler> handlers = new java.util.HashMap<>();

    private SkillRunner runner;

    @BeforeEach
    void setUp() {
        handlers.clear();
        runner = new SkillRunner(loader, handlers, llm, promptAssembler, jsonExtractor, schemaValidator,
                gateService, modelSelector, costRecorder, runRepository, stepRepository, objectMapper);
        // AuditableEntity assigns a random UUID at construction; pin it to a stable id on every save so the
        // run id threaded into costRecorder/gateService matchers is deterministic ("run-1").
        when(runRepository.save(any(SkillRun.class))).thenAnswer(inv -> {
            SkillRun r = inv.getArgument(0);
            r.setId("run-1");
            return r;
        });
    }

    private SkillManifest manifest(List<InputSpec> inputs, List<String> tokens, Step... steps) {
        return new SkillManifest("demo", "d", null, inputs, tokens, null, List.of(steps), null);
    }

    private Step deterministic(String id, String out, String when) {
        return new Step(id, StepKind.DETERMINISTIC, "h", null, null, null, null, null, out, when);
    }

    private Step llmStep(String id, String out, String expectsJson) {
        return new Step(id, StepKind.LLM, null, "p.md", "claude-x", null, null, expectsJson, out, null);
    }

    private Step gateStep(String id) {
        return new Step(id, StepKind.GATE, null, null, null, null, null, null, "gateOut", null);
    }

    private CostResult cost(double premium, double usd) {
        return new CostResult("claude-x", BillingMode.PER_REQUEST, premium, 100, 50, usd, true);
    }

    // ---- happy path: all three step kinds in one run ----------------------------------------------------

    @Test
    void runsDeterministicLlmAndGateAndRollsUpCostAndOutputs() throws Exception {
        handlers.put("h", handler);
        when(handler.handle(any(), any())).thenReturn("det-output");
        when(loader.get("demo")).thenReturn(manifest(null, null,
                deterministic("d1", "detOut", null),
                llmStep("l1", "llmOut", "schema.json"),
                gateStep("g1")));

        when(promptAssembler.assemble(any(), any())).thenReturn("PROMPT");
        when(modelSelector.resolve(any())).thenReturn("claude-x");
        when(llm.complete("PROMPT", "claude-x")).thenReturn("```json\n{\"k\":1}\n```");
        when(jsonExtractor.extract(anyString())).thenReturn("{\"k\":1}");
        when(costRecorder.record(eq("demo"), eq("l1"), eq("claude-x"), eq("PROMPT"), anyString(), isNull(), eq("run-1")))
                .thenReturn(cost(1.0, 0.25));
        when(gateService.await("run-1", "g1", "system"))
                .thenReturn(new GateService.Decision(true, "gate-42", "APPROVED"));

        SkillRunResult result = runner.run("demo", Map.of("foo", "bar"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.runId()).isEqualTo("run-1");
        assertThat(result.totalPremiumRequests()).isEqualTo(1.0);
        assertThat(result.totalEstCostUsd()).isEqualTo(0.25);
        assertThat(result.outputs()).containsEntry("detOut", "det-output");
        assertThat(result.outputs().get("llmOut")).isNotNull();
        assertThat(result.outputs()).containsKey("gateOut");
        @SuppressWarnings("unchecked")
        Map<String, Object> gateOut = (Map<String, Object>) result.outputs().get("gateOut");
        assertThat(gateOut).containsEntry("approved", true).containsEntry("gateId", "gate-42");

        // schema present → validate invoked; ledger billed before parse
        verify(schemaValidator).validate(any(), eq("schema.json"));
        verify(stepRepository, times(3)).save(any(RunStep.class));
    }

    // ---- input validation ------------------------------------------------------------------------------

    @Test
    void missingRequiredInputThrowsBeforeAnyRunIsPersisted() {
        when(loader.get("demo")).thenReturn(manifest(
                List.of(new InputSpec("ticket", "string", true, null)), null,
                deterministic("d1", "o", null)));

        assertThatThrownBy(() -> runner.run("demo", Map.of("other", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires input 'ticket'");

        verifyNoInteractions(runRepository, stepRepository);
    }

    @Test
    void nullInputsMapWithRequiredSpecThrows() {
        when(loader.get("demo")).thenReturn(manifest(
                List.of(new InputSpec("ticket", "string", true, null)), null,
                deterministic("d1", "o", null)));

        assertThatThrownBy(() -> runner.run("demo", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires input 'ticket'");
    }

    @Test
    void optionalInputAbsentIsAccepted() {
        handlers.put("h", handler);
        when(handler.handle(any(), any())).thenReturn("ok");
        when(loader.get("demo")).thenReturn(manifest(
                List.of(new InputSpec("note", "string", false, null)), null,
                deterministic("d1", "o", null)));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.outputs()).containsEntry("o", "ok");
    }

    @Test
    void nullInputSpecsSkipsValidationEntirely() {
        handlers.put("h", handler);
        when(handler.handle(any(), any())).thenReturn("ok");
        when(loader.get("demo")).thenReturn(manifest(null, null, deterministic("d1", "o", null)));

        SkillRunResult result = runner.run("demo", null);

        assertThat(result.status()).isEqualTo("COMPLETED");
    }

    // ---- when guard ------------------------------------------------------------------------------------

    @Test
    void whenGuardFalseSkipsStepAndRecordsZeroCost() {
        handlers.put("h", handler);
        when(loader.get("demo")).thenReturn(manifest(null, null,
                deterministic("d1", "o", "input['flag'] == true")));

        SkillRunResult result = runner.run("demo", Map.of("flag", false));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.outputs()).doesNotContainKey("o");
        verify(handler, never()).handle(any(), any());

        ArgumentCaptor<RunStep> stepCap = ArgumentCaptor.forClass(RunStep.class);
        verify(stepRepository).save(stepCap.capture());
        RunStep skipped = stepCap.getValue();
        assertThat(skipped.getStatus()).isEqualTo("SKIPPED");
        assertThat(skipped.getDurationMs()).isZero();
        assertThat(skipped.getModel()).isNull();
    }

    @Test
    void whenGuardTrueRunsStep() {
        handlers.put("h", handler);
        when(handler.handle(any(), any())).thenReturn("ran");
        when(loader.get("demo")).thenReturn(manifest(null, null,
                deterministic("d1", "o", "input['flag'] == true")));

        SkillRunResult result = runner.run("demo", Map.of("flag", true));

        assertThat(result.outputs()).containsEntry("o", "ran");
    }

    @Test
    void blankWhenGuardTreatedAsAlwaysRun() {
        handlers.put("h", handler);
        when(handler.handle(any(), any())).thenReturn("ran");
        when(loader.get("demo")).thenReturn(manifest(null, null,
                deterministic("d1", "o", "   ")));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.outputs()).containsEntry("o", "ran");
    }

    // ---- deterministic handler branches ----------------------------------------------------------------

    @Test
    void missingHandlerBeanFailsTheRunWithMessage() {
        // handlers map intentionally left empty → runDeterministic throws IllegalStateException
        when(loader.get("demo")).thenReturn(manifest(null, null, deterministic("d1", "o", null)));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("FAILED");

        ArgumentCaptor<SkillRun> runCap = ArgumentCaptor.forClass(SkillRun.class);
        verify(runRepository, times(2)).save(runCap.capture());
        SkillRun finalRun = runCap.getValue();
        assertThat(finalRun.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(finalRun.getErrorMessage()).contains("No handler bean 'h'").contains("d1");
        assertThat(finalRun.getFinishedAt()).isNotNull();
    }

    @Test
    void deterministicNullOutputIsNotStored() {
        handlers.put("h", handler);
        when(handler.handle(any(), any())).thenReturn(null);
        when(loader.get("demo")).thenReturn(manifest(null, null, deterministic("d1", "o", null)));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.outputs()).doesNotContainKey("o");
    }

    @Test
    void outputWithNullOutNameIsNotStored() {
        handlers.put("h", handler);
        when(handler.handle(any(), any())).thenReturn("value");
        when(loader.get("demo")).thenReturn(manifest(null, null,
                deterministic("d1", null, null)));   // out == null → ctx.put skipped

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.outputs()).isEmpty();
    }

    // ---- LLM branches ----------------------------------------------------------------------------------

    @Test
    void llmStepWithoutSchemaSkipsValidationButStillBills() throws Exception {
        when(loader.get("demo")).thenReturn(manifest(null, null,
                llmStep("l1", "llmOut", null)));   // expectsJson == null
        when(promptAssembler.assemble(any(), any())).thenReturn("PROMPT");
        when(modelSelector.resolve(any())).thenReturn("claude-x");
        when(llm.complete("PROMPT", "claude-x")).thenReturn("{\"a\":2}");
        when(jsonExtractor.extract(anyString())).thenReturn("{\"a\":2}");
        when(costRecorder.record(eq("demo"), eq("l1"), eq("claude-x"), eq("PROMPT"), anyString(), isNull(), eq("run-1")))
                .thenReturn(cost(2.0, 0.5));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.totalPremiumRequests()).isEqualTo(2.0);
        assertThat(result.totalEstCostUsd()).isEqualTo(0.5);
        verify(schemaValidator, never()).validate(any(), any());
        // cost is captured onto the persisted RunStep too
        ArgumentCaptor<RunStep> stepCap = ArgumentCaptor.forClass(RunStep.class);
        verify(stepRepository).save(stepCap.capture());
        assertThat(stepCap.getValue().getModel()).isEqualTo("claude-x");
        assertThat(stepCap.getValue().getPremiumRequests()).isEqualTo(2.0);
        assertThat(stepCap.getValue().getEstCostUsd()).isEqualTo(0.5);
    }

    @Test
    void llmBillsBeforeParseSoSchemaFailureStillRecordsCost() throws Exception {
        when(loader.get("demo")).thenReturn(manifest(null, null,
                llmStep("l1", "llmOut", "schema.json")));
        when(promptAssembler.assemble(any(), any())).thenReturn("PROMPT");
        when(modelSelector.resolve(any())).thenReturn("claude-x");
        when(llm.complete(anyString(), anyString())).thenReturn("{\"a\":1}");
        when(jsonExtractor.extract(anyString())).thenReturn("{\"a\":1}");
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), anyString()))
                .thenReturn(cost(1.0, 0.1));
        // schema validation rejects the reply → step fails
        org.mockito.Mockito.doThrow(new IllegalStateException("schema boom"))
                .when(schemaValidator).validate(any(), eq("schema.json"));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("FAILED");
        // the LLM call was billed even though the step ultimately failed validation
        verify(costRecorder).record(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), anyString());
    }

    @Test
    void llmInvalidJsonFromExtractorFailsTheRun() throws Exception {
        when(loader.get("demo")).thenReturn(manifest(null, null, llmStep("l1", "o", null)));
        when(promptAssembler.assemble(any(), any())).thenReturn("PROMPT");
        when(modelSelector.resolve(any())).thenReturn("claude-x");
        when(llm.complete(anyString(), anyString())).thenReturn("not json");
        when(jsonExtractor.extract(anyString())).thenReturn("this-is-not-json");
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), anyString()))
                .thenReturn(cost(1.0, 0.1));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("FAILED");
    }

    // ---- gate branches ---------------------------------------------------------------------------------

    @Test
    void rejectedGateFailsTheRun() {
        when(loader.get("demo")).thenReturn(manifest(null, null, gateStep("g1")));
        when(gateService.await("run-1", "g1", "system"))
                .thenReturn(new GateService.Decision(false, "gate-9", "PENDING"));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("FAILED");
        ArgumentCaptor<SkillRun> runCap = ArgumentCaptor.forClass(SkillRun.class);
        verify(runRepository, times(2)).save(runCap.capture());
        assertThat(runCap.getValue().getErrorMessage()).contains("awaiting approval").contains("gate-9");
    }

    // ---- token resolution ------------------------------------------------------------------------------

    @Test
    void resolvesDeclaredTokenFromEnvironmentWhenPresentElseSkips() {
        handlers.put("h", handler);
        when(handler.handle(any(), any())).thenReturn("ok");
        // PATH is virtually always set; BOGUS_TOKEN_XYZ_123 should be absent → exercises both env branches.
        when(loader.get("demo")).thenReturn(manifest(null, List.of("PATH", "BOGUS_TOKEN_XYZ_123"),
                deterministic("d1", "o", null)));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("COMPLETED");
    }

    // ---- cost roll-up across multiple LLM steps + rounding ---------------------------------------------

    @Test
    void multipleLlmStepsSumPremiumAndRoundCost() throws Exception {
        when(loader.get("demo")).thenReturn(manifest(null, null,
                llmStep("l1", "o1", null),
                llmStep("l2", "o2", null)));
        when(promptAssembler.assemble(any(), any())).thenReturn("PROMPT");
        when(modelSelector.resolve(any())).thenReturn("claude-x");
        when(llm.complete(anyString(), anyString())).thenReturn("{\"a\":1}");
        when(jsonExtractor.extract(anyString())).thenReturn("{\"a\":1}");
        when(costRecorder.record(eq("demo"), eq("l1"), anyString(), anyString(), anyString(), isNull(), anyString()))
                .thenReturn(cost(1.5, 0.11115));   // forces the half-up rounding to 4dp
        when(costRecorder.record(eq("demo"), eq("l2"), anyString(), anyString(), anyString(), isNull(), anyString()))
                .thenReturn(cost(2.5, 0.22225));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.totalPremiumRequests()).isEqualTo(4.0);
        // (0 + 0.11115) → 0.1112 (round) then (+ 0.22225) → 0.33345 → 0.3335 (round half-up at 4dp via *10000)
        assertThat(result.totalEstCostUsd()).isEqualTo(0.3335);
    }

    // ---- tier-driven model selection (no explicit model) ----------------------------------------------

    @Test
    void llmStepUsesModelSelectorResolvedModel() throws Exception {
        Step tierStep = new Step("l1", StepKind.LLM, null, "p.md", null, ModelTier.FRONTIER,
                null, null, "o", null);
        when(loader.get("demo")).thenReturn(manifest(null, null, tierStep));
        when(promptAssembler.assemble(any(), any())).thenReturn("PROMPT");
        when(modelSelector.resolve(tierStep)).thenReturn("resolved-model");
        when(llm.complete("PROMPT", "resolved-model")).thenReturn("{\"a\":1}");
        when(jsonExtractor.extract(anyString())).thenReturn("{\"a\":1}");
        when(costRecorder.record(anyString(), anyString(), eq("resolved-model"), anyString(), anyString(), isNull(), anyString()))
                .thenReturn(cost(1.0, 0.1));

        SkillRunResult result = runner.run("demo", Map.of());

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(llm).complete("PROMPT", "resolved-model");
        verify(modelSelector).resolve(tierStep);
    }

    // ---- error message truncation ----------------------------------------------------------------------

    @Test
    void longErrorMessageIsTruncatedTo1900Chars() {
        handlers.put("h", handler);
        String huge = "x".repeat(5000);
        when(handler.handle(any(), any())).thenThrow(new RuntimeException(huge));
        when(loader.get("demo")).thenReturn(manifest(null, null, deterministic("d1", "o", null)));

        runner.run("demo", Map.of());

        ArgumentCaptor<SkillRun> runCap = ArgumentCaptor.forClass(SkillRun.class);
        verify(runRepository, times(2)).save(runCap.capture());
        assertThat(runCap.getValue().getErrorMessage()).hasSize(1900);
    }
}