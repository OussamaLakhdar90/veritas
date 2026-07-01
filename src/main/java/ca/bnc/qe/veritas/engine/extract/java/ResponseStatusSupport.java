package ca.bnc.qe.veritas.engine.extract.java;

import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.firstString;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.getAnnotation;
import static ca.bnc.qe.veritas.engine.extract.java.TypeMappingSupport.simpleTypeName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.springframework.http.HttpStatus;

/**
 * Stateless helpers that resolve the HTTP status(es) an endpoint / exception handler produces — the tail-value walk over
 * a returned {@code ResponseEntity}, the exception→status mapping, and the reachability wiring that attaches error
 * statuses to an endpoint's responses. Lifted verbatim out of JavaSpringExtractor (which was well over the S104 line
 * threshold) into a collaborator that static-imports {@link AnnotationSupport}; call sites in the extractor are
 * unchanged (they static-import these).
 */
final class ResponseStatusSupport {

    private ResponseStatusSupport() {
    }

    /**
     * Resolve the HTTP status(es) an @ExceptionHandler produces, in priority order: a status set in the body via a
     * {@code ResponseEntity} (factory, {@code .status(...)}, or {@code new ResponseEntity<>(HttpStatus.X)}) — which
     * OVERWRITES a method @ResponseStatus at runtime (SPR-30305, mirroring the controller path); then an explicit
     * @ResponseStatus on the handler; then a {@code ProblemDetail.forStatus(AndDetail)(HttpStatus.X)}; then the handled
     * exception's own @ResponseStatus / a small framework-exception map. Returns an EMPTY list (never a phantom 500)
     * when nothing resolves, so the caller records an honest blind spot instead of guessing.
     */
    static List<Integer> errorStatuses(MethodDeclaration m, Map<String, TypeDeclaration<?>> types) {
        // A ResponseEntity in-body status WINS over a method @ResponseStatus (SPR-30305) — check it FIRST.
        LinkedHashSet<Integer> entity = new LinkedHashSet<>();
        for (Integer s : responseEntityStatuses(m)) {     // ResponseEntity.status(...) / factories
            if (s != null && s >= 400) {
                entity.add(s);
            }
        }
        entity.addAll(newResponseEntityStatuses(m));
        if (!entity.isEmpty()) {
            return new ArrayList<>(entity);
        }
        Integer ann = responseStatusAnnotation(m);
        if (ann != null) {
            return List.of(ann);
        }
        LinkedHashSet<Integer> body = new LinkedHashSet<>(problemDetailStatuses(m));
        if (!body.isEmpty()) {
            return new ArrayList<>(body);
        }
        Integer fromException = handledExceptionStatus(m, types);
        return fromException != null ? List.of(fromException) : List.of();
    }

    /** Status from a @ResponseStatus annotation on a node (handler method or exception class), or null. */
    static Integer responseStatusAnnotation(NodeWithAnnotations<?> n) {
        String code = getAnnotation(n, "ResponseStatus").map(a -> firstString(a, "value", "code")).orElse(null);
        return code == null ? null : statusFromText(code);
    }

    /** Statuses from {@code ProblemDetail.forStatus(...)} / {@code forStatusAndDetail(...)} calls in the body. */
    static List<Integer> problemDetailStatuses(MethodDeclaration m) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
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

    /** Error statuses from {@code new ResponseEntity<>(..., HttpStatus.X)} constructions in the body (an exception
     *  handler's whole body builds the error response, so this path is intentionally not return-scoped). */
    static List<Integer> newResponseEntityStatuses(MethodDeclaration m) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (ObjectCreationExpr oce : m.findAll(ObjectCreationExpr.class)) {
            Integer s = newResponseEntityStatus(oce);
            if (s != null && s >= 400) {
                out.add(s);
            }
        }
        return new ArrayList<>(out);
    }

    /** Status from the handled exception's own @ResponseStatus (if the class is in scope), else a framework map. */
    static Integer handledExceptionStatus(MethodDeclaration m, Map<String, TypeDeclaration<?>> types) {
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

    static List<String> handledExceptionNames(MethodDeclaration m) {
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

    static void collectClassNames(Expression e, List<String> out) {
        if (e instanceof ArrayInitializerExpr arr) {
            arr.getValues().forEach(v -> collectClassNames(v, out));
        } else if (e instanceof ClassExpr ce) {
            String tn = ce.getType().asString();
            out.add(tn.substring(tn.lastIndexOf('.') + 1));
        }
    }

    /** A small map of well-known Spring/JDK framework exceptions to the HTTP status Spring resolves them to. */
    static Integer frameworkExceptionStatus(String ex) {
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
    static boolean catchAllHandler(MethodDeclaration m) {
        for (String ex : handledExceptionNames(m)) {
            if (ex.equals("Exception") || ex.equals("RuntimeException") || ex.equals("Throwable")) {
                return true;
            }
        }
        return false;
    }

    /** Exception simple-names a method raises: {@code throw new X(...)} plus declared {@code throws X}. */
    static List<String> thrownExceptionNames(MethodDeclaration m) {
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
    static Integer exceptionStatusOf(String ex, Map<String, Integer> adviceExStatus, Map<String, TypeDeclaration<?>> types) {
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
    static Map<String, String> controllerFieldTypes(MethodDeclaration m) {
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

    static int responseStatus(MethodDeclaration m) {
        // Delegate to the full HttpStatus + numeric mapper (statusFromText) so a @ResponseStatus of CONFLICT/NOT_FOUND/
        // 422/etc. resolves to its true code — the old 4-way ladder collapsed everything else to a phantom 200, which
        // fabricated a STATUS_CODE_MISSING(200) and dropped the real status.
        Integer s = responseStatusAnnotation(m);
        return s != null ? s : 200;
    }

    /** True when a ResponseEntity's status is present but NOT statically readable here — a returned
     *  {@code ResponseEntity.status(arg)} whose arg didn't resolve, or a helper/factory delegation
     *  ({@code return factory.created(x)}) whose status lives in the callee. A bare pass-through / {@code return null}
     *  ResponseEntity is NOT unresolvable — it defaults to 200. The check is RETURN-scoped (the tail walk): a dynamic
     *  {@code ResponseEntity.status(var)} sitting in a non-returned position (a diagnostic that is only logged) is not a
     *  blind spot, so no blanket body scan is done. */
    static boolean responseEntityStatusUnresolvable(MethodDeclaration m) {
        for (ReturnStmt ret : m.findAll(ReturnStmt.class)) {
            Expression raw = ret.getExpression().orElse(null);
            if (raw == null) {
                continue;
            }
            // Unwrap casts/parentheses first, so a `return (ResponseEntity) factory.build(x);` is analysed as the
            // delegation it is (not skipped for being a CastExpr) — the cast form must behave like the un-cast form.
            Expression expr = unwrapExpr(raw);
            if (expr instanceof NameExpr ne) {
                // A returned LOCAL only blind-spots when at least one of its WRITES is opaque (aliased to another local,
                // set via this.<field>, a service delegation, a dynamic status) — the case whose status we truly can't
                // read. A local written SOLELY by readable factories (`resp = ok(x); if(async) resp = accepted(y);
                // return resp;` — the ubiquitous imperative conditional-status handler) is fully resolved by
                // allEntityStatuses and must NOT also be flagged. A bare pass-through (`return param`) has no writes.
                if (returnedLocalHasOpaqueWrite(m, ne.getNameAsString())) {
                    return true;
                }
            } else if (expr instanceof MethodCallExpr || expr instanceof ObjectCreationExpr
                    || expr instanceof ConditionalExpr) {
                // Decide on the ACTUALLY-RETURNED (tail) value, resolved through the fluent Optional/Stream chain — not a
                // blanket subtree scan. A direct ResponseEntity builder chain and a chain that BUILDS the ResponseEntity in
                // a tail position (`service.find(...).map(x -> ResponseEntity.ok(y)).orElseThrow(...)`, or
                // `.map(this::profileResponse)` delegating to a local helper) are fully resolvable. Only an OPAQUE tail —
                // a `return factory.created(x)`, a `.map(factory.build)` whose status lives in the callee, or a dynamic
                // `ResponseEntity.status(var)` — is unresolvable. Checking the tail (not the subtree) is what keeps a
                // ResponseEntity factory in a NON-returned position (an `.orElse(...)` fallback beside an opaque `.map`, a
                // `log(errorResponse())` side-call argument) from masking the real delegation.
                if (analyzeReturnTail(expr, m, true).opaque()) {
                    return true;
                }
            }
            // A null / literal / lambda return is not a delegation — it defaults to 200, not a blind spot.
        }
        return false;
    }

    /** The ROOT scope of a (possibly chained) method call: {@code ResponseEntity.ok().body(x)} → "ResponseEntity",
     *  {@code factory.created(x)} → "factory". Lets a ResponseEntity builder chain be told apart from a helper delegation. */
    static String rootScopeName(MethodCallExpr call) {
        Expression scope = call.getScope().orElse(null);
        while (scope instanceof MethodCallExpr inner) {
            scope = inner.getScope().orElse(null);
        }
        return scope == null ? "" : scope.toString();
    }

    /** Outcome of analysing the ACTUALLY-RETURNED (tail) value of a return expression: the statically-resolvable
     *  ResponseEntity statuses it yields, and whether any tail value is an OPAQUE delegation (its status lives in a
     *  callee we cannot read → a blind spot). {@code statuses} and {@code opaque} are independent — a return can yield a
     *  resolvable status on one branch and an opaque delegation on another (e.g. {@code opt.map(factory::build)
     *  .orElse(ResponseEntity.ok(x))}: status 200 from the fallback, opaque from the map). */
    private static final class TailResult {
        private final List<Integer> statuses = new ArrayList<>();
        private boolean opaque;

        boolean opaque() {
            return opaque;
        }

        void merge(TailResult other) {
            statuses.addAll(other.statuses);
            opaque |= other.opaque;
        }
    }

    /** All resolvable ResponseEntity statuses across every returned value (return expressions AND returned-local/field
     *  writes), RESOLVING one-hop local helpers — this recovers a status delegated to a local helper even when the
     *  delegation is bound into a returned LOCAL ({@code resp = svc.find(id).map(this::toCreated).orElseThrow(); return
     *  resp;}), which {@link #allEntityStatuses} (helpers-off, for recursion safety) cannot read. */
    static List<Integer> tailResolvableStatuses(MethodDeclaration m) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (Expression e : returnedValueExpressions(m)) {
            out.addAll(analyzeReturnTail(e, m, true).statuses);
        }
        return new ArrayList<>(out);
    }

    /** Analyse the actually-returned (tail) value(s) of a return expression, resolving THROUGH the fluent Optional/Stream
     *  chain — {@code map}/{@code flatMap} yield their function's result, {@code orElse}/{@code orElseGet} add their
     *  fallback, {@code orElseThrow}/{@code filter}/{@code get} pass the mapped content through — and through ternaries
     *  and lambda bodies. It deliberately does NOT descend into side-call arguments, filter predicates, or the throwing
     *  supplier of {@code orElseThrow}, so a ResponseEntity factory or helper call sitting in a non-returned position is
     *  neither harvested as a status nor allowed to mask a genuine opaque delegation. {@code resolveHelpers} bounds local
     *  helper resolution to ONE hop: true at the endpoint, false inside a helper's own analysis (no recursion / cycles). */
    private static TailResult analyzeReturnTail(Expression e, MethodDeclaration m, boolean resolveHelpers) {
        TailResult r = new TailResult();
        e = unwrapExpr(e);
        if (e instanceof ConditionalExpr c) {
            r.merge(analyzeReturnTail(c.getThenExpr(), m, resolveHelpers));
            r.merge(analyzeReturnTail(c.getElseExpr(), m, resolveHelpers));
            return r;
        }
        if (e instanceof ObjectCreationExpr oce) {
            Integer s = newResponseEntityStatus(oce);
            if (s != null) {
                r.statuses.add(s);
            } else {
                r.opaque = true;
            }
            return r;
        }
        if (e instanceof MethodReferenceExpr mr) {
            classifyMethodRef(mr, -1, m, resolveHelpers, r);
            return r;
        }
        if (e instanceof MethodCallExpr mc) {
            analyzeCallTail(mc, m, resolveHelpers, r);
            return r;
        }
        // A NameExpr / literal / other expression is not itself a ResponseEntity we can read a status from; the
        // returned-local case is handled separately (returnedLocalHasOpaqueWrite), a bare pass-through defaults to 200.
        r.opaque = true;
        return r;
    }

    private static void analyzeCallTail(MethodCallExpr mc, MethodDeclaration m, boolean resolveHelpers, TailResult r) {
        // A ResponseEntity factory/builder chain (root scope ResponseEntity) IS the returned value.
        if (isResponseEntityValued(mc)) {
            Integer s = responseEntityValuedStatus(mc);
            if (s != null) {
                r.statuses.add(s);
            } else {
                r.opaque = true;   // e.g. ResponseEntity.status(<dynamic>) — present but unreadable
            }
            return;
        }
        // A fluent Optional/Stream operator ALWAYS has a real receiver; an unqualified or this-qualified call is instead a
        // LOCAL method call — possibly a response helper NAMED like an operator (a sibling `get(id)`/`filter(x)`), which
        // must be classified as a helper, not mistaken for Optional.get()/Stream.filter().
        Expression scope = mc.getScope().orElse(null);
        if (scope == null || scope instanceof ThisExpr) {
            classifyHelperRef(mc.getNameAsString(), mc.getArguments().size(), m, resolveHelpers, r);
            return;
        }
        switch (mc.getNameAsString()) {
            case "orElse" -> {
                r.merge(receiverContentTail(mc, m, resolveHelpers));
                if (!mc.getArguments().isEmpty()) {
                    r.merge(analyzeReturnTail(mc.getArgument(0), m, resolveHelpers));
                }
            }
            case "orElseGet" -> {
                r.merge(receiverContentTail(mc, m, resolveHelpers));
                if (!mc.getArguments().isEmpty()) {
                    r.merge(functionResultTail(mc.getArgument(0), 0, m, resolveHelpers));
                }
            }
            case "orElseThrow", "filter", "get", "peek", "cache" -> r.merge(receiverContentTail(mc, m, resolveHelpers));
            case "map", "flatMap" -> {
                if (!mc.getArguments().isEmpty()) {
                    r.merge(functionResultTail(mc.getArgument(0), 1, m, resolveHelpers));
                }
            }
            default -> r.opaque = true;   // a receiver.op(...) we cannot read (factory.build(x), service.foo())
        }
    }

    /** The tail value the Optional/Stream content holds when a terminal ({@code orElse*}/{@code orElseThrow}/…) is
     *  applied: the function result of the nearest preceding {@code map}/{@code flatMap}, recursing past pass-through
     *  operators. When no {@code map} produced the content (e.g. {@code service.find(id).orElseThrow()}), it is opaque. */
    private static TailResult receiverContentTail(MethodCallExpr terminal, MethodDeclaration m, boolean resolveHelpers) {
        Expression scope = terminal.getScope().orElse(null);
        if (scope instanceof MethodCallExpr recv) {
            String rn = recv.getNameAsString();
            if ((rn.equals("map") || rn.equals("flatMap")) && !recv.getArguments().isEmpty()) {
                return functionResultTail(recv.getArgument(0), 1, m, resolveHelpers);
            }
            if (rn.equals("filter") || rn.equals("peek") || rn.equals("cache")) {
                return receiverContentTail(recv, m, resolveHelpers);
            }
        }
        TailResult r = new TailResult();
        r.opaque = true;
        return r;
    }

    /** The tail value a function argument (a lambda or a method reference) PRODUCES. {@code arity} is the arg count the
     *  functional interface binds ({@code map}→1, {@code orElseGet}→0) — used to pick the right helper overload. */
    private static TailResult functionResultTail(Expression fn, int arity, MethodDeclaration m, boolean resolveHelpers) {
        fn = unwrapExpr(fn);
        TailResult r = new TailResult();
        if (fn instanceof LambdaExpr lambda) {
            if (lambda.getExpressionBody().isPresent()) {
                return analyzeReturnTail(lambda.getExpressionBody().get(), m, resolveHelpers);
            }
            for (ReturnStmt ret : lambda.findAll(ReturnStmt.class)) {
                if (enclosingFunctionOf(ret) == lambda) {
                    ret.getExpression().ifPresent(x -> r.merge(analyzeReturnTail(x, m, resolveHelpers)));
                }
            }
            return r;
        }
        if (fn instanceof MethodReferenceExpr mr) {
            classifyMethodRef(mr, arity, m, resolveHelpers, r);
            return r;
        }
        r.opaque = true;   // an opaque function expression (a field-held Function)
        return r;
    }

    /** Classify a method reference used as a produced value: a ResponseEntity FACTORY reference ({@code ResponseEntity::ok}
     *  → 200, {@code ::noContent} → 204, …) resolves directly; a {@code this::helper} reference resolves one-hop to a
     *  local ResponseEntity helper (when {@code resolveHelpers}); anything else ({@code SomeType::x}) is opaque. */
    private static void classifyMethodRef(MethodReferenceExpr mr, int arity, MethodDeclaration m, boolean resolveHelpers,
                                   TailResult r) {
        Integer factory = factoryRefStatus(mr);
        if (factory != null) {
            r.statuses.add(factory);
            return;
        }
        if (resolveHelpers && mr.getScope() instanceof ThisExpr) {
            List<Integer> hs = localHelperStatusesFor(m, mr.getIdentifier(), arity);
            if (!hs.isEmpty()) {
                r.statuses.addAll(hs);
                return;
            }
        }
        r.opaque = true;
    }

    /** Classify a candidate LOCAL response helper call named {@code name} (unqualified or {@code this}-qualified): harvest
     *  its one-hop resolvable statuses (matched by name AND arity) when {@code resolveHelpers}; if none resolve, opaque. */
    private static void classifyHelperRef(String name, int arity, MethodDeclaration m, boolean resolveHelpers, TailResult r) {
        List<Integer> hs = resolveHelpers && name != null ? localHelperStatusesFor(m, name, arity) : List.of();
        if (hs.isEmpty()) {
            r.opaque = true;
        } else {
            r.statuses.addAll(hs);
        }
    }

    /** The status a ResponseEntity FACTORY method reference denotes ({@code ResponseEntity::ok} → 200,
     *  {@code ::noContent} → 204, …), or null when it is not a fixed-status ResponseEntity factory reference. */
    static Integer factoryRefStatus(MethodReferenceExpr mr) {
        String scope = mr.getScope().toString();
        if (!scope.equals("ResponseEntity") && !scope.endsWith(".ResponseEntity")) {
            return null;
        }
        return factoryNameStatus(mr.getIdentifier());
    }

    /** One-hop resolvable ResponseEntity statuses of a same-class sibling method matched by NAME and (when known) ARITY,
     *  returning ResponseEntity. {@code arity < 0} matches any arity (a method reference whose bound overload we don't
     *  pin down); otherwise only the same-parameter-count overload is read, so {@code this::render} binding
     *  {@code render(String)} never harvests a different {@code render(String, boolean)} overload's status. */
    static List<Integer> localHelperStatusesFor(MethodDeclaration m, String name, int arity) {
        ClassOrInterfaceDeclaration owner = m.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (owner == null) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (MethodDeclaration sib : owner.getMethods()) {
            if (sib != m && sib.getNameAsString().equals(name) && returnsResponseEntity(sib)
                    && (arity < 0 || sib.getParameters().size() == arity)) {
                out.addAll(allEntityStatuses(sib));
            }
        }
        return out;
    }

    /** True when a call's ROOT scope is {@code ResponseEntity} — a static factory or a builder chain on it. */
    static boolean isResponseEntityValued(MethodCallExpr mc) {
        String root = rootScopeName(mc);
        return root.equals("ResponseEntity") || root.endsWith(".ResponseEntity");
    }

    /** The status a ResponseEntity-valued call resolves to (the innermost readable factory/ctor in the builder chain),
     *  or null when the chain is ResponseEntity-rooted but its status is not statically readable ({@code status(var)}). */
    static Integer responseEntityValuedStatus(MethodCallExpr mc) {
        Integer found = null;
        for (MethodCallExpr c : mc.findAll(MethodCallExpr.class)) {
            Integer s = responseEntityFactoryStatus(c);
            if (s != null) {
                found = s;
            }
        }
        for (ObjectCreationExpr oce : mc.findAll(ObjectCreationExpr.class)) {
            Integer s = newResponseEntityStatus(oce);
            if (s != null) {
                found = s;
            }
        }
        return found;
    }

    /** The lambda or method this return statement belongs to directly (its nearest enclosing function), or null. */
    static Node enclosingFunctionOf(ReturnStmt ret) {
        Node p = ret.getParentNode().orElse(null);
        while (p != null) {
            if (p instanceof LambdaExpr || p instanceof MethodDeclaration) {
                return p;
            }
            p = p.getParentNode().orElse(null);
        }
        return null;
    }

    /** Unwrap parentheses and casts to the underlying expression. */
    static Expression unwrapExpr(Expression e) {
        while (true) {
            if (e instanceof EnclosedExpr en) {
                e = en.getInner();
            } else if (e instanceof CastExpr ce) {
                e = ce.getExpression();
            } else {
                return e;
            }
        }
    }

    /** True when a method's declared return type is {@code ResponseEntity} (raw or generic). */
    static boolean returnsResponseEntity(MethodDeclaration md) {
        String t = md.getTypeAsString();
        return t.equals("ResponseEntity") || t.startsWith("ResponseEntity<");
    }

    /** Add a status ResponseModel only if the list doesn't already carry that status (first/stronger-origin wins). */
    static void addIfAbsent(List<ResponseModel> responses, int status, String origin, SourceRef src) {
        if (responses.stream().noneMatch(r -> r.statusCode() == status)) {
            responses.add(new ResponseModel(status, null, null, origin, src));
        }
    }

    /** One-hop reachability: error statuses a CALLED service method (a controller field) throws are reachable from this
     *  endpoint — attach as EXCEPTION_HANDLER_REACHABLE (scored MEDIUM) BEFORE the blanket advice merge so the
     *  first-writer de-dup keeps this stronger origin. */
    static void addReachableServiceStatuses(List<ResponseModel> responses, MethodDeclaration m,
                                             Map<String, Set<Integer>> serviceStatuses, SourceRef retSrc) {
        Map<String, String> fieldTypes = controllerFieldTypes(m);
        LinkedHashSet<Integer> reachable = new LinkedHashSet<>();
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
            addIfAbsent(responses, s, "EXCEPTION_HANDLER_REACHABLE", retSrc);
        }
    }

    /** An error status the endpoint method THROWS DIRECTLY (a thrown exception whose class carries @ResponseStatus, or a
     *  known framework exception) is reachable from this endpoint — resolve via the exception→status map and attach it
     *  (serviceMethodStatuses skips a controller's own throws, so this is the only path for them). */
    static void addDirectThrowStatuses(List<ResponseModel> responses, MethodDeclaration m,
                                        Map<String, Integer> adviceExStatus, Map<String, TypeDeclaration<?>> types,
                                        SourceRef retSrc) {
        for (String ex : thrownExceptionNames(m)) {
            Integer s = exceptionStatusOf(ex, adviceExStatus, types);
            if (s != null && s >= 400) {
                addIfAbsent(responses, s, "EXCEPTION_HANDLER_REACHABLE", retSrc);
            }
        }
    }

    /** Error statuses produced by a controller-LOCAL @ExceptionHandler apply to every endpoint of that controller;
     *  their origin (LOW, controller-scoped) is carried in the map value. */
    static void addLocalHandlerStatuses(List<ResponseModel> responses, Map<Integer, String> localHandlerStatuses,
                                         SourceRef retSrc) {
        for (Map.Entry<Integer, String> e : localHandlerStatuses.entrySet()) {
            if (e.getKey() >= 400) {
                addIfAbsent(responses, e.getKey(), e.getValue(), retSrc);
            }
        }
    }

    /** The expressions that constitute a method's RETURNED values: every return expression, PLUS — for each name that is
     *  returned (`return local` / `return this.field`) — the values written to it (its declarator initializer, bare
     *  {@code name = ...} assignments, and {@code this.name = ...} assignments to a returned FIELD that no same-named
     *  local shadows). The no-shadow guard keeps an unrelated same-named field write from being mis-attributed to a
     *  returned LOCAL. Deliberately NOT transitive name aliasing (order-blind); an aliased return stays opaque. */
    static List<Expression> returnedValueExpressions(MethodDeclaration m) {
        List<ReturnStmt> returns = m.findAll(ReturnStmt.class);
        Set<String> returnedNames = new HashSet<>();
        for (ReturnStmt ret : returns) {
            Expression rex = ret.getExpression().map(ResponseStatusSupport::unwrapExpr).orElse(null);
            if (rex instanceof NameExpr ne) {
                returnedNames.add(ne.getNameAsString());           // `return r;`
            } else if (rex instanceof FieldAccessExpr fae && fae.getScope() instanceof ThisExpr) {
                returnedNames.add(fae.getNameAsString());          // `return this.field;`
            }
        }
        List<Expression> out = new ArrayList<>();
        returns.forEach(ret -> ret.getExpression().ifPresent(out::add));
        out.addAll(returnedNameWrites(m, returnedNames));
        return out;
    }

    /** Values written to any of {@code names}: declarator initializers, bare {@code name = ...} assignments, and
     *  {@code this.name = ...} assignments to a returned field that NO same-named local shadows. */
    static List<Expression> returnedNameWrites(MethodDeclaration m, Set<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        List<ReturnStmt> nameReturns = returnsOfNames(m, names);
        List<Expression> out = new ArrayList<>();
        Set<String> localNames = new HashSet<>();
        for (VariableDeclarator vd : m.findAll(VariableDeclarator.class)) {
            localNames.add(vd.getNameAsString());
            // Only a declarator whose lexical scope encloses a return OF THAT NAME feeds the returned value: a same-named
            // local declared in a DISJOINT block (legally reusing a short name like `resp` for a throwaway that is never
            // returned) must not have its initializer harvested as a phantom status.
            if (names.contains(vd.getNameAsString()) && vd.getInitializer().isPresent()
                    && declaratorInScopeOfReturn(vd, nameReturns)) {
                out.add(vd.getInitializer().get());
            }
        }
        for (AssignExpr ae : m.findAll(AssignExpr.class)) {
            Expression target = ae.getTarget();
            if (target instanceof NameExpr ne && names.contains(ne.getNameAsString())) {
                out.add(ae.getValue());   // `r = ...` (bare local/field)
            } else if (target instanceof FieldAccessExpr fae && fae.getScope() instanceof ThisExpr
                    && names.contains(fae.getNameAsString()) && !localNames.contains(fae.getNameAsString())) {
                out.add(ae.getValue());   // `this.<field> = ...` for a returned field with NO shadowing local
            }
        }
        return out;
    }

    /** The return statements whose (unwrapped) expression is a bare {@code return name} / {@code return this.name} for
     *  one of {@code names} — the returns a declarator of that name must be able to flow to. */
    static List<ReturnStmt> returnsOfNames(MethodDeclaration m, Set<String> names) {
        List<ReturnStmt> out = new ArrayList<>();
        for (ReturnStmt ret : m.findAll(ReturnStmt.class)) {
            Expression rex = ret.getExpression().map(ResponseStatusSupport::unwrapExpr).orElse(null);
            if ((rex instanceof NameExpr ne && names.contains(ne.getNameAsString()))
                    || (rex instanceof FieldAccessExpr fae && fae.getScope() instanceof ThisExpr
                        && names.contains(fae.getNameAsString()))) {
                out.add(ret);
            }
        }
        return out;
    }

    /** True when a declarator's lexical scope (its nearest enclosing block) encloses at least one of {@code returns} —
     *  i.e. the declared binding can actually reach that return. A declarator in a disjoint sibling block cannot. */
    static boolean declaratorInScopeOfReturn(VariableDeclarator vd, List<ReturnStmt> returns) {
        Node block = vd.findAncestor(BlockStmt.class).orElse(null);
        if (block == null) {
            return true;
        }
        for (ReturnStmt ret : returns) {
            if (block.isAncestorOf(ret)) {
                return true;
            }
        }
        return false;
    }

    /** Every statically-resolvable ResponseEntity status the method actually RETURNS — DIRECT factory calls and
     *  {@code new ResponseEntity<>(.., X)} in a tail position of a returned value (no local-helper hop, so this stays
     *  one-hop safe when reached recursively via {@link #localHelperStatusesFor}). Each returned value is read with the
     *  TAIL-precise walk, so a ResponseEntity factory in a non-returned position (an {@code orElseThrow} supplier, a
     *  {@code filter} predicate, a side-call argument) is NOT harvested — that would fabricate a phantom status. */
    static List<Integer> allEntityStatuses(MethodDeclaration m) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (Expression expr : returnedValueExpressions(m)) {
            out.addAll(analyzeReturnTail(expr, m, false).statuses);
        }
        return new ArrayList<>(out);
    }

    /** True when a returned LOCAL {@code name} has at least one WRITE (its declarator initializer or a bare
     *  {@code name = ...} / {@code this.name = ...} assignment) whose value is an OPAQUE ResponseEntity delegation — a
     *  status we cannot read (aliased to another local, a {@code this.<field>} indirection, a service/factory delegation,
     *  a dynamic {@code status(var)}). That is the case that IS an honest blind spot. A local written SOLELY by readable
     *  factories ({@code resp = ok(x); if (async) resp = accepted(y); return resp;}) is fully resolved by
     *  {@link #allEntityStatuses} and must NOT be flagged; a bare pass-through ({@code return param}) has no writes. */
    static boolean returnedLocalHasOpaqueWrite(MethodDeclaration m, String name) {
        for (Expression write : returnedNameWrites(m, Set.of(name))) {
            if (analyzeReturnTail(write, m, true).opaque()) {
                return true;
            }
        }
        return false;
    }

    static List<Integer> responseEntityStatuses(MethodDeclaration m) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            Integer s = responseEntityFactoryStatus(call);
            if (s != null) {
                out.add(s);
            }
        }
        return new ArrayList<>(out);
    }

    /** The HTTP status a static {@code ResponseEntity} factory call denotes (ok()/created()/status(X)/...), or null
     *  when the call is not a ResponseEntity factory. */
    static Integer responseEntityFactoryStatus(MethodCallExpr call) {
        String scope = call.getScope().map(Object::toString).orElse("");
        if (!scope.equals("ResponseEntity") && !scope.endsWith(".ResponseEntity")) {
            return null;   // only static factory calls ON ResponseEntity, e.g. ResponseEntity.created(...)
        }
        String name = call.getNameAsString();
        return name.equals("status") ? statusFromArg(call) : factoryNameStatus(name);
    }

    /** The fixed HTTP status a named {@code ResponseEntity} factory denotes ({@code ok} → 200, {@code noContent} → 204,
     *  …), or null for {@code status} (its status is an argument) and non-factory names. Shared by the call form
     *  ({@code ResponseEntity.ok(x)}) and the method-reference form ({@code ResponseEntity::ok}). */
    static Integer factoryNameStatus(String name) {
        return switch (name) {
            case "ok" -> 200;
            case "created" -> 201;
            case "accepted" -> 202;
            case "noContent" -> 204;
            case "badRequest" -> 400;
            case "notFound" -> 404;
            case "unprocessableEntity" -> 422;
            case "internalServerError" -> 500;
            default -> null;
        };
    }

    /** The HTTP status of a {@code new ResponseEntity<>(.., HttpStatus.X)} construction (the LAST arg is the status
     *  slot; scanning all args would misread a body/header whose text merely contains a status keyword), or null. */
    static Integer newResponseEntityStatus(ObjectCreationExpr oce) {
        if (!oce.getType().getNameAsString().equals("ResponseEntity") || oce.getArguments().isEmpty()) {
            return null;
        }
        return statusFromText(oce.getArgument(oce.getArguments().size() - 1).toString().trim());
    }

    /** Resolve the status from a {@code ResponseEntity.status(...)} argument (int literal or {@code HttpStatus.X}). */
    static Integer statusFromArg(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return null;
        }
        return statusFromText(call.getArgument(0).toString().trim());
    }

    /** Map an int literal or an {@code HttpStatus.X} expression's text to a numeric status code, or null. */
    static Integer statusFromText(String arg) {
        if (arg == null) {
            return null;
        }
        try {
            return Integer.parseInt(arg.trim());
        } catch (NumberFormatException ignore) {
            // not a literal — fall through to HttpStatus name mapping
        }
        // Resolve the trailing enum name (HttpStatus.SEE_OTHER / org...HttpStatus.SEE_OTHER / SEE_OTHER) against the
        // REAL Spring HttpStatus enum — the old hardcoded 14-entry ladder collapsed every other value (3xx redirects,
        // 205/206/207, 405/410/429, …) to null → a fabricated 200. HttpStatus has ~60 values; this resolves them all.
        String name = arg.substring(arg.lastIndexOf('.') + 1).trim();
        if (name.matches("[A-Z][A-Z0-9_]*")) {
            try {
                return HttpStatus.valueOf(name).value();
            } catch (IllegalArgumentException notAnHttpStatusName) {
                return null;
            }
        }
        return null;
    }
}
