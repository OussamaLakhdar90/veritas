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
        // AI self-confidence is demoted to a clearly-labelled footnote (not a top-line management KPI).
        assertThat(html).contains("AI-assist confidence").contains("79%");
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
    void pdfRendersWithDetailRowsAsValidXhtml() {
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        byte[] pdf = new ContractReportRenderer().renderPdf(scan, List.of(richFinding()));
        assertThat(pdf).isNotEmpty();
        // PDF magic header — proves the strict-XHTML render succeeded with the new detail rows
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }
}
