package ca.bnc.qe.veritas.finding;

/** Deterministic finding taxonomy (computed without the LLM). Mapped to layers + severities by the engine. */
public enum FindingType {
    // L1 — structural
    OPENAPI_PARSE_ERROR,
    UNRESOLVED_REF,
    MISSING_INFO_FIELD,
    // L2/L3 — coverage
    MISSING_ENDPOINT,        // in code, not in spec
    EXTRA_ENDPOINT,          // in spec, not in code (dead spec)
    // L4 — signature
    VERB_MISMATCH,
    PATH_VAR_NAME_MISMATCH,
    PARAM_MISSING,
    PARAM_EXTRA,
    PARAM_TYPE_MISMATCH,
    PARAM_REQUIRED_MISMATCH,
    REQUEST_BODY_PRESENCE_MISMATCH,
    STATUS_CODE_MISSING,
    STATUS_CODE_EXTRA,
    RESPONSE_SCHEMA_MISMATCH,
    SCHEMA_FIELD_MISSING,
    SCHEMA_FIELD_EXTRA,
    SCHEMA_FIELD_TYPE_MISMATCH,
    CONSUMES_PRODUCES_MISMATCH,
    // L4/L6 — constraints / security
    CONSTRAINT_GAP,
    SECURITY_MISMATCH,
    // Spec-vs-spec (repo YAML vs Confluence YAML)
    SPEC_DRIFT,
    // L5/L6 — LLM judgment (design quality / test-basis adequacy)
    DESIGN_QUALITY,
    TEST_BASIS_GAP
}
