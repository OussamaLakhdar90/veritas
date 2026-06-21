package ca.bnc.qe.veritas.engine.model;

import java.util.List;

/** Validation constraints, normalized from Bean Validation (code) or OpenAPI schema keywords (spec). */
public record ConstraintSet(
        Integer minLength,
        Integer maxLength,
        Double minimum,
        Double maximum,
        Boolean exclusiveMin,
        Boolean exclusiveMax,
        String pattern,
        List<String> enumValues,
        String format
) {
    public static ConstraintSet empty() {
        return new ConstraintSet(null, null, null, null, null, null, null, null, null);
    }

    /** Copy with the enum values set (used when a field/param type resolves to a Java enum). */
    public ConstraintSet withEnumValues(List<String> values) {
        return new ConstraintSet(minLength, maxLength, minimum, maximum, exclusiveMin, exclusiveMax,
                pattern, values, format);
    }

    public boolean isEmpty() {
        return minLength == null && maxLength == null && minimum == null && maximum == null
                && pattern == null && (enumValues == null || enumValues.isEmpty()) && format == null;
    }
}
