package ca.bnc.qe.veritas.codegen.plan;

import java.util.List;

/**
 * The reconciliation result the wizard shows before generating anything: for a service, what we'd add / update /
 * leave / flag, and whether this is a from-scratch or refactor run. The counts are derived from {@link #items} so the
 * UI can render summary tiles without recomputing.
 *
 * @param serviceName the service these tests cover
 * @param mode {@code SCRATCH} (no existing tests found) or {@code REFACTOR} (an existing test project was scanned)
 * @param items per-endpoint plan lines (GAP/STALE/CURRENT) plus any ORPHAN leftovers
 * @param filesScanned how many existing test files were read (0 for scratch)
 */
public record TestPlan(String serviceName, String mode, List<TestPlanItem> items, int filesScanned) {

    public static final String SCRATCH = "SCRATCH";
    public static final String REFACTOR = "REFACTOR";

    public TestPlan {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public long gaps() {
        return count(TestPlanItem.GAP);
    }

    public long stale() {
        return count(TestPlanItem.STALE);
    }

    public long current() {
        return count(TestPlanItem.CURRENT);
    }

    public long orphan() {
        return count(TestPlanItem.ORPHAN);
    }

    private long count(String status) {
        return items.stream().filter(i -> status.equals(i.status())).count();
    }
}
