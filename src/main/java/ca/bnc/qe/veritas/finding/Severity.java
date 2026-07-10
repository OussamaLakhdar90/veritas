package ca.bnc.qe.veritas.finding;

public enum Severity {
    BLOCKER, CRITICAL, MAJOR, MINOR, INFO,
    /** Fail-safe for a {@code FindingType} that has no explicit classification yet — surfaced for a human, never
     *  silently minor. Kept LAST so the ordinals of the established severities never shift. */
    UNSPECIFIED
}
