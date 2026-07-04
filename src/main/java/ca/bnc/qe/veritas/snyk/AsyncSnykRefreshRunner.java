package ca.bnc.qe.veritas.snyk;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a Snyk refresh/watch poll off the HTTP worker thread so {@code POST /snyk/refresh} (and the per-watch
 * refresh + a new watch's initial poll) return 202 immediately instead of blocking the request for the 30–60s
 * of Snyk REST calls {@link SnykPollService#pollAll()} makes. Mirrors {@code AsyncScanRunner} /
 * {@link ca.bnc.qe.veritas.snyk.fix.AsyncSnykFixRunner}: a small daemon pool + a lightweight in-progress flag
 * the controller exposes so the dashboard can show a "refreshing…" state and stop when the poll completes.
 *
 * <p>The polling/snapshot/alert/dedup semantics are unchanged — {@link SnykPollService} still owns the per-watch
 * {@code ReentrantLock}, so an overlapping refresh is still deduped. This runner only moves the slow I/O off the
 * request thread.
 */
@Component
@Slf4j
public class AsyncSnykRefreshRunner {

    private final SnykPollService pollService;
    private final SnykWatchRepository watches;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "snyk-poll");
        t.setDaemon(true);
        return t;
    });

    /** How many background refreshes are in flight — a counter (not a boolean) so a finishing poll can't clear the
     *  "running" flag out from under a still-running sibling on the 2-thread pool. */
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private volatile Instant lastRefreshedAt;

    public AsyncSnykRefreshRunner(SnykPollService pollService, SnykWatchRepository watches) {
        this.pollService = pollService;
        this.watches = watches;
    }

    /** A lightweight snapshot of the background refresh for {@code GET /snyk/refresh/status}. */
    public record RefreshStatus(boolean running, Instant lastRefreshedAt) {}

    public RefreshStatus status() {
        return new RefreshStatus(inFlight.get() > 0, lastRefreshedAt);
    }

    /** Submit a poll of every enabled watch on the background pool; returns at once (the request never blocks). */
    public void refreshAll() {
        submit(pollService::pollAll);
    }

    /** Submit a poll of one watch by id on the background pool; returns at once. Unknown ids are ignored (logged). */
    public void refresh(String watchId) {
        submit(() -> watches.findById(watchId).ifPresentOrElse(
                pollService::poll,
                () -> log.debug("Snyk refresh requested for unknown watch {} — nothing to poll", watchId)));
    }

    /**
     * Poll a just-added watch in the background. The watch row is persisted synchronously by the caller (so the UI
     * sees it immediately); this only runs its initial vulnerability poll off the request thread.
     */
    public void pollNewWatch(SnykWatch watch) {
        submit(() -> pollService.poll(watch));
    }

    /** Mark a refresh in flight, run {@code task} on the pool, then always stamp completion + decrement the counter. */
    private void submit(Runnable task) {
        inFlight.incrementAndGet();   // count before submit so status() reflects it even before the worker starts
        pool.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Background Snyk refresh failed: {}", e.getMessage());
            } finally {
                lastRefreshedAt = Instant.now();
                inFlight.decrementAndGet();
            }
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
