package ca.bnc.qe.veritas.snyk;

import java.util.List;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStatus;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import org.springframework.stereotype.Service;

/**
 * Rolls the Snyk module up into a single managerial summary — "what we found vs what we fixed" — for the executive
 * dashboard. Deterministic reads over the same repositories the page uses; no extra polling.
 */
@Service
public class SnykSummaryService {

    /** The skill the breaking-change LLM check bills under (see {@code BreakingChangeService}). */
    private static final String FIX_SKILL = "snyk-fix";

    private final SnykService snyk;
    private final SnykAlertRepository alerts;
    private final SnykFixTrainRepository trains;
    private final SnykFixStepRepository steps;
    private final CostEntryRepository costs;

    public SnykSummaryService(SnykService snyk, SnykAlertRepository alerts, SnykFixTrainRepository trains,
                              SnykFixStepRepository steps, CostEntryRepository costs) {
        this.snyk = snyk;
        this.alerts = alerts;
        this.trains = trains;
        this.steps = steps;
        this.costs = costs;
    }

    public SnykSummaryView summary() {
        List<SnykWatchView> watches = snyk.watchViews();
        int critical = watches.stream().mapToInt(SnykWatchView::critical).sum();
        int high = watches.stream().mapToInt(SnykWatchView::high).sum();
        int medium = watches.stream().mapToInt(SnykWatchView::medium).sum();
        int low = watches.stream().mapToInt(SnykWatchView::low).sum();
        int fixable = watches.stream().mapToInt(SnykWatchView::fixable).sum();
        int projects = watches.stream().mapToInt(SnykWatchView::projectCount).sum();

        return new SnykSummaryView(
                watches.size(), projects, critical, high, medium, low, fixable, alerts.countBySeenFalse(),
                trains.count(), trains.countByStatusIn(SnykFixStatus.NON_TERMINAL), trains.countByStatus(SnykFixStatus.DONE),
                trains.countByBreakingTrue(), steps.countByPrUrlIsNotNull(),
                costs.countBySkill(FIX_SKILL), round2(costs.sumEstCostUsdBySkill(FIX_SKILL)));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
