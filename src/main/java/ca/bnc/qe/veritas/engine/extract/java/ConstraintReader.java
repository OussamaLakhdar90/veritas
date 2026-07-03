package ca.bnc.qe.veritas.engine.extract.java;

import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.firstString;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.getAnnotation;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.has;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.isJsonIgnored;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.memberExpr;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.namedMember;
import static ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor.line;
import static ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor.relPath;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.collectionElement;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.openApiType;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.simpleTypeName;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

/**
 * Reads JSR-380 / Bean Validation constraints off an annotated node and builds a DTO's {@link SchemaModel}
 * (fields + constraints, enum values, nested-ref detection). Lifted verbatim out of JavaSpringExtractor (which was
 * over the S104 line threshold) into a collaborator that static-imports {@link AnnotationSupport},
 * {@link TypeMappingSupport}, and the extractor's {@code line}/{@code relPath} source-ref helpers; call sites in the
 * extractor are unchanged (they static-import these).
 */
final class ConstraintReader {

    private ConstraintReader() {
    }

    static SchemaModel buildSchema(Path sourceRoot, TypeDeclaration<?> td, Map<String, TypeDeclaration<?>> types) {
        SourceRef src = SourceRef.code(td.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(s -> relPath(sourceRoot, s.getPath())).orElse("?"), line(td), line(td), null);
        // An enum is a string schema with an enum list — not an empty {type:object} (else it spuriously diffs
        // against the spec, which models the same enum as type:string + enum).
        if (td instanceof EnumDeclaration ed) {
            return new SchemaModel(td.getNameAsString(), "string", List.of(), enumValuesOf(ed), src);
        }
        List<FieldModel> fields = new ArrayList<>();
        collectFields(sourceRoot, td, types, fields, new HashSet<>(), new HashSet<>());
        return new SchemaModel(td.getNameAsString(), "object", fields, null, src);
    }

    static List<String> enumValuesOf(EnumDeclaration ed) {
        List<String> out = new ArrayList<>();
        ed.getEntries().forEach(e -> out.add(e.getNameAsString()));
        return out;
    }

    /** Own fields + inherited fields from superclasses present in the same source set (subclass wins on name). */
    static void collectFields(Path sourceRoot, TypeDeclaration<?> td, Map<String, TypeDeclaration<?>> types,
                               List<FieldModel> out, Set<String> seenNames, Set<String> visitedTypes) {
        if (td == null || !visitedTypes.add(td.getNameAsString())) {
            return;
        }
        if (td instanceof RecordDeclaration rec) {
            for (Parameter c : rec.getParameters()) {
                if (isJsonIgnored(c)) {
                    continue;   // not serialized → not part of the JSON contract (but @JsonIgnore(false) stays on the wire)
                }
                addField(out, seenNames, fieldOf(sourceRoot, c.getNameAsString(), c.getType(), c, types));
            }
        } else {
            for (FieldDeclaration fd : td.getFields()) {
                // Skip statics and fields excluded from JSON (@JsonIgnore) — including them produces false
                // SCHEMA_FIELD_MISSING/EXTRA diffs against a spec that (correctly) omits them.
                if (fd.isStatic() || isJsonIgnored(fd)) {
                    continue;
                }
                fd.getVariables().forEach(v ->
                        addField(out, seenNames, fieldOf(sourceRoot, v.getNameAsString(), v.getType(), fd, types)));
            }
        }
        if (td instanceof ClassOrInterfaceDeclaration coid) {
            for (ClassOrInterfaceType ext : coid.getExtendedTypes()) {
                collectFields(sourceRoot, types.get(ext.getNameAsString()), types, out, seenNames, visitedTypes);
            }
        }
    }

    static void addField(List<FieldModel> out, Set<String> seenNames, FieldModel f) {
        if (seenNames.add(f.jsonName())) {
            out.add(f);
        }
    }

    static FieldModel fieldOf(Path sourceRoot, String javaName, Type type, NodeWithAnnotations<?> annotated,
                              Map<String, TypeDeclaration<?>> types) {
        String jsonName = getAnnotation(annotated, "JsonProperty").map(a -> firstString(a, "value")).orElse(javaName);
        boolean required = has(annotated, "NotNull") || has(annotated, "NotBlank") || has(annotated, "NotEmpty");
        String simple = simpleTypeName(type);
        String[] tf = openApiType(simple);
        ConstraintSet cs = constraintsOf(annotated);
        // The field's OWN source location (file + declared line), mirroring buildSchema's type-level ref — so a
        // schema-field finding (SCHEMA_FIELD_MISSING/TYPE_MISMATCH/…) traces to the exact DTO field in the source and
        // the report can render a clickable code link. `annotated` is the field's JavaParser node (FieldDeclaration
        // for classes, Parameter for records) — both are Nodes. snippet=null (matches buildSchema): the renderer draws
        // the inline linked "File.java:line" line rather than a code panel.
        Node node = (Node) annotated;
        SourceRef fieldSrc = SourceRef.code(node.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(s -> relPath(sourceRoot, s.getPath())).orElse("?"), line(node), line(node), null);
        // Collection field (List<Foo>, Set<Foo>, Foo[]) → an ARRAY of the element type, not a single {type:object}.
        // type="array" matches the spec side; refSchema "Foo[]" lets the corrected-YAML render items (DTO elements
        // only — a scalar element stays a bare array).
        String element = collectionElement(type);
        if (element != null) {
            // byte[]/Byte[] is a BINARY payload Jackson serializes as a base64 STRING (OpenAPI string/binary), NOT a
            // JSON array — mirror the return-path unwrap() guard, else it false-diffs (array vs string) against a
            // faithful springdoc {type:string, format:byte} spec. Scoped to the ARRAY form: List<Byte> stays a real
            // array of integers.
            if (type instanceof ArrayType && ("byte".equals(element) || "Byte".equals(element))) {
                return new FieldModel(jsonName, "string", "byte", required, cs.withoutLength(), null, fieldSrc);
            }
            String elemRef = types.containsKey(element) ? element + "[]" : null;
            // @Size on a collection is an item-count (minItems) bound, not string length — drop it so it doesn't
            // false-diff as a minLength CONSTRAINT_GAP against a spec that (correctly) uses minItems.
            return new FieldModel(jsonName, "array", null, required, cs.withoutLength(), elemRef, fieldSrc);
        }
        // Enum-typed field → a string with an inline enum, not a phantom {type:object} ref.
        if (types.get(simple) instanceof EnumDeclaration ed) {
            return new FieldModel(jsonName, "string", null, required, cs.withEnumValues(enumValuesOf(ed)), null, fieldSrc);
        }
        String refSchema = "object".equals(tf[0]) && types.containsKey(simple) ? simple : null;
        return new FieldModel(jsonName, tf[0], tf[1], required, cs, refSchema, fieldSrc);
    }

    @SuppressWarnings("unchecked")
    private record LengthBounds(Integer min, Integer max) {
    }

    /** A numeric bound value plus whether it is exclusive (null = inclusive/absent). */
    private record Bound(Double value, Boolean exclusive) {
    }

    static ConstraintSet constraintsOf(NodeWithAnnotations<?> n) {
        LengthBounds len = readLengthBounds(n);
        Bound lower = readLowerBound(n);
        Bound upper = readUpperBound(n);
        String pattern = getAnnotation(n, "Pattern").map(ConstraintReader::patternRegexp).orElse(null);
        String format = has(n, "Email") ? "email" : null;
        return new ConstraintSet(len.min(), len.max(), lower.value(), upper.value(),
                lower.exclusive(), upper.exclusive(), pattern, null, format);
    }

    /** @Size min/max. NOTE: @NotBlank is captured via the field's `required` flag, NOT as minLength=1 — synthesizing a
     *  length constraint produced spurious CONSTRAINT_GAP findings against specs that (correctly) omit minLength. Parse
     *  DEFENSIVELY: a long-literal (@Min(0L)), underscored (@Size(min=1_000)), or constant (@Size(min=MAX)) value must
     *  degrade to null, NOT throw — an unguarded Integer/Double.valueOf here would abort the whole scan on idiomatic JSR-380. */
    private static LengthBounds readLengthBounds(NodeWithAnnotations<?> n) {
        Optional<AnnotationExpr> size = getAnnotation(n, "Size");
        if (size.isEmpty()) {
            return new LengthBounds(null, null);
        }
        return new LengthBounds(toInt(firstString(size.get(), "min")), toInt(firstString(size.get(), "max")));
    }

    /** Lower numeric bound folding @Min, @DecimalMin (inclusive=false → exclusive; tighter bound wins), and the
     *  @Positive(>0)/@PositiveOrZero(>=0) sign shorthands (imply a 0 bound). */
    private static Bound readLowerBound(NodeWithAnnotations<?> n) {
        Double min = null;
        Boolean exclusiveMin = null;
        Optional<AnnotationExpr> minA = getAnnotation(n, "Min");
        if (minA.isPresent()) {
            min = toDouble(firstString(minA.get(), "value"));
        }
        Optional<AnnotationExpr> decMin = getAnnotation(n, "DecimalMin");
        if (decMin.isPresent()) {
            Double v = toDouble(firstString(decMin.get(), "value"));
            if (v != null && (min == null || v > min)) {
                min = v;
                exclusiveMin = "false".equals(namedMember(decMin.get(), "inclusive")) ? Boolean.TRUE : null;
            }
        }
        if (has(n, "Positive") && (min == null || min < 0)) {
            min = 0.0;
            exclusiveMin = Boolean.TRUE;
        }
        if (has(n, "PositiveOrZero") && (min == null || min < 0)) {
            min = 0.0;
        }
        return new Bound(min, exclusiveMin);
    }

    /** Upper numeric bound folding @Max, @DecimalMax (inclusive=false → exclusive; tighter bound wins), and the
     *  @Negative(<0)/@NegativeOrZero(<=0) sign shorthands (imply a 0 bound). */
    private static Bound readUpperBound(NodeWithAnnotations<?> n) {
        Double max = null;
        Boolean exclusiveMax = null;
        Optional<AnnotationExpr> maxA = getAnnotation(n, "Max");
        if (maxA.isPresent()) {
            max = toDouble(firstString(maxA.get(), "value"));
        }
        Optional<AnnotationExpr> decMax = getAnnotation(n, "DecimalMax");
        if (decMax.isPresent()) {
            Double v = toDouble(firstString(decMax.get(), "value"));
            if (v != null && (max == null || v < max)) {
                max = v;
                exclusiveMax = "false".equals(namedMember(decMax.get(), "inclusive")) ? Boolean.TRUE : null;
            }
        }
        if (has(n, "Negative") && (max == null || max > 0)) {
            max = 0.0;
            exclusiveMax = Boolean.TRUE;
        }
        if (has(n, "NegativeOrZero") && (max == null || max > 0)) {
            max = 0.0;
        }
        return new Bound(max, exclusiveMax);
    }

    /** The @Pattern regexp value, UNESCAPED. firstString → literal(toString()) keeps the source-level double
     *  backslashes ("\\d" stays "\\d"), which false-diffs against the spec's single-backslash pattern; reading the
     *  StringLiteralExpr's asString() yields the real value ("\d"). For a NON-literal regexp (a constant reference
     *  like {@code @Pattern(regexp = RegexConstants.PHONE)}) the source text is the constant NAME, not the regex — we
     *  cannot resolve it here, so return null rather than emit "RegexConstants.PHONE" as the pattern (a guaranteed
     *  false CONSTRAINT_GAP against the spec's real regex). */
    private static String patternRegexp(AnnotationExpr a) {
        Expression e = memberExpr(a, "regexp");
        return e instanceof StringLiteralExpr sl ? sl.asString() : null;
    }

    /** Parse an int annotation value, tolerating an underscore/long suffix; null when not a literal (e.g. a constant). */
    private static Integer toInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.replace("_", "").replaceAll("[lL]$", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parse a numeric annotation value, tolerating underscores and a long/float/double suffix; null when not a literal. */
    private static Double toDouble(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.valueOf(s.replace("_", "").replaceAll("[lLfFdD]$", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
