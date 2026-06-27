package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The reviewer is only shown a test's summary + steps, so it cannot legitimately score traceability / priority /
 * preconditions / risk-mapping. Any such gap the model emits is deterministically suppressed (it can only be
 * fabricated), while genuinely step-derivable gaps survive.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceGapSuppressionTest {

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

    @BeforeEach
    void setUp() {
        service = new ReviewService(llm, jsonExtractor, schemaValidator, modelSelector, costRecorder,
                promptComposer, repository, xray, gateService, objectMapper, preflight,
                new ca.bnc.qe.veritas.report.CitationSanitizer());
    }

    @Test
    void suppressesGapsAboutAttributesTheReviewerWasNeverShown() {
        // One fabricated gap per invisible dimension + one real, step-derivable gap that must survive.
        String json = "{\"score\":70,\"verdict\":\"Solid\",\"gaps\":["
                + "{\"severity\":\"HIGH\",\"issue\":\"No traceability to a linked requirement\",\"cite\":\"C5\"},"
                + "{\"severity\":\"MEDIUM\",\"issue\":\"Priority not justified against risk level\",\"cite\":\"C4\"},"
                + "{\"severity\":\"MEDIUM\",\"issue\":\"Preconditions are missing\",\"cite\":\"C2\"},"
                + "{\"severity\":\"HIGH\",\"issue\":\"No boundary value cases for the amount field\",\"cite\":\"C3\"}],"
                + "\"correctedSteps\":[],\"selfReview\":{\"confidence\":80,\"blindSpots\":[]}}";
        when(xray.getTestsByJql("jql")).thenReturn(List.of(
                new XrayTest("CIAM-1", "1", "Validate transfer", "Manual",
                        List.of(new XrayStep("POST /transfer", "amount=1", "200")))));
        when(promptComposer.data(anyString(), anyString())).thenReturn("DATA");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("PROMPT");
        when(modelSelector.resolveTier(ModelTier.STANDARD)).thenReturn("m");
        when(llm.complete(eq("PROMPT"), eq("m"))).thenReturn("raw");
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new CostResult("m", BillingMode.PER_REQUEST, 1, 1, 1, 0.01, false));
        when(jsonExtractor.extract(anyString())).thenReturn(json);
        when(repository.save(any(ReviewResult.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewResult r = service.reviewByJql("jql", "tester", false).get(0);

        // the three invisible-field gaps are gone; the real BVA gap survives
        assertThat(r.getGapsJson())
                .doesNotContain("traceability").doesNotContain("Priority").doesNotContain("Preconditions")
                .contains("No boundary value cases");
        // the suppression is disclosed as a blind spot
        assertThat(r.getDeliverableJson()).contains("Suppressed 3 gap(s)");
    }
}
