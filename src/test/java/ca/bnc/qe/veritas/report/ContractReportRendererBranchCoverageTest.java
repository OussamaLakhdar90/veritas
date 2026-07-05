package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.Scan;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage-focused companion to {@link ContractReportRendererTest}: drives the score bands,
 * bottom-line verdict branches, severity buckets, business-impact rows, recommendations, disposition
 * badges, coverage verdict, trend arrows, bilingual translation, and the donut/bar split.
 */
class ContractReportRendererBranchCoverageTest {

    private final ContractReportRenderer renderer = new ContractReportRenderer();

    private Scan scan(String name) {
        Scan s = new Scan();
        s.setServiceName(name);
        return s;
    }

    /** A counted (DETERMINISTIC, non-design) finding of the given severity + bucket-driving type. */
    private Finding counted(FindingType type, Severity sev) {
        return Finding.builder()
                .findingId("c-" + type + "-" + sev)
                .type(type)
                .layer(Layer.L4)
                .severity(sev)
                .confidence(Confidence.HIGH)
                .origin("DETERMINISTIC")
                .service("svc")
                .endpoint("GET /x")
                .specSource("code-vs-spec")
                .summary(type + " on GET /x")
                .build();
    }

    // ---- score bands (cover all 4 branches of scoreBand) ------------------------------------------------

    @Test
    void cleanBandWhenNoCountedFindings() {
        String html = renderer.renderHtml(scan("svc"), List.of());
        // score 100 -> band[0]=clean, EN "Excellent — meets target", verdict "Proceed".
        assertThat(html).contains("class=\"rating rating-clean\">")
                .contains("Excellent — meets target")
                .contains("Proceed")
                .contains("No action needed");
        // Quality gate passes; no findings -> §2 prints the "no discrepancies" note and §4 is absent.
        assertThat(html).contains("class=\"gate gate-pass\">").contains("Quality gate: PASS")
                .contains("No contract discrepancies were found.")
                .doesNotContain("4. Detailed findings");
    }

    @Test
    void minorBandForScoreBetween75And89() {
        // Two MAJORs = -16 -> 84 (75..89) -> "Below target", below the 90 gate so it FAILS but no blockers. One is a
        // breaking type-mismatch, so the release verdict is the strict "Hold for fixes" (not the additive-only proceed).
        String html = renderer.renderHtml(scan("svc"),
                List.of(counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR),
                        counted(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, Severity.MAJOR)));
        assertThat(html).contains("class=\"rating rating-minor\">").contains("Below target")
                .contains("class=\"gate gate-fail\">").contains("Quality gate: FAIL")
                .contains("Hold for fixes");        // !pass && a breaking finding present
        assertThat(html).contains("84/100");
    }

    @Test
    void additiveOnlyFailingScanRendersTheGateReconciliationNote() {
        // Two additive MISSING MAJORs -> 84 (<90, FAIL) but nothing breaks -> additiveProceed -> gate-note present.
        String html = renderer.renderHtml(scan("svc"),
                List.of(counted(FindingType.SCHEMA_FIELD_MISSING, Severity.MAJOR).toBuilder().findingId("m1").build(),
                        counted(FindingType.PARAM_MISSING, Severity.MAJOR).toBuilder().findingId("m2").endpoint("GET /y").build()));
        assertThat(html).contains("class=\"gate gate-fail\">").contains("Quality gate: FAIL")
                .contains("class=\"gate-note\">")
                .contains("release risk is assessed separately")
                .contains("Proceed — documentation fixes recommended");
    }

    @Test
    void warnBandForScoreBetween50And74() {
        // One CRITICAL (-15) + two MAJOR (-16) = -31 -> 69 (50..74) -> "Needs work".
        String html = renderer.renderHtml(scan("svc"),
                List.of(counted(FindingType.RESPONSE_SCHEMA_MISMATCH, Severity.CRITICAL),
                        counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR),
                        counted(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, Severity.MAJOR)));
        assertThat(html).contains("class=\"rating rating-warn\">").contains("Needs work").contains("69/100")
                .contains("Do not release");        // a CRITICAL counts as release-blocking
    }

    @Test
    void actionBandForScoreUnder50() {
        // Two BLOCKERs (-50) -> 50? No: 100-50=50 is warn. Use three BLOCKERs (-75) -> 25 -> "At risk".
        String html = renderer.renderHtml(scan("svc"),
                List.of(counted(FindingType.MISSING_ENDPOINT, Severity.BLOCKER),
                        counted(FindingType.VERB_MISMATCH, Severity.BLOCKER),
                        counted(FindingType.PARAM_TYPE_MISMATCH, Severity.BLOCKER)));
        assertThat(html).contains("class=\"rating rating-action\">").contains("At risk").contains("25/100")
                .contains("Do not release");
    }

    // ---- buckets: MISSING / DEAD / WRONG drive §4 subsections + recommendations ------------------------

    @Test
    void missingWrongAndDeadBucketsEachGetTheirSubsectionAndRecommendation() {
        Finding missing = counted(FindingType.MISSING_ENDPOINT, Severity.MAJOR);       // type contains MISSING
        Finding wrong = counted(FindingType.VERB_MISMATCH, Severity.MINOR);            // neither -> WRONG
        Finding dead = counted(FindingType.EXTRA_ENDPOINT, Severity.MINOR);            // type contains EXTRA -> DEAD
        String html = renderer.renderHtml(scan("svc"), List.of(missing, wrong, dead));

        assertThat(html).contains("4. Detailed findings")
                .contains("4.1 Missing from the specification")
                .contains("4.2 Contract mismatches")
                .contains("4.3 Dead spec (documented, not in code)");
        // Recommendations: one per non-empty bucket + the "corrected spec" line (missing||wrong) + the re-run line.
        assertThat(html).contains("Document the 1 undocumented")
                .contains("Correct the 1 mismatch(es)")
                .contains("Remove or restore the 1 dead spec")
                .contains("Apply the corrected OpenAPI specification")
                .contains("Re-run validation after the fixes");
        // anyFix is false (no proposedFix) -> no §5 corrected-YAML section.
        assertThat(html).doesNotContain("5. Corrected OpenAPI specification");
    }

    // ---- business-impact rows: exercise all five severity strings --------------------------------------

    @Test
    void everySeverityRowRendersItsBusinessImpactText() {
        String html = renderer.renderHtml(scan("svc"), List.of(
                counted(FindingType.MISSING_ENDPOINT, Severity.BLOCKER),
                counted(FindingType.RESPONSE_SCHEMA_MISMATCH, Severity.CRITICAL),
                counted(FindingType.SCHEMA_FIELD_MISSING, Severity.MAJOR),
                counted(FindingType.PARAM_EXTRA, Severity.MINOR),
                counted(FindingType.CONSTRAINT_GAP, Severity.INFO)));
        assertThat(html)
                .contains("Blocks release")
                .contains("High risk of integration failures")
                .contains("Likely to cause client confusion")
                .contains("Low-risk drift")
                .contains("Advisory / design observation.");
        // The severity pills use the per-severity colours and capitalised labels.
        assertThat(html).contains("background:#6B21A8").contains(">Blocker<")
                .contains("background:#3A4658").contains(">Info<");
    }

    // ---- disposition badges: cover dispClass + dispLabel switch arms -----------------------------------

    @Test
    void acceptedDispositionRendersOkBadgeWithReviewerAndDate() {
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR).toBuilder()
                .status("ACCEPTED").reviewedBy("bob")
                .reviewedAt(Instant.parse("2026-01-02T03:04:00Z")).build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        assertThat(html).contains("disp-badge disp-ok").contains(">Accepted<")
                .contains("class=\"disp-by\">").contains("bob").contains("2026-01-02 03:04 UTC");
    }

    @Test
    void triagedAndJiraDispositionsUseTheInfoBadge() {
        Finding triaged = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR).toBuilder()
                .findingId("t").status("TRIAGED").build();
        Finding jira = counted(FindingType.SCHEMA_FIELD_MISSING, Severity.MAJOR).toBuilder()
                .findingId("j").status("JIRA_CREATED").build();
        String html = renderer.renderHtml(scan("svc"), List.of(triaged, jira));
        assertThat(html).contains("disp-badge disp-info").contains(">Triaged<").contains(">Defect raised<");
    }

    @Test
    void unknownDispositionUsesMutedBadgeAndEscapesRawLabel() {
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR).toBuilder()
                .status("PARKED<>").build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        // default -> muted badge; dispLabel default -> esc(status), so "<>" is HTML-escaped.
        assertThat(html).contains("disp-badge disp-muted").contains("PARKED&lt;&gt;");
    }

    @Test
    void wontFixAndFalsePositiveUseTheDangerBadge() {
        Finding wontFix = counted(FindingType.VERB_MISMATCH, Severity.MAJOR).toBuilder()
                .findingId("w").status("WONT_FIX").build();
        Finding falsePos = counted(FindingType.PARAM_EXTRA, Severity.MAJOR).toBuilder()
                .findingId("p").status("FALSE_POSITIVE").build();
        Finding fixed = counted(FindingType.SCHEMA_FIELD_MISSING, Severity.MAJOR).toBuilder()
                .findingId("x").status("FIXED").build();
        String html = renderer.renderHtml(scan("svc"), List.of(wontFix, falsePos, fixed));
        assertThat(html).contains(">Won't fix<").contains(">False positive<").contains(">Fixed<")
                .contains("disp-badge disp-danger").contains("disp-badge disp-ok");   // FIXED -> ok badge
    }

    @Test
    void openDispositionDoesNotEmitADispositionBadgeLine() {
        // status defaults to OPEN -> the audited-badge block is skipped (the `.disp-badge` token still lives in CSS,
        // so assert against the emitted markup `class="disp-line">` which only appears when a disposition is shown).
        String html = renderer.renderHtml(scan("svc"),
                List.of(counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR)));
        assertThat(html).doesNotContain("class=\"disp-line\">").doesNotContain("disp-badge disp-");
    }

    // ---- needs-attention (manual review) items: not counted, with disposition seeding ------------------

    @Test
    void manualReviewItemsAreNotCountedAndSeedAcceptedRejectedTracker() {
        // TEST_BASIS_GAP + LLM origin -> needs-attention (excluded from score). One accepted, one rejected, one pending.
        Finding accepted = Finding.builder().findingId("a").type(FindingType.DESIGN_QUALITY).layer(Layer.L5)
                .severity(Severity.MINOR).confidence(Confidence.MEDIUM).origin("LLM").service("svc")
                .specSource("code-vs-spec").endpoint("GET /a").summary("design smell A").status("ACCEPTED").build();
        Finding rejected = Finding.builder().findingId("r").type(FindingType.TEST_BASIS_GAP).layer(Layer.L6)
                .severity(Severity.MAJOR).confidence(Confidence.LOW).origin("LLM").service("svc")
                .specSource("code-vs-spec").endpoint("GET /r").summary("weak basis R").status("REJECTED").build();
        Finding pending = Finding.builder().findingId("p").type(FindingType.DESIGN_QUALITY).layer(Layer.L5)
                .severity(Severity.INFO).confidence(Confidence.MEDIUM).origin("LLM").service("svc")
                .specSource("code-vs-spec").endpoint("GET /p").summary("design smell P").build();

        String html = renderer.renderHtml(scan("svc"), List.of(accepted, rejected, pending));
        // §6 present; score is a perfect 100 because none of these count.
        assertThat(html).contains("6. Items needing manual review").contains("100/100")
                .contains("not scored");
        // Cards seeded from persisted disposition -> CSS state classes.
        assertThat(html).contains("finding-card accepted").contains("finding-card rejected");
        // Tracker seed: 1 accepted, 1 rejected, 1 pending. acc0+rej0=2 reviewed of 3; pending count = 1.
        assertThat(html).contains("review-tracker")
                .contains("id=\"rev-count\">2</span>")       // acc0 + rej0 = 2 reviewed
                .contains("id=\"rt-acc\">1</b>")             // 1 accepted
                .contains("id=\"rt-rej\">1</b>")             // 1 rejected
                .contains("id=\"rt-pen\">1</b>")             // 1 pending
                .contains("manual review");
        // §7 must downgrade to partial: the LOW-confidence rejected item's text has no source disclaimer, so
        // with no blind spots and no disclaimer it reads Full — assert the manual-review donut header instead.
        assertThat(html).contains("Manual-review items by severity");
    }

    // ---- coverage verdict §7: blindSpots branch + gap KPI plural/singular -------------------------------

    @Test
    void blindSpotsDrivePartialCoverageWarningAndGapKpi() {
        Scan s = scan("svc");
        s.setBlindSpots("Reflection-based routes not visible");
        // coverageGaps null + blindSpots present -> gaps falls back to 1 -> "1 gap" (singular).
        String html = renderer.renderHtml(s, List.of());
        assertThat(html).contains("Limitations").contains("Reflection-based routes")
                .contains("1 gap").doesNotContain("Full coverage");
    }

    @Test
    void coverageGapsCountDrivesPluralGapKpi() {
        Scan s = scan("svc");
        s.setCoverageGaps(3);
        String html = renderer.renderHtml(s, List.of());
        // gaps=3 -> "3 gaps" KPI (plural) + kpi tone minor. No blindSpots text -> §7 reads Full coverage.
        assertThat(html).contains("3 gaps").contains("class=\"kpi kpi-minor\">").contains("Full coverage");
    }

    @Test
    void zeroCoverageGapsRendersFullKpiAndCleanTone() {
        Scan s = scan("svc");
        s.setCoverageGaps(0);
        String html = renderer.renderHtml(s, List.of());
        // gaps=0 -> covValue is the bilingual "Full"/"Complète" span + clean KPI tone; §7 reads Full coverage.
        assertThat(html).contains("class=\"en\">Full</span>").contains("Complète")
                .contains("Full coverage");
    }

    // ---- trend arrows: up / down / flat ----------------------------------------------------------------

    @Test
    void trendUpWhenScoreImprovedOverPreviousScan() {
        Scan s = scan("svc");
        s.setPreviousFidelityScore(80);   // current is 100 (no findings) -> +20 up.
        String html = renderer.renderHtml(s, List.of());
        assertThat(html).contains("class=\"trend trend-up\">").contains("▲").contains("+20").contains("was 80");
    }

    @Test
    void trendDownWhenScoreRegressed() {
        Scan s = scan("svc");
        s.setPreviousFidelityScore(100);
        // Current = 92 (one MINOR -3 => 97? use a MAJOR -8 => 92) -> -8 down.
        String html = renderer.renderHtml(s, List.of(counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR)));
        assertThat(html).contains("class=\"trend trend-down\">").contains("▼").contains("-8").contains("was 100");
    }

    @Test
    void trendFlatWhenScoreUnchanged() {
        Scan s = scan("svc");
        s.setPreviousFidelityScore(100);   // current 100 -> delta 0 -> flat, sign "+".
        String html = renderer.renderHtml(s, List.of());
        assertThat(html).contains("class=\"trend trend-flat\">").contains("●").contains("+0");
    }

    @Test
    void neutralTrendLineWhenPreviousScoreAbsent() {
        // No prior score on record (fresh/reset DB, or a genuine first scan): the trend line still renders (a missing
        // line reads as a regression) but states only what is true — nothing earlier is on record — and never the
        // unprovable "first scan of this service" claim.
        String html = renderer.renderHtml(scan("svc"), List.of());
        assertThat(html).contains("class=\"trend trend-flat\">")
                .contains("no earlier score on record")           // EN neutral note
                .contains("aucun score antérieur enregistré")     // FR neutral note (bi() embeds both languages)
                .doesNotContain("first scan")                     // never the false EN claim
                .doesNotContain("première analyse");              // never the false FR claim
    }

    // ---- bilingual translation map: biDyn picks the French string ---------------------------------------

    @Test
    void bilingualMapTranslatesDynamicSummaryAndProposedFix() {
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR).toBuilder()
                .summary("Spec omits 404").explanation("Controller returns 404.")
                .proposedFix("Add a 404 response.").build();
        Map<String, String> fr = Map.of(
                "Spec omits 404", "La spéc. omet le 404",
                "Controller returns 404.", "Le contrôleur renvoie 404.",
                "Add a 404 response.", "Ajouter une réponse 404.");
        String html = renderer.renderHtml(scan("svc"), List.of(f), fr);
        // Both EN and FR spans are present (CSS toggles visibility).
        assertThat(html).contains("Spec omits 404").contains("La spéc. omet le 404")
                .contains("Le contrôleur renvoie 404.").contains("Ajouter une réponse 404.");
    }

    @Test
    void nullFrenchMapIsTreatedAsEmptyAndFallsBackToEnglish() {
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR).toBuilder()
                .summary("Untranslated summary").build();
        String html = renderer.renderHtml(scan("svc"), List.of(f), null);
        // biDyn falls back to EN for both spans when no translation is supplied.
        assertThat(html).contains("Untranslated summary");
    }

    // ---- §5 corrected-spec link + slug -----------------------------------------------------------------

    @Test
    void proposedFixEnablesCorrectedSpecSectionWithSluggedDownloadLink() {
        Finding f = counted(FindingType.MISSING_ENDPOINT, Severity.MAJOR).toBuilder()
                .proposedFix("  /x:\n    get: {}").build();
        Scan s = scan("CIAM Policies / v2");   // slug lowercases + replaces non [a-z0-9._-] runs with '-'
        String html = renderer.renderHtml(s, List.of(f));
        assertThat(html).contains("5. Corrected OpenAPI specification")
                .contains("ciam-policies-v2_corrected.yaml")
                .contains("Download the corrected OpenAPI YAML");
    }

    // ---- evidence panels: each of the three dual-view panels independently ------------------------------

    @Test
    void currentYamlOnlyPanelRendersWithoutCodeOrFix() {
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR).toBuilder()
                .codeEvidence(null)
                .currentYamlFragment("  responses:\n    '200': {}")
                .proposedFix(null).citation(null).build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        assertThat(html).contains("Current YAML").contains("'200': {}")
                .doesNotContain("Code evidence").doesNotContain("Proposed fix");
    }

    @Test
    void layerAndConfidenceLabelsAreHumanReadableNotRawCodes() {
        Finding f = Finding.builder().findingId("lc").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.MEDIUM).origin("DETERMINISTIC").service("svc")
                .endpoint("GET /x").specSource("code-vs-spec").summary("s").build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        assertThat(html).contains("Signature accuracy").contains("Medium confidence")
                .doesNotContain(" · L4 ").doesNotContain(">MEDIUM<");
    }

    @Test
    void confidencePillIsSuppressedForLlmDesignFindingsButShownForDeterministic() {
        // §6 AI design findings are labelled "raised by the assistant, not deterministically proven", so they must NOT
        // carry a "Deterministic…" confidence pill (self-contradiction). A deterministic finding still shows its pill.
        Finding llm = Finding.builder().findingId("d").type(FindingType.DESIGN_QUALITY).layer(Layer.L5)
                .severity(Severity.INFO).confidence(Confidence.MEDIUM).origin("LLM").service("svc")
                .specSource("code-vs-spec").endpoint("GET /x").summary("design smell").build();
        assertThat(renderer.renderHtml(scan("svc"), List.of(llm))).doesNotContain("Medium confidence");

        Finding det = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR);   // DETERMINISTIC, HIGH confidence
        assertThat(renderer.renderHtml(scan("svc"), List.of(det))).contains("High confidence");
    }

    @Test
    void estimatedCostClauseIsHiddenWhenCostIsZero() {
        // "Est. cost $0.0000" on a mock/free-tier run reads as broken — suppress it when the cost is zero.
        assertThat(renderer.renderHtml(scan("svc"), List.of())).doesNotContain("Est. cost");
        Scan paid = scan("svc");
        paid.setTotalEstCostUsd(0.0123);
        assertThat(renderer.renderHtml(paid, List.of())).contains("Est. cost").contains("$0.0123");
    }

    @Test
    void footerCallsAnalysisDeterministicNotReliable() {
        String html = renderer.renderHtml(scan("svc"), List.of());
        assertThat(html).contains("deterministic — not an AI guess").doesNotContain("(reliable)");
    }

    @Test
    void effortIsAQualitativeBandNotAFabricatedHourEstimate() {
        List<Finding> two = List.of(counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR),
                counted(FindingType.SCHEMA_FIELD_MISSING, Severity.MINOR));
        String html = renderer.renderHtml(scan("svc"), two);
        assertThat(html).contains("small effort").doesNotContain("rough est").doesNotContain("(approx.)");
    }

    // ---- PDF render path uses the stacked distribution bar, not the donut ------------------------------

    @Test
    void pdfPathUsesDistributionBarAndStartsWithPdfMagic() {
        Scan s = scan("svc");
        byte[] pdf = renderer.renderPdf(s,
                List.of(counted(FindingType.MISSING_ENDPOINT, Severity.MAJOR),
                        counted(FindingType.VERB_MISMATCH, Severity.MINOR),
                        counted(FindingType.EXTRA_ENDPOINT, Severity.INFO)));
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void pdfRenderHonoursTheFrenchTranslationMapOverload() {
        Finding f = counted(FindingType.MISSING_ENDPOINT, Severity.MAJOR).toBuilder()
                .summary("Spec omits the endpoint").build();
        byte[] pdf = renderer.renderPdf(scan("svc"), List.of(f),
                Map.of("Spec omits the endpoint", "La spéc. omet le point de terminaison"));
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    // ---- footnote: cost always, confidence only when > 0 -----------------------------------------------

    @Test
    void confidenceFootnoteShownOnlyWhenPositive() {
        Scan with = scan("svc");
        with.setConfidence(91.0);
        with.setTotalEstCostUsd(0.1234);
        String shown = renderer.renderHtml(with, List.of());
        assertThat(shown).contains("Explanation confidence").contains("91%").contains("$0.1234");

        Scan without = scan("svc");
        without.setConfidence(0.0);   // not > 0 -> footnote omitted
        String hidden = renderer.renderHtml(without, List.of());
        assertThat(hidden).doesNotContain("Explanation confidence");
    }

    // ---- single vs plural copy in the executive summary + bottom line ----------------------------------

    @Test
    void singularDifferenceCopyWhenExactlyOneCountedFinding() {
        String html = renderer.renderHtml(scan("svc"),
                List.of(counted(FindingType.MISSING_ENDPOINT, Severity.MAJOR)));
        // "found 1 difference" (no trailing s); bottom-line effort "1 item".
        assertThat(html).contains("found 1 difference (").contains("1 item ·");
    }

    @Test
    void pluralDifferenceCopyWhenMultipleCountedFindings() {
        String html = renderer.renderHtml(scan("svc"),
                List.of(counted(FindingType.MISSING_ENDPOINT, Severity.MAJOR),
                        counted(FindingType.VERB_MISMATCH, Severity.MINOR)));
        assertThat(html).contains("found 2 differences (").contains("2 items ·");
    }
}
