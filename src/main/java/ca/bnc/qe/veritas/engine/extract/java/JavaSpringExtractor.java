package ca.bnc.qe.veritas.engine.extract.java;

import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.firstString;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.getAnnotation;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.has;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.hasMeta;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.isJsonIgnored;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.literal;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.memberExpr;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.namedMember;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.addDirectThrowStatuses;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.addLocalHandlerStatuses;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.addReachableServiceStatuses;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.allEntityStatuses;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.catchAllHandler;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.errorStatuses;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.exceptionStatusOf;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.handledExceptionNames;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.responseEntityStatusUnresolvable;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.responseStatus;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.tailResolvableStatuses;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.thrownExceptionNames;
import static ca.bnc.qe.veritas.engine.extract.java.SecuritySupport.hasSecurityAnnotation;
import static ca.bnc.qe.veritas.engine.extract.java.SecuritySupport.parseSecurityChain;
import static ca.bnc.qe.veritas.engine.extract.java.SecuritySupport.resolveEndpointSecurity;
import static ca.bnc.qe.veritas.engine.extract.java.SecuritySupport.securityExpr;
import static ca.bnc.qe.veritas.engine.extract.java.SecuritySupport.securityOf;
import static ca.bnc.qe.veritas.engine.extract.java.SecuritySupport.usesCentralizedSecurity;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.collectionElement;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.isBoundType;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.isImplicitModelAttribute;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.openApiType;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.optionalPrimitive;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.simpleTypeName;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.unwrap;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.verbsFrom;
import ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.BodyType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.stream.Collectors;
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
import ca.bnc.qe.veritas.engine.model.SecurityChain;
import ca.bnc.qe.veritas.engine.model.SecurityRule;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import java.util.HashSet;
import java.util.Set;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
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
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.ArrayType;
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

        // Coverage honesty: declare web-API code this annotation-based Java analysis cannot cover (Kotlin sources,
        // functional RouterFunction routing) so an empty/partial model is never mistaken for "this service has no API".
        addCoverageBlindSpots(sourceRoot, units, blindSpots);

        Map<String, String> constants = collectStringConstants(units);   // for resolving constant path refs

        // One-hop inter-procedural reachability: which error statuses a (non-controller) service method can throw, so a
        // status thrown DEEP in a service the controller calls is scored, not dismissed as a blanket @ControllerAdvice.
        Map<String, Integer> adviceExStatus = adviceExceptionStatuses(units, types);
        Map<String, Set<Integer>> serviceStatuses = serviceMethodStatuses(units, types, adviceExStatus);

        // The app's Spring base path (spring.webflux.base-path / server.servlet.context-path / spring.mvc.servlet.path)
        // prefixes every route at runtime. Apply it to code-side paths so they line up with the spec side, which already
        // folds in the OpenAPI servers[].url base — without it, an app served under e.g. /ciam shows phantom
        // "missing from spec" + "dead spec" findings for the very same endpoint.
        final String springBase = springBasePath(sourceRoot, blindSpots);

        List<Endpoint> endpoints = new ArrayList<>();
        List<String> referenced = new ArrayList<>();
        ExtractCtx ctx = new ExtractCtx(types, constants, serviceStatuses, adviceExStatus, referenced, blindSpots);
        for (CompilationUnit cu : units) {
            String file = cu.getStorage().map(s -> relPath(sourceRoot, s.getPath())).orElse("?");
            cu.findAll(TypeDeclaration.class).stream()
                    .filter(td -> isController(td, types))
                    .forEach(ctrl -> {
                        List<String> bases = classPaths(ctrl, types, constants, blindSpots);
                        if (!springBase.isEmpty()) {
                            bases = bases.stream().map(b -> joinPath(springBase, b)).collect(Collectors.toList());
                        }
                        List<String> classSecurity = securityOf(ctrl, types);
                        int before = endpoints.size();
                        // For a plain @Controller, only @ResponseBody methods are REST endpoints; the rest return views.
                        boolean bodyByDefault = producesBodyByDefault(ctrl, types);
                        // Error statuses a controller-LOCAL @ExceptionHandler produces apply to that controller's own
                        // endpoints (Spring scopes them there) — resolve once and attach to each endpoint below.
                        Map<Integer, String> localHandlerStatuses = localExceptionHandlerStatuses(ctrl, types);
                        ControllerCtx cc = new ControllerCtx(file, ctrl.getNameAsString(), bases, classSecurity,
                                localHandlerStatuses);
                        Set<String> seenSignatures = new HashSet<>();
                        for (Object mo : ctrl.getMethods()) {
                            MethodDeclaration method = (MethodDeclaration) mo;
                            seenSignatures.add(signatureKey(method));
                            if (!bodyByDefault && !has(method, "ResponseBody")) {
                                continue;   // @Controller handler without @ResponseBody → returns a view, not an API response
                            }
                            endpoints.addAll(toEndpoints(ctx, cc, method));
                        }
                        // Mappings inherited from an abstract/base class the controller EXTENDS are real routes at
                        // runtime — emit them (subclass overrides win). Use the subclass's class-level bases + security.
                        if (ctrl instanceof ClassOrInterfaceDeclaration coid) {
                            for (MethodDeclaration inherited :
                                    inheritedMappedMethods(coid, types, seenSignatures, new HashSet<>())) {
                                if (!bodyByDefault && !has(inherited, "ResponseBody")) {
                                    continue;   // inherited view handler on a plain @Controller — not an API endpoint
                                }
                                endpoints.addAll(toEndpoints(ctx, cc, inherited));
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
                        // Mappings declared on an implemented interface aren't analysed (only superclass extends is
                        // walked). Surface a blind spot when the controller implements interfaces AND either yields no
                        // own endpoints (the all-interface case) OR a scanned interface declares @*Mapping methods (the
                        // MIXED case: an own endpoint must not suppress the interface routes → false dead-spec).
                        if (ctrl instanceof ClassOrInterfaceDeclaration coid && !coid.getImplementedTypes().isEmpty()
                                && (endpoints.size() == before || implementsMappedInterface(coid, types))) {
                            blindSpots.add("Controller '" + ctrl.getNameAsString() + "' implements "
                                    + coid.getImplementedTypes()
                                    + " — mappings declared on interfaces are not analysed.");
                        }
                    });
        }

        // Centralized authorization in a SecurityFilterChain bean is enforced by URL pattern, not method annotations.
        // RESOLVE it per endpoint CONSERVATIVELY (only where a literal authorizeHttpRequests chain matches the endpoint
        // unambiguously, Spring first-match semantics); for anything we can't decide, keep the coarse "centralized
        // security" blind spot so the DiffEngine still suppresses a false UNSECURED there. A resolved permitAll stays
        // empty-but-definitive — and when the WHOLE chain resolves (no blind spot) the DiffEngine can finally flag a
        // spec that requires security on a wide-open endpoint (the security false-negative this closes).
        SecurityChain securityChain = parseSecurityChain(units);
        if (securityChain != null && !securityChain.rules().isEmpty()) {
            boolean anyUnresolved = false;
            for (int i = 0; i < endpoints.size(); i++) {
                Endpoint e = endpoints.get(i);
                if (e.security() != null && !e.security().isEmpty()) {
                    continue;   // already secured by an annotation — the chain is moot for this endpoint
                }
                SecurityRule rule = resolveEndpointSecurity(e, securityChain);
                if (rule == null || rule.access() == SecurityRule.Access.DENY_ALL) {
                    // null = ambiguous; DENY_ALL = a 403-to-everyone route that maps cleanly to neither "secured" nor
                    // "unsecured" in the spec's model — keep the blind spot rather than mislabel it as authorization.
                    anyUnresolved = true;
                } else if (rule.access() != SecurityRule.Access.PERMIT_ALL) {
                    endpoints.set(i, withSecurity(e, securityExpr(rule)));   // AUTHENTICATED / ROLE / AUTHORITY
                }
            }
            if (anyUnresolved) {
                blindSpots.add(SecuritySupport.CENTRALIZED_SECURITY_BLIND_SPOT);
            }
        } else if (usesCentralizedSecurity(units)) {
            blindSpots.add(SecuritySupport.CENTRALIZED_SECURITY_BLIND_SPOT);   // a security config exists but no parseable rule chain
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
                        merged, e.consumes(), e.produces(), e.security(), e.source(), e.controllerClass());
            });
        }

        Map<String, SchemaModel> schemas = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        // Worklist over referenced DTOs AND, transitively, the DTO types of their fields — so a nested DTO
        // (e.g. PasswordPolicy.complexity : PasswordComplexity) gets its own schema built and can be field-diffed,
        // not just left as a dangling refSchema pointer the comparison can't resolve.
        Deque<String> queue = new ArrayDeque<>();
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
                    if (t.resolve().isTypeVariable()) {
                        return false;   // a generic type parameter (e.g. base-controller T) — not a real, bindable type
                    }
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

    private static Endpoint withSecurity(Endpoint e, List<String> security) {
        return new Endpoint(e.method(), e.pathTemplate(), e.operationId(), e.params(), e.requestBody(), e.responses(),
                e.consumes(), e.produces(), security, e.source(), e.controllerClass());
    }

    /**
     * Records honest coverage blind spots for web-API code outside this extractor's reach: Kotlin controllers (it
     * parses Java only) and functional {@code RouterFunction} routing (it extracts annotation-based mappings only).
     * Without these, an all-Kotlin or functional-routing service yields an empty model with no signal of why.
     */
    private void addCoverageBlindSpots(Path sourceRoot, List<CompilationUnit> units, List<String> blindSpots) {
        // (a) Kotlin sources declaring Spring web stereotypes / functional routing — not analysed (Java parser only).
        // Gate on SPECIFIC web markers (the @*Mapping annotations, a stereotype, or a router DSL) rather than a bare
        // "Mapping" substring — the latter matches unrelated types (e.g. ColumnMapping) and would raise a spurious
        // blind spot that the DiffEngine guard then turns into whole-service dead-spec suppression.
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            long ktWeb = files.filter(p -> p.toString().endsWith(".kt"))
                    .filter(p -> !p.toString().contains(File.separator + "test" + File.separator))
                    .filter(p -> {
                        try {
                            String s = Files.readString(p);
                            return s.contains("@RestController") || s.contains("@Controller")
                                    || s.contains("@RequestMapping") || s.contains("@GetMapping")
                                    || s.contains("@PostMapping") || s.contains("@PutMapping")
                                    || s.contains("@PatchMapping") || s.contains("@DeleteMapping")
                                    || s.contains("RouterFunction") || s.contains("coRouter");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
            if (ktWeb > 0) {
                blindSpots.add(ktWeb + " Kotlin source file(s) declare Spring web routing but were not analysed (this "
                        + "extractor parses Java only); any endpoints they declare are not covered.");
            }
        } catch (Exception ignore) {
            // best-effort coverage probe — never fail extraction over it
        }
        // (b) Functional WebFlux/Web routing (RouterFunction) in Java — not annotation-based, so not analysed. Detect it
        // from the AST (a RouterFunction<> type, or a RouterFunctions.route(...) call), NOT a toString() substring, so a
        // mere mention in a comment/string never triggers it — that would otherwise over-suppress dead-spec via the
        // DiffEngine guard. These routes are surfaced as an honest blind spot rather than guessed at or fabricated.
        boolean functional = units.stream().anyMatch(cu -> {
            boolean byType = cu.findAll(ClassOrInterfaceType.class).stream()
                    .anyMatch(t -> t.getNameAsString().equals("RouterFunction"));
            boolean byScopedCall = cu.findAll(MethodCallExpr.class).stream().anyMatch(mc ->
                    mc.getNameAsString().equals("route")
                    && mc.getScope().map(s -> s.toString().equals("RouterFunctions")).orElse(false));
            // A statically-imported bare route()/nest() (no RouterFunctions scope, no RouterFunction<> type spelled) is
            // still functional routing — gate on importing the WebFlux/Web functional-server package so an unrelated
            // route()/nest() call can't trigger it.
            boolean importsRouterDsl = cu.getImports().stream().anyMatch(i -> {
                String n = i.getNameAsString();
                return n.contains("web.reactive.function.server") || n.contains("web.servlet.function");
            });
            boolean byBareCall = importsRouterDsl && cu.findAll(MethodCallExpr.class).stream().anyMatch(mc -> {
                String n = mc.getNameAsString();
                return n.equals("route") || n.equals("nest");
            });
            return byType || byScopedCall || byBareCall;
        });
        if (functional) {
            blindSpots.add("Functional routing (RouterFunction / RouterFunctions.route) was detected but is not analysed "
                    + "(only annotation-based @RequestMapping endpoints are extracted); those routes are not covered.");
        }
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
        // Classic Spring MVC REST style: a plain @Controller whose REST-ness is declared PER-METHOD via @ResponseBody
        // (very common — e.g. @Controller class + @RequestMapping(method=...) + @ResponseBody on each handler). Only the
        // @ResponseBody methods are API endpoints; the per-method gate in the extraction loop excludes view handlers.
        if (has(td, "Controller")) {
            for (Object mo : td.getMethods()) {
                if (has((MethodDeclaration) mo, "ResponseBody")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isControllerAnnotated(NodeWithAnnotations<?> n) {
        return has(n, "RestController") || (has(n, "Controller") && has(n, "ResponseBody"));
    }

    /**
     * True when every mapped method of this controller implicitly returns a response body — {@code @RestController},
     * a class-level {@code @ResponseBody}, or a custom stereotype meta-annotated with either. A plain {@code @Controller}
     * does NOT: there, {@code @ResponseBody} on the individual method is what makes a handler a REST endpoint (vs a view).
     */
    private boolean producesBodyByDefault(TypeDeclaration<?> td, Map<String, TypeDeclaration<?>> types) {
        if (has(td, "RestController") || has(td, "ResponseBody")) {
            return true;
        }
        for (AnnotationExpr a : td.getAnnotations()) {
            if (types.get(a.getNameAsString()) instanceof AnnotationDeclaration decl
                    && (has(decl, "RestController") || has(decl, "ResponseBody"))) {
                return true;
            }
        }
        return false;
    }

    private List<String> classPaths(TypeDeclaration<?> ctrl, Map<String, TypeDeclaration<?>> types,
                                    Map<String, String> constants, List<String> blindSpots) {
        return classPaths(ctrl, types, constants, blindSpots, new HashSet<>());
    }

    private List<String> classPaths(TypeDeclaration<?> ctrl, Map<String, TypeDeclaration<?>> types,
                                    Map<String, String> constants, List<String> blindSpots, Set<String> visitedTypes) {
        Optional<AnnotationExpr> rm = getAnnotation(ctrl, "RequestMapping");
        if (rm.isPresent()) {
            return annotationPaths(rm.get(), constants, blindSpots);
        }
        // Composed stereotype meta-annotated with @RequestMapping (e.g. @ApiV1Controller, or a deeper chain
        // @ApiV1 -> @ApiBase -> @RequestMapping). Walk the meta-annotation chain TRANSITIVELY (BFS, cycle-guarded by
        // name) so a 2+ level stereotype's base path is resolved instead of silently dropped → phantom-root endpoints.
        Deque<AnnotationExpr> queue = new ArrayDeque<>(ctrl.getAnnotations());
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            AnnotationExpr a = queue.poll();
            if (!visited.add(a.getNameAsString())) {
                continue;
            }
            if (types.get(a.getNameAsString()) instanceof AnnotationDeclaration decl) {
                Optional<AnnotationExpr> metaRm = getAnnotation(decl, "RequestMapping");
                if (metaRm.isPresent()) {
                    return annotationPaths(metaRm.get(), constants, blindSpots);
                }
                queue.addAll(decl.getAnnotations());   // descend into this meta-annotation's own annotations
            }
        }
        // A class-level @RequestMapping on a scanned ABSTRACT/base class applies to a subclass that declares none
        // (Spring resolves the type-level mapping up the hierarchy via AnnotatedElementUtils find-semantics) — so an
        // inherited handler's base path must come from the base, not be silently dropped to "".
        if (ctrl instanceof ClassOrInterfaceDeclaration coid) {
            // Cycle guard: a cyclic supertype chain (A extends B extends A) or a simple-name self-reference
            // (types is keyed by SIMPLE name, so `com.app.Base extends com.lib.Base` resolves Base→itself) would
            // otherwise recurse forever → StackOverflowError → the whole extraction crashes. Mirror the cycle guard
            // the sibling inheritedMappedMethods already threads.
            visitedTypes.add(ctrl.getNameAsString());
            for (ClassOrInterfaceType ext : coid.getExtendedTypes()) {
                String baseName = ext.getNameAsString();
                if (visitedTypes.contains(baseName)) {
                    continue;
                }
                if (types.get(baseName) instanceof TypeDeclaration<?> base) {
                    List<String> inherited = classPaths(base, types, constants, blindSpots, visitedTypes);
                    if (!inherited.equals(List.of(""))) {
                        return inherited;
                    }
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

    /** Scan-wide shared state threaded through endpoint extraction (the mutable referenced/blindSpots accumulators are
     *  held by reference, so appends still mutate the originals). Bundles what were 6 positional params. */
    private record ExtractCtx(Map<String, TypeDeclaration<?>> types, Map<String, String> constants,
                              Map<String, Set<Integer>> serviceStatuses, Map<String, Integer> adviceExStatus,
                              List<String> referenced, List<String> blindSpots) {
    }

    /** Per-controller state: the class path bases, class-level security, and local @ExceptionHandler statuses. */
    private record ControllerCtx(String file, String controllerClass, List<String> bases, List<String> classSecurity,
                                 Map<Integer, String> localHandlerStatuses) {
    }

    private List<Endpoint> toEndpoints(ExtractCtx ctx, ControllerCtx cc, MethodDeclaration m) {
        Map<String, TypeDeclaration<?>> types = ctx.types();
        List<String> referenced = ctx.referenced();
        List<String> blindSpots = ctx.blindSpots();
        Map<String, String> constants = ctx.constants();
        Map<String, Set<Integer>> serviceStatuses = ctx.serviceStatuses();
        Map<String, Integer> adviceExStatus = ctx.adviceExStatus();
        String file = cc.file();
        String controllerClass = cc.controllerClass();
        List<String> bases = cc.bases();
        List<String> classSecurity = cc.classSecurity();
        Map<Integer, String> localHandlerStatuses = cc.localHandlerStatuses();
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
        boolean multipart = false;
        for (Parameter p : m.getParameters()) {
            if (hasMeta(p, "PathVariable", types)) {
                params.add(param(file, p, ParamLocation.PATH, true, types));
            } else if (hasMeta(p, "RequestParam", types)) {
                String paramType = simpleTypeName(p.getType());
                String elem = collectionElement(p.getType());
                boolean unnamed = getAnnotation(p, "RequestParam")
                        .map(a -> firstString(a, "value", "name")).orElse(null) == null;
                if ("MultipartFile".equals(paramType) || "MultipartFile".equals(elem)) {
                    // A @RequestParam MultipartFile (or List<MultipartFile>) is a multipart/form-data file PART, not a
                    // query param — Spring binds it from the body. Emitting it as a QUERY param fabricated a false
                    // PARAM_MISSING (+ REQUEST_BODY_PRESENCE_MISMATCH); model it as a multipart body like @RequestPart.
                    multipart = true;
                } else if (unnamed && MAP_LIKE.contains(paramType)) {
                    // @RequestParam Map/MultiValueMap (no name) binds ALL query params at runtime — the variable name
                    // is not a real param name, so emitting it as an "object" param would false-diff. Surface it.
                    blindSpots.add("Controller " + controllerClass + "." + m.getNameAsString() + " binds all query params"
                            + " via a " + simpleTypeName(p.getType()) + " (@RequestParam) — the individual params are not"
                            + " modelled; verify them against the spec.");
                } else {
                    boolean required = getAnnotation(p, "RequestParam")
                            .map(a -> !"false".equals(namedMember(a, "required")) && namedMember(a, "defaultValue") == null)
                            .orElse(true);
                    params.add(param(file, p, ParamLocation.QUERY, required, types));
                }
            } else if (hasMeta(p, "RequestHeader", types)) {
                params.add(param(file, p, ParamLocation.HEADER, bindingRequired(p, "RequestHeader"), types));
            } else if (hasMeta(p, "CookieValue", types)) {
                params.add(param(file, p, ParamLocation.COOKIE, bindingRequired(p, "CookieValue"), types));
            } else if (hasMeta(p, "RequestBody", types)) {
                BodyType bt = unwrap(p.getType(), blindSpots);
                if (bt.typeName() != null) {
                    referenced.add(bt.typeName());
                }
                boolean valid = has(p, "Valid") || has(p, "Validated");
                body = new RequestBodyModel(bt.schemaRef(), true, valid, null,
                        SourceRef.code(file, line(p), line(p), p.toString()));
            } else if (hasMeta(p, "RequestPart", types)) {
                multipart = true;   // a multipart upload part → modelled as a multipart/form-data body below
            } else if (hasMeta(p, "ModelAttribute", types)) {
                // Command-object binding flattens the object's fields to form/query params at runtime; we don't expand
                // them, so surface it rather than silently dropping the bound fields (the project's blind-spot ethos).
                blindSpots.add("Controller " + controllerClass + "." + m.getNameAsString() + " binds @ModelAttribute '"
                        + simpleTypeName(p.getType()) + "' — its form/query fields are not expanded; verify them.");
            } else if (isBoundType(p, "Pageable", "PageRequest", "Sort")) {
                // Spring's resolver binds page/size/sort at runtime, but the names are configurable
                // (spring.data.web.pageable.*-parameter) and sort is a repeatable/array param — emitting the default
                // page/size/sort would mis-diff (false PARAM_MISSING when the spec omits them, string-vs-array on sort).
                // Surface a blind spot rather than guess, mirroring the @ModelAttribute treatment.
                blindSpots.add("Controller " + controllerClass + "." + m.getNameAsString() + " binds a "
                        + simpleTypeName(p.getType()) + " (pagination); its page/size/sort query params are resolved by "
                        + "Spring and not modelled — verify them against the spec.");
            } else if (hasMeta(p, "MatrixVariable", types)) {
                // A matrix URI segment param (;k=v) — OpenAPI models it (style:matrix), but we don't, so surface it
                // rather than let a spec param it corresponds to false-diff (parity with the other resolver bindings).
                blindSpots.add("Controller " + controllerClass + "." + m.getNameAsString() + " binds a @MatrixVariable '"
                        + p.getNameAsString() + "' — matrix URI segment params are not modelled; verify them.");
            } else if (isImplicitModelAttribute(p, types)) {
                // An unannotated command-object (scanned DTO) param is bound by Spring as an IMPLICIT @ModelAttribute,
                // flattening its fields to query/form params at runtime — same as the explicit @ModelAttribute branch.
                // Surface it so those spec-side params don't each false-diff as PARAM_EXTRA.
                blindSpots.add("Controller " + controllerClass + "." + m.getNameAsString() + " binds an (implicit "
                        + "@ModelAttribute) " + simpleTypeName(p.getType()) + " command object — its form/query fields "
                        + "are not expanded; verify them.");
            }
        }
        // A multipart handler (@RequestPart) HAS a body even without @RequestBody — model it as multipart/form-data so
        // it isn't seen as bodyless (the part schemas themselves are not modelled, which is honest, not a false claim).
        if (body == null && multipart) {
            body = new RequestBodyModel(null, true, false, List.of("multipart/form-data"),
                    SourceRef.code(file, line(m), line(m), "multipart request"));
        }

        BodyType ret = unwrap(m.getType(), blindSpots);
        if (ret.typeName() != null) {
            referenced.add(ret.typeName());
        }
        SourceRef retSrc = SourceRef.code(file, line(m), line(m), m.getDeclarationAsString(false, false, false));
        List<ResponseModel> responses = new ArrayList<>();
        // When the method returns a ResponseEntity, its in-body status WINS — over @ResponseStatus too (Spring's
        // HttpEntityMethodProcessor overwrites @ResponseStatus with the ResponseEntity's status; ref SPR-30305).
        // UNION the direct harvest (allEntityStatuses: return-expression factories + returned-local writes) with the
        // one-hop local-helper harvest (tailResolvableStatuses: `.map(this::helper)` / `return helper(x)`) — NOT an
        // else-fallback: a direct status sitting BESIDE a helper-delegating tail (e.g. `.map(this::created)
        // .orElse(ResponseEntity.notFound().build())` yields 201 AND 404) must keep both. Both harvests are tail-precise
        // (no phantom from an orElseThrow supplier / filter predicate / side-call), so the union adds only real statuses.
        List<Integer> entityStatuses = new ArrayList<>();
        if (ret.responseEntity()) {
            LinkedHashSet<Integer> merged = new LinkedHashSet<>(allEntityStatuses(m));
            merged.addAll(tailResolvableStatuses(m));
            entityStatuses.addAll(merged);
        }
        // A ResponseEntity whose status lives somewhere we can't read — a status(non-literal) call, or a helper/factory
        // delegation like `return factory.created(x)`. Surfaced as a blind spot below EVEN WHEN some returns resolved:
        // a method mixing a resolvable `return ResponseEntity.ok(x)` with an unresolvable `return factory.notFound()`
        // must not silently drop the unresolvable status (a partial harvest masking it).
        boolean unresolvableStatus = ret.responseEntity() && getAnnotation(m, "ResponseStatus").isEmpty()
                && responseEntityStatusUnresolvable(m);
        if (!entityStatuses.isEmpty()) {
            Integer success = entityStatuses.stream().filter(s -> s >= 200 && s < 300).min(Integer::compareTo).orElse(null);
            for (Integer s : entityStatuses) {
                boolean withBody = success != null && s.equals(success) && !ret.noBody();
                responses.add(new ResponseModel(s, withBody ? ret.schemaRef() : null, null, "RESPONSE_ENTITY", retSrc));
            }
        } else if (!unresolvableStatus) {
            // Non-ResponseEntity return, or a ResponseEntity carrying ONLY a @ResponseStatus (no in-body status), or a
            // bare pass-through / `return null` ResponseEntity that legitimately defaults to 200.
            int status = responseStatus(m);
            responses.add(new ResponseModel(status, ret.noBody() ? null : ret.schemaRef(), null,
                    ret.responseEntity() ? "RESPONSE_ENTITY" : "RETURN", retSrc));
        }
        if (unresolvableStatus) {
            // Don't fabricate a phantom 200 (a false STATUS_CODE_MISSING(200) that also drops the real status); record
            // an honest gap so the unresolvable status is surfaced for manual review.
            blindSpots.add("Controller " + controllerClass + "." + m.getNameAsString() + " builds a ResponseEntity whose "
                    + "status could not be resolved statically; its real status is not compared to the spec.");
        }

        // Error statuses reachable from this endpoint, in strongest-origin-first order (the noneMatch de-dup keeps the
        // first/stronger origin): a status a CALLED service method throws, then the endpoint's OWN direct throws, then
        // the controller-LOCAL @ExceptionHandler statuses.
        addReachableServiceStatuses(responses, m, serviceStatuses, retSrc);
        addDirectThrowStatuses(responses, m, adviceExStatus, types, retSrc);
        addLocalHandlerStatuses(responses, localHandlerStatuses, retSrc);

        // Spring method-security is most-specific-wins: a method-level @PreAuthorize/@Secured/@RolesAllowed REPLACES the
        // class default (it does not union). So a method @PreAuthorize("permitAll()") on a secured controller is OPEN —
        // unioning would keep the class roles and fabricate a false SECURITY_MISMATCH against a correctly-open spec.
        List<String> security = hasSecurityAnnotation(m, types)
                ? securityOf(m, types)
                : new ArrayList<>(classSecurity);
        // Surface the honest gap when authorization comes from an annotation we couldn't resolve from sources.
        security.stream().filter(s -> s.startsWith("unresolved:")).findFirst().ifPresent(tok ->
                blindSpots.add("Controller " + controllerClass + "." + m.getNameAsString() + " is guarded by "
                        + tok.substring("unresolved:".length()) + ", which could not be resolved from the scanned "
                        + "sources — its authorization is treated as present-but-unknown, not analysed."));

        SourceRef src = SourceRef.code(file, line(m), line(m), m.getDeclarationAsString(false, false, false));
        List<Endpoint> out = new ArrayList<>();
        for (String b : bases.isEmpty() ? List.of("") : bases) {
            for (String mp : methodPaths.isEmpty() ? List.of("") : methodPaths) {
                String path = joinPath(b, mp);
                for (HttpMethod verb : mm.verbs()) {
                    out.add(new Endpoint(verb, path, m.getNameAsString(), params, body, responses,
                            consumes, produces, security, src, controllerClass));
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
    /** True when a scanned implemented interface declares a @*Mapping method — those routes are not analysed (only
     *  superclass extends is walked), so the caller surfaces a blind spot even when the controller has its own methods. */
    private boolean implementsMappedInterface(ClassOrInterfaceDeclaration coid, Map<String, TypeDeclaration<?>> types) {
        for (ClassOrInterfaceType itf : coid.getImplementedTypes()) {
            if (types.get(itf.getNameAsString()) instanceof ClassOrInterfaceDeclaration idecl
                    && idecl.getMethods().stream().anyMatch(mm ->
                            mm.getAnnotations().stream().anyMatch(a -> a.getNameAsString().endsWith("Mapping")))) {
                return true;
            }
        }
        return false;
    }

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
                    // consumes/produces values may be MediaType.*_VALUE constants — resolve them to the real media type
                    // (e.g. application/json) via the same resolver the @ControllerAdvice path uses, not the raw literal.
                    String mt = mediaTypeFromExpr(x);
                    String v = mt != null ? mt : literal(x.toString());
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

    /** Spring binding-param required flag: default true, but false when required=false or a defaultValue is set. */
    private boolean bindingRequired(Parameter p, String annName) {
        return getAnnotation(p, annName)
                .map(a -> !"false".equals(namedMember(a, "required")) && namedMember(a, "defaultValue") == null)
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
        // Unwrap Optional<X> / OptionalInt|Long|Double so the param's type is the inner type, and an Optional-wrapped
        // param is never reported as required (else it false-diffs as type:object / required against an optional spec).
        Type pt = p.getType();
        boolean optional = false;
        String prim = optionalPrimitive(pt);
        if (prim != null) {
            optional = true;
        } else if (pt instanceof ClassOrInterfaceType oc && oc.getNameAsString().equals("Optional")
                && oc.getTypeArguments().map(ta -> !ta.isEmpty()).orElse(false)) {
            pt = oc.getTypeArguments().get().get(0);
            optional = true;
        }
        String simple = prim != null ? prim : simpleTypeName(pt);
        String[] tf = openApiType(simple);
        String type = tf[0];
        ConstraintSet cs = constraintsOf(p);
        if (collectionElement(pt) != null) {
            // A multi-value param (List/Set/Collection<E> or E[]) binds as an array (matching the spec's type=array),
            // not the {type:object} the catch-all openApiType assigns; @Size on it is an item-count bound, not length.
            type = "array";
            cs = cs.withoutLength();
        } else if (types.get(simple) instanceof EnumDeclaration ed) {
            // A param typed as a Java enum constrains the allowed values. Surface them as the param's enum constraint
            // so an enum drift becomes a contract-testable CONSTRAINT_GAP instead of an invisible string-vs-string match.
            type = "string";
            cs = cs.withEnumValues(enumValuesOf(ed));
        }
        return new ParamModel(name, loc, type, tf[1], required && !optional, cs,
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
                if (isJsonIgnored(c)) {
                    continue;   // not serialized → not part of the JSON contract (but @JsonIgnore(false) stays on the wire)
                }
                addField(out, seenNames, fieldOf(c.getNameAsString(), c.getType(), c, types));
            }
        } else {
            for (FieldDeclaration fd : td.getFields()) {
                // Skip statics and fields excluded from JSON (@JsonIgnore) — including them produces false
                // SCHEMA_FIELD_MISSING/EXTRA diffs against a spec that (correctly) omits them.
                if (fd.isStatic() || isJsonIgnored(fd)) {
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
            // byte[]/Byte[] is a BINARY payload Jackson serializes as a base64 STRING (OpenAPI string/binary), NOT a
            // JSON array — mirror the return-path unwrap() guard, else it false-diffs (array vs string) against a
            // faithful springdoc {type:string, format:byte} spec. Scoped to the ARRAY form: List<Byte> stays a real
            // array of integers.
            if (type instanceof ArrayType && ("byte".equals(element) || "Byte".equals(element))) {
                return new FieldModel(jsonName, "string", "byte", required, cs.withoutLength(), null, null);
            }
            String elemRef = types.containsKey(element) ? element + "[]" : null;
            // @Size on a collection is an item-count (minItems) bound, not string length — drop it so it doesn't
            // false-diff as a minLength CONSTRAINT_GAP against a spec that (correctly) uses minItems.
            return new FieldModel(jsonName, "array", null, required, cs.withoutLength(), elemRef, null);
        }
        // Enum-typed field → a string with an inline enum, not a phantom {type:object} ref.
        if (types.get(simple) instanceof EnumDeclaration ed) {
            return new FieldModel(jsonName, "string", null, required, cs.withEnumValues(enumValuesOf(ed)), null, null);
        }
        String refSchema = "object".equals(tf[0]) && types.containsKey(simple) ? simple : null;
        return new FieldModel(jsonName, tf[0], tf[1], required, cs, refSchema, null);
    }

    @SuppressWarnings("unchecked")
    private record LengthBounds(Integer min, Integer max) {
    }

    /** A numeric bound value plus whether it is exclusive (null = inclusive/absent). */
    private record Bound(Double value, Boolean exclusive) {
    }

    private ConstraintSet constraintsOf(NodeWithAnnotations<?> n) {
        LengthBounds len = readLengthBounds(n);
        Bound lower = readLowerBound(n);
        Bound upper = readUpperBound(n);
        String pattern = getAnnotation(n, "Pattern").map(this::patternRegexp).orElse(null);
        String format = has(n, "Email") ? "email" : null;
        return new ConstraintSet(len.min(), len.max(), lower.value(), upper.value(),
                lower.exclusive(), upper.exclusive(), pattern, null, format);
    }

    /** @Size min/max. NOTE: @NotBlank is captured via the field's `required` flag, NOT as minLength=1 — synthesizing a
     *  length constraint produced spurious CONSTRAINT_GAP findings against specs that (correctly) omit minLength. Parse
     *  DEFENSIVELY: a long-literal (@Min(0L)), underscored (@Size(min=1_000)), or constant (@Size(min=MAX)) value must
     *  degrade to null, NOT throw — an unguarded Integer/Double.valueOf here would abort the whole scan on idiomatic JSR-380. */
    private LengthBounds readLengthBounds(NodeWithAnnotations<?> n) {
        Optional<AnnotationExpr> size = getAnnotation(n, "Size");
        if (size.isEmpty()) {
            return new LengthBounds(null, null);
        }
        return new LengthBounds(toInt(firstString(size.get(), "min")), toInt(firstString(size.get(), "max")));
    }

    /** Lower numeric bound folding @Min, @DecimalMin (inclusive=false → exclusive; tighter bound wins), and the
     *  @Positive(>0)/@PositiveOrZero(>=0) sign shorthands (imply a 0 bound). */
    private Bound readLowerBound(NodeWithAnnotations<?> n) {
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
    private Bound readUpperBound(NodeWithAnnotations<?> n) {
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
    private String patternRegexp(AnnotationExpr a) {
        Expression e = memberExpr(a, "regexp");
        return e instanceof StringLiteralExpr sl ? sl.asString() : null;
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
                    BodyType bt = unwrap(m.getType(), blindSpots);
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
        LinkedHashSet<String> out = new LinkedHashSet<>();
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

    /** Error (>=400) statuses produced by a controller's OWN @ExceptionHandler methods — Spring scopes these to that
     *  controller's endpoints (a stronger signal than global @ControllerAdvice), but they were previously never
     *  scanned (extractAdvice is gated to @ControllerAdvice types). */
    private Map<Integer, String> localExceptionHandlerStatuses(TypeDeclaration<?> ctrl,
                                                               Map<String, TypeDeclaration<?>> types) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Object mo : ctrl.getMethods()) {
            MethodDeclaration m = (MethodDeclaration) mo;
            if (has(m, "ExceptionHandler")) {
                // A controller-LOCAL @ExceptionHandler is controller-scoped, not proven reachable from any SPECIFIC
                // sibling endpoint (we can't tell which endpoint throws the handled exception), so it is attached to
                // EVERY endpoint — it must therefore stay LOW (manual review), exactly like a global @ControllerAdvice.
                // Scoring it MEDIUM fabricated a STATUS_CODE_MISSING on non-throwing siblings. The genuinely
                // per-endpoint-reachable cases (the endpoint's own throw, or a one-hop service call that throws) are
                // attached separately at EXCEPTION_HANDLER_REACHABLE with real proof.
                String origin = catchAllHandler(m) ? "EXCEPTION_HANDLER_GLOBAL" : "EXCEPTION_HANDLER";
                for (int s : errorStatuses(m, types)) {
                    if (s >= 400) {
                        out.putIfAbsent(s, origin);
                    }
                }
            }
        }
        return out;
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
                    Set<Integer> statuses = new LinkedHashSet<>();
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

    /** Dictionary types whose body is a free-form object — never emitted as a named schema. */
    static final Set<String> MAP_LIKE = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "NavigableMap", "ConcurrentHashMap", "Properties",
            "MultiValueMap", "LinkedMultiValueMap");

    private String joinPath(String base, String method) {
        String b = nz(base);
        String mPart = nz(method);
        String joined = ("/" + b + "/" + mPart).replaceAll("/+", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        // Normalize regex path-variable constraints to the bare name: {id:\d+} -> {id} (Spring/OpenAPI match), with a
        // BRACE-BALANCED scan so a quantifier brace in the regex ({id:[0-9]{2}}) doesn't corrupt the token.
        joined = stripPathVarRegex(joined);
        return joined.isEmpty() ? "/" : joined;
    }

    /** Collapse each {@code {name:regex}} path variable to {@code {name}}, scanning matched braces so a nested brace
     *  in the regex (e.g. {@code {id:[0-9]{2}}}) is consumed as one unit instead of leaving a stray '}'. */
    static String stripPathVarRegex(String path) {
        if (path == null || path.indexOf('{') < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = path.length();
        while (i < n) {
            char c = path.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            int depth = 1;
            int colon = -1;
            while (j < n && depth > 0) {
                char d = path.charAt(j);
                if (d == '{') {
                    depth++;
                } else if (d == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                } else if (d == ':' && depth == 1 && colon < 0) {
                    colon = j;
                }
                j++;
            }
            String name = colon >= 0 ? path.substring(i + 1, colon) : path.substring(i + 1, Math.min(j, n));
            out.append('{').append(name).append('}');
            i = j + 1;
        }
        return out.toString();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * The app's Spring base path — the prefix every route is served under at runtime — read from the default
     * application config: {@code server.servlet.context-path} (+ {@code spring.mvc.servlet.path}) for MVC, or
     * {@code spring.webflux.base-path} for WebFlux. Returns "" when none is configured. When several configs declare
     * conflicting bases (multi-module / profiles), records a blind spot and applies none rather than guessing.
     */
    private String springBasePath(Path sourceRoot, List<String> blindSpots) {
        AppConfigs cfgs = findAppConfigs(sourceRoot);
        Set<String> defaultBases = new LinkedHashSet<>();
        // profile -> base, for a base configured under a Spring profile (a profile file, or a profile-gated document
        // inside a multi-doc application.yml). The active profile is environment-defined, so these never produce a
        // prefix — only a blind spot. LinkedHashMap keeps a stable order for the message.
        Map<String, String> profileBases = new LinkedHashMap<>();

        // Parse each YAML DOCUMENT separately (loadConfigDocuments) so an `on-profile` marker only gates a base in the
        // SAME document — a flattened merge would otherwise let a trailing profile document's marker poison a genuine
        // unconditional base from the default document.
        for (Path cfg : cfgs.defaults()) {
            for (Properties props : loadConfigDocuments(cfg)) {
                String base = basePathFrom(props);
                if (base.isEmpty()) {
                    continue;
                }
                String onProfile = onProfileLabel(props);
                if (!onProfile.isEmpty()) {
                    profileBases.putIfAbsent(onProfile, base);   // base lives under a profile-gated document
                } else {
                    defaultBases.add(base);                      // unconditional base in a profile-less document
                }
            }
        }
        for (Path cfg : cfgs.profiles()) {
            for (Properties props : loadConfigDocuments(cfg)) {
                String base = basePathFrom(props);
                if (!base.isEmpty()) {
                    profileBases.putIfAbsent(profileNameOf(cfg), base);
                }
            }
        }

        if (defaultBases.size() == 1) {
            return defaultBases.iterator().next();   // the default (no -Dspring.profiles.active) base — what runs as-is
        }
        if (defaultBases.size() > 1) {
            blindSpots.add("The service's application config declares more than one base path " + defaultBases
                    + "; the code-side base path was not applied, so endpoint paths may not line up with the spec's "
                    + "server base.");
            return "";
        }
        if (!profileBases.isEmpty()) {
            blindSpots.add("A base path is configured only under a Spring profile (" + formatProfileBases(profileBases)
                    + "); the active profile is environment-defined, so no code-side base path was applied and endpoint "
                    + "paths may not line up with the spec's server base.");
        }
        return "";
    }

    /**
     * The composed base path from one config's flattened properties: {@code server.servlet.context-path}
     * (+ {@code spring.mvc.servlet.path}) for MVC, or {@code spring.webflux.base-path} for WebFlux. Templated/root
     * segments are skipped via {@link #appendSegment}. Returns "" when none is configured.
     */
    private String basePathFrom(Properties props) {
        String base = "";
        base = appendSegment(base, props.getProperty("server.servlet.context-path"));
        base = appendSegment(base, props.getProperty("spring.mvc.servlet.path"));
        base = appendSegment(base, props.getProperty("spring.webflux.base-path"));
        return base;
    }

    private String profileNameOf(Path cfg) {
        java.util.regex.Matcher m = PROFILE_CONFIG.matcher(cfg.getFileName().toString());
        return m.matches() ? m.group(1) : cfg.getFileName().toString();
    }

    private String formatProfileBases(Map<String, String> profileBases) {
        return profileBases.entrySet().stream()
                .map(e -> e.getValue() + " under profile '" + e.getKey() + "'")
                .collect(Collectors.joining(", "));
    }

    private String appendSegment(String base, String seg) {
        // Skip empties, root, and unresolved placeholders (${...} / #{...}) — a templated base path can't be matched
        // statically, mirroring how the spec side skips a templated servers[].url.
        return (seg == null || seg.isBlank() || seg.equals("/") || seg.contains("{")) ? base : joinPath(base, seg);
    }

    /** Default {@code application.{yml,yaml,properties}} (preferring src/main/resources; never test configs). */
    private List<String> appConfigNames() {
        return List.of("application.yml", "application.yaml", "application.properties");
    }

    /** A profile-specific config {@code application-<profile>.{yml,yaml,properties}}; group 1 is the profile name.
     *  {@code (.+)} (not {@code [^.]+}) so multi-dot profile names like {@code application-prod.local.yml} are read. */
    private static final java.util.regex.Pattern PROFILE_CONFIG =
            java.util.regex.Pattern.compile("application-(.+)\\.(yml|yaml|properties)");

    /** Discovered application configs, split into default (profile-less) and profile-specific files. */
    private record AppConfigs(List<Path> defaults, List<Path> profiles) {}

    private AppConfigs findAppConfigs(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> all = s.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().replace('\\', '/').contains("/test/"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return appConfigNames().contains(n) || PROFILE_CONFIG.matcher(n).matches();
                    })
                    .collect(Collectors.toList());
            // Narrow each group to src/main/resources independently (so a profile config outside it isn't dropped just
            // because the defaults live under src/main/resources).
            List<Path> defaults = preferMainResources(all.stream()
                    .filter(p -> appConfigNames().contains(p.getFileName().toString()))
                    .collect(Collectors.toList()));
            List<Path> profiles = preferMainResources(all.stream()
                    .filter(p -> !appConfigNames().contains(p.getFileName().toString()))
                    .collect(Collectors.toList()));
            return new AppConfigs(defaults, profiles);
        } catch (Exception e) {
            return new AppConfigs(List.of(), List.of());
        }
    }

    private List<Path> preferMainResources(List<Path> configs) {
        List<Path> main = configs.stream()
                .filter(p -> p.toString().replace('\\', '/').contains("/src/main/resources/"))
                .collect(Collectors.toList());
        return main.isEmpty() ? configs : main;
    }

    /**
     * Each config DOCUMENT as flattened dot-keyed properties. A multi-document YAML yields ONE {@link Properties}
     * per document (split on {@code ---} separator lines) so a {@code spring.config.activate.on-profile} marker only
     * gates a base in its own document; {@code YamlPropertiesFactoryBean}'s default merge would flatten the boundaries
     * away. A {@code .properties} file (no multi-document concept) yields a single element.
     */
    private List<Properties> loadConfigDocuments(Path cfg) {
        try {
            if (cfg.getFileName().toString().endsWith(".properties")) {
                Properties p = new Properties();
                try (java.io.InputStream in = Files.newInputStream(cfg)) {
                    p.load(in);
                }
                return p.isEmpty() ? List.of() : List.of(p);
            }
            List<Properties> docs = new ArrayList<>();
            for (String doc : Files.readString(cfg).split("(?m)^---\\s*$")) {   // a line that is exactly '---'
                if (doc.isBlank()) {
                    continue;
                }
                org.springframework.beans.factory.config.YamlPropertiesFactoryBean y =
                        new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
                y.setResources(new org.springframework.core.io.ByteArrayResource(
                        doc.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                Properties p = y.getObject();
                if (p != null && !p.isEmpty()) {
                    docs.add(p);
                }
            }
            return docs;
        } catch (Exception e) {
            log.debug("Could not read application config {}: {}", cfg, e.getMessage());
            return List.of();
        }
    }

    /**
     * The profile a document is gated to via {@code spring.config.activate.on-profile}, or "" when it is unconditional.
     * Handles both the scalar form ({@code on-profile: prod}) and the list form ({@code on-profile: [prod, staging]},
     * which flattens to {@code …on-profile[0]}, {@code …on-profile[1]}) — so a profile-only base in list form is never
     * mistaken for an unconditional default.
     */
    private String onProfileLabel(Properties props) {
        // Spring Boot 2.4+ document-activation key, then the deprecated pre-2.4 `spring.profiles:` leaf (NOT
        // spring.profiles.active/.include/.group, which set/compose active profiles rather than gate a document).
        String modern = profileMarker(props, "spring.config.activate.on-profile");
        return modern.isEmpty() ? profileMarker(props, "spring.profiles") : modern;
    }

    /** The profile(s) a document is gated to under {@code key}, scalar or flattened list form, or "" when none. */
    private String profileMarker(Properties props, String key) {
        String scalar = props.getProperty(key);
        if (scalar != null && !scalar.isBlank()) {
            return scalar.trim();
        }
        List<String> vals = new ArrayList<>();
        for (int i = 0; ; i++) {
            String v = props.getProperty(key + "[" + i + "]");
            if (v == null) {
                break;
            }
            vals.add(v.trim());
        }
        return String.join(",", vals);
    }

    private Integer line(Node n) {
        return n.getBegin().map(pos -> pos.line).orElse(null);
    }

    private static final class File {
        static final String separator = java.io.File.separator;
    }
}
