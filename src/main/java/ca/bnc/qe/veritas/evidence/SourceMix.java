package ca.bnc.qe.veritas.evidence;

/**
 * Which evidence sources actually contributed to a run — computed from {@link FetchProvenance} <b>successes</b>,
 * not from what the user selected, so a half-empty fetch can't make the pipeline claim evidence it doesn't have
 * (design §1, §1.3). Fed into the synthesis prompt so the model never asserts something a missing source can't
 * support. {@link SourceKind#POLICY} is always available (pre-authored).
 */
public record SourceMix(boolean code, boolean jira, boolean confluence) {

    public boolean has(SourceKind kind) {
        return switch (kind) {
            case CODE -> code;
            case JIRA -> jira;
            case CONFLUENCE -> confluence;
            case POLICY -> true;
        };
    }

    public boolean any() {
        return code || jira || confluence;
    }

    /** A factual, code-only strategy (no business-intent claims possible). */
    public boolean codeOnly() {
        return code && !jira && !confluence;
    }
}
