package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.cost.BillingMode;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
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
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.report.ContractReportRenderer;
import ca.bnc.qe.veritas.report.TranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContractValidationServiceBranch2Test {

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

        when(scanRepo.save(any(Scan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(scanRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of());
        when(javaExtractor.extract(any())).thenReturn(codeModel(List.of(), endpoint("GET", "/x")));
        when(reportRenderer.renderHtml(any(), any(), any())).thenReturn("<html>ok</html>");
        when(reportRenderer.renderPdf(any(), any(), any())).thenReturn(new byte[] {1, 2, 3});
        when(correctedSpecBuilder.build(any(), any(), any())).thenReturn("openapi: 3.0.3");
        // The LLM-preferred corrected YAML is passed through metadata preservation; passthrough by default here.
        when(correctedSpecBuilder.withOriginalMetadata(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(openApi.extract(eq("corrected-check"), anyString()))
                .thenReturn(new SpecParse(specModel("corrected-check"), List.of(), true));
        when(openApi.presenceOf(anyString())).thenReturn(SpecPresence.empty());
        when(modelSelector.resolveTier(any())).thenReturn("claude-test");
        when(modelSelector.promptTokenCap(anyString())).thenReturn(60000);
        when(promptComposer.data(anyString(), anyString())).thenReturn("");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn("PROMPT");

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

    private static ApiModel codeModelNullBlindSpots(Endpoint... eps) {
        return new ApiModel("code", "Svc", "1.0", null, List.of(eps), Map.of(), null);
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

    private static ValidationRequest reqWithService(String service, boolean llmEnabled, SpecInput... specs) {
        return new ValidationRequest(service, "APP", "repo", "main", Path.of("repo"),
                List.of(specs), llmEnabled, "owner", Thoroughness.STANDARD);
    }

    private static CostResult cost() {
        return new CostResult("claude-test", BillingMode.PER_REQUEST, 1.0, 100, 50, 0.02, false);
    }

    private void armLlm(String reply) {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(reply);
        when(jsonExtractor.extract(anyString())).thenReturn(reply);
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(cost());
    }

    private void armRepoSpecParses() {
        when(diffEngine.l1FromMessages(anyString(), any())).thenReturn(List.of());
        when(openApi.extract(eq("repo-spec"), anyString()))
                .thenReturn(new SpecParse(specModel("repo-spec"), List.of(), true));
    }

    @SuppressWarnings("unchecked")
    private List<Finding> capturePersistedFindings() {
        org.mockito.ArgumentCaptor<List<Finding>> findCap =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(scanPersistence).complete(any(), findCap.capture(), any());
        return findCap.getValue();
    }

    private Scan capturePersistedScan() {
        org.mockito.ArgumentCaptor<Scan> scanCap = org.mockito.ArgumentCaptor.forClass(Scan.class);
        verify(scanPersistence).complete(scanCap.capture(), any(), any());
        return scanCap.getValue();
    }

    @Test
    void nullExtractorBlindSpots_yieldZeroCoverageGaps_andNoBlindSpotAppend() {
        when(javaExtractor.extract(any())).thenReturn(codeModelNullBlindSpots(endpoint("GET", "/x")));
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        armRepoSpecParses();

        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));
        Scan persisted = capturePersistedScan();

        assertThat(persisted.getCoverageGaps()).isZero();
        assertThat(persisted.getBlindSpots()).isNull();
    }

    @Test
    void blankPreexistingBlindSpots_areReplacedByExtractorGaps_notConcatenated() {
        when(javaExtractor.extract(any()))
                .thenReturn(codeModel(List.of("Unresolved DTO Baz"), endpoint("GET", "/x")));
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        armRepoSpecParses();
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],\"designFindings\":[],"
                + "\"selfReview\":{\"blindSpots\":[\"\"]}}");

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));
        Scan persisted = capturePersistedScan();

        assertThat(persisted.getCoverageGaps()).isEqualTo(1);
        assertThat(persisted.getBlindSpots()).isEqualTo("Unresolved DTO Baz");
    }

    @Test
    void enrichmentNotGrafted_whenFindingAlreadyHasExplanationAndProposedFix() {
        Finding det = Finding.builder().findingId("FID").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("already enriched")
                .explanation("ORIGINAL expl").proposedFix("ORIGINAL fix").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(det)));
        armRepoSpecParses();
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\","
                + "\"findings\":[{\"findingId\":\"FID\",\"explanation\":\"LLM expl\",\"proposedFix\":\"LLM fix\"}],"
                + "\"designFindings\":[]}");

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        Finding fid = capturePersistedFindings().stream()
                .filter(f -> "FID".equals(f.getFindingId())).findFirst().orElseThrow();
        assertThat(fid.getExplanation()).isEqualTo("ORIGINAL expl");
        assertThat(fid.getProposedFix()).isEqualTo("ORIGINAL fix");
    }

    @Test
    void enrichmentPresentButMissingKeys_leavesFindingFieldsNull() {
        Finding det = Finding.builder().findingId("FID").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("bare").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(det)));
        armRepoSpecParses();
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\","
                + "\"findings\":[{\"findingId\":\"FID\"}],\"designFindings\":[]}");

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        Finding fid = capturePersistedFindings().stream()
                .filter(f -> "FID".equals(f.getFindingId())).findFirst().orElseThrow();
        assertThat(fid.getExplanation()).isNull();
        assertThat(fid.getProposedFix()).isNull();
    }

    @Test
    void nullServiceName_shortCircuitsPreviousFidelityLookup_noPreviousScoreCarried() {
        Scan prior = new Scan();
        prior.setServiceName("svc");
        prior.setFidelityScore(90);
        try {
            Field idField = ca.bnc.qe.veritas.persistence.AuditableEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(prior, "prior-id");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(scanRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of(prior));
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(List.of());
        armRepoSpecParses();

        svc.validate(reqWithService(null, false, new SpecInput("repo-spec", "spec-yaml")));
        Scan persisted = capturePersistedScan();

        assertThat(persisted.getPreviousFidelityScore()).isNull();
    }

    @Test
    void findingWithProposedFix_isAddedToTheBilingualTranslationSet() throws Exception {
        setField(svc, "bilingualReport", true);
        Finding det = Finding.builder().findingId("pf1").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("needs a fix").proposedFix("apply the fix").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(det)));
        armRepoSpecParses();
        when(translationService.toFrench(any(), eq("owner")))
                .thenReturn(Map.of("apply the fix", "appliquer le correctif"));

        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Set<String>> setCap =
                org.mockito.ArgumentCaptor.forClass((Class) java.util.Set.class);
        verify(translationService).toFrench(setCap.capture(), eq("owner"));
        assertThat(setCap.getValue()).contains("apply the fix");
    }

    @Test
    void reconcileReplyWithoutFindingsKey_andEntryWithoutFindingId_addsNoEnrichment() {
        Finding det = Finding.builder().findingId("FID").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("no enrichment available").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(det)));
        armRepoSpecParses();
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"designFindings\":[]}");

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        Finding fid = capturePersistedFindings().stream()
                .filter(f -> "FID".equals(f.getFindingId())).findFirst().orElseThrow();
        assertThat(fid.getExplanation()).isNull();
    }

    @Test
    void findingEntryMissingFindingId_isSkippedDuringEnrichment() {
        Finding det = Finding.builder().findingId("FID").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("entry has no id").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(det)));
        armRepoSpecParses();
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\","
                + "\"findings\":[{\"explanation\":\"orphan\",\"proposedFix\":\"orphan fix\"}],"
                + "\"designFindings\":[]}");

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        Finding fid = capturePersistedFindings().stream()
                .filter(f -> "FID".equals(f.getFindingId())).findFirst().orElseThrow();
        assertThat(fid.getExplanation()).isNull();
    }

    @Test
    void blankEndpointDesignFinding_isTreatedAsSpecWide_andSuppressedWhenContradicted() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        armRepoSpecParses();
        when(openApi.presenceOf(anyString())).thenReturn(new SpecPresence(true, false, false, false));
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],"
                + "\"designFindings\":[{\"layer\":\"L5\",\"severity\":\"MINOR\",\"endpoint\":\"\","
                + "\"summary\":\"The spec has no examples anywhere\",\"explanation\":\"x\"}]}");

        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.totalFindings()).isEqualTo(1);
    }

    @Test
    void duplicateDesignFindingId_isAddedOnlyOnce() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(deterministic("gap"))));
        armRepoSpecParses();
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],"
                + "\"designFindings\":["
                + "{\"layer\":\"L5\",\"severity\":\"MINOR\",\"summary\":\"identical weak design\",\"explanation\":\"a\"},"
                + "{\"layer\":\"L5\",\"severity\":\"MINOR\",\"summary\":\"identical weak design\",\"explanation\":\"b\"}"
                + "]}");

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        long designCount = capturePersistedFindings().stream()
                .filter(f -> f.getType() == FindingType.DESIGN_QUALITY).count();
        assertThat(designCount).isEqualTo(1);
    }

    @Test
    void twoBatches_firstCorrectedYamlWins_confidenceIsMin_andDuplicateBlindSpotDeduped() throws Exception {
        setField(svc, "batchInputTokens", 1);
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            many.add(Finding.builder().findingId("f" + i).type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                    .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                    .endpoint("GET /x" + i).specSource("repo-spec").summary("finding " + i).build());
        }
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(many));
        armRepoSpecParses();

        when(llm.isAvailable()).thenReturn(true);
        String batch1 = "{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],\"designFindings\":[],"
                + "\"selfReview\":{\"confidence\":80.0,\"blindSpots\":[\"shared gap\"]}}";
        String batch2 = "{\"correctedYaml\":\"SECOND-yaml\",\"findings\":[],\"designFindings\":[],"
                + "\"selfReview\":{\"confidence\":40.0,\"blindSpots\":[\"shared gap\"]}}";
        when(llm.complete(anyString(), anyString())).thenReturn(batch1, batch2);
        when(jsonExtractor.extract(batch1)).thenReturn(batch1);
        when(jsonExtractor.extract(batch2)).thenReturn(batch2);
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(cost());
        when(openApi.extract(eq("corrected-check"), eq("openapi: 3.0.3")))
                .thenReturn(new SpecParse(specModel("corrected-check"), List.of(), true));

        ValidationResult r = svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));
        Scan persisted = capturePersistedScan();

        verify(llm, times(2)).complete(anyString(), anyString());
        assertThat(persisted.getConfidence()).isEqualTo(40.0);
        String blind = persisted.getBlindSpots();
        assertThat(blind).contains("shared gap");
        assertThat(blind.indexOf("shared gap")).isEqualTo(blind.lastIndexOf("shared gap"));
        assertThat(r.correctedYamlPath()).isNotNull();
        verify(openApi, org.mockito.Mockito.never()).extract(eq("corrected-check"), eq("SECOND-yaml"));
    }

    @Test
    void rejectsCorrectedYamlThatDropsACodeEndpointAndFallsBackToDeterministic() throws Exception {
        // One deterministic finding so reconcile runs. Code declares GET /x (the @BeforeEach extractor).
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(
                Finding.builder().findingId("f0").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                        .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                        .endpoint("GET /x").specSource("repo-spec").summary("finding").build())));
        armRepoSpecParses();

        when(llm.isAvailable()).thenReturn(true);
        String reply = "{\"correctedYaml\":\"DROPPED-yaml\",\"findings\":[],\"designFindings\":[]}";
        when(llm.complete(anyString(), anyString())).thenReturn(reply);
        when(jsonExtractor.extract(reply)).thenReturn(reply);
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(cost());
        // The LLM's "corrected" spec re-parses but exposes only GET /z — it silently dropped the code's GET /x.
        when(openApi.extract(eq("corrected-check"), eq("DROPPED-yaml")))
                .thenReturn(new SpecParse(specModel("corrected-check", endpoint("GET", "/z")), List.of(), true));

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));

        // A parse-only gate would have shipped the dropping spec; the endpoint-preservation check rejects it and the
        // deterministic code-wins builder is used instead.
        verify(correctedSpecBuilder).build(any(), any(), any());
    }

    @Test
    void zeroBudget_neverBatches_singleLlmCallForManyFindings() throws Exception {
        setField(svc, "batchInputTokens", 0);
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            many.add(Finding.builder().findingId("g" + i).type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                    .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                    .endpoint("GET /g" + i).specSource("repo-spec").summary("g " + i).build());
        }
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(many));
        armRepoSpecParses();
        armLlm("{\"correctedYaml\":\"openapi: 3.0.3\",\"findings\":[],\"designFindings\":[]}");

        svc.validate(req(true, new SpecInput("repo-spec", "spec-yaml")));
        Scan persisted = capturePersistedScan();

        verify(llm, times(1)).complete(anyString(), anyString());
        assertThat(persisted.getBlindSpots()).isNull();
    }

    @Test
    void roundTripsSwallowsParseException_noCorrectedYamlWritten() {
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(List.of());
        armRepoSpecParses();
        when(correctedSpecBuilder.build(any(), any(), any())).thenReturn("boom-yaml");
        when(openApi.extract(eq("corrected-check"), eq("boom-yaml")))
                .thenThrow(new RuntimeException("parser blew up"));

        ValidationResult r = svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.correctedYamlPath()).isNull();
    }

    @Test
    void findingWithExistingYamlFragment_isLeftUntouchedByEnrichment() {
        Finding withFrag = Finding.builder().findingId("frag1").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("already has a fragment")
                .currentYamlFragment("PRE-EXISTING fragment").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(withFrag)));
        armRepoSpecParses();

        svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        Finding f = capturePersistedFindings().stream()
                .filter(x -> "frag1".equals(x.getFindingId())).findFirst().orElseThrow();
        assertThat(f.getCurrentYamlFragment()).isEqualTo("PRE-EXISTING fragment");
    }

    @Test
    void crossListDedup_keepsFirstDeterministic_whenLaterLlmDuplicateArrives() {
        Finding det = Finding.builder().findingId("d1").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("Same issue").build();
        Finding llmDup = Finding.builder().findingId("l1").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.MEDIUM).origin("LLM").endpoint("GET /x")
                .specSource("repo-spec").summary("Same issue").explanation("llm explains").proposedFix("llm fix").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(det, llmDup)));
        armRepoSpecParses();

        ValidationResult r = svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.totalFindings()).isEqualTo(1);
        Finding survivor = capturePersistedFindings().get(0);
        assertThat(survivor.getOrigin()).isEqualTo("DETERMINISTIC");
        assertThat(survivor.getFindingId()).isEqualTo("d1");
        assertThat(survivor.getExplanation()).isNull();
        assertThat(survivor.getProposedFix()).isNull();
    }

    @Test
    void mergeEnrichment_doesNotOverwriteFieldsTheDeterministicSurvivorAlreadyHas() {
        Finding llmFirst = Finding.builder().findingId("l1").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.MEDIUM).origin("LLM").endpoint("GET /x")
                .specSource("repo-spec").summary("Same issue").explanation("LLM expl").proposedFix("LLM fix").build();
        Finding detEnriched = Finding.builder().findingId("d1").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint("GET /x")
                .specSource("repo-spec").summary("Same issue")
                .explanation("DET expl").proposedFix("DET fix").build();
        when(diffEngine.diffCodeVsSpec(any(), any())).thenReturn(new ArrayList<>(List.of(llmFirst, detEnriched)));
        armRepoSpecParses();

        ValidationResult r = svc.validate(req(false, new SpecInput("repo-spec", "spec-yaml")));

        assertThat(r.totalFindings()).isEqualTo(1);
        Finding survivor = capturePersistedFindings().get(0);
        assertThat(survivor.getOrigin()).isEqualTo("DETERMINISTIC");
        assertThat(survivor.getExplanation()).isEqualTo("DET expl");
        assertThat(survivor.getProposedFix()).isEqualTo("DET fix");
    }
}