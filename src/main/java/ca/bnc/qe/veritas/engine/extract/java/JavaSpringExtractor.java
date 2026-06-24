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
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ThrowStmt;
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

        // One-hop inter-procedural reachability: which error statuses a (non-controller) service method can throw, so a
        // status thrown DEEP in a service the controller calls is scored, not dismissed as a blanket @ControllerAdvice.
        Map<String, Integer> adviceExStatus = adviceExceptionStatuses(units, types);
        Map<String, Set<Integer>> serviceStatuses = serviceMethodStatuses(units, types, adviceExStatus);

        List<Endpoint> endpoints = new ArrayList<>();
        List<String> referenced = new ArrayList<>();
        for (CompilationUnit cu : units) {
            String file = cu.getStorage().map(s -> relPath(sourceRoot, s.getPath())).orElse("?");
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
                                    classSecurity, blindSpots, constants, serviceStatuses));
                        }
                        // Mappings inherited from an abstract/base class the controller EXTENDS are real routes at
                        // runtime — emit them (subclass overrides win). Use the subclass's class-level bases + security.
                        if (ctrl instanceof ClassOrInterfaceDeclaration coid) {
                            for (MethodDeclaration inherited :
                                    inheritedMappedMethods(coid, types, seenSignatures, new java.util.HashSet<>())) {
                                endpoints.addAll(toEndpoints(file, bases, inherited, types, referenced,
                                        classSecurity, blindSpots, constants, serviceStatuses));
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

        // Centralized authorization (SecurityFilterChain/HttpSecurity/WebSecurityConfigurerAdapter) is enforced by
        // URL pattern in a config bean, not by method annotations — invisible to this AST. Flag it so the DiffEngine
        // does not falsely report every endpoint as UNSECURED against a spec that declares a global security scheme.
        if (usesCentralizedSecurity(units)) {
            blindSpots.add("Authorization appears centralized in a Spring Security configuration "
                    + "(SecurityFilterChain/HttpSecurity); per-endpoint authorization is enforced there by URL pattern "
                    + "and is not visible to annotation-based static analysis.");
        }

        // @ControllerAdvice / @RestControllerAdvice error responses are global — attach them to every endpoint.
        List<ResponseModel> adviceResponses = extractAdvice(sourceRoot, units, referenced, types, blindSpots);
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
        // Worklist over referenced DTOs AND, transitively, the DTO types of their fields — so a nested DTO
        // (e.g. PasswordPolicy.complexity : PasswordComplexity) gets its own schema built and can be field-diffed,
        // not just left as a dangling refSchema pointer the comparison can't resolve.
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        referenced.forEach(n -> queue.add(n.replace("[]", "")));
        Set<String> processed = new HashSet<>();
        while (!queue.isEmpty()) {
            String simple = queue.poll();
            if (!processed.add(simple)) {
                continue;
            }
            if (types.containsKey(simple) && !schemas.containsKey(simple)) {
                SchemaModel built = buildSchema(sourceRoot, types.get(simple), types);
                schemas.put(simple, built);
                for (FieldModel fld : built.fields()) {
                    if (fld.refSchema() != null) {
                        queue.add(fld.refSchema().replace("[]", ""));   // transitively resolve nested DTOs
                    }
                }
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

    /** Types that mean the project centralizes Spring Security authorization (vs per-method annotations). */
    private static final Set<String> SECURITY_CONFIG_TYPES = Set.of(
            "SecurityFilterChain", "HttpSecurity", "WebSecurityConfigurerAdapter");

    private boolean usesCentralizedSecurity(List<CompilationUnit> units) {
        return units.stream().anyMatch(cu -> cu.findAll(ClassOrInterfaceType.class).stream()
                .anyMatch(t -> SECURITY_CONFIG_TYPES.contains(t.getNameAsString())));
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
    /**
     * Repo-relative, forward-slashed path for a parsed file, so a finding's code evidence is portable and
     * deep-linkable (e.g. a Bitbucket browse URL) rather than an absolute temp-clone path. Falls back to the
     * raw path if the file isn't under the scanned root.
     */
    private static String relPath(Path sourceRoot, Path file) {
        try {
            return sourceRoot.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException notUnderRoot) {
            return file.toString().replace('\\', '/');
        }
    }

    private List<Endpoint> toEndpoints(String file, List<String> bases, MethodDeclaration m,
                                       Map<String, TypeDeclaration<?>> types, List<String> referenced,
                                       List<String> classSecurity, List<String> blindSpots,
                                       Map<String, String> constants, Map<String, Set<Integer>> serviceStatuses) {
        MethodMapping mm = methodMappingOf(m, types);
        if (mm == null) {
            return List.of();
        }
        List<String> methodPaths = annotationPaths(mm.annotation(), constants, blindSpots);
        List<String> consumes = stringList(mm.annotation(), "consumes");   // @*Mapping(consumes=…)
        List<String> produces = stringList(mm.annotation(), "produces");   // @*Mapping(produces=…)

        // --- params / body / responses are identical across the method's path×verb combinations ---
        List<ParamModel> params = new ArrayList<>();
        RequestBodyModel body = null;
        for (Parameter p : m.getParameters()) {
            if (hasMeta(p, "PathVariable", types)) {
                params.add(param(file, p, ParamLocation.PATH, true, types));
            } else if (hasMeta(p, "RequestParam", types)) {
                boolean required = getAnnotation(p, "RequestParam")
                        .map(a -> !"false".equals(firstString(a, "required")) && firstString(a, "defaultValue") == null)
                        .orElse(true);
                params.add(param(file, p, ParamLocation.QUERY, required, types));
            } else if (hasMeta(p, "RequestHeader", types)) {
                params.add(param(file, p, ParamLocation.HEADER, bindingRequired(p, "RequestHeader"), types));
            } else if (hasMeta(p, "CookieValue", types)) {
                params.add(param(file, p, ParamLocation.COOKIE, bindingRequired(p, "CookieValue"), types));
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
        SourceRef retSrc = SourceRef.code(file, line(m), line(m), m.getDeclarationAsString(false, false, false));
        List<ResponseModel> responses = new ArrayList<>();
        // When the method builds a ResponseEntity (and has no explicit @ResponseStatus), read the real status
        // code(s) from the builder calls in the body — otherwise a 201/202/204 endpoint looked like 200.
        List<Integer> entityStatuses = ret.responseEntity() && getAnnotation(m, "ResponseStatus").isEmpty()
                ? responseEntityStatuses(m) : List.of();
        if (!entityStatuses.isEmpty()) {
            Integer success = entityStatuses.stream().filter(s -> s >= 200 && s < 300).min(Integer::compareTo).orElse(null);
            for (Integer s : entityStatuses) {
                boolean withBody = success != null && s.equals(success) && !ret.noBody();
                responses.add(new ResponseModel(s, withBody ? ret.schemaRef() : null, null, "RESPONSE_ENTITY", retSrc));
            }
        } else {
            int status = responseStatus(m);
            responses.add(new ResponseModel(status, ret.noBody() ? null : ret.schemaRef(), null,
                    ret.responseEntity() ? "RESPONSE_ENTITY" : "RETURN", retSrc));
        }

        // One-hop reachability: an error status a CALLED service method throws is reachable from THIS endpoint —
        // stronger than a blanket advice status. Attach it as EXCEPTION_HANDLER_REACHABLE (scored MEDIUM by the diff)
        // and BEFORE the blanket advice merge, so its noneMatch guard keeps this stronger origin.
        Map<String, String> fieldTypes = controllerFieldTypes(m);
        java.util.LinkedHashSet<Integer> reachable = new java.util.LinkedHashSet<>();
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            String scope = call.getScope().map(Object::toString).orElse("");
            String svcType = fieldTypes.get(scope);
            if (svcType == null) {
                continue;
            }
            Set<Integer> st = serviceStatuses.get(svcType + "#" + call.getNameAsString());
            if (st != null) {
                reachable.addAll(st);
            }
        }
        for (int s : reachable) {
            if (responses.stream().noneMatch(r -> r.statusCode() == s)) {
                responses.add(new ResponseModel(s, null, null, "EXCEPTION_HANDLER_REACHABLE", retSrc));
            }
        }

        List<String> security = new ArrayList<>(classSecurity);
        security.addAll(securityOf(m, types));   // method-level security adds to (overrides conceptually) the class default

        SourceRef src = SourceRef.code(file, line(m), line(m), m.getDeclarationAsString(false, false, false));
        List<Endpoint> out = new ArrayList<>();
        for (String b : bases.isEmpty() ? List.of("") : bases) {
            for (String mp : methodPaths.isEmpty() ? List.of("") : methodPaths) {
                String path = joinPath(b, mp);
                for (HttpMethod verb : mm.verbs()) {
                    out.add(new Endpoint(verb, path, m.getNameAsString(), params, body, responses,
                            consumes, produces, security, src));
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

    /**
     * All string literals from a NAMED annotation member (single value or array), e.g. {@code consumes}/
     * {@code produces}. Only named pairs are read — never the single-member form (which is the path/value), so
     * {@code @GetMapping("/x")} yields no produces. Returns an empty list when the member is absent.
     */
    private List<String> stringList(AnnotationExpr a, String member) {
        if (!(a instanceof NormalAnnotationExpr na)) {
            return List.of();
        }
        for (var pair : na.getPairs()) {
            if (pair.getNameAsString().equals(member)) {
                Expression e = pair.getValue();
                List<Expression> elems = e instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(e);
                List<String> out = new ArrayList<>();
                for (Expression x : elems) {
                    String v = literal(x.toString());
                    if (v != null && !v.isBlank()) {
                        out.add(v);
                    }
                }
                return out;
            }
        }
        return List.of();
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

    /** Spring binding-param required flag: default true, but false when required=false or a defaultValue is set. */
    private boolean bindingRequired(Parameter p, String annName) {
        return getAnnotation(p, annName)
                .map(a -> !"false".equals(firstString(a, "required")) && firstString(a, "defaultValue") == null)
                .orElse(true);
    }

    private ParamModel param(String file, Parameter p, ParamLocation loc, boolean required,
                             Map<String, TypeDeclaration<?>> types) {
        String annName = getAnnotation(p, switch (loc) {
            case PATH -> "PathVariable";
            case QUERY -> "RequestParam";
            case HEADER -> "RequestHeader";
            case COOKIE -> "CookieValue";
        }).map(a -> firstString(a, "value", "name")).orElse(null);
        String name = annName != null ? annName : p.getNameAsString();
        String simple = simpleTypeName(p.getType());
        String[] tf = openApiType(simple);
        String type = tf[0];
        ConstraintSet cs = constraintsOf(p);
        // A param typed as a Java enum constrains the allowed values. Surface them as the param's enum constraint
        // (the spec often models this as a bare string with the allowed set only in the description prose), so an
        // enum drift becomes a contract-testable CONSTRAINT_GAP instead of an invisible string-vs-string match.
        if (types.get(simple) instanceof EnumDeclaration ed) {
            type = "string";
            cs = cs.withEnumValues(enumValuesOf(ed));
        }
        return new ParamModel(name, loc, type, tf[1], required, cs,
                SourceRef.code(file, line(p), line(p), p.toString()));
    }

    private SchemaModel buildSchema(Path sourceRoot, TypeDeclaration<?> td, Map<String, TypeDeclaration<?>> types) {
        SourceRef src = SourceRef.code(td.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(s -> relPath(sourceRoot, s.getPath())).orElse("?"), line(td), line(td), null);
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
                if (has(c, "JsonIgnore")) {
                    continue;   // not serialized → not part of the JSON contract
                }
                addField(out, seenNames, fieldOf(c.getNameAsString(), c.getType(), c, types));
            }
        } else {
            for (FieldDeclaration fd : td.getFields()) {
                // Skip statics and fields excluded from JSON (@JsonIgnore) — including them produces false
                // SCHEMA_FIELD_MISSING/EXTRA diffs against a spec that (correctly) omits them.
                if (fd.isStatic() || has(fd, "JsonIgnore")) {
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
        // NOTE: @NotBlank is captured via the field's `required` flag, NOT as minLength=1 — synthesizing a
        // length constraint produced spurious CONSTRAINT_GAP findings against specs that (correctly) omit minLength.
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
    private List<ResponseModel> extractAdvice(Path sourceRoot, List<CompilationUnit> units, List<String> referenced,
                                              Map<String, TypeDeclaration<?>> types, List<String> blindSpots) {
        List<ResponseModel> out = new ArrayList<>();
        for (CompilationUnit cu : units) {
            String file = cu.getStorage().map(s -> relPath(sourceRoot, s.getPath())).orElse("?");
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                if (!has(td, "ControllerAdvice") && !has(td, "RestControllerAdvice")) {
                    continue;
                }
                for (MethodDeclaration m : td.getMethods()) {
                    if (!has(m, "ExceptionHandler")) {
                        continue;
                    }
                    List<Integer> statuses = errorStatuses(m, types);
                    if (statuses.isEmpty()) {
                        // Don't fabricate a 500: record an honest gap instead of guessing the produced status.
                        blindSpots.add("@ExceptionHandler '" + m.getNameAsString() + "' in " + td.getNameAsString()
                                + "' produces an HTTP status that could not be resolved statically; its error response "
                                + "is not compared to the spec.");
                        continue;
                    }
                    BodyType bt = unwrap(m.getType());
                    if (bt.typeName() != null) {
                        referenced.add(bt.typeName());
                    }
                    // A catch-all handler (Exception/RuntimeException/Throwable) maps to a status every endpoint could
                    // in theory produce — mark it GLOBAL so the diff treats it as LOW-confidence, not a hard defect.
                    String origin = catchAllHandler(m) ? "EXCEPTION_HANDLER_GLOBAL" : "EXCEPTION_HANDLER";
                    List<String> mt = adviceMediaTypes(m);
                    List<String> adviceMt = mt.isEmpty() ? null : mt;
                    for (int status : statuses) {
                        if (status < 400) {
                            continue;   // an @ExceptionHandler's output is an error response — a 2xx must not leak onto endpoints as a success body
                        }
                        if (out.stream().noneMatch(r -> r.statusCode() == status)) {
                            out.add(new ResponseModel(status, bt.noBody() ? null : bt.schemaRef(), adviceMt, origin,
                                    SourceRef.code(file, line(m), line(m), m.getDeclarationAsString(false, false, false))));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static final Map<String, String> MEDIA_TYPE_CONSTANTS = Map.ofEntries(
            Map.entry("APPLICATION_JSON", "application/json"),
            Map.entry("APPLICATION_PROBLEM_JSON", "application/problem+json"),
            Map.entry("APPLICATION_PROBLEM_XML", "application/problem+xml"),
            Map.entry("APPLICATION_XML", "application/xml"),
            Map.entry("TEXT_PLAIN", "text/plain"),
            Map.entry("TEXT_HTML", "text/html"),
            Map.entry("APPLICATION_PDF", "application/pdf"),
            Map.entry("APPLICATION_OCTET_STREAM", "application/octet-stream"));

    /** Media types an @ExceptionHandler sets on its response via {@code .contentType(MediaType.X)} / a string literal. */
    private List<String> adviceMediaTypes(MethodDeclaration m) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("contentType") || call.getArguments().isEmpty()) {
                continue;
            }
            String mt = mediaTypeFromExpr(call.getArgument(0));
            if (mt != null) {
                out.add(mt);
            }
        }
        return new ArrayList<>(out);
    }

    private String mediaTypeFromExpr(Expression e) {
        if (e.isStringLiteralExpr()) {
            return e.asStringLiteralExpr().getValue();
        }
        if (e.isMethodCallExpr()) {
            MethodCallExpr mc = e.asMethodCallExpr();
            if ((mc.getNameAsString().equals("parseMediaType") || mc.getNameAsString().equals("valueOf"))
                    && !mc.getArguments().isEmpty() && mc.getArgument(0).isStringLiteralExpr()) {
                return mc.getArgument(0).asStringLiteralExpr().getValue();
            }
        }
        String t = e.toString().trim();
        String suffix = t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
        if (suffix.endsWith("_VALUE")) {
            suffix = suffix.substring(0, suffix.length() - "_VALUE".length());
        }
        return MEDIA_TYPE_CONSTANTS.get(suffix);
    }

    /**
     * Resolve the HTTP status(es) an @ExceptionHandler produces, in priority order: an explicit @ResponseStatus on
     * the handler; a status set in the body via {@code ResponseEntity.status(...)}, a ResponseEntity factory,
     * {@code new ResponseEntity<>(HttpStatus.X)}, or {@code ProblemDetail.forStatus(AndDetail)(HttpStatus.X)}; the
     * handled exception's own @ResponseStatus; a small map of well-known framework exceptions. Returns an EMPTY list
     * (never a phantom 500) when nothing resolves, so the caller records an honest blind spot instead of guessing.
     */
    private List<Integer> errorStatuses(MethodDeclaration m, Map<String, TypeDeclaration<?>> types) {
        Integer ann = responseStatusAnnotation(m);
        if (ann != null) {
            return List.of(ann);
        }
        java.util.LinkedHashSet<Integer> body = new java.util.LinkedHashSet<>();
        for (Integer s : responseEntityStatuses(m)) {     // ResponseEntity.status(...) / factories
            if (s != null && s >= 400) {
                body.add(s);
            }
        }
        body.addAll(problemDetailStatuses(m));
        body.addAll(newResponseEntityStatuses(m));
        if (!body.isEmpty()) {
            return new ArrayList<>(body);
        }
        Integer fromException = handledExceptionStatus(m, types);
        return fromException != null ? List.of(fromException) : List.of();
    }

    /** Status from a @ResponseStatus annotation on a node (handler method or exception class), or null. */
    private Integer responseStatusAnnotation(NodeWithAnnotations<?> n) {
        String code = getAnnotation(n, "ResponseStatus").map(a -> firstString(a, "value", "code")).orElse(null);
        return code == null ? null : statusFromText(code);
    }

    /** Statuses from {@code ProblemDetail.forStatus(...)} / {@code forStatusAndDetail(...)} calls in the body. */
    private List<Integer> problemDetailStatuses(MethodDeclaration m) {
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            String scope = call.getScope().map(Object::toString).orElse("");
            if (!scope.equals("ProblemDetail") && !scope.endsWith(".ProblemDetail")) {
                continue;
            }
            if (call.getNameAsString().equals("forStatus") || call.getNameAsString().equals("forStatusAndDetail")) {
                Integer s = statusFromArg(call);
                if (s != null && s >= 400) {   // an exception handler's output is an error response; ignore a 2xx ProblemDetail
                    out.add(s);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Error statuses from {@code new ResponseEntity<>(..., HttpStatus.X)} constructions in the body. */
    private List<Integer> newResponseEntityStatuses(MethodDeclaration m) {
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        for (ObjectCreationExpr oce : m.findAll(ObjectCreationExpr.class)) {
            if (!oce.getType().getNameAsString().equals("ResponseEntity") || oce.getArguments().isEmpty()) {
                continue;
            }
            // The status is the LAST constructor arg (the HttpStatus/HttpStatusCode slot). Scanning all args would
            // misread a body/header expression whose text merely contains a status keyword (e.g. NOT_FOUND_MESSAGE).
            Integer s = statusFromText(oce.getArgument(oce.getArguments().size() - 1).toString().trim());
            if (s != null && s >= 400) {
                out.add(s);
            }
        }
        return new ArrayList<>(out);
    }

    /** Status from the handled exception's own @ResponseStatus (if the class is in scope), else a framework map. */
    private Integer handledExceptionStatus(MethodDeclaration m, Map<String, TypeDeclaration<?>> types) {
        for (String ex : handledExceptionNames(m)) {
            TypeDeclaration<?> decl = types.get(ex);
            if (decl != null) {
                Integer s = responseStatusAnnotation(decl);
                if (s != null) {
                    return s;
                }
            }
            Integer fw = frameworkExceptionStatus(ex);
            if (fw != null) {
                return fw;
            }
        }
        return null;
    }

    /** Simple names of the exception types a handler covers — from @ExceptionHandler(value), else its parameters. */
    private List<String> handledExceptionNames(MethodDeclaration m) {
        List<String> names = new ArrayList<>();
        getAnnotation(m, "ExceptionHandler").ifPresent(a -> {
            if (a instanceof SingleMemberAnnotationExpr sm) {
                collectClassNames(sm.getMemberValue(), names);
            } else if (a instanceof NormalAnnotationExpr na) {
                na.getPairs().stream().filter(p -> p.getNameAsString().equals("value"))
                        .forEach(p -> collectClassNames(p.getValue(), names));
            }
        });
        if (names.isEmpty()) {
            for (Parameter p : m.getParameters()) {
                String tn = p.getType().asString();
                String simple = tn.substring(tn.lastIndexOf('.') + 1);
                if (simple.endsWith("Exception") || simple.endsWith("Error") || simple.equals("Throwable")) {
                    names.add(simple);
                }
            }
        }
        return names;
    }

    private void collectClassNames(Expression e, List<String> out) {
        if (e instanceof ArrayInitializerExpr arr) {
            arr.getValues().forEach(v -> collectClassNames(v, out));
        } else if (e instanceof ClassExpr ce) {
            String tn = ce.getType().asString();
            out.add(tn.substring(tn.lastIndexOf('.') + 1));
        }
    }

    /** A small map of well-known Spring/JDK framework exceptions to the HTTP status Spring resolves them to. */
    private Integer frameworkExceptionStatus(String ex) {
        return switch (ex) {
            case "NotAcceptableStatusException", "HttpMediaTypeNotAcceptableException" -> 406;
            case "HttpMediaTypeNotSupportedException" -> 415;
            case "HttpRequestMethodNotSupportedException" -> 405;
            case "MethodArgumentNotValidException", "HttpMessageNotReadableException",
                 "MissingServletRequestParameterException", "ConstraintViolationException",
                 "BindException", "MethodArgumentTypeMismatchException" -> 400;
            case "AccessDeniedException" -> 403;
            case "AuthenticationException", "InsufficientAuthenticationException", "BadCredentialsException" -> 401;
            case "NoHandlerFoundException", "NoResourceFoundException" -> 404;
            case "MaxUploadSizeExceededException" -> 413;
            default -> null;
        };
    }

    /** True when the handler covers a catch-all type (Exception/RuntimeException/Throwable). */
    private boolean catchAllHandler(MethodDeclaration m) {
        for (String ex : handledExceptionNames(m)) {
            if (ex.equals("Exception") || ex.equals("RuntimeException") || ex.equals("Throwable")) {
                return true;
            }
        }
        return false;
    }

    /** Map each exception a @ControllerAdvice handler covers to the HTTP status that handler produces. */
    private Map<String, Integer> adviceExceptionStatuses(List<CompilationUnit> units, Map<String, TypeDeclaration<?>> types) {
        Map<String, Integer> out = new HashMap<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                if (!has(td, "ControllerAdvice") && !has(td, "RestControllerAdvice")) {
                    continue;
                }
                for (MethodDeclaration m : td.getMethods()) {
                    if (!has(m, "ExceptionHandler")) {
                        continue;
                    }
                    List<Integer> st = errorStatuses(m, types);
                    if (st.isEmpty()) {
                        continue;
                    }
                    handledExceptionNames(m).forEach(ex -> out.putIfAbsent(ex, st.get(0)));
                }
            }
        }
        return out;
    }

    /** Index every NON-controller (service) method to the error statuses it can throw — keyed "TypeName#method". */
    private Map<String, Set<Integer>> serviceMethodStatuses(List<CompilationUnit> units,
                                                            Map<String, TypeDeclaration<?>> types,
                                                            Map<String, Integer> adviceExStatus) {
        Map<String, Set<Integer>> out = new HashMap<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                if (isController(td, types)) {
                    continue;   // a controller's own throws are handled by its endpoint, not a service hop
                }
                for (MethodDeclaration m : td.getMethods()) {
                    Set<Integer> statuses = new java.util.LinkedHashSet<>();
                    for (String ex : thrownExceptionNames(m)) {
                        Integer s = exceptionStatusOf(ex, adviceExStatus, types);
                        if (s != null && s >= 400) {
                            statuses.add(s);
                        }
                    }
                    if (!statuses.isEmpty()) {
                        out.put(td.getNameAsString() + "#" + m.getNameAsString(), statuses);
                    }
                }
            }
        }
        return out;
    }

    /** Exception simple-names a method raises: {@code throw new X(...)} plus declared {@code throws X}. */
    private List<String> thrownExceptionNames(MethodDeclaration m) {
        List<String> out = new ArrayList<>();
        for (ThrowStmt t : m.findAll(ThrowStmt.class)) {
            if (t.getExpression().isObjectCreationExpr()) {
                out.add(t.getExpression().asObjectCreationExpr().getType().getNameAsString());
            }
        }
        m.getThrownExceptions().forEach(ex -> {
            String s = ex.toString();
            out.add(s.substring(s.lastIndexOf('.') + 1));
        });
        return out;
    }

    /** Resolve an exception's HTTP status: from advice mapping, the exception's own @ResponseStatus, or framework map. */
    private Integer exceptionStatusOf(String ex, Map<String, Integer> adviceExStatus, Map<String, TypeDeclaration<?>> types) {
        Integer s = adviceExStatus.get(ex);
        if (s != null) {
            return s;
        }
        TypeDeclaration<?> decl = types.get(ex);
        if (decl != null) {
            Integer rs = responseStatusAnnotation(decl);
            if (rs != null) {
                return rs;
            }
        }
        return frameworkExceptionStatus(ex);
    }

    /** Map a controller's field/param names to their (service) type simple-names, to resolve a {@code field.call()} hop. */
    private Map<String, String> controllerFieldTypes(MethodDeclaration m) {
        Map<String, String> out = new HashMap<>();
        m.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(td -> {
            for (FieldDeclaration fd : td.getFields()) {
                fd.getVariables().forEach(v -> out.put(v.getNameAsString(), simpleTypeName(v.getType())));
            }
        });
        for (Parameter p : m.getParameters()) {
            out.put(p.getNameAsString(), simpleTypeName(p.getType()));
        }
        return out;
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

    /** Status codes inferred from {@code ResponseEntity.<factory>(...)} builder calls in the method body. */
    private List<Integer> responseEntityStatuses(MethodDeclaration m) {
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            String scope = call.getScope().map(Object::toString).orElse("");
            if (!scope.equals("ResponseEntity") && !scope.endsWith(".ResponseEntity")) {
                continue;   // only static factory calls ON ResponseEntity, e.g. ResponseEntity.created(...)
            }
            Integer s = switch (call.getNameAsString()) {
                case "ok" -> 200;
                case "created" -> 201;
                case "accepted" -> 202;
                case "noContent" -> 204;
                case "badRequest" -> 400;
                case "notFound" -> 404;
                case "unprocessableEntity" -> 422;
                case "internalServerError" -> 500;
                case "status" -> statusFromArg(call);
                default -> null;
            };
            if (s != null) {
                out.add(s);
            }
        }
        return new ArrayList<>(out);
    }

    /** Resolve the status from a {@code ResponseEntity.status(...)} argument (int literal or {@code HttpStatus.X}). */
    private Integer statusFromArg(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return null;
        }
        return statusFromText(call.getArgument(0).toString().trim());
    }

    /** Map an int literal or an {@code HttpStatus.X} expression's text to a numeric status code, or null. */
    private Integer statusFromText(String arg) {
        if (arg == null) {
            return null;
        }
        try {
            return Integer.parseInt(arg.trim());
        } catch (NumberFormatException ignore) {
            // not a literal — fall through to HttpStatus name mapping
        }
        String u = arg.toUpperCase(Locale.ROOT);
        if (u.contains("NO_CONTENT")) return 204;
        if (u.contains("CREATED")) return 201;
        if (u.contains("ACCEPTED")) return 202;
        if (u.contains("BAD_REQUEST")) return 400;
        if (u.contains("UNAUTHORIZED")) return 401;
        if (u.contains("FORBIDDEN")) return 403;
        if (u.contains("NOT_FOUND")) return 404;
        if (u.contains("NOT_ACCEPTABLE")) return 406;
        if (u.contains("CONFLICT")) return 409;
        if (u.contains("UNPROCESSABLE")) return 422;
        if (u.contains("INTERNAL_SERVER_ERROR")) return 500;
        if (u.contains("BAD_GATEWAY")) return 502;
        if (u.contains("SERVICE_UNAVAILABLE")) return 503;
        if (u.endsWith("OK")) return 200;
        return null;
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
            // ENVELOPE_WRAPPERS only unwrap when parameterized (e.g. ApiResponse<User>); a bare type is left as-is.
            if (re || TRANSPARENT_WRAPPERS.contains(outer) || ENVELOPE_WRAPPERS.contains(outer)) {
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
    /** Common API "envelope" wrappers: the real payload is the single type argument (only unwrapped when generic). */
    private static final Set<String> ENVELOPE_WRAPPERS = Set.of(
            "ApiResponse", "ApiResult", "Result", "Response", "RestResponse", "DataResponse", "ResponseWrapper",
            "ResponseDTO", "ResponseDto", "Envelope", "BaseResponse", "GenericResponse", "ServiceResponse", "Wrapper");
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
