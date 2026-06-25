package ca.bnc.qe.veritas.evidence.feature;

/**
 * The kinds of coverage gap the deterministic {@link GapDetector} surfaces from a {@link FeatureIndex} (design §3.3).
 * These are the highest-value RTM signals — derived for free from the per-feature status and the clustering — and
 * they make the headline "Jira says X, code does Y" risk a deterministic output, not something the LLM has to notice.
 *
 */
public enum GapKind {
    /** Intent (Jira/Confluence) exists for a feature, but no implementing code was found — tests are pending. */
    PLANNED_NOT_IMPLEMENTED,
    /** Code exists for a feature with no Jira/Confluence behind it — implemented but unspecified (scope creep risk). */
    IMPLEMENTED_UNDOCUMENTED,
    /** Marked <b>done</b> in Jira but no implementing code was found — the highest-value RTM signal (needs lifecycle). */
    COVERAGE_GAP,
    /** An intent-only feature and a code-only feature look related but clustered apart — the intent↔impl gap may be invisible. */
    POSSIBLE_MISCLUSTER
}
