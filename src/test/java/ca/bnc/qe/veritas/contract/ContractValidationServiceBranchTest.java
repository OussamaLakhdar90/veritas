package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
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
        // severity "WAT" is invalid → INFO (never MAJOR); and "GET /y" is not a parsed endpoint (code has GET /x) →
        // capped to INFO as an unverifiable endpoint-scoped finding. Both guards converge: a junk finding can't ship hot.
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
        assertThat(design.getExplanation()).contains("unverified endpoint");
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
}
