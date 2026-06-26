package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.skill.GateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure-Mockito branch coverage for {@link CodegenService}. Constructs the service with mocked collaborators
 * (real {@link ObjectMapper} so JSON parsing is exercised), driving every decision branch in generate /
 * generateData / .http / suite-xml / repair and in publish (template resolution + build-fail publish guard).
 *
 * <p>Complements the {@code @SpringBootTest} sibling tests; this class never touches production code or other
 * test files and asserts on real values (mutation-test ready).
 */
class CodegenServiceBranchTest {

    private LlmGateway llm;
    private JsonBlockExtractor jsonExtractor;
    private ResponseSchemaValidator schemaValidator;
    private ModelSelector modelSelector;
    private CostRecorder costRecorder;
    private PromptComposer promptComposer;
    private ObjectMapper objectMapper;
    private TemplateLearner templateLearner;
    private JavaSpringExtractor javaSpringExtractor;
    private CodegenRunRepository repository;
    private BuildVerifier buildVerifier;
    private Preflight preflight;
    private PrPublisher prPublisher;
    private GateService gateService;
    private GeneratedFileWriter generatedFileWriter;
    private HttpRequestsEmitter httpRequestsEmitter;
    private SuiteXmlEmitter suiteXmlEmitter;

    private CodegenService service;

    @TempDir
    Path serviceRepo;
    @TempDir
    Path outputDir;

    /** A realistic 1-endpoint AST model so the .http emitter has something to emit. */
    private ApiModel oneEndpoint() {
        Endpoint e = new Endpoint(HttpMethod.GET, "/policies/{id}", "getPolicy",
                List.of(), null, List.of(), List.of(), List.of(), List.of(), null);
        return new ApiModel("code", "policies", "1.0", null, List.of(e), Map.of());
    }

    private TemplateSpec specWith(String verifyCommand) {
        return new TemplateSpec("demo-framework", "java", "maven", verifyCommand,
                "com.acme", Map.of(), "TEMPLATE-BODY");
    }

    @BeforeEach
    void setUp() {
        llm = mock(LlmGateway.class);
        jsonExtractor = mock(JsonBlockExtractor.class);
        schemaValidator = mock(ResponseSchemaValidator.class);
        modelSelector = mock(ModelSelector.class);
        costRecorder = mock(CostRecorder.class);
        promptComposer = mock(PromptComposer.class);
        objectMapper = new ObjectMapper();   // real — exercises readTree / writeValueAsString
        templateLearner = mock(TemplateLearner.class);
        javaSpringExtractor = mock(JavaSpringExtractor.class);
        repository = mock(CodegenRunRepository.class);
        buildVerifier = mock(BuildVerifier.class);
        preflight = mock(Preflight.class);
        prPublisher = mock(PrPublisher.class);
        gateService = mock(GateService.class);
        generatedFileWriter = mock(GeneratedFileWriter.class);
        httpRequestsEmitter = mock(HttpRequestsEmitter.class);
        suiteXmlEmitter = mock(SuiteXmlEmitter.class);

        service = new CodegenService(llm, jsonExtractor, schemaValidator, modelSelector, costRecorder,
                promptComposer, objectMapper, templateLearner, javaSpringExtractor, repository, buildVerifier,
                preflight, prPublisher, gateService, generatedFileWriter, httpRequestsEmitter, suiteXmlEmitter);

        // Reasonable shared defaults; individual tests tighten what they assert on.
        lenient().when(llm.isAvailable()).thenReturn(true);
        lenient().when(promptComposer.data(anyString(), anyString())).thenReturn("DATA");
        lenient().when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("PROMPT");
        lenient().when(modelSelector.resolveTier(any(ModelTier.class))).thenReturn("model-x");
        lenient().when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CostResult("model-x", null, 0, 10, 5, 0.42, false));
        lenient().when(javaSpringExtractor.extract(any())).thenReturn(oneEndpoint());
        lenient().when(httpRequestsEmitter.emit(anyString(), any())).thenReturn("### http\nGET {{baseUrl}}/x\n");
        lenient().when(suiteXmlEmitter.emit(anyString(), any()))
                .thenReturn(Map.of("svc-smoke.xml", "<suite/>"));
        // repository.save echoes its argument back (so the returned run is the one we set fields on).
        lenient().when(repository.save(any(CodegenRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * Wire the two LLM calls (generate-data then implement) to return the given fenced-JSON payloads.
     * The service calls {@code jsonExtractor.extract} once per LLM call, then {@code objectMapper.readTree}.
     */
    private void stubTwoLlmReplies(String dataJson, String implJson) {
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        when(jsonExtractor.extract("raw")).thenReturn(dataJson, implJson);
    }

    private TemplateSpec stubTemplate(String verifyCommand) {
        TemplateSpec spec = specWith(verifyCommand);
        when(templateLearner.learn(any())).thenReturn(spec);
        return spec;
    }

    // ---------------------------------------------------------------------------------------------------
    // generate(...) — happy path, SKIPPED build, explicit template path
    // ---------------------------------------------------------------------------------------------------

    @Test
    void generateWritesAllArtifactsAndRecordsRunOnSkippedBuild() throws Exception {
        Path template = Files.createTempFile("tmpl", ".md");   // explicit, non-null → resolveTemplate returns it
        stubTemplate("");   // empty verify command path goes through BuildVerifier mock
        String dataJson = "{\"files\":[{\"path\":\"src/test/resources/serverConfig.json\",\"content\":\"{}\"}],"
                + "\"todos\":[\"Provision a seed policy id\"]}";
        String implJson = "{\"files\":[{\"path\":\"src/test/java/GeneratedApiTest.java\",\"content\":\"class X{}\"}],"
                + "\"todos\":[\"Set the base URL\"]}";
        stubTwoLlmReplies(dataJson, implJson);
        when(buildVerifier.verify(eq(outputDir), eq(""))).thenReturn(new BuildVerifier.BuildResult("SKIPPED", ""));

        CodegenRun run = service.generate("ciam-policies", serviceRepo, template, outputDir, "tester");

        // Build status threaded through unchanged; no repair on a non-FAIL build.
        assertThat(run.getBuildStatus()).isEqualTo("SKIPPED");
        assertThat(run.getServiceName()).isEqualTo("ciam-policies");
        assertThat(run.getOutputRepo()).isEqualTo(outputDir.toString());
        assertThat(run.getTemplateSource()).isEqualTo(template.toString());
        assertThat(run.getApprovedBy()).isEqualTo("tester");
        assertThat(run.getEstCostUsd()).isEqualTo(0.42);
        // TODOs from BOTH llm steps merged (data first, then impl).
        assertThat(run.getTodos()).contains("Provision a seed policy id").contains("Set the base URL");

        // filesWritten JSON contains: 1 data file + 1 impl file + 1 .http + 1 suite under suites/.
        List<String> written = objectMapper.readValue(run.getFilesWritten(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(written).containsExactly(
                "src/test/resources/serverConfig.json",
                "src/test/java/GeneratedApiTest.java",
                "ciam-policies.http",
                "suites/svc-smoke.xml");

        // No repair pass on a SKIPPED build → exactly two LLM completions (data + impl).
        verify(llm, times(2)).complete(anyString(), anyString());
        verify(buildVerifier, times(1)).verify(any(), any());
        // The .http file name is the sanitized service name; emitter consulted with the AST model.
        verify(httpRequestsEmitter).emit(eq("ciam-policies"), any(ApiModel.class));
        verify(suiteXmlEmitter).emit(eq("ciam-policies"), any());
        // generate-data validated against the shared schema; implement step validated too → two validations.
        verify(schemaValidator, times(2)).validate(any(JsonNode.class), eq("implement-tests.schema.json"));
        verify(repository).save(any(CodegenRun.class));
        // Cost recorded for both data + generate actions.
        verify(costRecorder).record(eq("implement-tests"), eq("generate-data"), anyString(), anyString(),
                anyString(), eq("tester"));
        verify(costRecorder).record(eq("implement-tests"), eq("generate"), anyString(), anyString(),
                anyString(), eq("tester"));
    }

    @Test
    void serviceNameWithIllegalCharsIsSanitizedForHttpFileName() throws Exception {
        stubTemplate("");
        stubTwoLlmReplies("{\"files\":[],\"todos\":[]}", "{\"files\":[],\"todos\":[]}");
        when(buildVerifier.verify(any(), any())).thenReturn(new BuildVerifier.BuildResult("SKIPPED", ""));

        CodegenRun run = service.generate("ciam/policies v2!", serviceRepo,
                Files.createTempFile("t", ".md"), outputDir, "tester");

        List<String> written = objectMapper.readValue(run.getFilesWritten(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        // Each illegal char becomes a single dash; emitter still called with the ORIGINAL service name.
        assertThat(written).contains("ciam-policies-v2-.http");
        verify(httpRequestsEmitter).emit(eq("ciam/policies v2!"), any());
    }

    // ---------------------------------------------------------------------------------------------------
    // resolveTemplate(null) — bundled default template branch
    // ---------------------------------------------------------------------------------------------------

    @Test
    void nullTemplatePathResolvesToBundledDefaultTemplate() throws Exception {
        // templatePath == null → service loads the bundled classpath resource into a temp file and learns it.
        stubTemplate("");
        stubTwoLlmReplies("{\"files\":[],\"todos\":[]}", "{\"files\":[],\"todos\":[]}");
        when(buildVerifier.verify(any(), any())).thenReturn(new BuildVerifier.BuildResult("SKIPPED", ""));

        CodegenRun run = service.generate("svc", serviceRepo, null, outputDir, "tester");

        // The resolved template is a real temp file path ending in .md (the copied bundled resource).
        assertThat(run.getTemplateSource()).endsWith(".md");
        assertThat(Files.exists(Path.of(run.getTemplateSource()))).isTrue();
        // Preflight saw the resolved (non-null) template, not null.
        verify(preflight).implementTests(eq("svc"), eq(serviceRepo), any(), eq(outputDir));
        verify(preflight).requireLlm(eq(llm), eq("implement-tests"));
    }

    // ---------------------------------------------------------------------------------------------------
    // build FAIL → repair pass branches
    // ---------------------------------------------------------------------------------------------------

    @Test
    void buildFailThenRepairSucceedsYieldsRepairedStatus() throws Exception {
        stubTemplate("verify-cmd");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        // data, impl, then the repair reply (third extract).
        when(jsonExtractor.extract("raw")).thenReturn(
                "{\"files\":[],\"todos\":[]}",
                "{\"files\":[{\"path\":\"src/test/java/A.java\",\"content\":\"class A{}\"}],\"todos\":[]}",
                "{\"files\":[{\"path\":\"src/test/java/A.java\",\"content\":\"class A{ /*fixed*/ }\"}]}");
        // First verify FAIL (triggers repair), second verify PASS (repair → REPAIRED).
        when(buildVerifier.verify(eq(outputDir), eq("verify-cmd")))
                .thenReturn(new BuildVerifier.BuildResult("FAIL", "cannot find symbol"))
                .thenReturn(new BuildVerifier.BuildResult("PASS", "ok"));

        CodegenRun run = service.generate("svc", serviceRepo, Files.createTempFile("t", ".md"),
                outputDir, "tester");

        assertThat(run.getBuildStatus()).isEqualTo("REPAIRED");
        // Three LLM calls total: data + implement + the single bounded repair pass.
        verify(llm, times(3)).complete(anyString(), anyString());
        verify(buildVerifier, times(2)).verify(eq(outputDir), eq("verify-cmd"));
        // Repair cost billed under the "repair" action.
        verify(costRecorder).record(eq("implement-tests"), eq("repair"), anyString(), anyString(),
                anyString(), eq("tester"));
        // Repair re-wrote the file under the same rel path (already present in `written`, so no duplicate add).
        List<String> written = objectMapper.readValue(run.getFilesWritten(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(written).filteredOn(p -> p.equals("src/test/java/A.java")).hasSize(1);
    }

    @Test
    void buildFailThenRepairStillFailsKeepsFailStatusAndAddsNewRepairedFile() throws Exception {
        stubTemplate("verify-cmd");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        when(jsonExtractor.extract("raw")).thenReturn(
                "{\"files\":[],\"todos\":[]}",
                "{\"files\":[{\"path\":\"src/test/java/A.java\",\"content\":\"class A{}\"}],\"todos\":[]}",
                // repair introduces a NEW path → exercises the `!written.contains(rel)` true branch.
                "{\"files\":[{\"path\":\"src/test/java/RepairedApiTest.java\",\"content\":\"class R{}\"}]}");
        when(buildVerifier.verify(eq(outputDir), eq("verify-cmd")))
                .thenReturn(new BuildVerifier.BuildResult("FAIL", "errs"))
                .thenReturn(new BuildVerifier.BuildResult("FAIL", "still broken"));

        CodegenRun run = service.generate("svc", serviceRepo, Files.createTempFile("t", ".md"),
                outputDir, "tester");

        // Repair ran but couldn't fix it → status stays FAIL (not REPAIRED).
        assertThat(run.getBuildStatus()).isEqualTo("FAIL");
        List<String> written = objectMapper.readValue(run.getFilesWritten(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(written).contains("src/test/java/RepairedApiTest.java");
    }

    @Test
    void repairPassSwallowsExceptionAndKeepsOriginalFail() throws Exception {
        stubTemplate("verify-cmd");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        // First two extracts (data, impl) succeed; the THIRD (repair) throws → catch path returns FAIL(errors).
        when(jsonExtractor.extract("raw")).thenReturn(
                "{\"files\":[],\"todos\":[]}",
                "{\"files\":[{\"path\":\"src/test/java/A.java\",\"content\":\"class A{}\"}],\"todos\":[]}")
                .thenThrow(new IllegalStateException("boom in repair"));
        when(buildVerifier.verify(eq(outputDir), eq("verify-cmd")))
                .thenReturn(new BuildVerifier.BuildResult("FAIL", "the original errors"));

        CodegenRun run = service.generate("svc", serviceRepo, Files.createTempFile("t", ".md"),
                outputDir, "tester");

        assertThat(run.getBuildStatus()).isEqualTo("FAIL");
        // Repair attempted the 3rd LLM call, then bailed → only ONE verify (the failing one); no second verify.
        verify(llm, times(3)).complete(anyString(), anyString());
        verify(buildVerifier, times(1)).verify(eq(outputDir), eq("verify-cmd"));
    }

    // ---------------------------------------------------------------------------------------------------
    // error propagation: RuntimeException rethrown as-is; checked Exception wrapped.
    // ---------------------------------------------------------------------------------------------------

    @Test
    void runtimeExceptionFromCollaboratorIsRethrownUnwrapped() throws Exception {
        stubTemplate("");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        when(jsonExtractor.extract("raw")).thenReturn("{\"files\":[],\"todos\":[]}");
        // schemaValidator throws on the first (generate-data) validation — an unchecked exception.
        IllegalStateException boom = new IllegalStateException("schema mismatch");
        org.mockito.Mockito.doThrow(boom).when(schemaValidator).validate(any(), anyString());

        assertThatThrownBy(() -> service.generate("svc", serviceRepo, Files.createTempFile("t", ".md"),
                outputDir, "tester"))
                .isSameAs(boom);   // rethrown unwrapped (the `catch (RuntimeException e) { throw e; }` branch)
        verify(repository, never()).save(any(CodegenRun.class));
    }

    @Test
    void checkedExceptionFromCollaboratorIsWrappedInIllegalState() throws Exception {
        stubTemplate("");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        when(jsonExtractor.extract("raw")).thenReturn(
                "{\"files\":[],\"todos\":[]}",
                "{\"files\":[{\"path\":\"a.json\",\"content\":\"{}\"}],\"todos\":[]}");
        // GeneratedFileWriter.write declares IOException (checked) → service wraps it.
        when(buildVerifier.verify(any(), any())).thenReturn(new BuildVerifier.BuildResult("SKIPPED", ""));
        org.mockito.Mockito.doThrow(new java.io.IOException("disk full"))
                .when(generatedFileWriter).write(any(), eq("a.json"), anyString());

        assertThatThrownBy(() -> service.generate("svc", serviceRepo, Files.createTempFile("t", ".md"),
                outputDir, "tester"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("implement-tests failed")
                .hasMessageContaining("disk full")
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    // ---------------------------------------------------------------------------------------------------
    // publish(...) — gate, target-branch defaulting, build-fail guard, unknown run
    // ---------------------------------------------------------------------------------------------------

    private CodegenRun savedRun(String id, String buildStatus) {
        CodegenRun run = new CodegenRun();
        run.setServiceName("ciam policies");
        run.setOutputRepo(outputDir.toString());
        run.setBuildStatus(buildStatus);
        when(repository.findById(id)).thenReturn(Optional.of(run));
        return run;
    }

    @Test
    void publishUnknownRunIdThrows() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.publish("nope", "slug", "main", "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown codegen run nope");
    }

    @Test
    void publishApprovedGateOpensPrAndDefaultsTargetBranchWhenBlank() {
        savedRun("r1", "PASS");
        when(gateService.await("r1", "OPEN_PR", "tester"))
                .thenReturn(new GateService.Decision(true, "g1", "APPROVED"));
        when(prPublisher.publish(any())).thenReturn(
                new PrPublisher.PrResult("veritas/ciam-policies-tests", "https://pr/1"));

        // Blank targetBranch → service must substitute "main"; service name sanitized in the branch name.
        CodegenRun out = service.publish("r1", "ciam-policies-tests", "   ", "tester");

        assertThat(out.getPrUrl()).isEqualTo("https://pr/1");
        assertThat(out.getBranch()).isEqualTo("veritas/ciam-policies-tests");
        verify(preflight).requireGitWriteScope();
        org.mockito.ArgumentCaptor<PrPublisher.PrRequest> cap =
                org.mockito.ArgumentCaptor.forClass(PrPublisher.PrRequest.class);
        verify(prPublisher).publish(cap.capture());
        PrPublisher.PrRequest req = cap.getValue();
        assertThat(req.targetBranch()).isEqualTo("main");                    // blank → "main"
        assertThat(req.branch()).isEqualTo("veritas/ciam-policies-tests");   // "ciam policies" sanitized
        assertThat(req.title()).isEqualTo("Veritas: generated tests for ciam policies");
        assertThat(req.description()).contains("build: PASS");
        verify(repository).save(any(CodegenRun.class));
    }

    @Test
    void publishHonoursExplicitTargetBranch() {
        savedRun("r2", "SKIPPED");
        when(gateService.await(anyString(), anyString(), anyString()))
                .thenReturn(new GateService.Decision(true, "g2", "APPROVED"));
        when(prPublisher.publish(any())).thenReturn(new PrPublisher.PrResult("b", "https://pr/2"));

        service.publish("r2", "slug", "develop", "tester");

        org.mockito.ArgumentCaptor<PrPublisher.PrRequest> cap =
                org.mockito.ArgumentCaptor.forClass(PrPublisher.PrRequest.class);
        verify(prPublisher).publish(cap.capture());
        assertThat(cap.getValue().targetBranch()).isEqualTo("develop");   // explicit, non-blank → kept
    }

    @Test
    void publishGateNotApprovedThrowsAndSkipsPreflightAndPr() {
        savedRun("r3", "PASS");
        when(gateService.await("r3", "OPEN_PR", "tester"))
                .thenReturn(new GateService.Decision(false, "g3", "PENDING"));

        assertThatThrownBy(() -> service.publish("r3", "slug", "main", "tester"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting approval")
                .hasMessageContaining("g3");
        verify(preflight, never()).requireGitWriteScope();
        verify(prPublisher, never()).publish(any());
    }

    @Test
    void publishRefusesFailingBuildWithoutOverride() {
        savedRun("r4", "FAIL");

        assertThatThrownBy(() -> service.publish("r4", "slug", "main", "tester"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("did not compile");
        // Guard short-circuits BEFORE the gate is even consulted.
        verify(gateService, never()).await(anyString(), anyString(), anyString());
        verify(prPublisher, never()).publish(any());
    }

    @Test
    void publishFailingBuildIsAllowedWithExplicitOverride() {
        savedRun("r5", "fail");   // lower-case → equalsIgnoreCase still trips the guard, override bypasses it
        when(gateService.await("r5", "OPEN_PR", "tester"))
                .thenReturn(new GateService.Decision(true, "g5", "APPROVED"));
        when(prPublisher.publish(any())).thenReturn(new PrPublisher.PrResult("b", "https://pr/override"));

        CodegenRun out = service.publish("r5", "slug", "main", "tester", true);

        assertThat(out.getPrUrl()).isEqualTo("https://pr/override");
        verify(prPublisher).publish(any());
        verify(preflight).requireGitWriteScope();
    }

    @Test
    void publishTwoArgOverloadDelegatesWithoutOverride() {
        // The 4-arg publish must forward allowFailedBuild=false → a FAIL run is refused.
        savedRun("r6", "FAIL");
        assertThatThrownBy(() -> service.publish("r6", "slug", "main", "tester"))
                .isInstanceOf(PreconditionException.class);
        verify(prPublisher, never()).publish(any());
    }
}