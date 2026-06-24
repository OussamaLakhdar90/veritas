package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.Scan;
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
}
