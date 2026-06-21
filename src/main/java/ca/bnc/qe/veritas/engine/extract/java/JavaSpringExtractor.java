package ca.bnc.qe.veritas.engine.extract.java;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
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
        List<String> blindSpots = new ArrayList<>();   // never silently drop static-analysis gaps
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(File.separator + "test" + File.separator))
                    .forEach(p -> {
                        Optional<CompilationUnit> cu = parse(parser, p);
                        if (cu.isEmpty()) {
                            blindSpots.add("Could not parse " + sourceRoot.relativize(p) + " — its endpoints/types are not analysed.");
                            return;
                        }
                        units.add(cu.get());
                        cu.get().findAll(TypeDeclaration.class).forEach(td ->
                                types.put(td.getNameAsString(), (TypeDeclaration<?>) td));
                    });
        } catch (Exception e) {
            log.warn("Walk failed for {}: {}", sourceRoot, e.getMessage());
            blindSpots.add("Source walk failed for " + sourceRoot + ": " + e.getMessage());
        }

        Map<String, String> constants = collectStringConstants(units);   // for resolving constant path refs

        List<Endpoint> endpoints = new ArrayList<>();
        List<String> referenced = new ArrayList<>();
        for (CompilationUnit cu : units) {
            String file = cu.getStorage().map(s -> s.getPath().toString()).orElse("?");
            cu.findAll(TypeDeclaration.class).stream()
                    .filter(td -> isController(td, types))
                    .forEach(ctrl -> {
                        List<String> bases = classPaths(ctrl, types, constants, blindSpots);
                        List<String> classSecurity = securityOf(ctrl, types);
                        int before = endpoints.size();
                        Set<String> seenSignatures = new java.util.HashSet<>();
                        for (Object mo : ctrl.getMethods()) {
                            MethodDeclaration method = (MethodDeclaration) mo;
                            seenSignatures.add(signatureKey(method));
                            endpoints.addAll(toEndpoints(file, bases, method, types, referenced,
                                    classSecurity, blindSpots, constants));
                        }
                        // Mappings inherited from an abstract/base class the controller EXTENDS are real routes at
                        // runtime — emit them (subclass overrides win). Use the subclass's class-level bases + security.
                        if (ctrl instanceof ClassOrInterfaceDeclaration coid) {
                            for (MethodDeclaration inherited :
                                    inheritedMappedMethods(coid, types, seenSignatures, new java.util.HashSet<>())) {
                                endpoints.addAll(toEndpoints(file, bases, inherited, types, referenced,
                                        classSecurity, blindSpots, constants));
                            }
                            // A base we can't see can't be analysed — record an honest blind spot, never drop silently.
                            for (ClassOrInterfaceType ext : coid.getExtendedTypes()) {
                                if (!types.containsKey(ext.getNameAsString())) {
                                    blindSpots.add("Controller '" + ctrl.getNameAsString() + "' extends '"
                                            + ext.getNameAsString() + "', which is not in the scanned sources; any request "
                                            + "mappings it declares are not analysed.");
                                }
                            }
                        }
                        // Mappings declared on an implemented interface aren't analysed — if a controller yields no
                        // endpoints of its own yet implements interfaces, say so (don't drop silently).
                        if (endpoints.size() == before && ctrl instanceof ClassOrInterfaceDeclaration coid
                                && !coid.getImplementedTypes().isEmpty()) {
                            blindSpots.add("Controller '" + ctrl.getNameAsString() + "' has no mappings on its own methods"
                                    + " but implements " + coid.getImplementedTypes()
                                    + "; mappings declared on interfaces are not analysed.");
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
        Set<String> unresolved = new java.util.LinkedHashSet<>();
        for (String name : referenced) {
            String simple = name.replace("[]", "");
            if (types.containsKey(simple) && !schemas.containsKey(simple)) {
                schemas.put(simple, buildSchema(types.get(simple), types));
            } else if (!types.containsKey(simple) && isResolvableSchemaName(simple)) {
                // Referenced DTO not in the scanned sources — try the symbol solver before flagging it.
                unresolved.add(simple);
            }
        }
        for (String name : unresolved) {
            if (!resolvesViaSolver(units, name)) {
                blindSpots.add("Type '" + name + "' is referenced by an endpoint but could not be resolved from the "
                        + "scanned sources; its schema is omitted (likely an external/library DTO).");
            }
        }
        return new ApiModel("code", null, null, null, endpoints, schemas, blindSpots);
    }

    /** A simple name worth resolving (not a primitive/JDK scalar the IR already maps to an OpenAPI type). */
    private boolean isResolvableSchemaName(String simple) {
        if (simple == null || simple.isBlank() || !Character.isUpperCase(simple.charAt(0))) {
            return false;   // primitives/lowercase handled by openApiType()
        }
        return !Set.of("String", "Integer", "Long", "Double", "Float", "Boolean", "BigDecimal", "BigInteger",
                "Object", "Void", "LocalDate", "LocalDateTime", "Instant", "UUID", "Date").contains(simple);
    }

    /**
     * Genuinely consult the {@link JavaSymbolSolver}: try to resolve any same-named type used in the sources.
     * Returns true if the solver resolves it (a known JDK/library type — not a blind spot). Never throws.
     */
    private boolean resolvesViaSolver(List<CompilationUnit> units, String simpleName) {
        for (CompilationUnit cu : units) {
            for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
                if (!t.getNameAsString().equals(simpleName)) {
                    continue;
                }
                try {
                    t.resolve();   // symbol-solver resolution; throws UnsolvedSymbolException if unknown
                    return true;
                } catch (Throwable ignore) {
                    return false;   // genuinely unresolved → caller records a blind spot
                }
            }
        }
        return false;
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

    private boolean isController(TypeDeclaration<?> td, Map<String, TypeDeclaration<?>> types) {
        if (isControllerAnnotated(td)) {
            return true;
        }
        // custom stereotype meta-annotated with @RestController (or @Controller + @ResponseBody) — defined in sources
        for (AnnotationExpr a : td.getAnnotations()) {
            if (types.get(a.getNameAsString()) instanceof AnnotationDeclaration decl && isControllerAnnotated(decl)) {
                return true;
            }
        }
        return false;
    }

    private boolean isControllerAnnotated(NodeWithAnnotations<?> n) {
        return has(n, "RestController") || (has(n, "Controller") && has(n, "ResponseBody"));
    }

    private List<String> classPaths(TypeDeclaration<?> ctrl, Map<String, TypeDeclaration<?>> types,
                                    Map<String, String> constants, List<String> blindSpots) {
        Optional<AnnotationExpr> rm = getAnnotation(ctrl, "RequestMapping");
        if (rm.isPresent()) {
            return annotationPaths(rm.get(), constants, blindSpots);
        }
        // composed stereotype meta-annotated with @RequestMapping (e.g. @ApiV1Controller) — base path on the meta decl
        for (AnnotationExpr a : ctrl.getAnnotations()) {
            if (types.get(a.getNameAsString()) instanceof AnnotationDeclaration decl) {
                Optional<AnnotationExpr> metaRm = getAnnotation(decl, "RequestMapping");
                if (metaRm.isPresent()) {
                    return annotationPaths(metaRm.get(), constants, blindSpots);
                }
            }
        }
        return List.of("");
    }

    /**
     * Build every endpoint a method declares: class-path × method-path × HTTP verb (so multi-path / multi-method
     * mappings produce all of their endpoints, and constant path refs resolve to their literal value).
     */
    private List<Endpoint> toEndpoints(String file, List<String> bases, MethodDeclaration m,
                                       Map<String, TypeDeclaration<?>> types, List<String> referenced,
                                       List<String> classSecurity, List<String> blindSpots,
                                       Map<String, String> constants) {
        MethodMapping mm = methodMappingOf(m, types);
        if (mm == null) {
            return List.of();
        }
        List<String> methodPaths = annotationPaths(mm.annotation(), constants, blindSpots);

        // --- params / body / responses are identical across the method's path×verb combinations ---
        List<ParamModel> params = new ArrayList<>();
        RequestBodyModel body = null;
        for (Parameter p : m.getParameters()) {
            if (hasMeta(p, "PathVariable", types)) {
                params.add(param(file, p, ParamLocation.PATH, true));
            } else if (hasMeta(p, "RequestParam", types)) {
                boolean required = getAnnotation(p, "RequestParam")
                        .map(a -> !"false".equals(firstString(a, "required")) && firstString(a, "defaultValue") == null)
                        .orElse(true);
                params.add(param(file, p, ParamLocation.QUERY, required));
            } else if (hasMeta(p, "RequestHeader", types)) {
                params.add(param(file, p, ParamLocation.HEADER, true));
            } else if (hasMeta(p, "CookieValue", types)) {
                params.add(param(file, p, ParamLocation.COOKIE, true));
            } else if (hasMeta(p, "RequestBody", types)) {
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
        security.addAll(securityOf(m, types));   // method-level security adds to (overrides conceptually) the class default

        SourceRef src = SourceRef.code(file, line(m), line(m), m.getDeclarationAsString(false, false, false));
        List<Endpoint> out = new ArrayList<>();
        for (String b : bases.isEmpty() ? List.of("") : bases) {
            for (String mp : methodPaths.isEmpty() ? List.of("") : methodPaths) {
                String path = joinPath(b, mp);
                for (HttpMethod verb : mm.verbs()) {
                    out.add(new Endpoint(verb, path, m.getNameAsString(), params, body, responses,
                            null, null, security, src));
                }
            }
        }
        return out;
    }

    /**
     * Methods carrying a request mapping that this controller inherits from base classes it EXTENDS (recursively),
     * excluding any the subclass overrides ({@code seenSignatures} carries the subclass's own signatures). Only
     * bases present in the scanned sources are walked; an unresolved base is flagged as a blind spot by the caller.
     */
    private List<MethodDeclaration> inheritedMappedMethods(ClassOrInterfaceDeclaration coid,
                                                           Map<String, TypeDeclaration<?>> types,
                                                           Set<String> seenSignatures, Set<String> visited) {
        List<MethodDeclaration> out = new ArrayList<>();
        for (ClassOrInterfaceType ext : coid.getExtendedTypes()) {
            String name = ext.getNameAsString();
            if (!visited.add(name)) {
                continue;   // cycle guard
            }
            if (types.get(name) instanceof ClassOrInterfaceDeclaration base) {
                for (MethodDeclaration m : base.getMethods()) {
                    if (!seenSignatures.add(signatureKey(m))) {
                        continue;   // overridden by a subclass already processed
                    }
                    if (methodMappingOf(m, types) != null) {
                        out.add(m);
                    }
                }
                out.addAll(inheritedMappedMethods(base, types, seenSignatures, visited));
            }
        }
        return out;
    }

    /** Override-aware key: method name + parameter types (enough to detect a subclass override of a base method). */
    private String signatureKey(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder(m.getNameAsString()).append('(');
        m.getParameters().forEach(p -> sb.append(simpleTypeName(p.getType())).append(','));
        return sb.append(')').toString();
    }

    private record MethodMapping(List<HttpMethod> verbs, AnnotationExpr annotation) {}

    /** The method's HTTP verb(s) + the annotation carrying its path(s); supports shortcuts, @RequestMapping, and composed meta-annotations. */
    private MethodMapping methodMappingOf(MethodDeclaration m, Map<String, TypeDeclaration<?>> types) {
        for (Map.Entry<String, HttpMethod> e : Map.of(
                "GetMapping", HttpMethod.GET, "PostMapping", HttpMethod.POST, "PutMapping", HttpMethod.PUT,
                "PatchMapping", HttpMethod.PATCH, "DeleteMapping", HttpMethod.DELETE).entrySet()) {
            Optional<AnnotationExpr> a = getAnnotation(m, e.getKey());
            if (a.isPresent()) {
                return new MethodMapping(List.of(e.getValue()), a.get());
            }
        }
        Optional<AnnotationExpr> rm = getAnnotation(m, "RequestMapping");
        if (rm.isPresent()) {
            return new MethodMapping(verbsFrom(rm.get()), rm.get());
        }
        // composed / meta-annotation: a custom annotation itself meta-annotated with a mapping (verb from meta, path from usage)
        for (AnnotationExpr a : m.getAnnotations()) {
            TypeDeclaration<?> decl = types.get(a.getNameAsString());
            if (decl instanceof AnnotationDeclaration) {
                MethodMapping meta = metaMappingOf(decl);
                if (meta != null) {
                    boolean usageHasPath = memberExpr(a, "value", "path") != null;
                    return new MethodMapping(meta.verbs(), usageHasPath ? a : meta.annotation());
                }
            }
        }
        return null;
    }

    private MethodMapping metaMappingOf(TypeDeclaration<?> annotationDecl) {
        for (Map.Entry<String, HttpMethod> e : Map.of(
                "GetMapping", HttpMethod.GET, "PostMapping", HttpMethod.POST, "PutMapping", HttpMethod.PUT,
                "PatchMapping", HttpMethod.PATCH, "DeleteMapping", HttpMethod.DELETE).entrySet()) {
            Optional<AnnotationExpr> a = getAnnotation(annotationDecl, e.getKey());
            if (a.isPresent()) {
                return new MethodMapping(List.of(e.getValue()), a.get());
            }
        }
        Optional<AnnotationExpr> rm = getAnnotation(annotationDecl, "RequestMapping");
        return rm.map(a -> new MethodMapping(verbsFrom(a), a)).orElse(null);
    }

    private List<HttpMethod> verbsFrom(AnnotationExpr rm) {
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

    /** All declared paths of a mapping annotation, resolving constants; records a blind spot for the unresolvable. */
    private List<String> annotationPaths(AnnotationExpr a, Map<String, String> constants, List<String> blindSpots) {
        Expression v = memberExpr(a, "value", "path");
        if (v == null) {
            return List.of("");
        }
        List<Expression> elems = v instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(v);
        List<String> out = new ArrayList<>();
        for (Expression e : elems) {
            String r = resolvePathExpr(e, constants);
            if (r == null) {
                blindSpots.add("Path expression '" + e + "' could not be resolved to a literal — not compared to the spec.");
                r = e.toString();
            } else if (r.contains("${")) {
                blindSpots.add("Path '" + r + "' uses an unresolved property placeholder — its real path is "
                        + "environment-defined and is not compared to the spec.");
            }
            out.add(r);
        }
        return out.isEmpty() ? List.of("") : out;
    }

    /** Resolve a path expression: string literal, constant ref (from scanned sources), or concatenation. */
    private String resolvePathExpr(Expression e, Map<String, String> constants) {
        if (e instanceof StringLiteralExpr s) {
            return s.asString();
        }
        if (e instanceof NameExpr n) {
            return constants.get(n.getNameAsString());
        }
        if (e instanceof FieldAccessExpr fa) {
            String full = fa.toString();
            return constants.containsKey(full) ? constants.get(full) : constants.get(fa.getNameAsString());
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            String l = resolvePathExpr(b.getLeft(), constants);
            String r = resolvePathExpr(b.getRight(), constants);
            return (l == null || r == null) ? null : l + r;
        }
        return null;
    }

    private Expression memberExpr(AnnotationExpr a, String... members) {
        if (a instanceof SingleMemberAnnotationExpr sm) {
            return sm.getMemberValue();
        }
        if (a instanceof NormalAnnotationExpr na) {
            for (String member : members) {
                for (var pair : na.getPairs()) {
                    if (pair.getNameAsString().equals(member)) {
                        return pair.getValue();
                    }
                }
            }
        }
        return null;
    }

    /** Static String constants (incl. interface fields) from the scanned sources, keyed by name and Owner.NAME. */
    private Map<String, String> collectStringConstants(List<CompilationUnit> units) {
        Map<String, String> out = new HashMap<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                String owner = td.getNameAsString();
                for (FieldDeclaration fd : td.getFields()) {
                    fd.getVariables().forEach(var -> var.getInitializer().ifPresent(init -> {
                        if (init instanceof StringLiteralExpr s) {
                            out.putIfAbsent(var.getNameAsString(), s.asString());
                            out.put(owner + "." + var.getNameAsString(), s.asString());
                        }
                    }));
                }
            }
        }
        return out;
    }

    /**
     * Required roles/scopes from security annotations (@PreAuthorize / @Secured / @RolesAllowed) — including custom
     * annotations meta-annotated with one of them (e.g. a project @AdminOnly). Missing the meta path would report a
     * protected endpoint as UNSECURED — a security false-negative, the worst direction of error in a bank context.
     */
    private List<String> securityOf(NodeWithAnnotations<?> n, Map<String, TypeDeclaration<?>> types) {
        List<String> out = new ArrayList<>();
        addSecurity(n, out);   // literal @PreAuthorize/@Secured/@RolesAllowed on the node itself
        for (AnnotationExpr a : n.getAnnotations()) {
            if (types.get(a.getNameAsString()) instanceof AnnotationDeclaration decl) {
                addSecurity(decl, out);   // composed/meta annotation declared in the scanned sources
            }
        }
        return out;
    }

    private void addSecurity(NodeWithAnnotations<?> n, List<String> out) {
        getAnnotation(n, "PreAuthorize").map(a -> firstString(a, "value")).filter(s -> s != null).ifPresent(out::add);
        getAnnotation(n, "Secured").map(a -> firstString(a, "value")).filter(s -> s != null).ifPresent(out::add);
        getAnnotation(n, "RolesAllowed").map(a -> firstString(a, "value")).filter(s -> s != null).ifPresent(out::add);
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
        SourceRef src = SourceRef.code(td.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(s -> s.getPath().toString()).orElse("?"), line(td), line(td), null);
        // An enum is a string schema with an enum list — not an empty {type:object} (else it spuriously diffs
        // against the spec, which models the same enum as type:string + enum).
        if (td instanceof EnumDeclaration ed) {
            return new SchemaModel(td.getNameAsString(), "string", List.of(), enumValuesOf(ed), src);
        }
        List<FieldModel> fields = new ArrayList<>();
        collectFields(td, types, fields, new HashSet<>(), new HashSet<>());
        return new SchemaModel(td.getNameAsString(), "object", fields, null, src);
    }

    private List<String> enumValuesOf(EnumDeclaration ed) {
        List<String> out = new ArrayList<>();
        ed.getEntries().forEach(e -> out.add(e.getNameAsString()));
        return out;
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
        ConstraintSet cs = constraintsOf(annotated);
        // Collection field (List<Foo>, Set<Foo>, Foo[]) → an ARRAY of the element type, not a single {type:object}.
        // type="array" matches the spec side; refSchema "Foo[]" lets the corrected-YAML render items (DTO elements
        // only — a scalar element stays a bare array).
        String element = collectionElement(type);
        if (element != null) {
            String elemRef = types.containsKey(element) ? element + "[]" : null;
            return new FieldModel(jsonName, "array", null, required, cs, elemRef, null);
        }
        // Enum-typed field → a string with an inline enum, not a phantom {type:object} ref.
        if (types.get(simple) instanceof EnumDeclaration ed) {
            return new FieldModel(jsonName, "string", null, required, cs.withEnumValues(enumValuesOf(ed)), null, null);
        }
        String refSchema = "object".equals(tf[0]) && types.containsKey(simple) ? simple : null;
        return new FieldModel(jsonName, tf[0], tf[1], required, cs, refSchema, null);
    }

    /** Element type of a collection/array field (List/Set/Collection/…&lt;E&gt; or E[]), else null. */
    private String collectionElement(Type type) {
        if (type instanceof com.github.javaparser.ast.type.ArrayType at) {
            return simpleTypeName(at.getComponentType());
        }
        if (type instanceof ClassOrInterfaceType cit && COLLECTION_TYPES.contains(cit.getNameAsString())
                && cit.getTypeArguments().isPresent() && cit.getTypeArguments().get().size() == 1) {
            return simpleTypeName(cit.getTypeArguments().get().get(0));   // handles wildcard bounds too
        }
        return null;
    }

    /** Single-element collection field types (Map is excluded — it is a dictionary, not an array). */
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "List", "Set", "Collection", "Iterable", "SortedSet", "NavigableSet", "Queue", "Deque",
            "Flux", "Page", "Slice", "PagedModel", "CollectionModel", "Stream");

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
            boolean re = outer.equals("ResponseEntity") || outer.equals("HttpEntity");
            // Transparent wrappers — unwrap to the inner body type (envelope/async/reactive-single).
            if (re || TRANSPARENT_WRAPPERS.contains(outer)) {
                BodyType b = unwrap(inner);
                return new BodyType(b.typeName(), b.array(), b.noBody(), re || b.responseEntity());
            }
            // Collection-like — the body is an array of the inner type (incl. paged wrappers).
            if (ARRAY_WRAPPERS.contains(outer)) {
                BodyType b = unwrap(inner);
                return new BodyType(b.typeName(), true, b.typeName() == null, false);
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

    /** Dictionary types whose body is a free-form object — never emitted as a named schema. */
    private static final Set<String> MAP_LIKE = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "NavigableMap", "ConcurrentHashMap", "Properties");

    /** Wrappers whose single type argument IS the response body (Spring envelopes, reactive-single, async). */
    private static final Set<String> TRANSPARENT_WRAPPERS = Set.of(
            "Mono", "Optional", "CompletableFuture", "CompletionStage", "Future", "Callable", "DeferredResult");
    /** Wrappers that mean "a collection of the inner type" (incl. Spring Data paged wrappers). */
    private static final Set<String> ARRAY_WRAPPERS = Set.of(
            "List", "Set", "Collection", "Flux", "Page", "Slice", "PagedModel", "CollectionModel");

    // ---- annotation helpers ----

    private boolean has(NodeWithAnnotations<?> n, String name) {
        return n.getAnnotationByName(name).isPresent();
    }

    /**
     * True if the node carries {@code name} directly, OR carries a custom annotation whose declaration (in the
     * scanned sources) is meta-annotated with {@code name} — e.g. a composed @RequestParam-based param annotation.
     * Only fires for genuine composed annotations (the same way Spring resolves them), so no false positives for
     * resolver-backed annotations that don't compose a binding.
     */
    private boolean hasMeta(NodeWithAnnotations<?> n, String name, Map<String, TypeDeclaration<?>> types) {
        if (has(n, name)) {
            return true;
        }
        for (AnnotationExpr a : n.getAnnotations()) {
            if (types.get(a.getNameAsString()) instanceof AnnotationDeclaration decl && has(decl, name)) {
                return true;
            }
        }
        return false;
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
        // Wildcard (List<? extends Foo>) → recover the bound's real type instead of the literal "? extends Foo".
        if (type instanceof WildcardType wt) {
            return wt.getExtendedType().map(this::simpleTypeName)
                    .or(() -> wt.getSuperType().map(this::simpleTypeName))
                    .orElse("Object");
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
        // Normalize regex path-variable constraints to the bare name: {id:\d+} -> {id} (Spring/OpenAPI match).
        joined = joined.replaceAll("\\{([^}:]+):[^}]*\\}", "{$1}");
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
