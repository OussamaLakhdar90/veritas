package ca.bnc.qe.veritas.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.persistence.Scan;
import org.springframework.stereotype.Component;

/**
 * Renders the management contract-validation report, modelled on the BNC contract-validator
 * {@code contract-validation-report.html}: a self-contained, <b>bilingual (EN/FR)</b> dashboard report with a
 * severity distribution bar + category cards, findings grouped into <i>What's Missing</i> / <i>What's Wrong</i>
 * / <i>Dead Spec</i>, per-finding evidence panels (code / current YAML / proposed fix), a <i>Needs Your
 * Attention</i> section for unconfirmed items (NOT counted in the totals), and an <i>Analysis Notes</i> /
 * blind-spots section.
 *
 * <p>The interactive HTML carries a language toggle; the PDF render omits the script and defaults to English
 * (the French spans hide via CSS, which openhtmltopdf honours). The distribution is a CSS stacked bar — not a
 * JS canvas — so it renders identically in HTML and PDF. Severity is always colour + label (WCAG AA).
 */
@Component
public class ContractReportRenderer {

    private static final Map<String, String> SEVERITY_COLOR = Map.of(
            "BLOCKER", "#6B21A8", "CRITICAL", "#C2122D", "MAJOR", "#C2410C", "MINOR", "#CA8A04", "INFO", "#3A4658");

    public String renderHtml(Scan scan, List<Finding> findings) {
        return render(scan, findings, true);
    }

    public byte[] renderPdf(Scan scan, List<Finding> findings) {
        String html = render(scan, findings, false);   // no <script>; English default for a static render
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

    // ---- classification -------------------------------------------------------------------------------------

    /** Unconfirmed items (LLM-origin / low confidence / design-quality) — shown separately, NOT counted. */
    private boolean isNeedsAttention(Finding f) {
        String type = f.getType() != null ? f.getType().name() : "";
        boolean designOnly = type.equals("DESIGN_QUALITY") || type.equals("TEST_BASIS_GAP");
        boolean llm = f.getOrigin() != null && !f.getOrigin().equalsIgnoreCase("DETERMINISTIC");
        boolean lowConf = f.getConfidence() != null && f.getConfidence().name().equals("LOW");
        return designOnly || llm || lowConf;
    }

    private String bucket(Finding f) {
        String t = f.getType() != null ? f.getType().name() : "";
        if (t.contains("MISSING")) {
            return "MISSING";
        }
        if (t.contains("EXTRA")) {
            return "DEAD";
        }
        return "WRONG";
    }

    // ---- render ---------------------------------------------------------------------------------------------

    private String render(Scan scan, List<Finding> findings, boolean interactive) {
        List<Finding> attention = new ArrayList<>();
        List<Finding> missing = new ArrayList<>();
        List<Finding> wrong = new ArrayList<>();
        List<Finding> dead = new ArrayList<>();
        for (Finding f : findings) {
            if (isNeedsAttention(f)) {
                attention.add(f);
            } else if (bucket(f).equals("MISSING")) {
                missing.add(f);
            } else if (bucket(f).equals("DEAD")) {
                dead.add(f);
            } else {
                wrong.add(f);
            }
        }
        int counted = missing.size() + wrong.size() + dead.size();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
                .append("<title>Veritas — Contract Validation Report</title><style>")
                .append(css())
                .append("</style></head><body class=\"lang-en\">");

        // header
        sb.append("<div class=\"hdr\"><div class=\"hd-row\"><span class=\"mark\"></span>")
                .append("<div><div class=\"h-title\">").append(bi("Contract Validation Report", "Rapport de validation de contrat"))
                .append("</div><div class=\"subtitle\">").append(esc(nz(scan.getServiceName()))).append("</div></div>");
        if (interactive) {
            sb.append("<div class=\"lang-toggle\">")
                    .append("<button data-lang=\"en\" class=\"active\" onclick=\"setLang('en')\">EN</button>")
                    .append("<button data-lang=\"fr\" onclick=\"setLang('fr')\">FR</button></div>");
        }
        sb.append("</div></div>");

        sb.append("<div class=\"content\">");
        sb.append("<p class=\"meta\">").append(bi("Scan", "Analyse")).append(" ").append(esc(nz(scan.getId())))
                .append(scan.getStartedAt() != null ? " · " + esc(scan.getStartedAt().toString()) : "")
                .append(" · ").append(bi("specs", "specs")).append(": ").append(esc(nz(scan.getSpecSources())))
                .append("</p>");

        // executive summary card row: distribution + category cards
        sb.append("<div class=\"dashboard-row\">");
        sb.append("<div class=\"dist-panel\"><div class=\"panel-h\">")
                .append(bi("Severity distribution", "Répartition par sévérité")).append("</div>")
                .append(distributionBar(missing, wrong, dead)).append("</div>");
        sb.append("<div class=\"cards-container\">");
        sb.append(card("red", String.valueOf(missing.size()), bi("Missing from YAML", "Manquant du YAML")));
        sb.append(card("orange", String.valueOf(wrong.size()), bi("Mismatches", "Incohérences")));
        sb.append(card("yellow", String.valueOf(dead.size()), bi("Dead Spec", "Spéc. morte")));
        sb.append(card("info", String.valueOf(attention.size()), bi("Needs Attention", "À vérifier")));
        sb.append(card("green", String.valueOf(counted), bi("Counted findings", "Constats comptés")));
        sb.append(card("green", String.format("$%.4f", scan.getTotalEstCostUsd()), bi("Est. LLM cost", "Coût LLM est.")));
        if (scan.getConfidence() != null) {
            sb.append(card("info", Math.round(scan.getConfidence()) + "%", bi("Self-review", "Auto-revue")));
        }
        sb.append("</div></div>");

        // grouped findings
        sb.append(section(bi("What's Missing", "Ce qui manque"), missing, "MISSING"));
        sb.append(section(bi("What's Wrong", "Ce qui est incorrect"), wrong, "WRONG"));
        sb.append(section(bi("Dead Spec (in YAML, not in code)", "Spéc. morte (dans le YAML, absente du code)"), dead, "DEAD"));

        // corrected YAML link
        boolean anyFix = findings.stream().anyMatch(f -> !isBlank(f.getProposedFix()));
        if (anyFix) {
            sb.append("<div class=\"yaml-section\"><h2>").append(bi("Corrected OpenAPI YAML", "YAML OpenAPI corrigé"))
                    .append("</h2><p><a href=\"./").append(esc(slug(scan.getServiceName())))
                    .append("_corrected.yaml\" target=\"_blank\" rel=\"noreferrer\">")
                    .append(bi("Open the corrected OpenAPI YAML", "Ouvrir le YAML OpenAPI corrigé")).append("</a></p></div>");
        }

        // needs-attention (NOT counted)
        if (!attention.isEmpty()) {
            sb.append("<div class=\"needs-attention\"><h2>").append(bi("Needs Your Attention", "À vérifier"))
                    .append("</h2><p class=\"ns-intro\">")
                    .append(bi("These items could not be confirmed from the source code and are <strong>not counted</strong> "
                                    + "in the severity totals or the distribution. Please verify them manually.",
                            "Ces éléments n'ont pas pu être confirmés à partir du code source et ne sont "
                                    + "<strong>pas comptés</strong> dans les totaux de sévérité ni la répartition. Veuillez les vérifier."))
                    .append("</p>");
            for (Finding f : attention) {
                sb.append(findingCard(f));
            }
            sb.append("</div>");
        }

        // analysis notes / blind spots
        sb.append("<div class=\"blind-spots\"><h2>").append(bi("Blind spots &amp; Analysis Notes", "Angles morts et notes d'analyse"))
                .append("</h2>");
        if (!isBlank(scan.getBlindSpots())) {
            sb.append("<p>").append(esc(scan.getBlindSpots())).append("</p>");
        } else {
            sb.append("<p class=\"muted\">").append(bi("No blind spots recorded.", "Aucun angle mort consigné.")).append("</p>");
        }
        sb.append("</div>");

        sb.append("<p class=\"foot\">")
                .append(bi("Generated by Veritas. Deterministic findings (no LLM) for L1–L4; LLM-enriched "
                                + "explanations/fixes where shown. Unconfirmed items are listed under Needs Your Attention.",
                        "Généré par Veritas. Constats déterministes (sans LLM) pour L1–L4; explications/correctifs "
                                + "enrichis par LLM le cas échéant. Les éléments non confirmés sont listés sous À vérifier."))
                .append("</p>");
        sb.append("</div>");   // content

        if (interactive) {
            sb.append("<script>function setLang(l){document.body.className='lang-'+l;")
                    .append("document.querySelectorAll('.lang-toggle button').forEach(function(b){")
                    .append("b.classList.toggle('active', b.getAttribute('data-lang')===l);});}</script>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private String section(String title, List<Finding> items, String kind) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<div class=\"section\"><h2 class=\"section-header\">")
                .append(title).append(" <span class=\"count\">").append(items.size()).append("</span></h2>");
        for (Finding f : items) {
            sb.append(findingCard(f));
        }
        return sb.append("</div>").toString();
    }

    private String findingCard(Finding f) {
        String color = SEVERITY_COLOR.getOrDefault(f.getSeverity() != null ? f.getSeverity().name() : "", "#6B7280");
        StringBuilder sb = new StringBuilder("<div class=\"finding-card\">");
        sb.append("<div class=\"finding-header\">")
                .append("<span class=\"severity-badge\" style=\"background:").append(color).append("\">")
                .append(esc(f.getSeverity() != null ? f.getSeverity().name() : "")).append("</span>")
                .append("<span class=\"finding-title\">").append(esc(nz(f.getSummary()))).append("</span>")
                .append("<span class=\"finding-meta\"><code>").append(esc(nz(f.getEndpoint()))).append("</code>")
                .append(f.getLayer() != null ? " · " + f.getLayer().name() : "")
                .append(f.getConfidence() != null ? " · " + f.getConfidence().name() : "").append("</span>")
                .append("</div>");
        if (!isBlank(f.getExplanation())) {
            sb.append("<div class=\"business-impact\">").append(esc(f.getExplanation())).append("</div>");
        }

        String snippet = f.getCodeEvidence() != null ? f.getCodeEvidence().snippet() : null;
        String currentYaml = f.getCurrentYamlFragment();
        String proposedFix = f.getProposedFix();
        String citation = f.getCitation();
        boolean hasDual = !isBlank(snippet) || !isBlank(currentYaml) || !isBlank(proposedFix);
        if (hasDual) {
            sb.append("<div class=\"dual-view\">");
            if (!isBlank(snippet)) {
                sb.append(panel("code", bi("Code evidence", "Preuve dans le code"),
                        loc(f), "<pre><code>" + esc(snippet) + "</code></pre>"));
            }
            if (!isBlank(currentYaml)) {
                sb.append(panel("yaml-current", bi("Current YAML", "YAML actuel"),
                        "", "<pre><code>" + esc(currentYaml) + "</code></pre>"));
            }
            if (!isBlank(proposedFix)) {
                sb.append(panel("yaml-fix", bi("Proposed fix", "Correctif proposé"),
                        "", "<div class=\"fix\">" + esc(proposedFix) + "</div>"));
            }
            sb.append("</div>");
        }
        if (!isBlank(citation)) {
            sb.append("<div class=\"citation\">").append(bi("ISTQB citation", "Référence ISTQB")).append(": ")
                    .append(esc(citation)).append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private String panel(String cls, String label, String sub, String body) {
        return "<div class=\"evidence-panel " + cls + "\"><div class=\"ep-h\">" + label
                + (isBlank(sub) ? "" : " <span class=\"ep-sub\">" + esc(sub) + "</span>") + "</div>" + body + "</div>";
    }

    /** CSS stacked bar (PDF-safe, no JS/SVG): proportional segments by severity over the counted findings. */
    private String distributionBar(List<Finding> missing, List<Finding> wrong, List<Finding> dead) {
        List<Finding> all = new ArrayList<>();
        all.addAll(missing);
        all.addAll(wrong);
        all.addAll(dead);
        int total = all.size();
        if (total == 0) {
            return "<div class=\"no-findings\">" + bi("No counted findings — contract is clean.",
                    "Aucun constat compté — le contrat est conforme.") + "</div>";
        }
        String[] order = {"BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO"};
        StringBuilder bar = new StringBuilder("<div class=\"dist-bar\">");
        StringBuilder legend = new StringBuilder("<div class=\"dist-legend\">");
        for (String sev : order) {
            long n = all.stream().filter(f -> f.getSeverity() != null && f.getSeverity().name().equals(sev)).count();
            if (n == 0) {
                continue;
            }
            int pct = (int) Math.round(100.0 * n / total);
            String color = SEVERITY_COLOR.get(sev);
            bar.append("<span style=\"width:").append(pct).append("%;background:").append(color).append("\"></span>");
            legend.append("<span class=\"li\"><span class=\"dot\" style=\"background:").append(color)
                    .append("\"></span>").append(cap(sev)).append(" ").append(n).append("</span>");
        }
        return bar.append("</div>").append(legend).append("</div>").toString();
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private String card(String cls, String value, String label) {
        return "<div class=\"summary-card " + cls + "\"><div class=\"number\">" + esc(value) + "</div>"
                + "<div class=\"label\">" + label + "</div></div>";
    }

    /** Bilingual fragment: shows EN under body.lang-en, FR under body.lang-fr. */
    private String bi(String en, String fr) {
        return "<span class=\"en\">" + en + "</span><span class=\"fr\">" + fr + "</span>";
    }

    private String loc(Finding f) {
        if (f.getCodeEvidence() == null || f.getCodeEvidence().location() == null) {
            return "";
        }
        String l = f.getCodeEvidence().location();
        String shortLoc = l.contains("/") ? l.substring(l.lastIndexOf('/') + 1)
                : (l.contains("\\") ? l.substring(l.lastIndexOf('\\') + 1) : l);
        Integer line = f.getCodeEvidence().startLine();
        return shortLoc + (line != null ? ":" + line : "");
    }

    private String slug(String s) {
        return s == null ? "service" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String cap(String sev) {
        return sev.isEmpty() ? sev : sev.charAt(0) + sev.substring(1).toLowerCase(java.util.Locale.ROOT);
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

    private String css() {
        return "body{font-family:Inter,Segoe UI,system-ui,sans-serif;color:#1a1a2e;margin:0;background:#f5f6fa}"
                + ".lang-en .fr{display:none}.lang-fr .en{display:none}"
                + ".hdr{background:linear-gradient(135deg,#1a1a2e 0%,#16213e 50%,#0f3460 100%);color:#fff;padding:2rem 2rem}"
                + ".hd-row{display:flex;align-items:center;gap:14px;max-width:1100px;margin:0 auto}"
                + ".mark{width:26px;height:26px;border-radius:6px;background:#e94560;display:inline-block}"
                + ".h-title{font-size:1.5rem;font-weight:600}.subtitle{opacity:.85;font-size:.95rem}"
                + ".lang-toggle{margin-left:auto;display:flex;gap:6px}"
                + ".lang-toggle button{background:rgba(255,255,255,.15);color:#fff;border:1px solid rgba(255,255,255,.4);"
                + "padding:.3rem .8rem;border-radius:6px;cursor:pointer;font-size:.85rem;font-weight:600}"
                + ".lang-toggle button.active{background:rgba(255,255,255,.4)}"
                + ".content{max-width:1100px;margin:0 auto;padding:1.5rem 2rem}"
                + ".meta{color:#666;font-size:.85rem}"
                + ".dashboard-row{display:flex;gap:1rem;flex-wrap:wrap;margin-bottom:1.5rem}"
                + ".dist-panel{background:#fff;border:1px solid #e3e6eb;border-radius:12px;padding:1rem 1.25rem;flex:1;min-width:280px}"
                + ".panel-h{font-size:.8rem;text-transform:uppercase;letter-spacing:.05px;color:#475069;margin-bottom:.6rem}"
                + ".dist-bar{display:flex;height:18px;border-radius:999px;overflow:hidden;background:#eef1f5}"
                + ".dist-bar span{display:block;height:100%}"
                + ".dist-legend{display:flex;gap:14px;flex-wrap:wrap;margin-top:.6rem;font-size:.8rem;color:#475069}"
                + ".dist-legend .dot{display:inline-block;width:10px;height:10px;border-radius:3px;margin-right:5px}"
                + ".no-findings{color:#1E8E5A;font-weight:600}"
                + ".cards-container{display:flex;gap:.75rem;flex-wrap:wrap}"
                + ".summary-card{background:#fff;border:1px solid #e3e6eb;border-left:4px solid #888;border-radius:12px;"
                + "padding:.75rem 1rem;min-width:120px}"
                + ".summary-card .number{font-size:1.6rem;font-weight:700}"
                + ".summary-card .label{font-size:.72rem;text-transform:uppercase;color:#475069;letter-spacing:.5px}"
                + ".summary-card.red{border-left-color:#dc3545}.summary-card.orange{border-left-color:#fd7e14}"
                + ".summary-card.yellow{border-left-color:#ffc107}.summary-card.green{border-left-color:#28a745}"
                + ".summary-card.info{border-left-color:#17a2b8}"
                + ".section{margin-top:1.5rem}"
                + ".section-header{font-size:1.1rem;border-bottom:2px solid #e3e6eb;padding-bottom:.4rem}"
                + ".count{background:#1a1a2e;color:#fff;font-size:.75rem;border-radius:999px;padding:1px 9px;margin-left:6px}"
                + ".finding-card{background:#fff;border:1px solid #e3e6eb;border-radius:10px;padding:1rem 1.25rem;margin-top:.75rem}"
                + ".finding-header{display:flex;align-items:center;gap:10px;flex-wrap:wrap}"
                + ".severity-badge{color:#fff;font-size:.7rem;font-weight:700;text-transform:uppercase;padding:2px 9px;border-radius:999px}"
                + ".finding-title{font-weight:600}.finding-meta{color:#888;font-size:.8rem;margin-left:auto}"
                + ".business-impact{background:#fff8f0;border-left:3px solid #fd7e14;padding:.5rem .75rem;margin-top:.6rem;"
                + "border-radius:0 6px 6px 0;font-size:.88rem}"
                + ".dual-view{display:grid;grid-template-columns:1fr 1fr;gap:.75rem;margin-top:.75rem}"
                + ".evidence-panel{border:1px solid #e3e6eb;border-radius:8px;padding:.5rem .6rem;overflow:auto}"
                + ".evidence-panel.code{border-left:3px solid #0f3460}.evidence-panel.yaml-current{border-left:3px solid #dc3545}"
                + ".evidence-panel.yaml-fix{border-left:3px solid #28a745}"
                + ".ep-h{font-size:.72rem;text-transform:uppercase;color:#475069;margin-bottom:.3rem}"
                + ".ep-sub{color:#888;text-transform:none}"
                + ".evidence-panel pre{margin:0;white-space:pre-wrap;word-break:break-word}"
                + "code{font-family:JetBrains Mono,ui-monospace,monospace;font-size:.8rem}"
                + ".citation{color:#8a6a1e;font-size:.82rem;font-style:italic;margin-top:.5rem}"
                + ".yaml-section,.needs-attention,.blind-spots{margin-top:1.5rem;background:#fff;border:1px solid #e3e6eb;"
                + "border-radius:12px;padding:1rem 1.25rem}"
                + ".needs-attention{border-left:4px solid #17a2b8}.needs-attention .ns-intro{font-size:.88rem;color:#475069}"
                + ".blind-spots{border-left:4px solid #6b21a8}"
                + ".muted{color:#888}.foot{color:#9aa1ae;font-size:.78rem;margin-top:1.5rem}";
    }
}
