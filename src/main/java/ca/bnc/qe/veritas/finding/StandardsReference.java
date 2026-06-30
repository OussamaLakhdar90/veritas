package ca.bnc.qe.veritas.finding;

/**
 * Maps each contract-validation {@link FindingType} to the authority that actually governs it. Contract
 * fidelity is an <b>API-governance</b> concern — the OpenAPI Specification, HTTP semantics (RFC 9110), and
 * JSON Schema — <b>not</b> a testing one, so ISTQB is the wrong citation for structural findings (L1–L4).
 * ISTQB applies only to the test-basis judgement (L6). References are deterministic (no LLM, no fabricated
 * section numbers).
 */
public final class StandardsReference {

    private StandardsReference() {
    }

    public static String forType(FindingType type) {
        if (type == null) {
            return "OpenAPI Specification";
        }
        return switch (type) {
            case OPENAPI_PARSE_ERROR, UNRESOLVED_REF, MISSING_INFO_FIELD, SPEC_DRIFT ->
                    "OpenAPI Specification — document structure";
            case MISSING_ENDPOINT, EXTRA_ENDPOINT, PATH_VAR_NAME_MISMATCH ->
                    "OpenAPI Specification — Paths & Operations";
            case VERB_MISMATCH ->
                    "RFC 9110 — HTTP methods";
            case PARAM_MISSING, PARAM_EXTRA, PARAM_TYPE_MISMATCH, PARAM_REQUIRED_MISMATCH ->
                    "OpenAPI Specification — Parameter Object";
            case REQUEST_BODY_PRESENCE_MISMATCH, REQUEST_BODY_SCHEMA_MISMATCH ->
                    "OpenAPI Specification — Request Body Object";
            case STATUS_CODE_MISSING, STATUS_CODE_EXTRA ->
                    "RFC 9110 — HTTP status codes";
            case RESPONSE_SCHEMA_MISMATCH ->
                    "OpenAPI Specification — Responses & Schema Object";
            case SCHEMA_FIELD_MISSING, SCHEMA_FIELD_EXTRA, SCHEMA_FIELD_TYPE_MISMATCH ->
                    "OpenAPI Specification — Schema Object";
            case CONSUMES_PRODUCES_MISMATCH ->
                    "RFC 9110 — content negotiation (media types)";
            case CONSTRAINT_GAP ->
                    "JSON Schema validation (OpenAPI Schema Object)";
            case SECURITY_MISMATCH ->
                    "OpenAPI Specification — Security Requirement Object";
            case DESIGN_QUALITY ->
                    "REST API design guidelines";
            case TEST_BASIS_GAP ->
                    "ISTQB CTFL — test basis adequacy";   // the one finding type where a testing standard fits
        };
    }
}
