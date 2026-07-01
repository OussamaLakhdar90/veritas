package ca.bnc.qe.veritas.engine.extract.java;

import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.has;
import static ca.bnc.qe.veritas.engine.extract.java.PathResolver.mediaTypeFromExpr;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.catchAllHandler;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.errorStatuses;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.exceptionStatusOf;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.handledExceptionNames;
import static ca.bnc.qe.veritas.engine.extract.java.ResponseStatusSupport.thrownExceptionNames;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.unwrap;
import ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.BodyType;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The @ControllerAdvice / coverage-blind-spot cluster of the Spring extractor, relocated verbatim from
 * {@link JavaSpringExtractor} to keep that class under the S104 line threshold. Behaviour is unchanged;
 * the seams to the core helpers are static imports.
 */
final class AdviceSupport {

    private AdviceSupport() {
    }

    /**
     * Records honest coverage blind spots for web-API code outside this extractor's reach: Kotlin controllers (it
     * parses Java only) and functional {@code RouterFunction} routing (it extracts annotation-based mappings only).
     * Without these, an all-Kotlin or functional-routing service yields an empty model with no signal of why.
     */
    static void addCoverageBlindSpots(Path sourceRoot, List<CompilationUnit> units, List<String> blindSpots) {
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

    /** Error responses from @ControllerAdvice/@RestControllerAdvice @ExceptionHandler methods (one per status). */
    static List<ResponseModel> extractAdvice(Path sourceRoot, List<CompilationUnit> units, List<String> referenced,
                                              Map<String, TypeDeclaration<?>> types, List<String> blindSpots) {
        List<ResponseModel> out = new ArrayList<>();
        for (CompilationUnit cu : units) {
            String file = cu.getStorage().map(s -> JavaSpringExtractor.relPath(sourceRoot, s.getPath())).orElse("?");
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
                                    SourceRef.code(file, JavaSpringExtractor.line(m), JavaSpringExtractor.line(m), m.getDeclarationAsString(false, false, false))));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Media types an @ExceptionHandler sets on its response via {@code .contentType(MediaType.X)} / a string literal. */
    private static List<String> adviceMediaTypes(MethodDeclaration m) {
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

    /** Error (>=400) statuses produced by a controller's OWN @ExceptionHandler methods — Spring scopes these to that
     *  controller's endpoints (a stronger signal than global @ControllerAdvice), but they were previously never
     *  scanned (extractAdvice is gated to @ControllerAdvice types). */
    static Map<Integer, String> localExceptionHandlerStatuses(TypeDeclaration<?> ctrl,
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
    static Map<String, Integer> adviceExceptionStatuses(List<CompilationUnit> units, Map<String, TypeDeclaration<?>> types) {
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
    static Map<String, Set<Integer>> serviceMethodStatuses(List<CompilationUnit> units,
                                                            Map<String, TypeDeclaration<?>> types,
                                                            Map<String, Integer> adviceExStatus) {
        Map<String, Set<Integer>> out = new HashMap<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                if (JavaSpringExtractor.isController(td, types)) {
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
}
