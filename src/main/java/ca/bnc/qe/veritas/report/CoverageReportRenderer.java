package ca.bnc.qe.veritas.report;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.persistence.CoverageItem;
import ca.bnc.qe.veritas.persistence.TestPlan;
import org.springframework.stereotype.Component;

/** Renders the Requirements Traceability Matrix (RTM) for a test plan: required cases × match status. */
@Component
public class CoverageReportRenderer {

    private static final Map<String, String> STATUS_COLOR = Map.of(
            "MATCHED", "#1E8E5A", "CREATED", "#1B4F8A", "GAP", "#C2410C", "ORPHAN", "#6B7280", "DEAD", "#9AA1AE");

    public String renderHtml(TestPlan plan, List<CoverageItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/><title>Veritas RTM</title><style>")
                .append("body{font-family:Inter,Segoe UI,system-ui,sans-serif;color:#0E1726;margin:0;background:#F7F9FC}")
                .append(".bar{background:#0E1726;color:#fff;padding:16px 24px}.wrap{padding:24px;max-width:1000px;margin:0 auto}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #E3E6EB;border-radius:10px;overflow:hidden}")
                .append("th,td{text-align:left;padding:9px 12px;font-size:13px;border-top:1px solid #EEF1F5}")
                .append("th{background:#F1F3F6;color:#475069;font-size:11px;text-transform:uppercase}")
                .append(".bdg{font-size:11px;font-weight:600;color:#fff;padding:2px 8px;border-radius:999px}")
                .append("</style></head><body>");
        sb.append("<div class=\"bar\"><strong>Veritas</strong> - Requirements Traceability Matrix</div><div class=\"wrap\">");
        sb.append("<h1 style=\"font-size:20px\">").append(esc(nz(plan.getServiceName())))
                .append(plan.getFixVersion() != null ? " @ " + esc(plan.getFixVersion()) : "").append("</h1>");
        sb.append("<table><thead><tr><th>Required case / existing test</th><th>Status</th><th>Matched test</th><th>Confidence</th></tr></thead><tbody>");
        for (CoverageItem i : items) {
            String color = STATUS_COLOR.getOrDefault(nz(i.getMatchStatus()), "#6B7280");
            sb.append("<tr><td>").append(esc(nz(i.getRequiredCaseRef()))).append("</td>")
                    .append("<td><span class=\"bdg\" style=\"background:").append(color).append("\">")
                    .append(esc(nz(i.getMatchStatus()))).append("</span></td>")
                    .append("<td>").append(esc(nz(i.getMatchedTestKey()))).append("</td>")
                    .append("<td>").append(esc(nz(i.getConfidence()))).append("</td></tr>");
        }
        sb.append("</tbody></table></div></body></html>");
        return sb.toString();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
