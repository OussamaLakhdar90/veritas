package ca.bnc.qe.veritas.evidence.feature;

import java.time.Duration;
import java.time.Instant;
import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sweeps orphaned {@link ca.bnc.qe.veritas.persistence.FeatureIndexSnapshot} rows. A snapshot is session-scoped
 * working state for the §6 wizard (preview → edit → generate); a preview-and-leave flow persists a row that is
 * never generated from, so without a sweep the table grows unbounded on a long-running server. A snapshot is
 * reaped only once it has been <b>idle</b> (no edit/claim) past the TTL — keyed on {@code updatedAt}, so an
 * actively-edited session isn't swept out from under it — and never while a generation is in flight (a claim
 * newer than the lease is excluded). The generated strategy, if any, persists independently.
 */
@Component
@Slf4j
public class FeatureIndexSnapshotCleaner {

    private final FeatureIndexSnapshotRepository repository;
    private final Duration ttl;
    private final Duration lease;

    public FeatureIndexSnapshotCleaner(FeatureIndexSnapshotRepository repository,
                                       @Value("${veritas.multi-source.snapshot-ttl-hours:168}") long ttlHours,
                                       @Value("${veritas.multi-source.generation-lease-minutes:15}") long leaseMinutes) {
        this.repository = repository;
        this.ttl = Duration.ofHours(ttlHours);
        this.lease = Duration.ofMinutes(leaseMinutes);
    }

    @Scheduled(initialDelayString = "${veritas.multi-source.snapshot-cleanup-interval-ms:3600000}",
            fixedDelayString = "${veritas.multi-source.snapshot-cleanup-interval-ms:3600000}")
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        int deleted = repository.deleteIdleBefore(now.minus(ttl), now.minus(lease));
        if (deleted > 0) {
            log.info("Swept {} idle feature-index snapshot(s) (TTL {}h)", deleted, ttl.toHours());
        }
    }
}
