package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import ca.bnc.qe.veritas.cost.BillingMode;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.engine.openapi.CorrectedSpecBuilder;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.engine.openapi.SpecPresence;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmCallContext;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.report.ContractReportRenderer;
import ca.bnc.qe.veritas.report.TranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Branch coverage for {@link ContractValidationService#runInto} and its private helpers (reconcile batching/merge,
 * design-finding suppression, cross-list dedup, the LLM-unavailable degrade-to-diff path, corrected-YAML choice and
 * persistence). All collaborators are mocked so the orchestration logic is exercised in isolation. Assertions check
 * real values (these face mutation testing). Does NOT edit the existing ContractValidation*Test classes.
 */
class ContractValidationServiceBranchTest {

    private JavaSpringExtractor javaExtractor;
    private OpenApiModelExtractor openApi;
    private CorrectedSpecBuilder correctedSpecBuilder;
    private DiffEngine diffEngine;
    private LlmGateway llm;
    private JsonBlockExtractor jsonExtractor;
    private ResponseSchemaValidator schemaValidator;
    private ModelSelector modelSelector;
    private CostRecorder costRecorder;
    private PromptComposer promptComposer;
    private ContractReportRenderer reportRenderer;
    private ScanRepository scanRepo;
    private FindingRecordRepository findingRepo;
    private Preflight preflight;
    private ScanPersistence scanPersistence;
    private TranslationService translationService;
    private LlmCallContext callContext;
    private final ObjectMapper mapper = new ObjectMapper();

    private ContractValidationService svc;

    @BeforeEach
    void setUp() throws Exception {
        javaExtractor = mock(JavaSpringExtractor.class);
        openApi = mock(OpenApiModelExtractor.class);
        correctedSpecBuilder = mock(CorrectedSpecBuilder.class);
        diffEngine = mock(DiffEngine.class);
        llm = mock(LlmGateway.class);
        jsonExtractor = mock(JsonBlockExtractor.class);
        schemaValidator = mock(ResponseSchemaValidator.class);
        modelSelector = mock(ModelSelector.class);
        costRecorder = mock(CostRecorder.class);
        promptComposer = mock(PromptComposer.class);
        reportRenderer = mock(ContractReportRenderer.class);
        scanRepo = mock(ScanRepository.class);
        findingRepo = mock(FindingRecordRepository.class);
        preflight = mock(Preflight.class);
        scanPersistence = mock(ScanPersistence.class);
        translationService = mock(TranslationService.class);
        callContext = new LlmCallContext();

        // Echo-save so the in-memory Scan keeps its mutated state; @Version stays null → no conflict.
        when(scanRepo.save(any(Scan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(scanRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of());
        // Code model: one endpoint, no blind spots by default.
        when(javaExtractor.extract(any())).thenReturn(codeModel(List.of(), endpoint("GET", "/x")));
        // Report renderer succeeds; PDF returns bytes by default.
        when(reportRenderer.renderHtml(any(), any(), any())).thenReturn("<html>ok</html>");
        when(reportRenderer.renderPdf(any(), any(), any())).thenReturn(new byte[] {1, 2, 3});
        // Deterministic corrected spec round-trips by default.
        when(correctedSpecBuilder.build(any(), any(), any())).thenReturn("openapi: 3.0.3");
        // Metadata overlay on the chosen (LLM-preferred) corrected YAML is a passthrough by default here; the real
        // info/servers preservation is exercised in CorrectedSpecBuilderTest.
        when(correctedSpecBuilder.withOriginalMetadata(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        // The write-boundary dangling-ref sanitiser is a passthrough on a clean spec (real logic in CorrectedSpecBuilderTest).
        when(correctedSpecBuilder.withoutDanglingRefs(any())).thenAnswer(inv -> inv.getArgument(0));
        // The optional error-schema enrichment is a passthrough by default (real logic in CorrectedSpecBuilderTest).
        when(correctedSpecBuilder.withErrorSchemasFromSpec(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(openApi.extract(eq("corrected-check"), anyString()))
                .thenReturn(new SpecParse(specModel("corrected-check"), List.of(), true));
        when(openApi.presenceOf(anyString())).thenReturn(SpecPresence.empty());
        when(modelSelector.resolveTier(any())).thenReturn("claude-test");
        when(modelSelector.promptTokenCap(anyString())).thenReturn(60000);
        when(promptComposer.data(anyString(), anyString())).thenReturn("");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("PROMPT");

        svc = newService();
    }

    private ContractValidationService newService() {
        return new ContractValidationService(javaExtractor, openApi, correctedSpecBuilder, diffEngine, llm,
                jsonExtractor, schemaValidator, modelSelector, costRecorder, promptComposer, reportRenderer,
                scanRepo, findingRepo, mapper, preflight, scanPersistence, translationService, callContext);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = ContractValidationService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static ApiModel codeModel(List<String> blindSpots, Endpoint... eps) {
        return new ApiModel("code", "Svc", "1.0", null, List.of(eps), Map.of(), blindSpots);
    }

    /** A code model whose {@code schemas} carry a real SourceRef, so evidence binding has something to resolve against. */
    private static ApiModel codeModelWithSchema(String schemaName, String fieldName, Endpoint... eps) {
        SourceRef fieldSrc = SourceRef.code("src/main/java/ca/bnc/Dto.java", 42, 42, null);
        SourceRef schemaSrc = SourceRef.code("src/main/java/ca/bnc/Dto.java", 10, 20, null);
        FieldModel field = new FieldModel(fieldName, "string", null, false, ConstraintSet.empty(), null, fieldSrc);
        SchemaModel schema = new SchemaModel(schemaName, "object", List.of(field), List.of(), schemaSrc);
        return new ApiModel("code", "Svc", "1.0", null, List.of(eps), Map.of(schemaName, schema), List.of());
    }

    private static ApiModel specModel(String source, Endpoint... eps) {
        return new ApiModel(source, "Spec", "1.0", "3.0.3", List.of(eps), Map.of(), List.of());
    }

    private static Endpoint endpoint(String method, String path) {
        return new Endpoint(HttpMethod.valueOf(method), path, null, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), null);
    }

    private static Finding deterministic(String summary) {
        return Finding.builder().findingId("det-" + summary.hashCode()).type(FindingType.STATUS_CODE_MISSING)
                .layer(Layer.L4).severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                .endpoint("GET /x").specSource("repo-spec").summary(summary).build();
    }

    private static ValidationRequest req(boolean llmEnabled, SpecInput... specs) {
        return new ValidationRequest("svc", "APP", "repo", "main", Path.of("repo"),
                List.of(specs), llmEnabled, "owner", Thoroughness.STANDARD);
    }

    private static CostResult cost() {
        return new CostResult("claude-test", BillingMode.PER_REQUEST, 1.0, 100, 50, 0.02, false);
    }

    /** A reconcile reply that enriches one finding, adds one L5 + one L6 design finding, and a corrected spec. */
    private String reconcileReply() {
        return "{"
                + "\"correctedYaml\":\"openapi: 3.0.3\","
                + "\"findings\":[{\"findingId\":\"FID\",\"explanation\":\"why\",\"proposedFix\":\"fix it\"}],"
                + "\"designFindings\":["
                + "{\"layer\":\"L5\",\"severity\":\"MINOR\",\"endpoint\":\"GET /x\",\"summary\":\"weak design\",\"explanation\":\"e5\"},"
                + "{\"layer\":\"L6\",\"severity\":\"MAJOR\",\"summary\":\"thin test basis\",\"explanation\":\"e6\"}"
                + "],"
                + "\"selfReview\":{\"confidence\":78.0,\"blindSpots\":[\"unparsed file Foo.java\"]}"
                + "}";
    }

    private void armLlm(String reply) {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(reply);
        when(jsonExtractor.extract(anyString())).thenReturn(reply);   // already pure JSON
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(cost());
    }

    // ---------------------------------------------------------------------------------------------------------

    @Test
    void noSpecsNoFindings_skipsReconcileAndCompletes() {
        ValidationResult r = svc.validate(req(true));   // no specs → findings empty → reconcile skipped

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.totalFindings()).isZero();
        // reconcile never ran because findings were empty
        verify(llm, never()).complete(anyString(), anyString());
        // happy path persists atomically through ScanPersistence
        verify(scanPersistence, times(1)).complete(any(Scan.class), any(), any());
    }

    @Test
    void llmEnabledButUnavailable_degradesToDiffOnly() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        when(llm.isAvailable()).thenReturn(false);   // degrade: there ARE findings but the gateway is down

        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.totalFindings()).isEqualTo(1);   // the deterministic finding survives; no LLM findings added
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void llmDisabled_neverChecksAvailabilityOrReconciles() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));

        ValidationResult r = svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.status()).isEqualTo("COMPLETED");
        verify(llm, never()).isAvailable();
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void fullReconcile_addsDesignFindings_graftsEnrichment_andRecordsCostConfidenceBlindSpots() {
        Finding det = Finding.builder().findingId("FID").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("Code returns 500 not in spec").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(det)));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        armLlm(reconcileReply());

        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.status()).isEqualTo("COMPLETED");
        // 1 deterministic + L5 design + L6 design = 3 findings persisted
        assertThat(r.totalFindings()).isEqualTo(3);

        verify(llm, times(1)).complete(anyString(), anyString());
        // cost/confidence/blindSpots propagated from the reconcile reply onto the scan, asserted on the persisted scan
        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Finding>> findCap =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(scanPersistence).complete(scanCap.capture(), findCap.capture(), any());
        Scan persisted = scanCap.getValue();
        assertThat(persisted.getConfidence()).isEqualTo(78.0);
        assertThat(persisted.getBlindSpots()).contains("unparsed file Foo.java");
        assertThat(persisted.getTotalEstCostUsd()).isEqualTo(0.02);
        assertThat(persisted.getTotalPremiumRequests()).isEqualTo(1.0);
        assertThat(persisted.getModel()).isEqualTo("claude-test");

        List<Finding> finalFindings = findCap.getValue();
        // the deterministic FID got the LLM explanation + proposedFix grafted on
        Finding fid = finalFindings.stream().filter(f -> "FID".equals(f.getFindingId())).findFirst().orElseThrow();
        assertThat(fid.getExplanation()).isEqualTo("why");
        assertThat(fid.getProposedFix()).isEqualTo("fix it");
        // L5 + L6 design findings present
        assertThat(finalFindings).anyMatch(f -> f.getType() == FindingType.DESIGN_QUALITY && f.getLayer() == Layer.L5);
        assertThat(finalFindings).anyMatch(f -> f.getType() == FindingType.TEST_BASIS_GAP && f.getLayer() == Layer.L6);
    }

    @Test
    void aiDispute_movesABlockerOutOfTheGate_butKeepsItListedWithSeverityIntact() {
        Finding det = Finding.builder().findingId("FID").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.BLOCKER).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("Code returns 500 not in spec").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(det)));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],\"designFindings\":[],"
                + "\"disputedFindings\":[{\"findingId\":\"FID\",\"reason\":\"the @ControllerAdvice does map 500 here\"}]}");

        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));
        assertThat(r.status()).isEqualTo("COMPLETED");

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Finding>> findCap =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(scanPersistence).complete(scanCap.capture(), findCap.capture(), any());

        Finding fid = findCap.getValue().stream().filter(f -> "FID".equals(f.getFindingId())).findFirst().orElseThrow();
        assertThat(fid.isAiDisputed()).isTrue();
        assertThat(fid.getAiDisputeReason()).contains("ControllerAdvice");
        assertThat(fid.getSeverity()).isEqualTo(Severity.BLOCKER);   // severity is NEVER altered — a human can overturn
        // It is still listed (not deleted) but moved out of the score + release gate.
        assertThat(ca.bnc.qe.veritas.report.FidelityScore.isNeedsAttention(fid)).isTrue();
        assertThat(scanCap.getValue().getFidelityScore()).isEqualTo(100);   // a disputed BLOCKER costs no penalty
    }

    @Test
    void aiDispute_forAnUnknownFindingId_isIgnored() {
        Finding det = Finding.builder().findingId("FID").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.BLOCKER).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("Code returns 500 not in spec").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(det)));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        // The AI disputes an id the engine never emitted — it must be ignored, never trusted.
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],\"designFindings\":[],"
                + "\"disputedFindings\":[{\"findingId\":\"NOT-A-REAL-ID\",\"reason\":\"trust me\"}]}");

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Finding>> findCap =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(scanPersistence).complete(scanCap.capture(), findCap.capture(), any());

        Finding fid = findCap.getValue().stream().filter(f -> "FID".equals(f.getFindingId())).findFirst().orElseThrow();
        assertThat(fid.isAiDisputed()).isFalse();                          // unknown id never honoured
        assertThat(scanCap.getValue().getFidelityScore()).isEqualTo(75);   // the BLOCKER still counts: 100 - 25
    }

    @Test
    void oversizedStructuredEvidence_surfacesATruncationBlindSpot() {
        when(promptComposer.contextBudgetChars()).thenReturn(10);   // any real CODE_API/SPEC_API JSON exceeds this
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        armLlm(reconcileReply());

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        verify(scanPersistence).complete(scanCap.capture(), any(), any());
        assertThat(scanCap.getValue().getBlindSpots()).contains("exceeded the model context budget");
    }

    @Test
    void specWideDesignFinding_contradictedByPresenceFacts_isSuppressed() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        // The resolved spec DOES have examples → a spec-wide "no examples" claim is provably false → suppressed.
        when(openApi.presenceOf(anyString())).thenReturn(new SpecPresence(true, false, false, false));

        String reply = "{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],"
                + "\"designFindings\":[{\"layer\":\"L5\",\"severity\":\"MINOR\",\"summary\":\"The spec has no examples anywhere\",\"explanation\":\"x\"}]}";
        armLlm(reply);

        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        // only the deterministic finding remains — the contradicted spec-wide design finding was dropped
        assertThat(r.totalFindings()).isEqualTo(1);
    }

    @Test
    void endpointScopedDesignFinding_isNotSuppressedByGlobalPresenceFacts() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        when(openApi.presenceOf(anyString())).thenReturn(new SpecPresence(true, false, false, false));

        // SAME "no examples" claim but scoped to a specific endpoint → global facts can't refute it → kept.
        String reply = "{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],"
                + "\"designFindings\":[{\"layer\":\"L5\",\"severity\":\"MINOR\",\"endpoint\":\"GET /x\",\"summary\":\"This endpoint has no examples\",\"explanation\":\"x\"}]}";
        armLlm(reply);

        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.totalFindings()).isEqualTo(2);   // deterministic + the kept endpoint-scoped design finding
    }

    @Test
    void crossListDedup_collapsesDeterministicAndLlmDuplicate_keepingDeterministicWithGraftedEnrichment() {
        // Two findings with the SAME dedup key (type+endpoint+spec+summary): one DETERMINISTIC, one LLM.
        Finding det = Finding.builder().findingId("d1").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("Same issue").build();
        Finding llmDup = Finding.builder().findingId("l1").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.MEDIUM).origin("LLM").endpoint("GET /x")
                .specSource("repo-spec").summary("Same issue").explanation("llm explains").proposedFix("llm fix").build();
        // LLM finding listed FIRST so the deterministic one arrives later and replaces it (merge path).
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(llmDup, det)));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));

        ValidationResult r = svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.totalFindings()).isEqualTo(1);   // collapsed to one
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Finding>> findCap =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(scanPersistence).complete(any(), findCap.capture(), any());
        Finding survivor = findCap.getValue().get(0);
        assertThat(survivor.getOrigin()).isEqualTo("DETERMINISTIC");        // deterministic kept
        assertThat(survivor.getExplanation()).isEqualTo("llm explains");    // LLM enrichment grafted on
        assertThat(survivor.getProposedFix()).isEqualTo("llm fix");
    }

    @Test
    void specVsSpecDrift_runsWhenTwoSpecsParse() {
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(List.of());
        when(diffEngine.diffSpecVsSpec(any(), any()))
                .thenReturn(new ArrayList<>(List.of(deterministic("drift between specs"))));
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        when(openApi.extract(eq("conf-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("conf-spec"), List.of(), true));

        ValidationResult r = svc.validate(req(false,
                new SpecInput("repo-spec", "a"), new SpecInput("conf-spec", "b")));

        assertThat(r.status()).isEqualTo("COMPLETED");
        verify(diffEngine, times(1)).diffSpecVsSpec(any(), any());   // exactly one pair (i<j)
        assertThat(r.totalFindings()).isEqualTo(1);
    }

    @Test
    void unparsedSpec_emitsL1MessagesButSkipsCodeVsSpecDiff() {
        when(diffEngine.l1FromMessages(eq("bad-spec"), any()))
                .thenReturn(new ArrayList<>(List.of(deterministic("parse error"))));
        // parsed=false → diffCodeVsSpec must NOT be called for this spec
        when(openApi.extract(eq("bad-spec"), anyString()))
                .thenReturn(new SpecParse(null, List.of("YAML broken at line 3"), false));

        ValidationResult r = svc.validate(req(false, new SpecInput("bad-spec", "::not yaml::")));

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.totalFindings()).isEqualTo(1);
        verify(diffEngine, never()).diffCodeVsSpec(any(), any());
    }

    @Test
    void coverageGaps_appendExtractorBlindSpotsToExistingScanBlindSpots() {
        // Code model reports a blind spot → coverageGaps>0 path; reconcile also reports blind spots → merge branch.
        when(javaExtractor.extract(any()))
                .thenReturn(codeModel(List.of("Unresolved DTO Foo"), endpoint("GET", "/x")));
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        armLlm(reconcileReply());   // contributes selfReview blindSpots first

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));
        verify(scanPersistence).complete(scanCap.capture(), any(), any());

        Scan persisted = scanCap.getValue();
        assertThat(persisted.getCoverageGaps()).isEqualTo(1);
        // existing (LLM) blind spots are kept AND the extractor gap is appended
        assertThat(persisted.getBlindSpots()).contains("unparsed file Foo.java").contains("Unresolved DTO Foo");
    }

    @Test
    void coverageGaps_setsBlindSpotsWhenNonePreexisting() {
        when(javaExtractor.extract(any()))
                .thenReturn(codeModel(List.of("Unresolved DTO Bar"), endpoint("GET", "/x")));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));   // no LLM → no preexisting blindSpots
        verify(scanPersistence).complete(scanCap.capture(), any(), any());

        Scan persisted = scanCap.getValue();
        assertThat(persisted.getCoverageGaps()).isEqualTo(1);
        assertThat(persisted.getBlindSpots()).isEqualTo("Unresolved DTO Bar");
    }

    @Test
    void reconcileThrows_degradesToDiffOnly_andScanCompletesWithANote() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        when(llm.isAvailable()).thenReturn(true);
        // The Copilot streaming call drops mid-response (the live EOF) — reconcile throws.
        when(llm.complete(any(), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException(
                        "I/O error on POST: EOF reached while reading"));

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        // The scan degrades to the deterministic diff-only report and COMPLETES — it does NOT fail on the blip.
        assertThat(r.status()).isEqualTo("COMPLETED");
        verify(scanPersistence).complete(scanCap.capture(), any(), any());
        assertThat(scanCap.getValue().getBlindSpots()).contains("AI review could not run");
        verify(llm, times(3)).complete(any(), any());   // retried the whole reconcile 3× before degrading
    }

    @Test
    void reconcileRetriesAfterATransientDrop_thenSucceeds_noDegrade() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        armLlm(reconcileReply());   // success-path stubs (jsonExtractor, costRecorder…)
        // The first Copilot call drops mid-stream; the retry succeeds.
        when(llm.complete(any(), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("EOF reached while reading"))
                .thenReturn(reconcileReply());
        when(openApi.extract(eq("corrected-check"), eq("openapi: 3.0.3")))
                .thenReturn(new SpecParse(specModel("corrected-check"), List.of(), true));

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.status()).isEqualTo("COMPLETED");
        verify(llm, atLeast(2)).complete(any(), any());   // retried after the first drop
        verify(scanPersistence).complete(scanCap.capture(), any(), any());
        // Succeeded on the retry → no degrade note.
        String blind = scanCap.getValue().getBlindSpots();
        assertThat(blind == null ? "" : blind).doesNotContain("AI review could not run");
    }

    @Test
    void llmCorrectedYaml_usedWhenItRoundTrips_inPreferenceToDeterministic() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        armLlm(reconcileReply());   // correctedYaml = "openapi: 3.0.3"
        // The LLM yaml round-trips; deterministic builder must NOT be consulted.
        when(openApi.extract(eq("corrected-check"), eq("openapi: 3.0.3")))
                .thenReturn(new SpecParse(specModel("corrected-check"), List.of(), true));

        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.correctedYamlPath()).isNotNull();
        verify(correctedSpecBuilder, never()).build(any(), any(), any());   // LLM yaml won → deterministic skipped
        // ...but the LLM yaml is still passed through metadata preservation with the original spec, so its invented
        // info/servers get replaced by the real ones (the honest-drop-in fix).
        verify(correctedSpecBuilder).withOriginalMetadata(eq("openapi: 3.0.3"), eq("spec-yaml"));
    }

    @Test
    void chooseCorrectedYaml_referencesTheDocumentedErrorSchema_endToEndThroughARealBuilder() {
        // Regression guard for the mocked-builder blind spot: every OTHER test here mocks CorrectedSpecBuilder, so the
        // REAL build→metadata→enrich→sanitise composite is never exercised — which is exactly how three "fixed" builds
        // shipped the same error-model nit. Drive chooseCorrectedYaml with a NON-mocked builder + extractor on the
        // shapes the live ciam-policies report showed: the corrected routes carry a /ciam prefix and a renamed path var
        // (so an exact path match can't fire) and problem+json generic error bodies for 400/404/406/500; the original
        // documents error-model for 400/404 only, via a shared components/responses wrapper under application/json.
        ContractValidationService real = new ContractValidationService(javaExtractor, new OpenApiModelExtractor(),
                new CorrectedSpecBuilder(), diffEngine, llm, jsonExtractor, schemaValidator, modelSelector,
                costRecorder, promptComposer, reportRenderer, scanRepo, findingRepo, mapper, preflight,
                scanPersistence, translationService, callContext);
        String llmCorrected = """
                openapi: 3.0.3
                info: { title: CIAM Policies, version: 1.0.0 }
                paths:
                  /ciam/policies:
                    get:
                      responses:
                        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/policies' } } } }
                        '400': { description: Bad Request, content: { application/problem+json: { schema: { type: object } } } }
                        '404': { description: Not Found, content: { application/problem+json: { schema: { type: object } } } }
                        '406': { description: Response 406, content: { application/problem+json: { schema: { type: object } } } }
                        '500': { description: Internal Server Error, content: { application/problem+json: { schema: { type: object } } } }
                  /ciam/policies/{app}:
                    get:
                      responses:
                        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/policies' } } } }
                        '404': { description: Not Found, content: { application/problem+json: { schema: { type: object } } } }
                components:
                  schemas:
                    policies: { type: object, properties: { password: { type: string } } }
                """;
        String original = """
                openapi: 3.0.0
                info: { title: CIAM Policies, version: 1.0.5 }
                paths:
                  /policies:
                    get:
                      responses:
                        '400': { $ref: '#/components/responses/400' }
                        '404': { $ref: '#/components/responses/404' }
                  /policies/{appId}:
                    get:
                      responses:
                        '404': { $ref: '#/components/responses/404' }
                components:
                  responses:
                    '400':
                      description: Bad request
                      content:
                        application/json:
                          schema: { type: object, properties: { error: { $ref: '#/components/schemas/error-model' } } }
                    '404':
                      description: Not found
                      content:
                        application/json:
                          schema: { type: object, properties: { error: { $ref: '#/components/schemas/error-model' } } }
                  schemas:
                    policies: { type: object, properties: { password: { type: string } } }
                    error-model:
                      type: object
                      properties:
                        title: { type: string }
                        status: { type: integer }
                """;

        String out = real.chooseCorrectedYaml(llmCorrected, codeModel(List.of()), "CIAM Policies", original);

        assertThat(out).contains("#/components/schemas/error-model");   // the generic error bodies now reference it…
        assertThat(out).contains("error-model:");                       // …and error-model was carried into components
        assertThat(out).contains("application/problem+json");           // the code's media type survives the composite
    }

    @Test
    void llmCorrectedYamlFailsRoundTrip_fallsBackToDeterministicSpec() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        String reply = "{\"correctedYaml\":\"!!broken\",\"findings\":[],\"designFindings\":[]}";
        armLlm(reply);
        // LLM yaml does NOT round-trip; deterministic does.
        when(openApi.extract(eq("corrected-check"), eq("!!broken")))
                .thenReturn(new SpecParse(null, List.of("bad"), false));
        when(correctedSpecBuilder.build(any(), any(), any())).thenReturn("openapi: 3.0.3");
        when(openApi.extract(eq("corrected-check"), eq("openapi: 3.0.3")))
                .thenReturn(new SpecParse(specModel("corrected-check"), List.of(), true));

        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.correctedYamlPath()).isNotNull();
        verify(correctedSpecBuilder, times(1)).build(any(), any(), any());   // fell back to the deterministic build
    }

    @Test
    void noCorrectedYamlWritten_whenBothCandidatesFailRoundTrip() {
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        // No LLM (disabled). Deterministic builder output never round-trips → corrected stays null.
        when(correctedSpecBuilder.build(any(), any(), any())).thenReturn("garbage");
        when(openApi.extract(eq("corrected-check"), eq("garbage")))
                .thenReturn(new SpecParse(null, List.of("nope"), false));

        ValidationResult r = svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.correctedYamlPath()).isNull();   // nothing safe to write
    }

    @Test
    void pdfRenderFailure_isNonFatal_reportStillCompletes() {
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        when(reportRenderer.renderPdf(any(), any(), any())).thenThrow(new RuntimeException("no pdf engine"));

        ValidationResult r = svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.reportPath()).isNotNull();
        assertThat(r.reportPdfPath()).isNull();   // PDF skipped, HTML still produced
    }

    @Test
    void bilingualReport_invokesTranslationAndPersistsTranslationsJson() throws Exception {
        setField(svc, "bilingualReport", true);
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("a gap"))));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        when(translationService.toFrench(any(), eq("owner")))
                .thenReturn(Map.of("a gap", "une lacune"));

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        verify(translationService, times(1)).toFrench(any(), eq("owner"));
        verify(scanPersistence).complete(scanCap.capture(), any(), any());
        assertThat(scanCap.getValue().getTranslationsJson()).contains("une lacune");
    }

    @Test
    void previousFidelityScore_isCarriedFromTheMostRecentPriorScanOfSameService() {
        Scan prior = new Scan();
        prior.setServiceName("svc");
        prior.setFidelityScore(84);
        // a different id from the current scan so it isn't filtered out as self
        try {
            Field idField = ca.bnc.qe.veritas.persistence.AuditableEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(prior, "prior-id");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(scanRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of(prior));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));
        verify(scanPersistence).complete(scanCap.capture(), any(), any());

        assertThat(scanCap.getValue().getPreviousFidelityScore()).isEqualTo(84);
    }

    @Test
    void previousFidelityScore_matchesPriorScan_despiteWhitespaceAndCaseDifferenceInServiceName() {
        // Regression: the prior scan's service name differs from the current request's only by casing + surrounding
        // whitespace ("  SVC  " vs "svc"). A prior exact-equals match dropped the history here, resetting the Trend
        // line to "no earlier score" across builds. The normalized (trim + case-insensitive) match must still find it.
        Scan prior = new Scan();
        prior.setServiceName("  SVC  ");
        prior.setFidelityScore(84);
        try {
            Field idField = ca.bnc.qe.veritas.persistence.AuditableEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(prior, "prior-id");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(scanRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of(prior));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));   // req(...) serviceName == "svc"
        verify(scanPersistence).complete(scanCap.capture(), any(), any());

        assertThat(scanCap.getValue().getPreviousFidelityScore()).isEqualTo(84);
    }

    @Test
    void previousFidelityScore_picksMostRecentPriorDeterministically_regardlessOfListOrder() {
        // Two prior scans of the same service; the NEWEST by startedAt must win, not whichever the list returns
        // first (guards the deterministic max(startedAt, id) selection against startedAt-tie / ordering hazards).
        Scan older = priorScan("p-old", "svc", 70, "2026-07-05T10:00:00Z");
        Scan newer = priorScan("p-new", "svc", 84, "2026-07-05T11:00:00Z");
        // Returned oldest-first on purpose — the code must sort by startedAt, not trust list position.
        when(scanRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of(older, newer));
        stubCleanDiffAndSpec();

        org.mockito.ArgumentCaptor<Scan> cap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));
        verify(scanPersistence).complete(cap.capture(), any(), any());
        assertThat(cap.getValue().getPreviousFidelityScore()).isEqualTo(84);
    }

    @Test
    void previousFidelityScore_skipsPriorScansThatHaveNoScore() {
        // The most-recent prior has no fidelity score (e.g. an older/failed row): it must be skipped, and the trend
        // falls back to the most recent SCORED prior — never left null when a scored prior exists.
        Scan newerUnscored = priorScan("p-null", "svc", null, "2026-07-05T12:00:00Z");
        Scan olderScored = priorScan("p-84", "svc", 84, "2026-07-05T09:00:00Z");
        when(scanRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of(newerUnscored, olderScored));
        stubCleanDiffAndSpec();

        org.mockito.ArgumentCaptor<Scan> cap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));
        verify(scanPersistence).complete(cap.capture(), any(), any());
        assertThat(cap.getValue().getPreviousFidelityScore()).isEqualTo(84);
    }

    @Test
    void previousFidelityScore_isNullWhenNoPriorScanMatchesTheService() {
        // A prior scan exists but for a DIFFERENT service — it must not be borrowed. previousFidelityScore stays
        // null, and the report then renders the honest "no earlier score on record" note (never a false trend).
        Scan otherService = priorScan("p-other", "some-other-service", 99, "2026-07-05T10:00:00Z");
        when(scanRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of(otherService));
        stubCleanDiffAndSpec();

        org.mockito.ArgumentCaptor<Scan> cap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));
        verify(scanPersistence).complete(cap.capture(), any(), any());
        assertThat(cap.getValue().getPreviousFidelityScore()).isNull();
    }

    /** Build a prior Scan row with a fixed id (reflection), service name, optional score and startedAt. */
    private static Scan priorScan(String id, String serviceName, Integer fidelityScore, String startedAtIso) {
        Scan s = new Scan();
        s.setServiceName(serviceName);
        s.setFidelityScore(fidelityScore);
        if (startedAtIso != null) {
            s.setStartedAt(java.time.Instant.parse(startedAtIso));
        }
        try {
            Field idField = ca.bnc.qe.veritas.persistence.AuditableEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(s, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    /** Empty diff + a valid parsed spec so validate() reaches the trend lookup with no findings. */
    private void stubCleanDiffAndSpec() {
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
    }

    @Test
    void largeFindingSet_isBatchedAcrossMultipleLlmCalls_andBlindSpotNotesTheBatching() throws Exception {
        // Tiny token budget so each finding lands in its own batch → multiple reconcile calls.
        setField(svc, "batchInputTokens", 1);
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            many.add(Finding.builder().findingId("f" + i).type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                    .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                    .endpoint("GET /x" + i).specSource("repo-spec").summary("finding number " + i).build());
        }
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(many));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        // corrected YAML + design findings come from the first batch only; later batches just enrich.
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],\"designFindings\":[]}");

        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        verify(llm, times(3)).complete(anyString(), anyString());   // one call per batch
        verify(scanPersistence).complete(scanCap.capture(), any(), any());
        // the multi-batch note is appended to the scan blind spots
        assertThat(scanCap.getValue().getBlindSpots()).contains("3 batches").contains("3 findings");
    }

    @Test
    void reconcileStopsEarly_whenScanFinalizedExternallyMidReconcile() throws Exception {
        setField(svc, "batchInputTokens", 1);   // force per-finding batches so there are >1 detail() writes
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            many.add(Finding.builder().findingId("f" + i).type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                    .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                    .endpoint("GET /x" + i).specSource("repo-spec").summary("finding " + i).build());
        }
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(many));
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],\"designFindings\":[]}");

        // Make the per-batch detail() save throw an optimistic-lock conflict on the SECOND save inside reconcile,
        // so the loop breaks before the second LLM call. The "Reviewing N findings…" detail is the first reconcile
        // save; the per-batch "Reviewing findings — batch 1…" is the second.
        AtomicInteger saves = new AtomicInteger();
        when(scanRepo.save(any(Scan.class))).thenAnswer(inv -> {
            // count only the saves that carry a stageDetail containing the per-batch wording
            Scan s = inv.getArgument(0);
            String d = s.getStageDetail();
            if (d != null && (d.contains("AI is writing") || d.contains("batch 1 of"))
                    && saves.incrementAndGet() == 1) {
                throw new org.springframework.dao.OptimisticLockingFailureException("finalized under us");
            }
            return s;
        });

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        // Broke before any LLM call (the first per-batch detail() was rejected) → no complete() calls.
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void unknownDesignSeverity_defaultsToMajor() {
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        // severity "WAT" is invalid → INFO (never MAJOR); and "GET /y" is an HTTP-shaped endpoint the code never
        // parsed (code has GET /x) → capped to INFO AND flagged as an unverified endpoint. Both guards converge: a
        // junk per-endpoint finding can't ship hot, and the fabricated HTTP locus is called out in the report.
        String reply = "{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],"
                + "\"designFindings\":[{\"layer\":\"L5\",\"severity\":\"WAT\",\"endpoint\":\"GET /y\",\"summary\":\"odd one\"}]}";
        armLlm(reply);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Finding>> findCap =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));
        verify(scanPersistence).complete(any(), findCap.capture(), any());

        Finding design = findCap.getValue().stream()
                .filter(f -> f.getType() == FindingType.DESIGN_QUALITY).findFirst().orElseThrow();
        assertThat(design.getSeverity()).isEqualTo(Severity.INFO);
        // An HTTP-shaped phantom endpoint ("GET /y") still keeps the unverified-endpoint fence in its explanation.
        assertThat(design.getExplanation()).contains("unverified endpoint");
    }

    @Test
    void designFindingWithDescriptivePseudoLocus_keepsCleanLabel_noUnverifiedFence() {
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        // The "endpoint" here is a descriptive pseudo-locus, not an HTTP endpoint. It is not a parsed endpoint (so it
        // is still capped to INFO as unverifiable) but it must NOT leak the "unverified endpoint" guard into the
        // customer report — a legitimate design/test-coverage label keeps its clean descriptive text and explanation.
        String reply = "{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],"
                + "\"designFindings\":[{\"layer\":\"L5\",\"severity\":\"MINOR\","
                + "\"endpoint\":\"policies schema (both endpoints)\",\"summary\":\"coverage gap\","
                + "\"explanation\":\"the schema lacks negative-case coverage\"}]}";
        armLlm(reply);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Finding>> findCap =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));
        verify(scanPersistence).complete(any(), findCap.capture(), any());

        Finding design = findCap.getValue().stream()
                .filter(f -> f.getType() == FindingType.DESIGN_QUALITY).findFirst().orElseThrow();
        // Still capped to INFO (unverifiable endpoint-scoped finding), but no bracketed guard leaked into the report.
        assertThat(design.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(design.getExplanation()).doesNotContain("unverified endpoint");
        assertThat(design.getExplanation()).isEqualTo("the schema lacks negative-case coverage");
        assertThat(design.getEndpoint()).isEqualTo("policies schema (both endpoints)");
    }

    @Test
    void extractorThrows_runIntoMarksScanFailedAndReturnsFailedResult() {
        when(javaExtractor.extract(any())).thenThrow(new RuntimeException("kaboom"));

        Scan scan = svc.createScanRow(req(false, new SpecInput("repo-spec", "spec-yaml")));
        ValidationResult r = svc.runInto(scan, req(false, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(scan.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(scan.getFailedStage()).isEqualTo(ScanStages.EXTRACTING);
        assertThat(scan.getErrorMessage()).contains("kaboom");
        verify(scanPersistence, never()).complete(any(), any(), any());   // never reached the atomic write
    }

    @Test
    void createScanRow_runsPreflightAndSetsRunningQueuedWithJoinedSpecSources() {
        Scan scan = svc.createScanRow(req(false,
                new SpecInput("repo-spec", "a"), new SpecInput("conf-spec", "b")));

        verify(preflight, times(1)).validateContract(any());
        assertThat(scan.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(scan.getStage()).isEqualTo(ScanStages.QUEUED);
        assertThat(scan.getServiceName()).isEqualTo("svc");
        assertThat(scan.getSpecSources()).isEqualTo("repo-spec,conf-spec");
        assertThat(scan.getStartedAt()).isNotNull();
    }

    @Test
    void bySeverity_groupsCountsAndOmitsZeroBuckets() {
        Finding major = deterministic("a major thing");
        Finding minor = Finding.builder().findingId("m1").type(FindingType.PARAM_EXTRA).layer(Layer.L4)
                .severity(Severity.MINOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /z")
                .specSource("repo-spec").summary("a minor thing").build();
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any()))
                .thenReturn(new ArrayList<>(List.of(major, minor)));
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));

        ValidationResult r = svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.bySeverity()).containsEntry("MAJOR", 1L).containsEntry("MINOR", 1L);
        assertThat(r.bySeverity()).doesNotContainKey("BLOCKER");   // empty buckets omitted
    }

    // ---- evidence-on-every-finding: closed-world binding in parseDesignFindings -----------------------------------

    /** Helper: run a reconcile with the given single designFinding JSON and a code model, return the L5/L6 design finding. */
    private Finding runDesignFindingWith(String designFindingJson, ApiModel code) {
        when(javaExtractor.extract(any())).thenReturn(code);
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],\"designFindings\":[" + designFindingJson + "]}");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Finding>> findCap =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));
        // The svc/mocks are shared across a test's calls, so capture ALL persist invocations and read the last one.
        verify(scanPersistence, org.mockito.Mockito.atLeastOnce()).complete(any(), findCap.capture(), any());
        List<Finding> latest = findCap.getAllValues().get(findCap.getAllValues().size() - 1);
        return latest.stream()
                .filter(f -> f.getType() == FindingType.DESIGN_QUALITY || f.getType() == FindingType.TEST_BASIS_GAP)
                .findFirst().orElseThrow();
    }

    @Test
    void designEvidencePointingAtAnExistingCodeField_bindsThatFieldsSourceAndSpecLocus() {
        // The code model has schema "Dto" with field "code" (SourceRef at Dto.java:42). The LLM evidence points at it.
        ApiModel code = codeModelWithSchema("Dto", "code", endpoint("GET", "/x"));
        Finding design = runDesignFindingWith(
                "{\"layer\":\"L6\",\"severity\":\"MINOR\",\"summary\":\"Dto.code has no negative-case coverage\","
                        + "\"evidence\":{\"codeSchema\":\"Dto\",\"codeField\":\"code\"}}", code);

        assertThat(design.getCodeEvidence()).isNotNull();
        assertThat(design.getCodeEvidence().location()).isEqualTo("src/main/java/ca/bnc/Dto.java");
        assertThat(design.getCodeEvidence().startLine()).isEqualTo(42);
        assertThat(design.getSpecLocus()).isEqualTo("Dto#code");
    }

    @Test
    void designEvidencePointingAtANonExistentSchemaOrField_bindsNothing_noFabrication() {
        ApiModel code = codeModelWithSchema("Dto", "code", endpoint("GET", "/x"));
        // Schema exists but field does not → no binding (never a wrong location).
        Finding wrongField = runDesignFindingWith(
                "{\"layer\":\"L5\",\"severity\":\"MINOR\",\"summary\":\"about a phantom field\","
                        + "\"evidence\":{\"codeSchema\":\"Dto\",\"codeField\":\"nope\"}}", code);
        assertThat(wrongField.getCodeEvidence()).isNull();
        assertThat(wrongField.getSpecLocus()).isNull();

        // Schema does not exist at all → no binding.
        Finding wrongSchema = runDesignFindingWith(
                "{\"layer\":\"L5\",\"severity\":\"MINOR\",\"summary\":\"about a phantom schema\","
                        + "\"evidence\":{\"codeSchema\":\"Ghost\",\"codeField\":\"code\"}}", code);
        assertThat(wrongSchema.getCodeEvidence()).isNull();
        assertThat(wrongSchema.getSpecLocus()).isNull();
    }

    @Test
    void noEvidencePointer_bindsSchemaSourceOnlyWhenEndpointExactlyNamesAKnownSchema() {
        ApiModel code = codeModelWithSchema("Dto", "code", endpoint("GET", "/x"));
        // No evidence, and the finding's endpoint value EXACTLY equals a known schema name → schema-level SourceRef binds.
        Finding onSchema = runDesignFindingWith(
                "{\"layer\":\"L5\",\"severity\":\"MINOR\",\"endpoint\":\"Dto\",\"summary\":\"the Dto schema is under-specified\"}",
                code);
        assertThat(onSchema.getCodeEvidence()).isNotNull();
        assertThat(onSchema.getCodeEvidence().location()).isEqualTo("src/main/java/ca/bnc/Dto.java");
        assertThat(onSchema.getCodeEvidence().startLine()).isEqualTo(10);
        assertThat(onSchema.getSpecLocus()).isEqualTo("Dto");

        // No evidence and the endpoint does NOT name a schema → stays null (a genuinely spec-wide finding).
        Finding specWide = runDesignFindingWith(
                "{\"layer\":\"L5\",\"severity\":\"MINOR\",\"summary\":\"the spec has no examples anywhere\"}", code);
        assertThat(specWide.getCodeEvidence()).isNull();
        assertThat(specWide.getSpecLocus()).isNull();
    }

    @Test
    void designEvidenceBinding_doesNotChangeTheFindingIdHash() {
        // The id is hash(type, summary, endpoint) — binding evidence must NOT alter it. Same summary+endpoint, one with
        // an evidence pointer that resolves, one without → identical findingId.
        ApiModel code = codeModelWithSchema("Dto", "code", endpoint("GET", "/x"));
        Finding bound = runDesignFindingWith(
                "{\"layer\":\"L6\",\"severity\":\"MINOR\",\"summary\":\"same summary\","
                        + "\"evidence\":{\"codeSchema\":\"Dto\",\"codeField\":\"code\"}}", code);
        Finding unbound = runDesignFindingWith(
                "{\"layer\":\"L6\",\"severity\":\"MINOR\",\"summary\":\"same summary\"}", code);

        assertThat(bound.getCodeEvidence()).isNotNull();           // one bound evidence
        assertThat(unbound.getCodeEvidence()).isNull();            // the other did not
        assertThat(bound.getFindingId()).isEqualTo(unbound.getFindingId());   // …yet the id is identical
    }
}
