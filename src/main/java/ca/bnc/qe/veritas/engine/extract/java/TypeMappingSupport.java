package ca.bnc.qe.veritas.engine.extract.java;

import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.memberExpr;
import static ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor.MAP_LIKE;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;

/**
 * Stateless helpers that map a JavaParser {@link Type} to the extractor's response/schema model — the response-body
 * unwrap of transparent/envelope/array/paged wrappers, the Java→OpenAPI scalar mapping, collection/optional element
 * recovery, command-object (implicit @ModelAttribute) detection, and HTTP-verb resolution. Lifted verbatim out of
 * JavaSpringExtractor (which was well over the S104 line threshold) into a collaborator that static-imports
 * {@link AnnotationSupport}; call sites in the extractor are unchanged (they static-import these).
 */
final class TypeMappingSupport {

    private TypeMappingSupport() {
    }

    static List<HttpMethod> verbsFrom(AnnotationExpr rm) {
        Expression me = memberExpr(rm, "method");
        if (me == null) {
            return List.of(HttpMethod.GET);
        }
        List<Expression> elems = me instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(me);
        List<HttpMethod> out = new ArrayList<>();
        for (Expression e : elems) {
            out.add(verbFrom(e.toString()));
        }
        return out.isEmpty() ? List.of(HttpMethod.GET) : out;
    }

    // ---- mapping detection ----

    static HttpMethod verbFrom(String requestMethod) {
        for (HttpMethod hm : HttpMethod.values()) {
            if (requestMethod.toUpperCase(Locale.ROOT).contains(hm.name())) {
                return hm;
            }
        }
        return HttpMethod.GET;
    }

    // ---- type unwrapping ----

    record BodyType(String typeName, boolean array, boolean noBody, boolean responseEntity) {
        String schemaRef() {
            if (typeName == null) {
                return null;
            }
            return array ? typeName + "[]" : typeName;
        }
    }

    static BodyType unwrap(Type type, List<String> blindSpots) {
        // A Java array return: byte[]/Byte[] is a BINARY payload (OpenAPI string/binary), not a JSON array — letting the
        // literal "byte[]" leak would make DiffEngine.arrayRef read it as an array and false-diff vs a string spec.
        if (type instanceof ArrayType at) {
            String elem = simpleTypeName(at.getComponentType());
            if ("byte".equals(elem) || "Byte".equals(elem)) {
                return new BodyType("string", false, false, false);
            }
            BodyType e = unwrap(at.getComponentType(), blindSpots);
            return new BodyType(e.typeName(), true, e.typeName() == null, false);
        }
        String simple = simpleTypeName(type);
        if (simple == null || "void".equals(simple) || "Void".equals(simple)) {
            return new BodyType(null, false, true, false);
        }
        if (type instanceof ClassOrInterfaceType cit && cit.getTypeArguments().isPresent()
                && !cit.getTypeArguments().get().isEmpty()) {
            String outer = cit.getNameAsString();
            Type inner = cit.getTypeArguments().get().get(0);
            boolean re = outer.equals("ResponseEntity") || outer.equals("HttpEntity");
            // Transparent wrappers — unwrap to the inner body type (envelope/async/reactive-single).
            // ENVELOPE_WRAPPERS only unwrap when parameterized (e.g. ApiResponse<User>); a bare type is left as-is.
            if (re || TRANSPARENT_WRAPPERS.contains(outer) || ENVELOPE_WRAPPERS.contains(outer)) {
                BodyType b = unwrap(inner, blindSpots);
                return new BodyType(b.typeName(), b.array(), b.noBody(), re || b.responseEntity());
            }
            // Bare-array wrappers — the body is a JSON array of the inner type.
            if (ARRAY_WRAPPERS.contains(outer)) {
                BodyType b = unwrap(inner, blindSpots);
                return new BodyType(b.typeName(), true, b.typeName() == null, false);
            }
            // Spring Data Page/Slice + HATEOAS PagedModel/CollectionModel serialize as an OBJECT envelope
            // ({content:[...], totalElements, ...} / {_embedded, page}), NOT a bare array — modelling them as T[]
            // forces a false array-vs-object RESPONSE_SCHEMA_MISMATCH against the (correct) paged-object spec.
            if (PAGED_OBJECT_WRAPPERS.contains(outer)) {
                blindSpots.add("Return type " + outer + "<" + simpleTypeName(inner) + "> is a paged/HATEOAS object "
                        + "envelope; its field-by-field shape is not modelled — verify the paged wrapper against the spec.");
                return new BodyType(null, false, false, false);   // unknown object body → no array-vs-object diff
            }
        }
        // Raw ResponseEntity/HttpEntity with no generics → genuinely unknown body, NOT a 'ResponseEntity' schema.
        if ("ResponseEntity".equals(simple) || "HttpEntity".equals(simple)) {
            return new BodyType(null, false, false, true);
        }
        // A Map/dictionary body is a free-form object — model it as an unspecified body, never a phantom 'Map'
        // schema ref that resolves to nothing (the value type is a known simplification, not surfaced here).
        if (MAP_LIKE.contains(simple)) {
            return new BodyType(null, false, false, false);
        }
        // primitive/wrapper or DTO return — still a body (openApiType decides scalar vs object schema).
        return new BodyType(simple, false, false, false);
    }

    /** Framework-injected or simple/value parameter types that Spring resolves directly — never a command object. */
    private static final Set<String> INJECTED_OR_SIMPLE = Set.of(
            "HttpServletRequest", "HttpServletResponse", "ServletRequest", "ServletResponse", "Principal",
            "Authentication", "Model", "ModelMap", "BindingResult", "Errors", "Locale", "TimeZone", "ZoneId",
            "WebRequest", "NativeWebRequest", "HttpSession", "HttpMethod", "UriComponentsBuilder", "InputStream",
            "OutputStream", "Reader", "Writer", "RedirectAttributes", "SessionStatus", "MultipartFile",
            "MultipartHttpServletRequest", "HttpEntity", "RequestEntity", "Object",
            "String", "CharSequence", "Integer", "Long", "Short", "Byte", "Boolean", "Double", "Float", "BigDecimal",
            "BigInteger", "Character", "UUID", "Date", "LocalDate", "LocalDateTime", "LocalTime", "Instant",
            "OffsetDateTime", "ZonedDateTime");

    /** True when an UNANNOTATED parameter is a command object Spring binds as an implicit @ModelAttribute: a scanned
     *  DTO class (not an interface), excluding injected/simple types and params that carry a (non-marker) annotation. */
    static boolean isImplicitModelAttribute(Parameter p, Map<String, TypeDeclaration<?>> types) {
        boolean onlyMarkers = p.getAnnotations().stream().allMatch(a ->
                a.getNameAsString().equals("Valid") || a.getNameAsString().equals("Validated"));
        if (!onlyMarkers) {
            return false;   // an annotated param is resolved by something specific (e.g. @AuthenticationPrincipal)
        }
        String simple = simpleTypeName(p.getType());
        return simple != null && !INJECTED_OR_SIMPLE.contains(simple)
                && types.get(simple) instanceof ClassOrInterfaceDeclaration cid && !cid.isInterface();
    }

    /** Wrappers whose single type argument IS the response body (Spring envelopes, reactive-single, async). */
    private static final Set<String> TRANSPARENT_WRAPPERS = Set.of(
            "Mono", "Optional", "CompletableFuture", "CompletionStage", "Future", "Callable", "DeferredResult");
    /** Common API "envelope" wrappers: the real payload is the single type argument (only unwrapped when generic). */
    private static final Set<String> ENVELOPE_WRAPPERS = Set.of(
            "ApiResponse", "ApiResult", "Result", "Response", "RestResponse", "DataResponse", "ResponseWrapper",
            "ResponseDTO", "ResponseDto", "Envelope", "BaseResponse", "GenericResponse", "ServiceResponse", "Wrapper");
    /** Wrappers that serialize as a bare JSON array of the inner type. */
    private static final Set<String> ARRAY_WRAPPERS = Set.of(
            "List", "Set", "Collection", "Flux");
    /** Paged/HATEOAS wrappers that serialize as an OBJECT envelope, not a bare array (Page<X> = {content,total,...}). */
    private static final Set<String> PAGED_OBJECT_WRAPPERS = Set.of(
            "Page", "Slice", "PagedModel", "CollectionModel");

    // ---- annotation helpers ----

    static String simpleTypeName(Type type) {
        if (type == null) {
            return null;
        }
        // Wildcard (List<? extends Foo>) → recover the bound's real type instead of the literal "? extends Foo".
        if (type instanceof WildcardType wt) {
            return wt.getExtendedType().map(TypeMappingSupport::simpleTypeName)
                    .or(() -> wt.getSuperType().map(TypeMappingSupport::simpleTypeName))
                    .orElse("Object");
        }
        if (type instanceof ClassOrInterfaceType cit) {
            return cit.getNameAsString();
        }
        return type.asString();
    }

    static String[] openApiType(String javaType) {
        if (javaType == null) {
            return new String[]{"object", null};
        }
        return switch (javaType) {
            case "String", "CharSequence" -> new String[]{"string", null};
            case "UUID" -> new String[]{"string", "uuid"};
            case "int", "Integer", "short", "Short" -> new String[]{"integer", "int32"};
            case "long", "Long", "BigInteger" -> new String[]{"integer", "int64"};
            case "boolean", "Boolean" -> new String[]{"boolean", null};
            case "double", "Double", "float", "Float", "BigDecimal" -> new String[]{"number", null};
            case "LocalDate" -> new String[]{"string", "date"};
            case "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime", "Date" -> new String[]{"string", "date-time"};
            default -> new String[]{"object", null};
        };
    }

    /** Element type of a collection/array field (List/Set/Collection/…&lt;E&gt; or E[]), else null. */
    static String collectionElement(Type type) {
        if (type instanceof ArrayType at) {
            return simpleTypeName(at.getComponentType());
        }
        if (type instanceof ClassOrInterfaceType cit && COLLECTION_TYPES.contains(cit.getNameAsString())
                && cit.getTypeArguments().isPresent() && cit.getTypeArguments().get().size() == 1) {
            return simpleTypeName(cit.getTypeArguments().get().get(0));   // handles wildcard bounds too
        }
        return null;
    }

    /** Single-element collection field types that serialize as a bare JSON array (Map is excluded — it is a dictionary,
     *  not an array). Page/Slice/PagedModel/CollectionModel are DELIBERATELY absent: they serialize as an OBJECT
     *  envelope ({@link #PAGED_OBJECT_WRAPPERS}), so modelling a {@code Page<Item>} field as {@code Item[]} would
     *  contradict that and fire a false array-vs-object SCHEMA_FIELD_TYPE_MISMATCH — they fall through to an object. */
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "List", "Set", "Collection", "Iterable", "SortedSet", "NavigableSet", "Queue", "Deque",
            "Flux", "Stream");

    /** The primitive inner type of OptionalInt/OptionalLong/OptionalDouble, or null when not a primitive optional. */
    static String optionalPrimitive(Type t) {
        if (t instanceof ClassOrInterfaceType cit) {
            return switch (cit.getNameAsString()) {
                case "OptionalInt" -> "int";
                case "OptionalLong" -> "long";
                case "OptionalDouble" -> "double";
                default -> null;
            };
        }
        return null;
    }

    /** True when the parameter's (unannotated) type simple-name is one of {@code names}. */
    static boolean isBoundType(Parameter p, String... names) {
        String t = simpleTypeName(p.getType());
        for (String n : names) {
            if (n.equals(t)) {
                return true;
            }
        }
        return false;
    }
}
