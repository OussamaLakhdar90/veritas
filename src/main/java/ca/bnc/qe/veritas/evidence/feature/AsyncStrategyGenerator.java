package ca.bnc.qe.veritas.evidence.feature;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshot;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs the (paid, multi-minute) multi-source strategy synthesis OFF the request thread so the §6 wizard can return
 * 202 immediately and poll. Mirrors {@code AsyncScanRunner}: a small daemon pool, capture the snapshot's data on the
 * request thread, then on a worker call {@link MultiSourceStrategyService#generateFromIndex} and record the outcome
 * via the snapshot lifecycle — {@link FeatureIndexSnapshotService#linkGenerated} on success,
 * {@link FeatureIndexSnapshotService#failGeneration} on failure (which the poll surfaces).
 *
 * <p>The one-shot CLAIM is taken synchronously by the controller BEFORE submitting here (so a duplicate/concurrent
 * generate is a fast 409), and this worker only ever runs for the POST that won the claim — preserving the
 * no-double-spend guarantee. It uses the id-scoped {@code linkGenerated}/{@code failGeneration} bulk updates, never
 * a {@code save} of the detached claimed entity, so it can't stomp a concurrent edit's optimistic version.
 */
@Component
@Slf4j
public class AsyncStrategyGenerator {

    private final FeatureIndexSnapshotService snapshots;
    private final MultiSourceStrategyService strategyService;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "veritas-strategy-gen");
        t.setDaemon(true);
        return t;
    });

    public AsyncStrategyGenerator(FeatureIndexSnapshotService snapshots, MultiSourceStrategyService strategyService) {
        this.snapshots = snapshots;
        this.strategyService = strategyService;
    }

    /** Capture the claimed snapshot's data on the request thread, hand synthesis to a worker, and return its id. */
    public String submit(FeatureIndexSnapshot claimed) {
        return submit(claimed, null, null);
    }

    /**
     * As {@link #submit(FeatureIndexSnapshot)} but with a prior index + strategy for INCREMENTAL reuse (a lineage
     * re-run): the worker reuses unchanged-feature sections from {@code priorStrategy}, paying only for what changed.
     */
    public String submit(FeatureIndexSnapshot claimed, FeatureIndexResult priorIndex, TestStrategy priorStrategy) {
        String id = claimed.getId();
        String serviceName = claimed.getServiceName();
        String owner = claimed.getOwner();
        FeatureIndexResult index = snapshots.resultOf(claimed);   // cheap JSON read now, so the worker holds no entity
        pool.submit(() -> run(id, serviceName, index, owner, priorIndex, priorStrategy));
        return id;
    }

    private void run(String id, String serviceName, FeatureIndexResult index, String owner,
                     FeatureIndexResult priorIndex, TestStrategy priorStrategy) {
        try {
            TestStrategy strategy = priorStrategy != null
                    ? strategyService.generateFromIndex(serviceName, index, owner, priorIndex, priorStrategy)
                    : strategyService.generateFromIndex(serviceName, index, owner);
            snapshots.linkGenerated(id, strategy.getId());
            log.info("Generated multi-source strategy {} from snapshot {}", strategy.getId(), id);
        } catch (RuntimeException e) {
            log.error("Strategy generation from snapshot {} failed: {}", id, e.getMessage(), e);
            snapshots.failGeneration(id, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
