package ca.bnc.qe.veritas.testmgmt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
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
import org.springframework.stereotype.Service;

/**
 * Release test plan with coverage reconciliation: fetch the release's Jira issues → synthesize a plan
 * (LLM) → match its required cases against existing Xray tests (deterministic) → build an RTM (matched /
 * gap) → optionally create gap tests in Xray. Cost tracked.
 */
@Service
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

    public ReleaseTestPlanService(LlmGateway llm, JsonBlockExtractor jsonExtractor, ResponseSchemaValidator schemaValidator,
                                  ModelSelector modelSelector, CostRecorder costRecorder, PromptComposer promptComposer,
                                  ObjectMapper objectMapper, JiraClient jira, XrayClient xray, GateService gateService,
                                  TestPlanRepository planRepository, CoverageItemRepository coverageRepository,
                                  CoverageMatcher matcher, CoverageReportRenderer coverageReportRenderer, Preflight preflight) {
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
    }

    public CoverageSummary generate(String serviceName, String fixVersion, String issuesJql, String testsJql,
                                    String projectKey, boolean createGaps, String owner) {
        preflight.releaseTestPlan(fixVersion, issuesJql, createGaps, projectKey);
        // B1 — resolve/validate the release against the project's versions (when discoverable). This is a
        // precondition, so it propagates as PreconditionException (a 400-style config error), not a wrapped 500.
        if (projectKey != null && issuesJql == null) {
            List<JiraVersion> versions = jira.listVersions(projectKey);
            if (!versions.isEmpty() && versions.stream().noneMatch(v -> v.name().equalsIgnoreCase(fixVersion))) {
                throw new PreconditionException("release-test-plan", List.of(
                        "Release '" + fixVersion + "' was not found among project " + projectKey + " versions."));
            }
        }
        try {
            String jql = issuesJql != null ? issuesJql : "fixVersion = \"" + fixVersion + "\"";
            List<JiraIssue> issues = jira.search(jql, List.of("summary"), 200);
            // B2 — classify testable vs non-testable (infra/docs/spike) with a recorded reason (no silent drop).
            List<JiraIssue> nonTestable = new ArrayList<>();
            StringBuilder basis = new StringBuilder("Release " + fixVersion + " issues:\n");
            for (JiraIssue i : issues) {
                if (isNonTestable(i.summary())) {
                    nonTestable.add(i);
                    continue;
                }
                basis.append("- [").append(i.key()).append("] ").append(i.summary()).append("\n");
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
            String prompt = promptComposer.compose("[TEST-PLAN]", "generate-test-plan.prompt.md",
                    Set.of("1", "5", "6", "8", "9", "10"),   // terms, ISO 25010, techniques, planning, risk, monitoring
                    promptComposer.data("RELEASE_ISSUES", basis.toString()), outputContract);
            String model = modelSelector.resolveTier(ModelTier.DEEP);
            String raw = llm.complete(prompt, model);
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "test-plan.schema.json");
            CostResult cost = costRecorder.record("release-test-plan", "synthesize-plan", model, prompt, raw, owner);

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
            plan.setEstCostUsd(cost.estCostUsd());
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
                            String key = xray.createTest(
                                    new XrayTestSpec(projectKey, m.requiredTitle(), "Manual", List.of()));
                            keyByFingerprint.put(fp, key);
                            item.setMatchedTestKey(key);
                            item.setMatchStatus("CREATED");
                            created++;
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
                    cost.estCostUsd(), reportPath, plan.getConfidence(), plan.getRiskCount());
        } catch (Exception e) {
            throw new IllegalStateException("release-test-plan failed: " + e.getMessage(), e);
        }
    }

    /** Heuristic: issues that aren't worth test cases (spikes, research, infra, docs, chores). */
    private boolean isNonTestable(String summary) {
        if (summary == null) {
            return false;
        }
        return summary.toLowerCase(java.util.Locale.ROOT)
                .matches(".*\\b(spike|investigat|research|chore|infra|documentation)\\b.*|.*\\[doc.*");
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
