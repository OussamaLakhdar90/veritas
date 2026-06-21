package ca.bnc.qe.veritas.testmgmt;

import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Generates a global test strategy (ISTQB Test Manager). Basis is built by {@link BasisBuilder} (code or
 * Jira/Confluence); the single LLM step synthesizes the strategy. Cost is tracked.
 */
@Service
public class TestStrategyService {

    private final LlmGateway llm;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final TestStrategyRepository repository;
    private final ObjectMapper objectMapper;
    private final Preflight preflight;

    public TestStrategyService(LlmGateway llm, JsonBlockExtractor jsonExtractor, ResponseSchemaValidator schemaValidator,
                               ModelSelector modelSelector, CostRecorder costRecorder, PromptComposer promptComposer,
                               TestStrategyRepository repository, ObjectMapper objectMapper, Preflight preflight) {
        this.llm = llm;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.preflight = preflight;
    }

    /** One strategy section: its key, the model tier (cost-routed), the named ISTQB concept, and the ask. */
    private record SectionSpec(String key, ModelTier tier, String concept, String instruction, Set<String> pack) {}

    /** Hybrid per-section generation: DEEP only for the load-bearing risk register; ECONOMY for the rest. */
    private static final java.util.List<SectionSpec> SECTIONS = java.util.List.of(
            new SectionSpec("summary", ModelTier.ECONOMY, "CTAL-TM — Test Scope",
                    "One-paragraph executive summary of the strategy (a string).", Set.of("1")),
            new SectionSpec("scope", ModelTier.ECONOMY, "CTAL-TM — Test Scope",
                    "An object with inScope[], outOfScope[], objectives[], assumptions[].", Set.of("1")),
            new SectionSpec("riskRegister", ModelTier.DEEP, "CTAL-TM — Risk-Based Testing",
                    "An array of product+project risks: id, description, category, likelihood, impact, level, mitigation, citation.",
                    Set.of("1", "9")),
            new SectionSpec("testApproach", ModelTier.STANDARD, "CTFL — Test Levels / Test Design Techniques",
                    "An object: levels[], types[], techniques[{name, rationale, riskId, citation}].", Set.of("1", "6")),
            new SectionSpec("exitCriteria", ModelTier.ECONOMY, "CTAL-TM — Exit Criteria",
                    "An array of S.M.A.R.T. exit criteria: criterion, metric, citation.", Set.of("1")),
            new SectionSpec("selfReview", ModelTier.ECONOMY, "self-review",
                    "An object: confidence 0-100 and blindSpots[] reviewing the assembled strategy.", Set.of("1")));

    public TestStrategy generate(String serviceName, String basisText, String source, String owner) {
        preflight.testStrategy(serviceName, basisText);
        try {
            // Per-section generation: each section is its own focused, ISTQB-grounded, cost-routed LLM call.
            com.fasterxml.jackson.databind.node.ObjectNode deliverable = objectMapper.createObjectNode();
            double cost = 0;
            for (SectionSpec s : SECTIONS) {
                String soFar = s.key().equals("selfReview")
                        ? promptComposer.data("STRATEGY_SO_FAR", deliverable.toString()) : "";
                JsonNode section = generateSection(s, serviceName, basisText, soFar, owner);
                if (section != null && section.has(s.key())) {
                    deliverable.set(s.key(), section.get(s.key()));
                }
                cost += sectionCost;
            }
            deliverable.put("markdown", renderStrategyMarkdown(serviceName, deliverable));
            schemaValidator.validate(deliverable, "test-strategy.schema.json");

            TestStrategy strategy = new TestStrategy();
            strategy.setServiceName(serviceName);
            strategy.setContentMarkdown(deliverable.path("markdown").asText(""));
            strategy.setDeliverableJson(deliverable.toString());
            strategy.setConfidence(deliverable.path("selfReview").path("confidence").asDouble(0));
            strategy.setStatus("DRAFT");
            strategy.setSource(source);
            strategy.setOwner(owner);
            strategy.setEstCostUsd(cost);
            strategy.setVersion(1);
            TestStrategy saved = repository.save(strategy);
            saved.setLineageId(saved.getId());   // v1 seeds the lineage
            return repository.save(saved);
        } catch (Exception e) {
            throw new IllegalStateException("test-strategy generation failed: " + e.getMessage(), e);
        }
    }

    private transient double sectionCost;   // captured from the last generateSection cost record

    /** Generate one strategy section via its own LLM call (tier-routed), returning the parsed {key: value} node. */
    private JsonNode generateSection(SectionSpec s, String serviceName, String basisText, String soFar, String owner) {
        String contract = "Generate ONLY the \"" + s.key() + "\" section of the test strategy for " + serviceName
                + ". " + s.instruction() + " Cite ISTQB by NAMED concept (e.g. " + s.concept()
                + "), never a paragraph number. Reply with exactly one fenced ```json block: {\"" + s.key()
                + "\": ...}. No prose after.";
        String inputs = promptComposer.data("TEST_BASIS", basisText) + soFar;
        String prompt = promptComposer.compose("[TEST-STRATEGY-SECTION:" + s.key() + "]",
                "generate-test-artifacts.prompt.md", s.pack(), inputs, contract);
        String model = modelSelector.resolveTier(s.tier());
        String raw = llm.complete(prompt, model);
        sectionCost = costRecorder.record("test-strategy", "section:" + s.key(), model, prompt, raw, owner).estCostUsd();
        try {
            return objectMapper.readTree(jsonExtractor.extract(raw));
        } catch (Exception e) {
            return null;   // a single bad section is skipped, not fatal — the rest of the strategy still generates
        }
    }

    /** Deterministic markdown projection of the assembled sections (always starts "# Test Strategy — ..."). */
    private String renderStrategyMarkdown(String serviceName, JsonNode d) {
        StringBuilder md = new StringBuilder("# Test Strategy — ").append(serviceName).append("\n\n");
        if (!d.path("summary").asText("").isBlank()) {
            md.append("## Summary\n").append(d.path("summary").asText()).append("\n\n");
        }
        if (d.path("scope").isObject()) {
            md.append("## Scope\n");
            d.path("scope").path("objectives").forEach(o -> md.append("- ").append(o.asText()).append("\n"));
            md.append("\n");
        }
        if (d.path("riskRegister").isArray()) {
            md.append("## Risk register\n").append(d.path("riskRegister").size()).append(" risk(s).\n\n");
        }
        if (d.path("testApproach").isObject()) {
            md.append("## Test approach\nLevels & types per the structured deliverable.\n\n");
        }
        if (d.path("exitCriteria").isArray()) {
            md.append("## Exit criteria\n").append(d.path("exitCriteria").size()).append(" S.M.A.R.T. criteria.\n\n");
        }
        return md.toString();
    }

    /**
     * Revise one section of a strategy: replaces the named top-level field of the deliverable and stores it as a
     * NEW immutable version (history preserved). No LLM — a deterministic user edit. {@code contentJson} is the
     * new value (parsed as JSON; falls back to a plain string).
     */
    public TestStrategy reviseSection(String id, String sectionKey, String contentJson, String actor) {
        TestStrategy current = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy " + id));
        try {
            com.fasterxml.jackson.databind.node.ObjectNode deliverable = current.getDeliverableJson() == null
                    ? objectMapper.createObjectNode()
                    : (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(current.getDeliverableJson());
            JsonNode value;
            try {
                value = objectMapper.readTree(contentJson);
            } catch (Exception notJson) {
                value = objectMapper.getNodeFactory().textNode(contentJson);
            }
            deliverable.set(sectionKey, value);
            deliverable.put("markdown", renderStrategyMarkdown(current.getServiceName(), deliverable));   // refresh the rendered doc

            TestStrategy next = new TestStrategy();
            next.setServiceName(current.getServiceName());
            next.setContentMarkdown(deliverable.path("markdown").asText(current.getContentMarkdown()));
            next.setDeliverableJson(deliverable.toString());
            next.setConfidence(deliverable.path("selfReview").path("confidence").asDouble(
                    current.getConfidence() == null ? 0 : current.getConfidence()));
            next.setStatus("DRAFT");   // an edit re-opens the draft
            next.setSource(current.getSource());
            next.setOwner(current.getOwner());
            next.setRevisedBy(actor);
            next.setVersion((current.getVersion() == null ? 1 : current.getVersion()) + 1);
            next.setLineageId(current.getLineageId() != null ? current.getLineageId() : current.getId());
            return repository.save(next);
        } catch (Exception e) {
            throw new IllegalStateException("revise-section failed for " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * Regenerate ONE section with the assistant (optional user guidance), then store it as a new version. The
     * LLM is asked only for that section, grounded in the current strategy — the cheap, surgical "redo this part".
     */
    public TestStrategy regenerateSection(String id, String sectionKey, String guidance, String actor) {
        TestStrategy current = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy " + id));
        SectionSpec spec = SECTIONS.stream().filter(s -> s.key().equals(sectionKey)).findFirst()
                .orElse(new SectionSpec(sectionKey, ModelTier.STANDARD, "ISTQB",
                        "Regenerate this section accurately.", Set.of("1")));
        String contract = "Regenerate ONLY the \"" + sectionKey + "\" section of the test strategy. " + spec.instruction()
                + " Cite ISTQB by NAMED concept (" + spec.concept() + "), never a paragraph number."
                + (guidance != null && !guidance.isBlank() ? " User guidance: " + guidance : "")
                + " Reply with one fenced ```json block: {\"" + sectionKey + "\": ...}. No prose after.";
        String inputs = promptComposer.data("CURRENT_STRATEGY", nz(current.getDeliverableJson()));
        String prompt = promptComposer.compose("[TEST-STRATEGY-SECTION:" + sectionKey + "]",
                "generate-test-artifacts.prompt.md", spec.pack(), inputs, contract);
        String model = modelSelector.resolveTier(spec.tier());
        String raw = llm.complete(prompt, model);
        costRecorder.record("test-strategy", "regenerate:" + sectionKey, model, prompt, raw, actor);
        try {
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            JsonNode value = node.has(sectionKey) ? node.get(sectionKey) : node;
            return reviseSection(id, sectionKey, objectMapper.writeValueAsString(value), actor);
        } catch (Exception e) {
            throw new IllegalStateException("regenerate-section failed for " + id + ": " + e.getMessage(), e);
        }
    }

    /** Approve a strategy version (locks it as the basis release plans pin to). */
    public TestStrategy approve(String id, String actor) {
        TestStrategy s = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy " + id));
        s.setStatus("APPROVED");
        s.setRevisedBy(actor);
        return repository.save(s);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
