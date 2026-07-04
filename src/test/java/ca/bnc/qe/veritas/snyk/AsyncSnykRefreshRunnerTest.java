package ca.bnc.qe.veritas.snyk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The async contract of the Snyk refresh runner: the poll runs on the background pool (never the calling thread),
 * and {@link AsyncSnykRefreshRunner#status()} reports "running" while a poll is in flight and clears when it ends.
 * Async outcomes are asserted with Mockito {@code timeout} verifications (the work runs on a daemon worker).
 */
class AsyncSnykRefreshRunnerTest {

    private static final long ASYNC_MS = 5_000;

    private final SnykPollService pollService = mock(SnykPollService.class);
    private final SnykWatchRepository watches = mock(SnykWatchRepository.class);
    private final AsyncSnykRefreshRunner runner = new AsyncSnykRefreshRunner(pollService, watches);

    @Test
    void refreshAllRunsThePollOnABackgroundThreadNotTheCaller() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(pollService.pollAll()).thenAnswer(inv -> {
            started.countDown();
            release.await(ASYNC_MS, TimeUnit.MILLISECONDS);   // hold the "Snyk REST calls" open so the caller can't block on it
            return 0;
        });

        runner.refreshAll();   // must return immediately even though pollAll is still blocked on the pool

        assertThat(started.await(ASYNC_MS, TimeUnit.MILLISECONDS)).isTrue();   // the poll started on the pool
        assertThat(runner.status().running()).isTrue();                       // and the caller already saw it in-flight
        release.countDown();
        // The pool clears the in-flight flag + stamps the completion time once pollAll returns.
        waitUntil(() -> !runner.status().running());
        assertThat(runner.status().lastRefreshedAt()).isNotNull();
        verify(pollService).pollAll();
    }

    @Test
    void refreshResolvesAndPollsAKnownWatchInTheBackground() {
        SnykWatch w = new SnykWatch();
        w.setId("w1");
        when(watches.findById("w1")).thenReturn(Optional.of(w));

        runner.refresh("w1");

        verify(pollService, timeout(ASYNC_MS)).poll(w);
    }

    @Test
    void refreshOfAnUnknownWatchIsANoOpNotAnError() {
        when(watches.findById("gone")).thenReturn(Optional.empty());

        runner.refresh("gone");

        waitUntil(() -> !runner.status().running());   // the background task ran and cleared its flag
        verify(pollService, never()).poll(any());
    }

    @Test
    void pollNewWatchPollsTheGivenWatchInTheBackground() {
        SnykWatch w = new SnykWatch();
        w.setId("w2");

        runner.pollNewWatch(w);

        verify(pollService, timeout(ASYNC_MS)).poll(w);
    }

    /** Spin-wait (bounded) until a condition holds — for asserting the background flag cleared after a task ran. */
    private static void waitUntil(java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + ASYNC_MS;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(cond.getAsBoolean()).as("condition should hold within %d ms", ASYNC_MS).isTrue();
    }
}
