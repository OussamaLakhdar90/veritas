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

/** Fix 4: keep §7 coverage honest — strip false "source not supplied" disclaimers when coverage is full. */
class CoverageReconcilerTest {

    private Finding llmDisclaimer(String summary) {
        return Finding.builder().findingId("x").type(FindingType.TEST_BASIS_GAP).layer(Layer.L6)
                .severity(Severity.MAJOR).confidence(Confidence.MEDIUM).origin("LLM").summary(summary).build();
    }

    private ApiModel fullCoverage() {
        return new ApiModel("code", null, null, null, List.of(), Map.of());   // back-compat ctor → no blind spots
    }

    private ApiModel partialCoverage() {
        return new ApiModel("code", null, null, null, List.of(), Map.of(),
                List.of("Type 'ExternalDto' could not be resolved from the scanned sources"));
    }

    @Test
    void detectsMissingSourcePhrasings() {
        // true disclaimers — a Veritas SOURCE was not supplied
        assertThat(CoverageReconciler.looksLikeMissingSource("exception-handler source not supplied")).isTrue();
        assertThat(CoverageReconciler.looksLikeMissingSource("DTO source not supplied")).isTrue();
        assertThat(CoverageReconciler.looksLikeMissingSource("security source not supplied")).isTrue();
        assertThat(CoverageReconciler.looksLikeMissingSource("The handler source file was not supplied in the scan.")).isTrue();
    }

    @Test
    void doesNotOverMatchUnrelatedTextContainingSourceClassFile() {
        // these are NOT coverage disclaimers and must be left untouched (regression for the substring over-match)
        assertThat(CoverageReconciler.looksLikeMissingSource("The resource is not available to anonymous consumers.")).isFalse();
        assertThat(CoverageReconciler.looksLikeMissingSource("This open-source library was not provided by the vendor.")).isFalse();
        assertThat(CoverageReconciler.looksLikeMissingSource("Outsource validation is not available downstream.")).isFalse();
        assertThat(CoverageReconciler.looksLikeMissingSource("The class hierarchy is not available for inspection here.")).isFalse();
        assertThat(CoverageReconciler.looksLikeMissingSource("No security scheme is defined for this operation.")).isFalse();
        assertThat(CoverageReconciler.looksLikeMissingSource("Inconsistent resource naming across endpoints")).isFalse();
    }

    @Test
    void stripsFalseDisclaimerWhenCoverageIsFull() {
        Finding f = llmDisclaimer("DTO source not supplied so response fields cannot be derived.");
        List<Finding> out = CoverageReconciler.stripFalseSourceDisclaimers(List.of(f), fullCoverage());
        assertThat(CoverageReconciler.anyMissingSourceDisclaimer(out)).isFalse();
    }

    @Test
    void keepsDisclaimerWhenCoverageIsPartial() {
        Finding f = llmDisclaimer("DTO source not supplied so response fields cannot be derived.");
        List<Finding> out = CoverageReconciler.stripFalseSourceDisclaimers(List.of(f), partialCoverage());
        assertThat(CoverageReconciler.anyMissingSourceDisclaimer(out)).isTrue();
    }

    @Test
    void leavesDeterministicFindingsUntouchedEvenAtFullCoverage() {
        Finding det = Finding.builder().findingId("d").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.MEDIUM).origin("DETERMINISTIC")
                .summary("Code returns 404 but the spec doesn't document it").build();
        List<Finding> out = CoverageReconciler.stripFalseSourceDisclaimers(List.of(det), fullCoverage());
        assertThat(out.get(0).getSummary()).isEqualTo("Code returns 404 but the spec doesn't document it");
    }
}
