package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.Scan;
import org.junit.jupiter.api.Test;

/**
 * A deterministic BLOCKER the AI disputed must NOT trigger the "Do not release" verdict, must be shown under a clear
 * "flagged by AI as a possible false positive" banner (still listed), and the bottom line must honestly note the gate
 * was conditionally relaxed — in both EN and FR. An identical non-disputed BLOCKER still blocks release.
 */
class ContractReportRendererDisputeTest {

    private static Finding blocker(boolean disputed) {
        return Finding.builder()
                .findingId("b1").type(FindingType.STATUS_CODE_MISSING).layer(Layer.L4)
                .severity(Severity.BLOCKER).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                .service("ciam-policies").endpoint("GET /policies").specSource("code-vs-repo-spec")
                .summary("Controller can return 500 but the spec omits it")
                .aiDisputed(disputed)
                .aiDisputeReason(disputed ? "the GlobalExceptionHandler maps this to 500 — the spec is right" : null)
                .build();
    }

    private static Scan scan() {
        Scan s = new Scan();
        s.setServiceName("ciam-policies");
        return s;
    }

    @Test
    void disputedBlockerIsExcludedFromTheGateAndShownAsFlagged() {
        String html = new ContractReportRenderer().renderHtml(scan(), List.of(blocker(true)));

        // Gate relaxed: no "Do not release" verdict, and the honest exclusion note is present (EN + FR copy emitted).
        assertThat(html).doesNotContain("Do not release").doesNotContain("Ne pas livrer");
        assertThat(html).contains("excluded from this gate").contains("exclu(s) de cette décision");
        // The finding is still listed, under the AI-dispute banner, with its reason — in both languages.
        assertThat(html).contains("Flagged by AI as a possible false positive")
                .contains("Signalé par l'IA comme faux positif possible")
                .contains("GlobalExceptionHandler");
    }

    @Test
    void anIdenticalNonDisputedBlockerStillBlocksRelease() {
        String html = new ContractReportRenderer().renderHtml(scan(), List.of(blocker(false)));

        assertThat(html).contains("Do not release");
        assertThat(html).doesNotContain("Flagged by AI as a possible false positive");
    }
}
