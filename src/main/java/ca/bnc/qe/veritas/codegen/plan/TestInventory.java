package ca.bnc.qe.veritas.codegen.plan;

import java.util.List;

/**
 * What a scan of an existing test project found: the endpoint references it appears to exercise plus how many files
 * were read. An empty inventory means "no test project yet" — the reconciler treats that as the from-scratch case.
 *
 * @param references endpoint references discovered across the scanned test sources (may contain duplicates by path)
 * @param filesScanned number of test source files read (for transparency in the plan)
 */
public record TestInventory(List<TestReference> references, int filesScanned) {

    public TestInventory {
        references = references == null ? List.of() : List.copyOf(references);
    }

    /** No references found — the existing project (if any) exercises nothing we can see, i.e. treat as from-scratch. */
    public boolean isEmpty() {
        return references.isEmpty();
    }

    public static TestInventory empty() {
        return new TestInventory(List.of(), 0);
    }
}
