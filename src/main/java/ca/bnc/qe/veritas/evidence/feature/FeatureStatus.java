package ca.bnc.qe.veritas.evidence.feature;

/**
 * Per-feature implementation status (design §1.2/§3.1). Phase 3a computes the source-presence subset
 * ({@link #IMPLEMENTED}, {@link #PLANNED}, {@link #UNDOCUMENTED}); the finer {@link #PARTIAL} /
 * {@link #COVERAGE_GAP} states need per-endpoint code presence and the Jira lifecycle, which arrive with the
 * Jira field-widening follow-up.
 */
public enum FeatureStatus {
    /** Intent (Jira/Confluence) AND code both present. */
    IMPLEMENTED,
    /** Some of the feature's planned endpoints exist, some don't (needs per-endpoint data). */
    PARTIAL,
    /** Intent present, no code yet — design tests, mark pending. */
    PLANNED,
    /** Code present, no Jira/Confluence — "implemented but unspecified, confirm intent / scope creep". */
    UNDOCUMENTED,
    /** Marked done in Jira but no implementing code found — the highest-value RTM signal (needs lifecycle). */
    COVERAGE_GAP
}
