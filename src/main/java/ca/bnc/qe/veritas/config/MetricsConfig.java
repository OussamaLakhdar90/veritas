package ca.bnc.qe.veritas.config;

import java.util.List;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.snyk.SnykAlertRepository;
import ca.bnc.qe.veritas.snyk.SnykWatchRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStatus;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Veritas domain gauges alongside Micrometer's built-in meters (HTTP request latency/count, JVM, DB
 * pool), exposed at {@code /actuator/metrics}. Gauges poll the repository counts on scrape, so they need no event
 * hooks into the services — a decoupled, zero-churn way to surface "how much has this instance done" for ops
 * dashboards. (Per-event counters/timers, e.g. tagged LLM-call volume, are a follow-up once a scraper is wired.)
 */
@Configuration
public class MetricsConfig {

    /** Fix-train stages still in flight or awaiting a human — the "active" set for the in-flight gauge. */
    private static final List<String> ACTIVE_TRAIN_STATUSES = List.of(
            SnykFixStatus.PLANNING, SnykFixStatus.AWAITING_CONFIRM, SnykFixStatus.CHECKING, SnykFixStatus.VERIFYING,
            SnykFixStatus.OPENING_PRS, SnykFixStatus.PR_OPEN, SnykFixStatus.AWAITING_MANUAL_FIX);

    public MetricsConfig(MeterRegistry registry, ScanRepository scans, TestStrategyRepository strategies,
                         CostEntryRepository costs, SnykFixTrainRepository snykFixTrains,
                         SnykWatchRepository snykWatches, SnykAlertRepository snykAlerts) {
        Gauge.builder("veritas.scans.total", scans, r -> r.count())
                .description("Total contract-validation scans recorded").register(registry);
        Gauge.builder("veritas.strategies.total", strategies, r -> r.count())
                .description("Total test strategies generated").register(registry);
        Gauge.builder("veritas.cost.entries.total", costs, r -> r.count())
                .description("Total LLM cost-ledger entries").register(registry);

        // Snyk module health — surfaced at scrape time (no event hooks), consistent with the gauges above.
        Gauge.builder("veritas.snyk.watches.total", snykWatches, r -> r.count())
                .description("Watched Snyk app-ids").register(registry);
        Gauge.builder("veritas.snyk.alerts.unseen", snykAlerts, SnykAlertRepository::countBySeenFalse)
                .description("Unseen Snyk vulnerability alerts").register(registry);
        Gauge.builder("veritas.snyk.fix.trains.total", snykFixTrains, r -> r.count())
                .description("Snyk fix trains started").register(registry);
        Gauge.builder("veritas.snyk.fix.trains.inflight", snykFixTrains,
                        r -> r.countByStatusIn(ACTIVE_TRAIN_STATUSES))
                .description("Snyk fix trains in flight or awaiting a human").register(registry);
        Gauge.builder("veritas.snyk.fix.trains.failed", snykFixTrains,
                        r -> r.countByStatus(SnykFixStatus.FAILED))
                .description("Snyk fix trains that failed").register(registry);
        Gauge.builder("veritas.snyk.fix.trains.breaking", snykFixTrains,
                        SnykFixTrainRepository::countByBreakingTrue)
                .description("Snyk fix trains flagged as a breaking change").register(registry);
    }
}
