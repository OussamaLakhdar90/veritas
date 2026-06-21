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

    public CodegenService(LlmGateway llm, JsonBlockExtractor jsonExtractor, ResponseSchemaValidator schemaValidator,
                          ModelSelector modelSelector, CostRecorder costRecorder, PromptComposer promptComposer,
                          ObjectMapper objectMapper, TemplateLearner templateLearner,
                          JavaSpringExtractor javaSpringExtractor, CodegenRunRepository repository,
                          BuildVerifier buildVerifier, ca.bnc.qe.veritas.preflight.Preflight preflight,
                          PrPublisher prPublisher, ca.bnc.qe.veritas.skill.GateService gateService,
                          GeneratedFileWriter generatedFileWriter) {
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
    }

    /**
     * Outward step of implement-tests: branch + commit + push the generated output repo and open a PR
     * (gated for human approval). Completes the Pillar-C flow; idempotent re-runs reuse the branch/PR.
     */
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
        preflight.implementTests(serviceName, serviceRepo, templatePath, outputDir);
        TemplateSpec spec = templateLearner.learn(templatePath);   // fails fast if template missing/invalid
        ApiModel code = javaSpringExtractor.extract(serviceRepo);
        List<String> endpoints = new ArrayList<>();
        code.endpoints().forEach(e -> endpoints.add(e.signature()));

        try {
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
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "implement-tests.schema.json");
            CostResult cost = costRecorder.record("implement-tests", "generate", model, prompt, raw, owner);

            Files.createDirectories(outputDir);
            List<String> written = new ArrayList<>();
            for (JsonNode f : node.path("files")) {
                String relPath = f.path("path").asText();
                String content = f.path("content").asText("");
                // secret-scan (#15) + merge-don't-clobber (#14) handled by GeneratedFileWriter.
                generatedFileWriter.write(outputDir.resolve(relPath), relPath, content);
                written.add(relPath);
            }

            BuildVerifier.BuildResult build = buildVerifier.verify(outputDir, spec.verifyCommand());

            CodegenRun run = new CodegenRun();
            run.setServiceName(serviceName);
            run.setTemplateSource(templatePath.toString());
            run.setOutputRepo(outputDir.toString());
            run.setBuildStatus(build.status());   // PASS | FAIL | SKIPPED (git push/PR stays for live creds)
            run.setFilesWritten(objectMapper.writeValueAsString(written));
            run.setTodos(node.path("todos").toString());
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
