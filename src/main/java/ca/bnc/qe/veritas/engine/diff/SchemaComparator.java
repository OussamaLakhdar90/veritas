package ca.bnc.qe.veritas.engine.diff;

import static ca.bnc.qe.veritas.engine.diff.DiffEngine.arrayRef;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;

/**
 * Schema/field-level comparison of two canonical {@link SchemaModel}s — the resolved-structure verdict, the
 * binding-driven nested field diff, and the same-name field/type/constraint diff. Pure functions, no state — extracted
 * from DiffEngine to keep the diff engine under the god-class threshold; the finding factory ({@code DiffEngine.finding})
 * and the array-ref helper ({@code DiffEngine.arrayRef}) stay in DiffEngine and are called back into from here.
 */
final class SchemaComparator {

    private SchemaComparator() {
    }

    enum SchemaVerdict { MATCH, DIFFER, UNRESOLVED }

    /** Bound on nested-schema recursion when comparing structure (cycles are also guarded by a visited set). */
    static final int MAX_SCHEMA_DEPTH = 8;

    /** Known scalar/primitive ref names (Java type names + OpenAPI scalar types) — used to tell a provable
     *  scalar-vs-object response break apart from a genuinely-unresolvable external DTO. */
    private static final Set<String> SCALAR_REF_NAMES = Set.of(
            "string", "integer", "int", "long", "short", "byte", "boolean", "double", "float", "number",
            "bigdecimal", "biginteger", "character", "char", "uuid", "date", "localdate", "localdatetime",
            "instant", "offsetdatetime", "zoneddatetime", "void");

    static void fieldDiffByBinding(List<Finding> findings, ApiModel code, ApiModel spec, String codeRef, String specRef,
                                    String locus, Set<String> visited, int depth) {
        if (arrayRef(codeRef) != arrayRef(specRef)) {
            return;   // array-vs-object is the structuralVerdict's call, not a field diff
        }
        SchemaModel cs = code.schemas().get(baseName(codeRef));
        SchemaModel ss = spec.schemas().get(baseName(specRef));
        if (cs == null || ss == null || structureless(cs) || suppressStructurelessSpec(spec, ss)) {
            return;   // unresolved / opaque — owned by structuralVerdict + extractor blind spots
        }
        String key = baseName(codeRef) + "|" + baseName(specRef);
        if (!visited.add(key)) {
            return;   // cycle guard
        }
        try {
            // Same-name pairs are already field-diffed by the components-schema loop (dedup collapses any overlap),
            // so only run the diff here for the differently-named bound pairs that loop never reaches. The match must
            // be CASE-SENSITIVE to mirror that loop's exact-key `spec.schemas().get(name)`: a case-skew pair
            // (code `OrderResponse` vs spec `orderresponse`) is NOT covered by the components loop, so it must run here
            // — otherwise an array-of-DTO-vs-scalar field flip (now delegated to compareSchema) would be dropped.
            if (!baseName(codeRef).equals(baseName(specRef))) {
                compareSchema(findings, spec.source(), locus, cs, ss);
            }
            if (depth <= 0) {
                return;
            }
            Map<String, FieldModel> cf = fieldsByName(cs);
            Map<String, FieldModel> sf = fieldsByName(ss);
            for (Map.Entry<String, FieldModel> e : cf.entrySet()) {
                FieldModel c = e.getValue();
                FieldModel s = sf.get(e.getKey());
                if (s == null) {
                    continue;
                }
                // One side binds a nested object/array DTO (refSchema != null), the other is a bare SCALAR — a provable
                // wire-shape break (a JSON object/array can never equal a scalar). The compareSchema type guard lets
                // "object" through, so this nested object-vs-scalar flip would otherwise be silently dropped.
                if ((c.refSchema() != null) != (s.refSchema() != null)) {
                    String refType = c.refSchema() != null ? c.type() : s.type();
                    String scalarType = c.refSchema() != null ? s.type() : c.type();
                    // Emit ONLY for the flip compareSchema silently drops: a single nested OBJECT (type "object", or an
                    // untyped $ref) vs a concrete SCALAR. An ARRAY-of-DTO (type "array") vs a scalar is already
                    // reported as an array-vs-scalar type mismatch — by compareSchema for differently-named pairs, or
                    // by the components-schema loop for same-named — so re-emitting here would double-count one defect.
                    if (scalarType != null && !"object".equals(scalarType) && !"array".equals(refType)) {
                        findings.add(DiffEngine.finding(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, locus + "." + e.getKey(),
                                spec.source(), "Field '" + e.getKey() + "' of " + locus + " is "
                                        + (c.refSchema() != null
                                            ? "a nested object/array in code but a scalar (" + s.type() + ") in the spec"
                                            : "a scalar (" + c.type() + ") in code but a nested object/array in the spec"),
                                null, Confidence.HIGH));
                    }
                    continue;
                }
                if (c.refSchema() == null || s.refSchema() == null) {
                    continue;   // pair nested DTOs only where the binding field exists on both sides
                }
                if (arrayRef(c.refSchema()) != arrayRef(s.refSchema())) {
                    // A nested field that is an array on one side and a single object on the other — a real wire-shape
                    // break the recursion would otherwise drop (fieldDiffByBinding returns early on array-vs-object).
                    findings.add(DiffEngine.finding(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, locus + "." + e.getKey(), spec.source(),
                            "Field '" + e.getKey() + "' of " + locus + " is "
                                    + (arrayRef(c.refSchema()) ? "an array in code but a single object in the spec"
                                                               : "a single object in code but an array in the spec"),
                            null, Confidence.HIGH));
                    continue;
                }
                fieldDiffByBinding(findings, code, spec, c.refSchema(), s.refSchema(),
                        locus + "." + e.getKey(), visited, depth - 1);
            }
        } finally {
            visited.remove(key);
        }
    }

    /**
     * Decide whether two differently-named success-response schemas are a genuine contract break by comparing their
     * RESOLVED property structure rather than their names. Returns {@code DIFFER} only when the structures truly
     * diverge; {@code UNRESOLVED} when either side can't be looked up or carries no structure to compare (a
     * name-compare there is exactly the false positive we are removing — such external/opaque DTOs are already
     * recorded as extractor blind spots).
     */
    static SchemaVerdict structuralVerdict(ApiModel code, ApiModel spec, String codeRef, String specRef) {
        if (arrayRef(codeRef) != arrayRef(specRef)) {
            return SchemaVerdict.DIFFER;   // array vs single object is a real shape difference
        }
        SchemaModel cs = code.schemas().get(baseName(codeRef));
        SchemaModel ss = spec.schemas().get(baseName(specRef));
        // A KNOWN scalar (String/Integer/...) on exactly one side, against a structured object on the other, is a
        // PROVABLE shape break — a bare JSON string can never equal an object. Don't fold it into UNRESOLVED (which is
        // reserved for genuinely opaque/external DTOs and would suppress the mismatch).
        // A side counts as scalar ONLY when its name is a known scalar AND it is NOT a registered structured schema —
        // otherwise a code/spec DTO that happens to be named "Instant"/"Date"/"Number" would falsely read as a scalar
        // and a structurally-equal object pair would DIFFER on the name alone.
        boolean codeScalar = isScalarName(baseName(codeRef)) && cs == null;
        boolean specScalar = isScalarName(baseName(specRef)) && ss == null;
        if (codeScalar != specScalar) {
            SchemaModel object = codeScalar ? ss : cs;
            if (object != null && !structureless(object)) {
                return SchemaVerdict.DIFFER;
            }
        }
        if (cs == null || ss == null || structureless(cs) || suppressStructurelessSpec(spec, ss)) {
            return SchemaVerdict.UNRESOLVED;
        }
        return propsEqual(code, spec, cs, ss, MAX_SCHEMA_DEPTH, new HashSet<>())
                ? SchemaVerdict.MATCH : SchemaVerdict.DIFFER;
    }

    private static boolean isScalarName(String ref) {
        return ref != null && SCALAR_REF_NAMES.contains(ref.toLowerCase(Locale.ROOT));
    }

    static String baseName(String ref) {
        return ref == null ? null : ref.replace("[]", "");
    }

    /** A schema we cannot structurally compare: no extracted fields and no enum values. */
    private static boolean structureless(SchemaModel s) {
        boolean noFields = s.fields() == null || s.fields().isEmpty();
        boolean noEnum = s.enumValues() == null || s.enumValues().isEmpty();
        return noFields && noEnum;
    }

    /** Whether to suppress (treat as UNRESOLVED) a structureless SPEC schema. Suppress by default — the ONLY case the
     *  code's fields should surface as SCHEMA_FIELD_MISSING is a GENUINELY-EMPTY DECLARED object: type:object, no
     *  properties, and no composition blind spot (i.e. under-documentation). A bare-$ref alias / external $ref (type
     *  != "object") or a composition-opaque schema (oneOf/anyOf/unresolvable allOf, blind-spotted) stays suppressed,
     *  so neither produces a false SCHEMA_FIELD_MISSING. */
    static boolean suppressStructurelessSpec(ApiModel spec, SchemaModel ss) {
        if (!structureless(ss)) {
            return false;
        }
        boolean genuinelyEmptyObject = "object".equals(ss.type()) && !specSchemaComposed(spec, ss);
        return !genuinelyEmptyObject;
    }

    /** True when the extractor recorded a composition blind spot for a spec schema (oneOf/anyOf/unresolvable allOf). */
    private static boolean specSchemaComposed(ApiModel spec, SchemaModel ss) {
        if (spec.blindSpots() == null || ss == null || ss.name() == null) {
            return false;
        }
        String marker = "'" + ss.name() + "'";
        return spec.blindSpots().stream()
                .anyMatch(b -> b != null && b.contains(marker) && b.contains("composition"));
    }

    /** Structural equality: enum value sets, or same field-name set with compatible field types, recursing into nested
     * $ref'd schemas up to {@link #MAX_SCHEMA_DEPTH} (a visited-pair set guards cyclic DTO graphs). */
    private static boolean propsEqual(ApiModel code, ApiModel spec, SchemaModel cs, SchemaModel ss, int depth,
                               Set<String> visited) {
        String key = cs.name() + "|" + ss.name();
        if (!visited.add(key)) {
            return true;   // this pair is already being compared higher up the stack — break the cycle
        }
        // Scope the guard to genuine stack ANCESTORS: removing on exit means a pair truncated by depth on one path
        // can still be fully compared when reached via a shorter path, so a deep diff isn't wrongly memoized as MATCH.
        try {
            boolean cEnum = cs.enumValues() != null && !cs.enumValues().isEmpty();
            boolean sEnum = ss.enumValues() != null && !ss.enumValues().isEmpty();
            if (cEnum || sEnum) {
                return cEnum && sEnum && normSet(cs.enumValues()).equals(normSet(ss.enumValues()));
            }
            Map<String, FieldModel> cf = fieldsByName(cs);
            Map<String, FieldModel> sf = fieldsByName(ss);
            if (!cf.keySet().equals(sf.keySet())) {
                return false;
            }
            for (Map.Entry<String, FieldModel> e : cf.entrySet()) {
                FieldModel c = e.getValue();
                FieldModel s = sf.get(e.getKey());
                if (!typeCompatible(c, s)) {
                    return false;
                }
                if (depth > 0 && c.refSchema() != null && s.refSchema() != null) {
                    if (arrayRef(c.refSchema()) != arrayRef(s.refSchema())) {
                        return false;
                    }
                    SchemaModel nc = code.schemas().get(baseName(c.refSchema()));
                    SchemaModel ns = spec.schemas().get(baseName(s.refSchema()));
                    // recurse only when both nested schemas resolve; an unresolved nested side never invents a diff
                    if (nc != null && ns != null && !propsEqual(code, spec, nc, ns, depth - 1, visited)) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            visited.remove(key);
        }
    }

    private static Map<String, FieldModel> fieldsByName(SchemaModel s) {
        Map<String, FieldModel> m = new LinkedHashMap<>();
        if (s.fields() != null) {
            s.fields().forEach(f -> m.put(f.jsonName(), f));
        }
        return m;
    }

    /** Field types are compatible when equal, or when either side is null/object (same wildcard rule as compareSchema). */
    private static boolean typeCompatible(FieldModel a, FieldModel b) {
        if (a.type() == null || b.type() == null || "object".equals(a.type()) || "object".equals(b.type())) {
            return true;
        }
        return a.type().equals(b.type());
    }

    static String normRef(String ref) {
        return ref == null ? null : ref.replace("[]", "").toLowerCase(Locale.ROOT);
    }

    /** True if the constraint set declares a non-empty enum (allowed-value set). */
    static boolean hasEnum(ConstraintSet cs) {
        return cs != null && cs.enumValues() != null && !cs.enumValues().isEmpty();
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

    static void compareSchema(List<Finding> findings, String specSource, String name, SchemaModel codeS, SchemaModel specS) {
        Map<String, FieldModel> specFields = new LinkedHashMap<>();
        specS.fields().forEach(f -> specFields.put(f.jsonName(), f));
        Map<String, FieldModel> codeFields = new LinkedHashMap<>();
        codeS.fields().forEach(f -> codeFields.put(f.jsonName(), f));
        for (FieldModel cf : codeS.fields()) {
            FieldModel sf = specFields.get(cf.jsonName());
            if (sf == null) {
                findings.add(DiffEngine.finding(FindingType.SCHEMA_FIELD_MISSING, name + "." + cf.jsonName(), specSource,
                        "Field '" + cf.jsonName() + "' of " + name + " is in code but missing from the spec schema",
                        null, Confidence.HIGH));
                continue;
            }
            if (cf.type() != null && sf.type() != null && !cf.type().equals(sf.type())
                    && !"object".equals(cf.type()) && !"object".equals(sf.type())) {
                findings.add(DiffEngine.finding(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, name + "." + cf.jsonName(), specSource,
                        "Field '" + cf.jsonName() + "' type — code " + cf.type() + " vs spec " + sf.type(),
                        null, Confidence.MEDIUM));
            }
            // required drift — ONLY the faithful direction: the code POSITIVELY asserts the field is required
            // (@NotNull/@NotBlank/@NotEmpty) but the spec marks it optional. The reverse (code false, spec required)
            // is NOT reliable drift — code-side `required=false` means "no bean-validation annotation here", not
            // "optional" (the field may be validated in the service/constructor), so flagging it would be a false
            // positive on every conforming spec-required field that the code doesn't annotate (esp. response DTOs).
            if (cf.required() && !sf.required()) {
                findings.add(DiffEngine.finding(FindingType.CONSTRAINT_GAP, name + "." + cf.jsonName(), specSource,
                        "Field '" + cf.jsonName() + "' is required in code but optional in the spec",
                        null, Confidence.MEDIUM));
            }
            // format divergence (date vs date-time, int32 vs int64, …) — only when both declare a format.
            if (cf.format() != null && sf.format() != null && !cf.format().equals(sf.format())) {
                findings.add(DiffEngine.finding(FindingType.CONSTRAINT_GAP, name + "." + cf.jsonName(), specSource,
                        "Field '" + cf.jsonName() + "' format — code " + cf.format() + " vs spec " + sf.format(),
                        null, Confidence.LOW));
            }
            if (!cf.constraints().isEmpty()) {
                if (sf.constraints().isEmpty()) {
                    findings.add(DiffEngine.finding(FindingType.CONSTRAINT_GAP, name + "." + cf.jsonName(), specSource,
                            "Field '" + cf.jsonName() + "' has code constraints not exposed in the spec", null, Confidence.MEDIUM));
                } else {
                    String diff = ConstraintComparator.mismatchDesc(cf.constraints(), sf.constraints(),
                            ConstraintComparator.isIntegerTyped(cf.type()), ConstraintComparator.isIntegerTyped(sf.type()));
                    if (diff != null) {
                        findings.add(DiffEngine.finding(FindingType.CONSTRAINT_GAP, name + "." + cf.jsonName(), specSource,
                                "Field '" + cf.jsonName() + "' constraint mismatch — " + diff, null, Confidence.MEDIUM));
                    }
                }
            }
        }
        for (FieldModel sf : specS.fields()) {
            if (!codeFields.containsKey(sf.jsonName())) {
                findings.add(DiffEngine.finding(FindingType.SCHEMA_FIELD_EXTRA, name + "." + sf.jsonName(), specSource,
                        "Field '" + sf.jsonName() + "' of " + name + " is in the spec but not in code", null, Confidence.LOW));
            }
        }
    }
}
