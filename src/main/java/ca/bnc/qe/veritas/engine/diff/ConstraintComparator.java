package ca.bnc.qe.veritas.engine.diff;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;

/**
 * Constraint-equivalence algorithm: decides whether two {@link ConstraintSet}s describe the SAME wire constraint,
 * accounting for integer bound folding (an exclusive integer bound {@code >m} ≡ inclusive {@code >=m+1}) applied
 * per-side, and the "a null CODE-side bound/pattern is not reliable drift" rule. Pure functions, no state — extracted
 * from DiffEngine to keep the diff engine focused on emitting findings.
 */
final class ConstraintComparator {

    private ConstraintComparator() {
    }

    /** True when a side's VALUE SPACE is the integers (JSON {@code type: integer}). The bound fold is applied PER SIDE
     *  by that side's own integer-ness: a code integer field whose values are all {@code >0} is exactly {@code >=1}, but
     *  the spec side is only foldable when the spec itself declares integer — folding a (type-less / number) spec bound
     *  off the code's integer-ness would mis-equate {@code >1} with {@code >=2} and drop a real divergence. Deliberately
     *  ignores {@code format}: an int32/int64 format can legally sit on {@code type: number} (swagger accepts it). */
    static boolean isIntegerTyped(String type) {
        return "integer".equals(type);
    }

    /** First constraint keyword whose value differs between two non-empty sets, or null if equivalent. */
    static String mismatchDesc(ConstraintSet c, ConstraintSet s, boolean codeInteger, boolean specInteger) {
        if (c == null || s == null) {
            return null;
        }
        if (!Objects.equals(c.minLength(), s.minLength())) {
            return "minLength code=" + c.minLength() + " spec=" + s.minLength();
        }
        if (!Objects.equals(c.maxLength(), s.maxLength())) {
            return "maxLength code=" + c.maxLength() + " spec=" + s.maxLength();
        }
        // For an INTEGER side an exclusive bound folds into the next inclusive integer (>0 ≡ >=1, <0 ≡ <=-1), so a
        // code @Positive (minimum 0, exclusive) and a spec `minimum: 1` express the SAME constraint. The fold is keyed
        // PER SIDE by that side's own integer-ness — never the other's. Folding the spec's bound off the CODE being
        // integer would mis-equate a code `>=2` integer with a spec `>1` on a (type-less / number) field that actually
        // admits 1.5, silently dropping a real divergence.
        // Only when the CODE declares the bound: a null code minimum/maximum (no @Min/@Max, or a constant reference the
        // extractor couldn't resolve to a number) is NOT reliable drift — the bound may be enforced elsewhere — so don't
        // false-diff "code=null spec=18" (mirror of the required-drift rule). Code stricter than the spec (a non-null
        // code bound vs a looser/absent spec bound) still fires.
        Double cMin = effectiveMin(c, codeInteger);
        if (cMin != null && !Objects.equals(cMin, effectiveMin(s, specInteger))) {
            return "minimum code=" + cMin + " spec=" + effectiveMin(s, specInteger);
        }
        Double cMax = effectiveMax(c, codeInteger);
        if (cMax != null && !Objects.equals(cMax, effectiveMax(s, specInteger))) {
            return "maximum code=" + cMax + " spec=" + effectiveMax(s, specInteger);
        }
        // exclusiveMinimum/Maximum: null and false both mean "inclusive", so compare on TRUE-ness only (else a code
        // null vs a spec explicit-false would false-diff). An integer side's exclusivity is already folded into its
        // effective bound above, so it reads as inclusive here (else {0, exclusive} vs {1, inclusive} would re-diff).
        if (effectiveExclusive(c.exclusiveMin(), codeInteger) != effectiveExclusive(s.exclusiveMin(), specInteger)) {
            return "exclusiveMinimum code=" + c.exclusiveMin() + " spec=" + s.exclusiveMin();
        }
        if (effectiveExclusive(c.exclusiveMax(), codeInteger) != effectiveExclusive(s.exclusiveMax(), specInteger)) {
            return "exclusiveMaximum code=" + c.exclusiveMax() + " spec=" + s.exclusiveMax();
        }
        // ConstraintSet.format carries a semantic format the FieldModel/ParamModel.format slot does not — e.g. @Email
        // sets it to "email". Only when BOTH sides declare one (else a code null vs a spec format would double-fire with
        // the field/param-level format check, which reads the OTHER format slot).
        if (c.format() != null && s.format() != null && !c.format().equals(s.format())) {
            return "format code=" + c.format() + " spec=" + s.format();
        }
        // Only when the CODE declares a pattern: a null code pattern (no @Pattern, or a constant-reference regexp the
        // extractor couldn't resolve to a literal) is not reliable drift — don't false-diff "code=null spec=<regex>".
        if (c.pattern() != null && !c.pattern().equals(s.pattern())) {
            return "pattern code=" + c.pattern() + " spec=" + s.pattern();
        }
        if (!sameValueSet(c.enumValues(), s.enumValues())) {
            return "enum code=" + c.enumValues() + " spec=" + s.enumValues();
        }
        return null;
    }

    /** Effective INCLUSIVE lower bound: an exclusive minimum on an integer field is the next integer up (>m ≡ >=m+1).
     *  For non-integers, or an inclusive/absent bound, the raw minimum stands. */
    private static Double effectiveMin(ConstraintSet c, boolean integerField) {
        if (c == null || c.minimum() == null) {
            return null;
        }
        return integerField && Boolean.TRUE.equals(c.exclusiveMin()) ? c.minimum() + 1 : c.minimum();
    }

    /** Effective INCLUSIVE upper bound: an exclusive maximum on an integer field is the next integer down (<m ≡ <=m-1). */
    private static Double effectiveMax(ConstraintSet c, boolean integerField) {
        if (c == null || c.maximum() == null) {
            return null;
        }
        return integerField && Boolean.TRUE.equals(c.exclusiveMax()) ? c.maximum() - 1 : c.maximum();
    }

    /** Effective exclusivity of a bound: an integer side's exclusivity is folded into its effective inclusive bound
     *  (so it reads as inclusive here); a non-integer side keeps its declared exclusivity. */
    private static boolean effectiveExclusive(Boolean exclusive, boolean integerField) {
        return !integerField && Boolean.TRUE.equals(exclusive);
    }

    /** Enum equivalence is by VALUE SET, case-insensitive — declaration order and casing differences are not drift. */
    private static boolean sameValueSet(List<String> a, List<String> b) {
        return normSet(a).equals(normSet(b));
    }

    private static Set<String> normSet(List<String> v) {
        Set<String> out = new HashSet<>();
        if (v != null) {
            for (String x : v) {
                if (x != null) {
                    out.add(x.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
