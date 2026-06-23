package ca.bnc.qe.veritas.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.persistence.Scan;
import org.springframework.stereotype.Component;

/**
 * Renders the management-facing API contract-validation report (modelled on the BNC contract-validator
 * report): a cover block, a plain-language <b>executive summary</b> with a <b>Contract Fidelity Score</b> and
 * KPIs, <b>risk &amp; business impact</b>, <b>recommended actions</b>, then the technical <b>detailed
 * findings</b> (dual-view: code | current YAML | proposed fix) and an <b>analysis-coverage</b> section.
 *
 * <p>Bilingual EN/FR (toggle in the interactive HTML; English default for PDF, French hidden via CSS).
 * Generation is 100% deterministic — the only LLM use is translating dynamic strings (see TranslationService),
 * supplied as the {@code fr} map. The distribution is a CSS stacked bar (no JS/SVG) so it renders in PDF too.
 */
@Component
public class ContractReportRenderer {

    private static final Map<String, String> SEVERITY_COLOR = Map.of(
            "BLOCKER", "#6B21A8", "CRITICAL", "#C2122D", "MAJOR", "#C2410C", "MINOR", "#CA8A04", "INFO", "#3A4658");

    public String renderHtml(Scan scan, List<Finding> findings) {
        return render(scan, findings, Map.of(), true);
    }

    /** Bilingual render: {@code fr} maps each English dynamic string to its French translation. */
    public String renderHtml(Scan scan, List<Finding> findings, Map<String, String> fr) {
        return render(scan, findings, fr == null ? Map.of() : fr, true);
    }

    public byte[] renderPdf(Scan scan, List<Finding> findings) {
        return renderPdf(scan, findings, Map.of());
    }

    public byte[] renderPdf(Scan scan, List<Finding> findings, Map<String, String> fr) {
        String html = render(scan, findings, fr == null ? Map.of() : fr, false);
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

    private boolean isNeedsAttention(Finding f) {
        return FidelityScore.isNeedsAttention(f);   // shared with the score so report + KPI never drift
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

    private String render(Scan scan, List<Finding> findings, Map<String, String> fr, boolean interactive) {
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
        List<Finding> counted = new ArrayList<>();
        counted.addAll(missing);
        counted.addAll(wrong);
        counted.addAll(dead);
        int score = FidelityScore.of(counted);
        long blocking = counted.stream().filter(f -> f.getSeverity() != null
                && (f.getSeverity().name().equals("BLOCKER") || f.getSeverity().name().equals("CRITICAL"))).count();
        String[] band = scoreBand(score);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
                .append("<title>API Contract Validation Report — ").append(esc(nz(scan.getServiceName())))
                .append("</title><style>").append(css()).append("</style></head><body class=\"lang-en\">");

        // ---- cover block ----
        sb.append("<div class=\"cover\"><div class=\"cover-in\">");
        if (interactive) {
            sb.append("<div class=\"lang-toggle\">")
                    .append("<button data-lang=\"en\" class=\"active\" onclick=\"setLang('en')\">EN</button>")
                    .append("<button data-lang=\"fr\" onclick=\"setLang('fr')\">FR</button></div>");
        }
        sb.append("<div class=\"brand\"><span class=\"mark\"></span> Veritas</div>");
        sb.append("<h1 class=\"doc-title\">").append(bi("API Contract Validation Report",
                "Rapport de validation du contrat d'API")).append("</h1>");
        sb.append("<div class=\"doc-sub\">").append(esc(nz(scan.getServiceName()))).append("</div>");
        sb.append("<div class=\"rating rating-").append(band[0]).append("\">").append(bi(band[1], band[2])).append("</div>");
        sb.append("<table class=\"cover-meta\">")
                .append(metaRow(bi("Service", "Service"), esc(nz(scan.getServiceName()))))
                .append(metaRow(bi("Specification source", "Source de la spécification"), esc(nz(scan.getSpecSources()))))
                .append(metaRow(bi("Generated", "Généré le"), scan.getStartedAt() != null ? esc(scan.getStartedAt().toString()) : "—"))
                .append(metaRow(bi("Prepared by", "Préparé par"), "Veritas — automated contract validation"))
                .append(metaRow(bi("Report ID", "Identifiant du rapport"), esc(nz(scan.getId()))))
                .append("</table>");
        sb.append("</div></div>");

        sb.append("<div class=\"content\">");

        // ---- Table of contents (anchors to each section present) ----
        boolean anyFix = findings.stream().anyMatch(f -> !isBlank(f.getProposedFix()));
        sb.append("<nav class=\"toc\"><div class=\"toc-h\">").append(bi("Contents", "Sommaire")).append("</div><ol>");
        sb.append(tocItem("sec-1", bi("Executive summary", "Sommaire exécutif")));
        sb.append(tocItem("sec-2", bi("Risk &amp; business impact", "Risque et impact d'affaires")));
        sb.append(tocItem("sec-3", bi("Recommended actions", "Actions recommandées")));
        if (!counted.isEmpty()) {
            sb.append(tocItem("sec-4", bi("Detailed findings", "Constats détaillés")));
        }
        if (anyFix) {
            sb.append(tocItem("sec-5", bi("Corrected OpenAPI specification", "Spécification OpenAPI corrigée")));
        }
        if (!attention.isEmpty()) {
            sb.append(tocItem("sec-6", bi("Items needing manual review", "Éléments à vérifier manuellement")));
        }
        sb.append(tocItem("sec-7", bi("Analysis coverage", "Couverture de l'analyse")));
        sb.append("</ol></nav>");

        // ---- 1. Executive summary ----
        sb.append("<section id=\"sec-1\"><h2>").append(bi("1. Executive summary", "1. Sommaire exécutif")).append("</h2>");
        sb.append("<p class=\"lead\">").append(bi(
                "This report compares the published OpenAPI contract for " + esc(nz(scan.getServiceName()))
                        + " against its Spring implementation. The deterministic analysis identified " + counted.size()
                        + " discrepanc" + (counted.size() == 1 ? "y" : "ies") + " (" + blocking
                        + " release-blocking), for a contract fidelity score of " + score + "/100 ("
                        + band[1].toLowerCase(java.util.Locale.ROOT) + ")."
                        + (attention.isEmpty() ? "" : " A further " + attention.size()
                        + " item(s) require manual review and are not scored."),
                "Ce rapport compare le contrat OpenAPI publié de " + esc(nz(scan.getServiceName()))
                        + " à son implémentation Spring. L'analyse déterministe a relevé " + counted.size()
                        + " écart(s) (" + blocking + " bloquant(s) pour la livraison), pour un score de fidélité du "
                        + "contrat de " + score + "/100 (" + band[2].toLowerCase(java.util.Locale.ROOT) + ")."
                        + (attention.isEmpty() ? "" : " " + attention.size()
                        + " élément(s) supplémentaire(s) nécessitent une revue manuelle et ne sont pas notés.")))
                .append("</p>");

        // Quality gate (pass/fail vs threshold) + trend vs the previous scan — the two things management acts on.
        boolean pass = score >= FidelityScore.PASS_THRESHOLD;
        sb.append("<p class=\"gate gate-").append(pass ? "pass" : "fail").append("\">").append(bi(
                "Quality gate: " + (pass ? "PASS" : "FAIL") + " — fidelity " + score + " vs target ≥ " + FidelityScore.PASS_THRESHOLD,
                "Seuil qualité : " + (pass ? "RÉUSSI" : "ÉCHEC") + " — fidélité " + score + " vs cible ≥ " + FidelityScore.PASS_THRESHOLD))
                .append("</p>");
        if (scan.getPreviousFidelityScore() != null) {
            int prev = scan.getPreviousFidelityScore();
            int delta = score - prev;
            String arrow = delta > 0 ? "▲" : (delta < 0 ? "▼" : "●");
            String tcls = delta > 0 ? "up" : (delta < 0 ? "down" : "flat");
            String sign = delta >= 0 ? "+" : "";
            sb.append("<p class=\"trend trend-").append(tcls).append("\">").append(bi(
                    "Trend: " + arrow + " " + sign + delta + " vs previous scan (was " + prev + ")",
                    "Tendance : " + arrow + " " + sign + delta + " vs analyse précédente (était " + prev + ")"))
                    .append("</p>");
        }

        // KPI scorecard
        sb.append("<div class=\"kpis\">");
        sb.append(kpi(score + "<span class=\"unit\">/100</span>", bi("Contract fidelity", "Fidélité du contrat"), band[0]));
        sb.append(kpi(String.valueOf(blocking), bi("Release-blocking", "Bloquants"), blocking > 0 ? "action" : "clean"));
        sb.append(kpi(String.valueOf(counted.size()), bi("Total findings", "Constats totaux"), "neutral"));
        // Coverage = deterministic per-scan gaps (unparsed files / unresolved types). Fall back to the
        // blind-spots text only for older scans that predate the coverageGaps count.
        int gaps = scan.getCoverageGaps() != null ? scan.getCoverageGaps()
                : (isBlank(scan.getBlindSpots()) ? 0 : 1);
        String covValue = gaps == 0 ? bi("Full", "Complète")
                : bi(gaps + (gaps == 1 ? " gap" : " gaps"), gaps + (gaps == 1 ? " lacune" : " lacunes"));
        sb.append(kpi(covValue, bi("Analysis coverage", "Couverture"), gaps == 0 ? "clean" : "minor"));
        sb.append("</div>");

        sb.append("<div class=\"dist-panel\"><div class=\"panel-h\">")
                .append(bi("Findings by severity", "Constats par sévérité")).append("</div>")
                .append(distributionBar(missing, wrong, dead)).append("</div>");
        sb.append("</section>");

        // ---- 2. Risk & business impact ----
        sb.append("<section id=\"sec-2\"><h2>").append(bi("2. Risk &amp; business impact", "2. Risque et impact d'affaires")).append("</h2>");
        sb.append("<table class=\"impact\"><thead><tr><th>").append(bi("Severity", "Sévérité")).append("</th><th>")
                .append(bi("Count", "Nombre")).append("</th><th>").append(bi("What it means", "Ce que cela implique"))
                .append("</th></tr></thead><tbody>");
        for (String sev : new String[] {"BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO"}) {
            long n = counted.stream().filter(f -> f.getSeverity() != null && f.getSeverity().name().equals(sev)).count();
            if (n == 0) {
                continue;
            }
            String[] impact = businessImpact(sev);
            sb.append("<tr><td><span class=\"sev-pill\" style=\"background:").append(SEVERITY_COLOR.get(sev))
                    .append("\">").append(cap(sev)).append("</span></td><td>").append(n).append("</td><td>")
                    .append(bi(impact[0], impact[1])).append("</td></tr>");
        }
        sb.append("</tbody></table>");
        if (counted.isEmpty()) {
            sb.append("<p class=\"cov-ok\">").append(bi("No contract discrepancies were found.",
                    "Aucun écart de contrat n'a été relevé.")).append("</p>");
        }
        sb.append("</section>");

        // ---- 3. Recommended actions ----
        List<String[]> actions = recommendations(missing.size(), wrong.size(), dead.size(), attention.size());
        sb.append("<section id=\"sec-3\"><h2>").append(bi("3. Recommended actions", "3. Actions recommandées")).append("</h2>")
                .append("<table class=\"actions\"><thead><tr><th>#</th><th>").append(bi("Action", "Action"))
                .append("</th><th>").append(bi("Priority", "Priorité")).append("</th><th>").append(bi("Owner", "Responsable"))
                .append("</th><th>").append(bi("Target date", "Échéance")).append("</th></tr></thead><tbody>");
        int ai = 1;
        for (String[] a : actions) {
            String pr = ai == 1 ? bi("High", "Élevée") : (ai == 2 ? bi("Medium", "Moyenne") : bi("Normal", "Normale"));
            sb.append("<tr><td>").append(ai).append("</td><td>").append(bi(a[0], a[1])).append("</td><td>").append(pr)
                    .append("</td><td class=\"tbd\">").append(bi("TBD", "À définir")).append("</td>")
                    .append("<td class=\"tbd\">").append(bi("TBD", "À définir")).append("</td></tr>");
            ai++;
        }
        sb.append("</tbody></table></section>");

        // ---- 4. Detailed findings ----
        if (!counted.isEmpty()) {
            sb.append("<section id=\"sec-4\"><h2>").append(bi("4. Detailed findings", "4. Constats détaillés")).append("</h2>");
            sb.append(subsection(bi("4.1 Missing from the specification", "4.1 Manquant dans la spécification"), missing, fr));
            sb.append(subsection(bi("4.2 Contract mismatches", "4.2 Incohérences du contrat"), wrong, fr));
            sb.append(subsection(bi("4.3 Dead spec (documented, not in code)", "4.3 Spéc. morte (documentée, absente du code)"), dead, fr));
            sb.append("</section>");
        }

        // corrected YAML link (anyFix computed above for the TOC)
        if (anyFix) {
            sb.append("<section id=\"sec-5\"><h2>").append(bi("5. Corrected OpenAPI specification", "5. Spécification OpenAPI corrigée"))
                    .append("</h2><p><a href=\"./").append(esc(slug(scan.getServiceName())))
                    .append("_corrected.yaml\" target=\"_blank\" rel=\"noreferrer\">")
                    .append(bi("Download the corrected OpenAPI YAML", "Télécharger le YAML OpenAPI corrigé"))
                    .append("</a> ").append(bi("(drop-in replacement reflecting the code).",
                            "(remplacement direct reflétant le code).")).append("</p></section>");
        }

        // ---- needs attention (not counted) ----
        if (!attention.isEmpty()) {
            sb.append("<section id=\"sec-6\" class=\"needs-attention\"><h2>")
                    .append(bi("6. Items needing manual review", "6. Éléments à vérifier manuellement")).append("</h2>")
                    .append("<p class=\"ns-intro\">").append(bi(
                            "Raised by the assistant, not deterministically proven — <strong>not counted</strong> in the "
                                    + "score or severity totals. Please confirm manually.",
                            "Soulevés par l'assistant, non prouvés de façon déterministe — <strong>non comptés</strong> "
                                    + "dans le score ni les totaux. À confirmer manuellement."))
                    .append("</p>");
            for (Finding f : attention) {
                sb.append(findingCard(f, fr));
            }
            sb.append("</section>");
        }

        // ---- analysis coverage (deterministic limitations) ----
        sb.append("<section id=\"sec-7\" class=\"blind-spots\"><h2>").append(bi("7. Analysis coverage", "7. Couverture de l'analyse")).append("</h2>");
        if (!isBlank(scan.getBlindSpots())) {
            sb.append("<p class=\"cov-warn\">").append(bi(
                            "Limitations — these areas could not be fully analysed, so the absence of a finding here is "
                                    + "not a guarantee:",
                            "Limites — ces zones n'ont pas pu être pleinement analysées; l'absence de constat n'est donc "
                                    + "pas une garantie :"))
                    .append("</p><p>").append(biDyn(scan.getBlindSpots(), fr)).append("</p>");
        } else if (CoverageReconciler.anyMissingSourceDisclaimer(attention)) {
            // A manual-review item still flags an unsupplied source — §7 must not claim full coverage and contradict it.
            sb.append("<p class=\"cov-warn\">").append(bi(
                    "Partial coverage — a manual-review item above notes a source that was not fully analysed.",
                    "Couverture partielle — un élément à vérifier ci-dessus signale une source non pleinement analysée."))
                    .append("</p>");
        } else {
            sb.append("<p class=\"cov-ok\">").append(bi(
                    "Full coverage — all sources parsed and referenced types resolved.",
                    "Couverture complète — toutes les sources analysées et les types référencés résolus.")).append("</p>");
        }
        sb.append("</section>");

        sb.append("<p class=\"foot\">").append(bi(
                        "Generated by Veritas. Findings for L1–L4 are deterministic (static analysis, no AI); "
                                + "explanations and proposed fixes may be AI-assisted. Confidential — internal use.",
                        "Généré par Veritas. Les constats L1–L4 sont déterministes (analyse statique, sans IA); les "
                                + "explications et correctifs proposés peuvent être assistés par IA. Confidentiel — usage interne."))
                .append(" · ").append(bi("Est. cost", "Coût est.")).append(String.format(" $%.4f", scan.getTotalEstCostUsd()));
        if (scan.getConfidence() != null && scan.getConfidence() > 0) {
            sb.append(" · ").append(bi(
                    "AI-assist confidence: " + Math.round(scan.getConfidence())
                            + "% (the assistant's confidence in its own explanations — not a measure of contract correctness)",
                    "Confiance de l'assistance IA : " + Math.round(scan.getConfidence())
                            + "% (confiance de l'assistant dans ses propres explications — pas une mesure de l'exactitude du contrat)"));
        }
        sb.append("</p>");
        sb.append("</div>");   // content

        if (interactive) {
            sb.append("<script>function setLang(l){document.body.className='lang-'+l;")
                    .append("document.querySelectorAll('.lang-toggle button').forEach(function(b){")
                    .append("b.classList.toggle('active', b.getAttribute('data-lang')===l);});}</script>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    // ---- sections / cards -----------------------------------------------------------------------------------

    private String subsection(String title, List<Finding> items, Map<String, String> fr) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<h3 class=\"subhead\">").append(title)
                .append(" <span class=\"count\">").append(items.size()).append("</span></h3>");
        for (Finding f : items) {
            sb.append(findingCard(f, fr));
        }
        return sb.toString();
    }

    private String findingCard(Finding f, Map<String, String> fr) {
        String color = SEVERITY_COLOR.getOrDefault(f.getSeverity() != null ? f.getSeverity().name() : "", "#6B7280");
        StringBuilder sb = new StringBuilder("<div class=\"finding-card\">");
        sb.append("<div class=\"finding-header\">")
                .append("<span class=\"severity-badge\" style=\"background:").append(color).append("\">")
                .append(esc(f.getSeverity() != null ? f.getSeverity().name() : "")).append("</span>")
                .append("<span class=\"finding-title\">").append(biDyn(nz(f.getSummary()), fr)).append("</span>")
                .append("<span class=\"finding-meta\"><code>").append(esc(nz(f.getEndpoint()))).append("</code>")
                .append(f.getLayer() != null ? " · " + f.getLayer().name() : "")
                .append(f.getConfidence() != null ? " · " + f.getConfidence().name() : "").append("</span>")
                .append("</div>");
        if (!isBlank(f.getExplanation())) {
            sb.append("<div class=\"business-impact\">").append(biDyn(f.getExplanation(), fr)).append("</div>");
        }
        String snippet = f.getCodeEvidence() != null ? f.getCodeEvidence().snippet() : null;
        String currentYaml = f.getCurrentYamlFragment();
        String proposedFix = f.getProposedFix();
        String citation = f.getCitation();
        if (!isBlank(snippet) || !isBlank(currentYaml) || !isBlank(proposedFix)) {
            sb.append("<div class=\"dual-view\">");
            if (!isBlank(snippet)) {
                sb.append(panel("code", bi("Code evidence", "Preuve dans le code"), loc(f),
                        "<pre><code>" + esc(snippet) + "</code></pre>"));
            }
            if (!isBlank(currentYaml)) {
                sb.append(panel("yaml-current", bi("Current YAML", "YAML actuel"), "",
                        "<pre><code>" + esc(currentYaml) + "</code></pre>"));
            }
            if (!isBlank(proposedFix)) {
                sb.append(panel("yaml-fix", bi("Proposed fix", "Correctif proposé"), "",
                        "<div class=\"fix\">" + biDyn(proposedFix, fr) + "</div>"));
            }
            sb.append("</div>");
        }
        if (!isBlank(citation)) {
            sb.append("<div class=\"citation\">").append(bi("Reference", "Référence")).append(": ")
                    .append(esc(citation)).append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private String panel(String cls, String label, String sub, String body) {
        return "<div class=\"evidence-panel " + cls + "\"><div class=\"ep-h\">" + label
                + (isBlank(sub) ? "" : " <span class=\"ep-sub\">" + esc(sub) + "</span>") + "</div>" + body + "</div>";
    }

    private String distributionBar(List<Finding> missing, List<Finding> wrong, List<Finding> dead) {
        List<Finding> all = new ArrayList<>();
        all.addAll(missing);
        all.addAll(wrong);
        all.addAll(dead);
        int total = all.size();
        if (total == 0) {
            return "<div class=\"cov-ok\">" + bi("No counted findings — contract is clean.",
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
            bar.append("<span style=\"width:").append(pct).append("%;background:").append(SEVERITY_COLOR.get(sev)).append("\"></span>");
            legend.append("<span class=\"li\"><span class=\"dot\" style=\"background:").append(SEVERITY_COLOR.get(sev))
                    .append("\"></span>").append(cap(sev)).append(" ").append(n).append("</span>");
        }
        return bar.append("</div>").append(legend).append("</div>").toString();
    }

    // ---- deterministic narrative helpers (no LLM) -----------------------------------------------------------

    /** {cssClass, EN label, FR label}. */
    private String[] scoreBand(int s) {
        if (s >= 90) {
            return new String[] {"clean", "Excellent", "Excellent"};
        }
        if (s >= 75) {
            return new String[] {"good", "Good", "Bon"};
        }
        if (s >= 50) {
            return new String[] {"minor", "Needs work", "À améliorer"};
        }
        return new String[] {"action", "At risk", "À risque"};
    }

    private String[] businessImpact(String sev) {
        return switch (sev) {
            case "BLOCKER" -> new String[] {"Release-blocking: consumers would integrate against an incorrect contract.",
                    "Bloquant pour la livraison : les consommateurs intègreraient un contrat incorrect."};
            case "CRITICAL" -> new String[] {"High risk of integration failures and undocumented behaviour reaching clients.",
                    "Risque élevé d'échecs d'intégration et de comportements non documentés exposés aux clients."};
            case "MAJOR" -> new String[] {"Likely to cause client confusion or gaps in test coverage.",
                    "Susceptible de créer de la confusion chez les clients ou des lacunes de couverture de tests."};
            case "MINOR" -> new String[] {"Low-risk drift; address opportunistically.",
                    "Dérive à faible risque; à corriger au besoin."};
            default -> new String[] {"Advisory / design observation.", "Indicatif / observation de conception."};
        };
    }

    private List<String[]> recommendations(int missing, int wrong, int dead, int attention) {
        List<String[]> r = new ArrayList<>();
        if (missing > 0) {
            r.add(new String[] {"Document the " + missing + " undocumented endpoint(s)/field(s) in the OpenAPI specification.",
                    "Documenter les " + missing + " point(s) de terminaison/champ(s) non documentés dans la spécification OpenAPI."});
        }
        if (wrong > 0) {
            r.add(new String[] {"Reconcile the " + wrong + " contract mismatch(es) so the spec reflects the code's actual behaviour.",
                    "Résoudre les " + wrong + " incohérence(s) afin que la spécification reflète le comportement réel du code."});
        }
        if (dead > 0) {
            r.add(new String[] {"Remove or restore the " + dead + " dead spec entr(ies) that no longer match the code.",
                    "Retirer ou restaurer les " + dead + " entrée(s) de spéc. morte qui ne correspondent plus au code."});
        }
        if (attention > 0) {
            r.add(new String[] {"Review the " + attention + " item(s) flagged for manual attention.",
                    "Examiner les " + attention + " élément(s) signalés pour vérification manuelle."});
        }
        if (missing > 0 || wrong > 0) {
            r.add(new String[] {"Apply the corrected OpenAPI specification provided with this report.",
                    "Appliquer la spécification OpenAPI corrigée fournie avec ce rapport."});
        }
        r.add(new String[] {"Re-run validation after the fixes to confirm a clean contract.",
                "Relancer la validation après correction pour confirmer un contrat conforme."});
        return r;
    }

    // ---- small helpers --------------------------------------------------------------------------------------

    private String metaRow(String label, String value) {
        return "<tr><th>" + label + "</th><td>" + value + "</td></tr>";
    }

    private String kpi(String value, String label, String tone) {
        return "<div class=\"kpi kpi-" + tone + "\"><div class=\"kpi-v\">" + value + "</div>"
                + "<div class=\"kpi-l\">" + label + "</div></div>";
    }

    private String tocItem(String id, String label) {
        return "<li><a href=\"#" + id + "\">" + label + "</a></li>";
    }

    private String bi(String en, String fr) {
        return "<span class=\"en\">" + en + "</span><span class=\"fr\">" + fr + "</span>";
    }

    private String biDyn(String text, Map<String, String> fr) {
        String en = nz(text);
        String french = fr.getOrDefault(en, en);
        return "<span class=\"en\">" + esc(en) + "</span><span class=\"fr\">" + esc(french) + "</span>";
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
        return "*{box-sizing:border-box}"
                + "body{font-family:Inter,Segoe UI,system-ui,sans-serif;color:#1a1a2e;margin:0;background:#f5f6fa;line-height:1.55}"
                + ".lang-en .fr{display:none}.lang-fr .en{display:none}"
                + ".cover{background:linear-gradient(135deg,#1a1a2e 0%,#16213e 50%,#0f3460 100%);color:#fff;padding:2.5rem 0}"
                + ".cover-in{max-width:900px;margin:0 auto;padding:0 2rem;position:relative}"
                + ".brand{font-weight:600;letter-spacing:.5px;opacity:.9;display:flex;align-items:center;gap:8px}"
                + ".mark{width:22px;height:22px;border-radius:6px;background:#e94560;display:inline-block}"
                + ".doc-title{font-size:1.9rem;font-weight:600;margin:.8rem 0 .2rem}"
                + ".doc-sub{font-size:1.05rem;opacity:.85;margin-bottom:1rem}"
                + ".lang-toggle{position:absolute;top:0;right:2rem;display:flex;gap:6px}"
                + ".lang-toggle button{background:rgba(255,255,255,.15);color:#fff;border:1px solid rgba(255,255,255,.4);"
                + "padding:.3rem .8rem;border-radius:6px;cursor:pointer;font-size:.85rem;font-weight:600}"
                + ".lang-toggle button.active{background:rgba(255,255,255,.4)}"
                + ".rating{display:inline-block;font-weight:700;font-size:.8rem;padding:.35rem .9rem;border-radius:999px;"
                + "text-transform:uppercase;letter-spacing:.5px;color:#fff}"
                + ".rating-clean{background:#1E8E5A}.rating-good{background:#2f8fbf}.rating-minor{background:#b5852a}.rating-action{background:#C2122D}"
                + ".cover-meta{margin-top:1.2rem;border-collapse:collapse;font-size:.85rem}"
                + ".cover-meta th{text-align:left;color:rgba(255,255,255,.7);font-weight:400;padding:3px 18px 3px 0;vertical-align:top}"
                + ".cover-meta td{padding:3px 0;color:#fff}"
                + ".content{max-width:900px;margin:0 auto;padding:1.5rem 2rem 3rem}"
                + ".toc{margin:0 0 1rem;padding:1rem 1.2rem;background:#F7F9FC;border:1px solid #E3E6EB;border-radius:10px}"
                + ".toc-h{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;color:#6B7280;margin-bottom:.4rem}"
                + ".toc ol{margin:0;padding-left:1.2rem;columns:2;font-size:13px}"
                + ".toc a{color:#8A6A1E;text-decoration:none}.toc a:hover{text-decoration:underline}"
                + "section{margin-top:1.8rem;scroll-margin-top:1rem}"
                + "h2{font-size:1.2rem;font-weight:600;border-bottom:2px solid #e3e6eb;padding-bottom:.4rem;margin-bottom:.8rem}"
                + "h3.subhead{font-size:1rem;font-weight:600;margin:1.1rem 0 .4rem;color:#0f3460}"
                + ".lead{font-size:1rem}"
                + ".kpis{display:flex;gap:.9rem;flex-wrap:wrap;margin:1rem 0}"
                + ".kpi{flex:1;min-width:150px;background:#fff;border:1px solid #e3e6eb;border-top:3px solid #888;border-radius:10px;padding:.9rem 1.1rem}"
                + ".kpi-v{font-size:1.8rem;font-weight:700}.kpi-v .unit{font-size:.9rem;font-weight:400;color:#888}"
                + ".kpi-l{font-size:.72rem;text-transform:uppercase;color:#475069;letter-spacing:.5px}"
                + ".kpi-clean{border-top-color:#1E8E5A}.kpi-good{border-top-color:#2f8fbf}.kpi-action{border-top-color:#C2122D}"
                + ".kpi-minor{border-top-color:#b5852a}.kpi-neutral{border-top-color:#3A4658}"
                + ".dist-panel{background:#fff;border:1px solid #e3e6eb;border-radius:10px;padding:1rem 1.25rem}"
                + ".panel-h{font-size:.75rem;text-transform:uppercase;letter-spacing:.5px;color:#475069;margin-bottom:.6rem}"
                + ".dist-bar{display:flex;height:18px;border-radius:999px;overflow:hidden;background:#eef1f5}"
                + ".dist-bar span{display:block;height:100%}"
                + ".dist-legend{display:flex;gap:14px;flex-wrap:wrap;margin-top:.6rem;font-size:.8rem;color:#475069}"
                + ".dist-legend .dot{display:inline-block;width:10px;height:10px;border-radius:3px;margin-right:5px}"
                + "table.impact{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e3e6eb;border-radius:10px;overflow:hidden}"
                + "table.impact th,table.impact td{text-align:left;padding:9px 12px;font-size:.88rem;border-top:1px solid #eef1f5;vertical-align:top}"
                + "table.impact thead th{background:#f1f3f6;color:#475069;font-size:.72rem;text-transform:uppercase;border-top:0}"
                + ".sev-pill{color:#fff;font-size:.7rem;font-weight:700;text-transform:uppercase;padding:2px 9px;border-radius:999px}"
                + ".gate{display:inline-block;font-weight:700;font-size:.9rem;padding:.4rem .9rem;border-radius:8px;margin:.3rem 0}"
                + ".gate-pass{background:#e6f4ea;color:#1E8E5A}.gate-fail{background:#fdecef;color:#C2122D}"
                + ".trend{font-size:.85rem;margin:.2rem 0}.trend-up{color:#1E8E5A}.trend-down{color:#C2122D}.trend-flat{color:#475069}"
                + "table.actions{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e3e6eb;border-radius:10px;overflow:hidden}"
                + "table.actions th,table.actions td{text-align:left;padding:8px 12px;font-size:.86rem;border-top:1px solid #eef1f5;vertical-align:top}"
                + "table.actions thead th{background:#f1f3f6;color:#475069;font-size:.72rem;text-transform:uppercase;border-top:0}"
                + "table.actions .tbd{color:#9aa1ae;font-style:italic}"
                + ".finding-card{background:#fff;border:1px solid #e3e6eb;border-radius:10px;padding:1rem 1.25rem;margin-top:.7rem}"
                + ".finding-header{display:flex;align-items:center;gap:10px;flex-wrap:wrap}"
                + ".severity-badge{color:#fff;font-size:.7rem;font-weight:700;text-transform:uppercase;padding:2px 9px;border-radius:999px}"
                + ".finding-title{font-weight:600}.finding-meta{color:#888;font-size:.8rem;margin-left:auto}"
                + ".business-impact{background:#fff8f0;border-left:3px solid #fd7e14;border-radius:0 6px 6px 0;padding:.5rem .75rem;margin-top:.6rem;font-size:.88rem}"
                + ".dual-view{display:grid;grid-template-columns:1fr 1fr;gap:.75rem;margin-top:.75rem}"
                + ".evidence-panel{border:1px solid #e3e6eb;border-radius:8px;padding:.5rem .6rem;overflow:auto}"
                + ".evidence-panel.code{border-left:3px solid #0f3460}.evidence-panel.yaml-current{border-left:3px solid #dc3545}"
                + ".evidence-panel.yaml-fix{border-left:3px solid #28a745}"
                + ".ep-h{font-size:.72rem;text-transform:uppercase;color:#475069;margin-bottom:.3rem}.ep-sub{color:#888;text-transform:none}"
                + ".evidence-panel pre{margin:0;white-space:pre-wrap;word-break:break-word}"
                + "code{font-family:JetBrains Mono,ui-monospace,monospace;font-size:.8rem}"
                + ".citation{color:#8a6a1e;font-size:.82rem;font-style:italic;margin-top:.5rem}"
                + ".count{background:#1a1a2e;color:#fff;font-size:.72rem;border-radius:999px;padding:1px 9px;margin-left:6px}"
                + ".needs-attention .ns-intro{font-size:.88rem;color:#475069}"
                + ".cov-warn{color:#C2410C;font-size:.9rem}.cov-ok{color:#1E8E5A;font-size:.9rem;font-weight:500}"
                + ".foot{color:#9aa1ae;font-size:.78rem;margin-top:2rem;border-top:1px solid #e3e6eb;padding-top:.8rem}";
    }
}
