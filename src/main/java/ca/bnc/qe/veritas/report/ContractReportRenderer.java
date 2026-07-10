package ca.bnc.qe.veritas.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.BuildInfoService;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.vcs.BitbucketLinkBuilder;
import org.springframework.beans.factory.annotation.Autowired;
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
            "BLOCKER", "#6B21A8", "CRITICAL", "#C2122D", "MAJOR", "#C2410C", "MINOR", "#CA8A04", "INFO", "#3A4658",
            "UNSPECIFIED", "#6B7280");

    /** Optional — when Spring-injected, code evidence becomes a clickable Bitbucket deep link. Null in the
     *  no-arg constructor (used by tests), where evidence renders as plain text. */
    private final BitbucketLinkBuilder linkBuilder;

    /** The running build's stamp (short git SHA + build time), shown in the footer so a report self-identifies the
     *  build that produced it. Null/blank in the no-arg constructor (tests) → the footer simply omits it. */
    private final String buildStamp;

    /** The release quality-gate thresholds. Defaults (0/0/0) in the test constructors. */
    private final ca.bnc.qe.veritas.config.GateProperties gate;

    public ContractReportRenderer() {
        this(null, null);
    }

    public ContractReportRenderer(BitbucketLinkBuilder linkBuilder) {
        this(linkBuilder, null);
    }

    public ContractReportRenderer(BitbucketLinkBuilder linkBuilder, BuildInfoService buildInfo) {
        this(linkBuilder, buildInfo, new ca.bnc.qe.veritas.config.GateProperties());
    }

    @Autowired
    public ContractReportRenderer(BitbucketLinkBuilder linkBuilder, BuildInfoService buildInfo,
                                  ca.bnc.qe.veritas.config.GateProperties gate) {
        this.linkBuilder = linkBuilder;
        this.buildStamp = buildInfo == null ? null : buildInfo.stamp();
        this.gate = gate == null ? new ca.bnc.qe.veritas.config.GateProperties() : gate;
    }

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
        // Shared with the release gate so the report split + the KPI page never drift.
        return ca.bnc.qe.veritas.finding.CountedFindings.isNeedsAttention(f);
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
        // One shared verdict computation (PASS/WARN/FAIL + blocking / breaking / disputed counts) — the executive
        // dashboard consumes the same ReleaseVerdict, so the report and the KPI page can never disagree.
        ca.bnc.qe.veritas.contract.ReleaseVerdict verdict = ca.bnc.qe.veritas.contract.ReleaseVerdict.of(findings, gate);
        String gateVerdict = verdict.releaseSafe();   // PASS | WARN | FAIL
        long blocking = verdict.blocking();
        long disputed = verdict.aiDisputed();
        String[] band = verdictBand(gateVerdict);   // [css-class, EN label, FR label] for the cover badge + KPI

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
                .append("<title>API Contract Validation Report — ").append(esc(nz(scan.getServiceName())))
                .append("</title><style>").append(css()).append("</style></head><body class=\"lang-en\">");

        // Floating language toggle — fixed to the viewport so it's always one click away while scrolling.
        if (interactive) {
            sb.append("<div class=\"lang-toggle\" title=\"Language / Langue\">")
                    .append("<button type=\"button\" data-lang=\"en\" class=\"active\" onclick=\"setLang('en')\">EN</button>")
                    .append("<button type=\"button\" data-lang=\"fr\" onclick=\"setLang('fr')\">FR</button></div>");
        }

        // ---- cover block ----
        sb.append("<div class=\"cover\"><div class=\"cover-in\">");
        sb.append("<div class=\"brand\"><span class=\"mark\"></span> Veritas</div>");
        sb.append("<h1 class=\"doc-title\">").append(bi("API Contract Validation Report",
                "Rapport de validation du contrat d'API")).append("</h1>");
        sb.append("<div class=\"doc-sub\">").append(esc(nz(scan.getServiceName()))).append("</div>");
        sb.append("<div class=\"rating rating-").append(band[0]).append("\">").append(bi(band[1], band[2])).append("</div>");
        sb.append("<table class=\"meta-grid\"><tbody>")
                .append("<tr>")
                .append(metaCell(bi("Service", "Service"), esc(nz(scan.getServiceName()))))
                .append(metaCell(bi("Specification source", "Source de la spécification"), esc(nz(scan.getSpecSources()))))
                .append("</tr><tr>")
                .append(metaCell(bi("Generated", "Généré le"), fmtDate(scan.getStartedAt())))
                .append(metaCell(bi("Prepared by", "Préparé par"), "Veritas · automated contract validation"))
                .append("</tr><tr>")
                .append("<td class=\"meta-cell\" colspan=\"2\"><div class=\"meta-label\">")
                .append(bi("Report ID", "Identifiant du rapport")).append("</div><div class=\"meta-value mono\">")
                .append(esc(nz(scan.getId()))).append("</div></td>")
                .append("</tr></tbody></table>");
        sb.append("</div></div>");

        sb.append("<div class=\"content\">");

        // ---- Bottom line: the one-glance "is it safe to release?" verdict (deterministic from the gate) ----
        sb.append(bottomLine(verdict, missing.size(), wrong.size(), dead.size()));

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
                "This report compares the published API spec for " + esc(nz(scan.getServiceName()))
                        + " against what the code actually does. Automated code analysis found " + counted.size()
                        + " difference" + (counted.size() == 1 ? "" : "s") + " (" + blocking
                        + " release-blocking, " + verdict.breaking() + " that would break a running consumer)."
                        + (attention.isEmpty() ? "" : " A further " + attention.size()
                        + " item(s) need manual review and are not gated."),
                "Ce rapport compare la spécification d'API publiée de " + esc(nz(scan.getServiceName()))
                        + " à ce que fait réellement le code. L'analyse automatisée du code a relevé " + counted.size()
                        + " différence(s) (" + blocking + " bloquant(s), " + verdict.breaking()
                        + " qui briserai(en)t un consommateur en production)."
                        + (attention.isEmpty() ? "" : " " + attention.size()
                        + " élément(s) supplémentaire(s) nécessitent une revue manuelle et ne sont pas évalués.")))
                .append("</p>");

        // Quality gate — a categorical PASS/WARN/FAIL from severity counts (the SAME ReleaseVerdict the dashboard
        // shows), not a composite score: FAIL on any consumer-breaking finding, WARN on additive drift only, PASS clean.
        String gateCls = gateVerdict.equals("FAIL") ? "fail" : gateVerdict.equals("WARN") ? "warn" : "pass";
        // Phrasing is conditioned on the ACTUAL breaking count, not just the verdict string: once a gate cap is raised
        // above 0 a WARN/PASS scan can still carry breaking findings, so we must not hard-assert "no breaking changes".
        long gateBreaking = verdict.breaking();
        String gateEn = gateVerdict.equals("FAIL")
                ? "Quality gate: FAIL — " + gateBreaking + " finding(s) would break a running consumer; do not release"
                : gateVerdict.equals("WARN")
                ? "Quality gate: WARN — " + (gateBreaking == 0
                        ? "no breaking changes; " + counted.size() + " item(s) of additive drift to clean up"
                        : gateBreaking + " breaking change(s) within the configured cap; " + counted.size() + " item(s) to clean up")
                : "Quality gate: PASS — " + (gateBreaking == 0 ? "no breaking changes"
                        : gateBreaking + " breaking change(s) within the configured cap");
        String gateFr = gateVerdict.equals("FAIL")
                ? "Seuil qualité : ÉCHEC — " + gateBreaking + " constat(s) briserai(en)t un consommateur; ne pas livrer"
                : gateVerdict.equals("WARN")
                ? "Seuil qualité : ATTENTION — " + (gateBreaking == 0
                        ? "aucun changement cassant; " + counted.size() + " écart(s) additif(s) à corriger"
                        : gateBreaking + " changement(s) cassant(s) dans la limite configurée; " + counted.size() + " écart(s) à corriger")
                : "Seuil qualité : RÉUSSI — " + (gateBreaking == 0 ? "aucun changement cassant"
                        : gateBreaking + " changement(s) cassant(s) dans la limite configurée");
        sb.append("<p class=\"gate gate-").append(gateCls).append("\">").append(bi(gateEn, gateFr)).append("</p>");
        // Fail-safe callout: a not-yet-classified finding type holds the verdict at WARN until a human sets its severity.
        if (verdict.unspecified() > 0) {
            sb.append("<p class=\"gate gate-warn\">").append(bi(
                    verdict.unspecified() + " finding(s) of an unclassified type need a human to set a severity before "
                            + "the gate can fully judge this release.",
                    verdict.unspecified() + " constat(s) d'un type non classé nécessitent qu'un humain définisse une "
                            + "sévérité avant l'évaluation complète du seuil.")).append("</p>");
        }

        // Surface undocumented error responses (500/406/…) that were DEMOTED to manual review (§6) as low-confidence
        // advice-origin statuses (global @ControllerAdvice or controller-local @ExceptionHandler) — so the team is
        // still told, prominently, that those error statuses exist even though they don't count toward the score.
        // Renders for both the interactive and PDF paths (same code path here).
        sb.append(errorContractNote(attention));

        // KPI scorecard (built once, then laid out next to the severity breakdown for a single management snapshot).
        // Coverage = deterministic per-scan gaps (unparsed files / unresolved types); fall back to the blind-spots
        // text only for older scans that predate the coverageGaps count.
        int gaps = scan.getCoverageGaps() != null ? scan.getCoverageGaps()
                : (isBlank(scan.getBlindSpots()) ? 0 : 1);
        String covValue = gaps == 0 ? bi("Full", "Complète")
                : bi(gaps + (gaps == 1 ? " gap" : " gaps"), gaps + (gaps == 1 ? " lacune" : " lacunes"));
        String kpis = kpi(bi(band[1], band[2]), bi("Release gate", "Seuil de livraison"), band[0])
                + kpi(String.valueOf(verdict.breaking()), bi("Consumer-breaking", "Cassants"), verdict.breaking() > 0 ? "action" : "clean")
                + kpi(String.valueOf(counted.size()), bi("Total findings", "Constats totaux"), "neutral")
                + kpi(covValue, bi("Analysis coverage", "Couverture"), gaps == 0 ? "clean" : "minor");
        String vizLabel = "<div class=\"panel-h\">" + bi("Findings by severity", "Constats par sévérité") + "</div>";
        if (interactive) {
            // One executive snapshot: the severity donut and the KPIs side by side, so management reads the score and
            // the breakdown in a single block.
            sb.append("<div class=\"exec-snapshot\">")
                    .append("<div class=\"snapshot-viz\">").append(vizLabel)
                    .append(severityDonut(counted, "findings", "constats")).append("</div>")
                    .append("<div class=\"kpis snapshot-kpis\">").append(kpis).append("</div>")
                    .append("</div>");
        } else {
            // PDF: keep the stacked layout (the engine has no SVG/conic-gradient/flex-grid support).
            sb.append("<div class=\"kpis\">").append(kpis).append("</div>");
            sb.append("<div class=\"dist-panel\">").append(vizLabel)
                    .append(distributionBar(missing, wrong, dead)).append("</div>");
        }
        sb.append("</section>");

        // ---- 2. Risk & business impact ----
        sb.append("<section id=\"sec-2\"><h2>").append(bi("2. Risk &amp; business impact", "2. Risque et impact d'affaires")).append("</h2>");
        sb.append("<table class=\"impact\"><thead><tr><th>").append(bi("Severity", "Sévérité")).append("</th><th>")
                .append(bi("Count", "Nombre")).append("</th><th>").append(bi("What it means", "Ce que cela implique"))
                .append("</th></tr></thead><tbody>");
        for (String sev : new String[] {"BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO", "UNSPECIFIED"}) {
            long n = counted.stream().filter(f -> f.effectiveSeverity() != null && f.effectiveSeverity().name().equals(sev)).count();
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
            sb.append(subsection(scan, bi("4.1 Missing from the specification", "4.1 Manquant dans la spécification"), missing, fr));
            sb.append(subsection(scan, bi("4.2 Contract mismatches", "4.2 Incohérences du contrat"), wrong, fr));
            sb.append(subsection(scan, bi("4.3 Dead spec (documented, not in code)", "4.3 Spéc. morte (documentée, absente du code)"), dead, fr));
            sb.append("</section>");
        }

        // corrected YAML link (anyFix computed above for the TOC)
        if (anyFix) {
            sb.append("<section id=\"sec-5\"><h2>").append(bi("5. Corrected OpenAPI specification", "5. Spécification OpenAPI corrigée"))
                    .append("</h2><p><a href=\"./").append(esc(ReportNaming.correctedSpecName(scan)))
                    .append("\" target=\"_blank\" rel=\"noreferrer\">")
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
                                    + "score or severity totals. Accept or reject each below.",
                            "Soulevés par l'assistant, non prouvés de façon déterministe — <strong>non comptés</strong> "
                                    + "dans le score ni les totaux. Acceptez ou rejetez chacun ci-dessous."))
                    .append("</p>");
            if (interactive) {
                sb.append("<div class=\"dist-panel\"><div class=\"panel-h\">")
                        .append(bi("Manual-review items by severity", "Éléments à vérifier par sévérité")).append("</div>")
                        .append(severityDonut(attention, "to review", "à vérifier")).append("</div>");
                int totalRev = attention.size();
                long acc0 = attention.stream().filter(f -> isAccepted(f.getStatus())).count();
                long rej0 = attention.stream().filter(f -> isRejected(f.getStatus())).count();
                long pen0 = totalRev - acc0 - rej0;
                sb.append("<div class=\"review-tracker\">")
                        .append("<span class=\"rt-pill acc\"><b id=\"rt-acc\">").append(acc0).append("</b> ")
                        .append(bi("accepted", "acceptés")).append("</span>")
                        .append("<span class=\"rt-pill rej\"><b id=\"rt-rej\">").append(rej0).append("</b> ")
                        .append(bi("rejected", "rejetés")).append("</span>")
                        .append("<span class=\"rt-pill pen\"><b id=\"rt-pen\">").append(pen0).append("</b> ")
                        .append(bi("pending", "en attente")).append("</span>")
                        .append("<span class=\"rt-of\">(<span id=\"rev-count\">").append(acc0 + rej0).append("</span> ")
                        .append(bi("of", "sur")).append(" ").append(totalRev).append(" ").append(bi("reviewed", "vérifiés"))
                        .append(")</span></div>")
                        .append("<div class=\"rt-bar\"><span id=\"rt-bar-acc\" style=\"width:")
                        .append(pctStr(100.0 * acc0 / totalRev)).append("%\"></span>")
                        .append("<span id=\"rt-bar-rej\" style=\"width:").append(pctStr(100.0 * rej0 / totalRev))
                        .append("%\"></span></div>");
            }
            for (Finding f : attention) {
                sb.append(findingCard(scan, f, fr, interactive, true));
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
                        "Generated by Veritas. The differences are found by automated static code analysis "
                                + "(deterministic — not an AI guess); the explanations of why they matter and how to fix them "
                                + "use AI assistance. Confidential — internal use.",
                        "Généré par Veritas. Les différences sont détectées par analyse statique automatisée du code "
                                + "(déterministe — pas une supposition de l'IA); les explications (pourquoi et comment corriger) "
                                + "utilisent l'assistance IA. Confidentiel — usage interne."));
        if (buildStamp != null && !buildStamp.isBlank()) {
            // Which build produced THIS report — baked into the written file, so a stale build is obvious at a glance.
            sb.append(" · ").append(esc("Veritas " + buildStamp));
        }
        if (scan.getTotalEstCostUsd() > 0) {   // don't render "$0.0000" on free/mock runs
            sb.append(" · ").append(bi("Est. cost", "Coût est."))
                    .append(String.format(" $%.4f", scan.getTotalEstCostUsd()));
        }
        if (scan.getConfidence() != null && scan.getConfidence() > 0) {
            sb.append(" · ").append(bi(
                    "Explanation confidence: " + Math.round(scan.getConfidence())
                            + "% — how certain the AI is of its explanations (80%+ is more trustworthy)",
                    "Confiance des explications : " + Math.round(scan.getConfidence())
                            + "% — à quel point l'IA est certaine de ses explications (80 %+ est plus fiable)"));
        }
        sb.append("</p>");
        sb.append("</div>");   // content

        if (interactive) {
            sb.append("<script>function setLang(l){document.body.classList.remove('lang-en','lang-fr');")
                    .append("document.body.classList.add('lang-'+l);")
                    .append("document.querySelectorAll('.lang-toggle button').forEach(function(b){")
                    .append("b.classList.toggle('active', b.getAttribute('data-lang')===l);});}")
                    .append("function reviewTracker(){var box=document.querySelector('.needs-attention');if(!box)return;")
                    .append("var total=box.querySelectorAll('.finding-card').length;")
                    .append("var acc=box.querySelectorAll('.finding-card.accepted').length;")
                    .append("var rej=box.querySelectorAll('.finding-card.rejected').length;")
                    .append("var s=function(id,v){var e=document.getElementById(id);if(e)e.textContent=v;};")
                    .append("s('rt-acc',acc);s('rt-rej',rej);s('rt-pen',total-acc-rej);s('rev-count',acc+rej);")
                    .append("var w=function(id,n){var e=document.getElementById(id);if(e)e.style.width=(total?100*n/total:0)+'%';};")
                    .append("w('rt-bar-acc',acc);w('rt-bar-rej',rej);}")
                    .append("function reviewItem(btn,action){var card=btn.closest('.finding-card');")
                    .append("card.classList.remove('accepted','rejected');card.classList.add(action==='accept'?'accepted':'rejected');")
                    .append("reviewTracker();}reviewTracker();</script>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    // ---- sections / cards -----------------------------------------------------------------------------------

    private String subsection(Scan scan, String title, List<Finding> items, Map<String, String> fr) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<h3 class=\"subhead\">").append(title)
                .append(" <span class=\"count\">").append(items.size()).append("</span></h3>");
        for (Finding f : items) {
            sb.append(findingCard(scan, f, fr, false, false));
        }
        return sb.toString();
    }

    private String findingCard(Scan scan, Finding f, Map<String, String> fr, boolean reviewable, boolean manualReview) {
        String color = SEVERITY_COLOR.getOrDefault(f.effectiveSeverity() != null ? f.effectiveSeverity().name() : "", "#6B7280");
        // Seed the card's accept/reject state from the persisted disposition so the live tracker is correct on load.
        String stateClass = "";
        if (reviewable && isAccepted(f.getStatus())) {
            stateClass = " accepted";
        } else if (reviewable && isRejected(f.getStatus())) {
            stateClass = " rejected";
        }
        StringBuilder sb = new StringBuilder("<div class=\"finding-card" + stateClass + "\">");
        boolean multiEndpoint = f.getAffectedEndpoints() != null && f.getAffectedEndpoints().size() > 1;
        sb.append("<div class=\"finding-header\">")
                .append("<span class=\"severity-badge\" style=\"background:").append(color).append("\">")
                .append(esc(f.effectiveSeverity() != null ? f.effectiveSeverity().name() : "")).append("</span>");
        if (f.getUserSeverity() != null && f.getUserSeverity() != f.getSeverity()) {
            // A human overrode the engine's classification — surface the engine's original as an honest hint so the
            // effective (gating) severity and the engine's suggestion are both visible.
            sb.append("<span class=\"sev-override\">").append(bi("engine: ", "moteur : "))
                    .append(esc(f.getSeverity() != null ? f.getSeverity().name() : "?")).append("</span>");
        }
        sb.append("<span class=\"finding-title\">").append(biDyn(nz(f.getSummary()), fr)).append("</span>")
                .append("<span class=\"finding-meta\"><code>")
                .append(multiEndpoint ? f.getAffectedEndpoints().size() + " " + bi("endpoints", "points de terminaison")
                        : esc(nz(f.getEndpoint())))
                .append("</code>")
                .append(f.getLayer() != null ? " · " + esc(layerLabel(f.getLayer())) : "")
                // No confidence pill on LLM-origin (design/test) findings: §6 already labels them "raised by the
                // assistant, not deterministically proven", so a "Deterministic…" MEDIUM tooltip would contradict it.
                .append(f.getConfidence() != null && !"LLM".equalsIgnoreCase(f.getOrigin())
                        ? confidencePill(f.getConfidence()) : "").append("</span>")
                .append("</div>");
        // One shared root cause spanning several endpoints — list them so a reviewer sees the full blast radius (and
        // knows it's counted once, not per endpoint).
        if (multiEndpoint) {
            StringBuilder eps = new StringBuilder();
            for (String ep : f.getAffectedEndpoints()) {
                eps.append(eps.length() > 0 ? ", " : "").append("<code>").append(esc(ep)).append("</code>");
            }
            sb.append("<div class=\"affects\">").append(bi("Affects", "Concerne")).append(": ").append(eps).append("</div>");
        }
        // AI-disputed: a deterministic finding the assistant believes is a false positive. Shown prominently with its
        // reason; it is excluded from the release gate but still listed — a human verifies before dismissing it.
        if (f.isAiDisputed()) {
            sb.append("<div class=\"ai-dispute\" style=\"border-left:4px solid #C2410C;background:#fff4ec;"
                    + "border-radius:0 6px 6px 0;padding:.5rem .8rem;margin:.5rem 0;font-size:.86rem\">")
                    .append("<strong>").append(bi("Flagged by AI as a possible false positive",
                            "Signalé par l'IA comme faux positif possible")).append("</strong> — ")
                    .append(bi("excluded from the release gate, still listed; verify before dismissing.",
                            "exclu de la décision de livraison, mais toujours listé; à vérifier avant de l'écarter."));
            if (!isBlank(f.getAiDisputeReason())) {
                sb.append("<div style=\"margin-top:.3rem;color:#7c2d12\">")
                        .append(biDyn(f.getAiDisputeReason(), fr)).append("</div>");
            }
            sb.append("</div>");
        }
        // Recorded disposition (from the dashboard's system of record) — shown as an audited badge.
        String disp = f.getStatus();
        if (disp != null && !disp.isBlank() && !"OPEN".equalsIgnoreCase(disp)) {
            sb.append("<div class=\"disp-line\">").append(bi("Disposition", "Décision")).append(": ")
                    .append("<span class=\"disp-badge disp-").append(dispClass(disp)).append("\">")
                    .append(dispLabel(disp)).append("</span>");
            if (!isBlank(f.getReviewedBy())) {
                sb.append(" <span class=\"disp-by\">").append(bi("by", "par")).append(" ").append(esc(f.getReviewedBy()));
                if (f.getReviewedAt() != null) {
                    sb.append(" · ").append(esc(fmtDate(f.getReviewedAt())));
                }
                sb.append("</span>");
            }
            sb.append("</div>");
        }
        if (!isBlank(f.getExplanation())) {
            sb.append("<div class=\"business-impact\">").append(biDyn(f.getExplanation(), fr)).append("</div>");
        }
        String snippet = f.getCodeEvidence() != null ? f.getCodeEvidence().snippet() : null;
        String currentYaml = f.getCurrentYamlFragment();
        String proposedFix = f.getProposedFix();
        String citation = f.getCitation();
        // Traceable code anchor: always show where in the source this came from — "File.java:line" as a clickable
        // Bitbucket deep link (when the repo coordinates are known). For findings with a snippet the location sits in
        // the snippet panel's header; for the rest (e.g. a schema-field diff) show it as a standalone trace line so
        // the reviewer can always jump straight to the exact field in the code.
        String codeLoc = loc(f);
        // §6 manual-review items MUST always show a "Basis" (evidence pointer) — the label the reviewer trusts. For a
        // finding with a code location the standalone code-trace row IS that basis (relabelled below); for the rest we
        // synthesize one after the panels so no manual-review card is ever left bare. Counted findings keep "Code".
        if (!isBlank(codeLoc) && isBlank(snippet)) {
            sb.append("<div class=\"code-trace\"><span class=\"code-trace-label\">")
                    .append(manualReview ? bi("Basis", "Fondement") : bi("Code", "Code")).append("</span> ")
                    .append(codeEvidenceLabel(scan, f)).append("</div>");
        }
        if (!isBlank(snippet) || !isBlank(currentYaml) || !isBlank(proposedFix)) {
            sb.append("<div class=\"dual-view\">");
            if (!isBlank(snippet)) {
                sb.append(panelRawSub("code", bi("Code evidence", "Preuve dans le code"), codeEvidenceLabel(scan, f),
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
        // Guarantee a Basis on every manual-review card. A code location already rendered one above; otherwise fall
        // back — closed-world, never fabricated — to a spec pointer (specLocus / current YAML fragment) or, failing
        // that, the governing standard citation. A genuinely spec-wide finding legitimately shows a spec pointer +
        // citation, not a code line.
        boolean hasCodeBasis = !isBlank(codeLoc);   // a real code location was shown as the Basis above
        if (manualReview && !hasCodeBasis) {
            sb.append(manualReviewBasis(f, citation));
        }
        if (!isBlank(citation) && !manualReview) {
            sb.append("<div class=\"citation\">").append(bi("Reference", "Référence")).append(": ")
                    .append(esc(citation)).append("</div>");
        }
        if (reviewable) {
            // Give the reader the hand to accept or reject an AI-raised item (client-side; not scored either way).
            sb.append("<div class=\"review-actions\">")
                    .append("<button type=\"button\" class=\"btn-accept\" onclick=\"reviewItem(this,'accept')\">")
                    .append(bi("Accept", "Accepter")).append("</button>")
                    .append("<button type=\"button\" class=\"btn-reject\" onclick=\"reviewItem(this,'reject')\">")
                    .append(bi("Reject", "Rejeter")).append("</button>")
                    .append("<span class=\"review-state\">")
                    .append("<span class=\"rs rs-accepted\">").append(bi("Accepted", "Accepté")).append("</span>")
                    .append("<span class=\"rs rs-rejected\">").append(bi("Rejected — won't fix", "Rejeté — ne pas corriger"))
                    .append("</span></span></div>");
        }
        return sb.append("</div>").toString();
    }

    /**
     * The "Basis" row for a manual-review card that has no code line: prefer a spec pointer (the schema/field locus,
     * or a compact reference to the current YAML fragment), else the governing-standard citation, else a plain
     * "flagged by the assistant" note. Never fabricates a code location — a spec-wide finding legitimately shows a
     * spec pointer + citation. Compact and non-technical; reuses {@link #bi(String, String)}/{@link #esc(String)}.
     */
    private String manualReviewBasis(Finding f, String citation) {
        String specPointer = specPointer(f);
        if (!isBlank(specPointer)) {
            StringBuilder b = new StringBuilder("<div class=\"code-trace\"><span class=\"code-trace-label\">")
                    .append(bi("Basis", "Fondement")).append("</span> <span class=\"spec-locus\">")
                    .append(esc(specPointer)).append("</span>");
            if (!isBlank(citation)) {
                b.append(" · ").append(esc(citation));   // spec-wide items pair the pointer with the standard
            }
            return b.append("</div>").toString();
        }
        if (!isBlank(citation)) {
            return "<div class=\"code-trace\"><span class=\"code-trace-label\">" + bi("Basis", "Fondement")
                    + "</span> " + esc(citation) + "</div>";
        }
        // No code line, no spec pointer, no citation — still never bare: name the source of the observation.
        return "<div class=\"code-trace\"><span class=\"code-trace-label\">" + bi("Basis", "Fondement")
                + "</span> " + bi("Design/test-coverage observation raised by the assistant for manual review.",
                "Observation de conception ou de couverture de tests soulevée par l'assistant à vérifier.") + "</div>";
    }

    /**
     * A compact spec-side pointer for a manual-review basis: the schema/field locus when known, otherwise the first
     * line of the current YAML fragment (a terse reference to the spot in the spec). Null when neither is present.
     */
    private String specPointer(Finding f) {
        if (!isBlank(f.getSpecLocus())) {
            return f.getSpecLocus();
        }
        String yaml = f.getCurrentYamlFragment();
        if (isBlank(yaml)) {
            return null;
        }
        String firstLine = yaml.strip().lines().findFirst().orElse("").strip();
        return firstLine.isBlank() ? null : firstLine;
    }

    private String panel(String cls, String label, String sub, String body) {
        return "<div class=\"evidence-panel " + cls + "\"><div class=\"ep-h\">" + label
                + (isBlank(sub) ? "" : " <span class=\"ep-sub\">" + esc(sub) + "</span>") + "</div>" + body + "</div>";
    }

    /** Like {@link #panel} but {@code subHtml} is already-safe HTML (used for the clickable code-evidence link). */
    private String panelRawSub(String cls, String label, String subHtml, String body) {
        return "<div class=\"evidence-panel " + cls + "\"><div class=\"ep-h\">" + label
                + (isBlank(subHtml) ? "" : " <span class=\"ep-sub\">" + subHtml + "</span>") + "</div>" + body + "</div>";
    }

    /**
     * The code-evidence label as safe HTML: a clickable Bitbucket deep link to the file/line when the repo
     * coordinates and a Bitbucket connection are known, otherwise the plain (escaped) "File.java:line" text.
     */
    private String codeEvidenceLabel(Scan scan, Finding f) {
        String text = loc(f);
        if (isBlank(text)) {
            return "";
        }
        if (linkBuilder != null && scan != null && f.getCodeEvidence() != null) {
            String url = linkBuilder.fileLink(scan.getAppId(), scan.getRepoSlug(), scan.getGitRef(),
                    f.getCodeEvidence().location(), f.getCodeEvidence().startLine()).orElse(null);
            if (url != null) {
                return "<a class=\"code-link\" href=\"" + esc(url) + "\" target=\"_blank\" rel=\"noreferrer\""
                        + " style=\"color:#1D4ED8\">" + esc(text) + "</a>";
            }
        }
        return esc(text);
    }

    /** Conic-gradient donut + legend (interactive HTML only — the PDF engine can't render conic-gradient/SVG). */
    private String severityDonut(List<Finding> items, String centerEn, String centerFr) {
        // Count only items with a real severity so the donut total reconciles with the gradient stops below — a
        // null/unknown severity (e.g. a corrupt persisted row on a live re-render) can't yield an empty conic-gradient().
        int total = (int) items.stream().filter(f -> f.effectiveSeverity() != null).count();
        if (total == 0) {
            return "<div class=\"cov-ok\">" + bi("No counted findings — contract is clean.",
                    "Aucun constat compté — le contrat est conforme.") + "</div>";
        }
        String[] order = {"BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO", "UNSPECIFIED"};
        StringBuilder gradient = new StringBuilder();
        StringBuilder legend = new StringBuilder("<div class=\"pie-legend\">");
        double cum = 0;
        for (String sev : order) {
            long n = items.stream().filter(f -> f.effectiveSeverity() != null && f.effectiveSeverity().name().equals(sev)).count();
            if (n == 0) {
                continue;
            }
            double pct = 100.0 * n / total;
            double start = cum;
            cum += pct;
            if (gradient.length() > 0) {
                gradient.append(",");
            }
            gradient.append(SEVERITY_COLOR.get(sev)).append(" ").append(pctStr(start)).append("% ").append(pctStr(cum)).append("%");
            legend.append("<span class=\"li\"><span class=\"dot\" style=\"background:").append(SEVERITY_COLOR.get(sev))
                    .append("\"></span><span class=\"lab\">").append(cap(sev)).append("</span>")
                    .append("<span class=\"n\">").append(n).append("</span>")
                    .append("<span class=\"pct\">").append(Math.round(pct)).append("%</span></span>");
        }
        legend.append("</div>");
        return "<div class=\"pie-wrap\"><div class=\"pie\" style=\"background:conic-gradient(" + gradient + ")\">"
                + "<div class=\"pie-hole\"><div class=\"pie-total\">" + total + "</div>"
                + "<div class=\"pie-total-l\">" + bi(centerEn, centerFr) + "</div></div></div>" + legend + "</div>";
    }

    private String pctStr(double d) {
        long r = Math.round(d);
        return Math.abs(d - r) < 0.05 ? String.valueOf(r) : String.format(java.util.Locale.ROOT, "%.2f", d);
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
        String[] order = {"BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO", "UNSPECIFIED"};
        StringBuilder bar = new StringBuilder("<div class=\"dist-bar\">");
        StringBuilder legend = new StringBuilder("<div class=\"dist-legend\">");
        for (String sev : order) {
            long n = all.stream().filter(f -> f.effectiveSeverity() != null && f.effectiveSeverity().name().equals(sev)).count();
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

    /** An HTTP status code (3xx/4xx/5xx) as a standalone token in an advice-status summary. */
    private static final java.util.regex.Pattern ERROR_STATUS = java.util.regex.Pattern.compile("\\b([3-5]\\d\\d)\\b");

    /**
     * A compact §1 note listing the undocumented ERROR responses (500/406/…) that were demoted to §6 manual review —
     * exactly the advice-demoted statuses ({@code STATUS_CODE_MISSING && DETERMINISTIC && LOW}, produced only by the
     * diff engine's advice-origin demotion: a global {@code @ControllerAdvice} handler OR a controller-local
     * {@code @ExceptionHandler} whose per-endpoint reachability can't be proven statically). It names each status
     * once, sorted ascending, with the number of DISTINCT endpoints it can reach, so the team is still told these
     * error statuses exist even though they are not counted in the score. Returns "" (no heading) when there is
     * nothing to surface.
     */
    private String errorContractNote(List<Finding> attention) {
        // status -> distinct endpoints that can return it (sorted status keys for a stable ascending render).
        java.util.Map<Integer, java.util.Set<String>> byStatus = new java.util.TreeMap<>();
        for (Finding f : attention) {
            boolean adviceDemoted = f.getType() != null && "STATUS_CODE_MISSING".equals(f.getType().name())
                    && "DETERMINISTIC".equalsIgnoreCase(f.getOrigin())
                    && f.getConfidence() != null && "LOW".equals(f.getConfidence().name());
            if (!adviceDemoted || isBlank(f.getSummary())) {
                continue;
            }
            java.util.regex.Matcher m = ERROR_STATUS.matcher(f.getSummary());
            if (!m.find()) {
                continue;   // defensive: no parseable status in the summary → skip rather than guess
            }
            int status = Integer.parseInt(m.group(1));
            byStatus.computeIfAbsent(status, k -> new java.util.LinkedHashSet<>()).addAll(endpointsOf(f));
        }
        if (byStatus.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"err-note\"><div class=\"err-note-h\">").append(bi(
                "Undocumented error responses — not counted in the score; see section 6",
                "Réponses d'erreur non documentées — non comptées dans le score; voir la section 6")).append("</div>");
        for (java.util.Map.Entry<Integer, java.util.Set<String>> e : byStatus.entrySet()) {
            int n = e.getValue().size();
            sb.append("<div class=\"err-note-line\">").append(bi(
                    "HTTP " + e.getKey() + " — an exception handler can return this status, but the spec does not "
                            + "document it (" + n + (n == 1 ? " endpoint)." : " endpoints)."),
                    "HTTP " + e.getKey() + " — un gestionnaire d'exceptions peut retourner ce code, mais la spéc "
                            + "ne le documente pas (" + n + (n == 1 ? " point de terminaison)." : " points de terminaison)."))).append("</div>");
        }
        return sb.append("</div>").toString();
    }

    /** The distinct endpoints a finding covers — its {@code affectedEndpoints} when collapsed across several, else its
     *  single endpoint (or none). */
    private static List<String> endpointsOf(Finding f) {
        if (f.getAffectedEndpoints() != null && f.getAffectedEndpoints().size() > 1) {
            return f.getAffectedEndpoints();
        }
        return f.getEndpoint() == null ? List.of() : List.of(f.getEndpoint());
    }

    /** The plain "is it safe to release?" verdict box — first thing management sees. Consumer-breaking changes drive
     *  the FAIL; additive/documentation drift only WARNs; a clean scan PASSes. Mirrors the categorical release gate. */
    private String bottomLine(ca.bnc.qe.veritas.contract.ReleaseVerdict verdict, int missing, int wrong, int dead) {
        long breaking = verdict.breaking();
        long disputed = verdict.aiDisputed();
        int total = verdict.counted();
        String v = verdict.releaseSafe();
        String color, tint, statusEn, statusFr;
        if (v.equals("FAIL")) {
            color = "#C2122D"; tint = "#fdecef"; statusEn = "Do not release"; statusFr = "Ne pas livrer";
        } else if (v.equals("WARN")) {
            color = "#C2410C"; tint = "#fff4ec";
            statusEn = "Proceed — fixes recommended"; statusFr = "Prêt à livrer — corrections recommandées";
        } else {
            color = "#1E8E5A"; tint = "#e9f6ef"; statusEn = "Proceed"; statusFr = "Prêt à livrer";
        }
        String actionEn, actionFr;
        if (total == 0) {
            actionEn = "No action needed — the documentation matches the code.";
            actionFr = "Aucune action requise — la documentation correspond au code.";
        } else {
            // The missing/wrong/dead buckets partition the whole counted set, and breaking is a SUBSET of that set —
            // so breaking must NOT be an additional list item (it would double-count against the Effort total). Render
            // it as a prioritization qualifier over the bucket items instead.
            List<String> en = new ArrayList<>();
            List<String> frb = new ArrayList<>();
            if (missing > 0) { en.add("document " + missing); frb.add("documenter " + missing); }
            if (wrong > 0) { en.add("correct " + wrong + " mismatch" + (wrong == 1 ? "" : "es")); frb.add("corriger " + wrong + " incohérence(s)"); }
            if (dead > 0) { en.add("remove " + dead + " stale entr" + (dead == 1 ? "y" : "ies")); frb.add("retirer " + dead + " entrée(s) obsolète(s)"); }
            actionEn = capFirst(String.join(", ", en)) + ".";
            actionFr = capFirst(String.join(", ", frb)) + ".";
            if (breaking > 0) {
                actionEn += " " + breaking + " of these " + (breaking == 1 ? "is" : "are")
                        + " consumer-breaking — fix " + (breaking == 1 ? "it" : "those") + " first.";
                actionFr += " " + breaking + (breaking == 1 ? " de ceux-ci est cassant" : " de ceux-ci sont cassants")
                        + " — à corriger en priorité.";
            }
        }
        // Qualitative effort band from the item count — no fabricated per-item hour figure presented as a commitment.
        String effortEn = total <= 3 ? "small" : total <= 8 ? "moderate" : "large";
        String effortFr = total <= 3 ? "faible" : total <= 8 ? "modéré" : "important";
        String timeEn = total == 0 ? "—" : total + " item" + (total == 1 ? "" : "s") + " · " + effortEn + " effort";
        String timeFr = total == 0 ? "—" : total + " élément(s) · effort " + effortFr;

        StringBuilder b = new StringBuilder();
        b.append("<div style=\"border-left:5px solid ").append(color).append(";background:").append(tint)
                .append(";border-radius:0 8px 8px 0;padding:.9rem 1.2rem;margin:0 0 1.3rem\">");
        b.append("<div style=\"font-size:.66rem;text-transform:uppercase;letter-spacing:.1em;color:#475069\">")
                .append(bi("Bottom line", "En résumé")).append("</div>");
        b.append("<div style=\"font-size:1.3rem;font-weight:700;color:").append(color).append(";margin:.1rem 0 .55rem\">")
                .append(bi(statusEn, statusFr)).append("</div>");
        b.append("<table style=\"font-size:.9rem;border-collapse:collapse\">");
        b.append("<tr><td style=\"color:#475069;padding:.1rem 1rem .1rem 0;vertical-align:top;white-space:nowrap\">")
                .append(bi("Action", "Action")).append("</td><td style=\"padding:.1rem 0\">").append(bi(actionEn, actionFr)).append("</td></tr>");
        b.append("<tr><td style=\"color:#475069;padding:.1rem 1rem .1rem 0;vertical-align:top;white-space:nowrap\">")
                .append(bi("Effort", "Effort")).append("</td><td style=\"padding:.1rem 0\">").append(bi(timeEn, timeFr)).append("</td></tr>");
        b.append("</table>");
        // Why a WARN still reads "Proceed": no finding breaks a running consumer — the drift is additive documentation
        // (the code is a compatible superset of the spec), so the release is safe on its own timeline. Only assert this
        // when there are genuinely zero breaking findings (a raised gate cap can produce a WARN that still carries some).
        if (v.equals("WARN") && verdict.allNonBreaking()) {
            b.append("<div style=\"margin-top:.6rem;font-size:.82rem;color:#475069\">").append(bi(
                    "No consumer-breaking changes — all drift is additive documentation (the code returns/accepts more "
                            + "than the spec documents). Safe to release; update the spec at your own cadence.",
                    "Aucun changement cassant — toute la dérive est documentaire additive (le code renvoie/accepte plus "
                            + "que ce que la spéc documente). Livraison sûre; mettez à jour la spéc à votre rythme."))
                    .append("</div>");
        }
        // Honesty when the gate was conditionally relaxed: surface that the AI excluded N findings as possible false
        // positives, so a clean-looking verdict is never mistaken for "nothing flagged". They remain listed in §6.
        if (disputed > 0) {
            b.append("<div style=\"margin-top:.6rem;font-size:.82rem;color:#475069\">").append(bi(
                    "Note: " + disputed + " deterministic finding" + (disputed == 1 ? " was" : "s were")
                            + " flagged by the AI as a possible false positive and excluded from this gate — review "
                            + (disputed == 1 ? "it" : "them") + " in §6 before relying on this verdict.",
                    "Note : " + disputed + " constat(s) déterministe(s) signalé(s) par l'IA comme faux positif(s) "
                            + "possible(s) et exclu(s) de cette décision — à vérifier au §6 avant de vous y fier."))
                    .append("</div>");
        }
        b.append("</div>");
        return b.toString();
    }

    /** Sentence-case: uppercase the first character only, leaving the rest untouched. */
    private static String capFirst(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Plain-language label for a finding's analysis layer — readers should never see the L1–L6 codes. */
    private static String layerLabel(ca.bnc.qe.veritas.finding.Layer layer) {
        if (layer == null) {
            return "";
        }
        return switch (layer.name()) {
            case "L1" -> "Specification structure";
            case "L2" -> "API completeness";
            case "L3" -> "Documentation scope";
            case "L4" -> "Signature accuracy";
            case "L5" -> "Design quality";
            case "L6" -> "Test coverage";
            default -> layer.name();
        };
    }

    /** Confidence as a clear coloured pill with a plain-language tooltip explaining what the level means here. */
    private String confidencePill(ca.bnc.qe.veritas.finding.Confidence c) {
        if (c == null) {
            return "";
        }
        String cls;
        String tip;
        switch (c.name()) {
            case "HIGH" -> {
                cls = "conf-high";
                tip = "Parsed directly from the source signatures — deterministic, not an AI guess.";
            }
            case "MEDIUM" -> {
                cls = "conf-med";
                tip = "Deterministic, with some inference (e.g. type mapping).";
            }
            default -> {
                cls = "conf-low";
                tip = "Heuristic signal — verify before acting.";
            }
        }
        return "<span class=\"conf-pill " + cls + "\" title=\"" + esc(tip) + "\">" + esc(confidenceLabel(c)) + "</span>";
    }

    /** Plain-language label for confidence — never show the raw HIGH/MEDIUM/LOW code. */
    private static String confidenceLabel(ca.bnc.qe.veritas.finding.Confidence c) {
        if (c == null) {
            return "";
        }
        return switch (c.name()) {
            case "HIGH" -> "High confidence";
            case "MEDIUM" -> "Medium confidence";
            case "LOW" -> "Low confidence";
            default -> c.name();
        };
    }

    // ---- deterministic narrative helpers (no LLM) -----------------------------------------------------------

    /** {cssClass, EN label, FR label} for the categorical release-gate verdict — the cover badge + the KPI tile. */
    private String[] verdictBand(String verdict) {
        return switch (verdict) {
            case "PASS" -> new String[] {"clean", "Release-safe", "Prêt à livrer"};
            case "WARN" -> new String[] {"warn", "Ship with fixes", "Livrable avec corrections"};
            default -> new String[] {"action", "Do not release", "Ne pas livrer"};   // FAIL
        };
    }

    private String[] businessImpact(String sev) {
        return switch (sev) {
            case "BLOCKER" -> new String[] {"Blocks release — the API does not do what the documentation says; customers would build broken integrations.",
                    "Bloque la livraison — l'API ne fait pas ce que la documentation indique; les clients construiraient des intégrations brisées."};
            case "CRITICAL" -> new String[] {"High risk of integration failures and undocumented behaviour reaching clients.",
                    "Risque élevé d'échecs d'intégration et de comportements non documentés exposés aux clients."};
            case "MAJOR" -> new String[] {"Likely to cause client confusion or gaps in test coverage.",
                    "Susceptible de créer de la confusion chez les clients ou des lacunes de couverture de tests."};
            case "MINOR" -> new String[] {"Low-risk drift; address opportunistically.",
                    "Dérive à faible risque; à corriger au besoin."};
            case "UNSPECIFIED" -> new String[] {"Unclassified finding type — a human must set its severity before the gate can judge it.",
                    "Type de constat non classé — un humain doit définir sa sévérité avant que le seuil puisse l'évaluer."};
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
            r.add(new String[] {"Correct the " + wrong + " mismatch(es) so the documentation matches what the code actually does.",
                    "Corriger les " + wrong + " incohérence(s) afin que la documentation corresponde à ce que fait réellement le code."});
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

    private String metaCell(String label, String value) {
        return "<td class=\"meta-cell\"><div class=\"meta-label\">" + label + "</div>"
                + "<div class=\"meta-value\">" + value + "</div></td>";
    }

    private static boolean isAccepted(String status) {
        if (status == null) {
            return false;
        }
        String u = status.toUpperCase(java.util.Locale.ROOT);
        return u.equals("ACCEPTED") || u.equals("FIXED");
    }

    private static boolean isRejected(String status) {
        if (status == null) {
            return false;
        }
        String u = status.toUpperCase(java.util.Locale.ROOT);
        return u.equals("REJECTED") || u.equals("WONT_FIX") || u.equals("FALSE_POSITIVE");
    }

    private String dispClass(String status) {
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "ACCEPTED", "FIXED" -> "ok";
            case "REJECTED", "WONT_FIX", "FALSE_POSITIVE" -> "danger";
            case "TRIAGED", "JIRA_CREATED" -> "info";
            default -> "muted";
        };
    }

    private String dispLabel(String status) {
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "ACCEPTED" -> bi("Accepted", "Accepté");
            case "REJECTED" -> bi("Rejected", "Rejeté");
            case "WONT_FIX" -> bi("Won't fix", "Ne sera pas corrigé");
            case "FALSE_POSITIVE" -> bi("False positive", "Faux positif");
            case "TRIAGED" -> bi("Triaged", "Trié");
            case "JIRA_CREATED" -> bi("Defect raised", "Anomalie créée");
            case "FIXED" -> bi("Fixed", "Corrigé");
            default -> esc(status);
        };
    }

    private String fmtDate(java.time.Instant t) {
        return t == null ? "—" : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                .withZone(java.time.ZoneOffset.UTC).format(t);
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
                + ".lang-toggle{position:fixed;top:14px;right:14px;z-index:50;display:flex;gap:3px;background:#1a1a2e;"
                + "padding:4px;border-radius:999px;box-shadow:0 6px 18px rgba(0,0,0,.22)}"
                + ".lang-toggle button{background:transparent;color:#cdd3e0;border:0;padding:.32rem .8rem;border-radius:999px;"
                + "cursor:pointer;font-size:.8rem;font-weight:700;letter-spacing:.04em}"
                + ".lang-toggle button.active{background:#e94560;color:#fff}"
                + ".rating{display:inline-block;font-weight:700;font-size:.8rem;padding:.35rem .9rem;border-radius:999px;"
                + "text-transform:uppercase;letter-spacing:.5px;color:#fff}"
                + ".rating-clean{background:#1E8E5A}.rating-minor{background:#b5852a}.rating-warn{background:#C2410C}.rating-action{background:#C2122D}"
                + ".meta-grid{width:100%;margin-top:1.4rem;border-top:1px solid rgba(255,255,255,.18);border-collapse:collapse}"
                + ".meta-cell{padding:.78rem 1.4rem .35rem 0;vertical-align:top;width:50%}"
                + ".meta-label{font-size:.6rem;text-transform:uppercase;letter-spacing:.14em;color:rgba(255,255,255,.55);margin-bottom:3px}"
                + ".meta-value{font-size:.92rem;color:#fff;font-weight:500;word-break:break-word}"
                + ".meta-value.mono{font-family:JetBrains Mono,ui-monospace,monospace;font-size:.78rem;opacity:.85}"
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
                + ".exec-snapshot{display:flex;gap:1.6rem;flex-wrap:wrap;align-items:center;background:#fff;border:1px solid #e3e6eb;border-radius:10px;padding:1.1rem 1.3rem;margin:1rem 0}"
                + ".snapshot-viz{flex:1 1 300px;min-width:280px}"
                + ".snapshot-kpis{flex:1 1 340px;display:grid;grid-template-columns:1fr 1fr;gap:.7rem;margin:0}"
                + ".snapshot-kpis .kpi{min-width:0}"
                + ".kpi{flex:1;min-width:150px;background:#fff;border:1px solid #e3e6eb;border-top:3px solid #888;border-radius:10px;padding:.9rem 1.1rem}"
                + ".kpi-v{font-size:1.8rem;font-weight:700}.kpi-v .unit{font-size:.9rem;font-weight:400;color:#888}"
                + ".kpi-l{font-size:.72rem;text-transform:uppercase;color:#475069;letter-spacing:.5px}"
                + ".kpi-clean{border-top-color:#1E8E5A}.kpi-warn{border-top-color:#C2410C}.kpi-action{border-top-color:#C2122D}"
                + ".kpi-minor{border-top-color:#b5852a}.kpi-neutral{border-top-color:#3A4658}"
                + ".dist-panel{background:#fff;border:1px solid #e3e6eb;border-radius:10px;padding:1rem 1.25rem}"
                + ".panel-h{font-size:.75rem;text-transform:uppercase;letter-spacing:.5px;color:#475069;margin-bottom:.6rem}"
                + ".dist-bar{display:flex;height:18px;border-radius:999px;overflow:hidden;background:#eef1f5}"
                + ".dist-bar span{display:block;height:100%}"
                + ".dist-legend{display:flex;gap:14px;flex-wrap:wrap;margin-top:.6rem;font-size:.8rem;color:#475069}"
                + ".dist-legend .dot{display:inline-block;width:10px;height:10px;border-radius:3px;margin-right:5px}"
                + ".pie-wrap{display:flex;align-items:center;gap:1.6rem;flex-wrap:wrap}"
                + ".pie{width:148px;height:148px;border-radius:50%;position:relative;flex:0 0 auto;box-shadow:0 2px 10px rgba(0,0,0,.06)}"
                + ".pie-hole{position:absolute;inset:24%;background:#fff;border-radius:50%;display:flex;flex-direction:column;align-items:center;justify-content:center}"
                + ".pie-total{font-size:1.7rem;font-weight:700;color:#1a1a2e;line-height:1}"
                + ".pie-total-l{font-size:.56rem;text-transform:uppercase;letter-spacing:.08em;color:#888;margin-top:2px}"
                + ".pie-legend{display:flex;flex-direction:column;gap:.45rem;font-size:.85rem;min-width:210px;flex:1}"
                + ".pie-legend .li{display:flex;align-items:center;gap:.55rem}"
                + ".pie-legend .dot{width:11px;height:11px;border-radius:3px;flex:0 0 auto}"
                + ".pie-legend .lab{color:#475069}.pie-legend .n{font-weight:700;margin-left:auto;color:#1a1a2e}"
                + ".pie-legend .pct{color:#9aa1ae;min-width:42px;text-align:right}"
                + "table.impact{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e3e6eb;border-radius:10px;overflow:hidden}"
                + "table.impact th,table.impact td{text-align:left;padding:9px 12px;font-size:.88rem;border-top:1px solid #eef1f5;vertical-align:top}"
                + "table.impact thead th{background:#f1f3f6;color:#475069;font-size:.72rem;text-transform:uppercase;border-top:0}"
                + ".sev-pill{color:#fff;font-size:.7rem;font-weight:700;text-transform:uppercase;padding:2px 9px;border-radius:999px}"
                + ".gate{display:inline-block;font-weight:700;font-size:.9rem;padding:.4rem .9rem;border-radius:8px;margin:.3rem 0}"
                + ".gate-pass{background:#e6f4ea;color:#1E8E5A}.gate-fail{background:#fdecef;color:#C2122D}"
                + ".gate-warn{background:#fff4ec;color:#C2410C}"
                + ".err-note{background:#fbf7ee;border:1px solid #ecdfc4;border-left:3px solid #b5852a;border-radius:0 8px 8px 0;padding:.6rem .85rem;margin:.6rem 0}"
                + ".err-note-h{font-size:.72rem;text-transform:uppercase;letter-spacing:.04em;color:#8a6a1e;font-weight:700;margin-bottom:.3rem}"
                + ".err-note-line{font-size:.85rem;color:#475069;margin:.12rem 0}"
                + "table.actions{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e3e6eb;border-radius:10px;overflow:hidden}"
                + "table.actions th,table.actions td{text-align:left;padding:8px 12px;font-size:.86rem;border-top:1px solid #eef1f5;vertical-align:top}"
                + "table.actions thead th{background:#f1f3f6;color:#475069;font-size:.72rem;text-transform:uppercase;border-top:0}"
                + "table.actions .tbd{color:#9aa1ae;font-style:italic}"
                + ".finding-card{background:#fff;border:1px solid #e3e6eb;border-radius:10px;padding:1rem 1.25rem;margin-top:.7rem}"
                + ".finding-header{display:flex;align-items:center;gap:10px;flex-wrap:wrap}"
                + ".severity-badge{color:#fff;font-size:.7rem;font-weight:700;text-transform:uppercase;padding:2px 9px;border-radius:999px}"
                + ".sev-override{font-size:.66rem;color:#9aa1ae;margin-left:.4rem;text-transform:uppercase;letter-spacing:.03em}"
                + ".finding-title{font-weight:600}.finding-meta{color:#888;font-size:.8rem;margin-left:auto}"
                + ".conf-pill{display:inline-block;margin-left:.5rem;font-size:.72rem;font-weight:600;padding:2px 8px;"
                + "border-radius:999px;vertical-align:middle;cursor:help}"
                + ".conf-high{background:#e7f6ec;color:#1b7f4b}.conf-med{background:#fff4e0;color:#96690a}"
                + ".conf-low{background:#f0f0f0;color:#666}"
                + ".code-trace{margin-top:.55rem;font-size:.84rem;color:#475569;background:#f8fafc;border:1px solid #e5e9f0;"
                + "border-radius:6px;padding:.4rem .6rem}"
                + ".code-trace-label{font-weight:600;color:#334155;margin-right:.4rem}"
                + ".spec-locus{font-family:ui-monospace,Menlo,Consolas,monospace;color:#334155}"
                + ".affects{margin-top:.4rem;font-size:.82rem;color:#475569}"
                + ".code-link{font-family:ui-monospace,Menlo,Consolas,monospace}"
                + ".business-impact{background:#fff8f0;border-left:3px solid #fd7e14;border-radius:0 6px 6px 0;padding:.5rem .75rem;margin-top:.6rem;font-size:.88rem}"
                + ".dual-view{display:grid;grid-template-columns:1fr 1fr;gap:.75rem;margin-top:.75rem}"
                + ".evidence-panel{border:1px solid #e3e6eb;border-radius:8px;padding:.5rem .6rem;overflow:auto}"
                + ".evidence-panel.code{border-left:3px solid #0f3460}.evidence-panel.yaml-current{border-left:3px solid #dc3545}"
                + ".evidence-panel.yaml-fix{border-left:3px solid #28a745}"
                + ".ep-h{font-size:.72rem;text-transform:uppercase;color:#475069;margin-bottom:.3rem}.ep-sub{color:#888;text-transform:none}"
                + ".evidence-panel pre{margin:0;white-space:pre-wrap;word-break:break-word}"
                + "code{font-family:JetBrains Mono,ui-monospace,monospace;font-size:.8rem}"
                + ".citation{color:#8a6a1e;font-size:.82rem;font-style:italic;margin-top:.5rem}"
                + ".disp-line{font-size:.82rem;color:#475069;margin-top:.5rem}"
                + ".disp-badge{font-size:.66rem;font-weight:700;text-transform:uppercase;letter-spacing:.03em;padding:2px 9px;border-radius:999px}"
                + ".disp-ok{background:#e6f4ea;color:#1E8E5A}.disp-danger{background:#fdecef;color:#C2122D}"
                + ".disp-info{background:#e8f0fe;color:#1b5fb5}.disp-muted{background:#eef1f5;color:#475069}"
                + ".disp-by{color:#9aa1ae;margin-left:.3rem}"
                + ".count{background:#1a1a2e;color:#fff;font-size:.72rem;border-radius:999px;padding:1px 9px;margin-left:6px}"
                + ".needs-attention .ns-intro{font-size:.88rem;color:#475069}"
                + ".review-tracker{display:flex;gap:1.1rem;align-items:center;flex-wrap:wrap;margin:.7rem 0 .4rem;font-size:.85rem;color:#475069}"
                + ".rt-pill{display:inline-flex;align-items:center;gap:.35rem}.rt-pill b{font-size:1.05rem;font-weight:700}"
                + ".rt-pill.acc b{color:#1E8E5A}.rt-pill.rej b{color:#C2122D}.rt-pill.pen b{color:#475069}"
                + ".rt-of{color:#9aa1ae;font-size:.8rem}"
                + ".rt-bar{display:flex;height:8px;border-radius:999px;overflow:hidden;background:#eef1f5;margin-bottom:.7rem;max-width:520px}"
                + ".rt-bar span{display:block;height:100%}.rt-bar #rt-bar-acc{background:#1E8E5A}.rt-bar #rt-bar-rej{background:#C2122D}"
                + ".review-actions{display:flex;align-items:center;gap:.5rem;margin-top:.7rem;border-top:1px dashed #e3e6eb;padding-top:.6rem}"
                + ".review-actions button{border:1px solid #d6dae1;background:#fff;border-radius:7px;padding:.32rem .85rem;font-size:.78rem;font-weight:600;cursor:pointer}"
                + ".btn-accept:hover{background:#e6f4ea;border-color:#1E8E5A;color:#1E8E5A}.btn-reject:hover{background:#fdecef;border-color:#C2122D;color:#C2122D}"
                + ".finding-card.accepted{border-color:#bfe3cd;background:#f6fbf8}.finding-card.rejected{opacity:.55}"
                + ".finding-card.rejected .finding-title{text-decoration:line-through}"
                + ".finding-card.accepted .btn-accept{background:#1E8E5A;color:#fff;border-color:#1E8E5A}"
                + ".finding-card.rejected .btn-reject{background:#C2122D;color:#fff;border-color:#C2122D}"
                + ".review-state{margin-left:auto;font-size:.78rem;font-weight:700}.rs{display:none}"
                + ".finding-card.accepted .rs-accepted{display:inline;color:#1E8E5A}.finding-card.rejected .rs-rejected{display:inline;color:#C2122D}"
                + ".cov-warn{color:#C2410C;font-size:.9rem}.cov-ok{color:#1E8E5A;font-size:.9rem;font-weight:500}"
                + ".foot{color:#9aa1ae;font-size:.78rem;margin-top:2rem;border-top:1px solid #e3e6eb;padding-top:.8rem}";
    }
}
