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
        String format,
        /** True when {@code enumValues} were parsed from a parameter's DESCRIPTION prose, not a formal schema enum. */
        boolean enumFromDescription
) {
    /** Standard constraints — an enum here (if any) is a formal schema/Java enum, not description-derived. */
    public ConstraintSet(Integer minLength, Integer maxLength, Double minimum, Double maximum,
            Boolean exclusiveMin, Boolean exclusiveMax, String pattern, List<String> enumValues, String format) {
        this(minLength, maxLength, minimum, maximum, exclusiveMin, exclusiveMax, pattern, enumValues, format, false);
    }

    public static ConstraintSet empty() {
        return new ConstraintSet(null, null, null, null, null, null, null, null, null);
    }

    /** Copy with the enum values set (a formal schema/Java enum). */
    public ConstraintSet withEnumValues(List<String> values) {
        return new ConstraintSet(minLength, maxLength, minimum, maximum, exclusiveMin, exclusiveMax,
                pattern, values, format);
    }

    /** Copy with enum values parsed from the parameter's DESCRIPTION prose (documented, but not a formal schema enum). */
    public ConstraintSet withEnumFromDescription(List<String> values) {
        return new ConstraintSet(minLength, maxLength, minimum, maximum, exclusiveMin, exclusiveMax,
                pattern, values, format, true);
    }

    /** Copy with the length bounds dropped — for an array/collection, {@code @Size} is an item-count (minItems) bound,
     *  not string length, and there is no minItems channel, so carrying minLength/maxLength would false-diff. */
    public ConstraintSet withoutLength() {
        return new ConstraintSet(null, null, minimum, maximum, exclusiveMin, exclusiveMax, pattern, enumValues, format,
                enumFromDescription);
    }

    public boolean isEmpty() {
        return minLength == null && maxLength == null && minimum == null && maximum == null
                && pattern == null && (enumValues == null || enumValues.isEmpty()) && format == null;
    }
}
