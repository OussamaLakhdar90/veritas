package ca.bnc.qe.veritas.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import ca.bnc.qe.veritas.persistence.DefectLink;
import org.junit.jupiter.api.Test;

/** Deterministic defect aggregates: totals, open/closed, and distributions by severity / status / service. */
class DefectMetricsTest {

    private static DefectLink link(String severity, String service, String statusCategory) {
        DefectLink d = new DefectLink();
        d.setSeverity(severity);
        d.setServiceName(service);
        d.setJiraStatusCategory(statusCategory);
        return d;
    }

    @Test
    void aggregatesTotalsOpenClosedAndDistributions() {
        DefectMetrics m = DefectMetrics.of(List.of(
                link("HIGH", "ciam-policies", "done"),
                link("HIGH", "ciam-policies", "in progress"),
                link("MEDIUM", "auth-svc", null),     // never synced → counts as open + an "unsynced" bucket
                link(null, "ciam-policies", "to do")));

        assertThat(m.total()).isEqualTo(4);
        assertThat(m.closed()).isEqualTo(1);   // only the 'done' one
        assertThat(m.open()).isEqualTo(3);
        assertThat(m.bySeverity()).contains(entry("HIGH", 2L), entry("MEDIUM", 1L), entry("UNKNOWN", 1L));
        assertThat(m.byStatusCategory()).contains(entry("done", 1L), entry("unsynced", 1L));
        assertThat(m.byService()).contains(entry("ciam-policies", 3L), entry("auth-svc", 1L));
    }

    @Test
    void emptyIsAllZero() {
        DefectMetrics m = DefectMetrics.of(List.of());
        assertThat(m.total()).isZero();
        assertThat(m.open()).isZero();
        assertThat(m.bySeverity()).isEmpty();
    }
}
