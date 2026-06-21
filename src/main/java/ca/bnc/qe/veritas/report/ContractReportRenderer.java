package ca.bnc.qe.veritas.report;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.persistence.Scan;
import org.springframework.stereotype.Component;

/**
 * Renders a self-contained, NBC-themed management report (HTML) from contract-validation findings.
 * No external assets; severity is colour + label (WCAG: never colour alone). PDF is a later addition.
 */
@Component
public class ContractReportRenderer {

    private static final Map<String, String> SEVERITY_COLOR = Map.of(
            "BLOCKER", "#6B21A8", "CRITICAL", "#C2122D", "MAJOR", "#C2410C", "MINOR", "#CA8A04", "INFO", "#3A4658");

    public String renderHtml(Scan scan, List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                .append("<title>Veritas — Contract Validation Report</title><style>")
                .append("body{font-family:Inter,Segoe UI,system-ui,sans-serif;color:#0E1726;margin:0;background:#F7F9FC}")
                .append(".bar{background:#0E1726;color:#fff;padding:16px 24px;display:flex;align-items:center;gap:10px}")
                .append(".mark{width:22px;height:22px;border-radius:6px;background:#E31837;display:inline-block}")
                .append(".wrap{padding:24px;max-width:1100px;margin:0 auto}")
                .append(".cards{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}")
                .append(".card{background:#fff;border:1px solid #E3E6EB;border-radius:10px;padding:12px 16px;min-width:120px}")
                .append(".card .n{font-size:24px;font-weight:600}.card .l{font-size:12px;color:#6B7280}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #E3E6EB;border-radius:10px;overflow:hidden}")
                .append("th,td{text-align:left;padding:9px 12px;font-size:13px;border-top:1px solid #EEF1F5;vertical-align:top}")
                .append("th{background:#F1F3F6;color:#475069;font-size:11px;text-transform:uppercase;letter-spacing:.03em}")
                .append(".bdg{font-size:11px;font-weight:600;color:#fff;padding:2px 8px;border-radius:999px}")
                .append("code{font-family:JetBrains Mono,ui-monospace,monospace;font-size:12px}")
                .append(".foot{color:#9AA1AE;font-size:12px;margin-top:16px}")
                .append("</style></head><body>");

        sb.append("<div class=\"bar\"><span class=\"mark\"></span>")
                .append("<strong>Veritas</strong> - Contract Validation Report</div>");

        sb.append("<div class=\"wrap\">");
        sb.append("<h1 style=\"font-size:20px\">").append(esc(nz(scan.getServiceName()))).append("</h1>");
        sb.append("<p style=\"color:#6B7280;font-size:13px\">Scan ").append(esc(scan.getId()))
                .append(scan.getStartedAt() != null ? " - " + scan.getStartedAt() : "")
                .append(" - specs: ").append(esc(nz(scan.getSpecSources()))).append("</p>");

        sb.append("<div class=\"cards\">");
        sb.append(card("Total findings", String.valueOf(findings.size())));
        for (String sev : List.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")) {
            long n = findings.stream().filter(f -> f.getSeverity().name().equals(sev)).count();
            if (n > 0) {
                sb.append(card(cap(sev), String.valueOf(n)));
            }
        }
        sb.append(card("Est. LLM cost", String.format("$%.4f", scan.getTotalEstCostUsd())));
        if (scan.getConfidence() != null) {
            sb.append(card("Self-review", Math.round(scan.getConfidence()) + "%"));
        }
        sb.append("</div>");
        if (scan.getBlindSpots() != null && !scan.getBlindSpots().isBlank()) {
            sb.append("<p style=\"color:#C2410C;font-size:13px\"><strong>Blind spots:</strong> ")
                    .append(esc(scan.getBlindSpots())).append("</p>");
        }

        sb.append("<table><thead><tr><th>Severity</th><th>Layer</th><th>Endpoint</th><th>Spec</th>")
                .append("<th>Summary</th><th>Evidence</th><th>Conf.</th></tr></thead><tbody>");
        for (Finding f : findings) {
            String color = SEVERITY_COLOR.getOrDefault(f.getSeverity().name(), "#6B7280");
            sb.append("<tr>")
                    .append("<td><span class=\"bdg\" style=\"background:").append(color).append("\">")
                    .append(esc(f.getSeverity().name())).append("</span></td>")
                    .append("<td>").append(f.getLayer() != null ? f.getLayer().name() : "").append("</td>")
                    .append("<td><code>").append(esc(nz(f.getEndpoint()))).append("</code></td>")
                    .append("<td>").append(esc(nz(f.getSpecSource()))).append("</td>")
                    .append("<td>").append(esc(nz(f.getSummary())));
            if (f.getExplanation() != null) {
                sb.append("<br/><span style=\"color:#6B7280\">").append(esc(f.getExplanation())).append("</span>");
            }
            sb.append("</td>")
                    .append("<td>").append(evidence(f)).append("</td>")
                    .append("<td>").append(f.getConfidence() != null ? f.getConfidence().name() : "").append("</td>")
                    .append("</tr>");
            sb.append(detailRow(f));
        }
        sb.append("</tbody></table>");
        sb.append("<p class=\"foot\">Generated by Veritas. Deterministic findings (no LLM) for L1–L4; ")
                .append("LLM-enriched explanations/fixes where shown.</p>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    public byte[] renderPdf(Scan scan, List<Finding> findings) {
        String html = renderHtml(scan, findings);
        try (java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream()) {
            com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF render failed: " + e.getMessage(), e);
        }
    }

    private String card(String label, String value) {
        return "<div class=\"card\"><div class=\"n\">" + esc(value) + "</div><div class=\"l\">" + esc(label) + "</div></div>";
    }

    /**
     * Expandable detail row carrying the load-bearing evidence the management report must show: the code
     * snippet, the current YAML fragment, the proposed fix, and the ISTQB citation. Rendered only when at
     * least one is present, so clean findings stay compact.
     */
    private String detailRow(Finding f) {
        String snippet = f.getCodeEvidence() != null ? f.getCodeEvidence().snippet() : null;
        String currentYaml = f.getCurrentYamlFragment();
        String proposedFix = f.getProposedFix();
        String citation = f.getCitation();
        if (isBlank(snippet) && isBlank(currentYaml) && isBlank(proposedFix) && isBlank(citation)) {
            return "";
        }
        StringBuilder d = new StringBuilder("<tr><td colspan=\"7\" style=\"background:#FBFCFD\">");
        if (!isBlank(snippet)) {
            d.append(block("Code evidence", "<pre style=\"margin:4px 0;white-space:pre-wrap\"><code>")
                    .append(esc(snippet)).append("</code></pre>"));
        }
        if (!isBlank(currentYaml)) {
            d.append(block("Current YAML", "<pre style=\"margin:4px 0;white-space:pre-wrap\"><code>")
                    .append(esc(currentYaml)).append("</code></pre>"));
        }
        if (!isBlank(proposedFix)) {
            d.append(block("Proposed fix", "<div style=\"margin:4px 0\">")
                    .append(esc(proposedFix)).append("</div>"));
        }
        if (!isBlank(citation)) {
            d.append(block("ISTQB citation", "<div style=\"margin:4px 0;color:#8A6A1E\">")
                    .append(esc(citation)).append("</div>"));
        }
        d.append("</td></tr>");
        return d.toString();
    }

    private StringBuilder block(String label, String openTag) {
        return new StringBuilder("<div style=\"font-size:11px;text-transform:uppercase;letter-spacing:.03em;"
                + "color:#475069;margin-top:6px\">").append(esc(label)).append("</div>").append(openTag);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String evidence(Finding f) {
        if (f.getCodeEvidence() == null || f.getCodeEvidence().location() == null) {
            return "";
        }
        String loc = f.getCodeEvidence().location();
        Integer line = f.getCodeEvidence().startLine();
        String shortLoc = loc.contains("/") ? loc.substring(loc.lastIndexOf('/') + 1)
                : (loc.contains("\\") ? loc.substring(loc.lastIndexOf('\\') + 1) : loc);
        return "<code>" + esc(shortLoc) + (line != null ? ":" + line : "") + "</code>";
    }

    private String cap(String sev) {
        return sev.charAt(0) + sev.substring(1).toLowerCase();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
