package ca.bnc.qe.veritas.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local/dev/test gateway. Active by default ({@code veritas.llm.mode=mock}) so the whole pipeline runs
 * without the {@code copilot} CLI. Returns a deterministic, schema-valid response for the Phase-0 echo
 * skill; later phases register richer canned responses keyed by prompt content.
 */
@Component
@ConditionalOnProperty(name = "veritas.llm.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockLlmGateway implements LlmGateway {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String complete(String prompt, String model) {
        log.debug("MockLlmGateway.complete (model={}, promptChars={})", model, prompt == null ? 0 : prompt.length());
        if (prompt != null && prompt.contains("[TRANSLATE]")) {
            // Mock can't translate; return an empty map so the service falls back to English (a real run uses
            // the cheapest Copilot model). Keeps the report bilingual-capable without a real LLM in tests.
            return "Translations (mock).\n```json\n{}\n```\n";
        }
        if (prompt != null && prompt.contains("[FEATURE-TAGGER]")) {
            // Mock can't do semantic clustering; return no merge groups so the deterministic seed stands unchanged.
            return "Feature tags (mock).\n```json\n{\"features\":[]}\n```\n";
        }
        if (prompt != null && prompt.contains("[EVIDENCE-SECTION:")) {
            // Mock can't synthesize grounded content; cite the first allowed unit id AND copy a verbatim quote from
            // that unit's evidence line in the prompt, so the section passes the (now mandatory) CitationValidator
            // quote-grounding check in mock mode. Placeholder content otherwise.
            String fid = firstAllowedId(prompt);
            String quote = groundedQuote(prompt, fid);
            return "Section (mock).\n```json\n{\"feature\":\"(mock)\",\"evidence\":[{\"unitId\":\"" + fid
                    + "\",\"quote\":\"" + jsonEscape(quote) + "\"}],\"content\":\"(mock section — a real run uses Copilot)\"}\n```\n";
        }
        if (prompt != null && prompt.contains("[TEST-STRATEGY-SECTION:")) {
            int i = prompt.indexOf("[TEST-STRATEGY-SECTION:") + "[TEST-STRATEGY-SECTION:".length();
            int j = prompt.indexOf("]", i);
            String key = j > i ? prompt.substring(i, j) : "";
            String body = switch (key) {
                case "summary" -> "{\"summary\":\"Test strategy executive summary (mock, per-section).\"}";
                case "scope" -> "{\"scope\":{\"inScope\":[\"Policy create/read APIs\"],\"outOfScope\":[\"Batch jobs\"],"
                        + "\"objectives\":[\"Validate functional correctness and authZ\"],\"assumptions\":[\"Stable env\"]}}";
                case "riskRegister" -> "{\"riskRegister\":[{\"id\":\"R1\",\"description\":\"Policy creation accepts "
                        + "invalid payloads\",\"category\":\"PRODUCT\",\"likelihood\":\"M\",\"impact\":\"H\",\"level\":"
                        + "\"HIGH\",\"mitigation\":\"BVA + decision-table coverage\",\"citation\":\"CTAL-TM — Risk-Based Testing\"}]}";
                case "testApproach" -> "{\"testApproach\":{\"levels\":[\"System\",\"Integration\"],\"types\":"
                        + "[\"Functional\",\"Security\"],\"techniques\":[{\"name\":\"Boundary Value Analysis\","
                        + "\"rationale\":\"Bounded fields on create\",\"riskId\":\"R1\",\"citation\":\"CTFL — Boundary Value Analysis\"}]}}";
                case "exitCriteria" -> "{\"exitCriteria\":[{\"criterion\":\"Every HIGH risk has >=2 executed cases\","
                        + "\"metric\":\"risk coverage %\",\"citation\":\"CTAL-TM — Exit Criteria\"}]}";
                case "selfReview" -> "{\"selfReview\":{\"confidence\":80,\"blindSpots\":[\"No performance NFRs supplied\"]}}";
                default -> "{\"" + key + "\":\"(mock section)\"}";
            };
            return "Strategy section (mock).\n```json\n" + body + "\n```\n";
        }
        if (prompt != null && prompt.contains("[TEST-STRATEGY]")) {
            return """
                    Test strategy (mock — a real run uses Copilot).

                    ```json
                    {"markdown": "# Test Strategy (mock)\\n\\n## Scope\\n## Test levels\\n## Risk-based prioritization\\n## Entry & exit criteria\\n", "summary": "mock strategy", "riskRegister": [{"id": "R1", "description": "Auth bypass on policy APIs", "level": "HIGH", "citation": "CTAL-TM — Risk-Based Testing"}], "testApproach": {"levels": ["System", "Integration"], "types": ["Functional", "Security"]}, "exitCriteria": [{"criterion": "All HIGH risks have >=2 tests", "metric": "risk coverage %", "citation": "CTAL-TM — Exit Criteria"}], "selfReview": {"confidence": 80, "blindSpots": ["No performance NFRs supplied"]}}
                    ```
                    """;
        }
        if (prompt != null && prompt.contains("[GENERATE-DATA]")) {
            // Data artifacts in the template's format — secrets ONLY as $sensitive refs, IDs-that-must-exist as TODOs.
            return """
                    Test data (mock).

                    ```json
                    {"files": [{"path": "src/test/resources/serverConfig.json", "content": "{\\n  \\"baseUrl\\": \\"https://localhost:8443\\",\\n  \\"token\\": \\"$sensitive:CIAM_API_TOKEN\\"\\n}\\n"}, {"path": "src/test/resources/data-manager.json", "content": "{\\n  \\"policies\\": []\\n}\\n"}], "todos": ["Provision a seed policy id and set it in data-manager.json"]}
                    ```
                    """;
        }
        if (prompt != null && prompt.contains("[IMPLEMENT-TESTS-REPAIR]")) {
            return """
                    Repaired tests (mock).

                    ```json
                    {"files": [{"path": "src/test/java/RepairedApiTest.java", "content": "// repaired by Veritas (mock)\\nclass RepairedApiTest {}\\n"}]}
                    ```
                    """;
        }
        if (prompt != null && prompt.contains("[IMPLEMENT-TESTS]")) {
            return """
                    Generated tests (mock).

                    ```json
                    {"files": [{"path": "src/test/java/GeneratedApiTest.java", "content": "// generated by Veritas (mock)\\nclass GeneratedApiTest {}\\n"}], "todos": ["Set the base URL in serverConfig.json"]}
                    ```
                    """;
        }
        if (prompt != null && prompt.contains("[TEST-PLAN]")) {
            // Structured, consultant-grade deliverable (mock — a real run uses Copilot). Keeps the two
            // required-case titles the coverage matcher/tests rely on.
            return """
                    Release test plan (mock).

                    ```json
                    {"executiveSummary":"Release 8.2 carries elevated risk in policy creation and authorized retrieval; risk-based testing prioritizes input-validation and authZ coverage.","scope":{"inScope":["Policy create/read APIs"],"outOfScope":["Batch jobs"],"objectives":["Validate functional correctness and authZ for release 8.2"],"assumptions":["Stable system test environment"]},"riskRegister":[{"id":"R1","description":"Policy creation accepts invalid payloads","category":"PRODUCT","qualityCharacteristic":"Functional suitability","likelihood":"M","impact":"H","level":"HIGH","mitigation":"BVA + decision-table coverage on create","citation":"CTAL-TM — Risk-Based Testing"},{"id":"R2","description":"Unauthorized read of policies","category":"PRODUCT","qualityCharacteristic":"Security","likelihood":"L","impact":"VH","level":"HIGH","mitigation":"AuthZ matrix on read","citation":"CTAL-TM — Risk-Based Testing"}],"testApproach":{"levels":["System","System integration"],"types":["Functional","Security"],"techniques":[{"name":"Boundary Value Analysis","rationale":"Bounded fields on create","citation":"CTFL — Boundary Value Analysis"},{"name":"Decision Table","rationale":"Role x state rules","citation":"CTAL-TA — Decision Table Testing"}],"entryCriteria":["Smoke tests pass","Test data provisioned"]},"exitCriteria":[{"criterion":"Every HIGH risk has >=2 executed cases","metric":"risk coverage %","smart":true,"citation":"CTAL-TM — Exit Criteria"},{"criterion":"No open CRITICAL defects","metric":"open critical count = 0","smart":true,"citation":"CTAL-TM — Exit Criteria"}],"estimation":{"technique":"three-point","effortDays":12,"basis":"E=(a+4m+b)/6 over 2 epics","citation":"CTFL — Test Estimation"},"requiredCases":[{"title":"Validate create policy","technique":"Boundary Value Analysis","priority":"P1","level":"System","type":"Functional","requirementKey":"CIAM-1","riskId":"R1","citation":"CTFL — Boundary Value Analysis"},{"title":"Validate get policy","technique":"Decision Table","priority":"P1","level":"System","type":"Security","requirementKey":"CIAM-2","riskId":"R2","citation":"CTAL-TA — Decision Table Testing"}],"selfReview":{"confidence":84,"rubricChecks":[{"check":"Every required case traces to a requirement and a risk","pass":true,"note":"CIAM-1->R1, CIAM-2->R2"},{"check":"Exit criteria S.M.A.R.T.","pass":true,"note":"both measurable"}],"blindSpots":["Non-functional performance thresholds were not supplied in the basis"]},"markdown":"# Release Test Plan — fixVersion 8.2 (mock)\\n\\nExecutive summary, risk register, RTM, and S.M.A.R.T. exit criteria are carried in the structured fields and rendered by the dashboard."}
                    ```
                    """;
        }
        if (prompt != null && prompt.contains("[TEST-ANALYSIS]")) {
            // Test conditions (ISTQB test analysis) — traced to the mock strategy's risks R1/R2, with an
            // automation candidacy per condition. Schema-valid stand-in so the analysis pipeline runs in mock mode.
            return """
                    Test conditions (mock — a real run uses Copilot).

                    ```json
                    {"conditions": [{"ref": "TCD-001", "description": "Create policy rejects payloads that violate field constraints", "sourceBasisItem": "POST /policies", "priority": "P1", "riskRef": "R1", "qualityCharacteristic": "Functional suitability", "technique": "Boundary Value Analysis", "automation": "AUTOMATED", "automationRationale": "Stable, repeatable regression check — high value to automate"}, {"ref": "TCD-002", "description": "Unauthorized read of a policy is denied", "sourceBasisItem": "GET /policies/{id}", "priority": "P1", "riskRef": "R1", "qualityCharacteristic": "Security", "technique": "Decision Table", "automation": "AUTOMATED", "automationRationale": "AuthZ matrix is deterministic and regression-critical"}, {"ref": "TCD-003", "description": "Exploratory check of error-message clarity on validation failure", "sourceBasisItem": "POST /policies", "priority": "P3", "riskRef": "R1", "qualityCharacteristic": "Usability", "technique": "Exploratory", "automation": "MANUAL", "automationRationale": "Judgement-based, no stable oracle — better run by a human"}], "selfReview": {"confidence": 81, "blindSpots": ["No performance NFRs supplied in the basis"]}}
                    ```
                    """;
        }
        if (prompt != null && prompt.contains("[TEST-CASES]")) {
            return """
                    Test cases (mock).

                    ```json
                    {"cases": [{"title": "Validate happy path", "technique": "Equivalence Partitioning", "priority": "P1", "type": "Functional", "rationale": "EP covers the valid input partition (CTFL — Equivalence Partitioning)", "requirementKey": "CIAM-1", "steps": [{"action": "Call endpoint", "data": "valid", "expected": "200"}]}], "selfReview": {"confidence": 82, "blindSpots": ["No boundary cases generated for unbounded fields"]}}
                    ```
                    """;
        }
        if (prompt != null && prompt.contains("[TEST-CASE-REVIEW]")) {
            return """
                    Review (mock).

                    ```json
                    {"score": 78, "verdict": "Solid", "gaps": [{"severity": "MAJOR", "issue": "No boundary cases", "cite": "CTAL-TA 3"}], "rubric": [{"criterion": "C1 coverage", "score": 3, "note": "missing BVA"}, {"criterion": "C2 clarity", "score": 4, "note": "ok"}], "correctedSteps": [{"action": "Call endpoint", "data": "boundary", "expected": "400"}], "selfReview": {"confidence": 76, "blindSpots": ["Could not see linked requirement"]}}
                    ```
                    """;
        }
        if (prompt != null && prompt.contains("[CONTRACT-RECONCILE]")) {
            // Deterministic, schema-valid stand-in for the real Copilot reconcile reply.
            return """
                    Contract reconciliation (mock — a real run uses Copilot).

                    ```json
                    {"correctedYaml": "# corrected OpenAPI (mock placeholder — real run generates the reconciled spec)\\n", "findings": [], "designFindings": [{"layer": "L5", "severity": "MINOR", "endpoint": null, "summary": "Inconsistent resource naming across endpoints", "explanation": "Mixed singular/plural nouns reduce API clarity.", "citation": "CTAL-TA — Quality Characteristics (ISO 25010)"}, {"layer": "L6", "severity": "MAJOR", "endpoint": null, "summary": "Spec is a weak test basis — no examples, constraints, or error responses", "explanation": "Without these, equivalence/boundary cases cannot be derived from the spec.", "citation": "CTFL — Test Basis"}], "selfReview": {"confidence": 79, "blindSpots": ["Runtime-only routes and security filters not visible to static analysis"]}}
                    ```
                    """;
        }
        return """
                Echo complete.

                ```json
                {"message": "echo ok"}
                ```
                """;
    }

    // Pull the first id from the section contract's "Cite ONLY these unit ids: [id1, id2]" list. The marker must
    // stay in sync with EvidenceFirstSectionGenerator's contract (guarded end-to-end by the SpringBootTest
    // EvidenceFirstSectionGeneratorMockModeTest). Assumes evidence ids contain no ',' or ']' (true for the
    // JIRA / CONF / CODE:Class#method / POLICY id shapes).
    // Copy a verbatim slice of the cited unit's text from its evidence line in the prompt. EvidenceRetriever.forFeature
    // renders each unit as "[id] (source/type) title: text", so we anchor on "[id] (" (NOT the cite list "[id, ...]")
    // and take the text after the first ": ". Returns "" when there's no evidence line (e.g. the marker-only branch
    // tests) — the section then has no quote, which is the correct outcome to surface.
    private static String groundedQuote(String prompt, String id) {
        if (prompt == null || id == null || id.isBlank()) {
            return "";
        }
        int at = prompt.indexOf("[" + id + "] (");
        if (at < 0) {
            return "";
        }
        int eol = prompt.indexOf('\n', at);
        String line = eol < 0 ? prompt.substring(at) : prompt.substring(at, eol);
        int colon = line.indexOf(": ");
        if (colon < 0) {
            return "";
        }
        String text = line.substring(colon + 2).strip();
        return text.length() <= 40 ? text : text.substring(0, 40);
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static String firstAllowedId(String prompt) {
        String marker = "Cite ONLY these unit ids: [";
        int start = prompt.indexOf(marker);
        if (start < 0) {
            return "NONE";
        }
        start += marker.length();
        int end = prompt.indexOf(']', start);
        if (end <= start) {
            return "NONE";
        }
        String first = prompt.substring(start, end).split(",")[0].trim();
        return first.isEmpty() ? "NONE" : first;
    }
}

