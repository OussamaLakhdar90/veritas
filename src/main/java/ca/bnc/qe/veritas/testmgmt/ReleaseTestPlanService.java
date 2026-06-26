package ca.bnc.qe.veritas.testmgmt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.coverage.CoverageMatcher;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import ca.bnc.qe.veritas.integration.jira.JiraVersion;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.integration.xray.XrayTest;
import ca.bnc.qe.veritas.integration.xray.XrayTestSpec;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptChunker;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.CoverageItem;
import ca.bnc.qe.veritas.persistence.CoverageItemRepository;
import ca.bnc.qe.veritas.persistence.TestPlan;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import ca.bnc.qe.veritas.report.CoverageReportRenderer;
import ca.bnc.qe.veritas.skill.GateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Release test plan with coverage reconciliation: fetch the release's Jira issues → synthesize a plan
 * (LLM) → match its required cases against existing Xray tests (deterministic) → build an RTM (matched /
 * gap) → optionally create gap tests in Xray. Cost tracked.
 */
@Service
@Slf4j
public class ReleaseTestPlanService {

    private final LlmGateway llm;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final ObjectMapper objectMapper;
    private final JiraClient jira;
    private final XrayClient xray;
    private final GateService gateService;
    private final TestPlanRepository planRepository;
    private final CoverageItemRepository coverageRepository;
    private final CoverageMatcher matcher;
    private final CoverageReportRenderer coverageReportRenderer;
    private final Preflight preflight;
    private final ca.bnc.qe.veritas.persistence.TestStrategyRepository strategyRepository;

    /** Per-batch token budget for the release-issues list. Large releases are synthesized in batches then merged
     *  (so no requirement is silently elided to fit one prompt). 0 = never batch (single call). */
    @Value("${veritas.llm.batch-input-tokens:24000}")
    private int batchInputTokens;

    public ReleaseTestPlanService(LlmGateway llm, JsonBlockExtractor jsonExtractor, ResponseSchemaValidator schemaValidator,
                                  ModelSelector modelSelector, CostRecorder costRecorder, PromptComposer promptComposer,
                                  ObjectMapper objectMapper, JiraClient jira, XrayClient xray, GateService gateService,
                                  TestPlanRepository planRepository, CoverageItemRepository coverageRepository,
                                  CoverageMatcher matcher, CoverageReportRenderer coverageReportRenderer, Preflight preflight,
                                  ca.bnc.qe.veritas.persistence.TestStrategyRepository strategyRepository) {
        this.llm = llm;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.objectMapper = objectMapper;
        this.jira = jira;
        this.xray = xray;
        this.gateService = gateService;
        this.planRepository = planRepository;
        this.coverageRepository = coverageRepository;
        this.matcher = matcher;
        this.coverageReportRenderer = coverageReportRenderer;
        this.preflight = preflight;
        this.strategyRepository = strategyRepository;
    }

    public CoverageSummary generate(String serviceName, String fixVersion, String issuesJql, String testsJql,
                                    String projectKey, boolean createGaps, String owner) {
        preflight.releaseTestPlan(fixVersion, issuesJql, createGaps, projectKey);
        preflight.requireLlm(llm, "release-test-plan");
        // B1 — resolve/validate the release against the project's versions (when discoverable). This is a
        // precondition, so it propagates as PreconditionException (a 400-style config error), not a wrapped 500.
        if (projectKey != null && issuesJql == null) {
            List<JiraVersion> versions = jira.listVersions(projectKey);
            if (!versions.isEmpty() && versions.stream().noneMatch(v -> v.name().equalsIgnoreCase(fixVersion))) {
                throw new PreconditionException("release-test-plan", List.of(
                        "Release '" + fixVersion + "' was not found among project " + projectKey + " versions."));
            }
        }
        // loadStrategy (plan §3 step 4) — coverage is defined relative to a previously-authored strategy's risk
        // register + coverage goals. Hard dependency: fail clearly if none exists, and feed it to the planner.
        List<ca.bnc.qe.veritas.persistence.TestStrategy> strategies =
                strategyRepository.findByServiceNameOrderByCreatedAtDesc(serviceName);
        if (strategies.isEmpty()) {
            throw new PreconditionException("release-test-plan", List.of(
                    "No test strategy found for '" + serviceName + "'. Create a strategy first (test-strategy) — the "
                            + "release plan's risk register and coverage goals derive from it."));
        }
        ca.bnc.qe.veritas.persistence.TestStrategy strategy = selectStrategy(serviceName, strategies);
        String strategyBasis = strategy.getDeliverableJson() != null && !strategy.getDeliverableJson().isBlank()
                ? strategy.getDeliverableJson() : strategy.getContentMarkdown();
        try {
            String jql = issuesJql != null ? issuesJql : "fixVersion = \"" + fixVersion + "\"";
            List<JiraIssue> issues = jira.search(jql, List.of("summary"), 200);
            // B2 — classify testable vs non-testable (infra/docs/spike) with a recorded reason (no silent drop).
            List<JiraIssue> nonTestable = new ArrayList<>();
            List<String> issueLines = new ArrayList<>();
            for (JiraIssue i : issues) {
                if (isNonTestable(i.summary())) {
                    nonTestable.add(i);
                    continue;
                }
                issueLines.add("- [" + i.key() + "] " + i.summary());
            }

            // Output contract MUST mirror test-plan.schema.json (required: markdown, requiredCases, selfReview)
            // and the fields the service reads below (selfReview.confidence, riskRegister, requiredCases.*).
            // Previously this asked for only {markdown, requiredCases[title,technique,priority]}, which a real
            // Copilot run would obey → schema-validation failure + empty risk register/confidence (masked by the mock).
            String outputContract = "Produce a board-ready release test plan for fixVersion " + fixVersion + ". "
                    + "Markdown first (the full document), then ONE fenced ```json block last, nothing after it. "
                    + "The JSON MUST be: {"
                    + "\"executiveSummary\": string, "
                    + "\"scope\": {\"inScope\": [string], \"outOfScope\": [string], \"objectives\": [string], \"assumptions\": [string]}, "
                    + "\"riskRegister\": [{\"id\": string, \"description\": string, \"category\": string, \"qualityCharacteristic\": string, \"likelihood\": string, \"impact\": string, \"level\": string, \"mitigation\": string, \"citation\": string}], "
                    + "\"testApproach\": {\"levels\": [string], \"types\": [string], \"techniques\": [{\"name\": string, \"rationale\": string, \"citation\": string}], \"entryCriteria\": [string]}, "
                    + "\"exitCriteria\": [{\"criterion\": string, \"metric\": string, \"smart\": boolean, \"citation\": string}], "
                    + "\"estimation\": {\"technique\": string, \"effortDays\": number, \"basis\": string, \"citation\": string}, "
                    + "\"requiredCases\": [{\"title\": string, \"technique\": string, \"priority\": string, \"level\": string, \"type\": string, \"requirementKey\": string, \"riskId\": string, \"citation\": string}], "
                    + "\"selfReview\": {\"confidence\": number, \"rubricChecks\": [{\"check\": string, \"pass\": boolean, \"note\": string}], \"blindSpots\": [string]}, "
                    + "\"markdown\": string}. "
                    + "Each requiredCase traces to a requirementKey and a riskId; every HIGH/VERY-HIGH risk needs >=2 cases.";
            // Chunk-and-merge (blind spot #8): a big release would otherwise have its issue list elided to fit one
            // prompt — silently dropping requirements. Instead batch the issues to a token budget, synthesize each
            // batch, and merge the deliverables deterministically. Small releases stay one call (unchanged behaviour).
            String strategyData = promptComposer.data("TEST_STRATEGY", strategyBasis == null ? "" : strategyBasis);
            String model = modelSelector.resolveTier(ModelTier.DEEP);
            List<String> chunks = PromptChunker.chunkLines("Release " + fixVersion + " issues:", issueLines, batchInputTokens);
            if (chunks.size() > 1) {
                log.info("Release {} has {} testable issues — synthesizing in {} batches to fit the context window.",
                        fixVersion, issueLines.size(), chunks.size());
            }
            List<JsonNode> parts = new ArrayList<>();
            double estCostUsd = 0.0;
            for (String chunk : chunks) {
                String inputs = strategyData + promptComposer.data("RELEASE_ISSUES", chunk);
                String prompt = promptComposer.compose("[TEST-PLAN]", "generate-test-plan.prompt.md",
                        Set.of("1", "5", "6", "8", "9", "10"),   // terms, ISO 25010, techniques, planning, risk, monitoring
                        inputs, outputContract, modelSelector.promptTokenCap(model));
                String raw = llm.complete(prompt, model);
                estCostUsd += costRecorder.record("release-test-plan", "synthesize-plan", model, prompt, raw, owner)
                        .estCostUsd();   // bill before parse, so a chunk whose reply fails to parse is still billed
                JsonNode part = objectMapper.readTree(jsonExtractor.extract(raw));
                schemaValidator.validate(part, "test-plan.schema.json");
                parts.add(part);
            }
            JsonNode node = parts.size() == 1 ? parts.get(0) : mergePlanNodes(parts, issueLines.size());

            TestPlan plan = new TestPlan();
            plan.setServiceName(serviceName);
            plan.setKind("RELEASE");
            plan.setFixVersion(fixVersion);
            plan.setDescription("Release test plan for " + fixVersion);
            plan.setContentMarkdown(node.path("markdown").asText(""));
            plan.setDeliverableJson(node.toString());   // full structured deliverable for the dashboard
            plan.setConfidence(node.path("selfReview").path("confidence").asDouble(0));
            plan.setRiskCount(node.path("riskRegister").size());
            plan.setStatus("DRAFT");
            plan.setOwner(owner);
            plan.setEstCostUsd(Math.round(estCostUsd * 10_000.0) / 10_000.0);
            plan = planRepository.save(plan);

            String testsQuery = testsJql != null ? testsJql : (projectKey != null ? "project = " + projectKey : null);
            List<XrayTest> existing = testsQuery != null ? xray.getTestsByJql(testsQuery) : List.of();
            List<CoverageMatcher.TitledTest> titled = new ArrayList<>();
            existing.forEach(t -> titled.add(new CoverageMatcher.TitledTest(t.key(), t.summary())));

            List<String> required = new ArrayList<>();
            java.util.Map<String, String> reqKeyByTitle = new java.util.HashMap<>();
            for (JsonNode c : node.path("requiredCases")) {
                String title = c.path("title").asText("");
                required.add(title);
                if (c.hasNonNull("requirementKey")) {
                    reqKeyByTitle.put(title, c.path("requirementKey").asText());
                }
            }

            int matched = 0;
            int gaps = 0;
            int created = 0;
            int orphans = 0;
            List<CoverageItem> items = new ArrayList<>();
            Set<String> matchedKeys = new HashSet<>();
            // B4 — dedup fingerprints already covered (existing matches) so we never double-create within a run.
            java.util.Map<String, String> keyByFingerprint = new java.util.HashMap<>();
            for (XrayTest t : existing) {
                if (t.key() != null) {
                    keyByFingerprint.putIfAbsent(CoverageMatcher.fingerprint(t.summary()), t.key());
                }
            }
            // Creating gap tests in Xray is an outward write — gate it once for the batch.
            boolean createApproved = createGaps && projectKey != null
                    && gateService.await(plan.getId(), "XRAY_CREATE_GAP_TESTS", owner).approved();
            if (createApproved) {
                // Outward writes ahead (Xray gap tests + the release Test Plan) — fail fast on a missing token.
                preflight.requireXrayWriteScope();
                preflight.requireJiraWriteScope(projectKey);
            }
            for (CoverageMatcher.Match m : matcher.match(required, titled)) {
                CoverageItem item = new CoverageItem();
                item.setTestPlanId(plan.getId());
                item.setRequiredCaseRef(m.requiredTitle());
                item.setRequirementKey(reqKeyByTitle.get(m.requiredTitle()));
                item.setDimension("REQUIREMENT");
                item.setMatchStatus(m.status());
                item.setMatchedTestKey(m.matchedKey());
                item.setConfidence(m.confidence());
                if ("MATCHED".equals(m.status())) {
                    matched++;
                    if (m.matchedKey() != null) {
                        matchedKeys.add(m.matchedKey());
                    }
                } else {
                    gaps++;
                    if (createApproved) {
                        String fp = CoverageMatcher.fingerprint(m.requiredTitle());
                        String dup = keyByFingerprint.get(fp);
                        if (dup != null) {
                            // An equivalent test already exists / was just created — reuse it, never duplicate.
                            item.setMatchedTestKey(dup);
                            item.setMatchStatus("SKIPPED_DUP");
                        } else {
                            // Partial-failure-safe (plan §3.9 / blind spot #4, #20): one failed create must not
                            // abort the batch or lose the rows already persisted. Record FAILED + reason; continue.
                            try {
                                String key = xray.createTest(
                                        new XrayTestSpec(projectKey, m.requiredTitle(), "Manual", List.of()));
                                keyByFingerprint.put(fp, key);
                                item.setMatchedTestKey(key);
                                item.setMatchStatus("CREATED");
                                created++;
                                // Establish requirement coverage in Xray/Jira (#22). Non-fatal — the RTM already
                                // records the link in Veritas; a link failure must not fail the whole batch.
                                String reqKey = item.getRequirementKey();
                                if (reqKey != null && !reqKey.isBlank()) {
                                    try {
                                        xray.linkTestToRequirement(key, reqKey);
                                    } catch (RuntimeException le) {
                                        log.warn("linkTestToRequirement {} -> {} failed: {}", key, reqKey, le.getMessage());
                                    }
                                }
                            } catch (RuntimeException ex) {
                                item.setMatchStatus("FAILED");
                                item.setNote("Xray create failed: " + ex.getMessage());
                                log.warn("Xray createTest failed for '{}': {}", m.requiredTitle(), ex.getMessage());
                            }
                        }
                    }
                }
                items.add(coverageRepository.save(item));
            }
            // orphan/dead: existing tests not covering any required case in this plan
            for (XrayTest t : existing) {
                if (t.key() != null && !matchedKeys.contains(t.key())) {
                    CoverageItem orphan = new CoverageItem();
                    orphan.setTestPlanId(plan.getId());
                    orphan.setDimension("REQUIREMENT");
                    orphan.setRequiredCaseRef("(existing test not in plan: " + t.summary() + ")");
                    orphan.setMatchStatus("ORPHAN");
                    orphan.setMatchedTestKey(t.key());
                    orphan.setConfidence("MEDIUM");
                    items.add(coverageRepository.save(orphan));
                    orphans++;
                }
            }
            // B2 — record non-testable issues so they're visible, not silently dropped.
            for (JiraIssue nt : nonTestable) {
                CoverageItem ci = new CoverageItem();
                ci.setTestPlanId(plan.getId());
                ci.setRequirementKey(nt.key());
                ci.setDimension("REQUIREMENT");
                ci.setRequiredCaseRef("[non-testable] " + nt.summary());
                ci.setMatchStatus("NON_TESTABLE");
                items.add(coverageRepository.save(ci));
            }
            // B6 — multi-dimensional RTM: technique / level / type / risk coverage from the required cases.
            for (JsonNode c : node.path("requiredCases")) {
                String title = c.path("title").asText("");
                addDimension(items, plan.getId(), "TECHNIQUE", c.path("technique"), title);
                addDimension(items, plan.getId(), "LEVEL", c.path("level"), title);
                addDimension(items, plan.getId(), "TYPE", c.path("type"), title);
                addDimension(items, plan.getId(), "RISK", c.path("riskId"), title);
            }

            // Create-if-missing the release Test Plan and attach matched + newly-created tests (gated above).
            if (createApproved) {
                Set<String> attachSet = new java.util.LinkedHashSet<>(matchedKeys);
                for (CoverageItem it : items) {
                    if (it.getMatchedTestKey() != null
                            && ("CREATED".equals(it.getMatchStatus()) || "SKIPPED_DUP".equals(it.getMatchStatus()))) {
                        attachSet.add(it.getMatchedTestKey());
                    }
                }
                List<String> attach = new ArrayList<>(attachSet);
                if (!attach.isEmpty()) {
                    String planKey = jira.createIssue(new JiraCreateRequest(projectKey, "Test Plan",
                            "Release " + fixVersion + " — test plan",
                            List.of("Auto-created by Veritas for release " + fixVersion + "."),
                            List.of("veritas", "release-" + fixVersion)));
                    plan.setXrayTestPlanKey(planKey);
                    plan = planRepository.save(plan);
                    xray.addTestsToTestPlan(planKey, attach);
                }
            }

            String reportPath = writeRtm(plan, items);
            return new CoverageSummary(plan.getId(), required.size(), matched, gaps, created, orphans,
                    plan.getEstCostUsd(), reportPath, plan.getConfidence(), plan.getRiskCount());
        } catch (Exception e) {
            throw new IllegalStateException("release-test-plan failed: " + e.getMessage(), e);
        }
    }

    /**
     * Pick the strategy the release plan derives from: the APPROVED, signed-off one — not merely the newest-created.
     * Otherwise approve-v1 → revise-to-v2(DRAFT) would silently base the release plan on an unapproved draft, voiding
     * the whole approval gate. {@code strategies} is createdAt-desc, so the first APPROVED is the latest approved;
     * with none approved we fall back to the most recent (logged, so it's visible that the gate wasn't crossed).
     */
    static ca.bnc.qe.veritas.persistence.TestStrategy selectStrategy(
            String serviceName, List<ca.bnc.qe.veritas.persistence.TestStrategy> strategies) {
        ca.bnc.qe.veritas.persistence.TestStrategy strategy = strategies.stream()
                .filter(s -> "APPROVED".equalsIgnoreCase(s.getStatus()))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("No APPROVED test strategy for '{}'; deriving the release plan from the most recent "
                            + "strategy (status={}). Approve a strategy so the plan rests on a signed-off baseline.",
                            serviceName, strategies.get(0).getStatus());
                    return strategies.get(0);
                });
        log.info("Release plan for '{}' uses strategy {} (v{}, status={}).",
                serviceName, strategy.getId(), strategy.getVersion(), strategy.getStatus());
        return strategy;
    }

    /** Heuristic: issues that aren't worth test cases (spikes, research, infra, docs, chores). */
    private boolean isNonTestable(String summary) {
        if (summary == null) {
            return false;
        }
        return summary.toLowerCase(java.util.Locale.ROOT)
                .matches(".*\\b(spike|investigat|research|chore|infra|documentation)\\b.*|.*\\[doc.*");
    }

    /**
     * Deterministically merge the per-batch plan deliverables into one (chunk-and-merge): union the requiredCases
     * (dedup by requirementKey+title) and riskRegister (dedup by id), union the list-valued scope/approach/exit
     * fields, take the most conservative self-review confidence, and keep batch-1's narrative (exec summary /
     * estimation / markdown) with a transparent blind spot noting the batch assembly. No LLM call here.
     */
    private JsonNode mergePlanNodes(List<JsonNode> parts, int totalIssues) {
        ObjectNode merged = parts.get(0).deepCopy();

        ArrayNode cases = objectMapper.createArrayNode();
        Set<String> seenCase = new HashSet<>();
        ArrayNode risks = objectMapper.createArrayNode();
        Set<String> seenRisk = new HashSet<>();
        double minConfidence = Double.MAX_VALUE;
        List<String> blindSpots = new ArrayList<>();
        Set<String> seenBlind = new HashSet<>();

        for (JsonNode p : parts) {
            for (JsonNode c : p.path("requiredCases")) {
                String key = c.path("requirementKey").asText("") + "|" + norm(c.path("title").asText(""));
                if (seenCase.add(key)) {
                    cases.add(c);
                }
            }
            for (JsonNode r : p.path("riskRegister")) {
                String id = r.path("id").asText("");
                String key = id.isBlank() ? "desc:" + norm(r.path("description").asText("")) : id;
                if (seenRisk.add(key)) {
                    risks.add(r);
                }
            }
            double conf = p.path("selfReview").path("confidence").asDouble(Double.MAX_VALUE);
            if (conf < minConfidence) {
                minConfidence = conf;
            }
            for (JsonNode b : p.path("selfReview").path("blindSpots")) {
                if (seenBlind.add(b.asText())) {
                    blindSpots.add(b.asText());
                }
            }
        }
        merged.set("requiredCases", cases);
        merged.set("riskRegister", risks);

        // Union the list-valued narrative fields so nothing from later batches is lost.
        if (merged.path("scope").isObject()) {
            ObjectNode scope = (ObjectNode) merged.get("scope");
            for (String f : List.of("inScope", "outOfScope", "objectives", "assumptions")) {
                scope.set(f, unionStrings(parts, "scope", f));
            }
        }
        if (merged.path("testApproach").isObject()) {
            ObjectNode ta = (ObjectNode) merged.get("testApproach");
            for (String f : List.of("levels", "types", "entryCriteria")) {
                ta.set(f, unionStrings(parts, "testApproach", f));
            }
            ta.set("techniques", unionObjects(parts, "testApproach", "techniques", "name"));
        }
        merged.set("exitCriteria", unionObjectsTop(parts, "exitCriteria", "criterion"));

        ObjectNode self = merged.path("selfReview").isObject()
                ? (ObjectNode) merged.get("selfReview") : objectMapper.createObjectNode();
        if (minConfidence != Double.MAX_VALUE) {
            self.put("confidence", minConfidence);
        }
        blindSpots.add("Plan synthesized in " + parts.size() + " batches over " + totalIssues + " release issues to "
                + "fit the model context window; required cases and risks are merged across all batches, narrative "
                + "sections reflect the first batch.");
        ArrayNode bs = objectMapper.createArrayNode();
        blindSpots.forEach(bs::add);
        self.set("blindSpots", bs);
        merged.set("selfReview", self);

        String md = merged.path("markdown").asText("");
        merged.put("markdown", md + "\n\n> _Note: assembled from " + parts.size()
                + " batches of release issues to respect the model context window._");
        return merged;
    }

    /** Union of a nested string array field across all parts, order-preserving and de-duplicated. */
    private ArrayNode unionStrings(List<JsonNode> parts, String parent, String field) {
        ArrayNode out = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode p : parts) {
            for (JsonNode v : p.path(parent).path(field)) {
                if (seen.add(v.asText())) {
                    out.add(v.asText());
                }
            }
        }
        return out;
    }

    /** Union of a nested object array (e.g. techniques) deduped by one identity field. */
    private ArrayNode unionObjects(List<JsonNode> parts, String parent, String field, String idField) {
        ArrayNode out = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode p : parts) {
            for (JsonNode v : p.path(parent).path(field)) {
                if (seen.add(norm(v.path(idField).asText("")))) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** Union of a top-level object array (e.g. exitCriteria) deduped by one identity field. */
    private ArrayNode unionObjectsTop(List<JsonNode> parts, String field, String idField) {
        ArrayNode out = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode p : parts) {
            for (JsonNode v : p.path(field)) {
                if (seen.add(norm(v.path(idField).asText("")))) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private void addDimension(List<CoverageItem> items, String planId, String dimension, JsonNode value, String title) {
        if (value == null || value.isMissingNode() || value.isNull() || value.asText("").isBlank()) {
            return;
        }
        CoverageItem ci = new CoverageItem();
        ci.setTestPlanId(planId);
        ci.setDimension(dimension);
        ci.setRequiredCaseRef(value.asText() + " ← " + title);
        ci.setMatchStatus("PLANNED");
        items.add(coverageRepository.save(ci));
    }

    private String writeRtm(TestPlan plan, List<CoverageItem> items) {
        try {
            Path outDir = Path.of("out");
            Files.createDirectories(outDir);
            Path file = outDir.resolve("rtm-" + plan.getId() + ".html");
            Files.writeString(file, coverageReportRenderer.renderHtml(plan, items));
            return file.toAbsolutePath().toString();
        } catch (Exception e) {
            return null;
        }
    }

    public record CoverageSummary(String planId, int total, int matched, int gaps, int created, int orphans,
                                  double estCostUsd, String reportPath, Double confidence, Integer risks) {}
}
