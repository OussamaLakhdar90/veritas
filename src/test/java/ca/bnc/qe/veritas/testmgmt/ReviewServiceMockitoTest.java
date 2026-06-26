package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.cost.BillingMode;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Pure-Mockito branch coverage for {@link ReviewService} — drives reviewByJql, the per-issue review,
 * dry-run vs apply, gate approval/rejection, persistence field mapping, and the error wrapping branch
 * without a Spring context (all collaborators mocked; a real {@link ObjectMapper} parses the JSON).
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceMockitoTest {

    @Mock private LlmGateway llm;
    @Mock private JsonBlockExtractor jsonExtractor;
    @Mock private ResponseSchemaValidator schemaValidator;
    @Mock private ModelSelector modelSelector;
    @Mock private CostRecorder costRecorder;
    @Mock private PromptComposer promptComposer;
    @Mock private ReviewResultRepository repository;
    @Mock private XrayClient xray;
    @Mock private GateService gateService;
    @Mock private Preflight preflight;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ReviewService service;

    private static final String FULL_JSON = "{"
            + "\"score\":78.5,"
            + "\"verdict\":\"Solid\","
            + "\"gaps\":[{\"severity\":\"minor\",\"issue\":\"no neg case\",\"cite\":\"C3\"}],"
            + "\"rubric\":[{\"criterion\":\"C1\",\"score\":4,\"note\":\"ok\"}],"
            + "\"correctedSteps\":[{\"action\":\"POST /x\",\"data\":\"body\",\"expected\":\"201\"}],"
            + "\"selfReview\":{\"confidence\":91.0,\"blindSpots\":[\"timing\"]}"
            + "}";

    @BeforeEach
    void setUp() {
        service = new ReviewService(llm, jsonExtractor, schemaValidator, modelSelector, costRecorder,
                promptComposer, repository, xray, gateService, objectMapper, preflight);
    }

    private XrayTest test(String key) {
        return new XrayTest(key, "1001", "summary", "Manual",
                List.of(new XrayStep("call POST /policies", "valid body", "201 created")));
    }

    private CostResult cost(double usd) {
        return new CostResult("claude-std", BillingMode.PER_REQUEST, 1.0, 100, 50, usd, true);
    }

    /**
     * Common stubs for a successful single-issue review returning {@code rawJson}. Lenient because some tests
     * deliberately short-circuit (schema failure, rejected gate) before reaching the save/persist stub.
     */
    private void stubReviewPipeline(String rawJson, double estCost) {
        org.mockito.Mockito.lenient().when(promptComposer.data(anyString(), anyString())).thenReturn("DATA");
        org.mockito.Mockito.lenient()
                .when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("PROMPT");
        org.mockito.Mockito.lenient().when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("claude-std");
        org.mockito.Mockito.lenient().when(llm.complete(eq("PROMPT"), eq("claude-std"))).thenReturn("raw ```json```");
        org.mockito.Mockito.lenient()
                .when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(cost(estCost));
        org.mockito.Mockito.lenient().when(jsonExtractor.extract(anyString())).thenReturn(rawJson);
        org.mockito.Mockito.lenient().when(repository.save(any(ReviewResult.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void reviewByJql_runsPreflightChecksBeforeLooping() {
        when(xray.getTestsByJql("project = CIAM")).thenReturn(List.of());

        List<ReviewResult> results = service.reviewByJql("project = CIAM", "tester", false);

        assertThat(results).isEmpty();
        verify(preflight).reviewTestCases("project = CIAM");
        verify(preflight).requireLlm(llm, "review-test-cases");
        // empty test list short-circuits before any LLM work
        verifyNoInteractions(llm, costRecorder, repository, gateService);
    }

    @Test
    void reviewByJql_reviewsEveryReturnedTest() {
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-1"), test("CIAM-2"), test("CIAM-3")));
        stubReviewPipeline(FULL_JSON, 0.02);

        List<ReviewResult> results = service.reviewByJql("jql", "tester", false);

        assertThat(results).hasSize(3);
        assertThat(results).extracting(ReviewResult::getTargetKey)
                .containsExactlyInAnyOrder("CIAM-1", "CIAM-2", "CIAM-3");
        verify(repository, times(3)).save(any(ReviewResult.class));
    }

    @Test
    void dryRun_persistsAllFieldsButDoesNotApply() {
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-7")));
        stubReviewPipeline(FULL_JSON, 0.03);

        List<ReviewResult> results = service.reviewByJql("jql", "alice", false);

        ReviewResult r = results.get(0);
        assertThat(r.getTargetType()).isEqualTo("XRAY_TEST");
        assertThat(r.getTargetKey()).isEqualTo("CIAM-7");
        assertThat(r.getScore()).isEqualTo(78.5);
        assertThat(r.getVerdict()).isEqualTo("Solid");
        assertThat(r.getGapsJson()).contains("no neg case").contains("minor");
        assertThat(r.getCorrectedJson()).contains("POST /x").contains("201");
        assertThat(r.getDeliverableJson()).contains("rubric").contains("selfReview");
        assertThat(r.getConfidence()).isEqualTo(91.0);
        assertThat(r.getOwner()).isEqualTo("alice");
        assertThat(r.getEstCostUsd()).isEqualTo(0.03);
        assertThat(r.isApplied()).isFalse();
        // schema validated, cost billed before parse, no outward write on dry-run
        verify(schemaValidator).validate(any(), eq("test-case-review.schema.json"));
        verify(xray, never()).updateTestSteps(anyString(), any());
        verifyNoInteractions(gateService);
    }

    @Test
    void confidence_defaultsToScoreWhenSelfReviewMissing() {
        String json = "{\"score\":64.0,\"verdict\":\"Weak\",\"gaps\":[],\"correctedSteps\":[]}";
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-9")));
        stubReviewPipeline(json, 0.01);

        List<ReviewResult> results = service.reviewByJql("jql", "bob", false);

        // no selfReview.confidence -> falls back to the overall score
        assertThat(results.get(0).getConfidence()).isEqualTo(64.0);
        assertThat(results.get(0).getScore()).isEqualTo(64.0);
    }

    @Test
    void missingScoreAndVerdict_defaultToZeroAndEmpty() {
        String json = "{\"gaps\":[],\"correctedSteps\":[]}";
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-0")));
        stubReviewPipeline(json, 0.0);

        ReviewResult r = service.reviewByJql("jql", "carol", false).get(0);

        assertThat(r.getScore()).isEqualTo(0.0);
        assertThat(r.getVerdict()).isEmpty();
        assertThat(r.getConfidence()).isEqualTo(0.0);
        assertThat(r.getGapsJson()).isEqualTo("[]");
        assertThat(r.getCorrectedJson()).isEqualTo("[]");
    }

    @Test
    void apply_whenGateApproved_updatesXrayWithParsedStepsAndMarksApplied() {
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-5")));
        stubReviewPipeline(FULL_JSON, 0.04);
        when(gateService.await("CIAM-5", "XRAY_UPDATE_STEPS", "dave"))
                .thenReturn(new GateService.Decision(true, "gate-1", "APPROVED"));

        ReviewResult r = service.reviewByJql("jql", "dave", true).get(0);

        assertThat(r.isApplied()).isTrue();
        ArgumentCaptor<List<XrayStep>> stepsCap = ArgumentCaptor.forClass(List.class);
        verify(xray).updateTestSteps(eq("CIAM-5"), stepsCap.capture());
        List<XrayStep> steps = stepsCap.getValue();
        assertThat(steps).hasSize(1);
        // parseSteps maps action/data and the "expected" field onto XrayStep.result
        assertThat(steps.get(0).action()).isEqualTo("POST /x");
        assertThat(steps.get(0).data()).isEqualTo("body");
        assertThat(steps.get(0).result()).isEqualTo("201");
    }

    @Test
    void apply_whenGateRejected_throwsWrappedAndDoesNotWriteXray() {
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-6")));
        stubReviewPipeline(FULL_JSON, 0.04);
        when(gateService.await("CIAM-6", "XRAY_UPDATE_STEPS", "erin"))
                .thenReturn(new GateService.Decision(false, "gate-9", "PENDING"));

        assertThatThrownBy(() -> service.reviewByJql("jql", "erin", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review-test-cases failed for CIAM-6")
                .hasMessageContaining("awaiting approval")
                .hasMessageContaining("gate-9");

        verify(xray, never()).updateTestSteps(anyString(), any());
        // the rejected case is never persisted (exception thrown before save)
        verify(repository, never()).save(any());
    }

    @Test
    void apply_whenNoCorrectedStepsField_skipsGateAndDoesNotApply() {
        String json = "{\"score\":70.0,\"verdict\":\"Fine\",\"gaps\":[]}";   // no correctedSteps key
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-8")));
        stubReviewPipeline(json, 0.02);

        ReviewResult r = service.reviewByJql("jql", "frank", true).get(0);

        assertThat(r.isApplied()).isFalse();
        assertThat(r.getCorrectedJson()).isEqualTo("");   // missing node -> MissingNode.toString() is ""
        verifyNoInteractions(gateService);
        verify(xray, never()).updateTestSteps(anyString(), any());
        verify(repository).save(any(ReviewResult.class));
    }

    @Test
    void apply_withEmptyCorrectedSteps_writesEmptyStepListToXray() {
        String json = "{\"score\":80.0,\"verdict\":\"Good\",\"gaps\":[],\"correctedSteps\":[]}";
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-11")));
        stubReviewPipeline(json, 0.02);
        when(gateService.await("CIAM-11", "XRAY_UPDATE_STEPS", "gina"))
                .thenReturn(new GateService.Decision(true, "gate-2", "APPROVED"));

        ReviewResult r = service.reviewByJql("jql", "gina", true).get(0);

        assertThat(r.isApplied()).isTrue();
        ArgumentCaptor<List<XrayStep>> stepsCap = ArgumentCaptor.forClass(List.class);
        verify(xray).updateTestSteps(eq("CIAM-11"), stepsCap.capture());
        assertThat(stepsCap.getValue()).isEmpty();
    }

    @Test
    void schemaValidationFailure_isWrappedWithIssueKey() {
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-42")));
        stubReviewPipeline(FULL_JSON, 0.02);
        org.mockito.Mockito.doThrow(new IllegalStateException("schema boom"))
                .when(schemaValidator).validate(any(), anyString());

        assertThatThrownBy(() -> service.reviewByJql("jql", "tester", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review-test-cases failed for CIAM-42")
                .hasMessageContaining("schema boom");

        verify(repository, never()).save(any());
    }

    @Test
    void llmFailure_isWrappedWithIssueKeyAndCause() {
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-99")));
        when(promptComposer.data(anyString(), anyString())).thenReturn("DATA");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("PROMPT");
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("claude-std");
        when(llm.complete(anyString(), anyString())).thenThrow(new RuntimeException("gateway down"));

        assertThatThrownBy(() -> service.reviewByJql("jql", "tester", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review-test-cases failed for CIAM-99")
                .hasMessageContaining("gateway down")
                .hasRootCauseInstanceOf(RuntimeException.class);

        verify(costRecorder, never()).record(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void malformedJsonFromExtractor_isWrappedAsReviewFailure() {
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-13")));
        when(promptComposer.data(anyString(), anyString())).thenReturn("DATA");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("PROMPT");
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("claude-std");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(cost(0.01));
        when(jsonExtractor.extract(anyString())).thenReturn("{ this is not json");

        assertThatThrownBy(() -> service.reviewByJql("jql", "tester", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review-test-cases failed for CIAM-13");

        // billed before the parse blew up
        verify(costRecorder).record(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void billsCostBeforeParsing_usingResolvedModelAndIssueKeyAsRef() {
        when(xray.getTestsByJql("jql")).thenReturn(List.of(test("CIAM-3")));
        stubReviewPipeline(FULL_JSON, 0.055);

        service.reviewByJql("jql", "owner1", false);

        verify(modelSelector).resolveTier(ModelTier.STANDARD);
        verify(costRecorder).record(eq("review-test-cases"), eq("review"), eq("claude-std"),
                eq("PROMPT"), anyString(), eq("owner1"), eq("CIAM-3"));
    }

    @Test
    void preflightBlocker_propagatesAndSkipsXrayLookup() {
        org.mockito.Mockito.doThrow(new RuntimeException("no jql"))
                .when(preflight).reviewTestCases("bad");

        assertThatThrownBy(() -> service.reviewByJql("bad", "tester", false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no jql");

        verifyNoInteractions(xray, llm, repository);
    }
}
