package ca.bnc.qe.veritas.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.persistence.ServiceCount;
import ca.bnc.qe.veritas.persistence.TestCaseRepository;
import ca.bnc.qe.veritas.persistence.TestConditionRepository;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import org.springframework.stereotype.Service;

/**
 * The service catalog: every distinct serviceName the platform holds work for, with per-stage counts. This is what
 * turns the pipeline from "write-only, find it only by retyping the exact service string" into something browsable —
 * the dashboard renders it as a picker and a per-service pipeline-status panel.
 */
@Service
public class ServiceCatalog {

    private final TestStrategyRepository strategies;
    private final TestConditionRepository conditions;
    private final TestCaseRepository cases;
    private final TestPlanRepository plans;
    private final ScanRepository scans;
    private final CodegenRunRepository codegenRuns;

    public ServiceCatalog(TestStrategyRepository strategies, TestConditionRepository conditions,
                          TestCaseRepository cases, TestPlanRepository plans, ScanRepository scans,
                          CodegenRunRepository codegenRuns) {
        this.strategies = strategies;
        this.conditions = conditions;
        this.cases = cases;
        this.plans = plans;
        this.scans = scans;
        this.codegenRuns = codegenRuns;
    }

    public List<ServiceSummary> catalog() {
        Map<String, long[]> by = new TreeMap<>();   // name → [strategies, conditions, cases, plans, scans, codegen]
        merge(by, strategies.countByServiceName(), 0);
        merge(by, conditions.countByServiceName(), 1);
        merge(by, cases.countByServiceName(), 2);
        merge(by, plans.countByServiceName(), 3);
        merge(by, scans.countByServiceName(), 4);
        merge(by, codegenRuns.countByServiceName(), 5);

        List<ServiceSummary> out = new ArrayList<>();
        by.forEach((name, c) -> out.add(new ServiceSummary(name, c[0], c[1], c[2], c[3], c[4], c[5])));
        return out;   // TreeMap → alphabetical by service name
    }

    private static void merge(Map<String, long[]> by, List<ServiceCount> rows, int idx) {
        for (ServiceCount r : rows) {
            if (r.getName() != null && !r.getName().isBlank()) {
                by.computeIfAbsent(r.getName(), k -> new long[6])[idx] += r.getCount();
            }
        }
    }
}
