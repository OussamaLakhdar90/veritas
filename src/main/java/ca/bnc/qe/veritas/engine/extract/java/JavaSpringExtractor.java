package ca.bnc.qe.veritas.engine.extract.java;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.RequestBodyModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import java.util.HashSet;
import java.util.Set;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Extracts the canonical {@link ApiModel} from Spring MVC controllers via JavaParser (syntactic,
 * annotation-based — no compilation/classpath needed). Covers @RestController + @*Mapping, path-variable /
 * query / header / cookie params, @RequestBody, @ResponseStatus, return-type response schema, and DTO
 * schemas (fields + Bean Validation constraints). Unresolved bits are left out rather than fabricated.
 */
@Component
@Slf4j
public class JavaSpringExtractor {

    public ApiModel extract(Path sourceRoot) {
        JavaParser parser = symbolAwareParser(sourceRoot);
        Map<String, TypeDeclaration<?>> types = new LinkedHashMap<>();
        List<CompilationUnit> units = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(File.separator + "test" + File.separator))
                    .forEach(p -> parse(parser, p).ifPresent(cu -> {
                        units.add(cu);
                        cu.findAll(TypeDeclaration.class).forEach(td ->
                                types.put(td.getNameAsString(), (TypeDeclaration<?>) td));
                    }));
        } catch (Exception e) {
            log.warn("Walk failed for {}: {}", sourceRoot, e.getMessage());
        }

        List<Endpoint> endpoints = new ArrayList<>();
        List<String> referenced = new ArrayList<>();
        for (CompilationUnit cu : units) {
            String file = cu.getStorage().map(s -> s.getPath().toString()).orElse("?");
            cu.findAll(TypeDeclaration.class).stream()
                    .filter(this::isController)
                    .forEach(ctrl -> {
                        String base = classPath(ctrl);
                        List<String> classSecurity = securityOf(ctrl);
                        for (Object mo : ctrl.getMethods()) {
                            MethodDeclaration method = (MethodDeclaration) mo;
                            toEndpoint(file, base, method, types, referenced, classSecurity).ifPresent(endpoints::add);
                        }
                    });
        }

        // @ControllerAdvice / @RestControllerAdvice error responses are global — attach them to every endpoint.
        List<ResponseModel> adviceResponses = extractAdvice(units, referenced);
        if (!adviceResponses.isEmpty()) {
            endpoints.replaceAll(e -> {
                List<ResponseModel> merged = new ArrayList<>(e.responses());
                for (ResponseModel ar : adviceResponses) {
                    if (merged.stream().noneMatch(r -> r.statusCode() == ar.statusCode())) {
                        merged.add(ar);
                    }
                }
                return new Endpoint(e.method(), e.pathTemplate(), e.operationId(), e.params(), e.requestBody(),
                        merged, e.consumes(), e.produces(), e.security(), e.source());
            });
        }

        Map<String, SchemaModel> schemas = new LinkedHashMap<>();
        for (String name : referenced) {
            String simple = name.replace("[]", "");
            if (types.containsKey(simple) && !schemas.containsKey(simple)) {
                schemas.put(simple, buildSchema(types.get(simple), types));
            }
        }
        return new ApiModel("code", null, null, null, endpoints, schemas);
    }

    private Optional<CompilationUnit> parse(JavaParser parser, Path p) {
        try {
            return parser.parse(p).getResult();
        } catch (Exception e) {
            log.warn("Parse failed for {}: {}", p, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A parser with a {@link JavaSymbolSolver} over the JDK ({@link ReflectionTypeSolver}) + the cloned
     * sources ({@link JavaParserTypeSolver}), so types/inheritance can be resolved. Never hard-fails — if the
     * solver can't be built, falls back to syntactic parsing.
     */
    private JavaParser symbolAwareParser(Path sourceRoot) {
        ParserConfiguration cfg = new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21);
        try {
            CombinedTypeSolver ts = new CombinedTypeSolver(new ReflectionTypeSolver());
            if (sourceRoot != null && Files.isDirectory(sourceRoot)) {
                ts.add(new JavaParserTypeSolver(sourceRoot));
            }
            cfg.setSymbolResolver(new JavaSymbolSolver(ts));
        } catch (Exception e) {
            log.warn("Symbol solver unavailable for {}, parsing syntactically: {}", sourceRoot, e.getMessage());
        }
        return new JavaParser(cfg);
    }

    private boolean isController(TypeDeclaration<?> td) {
        return has(td, "RestController")
                || (has(td, "Controller") && (has(td, "ResponseBody")));
    }

    private String classPath(TypeDeclaration<?> ctrl) {
        return getAnnotation(ctrl, "RequestMapping").map(a -> firstString(a, "value", "path")).orElse("");
    }

    private Optional<Endpoint> toEndpoint(String file, String base, MethodDeclaration m,
                                          Map<String, TypeDeclaration<?>> types, List<String> referenced,
                                          List<String> classSecurity) {
        MappingInfo mapping = mappingOf(m, types);
        if (mapping == null) {
            return Optional.empty();
        }
        String path = joinPath(base, mapping.path());
        List<ParamModel> params = new ArrayList<>();
        RequestBodyModel body = null;
        for (Parameter p : m.getParameters()) {
            if (has(p, "PathVariable")) {
                params.add(param(file, p, ParamLocation.PATH, true));
            } else if (has(p, "RequestParam")) {
                boolean required = getAnnotation(p, "RequestParam")
                        .map(a -> !"false".equals(firstString(a, "required")) && firstString(a, "defaultValue") == null)
                        .orElse(true);
                params.add(param(file, p, ParamLocation.QUERY, required));
            } else if (has(p, "RequestHeader")) {
                params.add(param(file, p, ParamLocation.HEADER, true));
            } else if (has(p, "CookieValue")) {
                params.add(param(file, p, ParamLocation.COOKIE, true));
            } else if (has(p, "RequestBody")) {
                BodyType bt = unwrap(p.getType());
                if (bt.typeName() != null) {
                    referenced.add(bt.typeName());
                }
                boolean valid = has(p, "Valid") || has(p, "Validated");
                body = new RequestBodyModel(bt.schemaRef(), true, valid, null,
                        SourceRef.code(file, line(p), line(p), p.toString()));
            }
        }

        BodyType ret = unwrap(m.getType());
        if (ret.typeName() != null) {
            referenced.add(ret.typeName());
        }
        int status = responseStatus(m);
        List<ResponseModel> responses = new ArrayList<>();
        responses.add(new ResponseModel(status, ret.noBody() ? null : ret.schemaRef(), null,
                ret.responseEntity() ? "RESPONSE_ENTITY" : "RETURN",
                SourceRef.code(file, line(m), line(m), m.getDeclarationAsString(false, false, false))));

        List<String> security = new ArrayList<>(classSecurity);
        security.addAll(securityOf(m));   // method-level security adds to (overrides conceptually) the class default

        Endpoint endpoint = new Endpoint(mapping.method(), path, m.getNameAsString(), params, body, responses,
                null, null, security, SourceRef.code(file, line(m), line(m), m.getDeclarationAsString(false, false, false)));
        return Optional.of(endpoint);
    }

    /** Required roles/scopes from method/class security annotations (@PreAuthorize / @Secured / @RolesAllowed). */
    private List<String> securityOf(NodeWithAnnotations<?> n) {
        List<String> out = new ArrayList<>();
        getAnnotation(n, "PreAuthorize").map(a -> firstString(a, "value")).filter(s -> s != null).ifPresent(out::add);
        getAnnotation(n, "Secured").map(a -> firstString(a, "value")).filter(s -> s != null).ifPresent(out::add);
        getAnnotation(n, "RolesAllowed").map(a -> firstString(a, "value")).filter(s -> s != null).ifPresent(out::add);
        return out;
    }

    private ParamModel param(String file, Parameter p, ParamLocation loc, boolean required) {
        String annName = getAnnotation(p, switch (loc) {
            case PATH -> "PathVariable";
            case QUERY -> "RequestParam";
            case HEADER -> "RequestHeader";
            case COOKIE -> "CookieValue";
        }).map(a -> firstString(a, "value", "name")).orElse(null);
        String name = annName != null ? annName : p.getNameAsString();
        String[] tf = openApiType(simpleTypeName(p.getType()));
        return new ParamModel(name, loc, tf[0], tf[1], required, constraintsOf(p),
                SourceRef.code(file, line(p), line(p), p.toString()));
    }

    private SchemaModel buildSchema(TypeDeclaration<?> td, Map<String, TypeDeclaration<?>> types) {
        List<FieldModel> fields = new ArrayList<>();
        collectFields(td, types, fields, new HashSet<>(), new HashSet<>());
        return new SchemaModel(td.getNameAsString(), "object", fields, null,
                SourceRef.code(td.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                        .map(s -> s.getPath().toString()).orElse("?"), line(td), line(td), null));
    }

    /** Own fields + inherited fields from superclasses present in the same source set (subclass wins on name). */
    private void collectFields(TypeDeclaration<?> td, Map<String, TypeDeclaration<?>> types,
                               List<FieldModel> out, Set<String> seenNames, Set<String> visitedTypes) {
        if (td == null || !visitedTypes.add(td.getNameAsString())) {
            return;
        }
        if (td instanceof RecordDeclaration rec) {
            for (Parameter c : rec.getParameters()) {
                addField(out, seenNames, fieldOf(c.getNameAsString(), c.getType(), c, types));
            }
        } else {
            for (FieldDeclaration fd : td.getFields()) {
                if (fd.isStatic()) {
                    continue;
                }
                fd.getVariables().forEach(v -> addField(out, seenNames, fieldOf(v.getNameAsString(), v.getType(), fd, types)));
            }
        }
        if (td instanceof ClassOrInterfaceDeclaration coid) {
            for (ClassOrInterfaceType ext : coid.getExtendedTypes()) {
                collectFields(types.get(ext.getNameAsString()), types, out, seenNames, visitedTypes);
            }
        }
    }

    private void addField(List<FieldModel> out, Set<String> seenNames, FieldModel f) {
        if (seenNames.add(f.jsonName())) {
            out.add(f);
        }
    }

    private FieldModel fieldOf(String javaName, Type type, NodeWithAnnotations<?> annotated, Map<String, TypeDeclaration<?>> types) {
        String jsonName = getAnnotation(annotated, "JsonProperty").map(a -> firstString(a, "value")).orElse(javaName);
        boolean required = has(annotated, "NotNull") || has(annotated, "NotBlank") || has(annotated, "NotEmpty");
        String simple = simpleTypeName(type);
        String[] tf = openApiType(simple);
        String refSchema = "object".equals(tf[0]) && types.containsKey(simple) ? simple : null;
        return new FieldModel(jsonName, tf[0], tf[1], required, constraintsOf(annotated), refSchema, null);
    }

    @SuppressWarnings("unchecked")
    private ConstraintSet constraintsOf(NodeWithAnnotations<?> n) {
        Integer minLen = null, maxLen = null;
        Double min = null, max = null;
        String pattern = null, format = null;
        if (has(n, "NotBlank")) {
            minLen = 1;
        }
        Optional<AnnotationExpr> size = getAnnotation(n, "Size");
        if (size.isPresent()) {
            String mn = firstString(size.get(), "min");
            String mx = firstString(size.get(), "max");
            minLen = mn != null ? Integer.valueOf(mn) : minLen;
            maxLen = mx != null ? Integer.valueOf(mx) : maxLen;
        }
        Optional<AnnotationExpr> minA = getAnnotation(n, "Min");
        if (minA.isPresent() && firstString(minA.get(), "value") != null) {
            min = Double.valueOf(firstString(minA.get(), "value"));
        }
        Optional<AnnotationExpr> maxA = getAnnotation(n, "Max");
        if (maxA.isPresent() && firstString(maxA.get(), "value") != null) {
            max = Double.valueOf(firstString(maxA.get(), "value"));
        }
        Optional<AnnotationExpr> pat = getAnnotation(n, "Pattern");
        if (pat.isPresent()) {
            pattern = firstString(pat.get(), "regexp");
        }
        if (has(n, "Email")) {
            format = "email";
        }
        return new ConstraintSet(minLen, maxLen, min, max, null, null, pattern, null, format);
    }

    /** Error responses from @ControllerAdvice/@RestControllerAdvice @ExceptionHandler methods (one per status). */
    private List<ResponseModel> extractAdvice(List<CompilationUnit> units, List<String> referenced) {
        List<ResponseModel> out = new ArrayList<>();
        for (CompilationUnit cu : units) {
            String file = cu.getStorage().map(s -> s.getPath().toString()).orElse("?");
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                if (!has(td, "ControllerAdvice") && !has(td, "RestControllerAdvice")) {
                    continue;
                }
                for (MethodDeclaration m : td.getMethods()) {
                    if (!has(m, "ExceptionHandler")) {
                        continue;
                    }
                    int status = errorStatus(m);
                    BodyType bt = unwrap(m.getType());
                    if (bt.typeName() != null) {
                        referenced.add(bt.typeName());
                    }
                    int s = status;
                    if (out.stream().noneMatch(r -> r.statusCode() == s)) {
                        out.add(new ResponseModel(status, bt.noBody() ? null : bt.schemaRef(), null,
                                "EXCEPTION_HANDLER",
                                SourceRef.code(file, line(m), line(m), m.getDeclarationAsString(false, false, false))));
                    }
                }
            }
        }
        return out;
    }

    private int errorStatus(MethodDeclaration m) {
        String code = getAnnotation(m, "ResponseStatus").map(a -> firstString(a, "value", "code")).orElse(null);
        if (code == null) {
            return 500;   // no @ResponseStatus on the handler → default to 500
        }
        if (code.contains("BAD_REQUEST")) return 400;
        if (code.contains("UNAUTHORIZED")) return 401;
        if (code.contains("FORBIDDEN")) return 403;
        if (code.contains("NOT_FOUND")) return 404;
        if (code.contains("CONFLICT")) return 409;
        if (code.contains("UNPROCESSABLE")) return 422;
        if (code.contains("INTERNAL_SERVER_ERROR")) return 500;
        if (code.contains("BAD_GATEWAY")) return 502;
        if (code.contains("SERVICE_UNAVAILABLE")) return 503;
        return 500;
    }

    private int responseStatus(MethodDeclaration m) {
        Optional<AnnotationExpr> rs = getAnnotation(m, "ResponseStatus");
        if (rs.isPresent()) {
            String code = firstString(rs.get(), "value", "code");
            if (code != null) {
                if (code.contains("CREATED")) return 201;
                if (code.contains("NO_CONTENT")) return 204;
                if (code.contains("ACCEPTED")) return 202;
                if (code.contains("OK")) return 200;
            }
        }
        return 200;
    }

    // ---- mapping detection ----

    private record MappingInfo(HttpMethod method, String path) {}

    private MappingInfo mappingOf(MethodDeclaration m, Map<String, TypeDeclaration<?>> types) {
        MappingInfo direct = directMappingOf(m);
        if (direct != null) {
            return direct;
        }
        // composed / meta-annotations: a custom annotation that is itself meta-annotated with a mapping
        for (AnnotationExpr a : m.getAnnotations()) {
            TypeDeclaration<?> decl = types.get(a.getNameAsString());
            if (decl instanceof com.github.javaparser.ast.body.AnnotationDeclaration) {
                MappingInfo meta = directMappingOf(decl);
                if (meta != null) {
                    String path = firstString(a, "value", "path");   // usage path overrides the meta default
                    return new MappingInfo(meta.method(), nz(path != null ? path : meta.path()));
                }
            }
        }
        return null;
    }

    private MappingInfo directMappingOf(NodeWithAnnotations<?> n) {
        for (Map.Entry<String, HttpMethod> e : Map.of(
                "GetMapping", HttpMethod.GET, "PostMapping", HttpMethod.POST, "PutMapping", HttpMethod.PUT,
                "PatchMapping", HttpMethod.PATCH, "DeleteMapping", HttpMethod.DELETE).entrySet()) {
            Optional<AnnotationExpr> a = getAnnotation(n, e.getKey());
            if (a.isPresent()) {
                return new MappingInfo(e.getValue(), nz(firstString(a.get(), "value", "path")));
            }
        }
        Optional<AnnotationExpr> rm = getAnnotation(n, "RequestMapping");
        if (rm.isPresent()) {
            String methodMember = firstString(rm.get(), "method");
            HttpMethod hm = methodMember == null ? HttpMethod.GET : verbFrom(methodMember);
            return new MappingInfo(hm, nz(firstString(rm.get(), "value", "path")));
        }
        return null;
    }

    private HttpMethod verbFrom(String requestMethod) {
        for (HttpMethod hm : HttpMethod.values()) {
            if (requestMethod.toUpperCase(Locale.ROOT).contains(hm.name())) {
                return hm;
            }
        }
        return HttpMethod.GET;
    }

    // ---- type unwrapping ----

    private record BodyType(String typeName, boolean array, boolean noBody, boolean responseEntity) {
        String schemaRef() {
            if (typeName == null) {
                return null;
            }
            return array ? typeName + "[]" : typeName;
        }
    }

    private BodyType unwrap(Type type) {
        String simple = simpleTypeName(type);
        if (simple == null || "void".equals(simple) || "Void".equals(simple)) {
            return new BodyType(null, false, true, false);
        }
        if (type instanceof ClassOrInterfaceType cit && cit.getTypeArguments().isPresent()
                && !cit.getTypeArguments().get().isEmpty()) {
            String outer = cit.getNameAsString();
            Type inner = cit.getTypeArguments().get().get(0);
            boolean re = outer.equals("ResponseEntity");
            if (outer.equals("ResponseEntity") || outer.equals("Mono") || outer.equals("Optional")) {
                BodyType b = unwrap(inner);
                return new BodyType(b.typeName(), b.array(), b.noBody(), re || b.responseEntity());
            }
            if (outer.equals("List") || outer.equals("Set") || outer.equals("Collection") || outer.equals("Flux")) {
                return new BodyType(simpleTypeName(inner), true, false, false);
            }
        }
        boolean known = !"object".equals(openApiType(simple)[0]);
        if (known) {
            // primitive/wrapper return — still a body, but not a DTO schema
            return new BodyType(simple, false, false, false);
        }
        return new BodyType(simple, false, false, false);
    }

    // ---- annotation helpers ----

    private boolean has(NodeWithAnnotations<?> n, String name) {
        return n.getAnnotationByName(name).isPresent();
    }

    private Optional<AnnotationExpr> getAnnotation(NodeWithAnnotations<?> n, String name) {
        return n.getAnnotationByName(name);
    }

    private String firstString(AnnotationExpr a, String... members) {
        if (a instanceof SingleMemberAnnotationExpr sm) {
            return literal(sm.getMemberValue().toString());
        }
        if (a instanceof NormalAnnotationExpr na) {
            for (String member : members) {
                for (var pair : na.getPairs()) {
                    if (pair.getNameAsString().equals(member)) {
                        return literal(pair.getValue().toString());
                    }
                }
            }
        }
        return null;
    }

    private String literal(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            s = s.substring(1, s.length() - 1).trim();
            int comma = s.indexOf(',');
            if (comma >= 0) {
                s = s.substring(0, comma).trim();
            }
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String simpleTypeName(Type type) {
        if (type == null) {
            return null;
        }
        if (type instanceof ClassOrInterfaceType cit) {
            return cit.getNameAsString();
        }
        return type.asString();
    }

    private String[] openApiType(String javaType) {
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

    private String joinPath(String base, String method) {
        String b = nz(base);
        String mPart = nz(method);
        String joined = ("/" + b + "/" + mPart).replaceAll("/+", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined.isEmpty() ? "/" : joined;
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private Integer line(com.github.javaparser.ast.Node n) {
        return n.getBegin().map(pos -> pos.line).orElse(null);
    }

    private static final class File {
        static final String separator = java.io.File.separator;
    }
}
