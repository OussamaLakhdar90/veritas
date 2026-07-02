package ca.bnc.qe.veritas.snyk;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/** The scheduled poller is a thin, fail-safe wrapper around SnykPollService.pollAll(). */
class SnykPollerTest {

    private final SnykPollService pollService = mock(SnykPollService.class);
    private final SnykPoller poller = new SnykPoller(pollService);

    @Test
    void delegatesToPollAll() {
        poller.poll();
        verify(pollService).pollAll();
    }

    @Test
    void swallowsExceptionsSoTheSchedulerThreadSurvives() {
        doThrow(new RuntimeException("Snyk down")).when(pollService).pollAll();
        poller.poll();   // must not propagate — a failed poll can't kill the scheduler
        verify(pollService).pollAll();
    }
}
