package ca.bnc.qe.veritas.codegen.plan;

/**
 * One line of the reconciliation plan: what we'd do for a single endpoint (or a leftover test). Its {@link #status}
 * drives the downstream action — generate, refactor, skip, or flag.
 *
 * <ul>
 *   <li>{@code GAP}     — an endpoint with no test we can see → generate a new test.</li>
 *   <li>{@code STALE}   — the path is tested but the verb no longer matches the API → refactor (a reviewable diff).</li>
 *   <li>{@code CURRENT} — tested and still matches → leave alone (this is what makes a re-run idempotent).</li>
 *   <li>{@code ORPHAN}  — a test points at a path that is not in the current API → flag for a human; never delete.</li>
 * </ul>
 *
 * @param status      one of GAP | STALE | CURRENT | ORPHAN
 * @param method      HTTP verb (endpoint side for GAP/STALE/CURRENT; the referenced verb for ORPHAN; may be null)
 * @param path        the endpoint path template (GAP/STALE/CURRENT) or the orphaned test path (ORPHAN)
 * @param signature   "METHOD /path" convenience label for the endpoint, or the raw path for ORPHAN
 * @param existingRef the test file backing this item (STALE/CURRENT/ORPHAN), or null for a GAP
 * @param reason      a short, human-readable explanation shown in the plan
 */
public record TestPlanItem(String status, String method, String path, String signature,
                           String existingRef, String reason) {

    public static final String GAP = "GAP";
    public static final String STALE = "STALE";
    public static final String CURRENT = "CURRENT";
    public static final String ORPHAN = "ORPHAN";
}
