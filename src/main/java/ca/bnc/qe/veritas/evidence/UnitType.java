package ca.bnc.qe.veritas.evidence;

/**
 * The kind of element a single {@link EvidenceUnit} captures. Drawn from across the source kinds:
 * intent ({@link #REQUIREMENT}, {@link #ACCEPTANCE_CRITERIA}, {@link #BUSINESS_RULE}), design ({@link #DESIGN}),
 * implementation ({@link #ENDPOINT}, {@link #DTO_CONSTRAINT}), the global static-analysis caveats that span every
 * feature ({@link #GLOBAL_CAVEAT}), and pre-authored mandatory controls ({@link #STANDARD}). See design §2.
 */
public enum UnitType {
    REQUIREMENT,
    ACCEPTANCE_CRITERIA,
    BUSINESS_RULE,
    DESIGN,
    ENDPOINT,
    DTO_CONSTRAINT,
    GLOBAL_CAVEAT,
    STANDARD
}
