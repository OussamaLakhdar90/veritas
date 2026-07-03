package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.vcs.BitbucketLinkBuilder;
import org.junit.jupiter.api.Test;

class ContractReportRendererTest {

    private Finding richFinding() {
        return Finding.builder()
                .findingId("f1")
                .type(FindingType.STATUS_CODE_MISSING)
                .layer(Layer.L4)
                .severity(Severity.MAJOR)
                .confidence(Confidence.HIGH)
                .origin("DETERMINISTIC")
                .service("ciam-policies")
                .endpoint("GET /policies")
                .specSource("code-vs-repo-spec")
                .summary("Spec omits 404 returned by the controller")
                .explanation("The controller returns 404 when the policy is absent.")
                .codeEvidence(SourceRef.code("PolicyController.java", 45, 47, "return ResponseEntity.notFound().build();"))
                .currentYamlFragment("  /policies:\n    get:\n      responses:\n        '200': {}")
                .proposedFix("Add a 404 response to the get operation.")
                .citation("RFC 9110 — HTTP status codes")
                .build();
    }

    @Test
    void codeEvidenceIsAClickableBitbucketLinkWhenRepoCoordsAndConnectionAreKnown() {
        ConnectionsProperties cp = new ConnectionsProperties();
        cp.getBitbucket().setEdition("SERVER_DC");
        cp.getBitbucket().setBaseUrl("https://git.bnc.ca");
        ContractReportRenderer renderer = new ContractReportRenderer(new BitbucketLinkBuilder(cp));

        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        scan.setAppId("APP7571");
        scan.setRepoSlug("ciam-policies");
        scan.setGitRef("develop");
        Finding f = richFinding().toBuilder()
                .codeEvidence(SourceRef.code("src/main/java/ca/bnc/PolicyController.java", 45, 47,
                        "return ResponseEntity.notFound().build();"))
                .build();

        String html = renderer.renderHtml(scan, List.of(f));
        // The code-evidence label is now an <a> deep-linking to the file/line in Bitbucket Server.
        assertThat(html)
                .contains("<a")
                .contains("/projects/APP7571/repos/ciam-policies/browse/src/main/java/ca/bnc/PolicyController.java")
                .contains("at=refs%2Fheads%2Fdevelop#45")
                .contains(">PolicyController.java:45</a>");
    }

    @Test
    void aFieldFindingWithoutASnippetStillShowsATraceableCodeLineAndAClearConfidencePill() {
        ConnectionsProperties cp = new ConnectionsProperties();
        cp.getBitbucket().setEdition("SERVER_DC");
        cp.getBitbucket().setBaseUrl("https://git.bnc.ca");
        ContractReportRenderer renderer = new ContractReportRenderer(new BitbucketLinkBuilder(cp));

        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        scan.setAppId("APP7571");
        scan.setRepoSlug("ciam-policies");
        scan.setGitRef("develop");
        // A schema-field finding: code evidence points at the DTO field's own file/line, with NO snippet (the case
        // the user hit — previously it showed no code evidence at all).
        Finding f = Finding.builder()
                .findingId("sf1").type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4).severity(Severity.MAJOR)
                .confidence(Confidence.HIGH).origin("DETERMINISTIC").service("ciam-policies").specSource("code-vs-spec")
                .endpoint("GET /ciam/policies response.password.complexity.excludeAttributes")
                .summary("Field 'excludeAttributes' is in code but missing from the spec schema")
                .codeEvidence(SourceRef.code("src/main/java/ca/bnc/PasswordComplexity.java", 42, 42, null))
                .build();

        String html = renderer.renderHtml(scan, List.of(f));
        // A standalone, traceable code line with a clickable Bitbucket deep link to the exact field.
        assertThat(html).contains("code-trace")
                .contains("/browse/src/main/java/ca/bnc/PasswordComplexity.java")
                .contains(">PasswordComplexity.java:42</a>")
                .contains("#42");
        // Confidence is a clearly-labelled coloured pill, not buried plain text.
        assertThat(html).contains("conf-pill").contains("conf-high").contains("High confidence");
    }

    @Test
    void codeEvidenceStaysPlainTextWhenNoLinkBuilder() {
        // The no-arg constructor (tests / non-Spring use) has no link builder → evidence renders as plain text.
        String html = new ContractReportRenderer().renderHtml(new Scan(), List.of(richFinding()));
        assertThat(html).contains("Code evidence").contains("PolicyController.java:45").doesNotContain("/browse/");
    }

    @Test
    void htmlRendersSnippetCurrentYamlProposedFixAndCitation() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        scan.setConfidence(79.0);
        scan.setBlindSpots("Runtime-only routes not visible to static analysis");
        String html = new ContractReportRenderer().renderHtml(scan, List.of(richFinding()));
        // Management report: executive summary KPIs + reframed coverage section.
        assertThat(html).contains("Executive summary").contains("Contract fidelity").contains("Recommended actions");
        // Bottom-line verdict box: a one-glance release call with Action + Effort rows, bilingual.
        assertThat(html).contains("Bottom line").contains("En résumé").contains("Effort");
        // AI self-confidence is demoted to a clearly-labelled, plain-language footnote (not a top-line management KPI).
        assertThat(html).contains("Explanation confidence").contains("79%");
        assertThat(html).contains("Analysis coverage").contains("Runtime-only routes");

        assertThat(html).contains("Code evidence");
        assertThat(html).contains("ResponseEntity.notFound");
        assertThat(html).contains("Current YAML");
        assertThat(html).contains("/policies:");
        assertThat(html).contains("Proposed fix");
        assertThat(html).contains("Add a 404 response");
        // Reference cites the governing standard for the finding type (OpenAPI/HTTP), not ISTQB.
        assertThat(html).contains("Reference");
        assertThat(html).contains("RFC 9110");
        assertThat(html).doesNotContain("ISTQB citation");
    }

    @Test
    void coverageNotClaimedFullWhenAManualReviewItemDisclaimsAMissingSource() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");   // no blind spots → would otherwise read "Full coverage"
        Finding disclaimer = Finding.builder()
                .findingId("d1").type(FindingType.TEST_BASIS_GAP).layer(Layer.L6).severity(Severity.MAJOR)
                .confidence(Confidence.MEDIUM).origin("LLM").service("ciam-policies").specSource("code-vs-spec")
                .summary("Security test cases not derivable — security source not supplied").build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(disclaimer));
        assertThat(html).contains("Analysis coverage");
        assertThat(html).doesNotContain("Full coverage");
        assertThat(html).contains("Partial coverage");
    }

    @Test
    void coverageClaimedFullWhenNoBlindSpotsAndNoDisclaimers() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");   // no blind spots, clean deterministic finding → "Full coverage"
        String html = new ContractReportRenderer().renderHtml(scan, List.of(richFinding()));
        assertThat(html).contains("Full coverage");
    }

    @Test
    void interactiveHtmlHasSeverityDonutFloatingToggleAndReviewControls() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        Finding manual = Finding.builder()
                .findingId("m1").type(FindingType.TEST_BASIS_GAP).layer(Layer.L6).severity(Severity.MAJOR)
                .confidence(Confidence.MEDIUM).origin("LLM").service("ciam-policies").specSource("code-vs-spec")
                .summary("Spec is a weak test basis").build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(richFinding(), manual));

        assertThat(html).contains("conic-gradient(");                 // exec-summary severity donut (not the flat bar)
        assertThat(html).contains("class=\"lang-toggle\"");           // floating language toggle
        assertThat(html).contains("reviewItem(this,'accept')").contains("reviewItem(this,'reject')");  // accept/reject
        assertThat(html).contains("Manual-review items by severity"); // §6 donut panel
        assertThat(html).doesNotContain("rating-good").doesNotContain(">Good<");   // misleading band label removed
        assertThat(html).contains("exec-snapshot");                   // pie integrated with the KPIs in the summary
        assertThat(html).contains("review-tracker")                   // dynamic accepted/rejected/pending tracker
                .contains("id=\"rt-acc\"").contains("id=\"rt-rej\"").contains("id=\"rt-pen\"");
    }

    @Test
    void additiveOnlyDriftBelow90RendersProceedWithDocumentationFixes() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        // Two additive MAJORs (code returns fields the spec omits) → score 84 (<90) but nothing breaks a consumer.
        Finding a = Finding.builder().findingId("a").type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").service("ciam-policies")
                .specSource("code-vs-spec").endpoint("GET /policies").summary("Field 'x' in code, missing from spec").build();
        Finding b = a.toBuilder().findingId("b").endpoint("GET /policies/{app}")
                .summary("Field 'y' in code, missing from spec").build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(a, b));
        assertThat(html).contains("Proceed — documentation fixes recommended")
                .contains("0 release-blocking")
                .doesNotContain("Hold for fixes");
    }

    @Test
    void additiveOnlyDriftBelow90RendersTheGateReconciliationNoteInBothLanguages() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        Finding a = Finding.builder().findingId("a").type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").service("ciam-policies")
                .specSource("code-vs-spec").endpoint("GET /policies").summary("Field 'x' in code, missing from spec").build();
        Finding b = a.toBuilder().findingId("b").endpoint("GET /policies/{app}")
                .summary("Field 'y' in code, missing from spec").build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(a, b));
        // The gate still FAILS (below 90) — that assertion is unchanged — and a SEPARATE note reconciles it with Proceed.
        assertThat(html).contains("Quality gate: FAIL").contains("class=\"gate gate-fail\">");
        assertThat(html).contains("class=\"gate-note\">")
                .contains("The quality gate measures documentation fidelity; release risk is assessed separately.")
                .contains("Le seuil qualité mesure la fidélité de la documentation");
    }

    @Test
    void gateReconciliationNoteAbsentOnAPassingScan() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");   // no findings -> score 100 -> gate PASS
        String html = new ContractReportRenderer().renderHtml(scan, List.of());
        assertThat(html).contains("Quality gate: PASS").doesNotContain("class=\"gate-note\">");
    }

    @Test
    void aBreakingMajorBelow90StillRendersHoldForFixes() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        Finding breaking = Finding.builder().findingId("t").type(FindingType.SCHEMA_FIELD_TYPE_MISMATCH).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").service("ciam-policies")
                .specSource("code-vs-spec").endpoint("GET /policies").summary("Field 'x' type — code string vs spec integer").build();
        Finding additive = breaking.toBuilder().findingId("m").type(FindingType.SCHEMA_FIELD_MISSING)
                .summary("Field 'y' in code, missing from spec").build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(breaking, additive));
        assertThat(html).contains("Hold for fixes").doesNotContain("documentation fixes recommended");
        // A breaking finding holds the release -> no gate-reconciliation note (that line is only for additive Proceed).
        assertThat(html).doesNotContain("class=\"gate-note\">");
    }

    @Test
    void collapsedFindingListsItsAffectedEndpoints() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        Finding shared = Finding.builder().findingId("s").type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").service("ciam-policies")
                .specSource("code-vs-spec").endpoint("GET /policies")
                .affectedEndpoints(List.of("GET /policies", "GET /policies/{app}"))
                .summary("Field 'excludeAttributes' is in code but missing from spec").build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(shared));
        assertThat(html).contains("Affects").contains("GET /policies/{app}").contains("2 ");
    }

    @Test
    void recordedDispositionRendersAsAnAuditedBadge() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        Finding rejected = richFinding().toBuilder()
                .status("REJECTED").reviewedBy("alice")
                .reviewedAt(java.time.Instant.parse("2026-06-23T14:09:00Z")).build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(rejected));
        assertThat(html).contains("disp-badge").contains("Rejected").contains("alice").contains("2026-06-23");
    }

    @Test
    void nullSeverityFindingDoesNotEmitAnInvalidEmptyConicGradient() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        Finding noSev = Finding.builder().findingId("n").type(FindingType.MISSING_ENDPOINT).layer(Layer.L2)
                .severity(null).confidence(Confidence.MEDIUM).origin("DETERMINISTIC").specSource("code-vs-spec")
                .endpoint("GET /x").summary("endpoint missing from spec").build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(noSev));
        assertThat(html).doesNotContain("conic-gradient()");   // would be invalid CSS / blank donut
    }

    @Test
    void pdfRendersWithDetailRowsAsValidXhtml() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        byte[] pdf = new ContractReportRenderer().renderPdf(scan, List.of(richFinding()));
        assertThat(pdf).isNotEmpty();
        // PDF magic header — proves the strict-XHTML render succeeded with the new detail rows
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    // ───────────────────────── S13i-3: undocumented error-responses note ─────────────────────────

    /** A blanket-advice-demoted STATUS_CODE_MISSING (DETERMINISTIC + LOW) — the exact set demoted to §6 manual review. */
    private Finding demotedAdviceStatus(String id, String endpoint, int status) {
        return Finding.builder().findingId(id).type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.LOW).origin("DETERMINISTIC").service("ciam-policies")
                .specSource("code-vs-spec").endpoint(endpoint)
                .summary("Code can return " + status + " but the spec doesn't document it").build();
    }

    @Test
    void undocumentedErrorResponsesNoteGroupsByStatusWithDistinctEndpointCounts() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        // Two endpoints for 500, one for 406 — 500 must render once with a 2-endpoint count, 406 once.
        Finding a = demotedAdviceStatus("a", "GET /policies", 500);
        Finding b = demotedAdviceStatus("b", "GET /policies/{app}", 500);
        Finding c = demotedAdviceStatus("c", "GET /policies", 406);
        String html = new ContractReportRenderer().renderHtml(scan, List.of(a, b, c));
        assertThat(html).contains("Undocumented error responses").contains("not counted in the score")
                .contains("HTTP 500 — a global exception handler can return this status")
                .contains("(2 endpoints).")
                .contains("HTTP 406 — a global exception handler can return this status")
                .contains("(1 endpoint).");
        // Bilingual — the French heading + line are present too.
        assertThat(html).contains("Réponses d'erreur non documentées").contains("gestionnaire d'exceptions global");
    }

    @Test
    void errorNoteAbsentWhenTheStatusIsCountedMediumNotDemoted() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        // MEDIUM confidence => counted, not demoted to §6 => the note must NOT appear.
        Finding counted = demotedAdviceStatus("m", "GET /policies", 500).toBuilder()
                .confidence(Confidence.MEDIUM).build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(counted));
        assertThat(html).doesNotContain("Undocumented error responses");
    }

    @Test
    void errorNoteAbsentForAnLlmOriginLookalike() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        // Same type + LOW confidence but LLM origin — NOT the deterministic blanket-advice set => no note.
        Finding llm = demotedAdviceStatus("l", "GET /policies", 500).toBuilder().origin("LLM").build();
        String html = new ContractReportRenderer().renderHtml(scan, List.of(llm));
        assertThat(html).doesNotContain("Undocumented error responses");
    }

    @Test
    void errorNoteAbsentWhenThereAreNoFindings() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        String html = new ContractReportRenderer().renderHtml(scan, List.of());
        assertThat(html).doesNotContain("Undocumented error responses");
    }

    @Test
    void errorNoteRendersOnThePdfPathWhichStillStartsWithPdfMagic() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        byte[] pdf = new ContractReportRenderer().renderPdf(scan,
                List.of(demotedAdviceStatus("a", "GET /policies", 500),
                        demotedAdviceStatus("b", "GET /policies/{app}", 500)));
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }
}
