package ca.bnc.qe.veritas.engine.openapi;

import java.util.Locale;

/**
 * Deterministic presence facts about a spec, computed from a FULLY-resolved parse (so {@code $ref}-based examples
 * and {@code $ref}-bound schema properties/constraints count as present). Used to fact-check the LLM's L5/L6
 * "absence" judgements: the engine must never let an AI claim "no examples / no properties / no error responses"
 * stand when the resolved spec actually contains them.
 */
public record SpecPresence(
        boolean anyResponseHasExamples,
        boolean anySchemaHasProperties,
        boolean anySchemaHasConstraints,
        boolean anyErrorResponseDeclared
) {
    public static SpecPresence empty() {
        return new SpecPresence(false, false, false, false);
    }

    public SpecPresence merge(SpecPresence o) {
        if (o == null) {
            return this;
        }
        return new SpecPresence(
                anyResponseHasExamples || o.anyResponseHasExamples,
                anySchemaHasProperties || o.anySchemaHasProperties,
                anySchemaHasConstraints || o.anySchemaHasConstraints,
                anyErrorResponseDeclared || o.anyErrorResponseDeclared);
    }

    /**
     * True when an LLM design finding asserts the absence of something the resolved spec actually has — and EVERY
     * absence claim it makes is contradicted (so the finding is wholly false). A finding that also makes a genuinely
     * true claim (e.g. the spec really has no constraints) is kept, to avoid over-suppressing real gaps.
     */
    public boolean contradictsAbsenceClaim(String summary) {
        if (summary == null) {
            return false;
        }
        String s = summary.toLowerCase(Locale.ROOT);
        if (!assertsAbsence(s)) {
            return false;
        }
        boolean claimsExamples = s.contains("example");
        boolean claimsProps = s.contains("propert") || s.contains("field");
        boolean claimsConstraints = s.contains("constraint");
        boolean claimsErrors = s.contains("error response") || s.contains("error code") || s.contains("error status");
        if (!(claimsExamples || claimsProps || claimsConstraints || claimsErrors)) {
            return false;   // not an absence-of-something-we-track claim
        }
        // Keep the finding if any claim it makes is genuinely true (not contradicted by the facts).
        if (claimsExamples && !anyResponseHasExamples) {
            return false;
        }
        if (claimsProps && !anySchemaHasProperties) {
            return false;
        }
        if (claimsConstraints && !anySchemaHasConstraints) {
            return false;
        }
        if (claimsErrors && !anyErrorResponseDeclared) {
            return false;
        }
        return true;
    }

    private static boolean assertsAbsence(String s) {
        return s.contains("no ") || s.contains("not ") || s.contains("without") || s.contains("missing")
                || s.contains("absent") || s.contains("lacks") || s.contains("none");
    }
}
