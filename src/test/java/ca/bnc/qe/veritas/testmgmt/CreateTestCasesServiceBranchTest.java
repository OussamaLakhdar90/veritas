package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.integration.xray.XrayTestSpec;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.persistence.TestCaseRepository;
import ca.bnc.qe.veritas.preflight.CopilotAuthRequiredException;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.skill.GateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-Mockito branch coverage for {@link CreateTestCasesService}: basis-parse / case generation, the
 * per-case cost split, null-vs-present rationale + requirementKey, self-review confidence defaulting,
 * the parse/validation failure wrap, and the {@code pushToXray} gate / write-scope / step-parse branches.
 * A real (spy) {@link ObjectMapper} does the JSON work so the parse branches are exercised for real.
 */
@ExtendWith(MockitoExtension.class)
class CreateTestCasesServiceBranchTest {

    @Mock LlmGateway llm;
    @Mock JsonBlockExtractor jsonExtractor;
    @Mock ResponseSchemaValidator schemaValidator;
    @Mock ModelSelector modelSelector;
    @Mock CostRecorder costRecorder;
    @Mock PromptComposer promptComposer;
    @Mock TestCaseRepository repository;
    @Mock XrayClient xray;
    @Mock GateService gateService;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @Mock Preflight preflight;

    @InjectMocks CreateTestCasesService service;

    private static final String OWNER = "tester";

    /** Wire the generative happy-path collaborators; the raw LLM body is supplied per test. */
    private void stubGeneratePath(String extractedJson, double estCost) {
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("claude-sonnet-4.6");
        when(promptComposer.data(anyString(), any())).thenReturn("DATA");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("PROMPT");
        when(llm.complete(eq("PROMPT"), eq("claude-sonnet-4.6"))).thenReturn("raw");
        when(costRecorder.record(eq("create-test-cases"), eq("generate"), eq("claude-sonnet-4.6"),
                anyString(), anyString(), eq(OWNER)))
                .thenReturn(new CostResult("claude-sonnet-4.6", null, 0, 0, 0, estCost, false));
        when(jsonExtractor.extract("raw")).thenReturn(extractedJson);
        // repository.save echoes the entity back with a stable id so callers see a persisted instance.
        // Lenient: the empty-cases path never saves, but every other generate test relies on this.
        lenient().when(repository.save(any(TestCase.class))).thenAnswer(inv -> {
            TestCase tc = inv.getArgument(0);
            tc.setId("tc-saved");
            return tc;
        });
    }

    @Test
    void unavailableLlmShortCircuitsBeforeAnyModelCall() {
        // requireLlm is the guard: make it throw the Copilot-auth signal and assert nothing downstream runs.
        // The real Preflight throws when the gateway is unavailable; mirror that via the mock here.
        doThrowCopilotAuth();

        assertThatThrownBy(() -> service.generate("svc", "basis", OWNER))
                .isInstanceOf(CopilotAuthRequiredException.class);

        verify(llm, never()).complete(any(), any());
        verify(repository, never()).save(any());
    }

    private void doThrowCopilotAuth() {
        org.mockito.Mockito.doThrow(new CopilotAuthRequiredException("create-test-cases"))
                .when(preflight).requireLlm(any(), eq("create-test-cases"));
    }

    @Test
    void generatesTwoCasesAndSplitsCostEvenly() {
        String json = "{\"cases\":["
                + "{\"title\":\"Happy path\",\"technique\":\"EP\",\"priority\":\"High\",\"type\":\"Functional\","
                + "\"rationale\":\"EP per ISTQB\",\"requirementKey\":\"CIAM-1\","
                + "\"steps\":[{\"action\":\"POST\",\"data\":\"valid\",\"expected\":\"201\"}]},"
                + "{\"title\":\"Boundary\",\"technique\":\"BVA\",\"priority\":\"Medium\",\"type\":\"Functional\","
                + "\"rationale\":\"BVA per ISTQB\",\"requirementKey\":\"CIAM-2\",\"steps\":[]}],"
                + "\"selfReview\":{\"confidence\":82,\"blindSpots\":[]}}";
        stubGeneratePath(json, 0.40);

        List<TestCase> out = service.generate("ciam-policies", "Endpoints", OWNER);

        assertThat(out).hasSize(2);
        assertThat(out).allMatch(c -> "PROPOSED".equals(c.getStatus()));
        assertThat(out).allMatch(c -> "ciam-policies".equals(c.getServiceName()));
        assertThat(out).allMatch(c -> OWNER.equals(c.getOwner()));
        assertThat(out).allMatch(c -> c.getConfidence() != null && c.getConfidence() == 82.0);
        // cases.size() > 0 → per-case split: 0.40 / 2 = 0.20 each
        assertThat(out).allMatch(c -> c.getEstCostUsd() == 0.20);
        // first case keeps its full structured deliverable
        TestCase first = out.get(0);
        assertThat(first.getTitle()).isEqualTo("Happy path");
        assertThat(first.getTechnique()).isEqualTo("EP");
        assertThat(first.getPriority()).isEqualTo("High");
        assertThat(first.getType()).isEqualTo("Functional");
        assertThat(first.getRationale()).isEqualTo("EP per ISTQB");
        assertThat(first.getLinkedRequirement()).isEqualTo("CIAM-1");
        assertThat(first.getStepsJson()).contains("\"action\":\"POST\"").contains("\"expected\":\"201\"");
        assertThat(out.get(1).getStepsJson()).isEqualTo("[]");
        verify(schemaValidator).validate(any(), eq("test-cases.schema.json"));
    }

    @Test
    void missingRationaleAndRequirementKeyLeaveNullFields() {
        // hasNonNull(...) == false on both → linkedRequirement & rationale must stay null (the else branch).
        String json = "{\"cases\":["
                + "{\"title\":\"No-trace case\",\"technique\":\"DT\",\"priority\":\"Low\",\"type\":\"Negative\","
                + "\"steps\":[]}],"
                + "\"selfReview\":{\"confidence\":50}}";
        stubGeneratePath(json, 1.0);

        TestCase tc = service.generate("svc", "basis", OWNER).get(0);

        assertThat(tc.getRationale()).isNull();
        assertThat(tc.getLinkedRequirement()).isNull();
        assertThat(tc.getTitle()).isEqualTo("No-trace case");
        assertThat(tc.getConfidence()).isEqualTo(50.0);
    }

    @Test
    void explicitNullRationaleAndRequirementKeyAlsoLeaveNullFields() {
        // hasNonNull is false for an explicit JSON null too — distinct from an absent field, same null result.
        String json = "{\"cases\":["
                + "{\"title\":\"Null-trace\",\"technique\":\"ST\",\"priority\":\"Low\",\"type\":\"Functional\","
                + "\"rationale\":null,\"requirementKey\":null,\"steps\":[]}],"
                + "\"selfReview\":{\"confidence\":71}}";
        stubGeneratePath(json, 1.0);

        TestCase tc = service.generate("svc", "basis", OWNER).get(0);

        assertThat(tc.getRationale()).isNull();
        assertThat(tc.getLinkedRequirement()).isNull();
        assertThat(tc.getConfidence()).isEqualTo(71.0);
    }

    @Test
    void emptyCaseUsesFieldDefaultsAndAbsentConfidenceDefaultsToZero() {
        // No cases-array fields and no selfReview.confidence → title "" default, others null, confidence 0.0.
        String json = "{\"cases\":[{\"steps\":[]}]}";
        stubGeneratePath(json, 0.75);

        TestCase tc = service.generate("svc", "basis", OWNER).get(0);

        assertThat(tc.getTitle()).isEqualTo("");        // asText("") default
        assertThat(tc.getTechnique()).isNull();          // asText(null) default
        assertThat(tc.getPriority()).isNull();
        assertThat(tc.getType()).isNull();
        assertThat(tc.getConfidence()).isEqualTo(0.0);   // selfReview.confidence absent → asDouble(0)
        // single case → per-case == full cost
        assertThat(tc.getEstCostUsd()).isEqualTo(0.75);
    }

    @Test
    void emptyCasesArrayReturnsEmptyListAndChargesNothingPerCase() {
        // cases.size() == 0 → the perCase branch keeps the full estCost (no division), loop body never runs.
        String json = "{\"cases\":[],\"selfReview\":{\"confidence\":90}}";
        stubGeneratePath(json, 0.33);

        List<TestCase> out = service.generate("svc", "basis", OWNER);

        assertThat(out).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void schemaValidationFailureIsWrappedInIllegalState() {
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("m");
        when(promptComposer.data(anyString(), any())).thenReturn("DATA");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("PROMPT");
        when(llm.complete(any(), any())).thenReturn("raw");
        when(costRecorder.record(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CostResult("m", null, 0, 0, 0, 0.1, false));
        when(jsonExtractor.extract("raw")).thenReturn("{\"cases\":[]}");
        // validator rejects → caught and rewrapped with the "create-test-cases failed:" prefix.
        org.mockito.Mockito.doThrow(new IllegalStateException("bad schema"))
                .when(schemaValidator).validate(any(), eq("test-cases.schema.json"));

        assertThatThrownBy(() -> service.generate("svc", "basis", OWNER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create-test-cases failed")
                .hasMessageContaining("bad schema");

        verify(repository, never()).save(any());
    }

    @Test
    void unparseableExtractedJsonIsWrappedInIllegalState() {
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("m");
        when(promptComposer.data(anyString(), any())).thenReturn("DATA");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("PROMPT");
        when(llm.complete(any(), any())).thenReturn("raw");
        when(costRecorder.record(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CostResult("m", null, 0, 0, 0, 0.1, false));
        // The real ObjectMapper spy throws on this garbage → exception path before validation.
        when(jsonExtractor.extract("raw")).thenReturn("not-json{");

        assertThatThrownBy(() -> service.generate("svc", "basis", OWNER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create-test-cases failed");

        verify(schemaValidator, never()).validate(any(), anyString());
    }

    // ---- pushToXray branches ----

    @Test
    void pushToXrayCreatesTestWhenGateApproved() {
        TestCase tc = new TestCase();
        tc.setId("tc-1");
        tc.setTitle("Validate create policy");
        tc.setStepsJson("[{\"action\":\"POST /p\",\"data\":\"v\",\"expected\":\"201\"}]");
        when(gateService.await("tc-1", "XRAY_CREATE_TEST", OWNER))
                .thenReturn(new GateService.Decision(true, "gate-9", "APPROVED"));
        when(xray.createTest(any())).thenReturn("CIAM-T100");
        when(repository.save(tc)).thenReturn(tc);

        TestCase result = service.pushToXray(tc, "CIAM", OWNER);

        assertThat(result.getXrayKey()).isEqualTo("CIAM-T100");
        assertThat(result.getStatus()).isEqualTo("CREATED_IN_XRAY");
        verify(preflight).requireXrayWriteScope();
        // steps are parsed and forwarded with the project/title/type the service builds.
        ArgumentCaptor<XrayTestSpec> spec = ArgumentCaptor.forClass(XrayTestSpec.class);
        verify(xray).createTest(spec.capture());
        assertThat(spec.getValue().projectKey()).isEqualTo("CIAM");
        assertThat(spec.getValue().summary()).isEqualTo("Validate create policy");
        assertThat(spec.getValue().testType()).isEqualTo("Manual");
        assertThat(spec.getValue().steps()).hasSize(1);
        assertThat(spec.getValue().steps().get(0).action()).isEqualTo("POST /p");
        assertThat(spec.getValue().steps().get(0).result()).isEqualTo("201");
    }

    @Test
    void pushToXrayThrowsWhenGateNotApproved() {
        TestCase tc = new TestCase();
        tc.setId("tc-2");
        when(gateService.await("tc-2", "XRAY_CREATE_TEST", OWNER))
                .thenReturn(new GateService.Decision(false, "gate-pending", "PENDING"));

        assertThatThrownBy(() -> service.pushToXray(tc, "CIAM", OWNER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting approval")
                .hasMessageContaining("gate-pending");

        verify(preflight, never()).requireXrayWriteScope();
        verify(xray, never()).createTest(any());
        verify(repository, never()).save(any());
    }

    @Test
    void pushToXrayPropagatesMissingWriteScope() {
        TestCase tc = new TestCase();
        tc.setId("tc-3");
        tc.setStepsJson("[]");
        when(gateService.await("tc-3", "XRAY_CREATE_TEST", OWNER))
                .thenReturn(new GateService.Decision(true, "gate-ok", "APPROVED"));
        org.mockito.Mockito.doThrow(new PreconditionException("xray-write", List.of("token missing")))
                .when(preflight).requireXrayWriteScope();

        assertThatThrownBy(() -> service.pushToXray(tc, "CIAM", OWNER))
                .isInstanceOf(PreconditionException.class);

        verify(xray, never()).createTest(any());
    }

    @Test
    void pushToXrayTreatsNullStepsJsonAsEmptySteps() {
        // stepsJson == null → parseSteps reads "[]" → zero steps, write still proceeds.
        TestCase tc = new TestCase();
        tc.setId("tc-4");
        tc.setTitle("No-steps test");
        tc.setStepsJson(null);
        when(gateService.await("tc-4", "XRAY_CREATE_TEST", OWNER))
                .thenReturn(new GateService.Decision(true, "g", "APPROVED"));
        when(xray.createTest(any())).thenReturn("CIAM-T7");
        when(repository.save(tc)).thenReturn(tc);

        TestCase result = service.pushToXray(tc, "CIAM", OWNER);

        assertThat(result.getXrayKey()).isEqualTo("CIAM-T7");
        ArgumentCaptor<XrayTestSpec> spec = ArgumentCaptor.forClass(XrayTestSpec.class);
        verify(xray).createTest(spec.capture());
        assertThat(spec.getValue().steps()).isEmpty();
    }

    @Test
    void pushToXrayTreatsMalformedStepsJsonAsEmptySteps() {
        // Malformed JSON in stepsJson is swallowed best-effort → empty steps, no throw.
        TestCase tc = new TestCase();
        tc.setId("tc-5");
        tc.setTitle("Broken-steps test");
        tc.setStepsJson("{not valid json");
        when(gateService.await("tc-5", "XRAY_CREATE_TEST", OWNER))
                .thenReturn(new GateService.Decision(true, "g", "APPROVED"));
        when(xray.createTest(any())).thenReturn("CIAM-T8");
        when(repository.save(tc)).thenReturn(tc);

        TestCase result = service.pushToXray(tc, "CIAM", OWNER);

        assertThat(result.getXrayKey()).isEqualTo("CIAM-T8");
        ArgumentCaptor<XrayTestSpec> spec = ArgumentCaptor.forClass(XrayTestSpec.class);
        verify(xray).createTest(spec.capture());
        assertThat(spec.getValue().steps()).isEmpty();
    }
}
