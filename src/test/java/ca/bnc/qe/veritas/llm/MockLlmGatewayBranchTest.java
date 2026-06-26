package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Branch-maximising unit tests for {@link MockLlmGateway}. Every [MARKER] reply branch, the per-key
 * switch arms of [TEST-STRATEGY-SECTION], the [EVIDENCE-SECTION] id-extraction helper (its found /
 * not-found / empty branches), the default/unknown-marker echo path, and {@link MockLlmGateway#isAvailable()}
 * are exercised. Assertions parse the fenced json block exactly the way the production pipeline does
 * ({@link JsonBlockExtractor}) and check real values so they survive mutation testing.
 */
class MockLlmGatewayBranchTest {

    private final MockLlmGateway gateway = new MockLlmGateway();
    private final JsonBlockExtractor extractor = new JsonBlockExtractor();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String reply) {
        try {
            return mapper.readTree(extractor.extract(reply));
        } catch (Exception e) {
            throw new IllegalStateException("reply did not contain parseable json: " + reply, e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // isAvailable
    // ---------------------------------------------------------------------------------------------

    @Test
    void isAvailableAlwaysTrue() {
        assertThat(gateway.isAvailable()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // [TRANSLATE]
    // ---------------------------------------------------------------------------------------------

    @Test
    void translateReturnsEmptyJsonObject() {
        String reply = gateway.complete("Please [TRANSLATE] this", "m");
        assertThat(reply).contains("Translations (mock).");
        JsonNode node = json(reply);
        assertThat(node.isObject()).isTrue();
        assertThat(node.size()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // [FEATURE-TAGGER]
    // ---------------------------------------------------------------------------------------------

    @Test
    void featureTaggerReturnsEmptyFeaturesArray() {
        String reply = gateway.complete("[FEATURE-TAGGER] cluster these", "m");
        assertThat(reply).contains("Feature tags (mock).");
        JsonNode features = json(reply).get("features");
        assertThat(features).isNotNull();
        assertThat(features.isArray()).isTrue();
        assertThat(features).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // [EVIDENCE-SECTION:...] + firstAllowedId branches
    // ---------------------------------------------------------------------------------------------

    @Test
    void evidenceSectionCitesFirstAllowedIdWhenMarkerPresent() {
        String prompt = "[EVIDENCE-SECTION:auth] body\nCite ONLY these unit ids: [JIRA-1, CONF-2, CODE:Foo#bar]\nmore";
        JsonNode node = json(gateway.complete(prompt, "m"));
        assertThat(node.get("feature").asText()).isEqualTo("(mock)");
        JsonNode evidence = node.get("evidence");
        assertThat(evidence.isArray()).isTrue();
        assertThat(evidence).hasSize(1);
        // First id is taken and trimmed (leading/trailing spaces removed, no comma).
        assertThat(evidence.get(0).get("unitId").asText()).isEqualTo("JIRA-1");
        assertThat(node.get("content").asText()).contains("mock section");
    }

    @Test
    void evidenceSectionUsesNoneWhenMarkerAbsent() {
        // firstAllowedId: marker not found -> "NONE".
        String prompt = "[EVIDENCE-SECTION:auth] but no cite list at all";
        JsonNode node = json(gateway.complete(prompt, "m"));
        assertThat(node.get("evidence").get(0).get("unitId").asText()).isEqualTo("NONE");
    }

    @Test
    void evidenceSectionUsesNoneWhenBracketListEmpty() {
        // firstAllowedId: end (']') immediately follows start -> end <= start -> "NONE".
        String prompt = "[EVIDENCE-SECTION:x] Cite ONLY these unit ids: [] nothing here";
        JsonNode node = json(gateway.complete(prompt, "m"));
        assertThat(node.get("evidence").get(0).get("unitId").asText()).isEqualTo("NONE");
    }

    @Test
    void evidenceSectionUsesNoneWhenFirstIdBlank() {
        // firstAllowedId: first split element trims to empty -> "NONE".
        String prompt = "[EVIDENCE-SECTION:x] Cite ONLY these unit ids: [   ,JIRA-9] trailing";
        JsonNode node = json(gateway.complete(prompt, "m"));
        assertThat(node.get("evidence").get(0).get("unitId").asText()).isEqualTo("NONE");
    }

    @Test
    void evidenceSectionTrimsSingleId() {
        String prompt = "[EVIDENCE-SECTION:x] Cite ONLY these unit ids: [  POLICY-7  ] end";
        JsonNode node = json(gateway.complete(prompt, "m"));
        assertThat(node.get("evidence").get(0).get("unitId").asText()).isEqualTo("POLICY-7");
    }

    // ---------------------------------------------------------------------------------------------
    // [TEST-STRATEGY-SECTION:...] switch arms
    // ---------------------------------------------------------------------------------------------

    @Test
    void strategySectionSummaryArm() {
        JsonNode node = json(gateway.complete("[TEST-STRATEGY-SECTION:summary] go", "m"));
        assertThat(node.get("summary").asText()).isEqualTo("Test strategy executive summary (mock, per-section).");
    }

    @Test
    void strategySectionScopeArm() {
        JsonNode scope = json(gateway.complete("[TEST-STRATEGY-SECTION:scope] go", "m")).get("scope");
        assertThat(scope.get("inScope").get(0).asText()).isEqualTo("Policy create/read APIs");
        assertThat(scope.get("outOfScope").get(0).asText()).isEqualTo("Batch jobs");
        assertThat(scope.get("objectives").get(0).asText()).isEqualTo("Validate functional correctness and authZ");
        assertThat(scope.get("assumptions").get(0).asText()).isEqualTo("Stable env");
    }

    @Test
    void strategySectionRiskRegisterArm() {
        JsonNode risk = json(gateway.complete("[TEST-STRATEGY-SECTION:riskRegister] go", "m"))
                .get("riskRegister").get(0);
        assertThat(risk.get("id").asText()).isEqualTo("R1");
        assertThat(risk.get("category").asText()).isEqualTo("PRODUCT");
        assertThat(risk.get("likelihood").asText()).isEqualTo("M");
        assertThat(risk.get("impact").asText()).isEqualTo("H");
        assertThat(risk.get("level").asText()).isEqualTo("HIGH");
        assertThat(risk.get("citation").asText()).contains("Risk-Based Testing");
    }

    @Test
    void strategySectionTestApproachArm() {
        JsonNode ta = json(gateway.complete("[TEST-STRATEGY-SECTION:testApproach] go", "m")).get("testApproach");
        assertThat(ta.get("levels")).hasSize(2);
        assertThat(ta.get("levels").get(0).asText()).isEqualTo("System");
        assertThat(ta.get("levels").get(1).asText()).isEqualTo("Integration");
        assertThat(ta.get("types").get(1).asText()).isEqualTo("Security");
        JsonNode technique = ta.get("techniques").get(0);
        assertThat(technique.get("name").asText()).isEqualTo("Boundary Value Analysis");
        assertThat(technique.get("riskId").asText()).isEqualTo("R1");
    }

    @Test
    void strategySectionExitCriteriaArm() {
        JsonNode exit = json(gateway.complete("[TEST-STRATEGY-SECTION:exitCriteria] go", "m"))
                .get("exitCriteria").get(0);
        assertThat(exit.get("criterion").asText()).isEqualTo("Every HIGH risk has >=2 executed cases");
        assertThat(exit.get("metric").asText()).isEqualTo("risk coverage %");
        assertThat(exit.get("citation").asText()).contains("Exit Criteria");
    }

    @Test
    void strategySectionSelfReviewArm() {
        JsonNode sr = json(gateway.complete("[TEST-STRATEGY-SECTION:selfReview] go", "m")).get("selfReview");
        assertThat(sr.get("confidence").asInt()).isEqualTo(80);
        assertThat(sr.get("blindSpots").get(0).asText()).isEqualTo("No performance NFRs supplied");
    }

    @Test
    void strategySectionDefaultArmEchoesUnknownKey() {
        // default switch arm: unknown key becomes the json object's single field name.
        JsonNode node = json(gateway.complete("[TEST-STRATEGY-SECTION:weirdKey] go", "m"));
        assertThat(node.has("weirdKey")).isTrue();
        assertThat(node.get("weirdKey").asText()).isEqualTo("(mock section)");
    }

    @Test
    void strategySectionEmptyKeyWhenNoClosingBracket() {
        // j > i is false (no ']'), so key = "" and the default arm uses an empty field name.
        JsonNode node = json(gateway.complete("[TEST-STRATEGY-SECTION:noClose go", "m"));
        assertThat(node.has("")).isTrue();
        assertThat(node.get("").asText()).isEqualTo("(mock section)");
    }

    // ---------------------------------------------------------------------------------------------
    // [TEST-STRATEGY] (full)
    // ---------------------------------------------------------------------------------------------

    @Test
    void testStrategyFullReturnsMarkdownAndRiskRegister() {
        JsonNode node = json(gateway.complete("[TEST-STRATEGY] full please", "m"));
        assertThat(node.get("markdown").asText()).contains("# Test Strategy (mock)");
        assertThat(node.get("summary").asText()).isEqualTo("mock strategy");
        assertThat(node.get("riskRegister").get(0).get("level").asText()).isEqualTo("HIGH");
        assertThat(node.get("testApproach").get("types").get(1).asText()).isEqualTo("Security");
        assertThat(node.get("selfReview").get("confidence").asInt()).isEqualTo(80);
    }

    @Test
    void testStrategySectionTakesPriorityOverFullStrategy() {
        // The per-section marker is checked BEFORE the full [TEST-STRATEGY] marker; a prompt with both
        // must resolve to the section reply, not the full one.
        String reply = gateway.complete("[TEST-STRATEGY-SECTION:summary] inside a [TEST-STRATEGY] prompt", "m");
        assertThat(reply).contains("Strategy section (mock).");
        assertThat(reply).doesNotContain("Test strategy (mock — a real run uses Copilot).");
    }

    // ---------------------------------------------------------------------------------------------
    // [GENERATE-DATA]
    // ---------------------------------------------------------------------------------------------

    @Test
    void generateDataReturnsFilesWithSensitiveRefAndTodos() {
        JsonNode node = json(gateway.complete("[GENERATE-DATA] now", "m"));
        JsonNode files = node.get("files");
        assertThat(files).hasSize(2);
        assertThat(files.get(0).get("path").asText()).isEqualTo("src/test/resources/serverConfig.json");
        assertThat(files.get(0).get("content").asText()).contains("$sensitive:CIAM_API_TOKEN");
        assertThat(files.get(1).get("path").asText()).isEqualTo("src/test/resources/data-manager.json");
        assertThat(node.get("todos").get(0).asText()).contains("seed policy id");
    }

    // ---------------------------------------------------------------------------------------------
    // [IMPLEMENT-TESTS-REPAIR] vs [IMPLEMENT-TESTS] ordering
    // ---------------------------------------------------------------------------------------------

    @Test
    void implementTestsRepairReturnsRepairedFile() {
        String reply = gateway.complete("[IMPLEMENT-TESTS-REPAIR] fix it", "m");
        assertThat(reply).contains("Repaired tests (mock).");
        JsonNode file = json(reply).get("files").get(0);
        assertThat(file.get("path").asText()).isEqualTo("src/test/java/RepairedApiTest.java");
        assertThat(file.get("content").asText()).contains("repaired by Veritas");
    }

    @Test
    void implementTestsReturnsGeneratedFileAndTodos() {
        String reply = gateway.complete("[IMPLEMENT-TESTS] make tests", "m");
        assertThat(reply).contains("Generated tests (mock).");
        JsonNode node = json(reply);
        assertThat(node.get("files").get(0).get("path").asText()).isEqualTo("src/test/java/GeneratedApiTest.java");
        assertThat(node.get("files").get(0).get("content").asText()).contains("generated by Veritas");
        assertThat(node.get("todos").get(0).asText()).contains("base URL");
    }

    @Test
    void implementTestsRepairTakesPriorityOverImplementTests() {
        // [IMPLEMENT-TESTS-REPAIR] contains the substring "[IMPLEMENT-TESTS" so order matters: REPAIR must win.
        String reply = gateway.complete("[IMPLEMENT-TESTS-REPAIR] but also [IMPLEMENT-TESTS]", "m");
        assertThat(reply).contains("Repaired tests (mock).");
        assertThat(reply).doesNotContain("Generated tests (mock).");
    }

    // ---------------------------------------------------------------------------------------------
    // [TEST-PLAN]
    // ---------------------------------------------------------------------------------------------

    @Test
    void testPlanReturnsRequiredCasesAndRiskRegister() {
        JsonNode node = json(gateway.complete("[TEST-PLAN] release 8.2", "m"));
        assertThat(node.get("executiveSummary").asText()).contains("Release 8.2");
        assertThat(node.get("riskRegister")).hasSize(2);
        assertThat(node.get("riskRegister").get(1).get("qualityCharacteristic").asText()).isEqualTo("Security");
        JsonNode required = node.get("requiredCases");
        assertThat(required).hasSize(2);
        assertThat(required.get(0).get("title").asText()).isEqualTo("Validate create policy");
        assertThat(required.get(1).get("title").asText()).isEqualTo("Validate get policy");
        assertThat(node.get("estimation").get("effortDays").asInt()).isEqualTo(12);
        assertThat(node.get("selfReview").get("confidence").asInt()).isEqualTo(84);
    }

    // ---------------------------------------------------------------------------------------------
    // [TEST-CASES]
    // ---------------------------------------------------------------------------------------------

    @Test
    void testCasesReturnsOneCaseWithSteps() {
        JsonNode node = json(gateway.complete("[TEST-CASES] generate", "m"));
        JsonNode firstCase = node.get("cases").get(0);
        assertThat(firstCase.get("title").asText()).isEqualTo("Validate happy path");
        assertThat(firstCase.get("technique").asText()).isEqualTo("Equivalence Partitioning");
        assertThat(firstCase.get("priority").asText()).isEqualTo("P1");
        assertThat(firstCase.get("steps").get(0).get("expected").asText()).isEqualTo("200");
        assertThat(node.get("selfReview").get("confidence").asInt()).isEqualTo(82);
    }

    // ---------------------------------------------------------------------------------------------
    // [TEST-CASE-REVIEW]
    // ---------------------------------------------------------------------------------------------

    @Test
    void testCaseReviewReturnsScoreAndRubric() {
        JsonNode node = json(gateway.complete("[TEST-CASE-REVIEW] review", "m"));
        assertThat(node.get("score").asInt()).isEqualTo(78);
        assertThat(node.get("verdict").asText()).isEqualTo("Solid");
        assertThat(node.get("gaps").get(0).get("severity").asText()).isEqualTo("MAJOR");
        assertThat(node.get("rubric")).hasSize(2);
        assertThat(node.get("correctedSteps").get(0).get("expected").asText()).isEqualTo("400");
    }

    // ---------------------------------------------------------------------------------------------
    // [CONTRACT-RECONCILE]
    // ---------------------------------------------------------------------------------------------

    @Test
    void contractReconcileReturnsCorrectedYamlAndDesignFindings() {
        JsonNode node = json(gateway.complete("[CONTRACT-RECONCILE] reconcile", "m"));
        assertThat(node.get("correctedYaml").asText()).contains("corrected OpenAPI");
        assertThat(node.get("findings").isArray()).isTrue();
        assertThat(node.get("findings")).isEmpty();
        JsonNode designFindings = node.get("designFindings");
        assertThat(designFindings).hasSize(2);
        assertThat(designFindings.get(0).get("layer").asText()).isEqualTo("L5");
        assertThat(designFindings.get(0).get("severity").asText()).isEqualTo("MINOR");
        assertThat(designFindings.get(1).get("layer").asText()).isEqualTo("L6");
        assertThat(designFindings.get(1).get("severity").asText()).isEqualTo("MAJOR");
        // endpoint is explicit JSON null in both findings.
        assertThat(designFindings.get(0).get("endpoint").isNull()).isTrue();
        assertThat(node.get("selfReview").get("confidence").asInt()).isEqualTo(79);
    }

    // ---------------------------------------------------------------------------------------------
    // default / unknown-marker echo path + null prompt branch
    // ---------------------------------------------------------------------------------------------

    @Test
    void unknownMarkerFallsBackToEcho() {
        JsonNode node = json(gateway.complete("no recognised marker here", "m"));
        assertThat(node.get("message").asText()).isEqualTo("echo ok");
    }

    @Test
    void nullPromptFallsBackToEchoWithoutNpe() {
        // Every branch guards with `prompt != null`, so a null prompt must skip all markers and echo.
        String reply = gateway.complete(null, "m");
        assertThat(reply).contains("Echo complete.");
        assertThat(json(reply).get("message").asText()).isEqualTo("echo ok");
    }

    @Test
    void emptyPromptFallsBackToEcho() {
        JsonNode node = json(gateway.complete("", "m"));
        assertThat(node.get("message").asText()).isEqualTo("echo ok");
    }

    @Test
    void nullModelIsAcceptedAndDoesNotAffectRouting() {
        // The model arg only feeds a debug log; routing depends solely on the prompt.
        JsonNode node = json(gateway.complete("[TEST-CASES] go", null));
        assertThat(node.get("cases").get(0).get("title").asText()).isEqualTo("Validate happy path");
    }

    // ---------------------------------------------------------------------------------------------
    // Marker precedence: [TRANSLATE] checked before all others
    // ---------------------------------------------------------------------------------------------

    @Test
    void translateTakesPriorityWhenMultipleMarkersPresent() {
        String reply = gateway.complete("[TRANSLATE] and also [TEST-PLAN]", "m");
        assertThat(reply).contains("Translations (mock).");
        assertThat(reply).doesNotContain("Release test plan (mock).");
        assertThat(json(reply).size()).isZero();
    }
}