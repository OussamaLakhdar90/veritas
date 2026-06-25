package ca.bnc.qe.veritas.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The Veritas domain gauges are registered against the real meter registry (alongside the built-in meters). */
@SpringBootTest
class MetricsConfigTest {

    @Autowired private MeterRegistry registry;

    @Test
    void registersTheDomainGauges() {
        assertThat(registry.find("veritas.scans.total").gauge()).isNotNull();
        assertThat(registry.find("veritas.strategies.total").gauge()).isNotNull();
        assertThat(registry.find("veritas.cost.entries.total").gauge()).isNotNull();
    }
}
