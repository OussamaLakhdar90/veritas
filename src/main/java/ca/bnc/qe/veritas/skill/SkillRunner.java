package ca.bnc.qe.veritas.skill;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import ca.bnc.qe.veritas.cost.CostEstimator;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptAssembler;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.RunStep;
import ca.bnc.qe.veritas.persistence.RunStepRepository;
import ca.bnc.qe.veritas.persistence.SkillRun;
import ca.bnc.qe.veritas.persistence.SkillRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

/**
 * Walks a skill pipeline: routes each step to its Java handler ({@link StepKind#DETERMINISTIC}), the
 * Copilot CLI ({@link StepKind#LLM}), or the {@link GateService} ({@link StepKind#GATE}); evaluates
 * {@code when} guards; validates every LLM reply against its JSON schema; selects a model by tier and
 * records the dollar cost per LLM step; and persists the run + per-step records. This is where "LLM only
 * when needed" is enforced — deterministic and gate steps record zero cost.
 */
@Service
@Slf4j
public class SkillRunner {

    private final SkillManifestLoader loader;
    private final Map<String, StepHandler> handlers;
    private final LlmGateway llm;
    private final PromptAssembler promptAssembler;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final GateService gateService;
    private final ModelSelector modelSelector;
    private final CostEstimator costEstimator;
    private final SkillRunRepository runRepository;
    private final RunStepRepository stepRepository;
    private final ObjectMapper objectMapper;
    private final SpelExpressionParser spel = new SpelExpressionParser();

    public SkillRunner(SkillManifestLoader loader,
                       Map<String, StepHandler> handlers,
                       LlmGateway llm,
                       PromptAssembler promptAssembler,
                       JsonBlockExtractor jsonExtractor,
                       ResponseSchemaValidator schemaValidator,
                       GateService gateService,
                       ModelSelector modelSelector,
                       CostEstimator costEstimator,
                       SkillRunRepository runRepository,
                       RunStepRepository stepRepository,
                       ObjectMapper objectMapper) {
        this.loader = loader;
        this.handlers = handlers;
        this.llm = llm;
        this.promptAssembler = promptAssembler;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.gateService = gateService;
        this.modelSelector = modelSelector;
        this.costEstimator = costEstimator;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.objectMapper = objectMapper;
    }

    public SkillRunResult run(String skillName, Map<String, Object> inputs) {
        SkillManifest manifest = loader.get(skillName);
        validateInputs(manifest, inputs);

        SkillRun run = new SkillRun();
        run.setSkillName(skillName);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.setInputJson(writeJsonQuietly(inputs));
        run = runRepository.save(run);

        StepContext ctx = new StepContext(inputs, resolveTokens(manifest), run.getId());
        int ordinal = 0;
        try {
            for (Step step : manifest.pipeline()) {
                ordinal++;
                if (!shouldRun(step, ctx)) {
                    recordStep(run.getId(), step, ordinal, "SKIPPED", 0, null);
                    continue;
                }
                long t0 = System.currentTimeMillis();
                Object out = null;
                CostResult cost = null;
                switch (step.kind()) {
                    case DETERMINISTIC -> out = runDeterministic(step, ctx);
                    case LLM -> {
                        LlmExec exec = runLlm(step, ctx);
                        out = exec.node();
                        cost = exec.cost();
                    }
                    case GATE -> out = runGate(step, ctx);
                }
                if (out != null && step.out() != null) {
                    ctx.put(step.out(), out);
                }
                if (cost != null) {
                    run.setTotalPremiumRequests(run.getTotalPremiumRequests() + cost.premiumRequests());
                    run.setTotalEstCostUsd(round(run.getTotalEstCostUsd() + cost.estCostUsd()));
                }
                recordStep(run.getId(), step, ordinal, "COMPLETED", System.currentTimeMillis() - t0, cost);
            }
            run.setStatus(RunStatus.COMPLETED);
        } catch (Exception ex) {
            log.error("Skill '{}' run {} failed", skillName, run.getId(), ex);
            run.setStatus(RunStatus.FAILED);
            run.setErrorMessage(truncate(ex.getMessage(), 1900));
        } finally {
            run.setFinishedAt(Instant.now());
            run = runRepository.save(run);
        }
        return new SkillRunResult(run.getId(), run.getStatus().name(), ctx.values(),
                run.getTotalPremiumRequests(), run.getTotalEstCostUsd());
    }

    private Object runDeterministic(Step step, StepContext ctx) {
        StepHandler handler = handlers.get(step.handler());
        if (handler == null) {
            throw new IllegalStateException("No handler bean '" + step.handler() + "' for step '" + step.id() + "'");
        }
        return handler.handle(ctx, step);
    }

    private LlmExec runLlm(Step step, StepContext ctx) throws Exception {
        String prompt = promptAssembler.assemble(step, ctx);
        String model = modelSelector.resolve(step);
        String raw = llm.complete(prompt, model);
        String json = jsonExtractor.extract(raw);
        JsonNode node = objectMapper.readTree(json);
        if (step.expectsJson() != null) {
            schemaValidator.validate(node, step.expectsJson());
        }
        CostResult cost = costEstimator.estimate(model, prompt, raw);
        return new LlmExec(node, cost);
    }

    private Object runGate(Step step, StepContext ctx) {
        GateService.Decision decision = gateService.await(ctx.runId(), step.id(), "system");
        if (!decision.approved()) {
            throw new IllegalStateException("Gate '" + step.id() + "' awaiting approval (gate " + decision.gateId() + ")");
        }
        return Map.of("approved", true, "gateId", decision.gateId());
    }

    private boolean shouldRun(Step step, StepContext ctx) {
        if (step.when() == null || step.when().isBlank()) {
            return true;
        }
        StandardEvaluationContext ec = new StandardEvaluationContext(new EvalRoot(ctx.inputs(), ctx.values()));
        ec.addPropertyAccessor(new MapAccessor());
        Boolean result = spel.parseExpression(step.when()).getValue(ec, Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    private void validateInputs(SkillManifest manifest, Map<String, Object> inputs) {
        if (manifest.inputs() == null) {
            return;
        }
        for (InputSpec spec : manifest.inputs()) {
            if (spec.required() && (inputs == null || inputs.get(spec.name()) == null)) {
                throw new IllegalArgumentException("Skill '" + manifest.name()
                        + "' requires input '" + spec.name() + "'");
            }
        }
    }

    private Map<String, String> resolveTokens(SkillManifest manifest) {
        Map<String, String> resolved = new HashMap<>();
        if (manifest.tokens() != null) {
            for (String token : manifest.tokens()) {
                String value = System.getenv(token);
                if (value != null) {
                    resolved.put(token, value);
                }
            }
        }
        return resolved;
    }

    private void recordStep(String runId, Step step, int ordinal, String status, long durationMs, CostResult cost) {
        RunStep runStep = new RunStep();
        runStep.setSkillRunId(runId);
        runStep.setStepId(step.id());
        runStep.setKind(step.kind().name());
        runStep.setStatus(status);
        runStep.setOrdinal(ordinal);
        runStep.setDurationMs(durationMs);
        if (cost != null) {
            runStep.setModel(cost.model());
            runStep.setPremiumRequests(cost.premiumRequests());
            runStep.setEstTokensIn(cost.estTokensIn());
            runStep.setEstTokensOut(cost.estTokensOut());
            runStep.setEstCostUsd(cost.estCostUsd());
        }
        stepRepository.save(runStep);
    }

    private String writeJsonQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record LlmExec(JsonNode node, CostResult cost) {}
}
