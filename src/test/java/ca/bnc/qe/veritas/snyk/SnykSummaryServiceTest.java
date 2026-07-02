package ca.bnc.qe.veritas.snyk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStatus;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import org.junit.jupiter.api.Test;

/** The managerial roll-up aggregates open severities across watches and the fix/PR/cost activity. */
class SnykSummaryServiceTest {

    private final SnykService snyk = mock(SnykService.class);
    private final SnykAlertRepository alerts = mock(SnykAlertRepository.class);
    private final SnykFixTrainRepository trains = mock(SnykFixTrainRepository.class);
    private final SnykFixStepRepository steps = mock(SnykFixStepRepository.class);
    private final CostEntryRepository costs = mock(CostEntryRepository.class);
    private final SnykSummaryService service = new SnykSummaryService(snyk, alerts, trains, steps, costs);

    private SnykWatchView watch(int c, int h, int m, int l, int fixable, int projects) {
        return new SnykWatchView("w", "o", "app", "App", "t", "application-tests", true,
                c, h, m, l, fixable, projects, null);
    }

    @Test
    void aggregatesOpenSeveritiesAndFixActivity() {
        when(snyk.watchViews()).thenReturn(List.of(watch(2, 1, 3, 0, 1, 4), watch(1, 2, 0, 5, 2, 6)));
        when(alerts.countBySeenFalse()).thenReturn(3L);
        when(trains.count()).thenReturn(9L);
        when(trains.countByStatusIn(anyList())).thenReturn(2L);
        when(trains.countByStatus(SnykFixStatus.DONE)).thenReturn(5L);
        when(trains.countByBreakingTrue()).thenReturn(1L);
        when(steps.countByPrUrlIsNotNull()).thenReturn(18L);
        when(costs.countBySkill("snyk-fix")).thenReturn(9L);
        when(costs.sumEstCostUsdBySkill("snyk-fix")).thenReturn(0.4249);

        SnykSummaryView s = service.summary();

        assertThat(s.watchedApps()).isEqualTo(2);
        assertThat(s.critical()).isEqualTo(3);   // 2 + 1
        assertThat(s.high()).isEqualTo(3);        // 1 + 2
        assertThat(s.low()).isEqualTo(5);
        assertThat(s.fixable()).isEqualTo(3);
        assertThat(s.projects()).isEqualTo(10);
        assertThat(s.openTotal()).isEqualTo(14);
        assertThat(s.fixesMerged()).isEqualTo(5);
        assertThat(s.prsOpened()).isEqualTo(18);
        assertThat(s.llmCostUsd()).isEqualTo(0.42);   // rounded to cents
    }
}
