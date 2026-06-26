package ca.bnc.qe.veritas.codegen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Template-driven test generation: learn the user's template → analyze the service (AST) → LLM generates
 * files following the template → write to the output dir → record the run. Build-verify + PR are added on
 * top (live-validated); first cut writes files + records the run (status SKIPPED for build).
 */
@Service
@Slf4j
public class CodegenService {

    private final LlmGateway llm;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final ObjectMapper objectMapper;
    private final TemplateLearner templateLearner;
    private final JavaSpringExtractor javaSpringExtractor;
    private final CodegenRunRepository repository;
    private final BuildVerifier buildVerifier;
    private final ca.bnc.qe.veritas.preflight.Preflight preflight;
    private final PrPublisher prPublisher;
    private final ca.bnc.qe.veritas.skill.GateService gateService;
    private final GeneratedFileWriter generatedFileWriter;
    private final HttpRequestsEmitter httpRequestsEmitter;
    private final SuiteXmlEmitter suiteXmlEmitter;

    public CodegenService(LlmGateway llm, JsonBlockExtractor jsonExtractor, ResponseSchemaValidator schemaValidator,
                          ModelSelector modelSelector, CostRecorder costRecorder, PromptComposer promptComposer,
                          ObjectMapper objectMapper, TemplateLearner templateLearner,
                          JavaSpringExtractor javaSpringExtractor, CodegenRunRepository repository,
                          BuildVerifier buildVerifier, ca.bnc.qe.veritas.preflight.Preflight preflight,
                          PrPublisher prPublisher, ca.bnc.qe.veritas.skill.GateService gateService,
                          GeneratedFileWriter generatedFileWriter, HttpRequestsEmitter httpRequestsEmitter,
                          SuiteXmlEmitter suiteXmlEmitter) {
        this.llm = llm;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.objectMapper = objectMapper;
        this.templateLearner = templateLearner;
        this.javaSpringExtractor = javaSpringExtractor;
        this.repository = repository;
        this.buildVerifier = buildVerifier;
        this.preflight = preflight;
        this.prPublisher = prPublisher;
        this.gateService = gateService;
        this.generatedFileWriter = generatedFileWriter;
        this.httpRequestsEmitter = httpRequestsEmitter;
        this.suiteXmlEmitter = suiteXmlEmitter;
    }

    /**
     * Outward step of implement-tests: branch + commit + push the generated output repo and open a PR
     * (gated for human approval). Completes the Pillar-C flow; idempotent re-runs reuse the branch/PR.
     */
    /**
     * One bounded repair pass: ask the LLM to fix the non-compiling files given the build errors, rewrite them,
     * and re-verify. Returns REPAIRED if it now compiles, else the (still-FAIL) result. Never PR a FAIL branch
     * (the publish gate enforces that); REPAIRED is publishable. Non-fatal — any error keeps the original FAIL.
     */
    private BuildVerifier.BuildResult attemptRepair(Path outputDir, JsonNode originalFiles, String errors,
                                                    TemplateSpec spec, String owner, List<String> written) {
        try {
            String contract = "The generated tests did NOT compile. Fix them so they compile against the template. "
                    + "Reply with exactly one fenced ```json block: {\"files\":[{\"path\":string,\"content\":string}]}. "
                    + "Only the corrected files. No prose after.";
            String inputs = promptComposer.data("BUILD_ERRORS", errors)
                    + promptComposer.data("CURRENT_FILES", originalFiles.toString());
            String prompt = promptComposer.compose("[IMPLEMENT-TESTS-REPAIR]", "implement-api-tests.prompt.md",
                    Set.of("1", "12"), inputs, contract);
            String model = modelSelector.resolveTier(ModelTier.DEEP);
            String raw = llm.complete(prompt, model);
            costRecorder.record("implement-tests", "repair", model, prompt, raw, owner);
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            for (JsonNode f : node.path("files")) {
                String rel = f.path("path").asText();
                generatedFileWriter.writeWithin(outputDir, rel, f.path("content").asText(""));
                if (!written.contains(rel)) {
                    written.add(rel);
                }
            }
            BuildVerifier.BuildResult r2 = buildVerifier.verify(outputDir, spec.verifyCommand());
            return "PASS".equals(r2.status()) ? new BuildVerifier.BuildResult("REPAIRED", r2.output()) : r2;
        } catch (Exception e) {
            log.warn("Repair pass failed: {}", e.getMessage());
            return new BuildVerifier.BuildResult("FAIL", errors);
        }
    }

    /**
     * Distinct data-generation step (Pillar C, §4 step 4): emit the data artifacts in the TEMPLATE's own format
     * (e.g. serverConfig.json / data-manager.json / entity+response fixtures) BEFORE the tests, so the test code
     * can reference them. Secrets are emitted only as {@code $sensitive:ENV} refs (the GeneratedFileWriter rejects
     * literal secrets); IDs that must pre-exist come back as TODOs, never invented. Returns the parsed reply
     * (files + todos) so the caller writes the files and merges the TODOs with the implement step's.
     */
    private JsonNode generateData(TemplateSpec spec, List<String> endpoints, String owner) throws Exception {
        String contract = "Generate the test DATA artifacts in the TEMPLATE's exact format (framework: "
                + spec.frameworkName() + "). Secrets MUST be \"$sensitive:ENV_NAME\" references — never literal values. "
                + "Any record/ID that must already exist in the system goes in todos (do not invent it). "
                + "One fenced ```json block last: {\"files\":[{\"path\":string,\"content\":string}],\"todos\":[string]}. "
                + "Paths relative to the output repo. No prose after.";
        String inputs = promptComposer.data("TEMPLATE", spec.body())
                + promptComposer.data("ENDPOINTS", endpoints.toString());
        String prompt = promptComposer.compose("[GENERATE-DATA]", "generate-test-data.prompt.md",
                Set.of("1", "15"), inputs, contract);   // terminology + secrets-handling knowledge
        String model = modelSelector.resolveTier(ModelTier.STANDARD);   // data fixtures don't need the DEEP tier
        String raw = llm.complete(prompt, model);
        costRecorder.record("implement-tests", "generate-data", model, prompt, raw, owner);   // bill before parse
        JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
        schemaValidator.validate(node, "implement-tests.schema.json");   // same {files,todos} shape
        return node;
    }

    /** Default codegen template = the bundled BNC autotests template; copied to a temp file for TemplateLearner. */
    static final String DEFAULT_TEMPLATE_RESOURCE = "veritas/templates/autotests-template.md";

    private Path resolveTemplate(Path templatePath) {
        if (templatePath != null) {
            return templatePath;
        }
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(DEFAULT_TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled default template not found on classpath: " + DEFAULT_TEMPLATE_RESOURCE);
            }
            Path tmp = Files.createTempFile("veritas-autotests-template", ".md");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the bundled default template: " + e.getMessage(), e);
        }
    }

    public CodegenRun publish(String runId, String outputRepoSlug, String targetBranch, String owner) {
        return publish(runId, outputRepoSlug, targetBranch, owner, false);
    }

    public CodegenRun publish(String runId, String outputRepoSlug, String targetBranch, String owner,
                              boolean allowFailedBuild) {
        CodegenRun run = repository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown codegen run " + runId));
        // Blind spot #12: never PR a non-compiling branch unless the user explicitly overrides.
        if ("FAIL".equalsIgnoreCase(run.getBuildStatus()) && !allowFailedBuild) {
            throw new ca.bnc.qe.veritas.preflight.PreconditionException("implement-tests", java.util.List.of(
                    "Generated tests did not compile (build status FAIL). Refusing to open a PR for a non-compiling "
                            + "branch. Re-run so the repair pass can fix it, or publish with the explicit override."));
        }
        ca.bnc.qe.veritas.skill.GateService.Decision gate = gateService.await(runId, "OPEN_PR", owner);
        if (!gate.approved()) {
            throw new IllegalStateException("PR for codegen run " + runId
                    + " awaiting approval (gate " + gate.gateId() + ")");
        }
        preflight.requireGitWriteScope();   // fail fast on a missing/insufficient git token, not mid-push
        String branch = "veritas/" + run.getServiceName().replaceAll("[^A-Za-z0-9._-]", "-") + "-tests";
        String title = "Veritas: generated tests for " + run.getServiceName();
        String description = "Automated tests generated by Veritas (build: " + run.getBuildStatus()
                + "). Please review before merge.";
        PrPublisher.PrResult result = prPublisher.publish(new PrPublisher.PrRequest(
                Path.of(run.getOutputRepo()), outputRepoSlug, branch,
                targetBranch == null || targetBranch.isBlank() ? "main" : targetBranch,
                title, description, title));
        run.setBranch(result.branch());
        run.setPrUrl(result.prUrl());
        return repository.save(run);
    }

    public CodegenRun generate(String serviceName, Path serviceRepo, Path templatePath, Path outputDir, String owner) {
        Path effectiveTemplate = resolveTemplate(templatePath);   // null → bundled BNC autotests template
        preflight.implementTests(serviceName, serviceRepo, effectiveTemplate, outputDir);
        preflight.requireLlm(llm, "implement-tests");
        TemplateSpec spec = templateLearner.learn(effectiveTemplate);   // fails fast if template missing/invalid
        ApiModel code = javaSpringExtractor.extract(serviceRepo);
        List<String> endpoints = new ArrayList<>();
        code.endpoints().forEach(e -> endpoints.add(e.signature()));

        try {
            // Step 4 (L): generate the data artifacts first, in the template's format, so tests can reference them.
            JsonNode dataNode = generateData(spec, endpoints, owner);

            String outputContract = "Generate automated tests that EXACTLY follow the template (framework: "
                    + spec.frameworkName() + ", language: " + spec.language() + "). Mirror its conventions; "
                    + "introduce no pattern absent from it. One fenced ```json block last: "
                    + "{\"files\":[{\"path\":string,\"content\":string}],\"todos\":[string]}. "
                    + "Paths relative to the output repo. No prose after.";
            String inputs = promptComposer.data("TEMPLATE", spec.body())
                    + promptComposer.data("ENDPOINTS", endpoints.toString());
            String prompt = promptComposer.compose("[IMPLEMENT-TESTS]", "implement-api-tests.prompt.md",
                    Set.of("1", "12"), inputs, outputContract);   // terminology, API heuristics
            String model = modelSelector.resolveTier(ModelTier.DEEP);
            String raw = llm.complete(prompt, model);
            CostResult cost = costRecorder.record("implement-tests", "generate", model, prompt, raw, owner);   // bill before parse
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "implement-tests.schema.json");

            Files.createDirectories(outputDir);
            List<String> written = new ArrayList<>();
            // Data files first — merge-don't-clobber (#14) JSON registries, secret-scan (#15), both in GeneratedFileWriter.
            for (JsonNode f : dataNode.path("files")) {
                String relPath = f.path("path").asText();
                generatedFileWriter.writeWithin(outputDir, relPath, f.path("content").asText(""));
                written.add(relPath);
            }
            for (JsonNode f : node.path("files")) {
                String relPath = f.path("path").asText();
                String content = f.path("content").asText("");
                // path containment (LLM-supplied path) + secret-scan (#15) + merge-don't-clobber (#14) in GeneratedFileWriter.
                generatedFileWriter.writeWithin(outputDir, relPath, content);
                written.add(relPath);
            }

            // Deterministic ad-hoc API artifact (IntelliJ HTTP Client / Bruno) — from the AST, no LLM.
            String httpFile = serviceName.replaceAll("[^A-Za-z0-9._-]", "-") + ".http";
            generatedFileWriter.writeWithin(outputDir, httpFile, httpRequestsEmitter.emit(serviceName, code));
            written.add(httpFile);

            // Deterministic TestNG suites (smoke / regression / full) from the generated test classes — no LLM.
            for (var suite : suiteXmlEmitter.emit(serviceName, written).entrySet()) {
                String rel = "suites/" + suite.getKey();
                generatedFileWriter.writeWithin(outputDir, rel, suite.getValue());
                written.add(rel);
            }

            BuildVerifier.BuildResult build = buildVerifier.verify(outputDir, spec.verifyCommand());
            if ("FAIL".equals(build.status())) {
                // Bounded (single) LLM repair pass: feed the compile errors back, rewrite, re-verify.
                build = attemptRepair(outputDir, node.path("files"), build.output(), spec, owner, written);
            }

            CodegenRun run = new CodegenRun();
            run.setServiceName(serviceName);
            run.setTemplateSource(effectiveTemplate.toString());   // null-safe (default template resolves to a temp file)
            run.setOutputRepo(outputDir.toString());
            run.setBuildStatus(build.status());   // PASS | FAIL | SKIPPED (git push/PR stays for live creds)
            run.setFilesWritten(objectMapper.writeValueAsString(written));
            // Merge TODOs from both LLM steps (data IDs that must pre-exist + implement-step notes).
            com.fasterxml.jackson.databind.node.ArrayNode todos = objectMapper.createArrayNode();
            dataNode.path("todos").forEach(todos::add);
            node.path("todos").forEach(todos::add);
            run.setTodos(todos.toString());
            run.setApprovedBy(owner);
            run.setEstCostUsd(cost.estCostUsd());
            CodegenRun saved = repository.save(run);
            log.info("Generated {} file(s) for {} into {}", written.size(), serviceName, outputDir);
            return saved;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("implement-tests failed: " + e.getMessage(), e);
        }
    }
}
