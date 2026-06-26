package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import org.junit.jupiter.api.Test;

/**
 * Mutation-killing tests for {@link FidelityScore} and {@link CoverageReconciler}.
 *
 * <p>Each test pins an EXACT concrete value (a score number or a cleaned-string) so that a surviving PIT mutant
 * — a changed penalty weight, a negated conditional, a swapped return value — produces a different observable
 * result and fails the assertion. These are intentionally separate from the existing test files (never edited).
 */
class FidelityScoreMutationTest {

    // ----- builders ---------------------------------------------------------

    /** A counted finding: deterministic origin, HIGH confidence, non-design type — so it is NOT "needs attention". */
    private static Finding counted(Severity sev) {
        return Finding.builder()
                .findingId("c-" + sev)
                .type(FindingType.STATUS_CODE_MISSING)   // non-design → not excluded
                .layer(Layer.L4)
                .severity(sev)
                .confidence(Confidence.HIGH)             // not LOW → not excluded
                .origin("DETERMINISTIC")                 // not LLM → not excluded
                .summary("counted " + sev)
                .build();
    }

    // ========================================================================
    // FidelityScore.of() — penalty weights (IncrementsMutator on each case arm)
    // ========================================================================

    /** Kills IncrementsMutator on line 26: one BLOCKER must subtract exactly 25 → score 75 (was NO_COVERAGE). */
    @Test
    void blockerPenaltyIsExactly25() {
        assertThat(FidelityScore.of(List.of(counted(Severity.BLOCKER)))).isEqualTo(75);
    }

    /** Kills IncrementsMutator on line 27: one CRITICAL must subtract exactly 15 → score 85. */
    @Test
    void criticalPenaltyIsExactly15() {
        assertThat(FidelityScore.of(List.of(counted(Severity.CRITICAL)))).isEqualTo(85);
    }

    /** Pins MAJOR at exactly 8 → score 92 (guards the line-28 weight against drift). */
    @Test
    void majorPenaltyIsExactly8() {
        assertThat(FidelityScore.of(List.of(counted(Severity.MAJOR)))).isEqualTo(92);
    }

    /** Kills IncrementsMutator on line 29: one MINOR must subtract exactly 3 → score 97. */
    @Test
    void minorPenaltyIsExactly3() {
        assertThat(FidelityScore.of(List.of(counted(Severity.MINOR)))).isEqualTo(97);
    }

    /** INFO (and the default switch arm) must add ZERO penalty → score stays 100. */
    @Test
    void infoSeverityAddsNoPenalty() {
        assertThat(FidelityScore.of(List.of(counted(Severity.INFO)))).isEqualTo(100);
    }

    /**
     * Distinguishes the exact mix: BLOCKER(25)+CRITICAL(15)+MAJOR(8)+MINOR(3) = 51 → 49. If any single weight
     * mutated the total would differ, so this pins the whole additive model in one assertion.
     */
    @Test
    void mixedSeveritiesSumToExactScore() {
        int score = FidelityScore.of(List.of(
                counted(Severity.BLOCKER), counted(Severity.CRITICAL),
                counted(Severity.MAJOR), counted(Severity.MINOR)));
        assertThat(score).isEqualTo(49);
    }

    /** Math/clamp on line 33: penalty 100 (4×BLOCKER=100) → Math.max(0, 0) = 0, never negative. */
    @Test
    void scoreClampsAtZeroNeverNegative() {
        int score = FidelityScore.of(List.of(
                counted(Severity.BLOCKER), counted(Severity.BLOCKER),
                counted(Severity.BLOCKER), counted(Severity.BLOCKER),
                counted(Severity.BLOCKER)));   // 5×25 = 125 penalty → clamp to 0
        assertThat(score).isEqualTo(0);
    }

    // ========================================================================
    // FidelityScore.isNeedsAttention() — negated-conditional survivors
    // ========================================================================

    /**
     * Kills NegateConditionals on line 38 (`f.getType() != null`). A DESIGN_QUALITY blocker that is otherwise
     * "counted" must be EXCLUDED via the designOnly path, so the score stays 100. If the type!=null guard flips,
     * the type string is read as "" → designOnly=false → the blocker would count → score 75.
     */
    @Test
    void designQualityFindingIsExcludedSoBlockerDoesNotCount() {
        Finding design = Finding.builder().findingId("dq").type(FindingType.DESIGN_QUALITY).layer(Layer.L5)
                .severity(Severity.BLOCKER).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                .summary("design smell").build();
        assertThat(FidelityScore.isNeedsAttention(design)).isTrue();
        assertThat(FidelityScore.of(List.of(design))).isEqualTo(100);
    }

    /** TEST_BASIS_GAP is the other designOnly type — also excluded (covers the second equals on line 39). */
    @Test
    void testBasisGapFindingIsExcluded() {
        Finding gap = Finding.builder().findingId("tbg").type(FindingType.TEST_BASIS_GAP).layer(Layer.L6)
                .severity(Severity.BLOCKER).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                .summary("no test basis").build();
        assertThat(FidelityScore.isNeedsAttention(gap)).isTrue();
        assertThat(FidelityScore.of(List.of(gap))).isEqualTo(100);
    }

    /**
     * Kills NegateConditionals on line 40 (`f.getOrigin() != null`). A finding with a NULL origin is treated as
     * NOT-LLM (origin!=null is false), so a non-design HIGH-confidence blocker is COUNTED → score 75. If the
     * guard flips to (origin!=null)==true, the code evaluates null.equalsIgnoreCase(...) → NPE, which fails this
     * test (PIT records the thrown exception as a kill).
     */
    @Test
    void nullOriginIsTreatedAsNonLlmAndCounts() {
        Finding nullOrigin = Finding.builder().findingId("no").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.BLOCKER).confidence(Confidence.HIGH).origin(null)
                .summary("code returns 500 not documented").build();
        assertThat(FidelityScore.isNeedsAttention(nullOrigin)).isFalse();
        assertThat(FidelityScore.of(List.of(nullOrigin))).isEqualTo(75);
    }

    /** An LLM-origin finding is excluded (llm=true), so its blocker does NOT count → score 100. */
    @Test
    void llmOriginFindingIsExcluded() {
        Finding llm = Finding.builder().findingId("llm").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.BLOCKER).confidence(Confidence.HIGH).origin("LLM")
                .summary("ai-judged").build();
        assertThat(FidelityScore.isNeedsAttention(llm)).isTrue();
        assertThat(FidelityScore.of(List.of(llm))).isEqualTo(100);
    }

    /** LOW confidence excludes a finding (lowConf=true) even when deterministic + non-design → score 100. */
    @Test
    void lowConfidenceFindingIsExcluded() {
        Finding lowConf = Finding.builder().findingId("lc").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.BLOCKER).confidence(Confidence.LOW).origin("DETERMINISTIC")
                .summary("low-confidence").build();
        assertThat(FidelityScore.isNeedsAttention(lowConf)).isTrue();
        assertThat(FidelityScore.of(List.of(lowConf))).isEqualTo(100);
    }

    /** Empty list → perfect 100; PASS_THRESHOLD constant must remain 90. */
    @Test
    void emptyFindingsScorePerfectAndThresholdConstant() {
        assertThat(FidelityScore.of(List.of())).isEqualTo(100);
        assertThat(FidelityScore.PASS_THRESHOLD).isEqualTo(90);
    }

    // ========================================================================
    // CoverageReconciler
    // ========================================================================

    private static Finding llm(String summary, String explanation) {
        return Finding.builder().findingId("x").type(FindingType.TEST_BASIS_GAP).layer(Layer.L6)
                .severity(Severity.MAJOR).confidence(Confidence.MEDIUM).origin("LLM")
                .summary(summary).explanation(explanation).build();
    }

    private static ApiModel fullCoverage() {
        return new ApiModel("code", null, null, null, List.of(), Map.of());   // no blind spots
    }

    /**
     * Kills BooleanTrueReturnVals on line 37 (`if (findings == null) return false;`). A null collection must
     * return false, not true.
     */
    @Test
    void anyMissingSourceDisclaimerOnNullReturnsFalse() {
        assertThat(CoverageReconciler.anyMissingSourceDisclaimer(null)).isFalse();
    }

    /**
     * Kills NegateConditionals on line 88 (`cleaned.isEmpty()`) AND EmptyObjectReturnVals on line 88
     * (return value replaced with ""). The summary keeps one clean sentence after the false disclaimer is
     * stripped, so the cleaned result is that exact surviving sentence — not the empty-fallback string and
     * not "".
     */
    @Test
    void strippingKeepsTheSurvivingSentenceVerbatim() {
        Finding f = llm("The DTO source was not supplied in the scan. Response uses HTTP 200.", null);
        List<Finding> out = CoverageReconciler.stripFalseSourceDisclaimers(List.of(f), fullCoverage());
        assertThat(out.get(0).getSummary()).isEqualTo("Response uses HTTP 200.");
    }

    /**
     * Kills NegateConditionals on line 82 (`if (sb.length() > 0)` — the inter-sentence space separator). Two
     * clean sentences survive the strip, so they must be joined by a single space. A flipped guard would drop
     * the space between them (yielding "...HTTP 200.Status is documented.").
     */
    @Test
    void twoSurvivingSentencesAreJoinedByASingleSpace() {
        Finding f = llm(
                "The DTO source was not supplied in the scan. Response uses HTTP 200. Status is documented.", null);
        List<Finding> out = CoverageReconciler.stripFalseSourceDisclaimers(List.of(f), fullCoverage());
        assertThat(out.get(0).getSummary()).isEqualTo("Response uses HTTP 200. Status is documented.");
    }

    /**
     * When EVERY sentence is a false disclaimer, the cleaned string is empty so line 88 returns the fallback
     * literal (kills both the isEmpty-negate and the empty-return mutants on the empty branch).
     */
    @Test
    void allDisclaimerSentencesCollapseToTheFallbackLiteral() {
        Finding f = llm("The DTO source was not supplied. The handler source is not provided.", null);
        List<Finding> out = CoverageReconciler.stripFalseSourceDisclaimers(List.of(f), fullCoverage());
        assertThat(out.get(0).getSummary()).isEqualTo("Veritas parsed all referenced sources for this scan.");
    }

    /**
     * Kills EmptyObjectReturnVals on line 75 (`if (text == null) return null;` → `return ""`). The finding's
     * summary disclaims (so it is processed), but its explanation is null. The cleaned explanation must remain
     * null, not "".
     */
    @Test
    void nullExplanationStaysNullAfterStripping() {
        Finding f = llm("The DTO source was not supplied. Response uses HTTP 200.", null);
        List<Finding> out = CoverageReconciler.stripFalseSourceDisclaimers(List.of(f), fullCoverage());
        assertThat(out.get(0).getExplanation()).isNull();
    }

    /**
     * Kills NegateConditionals on line 60 (the second operand of `disclaims = looksLikeMissingSource(summary)
     * || looksLikeMissingSource(explanation)`). Here the SUMMARY is clean but the EXPLANATION carries the false
     * disclaimer, so disclaims must be true and the explanation must be cleaned. If the explanation check is
     * flipped off, the finding is left untouched and the disclaimer survives.
     */
    @Test
    void disclaimerOnlyInExplanationIsStillStripped() {
        Finding f = llm("Endpoint returns 201 on create.",
                "The DTO source was not supplied. Fields cannot be derived from it.");
        List<Finding> out = CoverageReconciler.stripFalseSourceDisclaimers(List.of(f), fullCoverage());
        assertThat(CoverageReconciler.looksLikeMissingSource(out.get(0).getExplanation())).isFalse();
        assertThat(out.get(0).getExplanation()).isEqualTo("Fields cannot be derived from it.");
        // summary untouched
        assertThat(out.get(0).getSummary()).isEqualTo("Endpoint returns 201 on create.");
    }
}