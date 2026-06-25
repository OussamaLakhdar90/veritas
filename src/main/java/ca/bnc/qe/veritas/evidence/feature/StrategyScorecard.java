package ca.bnc.qe.veritas.evidence.feature;

import java.util.List;

/**
 * A deterministic ($0) quality scorecard for an assembled multi-source strategy (design §5b): a set of pass/fail
 * checks over the deliverable + feature index, an overall {@code verdict} ({@code OK} | {@code DEGRADED}), and a
 * derived confidence. It makes the silent-failure surfaces the pipeline adds (a dropped section, a feature with a
 * risk register but no test approach, an implemented feature with no strategy, a pending feature never raised as a
 * gap) <b>visible and citable</b> rather than something a reviewer has to notice — so a degraded run says so.
 *
 * @param verdict     {@code OK} when every check passes, else {@code DEGRADED}
 * @param confidence  0–100, the share of checks that passed (the strategy's self-review confidence)
 * @param checks      each rule's result
 * @param featuresCovered features with at least one generated section
 * @param droppedSections sections that couldn't be grounded and were omitted
 */
public record StrategyScorecard(
        String verdict,
        int confidence,
        List<Check> checks,
        int featuresCovered,
        int droppedSections) {

    public static final String OK = "OK";
    public static final String DEGRADED = "DEGRADED";

    public StrategyScorecard {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public boolean degraded() {
        return DEGRADED.equals(verdict);
    }

    /** One scorecard rule: its name, whether it passed, and a reviewer-facing detail. */
    public record Check(String name, boolean passed, String detail) {}
}
