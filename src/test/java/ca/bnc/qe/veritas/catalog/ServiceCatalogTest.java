package ca.bnc.qe.veritas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.persistence.ServiceCount;
import ca.bnc.qe.veritas.persistence.TestCaseRepository;
import ca.bnc.qe.veritas.persistence.TestConditionRepository;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The catalog unions distinct serviceNames across all pipeline stages with per-stage counts, sorted by name. */
@ExtendWith(MockitoExtension.class)
class ServiceCatalogTest {

    @Mock TestStrategyRepository strategies;
    @Mock TestConditionRepository conditions;
    @Mock TestCaseRepository cases;
    @Mock TestPlanRepository plans;
    @Mock ScanRepository scans;
    @Mock CodegenRunRepository codegenRuns;

    private static ServiceCount sc(String name, long count) {
        return new ServiceCount() {
            public String getName() { return name; }
            public long getCount() { return count; }
        };
    }

    @Test
    void unionsServicesAcrossStagesWithCountsSortedByName() {
        when(strategies.countByServiceName()).thenReturn(List.of(sc("ciam-policies", 2), sc("auth-svc", 1)));
        when(conditions.countByServiceName()).thenReturn(List.of(sc("ciam-policies", 9)));
        when(cases.countByServiceName()).thenReturn(List.of(sc("ciam-policies", 12)));
        when(plans.countByServiceName()).thenReturn(List.of());
        when(scans.countByServiceName()).thenReturn(List.of(sc("auth-svc", 3), sc("billing", 1)));
        when(codegenRuns.countByServiceName()).thenReturn(List.of(sc("ciam-policies", 4)));

        ServiceCatalog catalog = new ServiceCatalog(strategies, conditions, cases, plans, scans, codegenRuns);
        List<ServiceSummary> out = catalog.catalog();

        // Three distinct services, alphabetical (auth-svc, billing, ciam-policies) — including 'billing' that was
        // only ever scanned, which the scans-only Dashboard derivation would have surfaced but the pipeline pages did not.
        assertThat(out).extracting(ServiceSummary::name).containsExactly("auth-svc", "billing", "ciam-policies");
        ServiceSummary ciam = out.get(2);
        assertThat(ciam.strategies()).isEqualTo(2);
        assertThat(ciam.conditions()).isEqualTo(9);
        assertThat(ciam.cases()).isEqualTo(12);
        assertThat(ciam.codegenRuns()).isEqualTo(4);
        assertThat(ciam.plans()).isZero();
        assertThat(out.get(0).scans()).isEqualTo(3);   // auth-svc scans
    }

    @Test
    void emptyWhenNothingExists() {
        ServiceCatalog catalog = new ServiceCatalog(strategies, conditions, cases, plans, scans, codegenRuns);
        when(strategies.countByServiceName()).thenReturn(List.of());
        when(conditions.countByServiceName()).thenReturn(List.of());
        when(cases.countByServiceName()).thenReturn(List.of());
        when(plans.countByServiceName()).thenReturn(List.of());
        when(scans.countByServiceName()).thenReturn(List.of());
        when(codegenRuns.countByServiceName()).thenReturn(List.of());
        assertThat(catalog.catalog()).isEmpty();
    }
}
