package ca.bnc.qe.veritas.config;

import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
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

    public MetricsConfig(MeterRegistry registry, ScanRepository scans, TestStrategyRepository strategies,
                         CostEntryRepository costs) {
        Gauge.builder("veritas.scans.total", scans, r -> r.count())
                .description("Total contract-validation scans recorded").register(registry);
        Gauge.builder("veritas.strategies.total", strategies, r -> r.count())
                .description("Total test strategies generated").register(registry);
        Gauge.builder("veritas.cost.entries.total", costs, r -> r.count())
                .description("Total LLM cost-ledger entries").register(registry);
    }
}
