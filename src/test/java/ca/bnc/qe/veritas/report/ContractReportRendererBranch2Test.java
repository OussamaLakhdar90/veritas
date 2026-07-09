package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import ca.bnc.qe.veritas.config.GateProperties;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.Scan;
import org.junit.jupiter.api.Test;

/**
 * Second branch-coverage companion to {@link ContractReportRendererTest} and
 * {@link ContractReportRendererBranchCoverageTest}: targets the red/yellow JaCoCo branches the first
 * two suites leave uncovered — null finding-type/layer/confidence, a null-location code-evidence label,
 * blank-but-non-null and reviewer-without-date dispositions, WONT_FIX/FALSE_POSITIVE manual-review seeding,
 * the singular "dead spec" copy, the L1 layer label, a Windows-backslash evidence location, a code-evidence
 * ref with no line number, the null-severity element inside the donut/bar streams, and the PDF null-fr path.
 */
class ContractReportRendererBranch2Test {

    private final ContractReportRenderer renderer = new ContractReportRenderer();

    private Scan scan(String name) {
        Scan s = new Scan();
        s.setServiceName(name);
        return s;
    }

    /** A counted (DETERMINISTIC, non-design, non-LOW) finding — affects the score/buckets. */
    private Finding.FindingBuilder counted(FindingType type, Severity sev) {
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
                .summary((type == null ? "no-type" : type.name()) + " on GET /x");
    }

    // ---- L75: bucket() with a null finding type falls through to the WRONG bucket --------------------------

    @Test
    void nullTypeFindingIsCountedAndLandsInTheWrongMismatchBucket() {
        // type == null -> bucket()'s `f.getType() != null` false branch -> t="" -> neither MISSING nor EXTRA -> WRONG.
        // DETERMINISTIC + HIGH conf + null type -> NOT needs-attention, so it is counted.
        Finding f = counted(null, Severity.MAJOR).findingId("null-type").build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        // It is counted and shows under §4.2 Contract mismatches, not 4.1/4.3.
        assertThat(html).contains("4.2 Contract mismatches")
                .contains("Correct the 1 mismatch(es)")
                .doesNotContain("4.1 Missing from the specification")
                .doesNotContain("4.3 Dead spec (documented, not in code)");
        // The severity badge still renders (empty type, real MAJOR severity).
        assertThat(html).contains(">MAJOR</span>");
    }

    // ---- L419 / L420 / L632: null layer + null confidence; and a separate L1 layer label ------------------

    @Test
    void nullLayerAndNullConfidenceOmitTheirMetaSegments() {
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR)
                .findingId("no-meta").layer(null).confidence(null).build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        // The card renders, but neither a layer label nor a confidence label appears in the meta line.
        assertThat(html).contains("STATUS_CODE_MISSING on GET /x")
                .doesNotContain("Signature accuracy")     // L4 label would appear if layer were non-null
                .doesNotContain("confidence");             // any "* confidence" label would appear if non-null
    }

    @Test
    void l1LayerRendersTheSpecificationStructureLabel() {
        // layerLabel's "L1" switch arm is the one the other suites never hit (they use L2..L6).
        Finding f = counted(FindingType.MISSING_INFO_FIELD, Severity.MINOR)
                .findingId("l1").layer(Layer.L1).endpoint(null).build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        assertThat(html).contains("Specification structure").doesNotContain(" · L1 ");
    }

    // ---- L424 / L430: blank-but-non-null status, and a reviewer with no reviewed-at date ------------------

    @Test
    void blankNonNullStatusSuppressesTheDispositionBadgeLine() {
        // status is "   " (non-null, isBlank() true) -> the `!disp.isBlank()` guard short-circuits the badge block,
        // and isAccepted/isRejected ("   " -> neither) leave the card unstyled.
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR)
                .findingId("blank").status("   ").build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        assertThat(html).doesNotContain("class=\"disp-line\">").doesNotContain("disp-badge disp-");
    }

    @Test
    void dispositionWithReviewerButNoDateOmitsTheDateSegment() {
        // reviewedBy present, reviewedAt null -> the "by <name>" span renders but the " · <date>" segment is skipped.
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR)
                .findingId("nodate").status("ACCEPTED").reviewedBy("carol").reviewedAt(null).build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        assertThat(html).contains("disp-badge disp-ok").contains(">Accepted<")
                .contains("class=\"disp-by\">").contains("carol")
                .doesNotContain(" UTC</span>");   // no formatted date appended after the reviewer name
    }

    // ---- L496 / L784 / L791: code-evidence label / loc() with a null location and a null start line --------

    @Test
    void codeEvidenceWithNullLocationYieldsAnEmptyLabelButStillRendersTheSnippet() {
        // A SourceRef with a non-blank snippet but a null location -> dual-view opens (snippet present),
        // codeEvidenceLabel is invoked, loc() returns "" (codeEvidence.location()==null), and isBlank(text) is true
        // so the label collapses to "" while the <pre><code> snippet still renders.
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR)
                .findingId("noloc")
                .codeEvidence(new SourceRef("code", null, 12, 14, "int x = doStuff();"))
                .build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        assertThat(html).contains("Code evidence")
                .contains("int x = doStuff();")
                .doesNotContain(":12</");   // no "File:line" location text because location() was null
    }

    @Test
    void codeEvidenceWithoutAStartLineDropsTheLineSuffix() {
        // location present, startLine null -> loc() returns just the file name (no ":line"), exercising the
        // `line != null` false branch.
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR)
                .findingId("nostartline")
                .codeEvidence(new SourceRef("code", "Foo.java", null, null, "return 1;"))
                .build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        // The "File.java:line" label degrades to just "Foo.java" (no colon-line suffix).
        assertThat(html).contains("Foo.java").doesNotContain("Foo.java:");
    }

    @Test
    void windowsBackslashLocationIsShortenedToTheFileName() {
        // location uses backslashes only -> loc()'s `l.contains("/")` false, `l.contains("\\")` true branch trims to
        // the trailing file segment.
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR)
                .findingId("winpath")
                .codeEvidence(new SourceRef("code", "src\\main\\java\\Bar.java", 7, 9, "doIt();"))
                .build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        assertThat(html).contains("Bar.java:7")
                .doesNotContain("src\\main\\java\\Bar.java:7");
    }

    // ---- L734: WONT_FIX / FALSE_POSITIVE seed a needs-attention card as "rejected" ------------------------

    @Test
    void manualReviewItemsWithWontFixAndFalsePositiveSeedRejectedCards() {
        // origin LLM -> needs-attention (reviewable). isRejected("WONT_FIX") and isRejected("FALSE_POSITIVE")
        // are the arms the first suite never hits through the reviewable path (it used them only as dispositions).
        Finding wontFix = Finding.builder().findingId("wf").type(FindingType.DESIGN_QUALITY).layer(Layer.L5)
                .severity(Severity.MINOR).confidence(Confidence.MEDIUM).origin("LLM").service("svc")
                .specSource("code-vs-spec").endpoint("GET /wf").summary("design smell WF").status("WONT_FIX").build();
        Finding falsePos = Finding.builder().findingId("fp").type(FindingType.TEST_BASIS_GAP).layer(Layer.L6)
                .severity(Severity.MAJOR).confidence(Confidence.MEDIUM).origin("LLM").service("svc")
                .specSource("code-vs-spec").endpoint("GET /fp").summary("weak basis FP").status("FALSE_POSITIVE").build();

        String html = renderer.renderHtml(scan("svc"), List.of(wontFix, falsePos));
        assertThat(html).contains("6. Items needing manual review")
                .contains("finding-card rejected");
        // Both seed as rejected, so the live tracker starts at 0 accepted / 2 rejected / 0 pending.
        assertThat(html).contains("id=\"rt-acc\">0</b>")
                .contains("id=\"rt-rej\">2</b>")
                .contains("id=\"rt-pen\">0</b>");
        // None are counted -> clean gate.
        assertThat(html).contains("Quality gate: PASS");
    }

    // ---- L598: exactly one DEAD finding uses the singular "stale entry" copy ------------------------------

    @Test
    void singleDeadFindingUsesSingularStaleEntryCopy() {
        // dead == 1 -> bottomLine emits "remove 1 stale entry" (singular "y"), not "entries".
        Finding dead = counted(FindingType.EXTRA_ENDPOINT, Severity.MINOR).findingId("dead1").build();
        String html = renderer.renderHtml(scan("svc"), List.of(dead));
        // bottomLine joins the clauses then capFirst()s the first character, so the singular clause reads
        // "Remove 1 stale entry" (capital R) — assert the singular "1 stale entry" form, never the plural.
        assertThat(html).contains("1 stale entry").doesNotContain("stale entries");
        // And §4.3 plus the dead recommendation are present.
        assertThat(html).contains("4.3 Dead spec (documented, not in code)")
                .contains("Remove or restore the 1 dead spec");
    }

    // ---- L524 / L565: a null-severity finding inside the counted list (donut + bar streams) ----------------

    @Test
    void nullSeverityCountedFindingIsSkippedByTheDonutStreamButStillListedInDetails() {
        // A counted finding with a null severity (DETERMINISTIC, non-design, non-LOW). It contributes nothing to the
        // donut/legend (the `f.getSeverity() != null` guard inside the per-severity stream is false for it) while a
        // real MAJOR sibling drives the single gradient stop. This exercises the short-circuit arm of those filters.
        Finding nullSev = counted(FindingType.MISSING_ENDPOINT, null).findingId("nullsev")
                .summary("endpoint missing, severity unknown").build();
        Finding major = counted(FindingType.VERB_MISMATCH, Severity.MAJOR).findingId("major-real").build();

        String htmlInteractive = renderer.renderHtml(scan("svc"), List.of(nullSev, major));
        // Donut total counts only the severity-bearing finding (1), and the legend shows exactly the MAJOR stop.
        assertThat(htmlInteractive).contains("conic-gradient(")
                .contains(">Major</span>")
                .doesNotContain("conic-gradient()");   // never an empty/invalid gradient
        // The null-severity finding is still scored as 0 penalty but appears in the detailed findings list.
        assertThat(htmlInteractive).contains("endpoint missing, severity unknown")
                .contains("4. Detailed findings");

        // PDF path runs the distributionBar version of the same filter; it must not blow up and is valid XHTML.
        byte[] pdf = renderer.renderPdf(scan("svc"), List.of(nullSev, major));
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    // ---- L55: the PDF render path with an explicitly-null French map -------------------------------------

    @Test
    void pdfRenderWithNullFrenchMapFallsBackToEmptyMap() {
        // renderPdf(scan, findings, null) -> the `fr == null` true branch substitutes Map.of() before rendering.
        byte[] pdf = renderer.renderPdf(scan("svc"),
                List.of(counted(FindingType.MISSING_ENDPOINT, Severity.MAJOR).findingId("pdf-null-fr").build()),
                null);
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    // ---- bonus: action summary uses singular "consumer-breaking change" for exactly one breaking finding --

    @Test
    void singleBreakingChangeUsesSingularCopy() {
        // breaking == 1 -> bottomLine renders breaking as a SUBSET qualifier with singular copy ("is"/"it"), not as a
        // separate double-counted list item.
        Finding blocker = counted(FindingType.MISSING_ENDPOINT, Severity.BLOCKER).findingId("oneblocker").build();
        String html = renderer.renderHtml(scan("svc"), List.of(blocker));
        assertThat(html).contains("1 of these is consumer-breaking — fix it first")
                .doesNotContain("are consumer-breaking");
        // A BLOCKER breaks a running consumer -> the verdict is "Do not release".
        assertThat(html).contains("Do not release");
    }

    // ---- raised gate caps: a WARN/PASS scan can still carry breaking findings -> the copy must be honest ---

    @Test
    void raisedBreakingCapRendersWarnWithRealBreakingCountAndPluralAction() {
        // A gate that tolerates breaking findings turns a 2-breaking scan into WARN (not FAIL). The gate line must
        // report the real breaking count (never "no breaking changes"), the Action uses the PLURAL qualifier, and the
        // "additive documentation / safe to release" note is suppressed (breaking != 0).
        GateProperties gate = new GateProperties();
        gate.setMaxBreaking(5);
        ContractReportRenderer r = new ContractReportRenderer(null, null, gate);
        List<Finding> findings = List.of(
                counted(FindingType.PARAM_TYPE_MISMATCH, Severity.MAJOR).findingId("b1").endpoint("GET /a").build(),
                counted(FindingType.PARAM_TYPE_MISMATCH, Severity.MAJOR).findingId("b2").endpoint("GET /b").build());
        String html = r.renderHtml(scan("svc"), findings);
        assertThat(html)
                .contains("Quality gate: WARN — 2 breaking change(s) within the configured cap")
                .contains("2 of these are consumer-breaking — fix those first")
                .doesNotContain("no breaking changes")
                .doesNotContain("Safe to release");
    }

    @Test
    void raisedCapsRenderPassWithABreakingChangeStillAcknowledged() {
        // Critical + breaking caps raised -> a lone CRITICAL breaking finding (no MAJOR/MINOR drift) lands on PASS;
        // the gate line must acknowledge the breaking change is within the cap, not assert "no breaking changes".
        GateProperties gate = new GateProperties();
        gate.setMaxCritical(1);
        gate.setMaxBreaking(1);
        ContractReportRenderer r = new ContractReportRenderer(null, null, gate);
        Finding f = counted(FindingType.MISSING_ENDPOINT, Severity.CRITICAL).findingId("crit1").build();
        String html = r.renderHtml(scan("svc"), List.of(f));
        assertThat(html).contains("Quality gate: PASS — 1 breaking change(s) within the configured cap");
    }

    // ---- bottomLine: empty/disputed, large effort band + plural buckets, manual-review dispositions --------

    @Test
    void onlyDisputedFindingsShowNoActionNeededPlusTheDisputeNote() {
        // A lone AI-disputed finding is needs-attention -> excluded from the counted set (total == 0 -> "No action
        // needed") but still surfaces the "AI flagged N as a possible false positive" note (disputed > 0). LOW
        // confidence + L5 layer exercise those plain-language label arms while it renders in §6.
        Finding disputed = counted(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, Severity.MAJOR)
                .findingId("disp").confidence(Confidence.LOW).layer(Layer.L5).aiDisputed(true)
                .aiDisputeReason("Likely a documented convention, not a defect.").build();
        String html = renderer.renderHtml(scan("svc"), List.of(disputed));
        assertThat(html)
                .contains("No action needed")
                .contains("flagged by the AI as a possible false positive");
    }

    @Test
    void aLargeDiverseFindingSetUsesTheLargeEffortBandAndPluralBucketCopy() {
        // 9 counted findings across all three buckets -> total > 8 ("large" effort band) with plural copy in each
        // bucket; a MEDIUM-confidence finding + L2/L3 layers exercise the remaining label arms.
        java.util.List<Finding> findings = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            findings.add(counted(FindingType.MISSING_ENDPOINT, Severity.CRITICAL)
                    .findingId("m" + i).endpoint("GET /m" + i).layer(Layer.L2).build());
            findings.add(counted(FindingType.PARAM_TYPE_MISMATCH, Severity.MAJOR)
                    .findingId("w" + i).endpoint("GET /w" + i).confidence(Confidence.MEDIUM).build());
            findings.add(counted(FindingType.EXTRA_ENDPOINT, Severity.MINOR)
                    .findingId("d" + i).endpoint("GET /d" + i).layer(Layer.L3).build());
        }
        String html = renderer.renderHtml(scan("svc"), findings);
        assertThat(html)
                .contains("large effort")
                .contains("mismatches")       // wrong bucket plural
                .contains("stale entr");      // dead bucket plural ("stale entries")
    }

    @Test
    void acceptedAndRejectedManualReviewFindingsRenderWithoutError() {
        // Two needs-attention (LOW-confidence) findings in §6 with ACCEPTED / REJECTED dispositions exercise the
        // isAccepted / isRejected card-state branches during rendering.
        Finding accepted = counted(FindingType.DESIGN_QUALITY, Severity.INFO)
                .findingId("acc").confidence(Confidence.LOW).layer(Layer.L6).status("ACCEPTED").build();
        Finding rejected = counted(FindingType.DESIGN_QUALITY, Severity.INFO)
                .findingId("rej").confidence(Confidence.LOW).layer(Layer.L6).status("REJECTED")
                .endpoint("GET /y").build();
        String html = renderer.renderHtml(scan("svc"), List.of(accepted, rejected));
        assertThat(html).isNotEmpty().contains("svc");
    }

    @Test
    void undocumentedErrorStatusesFromAdviceAreSurfacedInTheErrorNote() {
        // Needs-attention (LOW-confidence, DETERMINISTIC) STATUS_CODE_MISSING findings whose summary names an HTTP
        // status roll up into the "undocumented error responses" note: 404 across two endpoints (plural), 500 on one
        // (singular), and a summary with no status is skipped (defensive !find branch).
        String html = renderer.renderHtml(scan("svc"), List.of(
                adviceStatus("e404a", "The controller can return 404 when the policy is absent.", "GET /a"),
                adviceStatus("e404b", "A 404 is thrown by the service layer here.", "GET /b"),
                adviceStatus("e500", "This path can surface a 500 from the handler.", "GET /c"),
                adviceStatus("nostat", "An error can occur but no code is named.", "GET /d")));
        assertThat(html)
                .contains("Undocumented error responses")
                .contains("HTTP 404")
                .contains("HTTP 500");
    }

    private Finding adviceStatus(String id, String summary, String endpoint) {
        return Finding.builder().findingId(id).type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.LOW).origin("DETERMINISTIC")
                .service("svc").endpoint(endpoint).specSource("code-vs-spec").summary(summary).build();
    }

    // ---- bonus: a reviewedAt-bearing disposition formats the UTC timestamp -------------------------------

    @Test
    void instantReviewedAtRendersAsFormattedUtcTimestamp() {
        Finding f = counted(FindingType.STATUS_CODE_MISSING, Severity.MAJOR)
                .findingId("withdate").status("TRIAGED").reviewedBy("dave")
                .reviewedAt(Instant.parse("2026-05-04T09:08:00Z")).build();
        String html = renderer.renderHtml(scan("svc"), List.of(f));
        assertThat(html).contains("disp-badge disp-info").contains(">Triaged<")
                .contains("dave").contains("2026-05-04 09:08 UTC");
    }
}