package ca.bnc.qe.veritas.engine.extract.java;

import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.getAnnotation;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.memberExpr;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.extern.slf4j.Slf4j;

/**
 * Stateless helpers that resolve a Spring project's routing paths and runtime base path — class/method @*Mapping path
 * resolution (incl. constant refs, composed meta-annotations, and inherited base-class mappings), path join/normalize,
 * the app's {@code server.servlet.context-path} / {@code spring.mvc.servlet.path} / {@code spring.webflux.base-path}
 * base-path discovery across default/profile configs, and {@code MediaType.*_VALUE} media-type constant resolution.
 * Lifted verbatim out of JavaSpringExtractor (which was well over the S104 line threshold) into a collaborator that
 * static-imports {@link AnnotationSupport}; call sites in the extractor are unchanged (they static-import these).
 */
@Slf4j
final class PathResolver {

    private PathResolver() {
    }

    static List<String> classPaths(TypeDeclaration<?> ctrl, Map<String, TypeDeclaration<?>> types,
                                    Map<String, String> constants, List<String> blindSpots) {
        return classPaths(ctrl, types, constants, blindSpots, new HashSet<>());
    }

    static List<String> classPaths(TypeDeclaration<?> ctrl, Map<String, TypeDeclaration<?>> types,
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

    /** All declared paths of a mapping annotation, resolving constants; records a blind spot for the unresolvable. */
    static List<String> annotationPaths(AnnotationExpr a, Map<String, String> constants, List<String> blindSpots) {
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
    static String resolvePathExpr(Expression e, Map<String, String> constants) {
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
    static List<String> stringList(AnnotationExpr a, String member) {
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
                    // When it stays UNRESOLVED, SKIP it: emitting the raw source text ("MediaType.FOO_VALUE") as a media
                    // type fires a false CONSUMES_PRODUCES_MISMATCH. Leaving it out keeps consumes/produces honestly
                    // empty, which the DiffEngine's empty-code guard then suppresses.
                    String mt = mediaTypeFromExpr(x);
                    if (mt != null && !mt.isBlank()) {
                        out.add(mt);
                    }
                }
                return out;
            }
        }
        return List.of();
    }

    /** Static String constants (incl. interface fields) from the scanned sources, keyed by name and Owner.NAME. */
    static Map<String, String> collectStringConstants(List<CompilationUnit> units) {
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

    static String joinPath(String base, String method) {
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

    /**
     * The servlet-context-relative form of a base-prefixed path: strip the Spring base ({@code server.servlet.context-
     * path} etc.) that {@code pathTemplate} already carries, because Spring Security Ant-matches its chain patterns
     * against the context-relative path, not the full runtime path. A path equal to the base maps to root {@code "/"}.
     * With no base (default {@code ""}), the path is already context-relative and is returned unchanged.
     */
    static String relativeToBase(String pathTemplate, String springBase) {
        if (springBase == null || springBase.isEmpty()) {
            return pathTemplate;
        }
        if (pathTemplate.equals(springBase)) {
            return "/";
        }
        if (pathTemplate.startsWith(springBase + "/")) {
            return pathTemplate.substring(springBase.length());
        }
        return pathTemplate;   // not under the base (shouldn't happen once the base is applied) — match as-is
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

    static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * The app's Spring base path — the prefix every route is served under at runtime — read from the default
     * application config: {@code server.servlet.context-path} (+ {@code spring.mvc.servlet.path}) for MVC, or
     * {@code spring.webflux.base-path} for WebFlux. Returns "" when none is configured. When several configs declare
     * conflicting bases (multi-module / profiles), records a blind spot and applies none rather than guessing.
     */
    static String springBasePath(Path sourceRoot, List<String> blindSpots) {
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
    static String basePathFrom(Properties props) {
        String base = "";
        base = appendSegment(base, props.getProperty("server.servlet.context-path"));
        base = appendSegment(base, props.getProperty("spring.mvc.servlet.path"));
        base = appendSegment(base, props.getProperty("spring.webflux.base-path"));
        return base;
    }

    static String profileNameOf(Path cfg) {
        java.util.regex.Matcher m = PROFILE_CONFIG.matcher(cfg.getFileName().toString());
        return m.matches() ? m.group(1) : cfg.getFileName().toString();
    }

    static String formatProfileBases(Map<String, String> profileBases) {
        return profileBases.entrySet().stream()
                .map(e -> e.getValue() + " under profile '" + e.getKey() + "'")
                .collect(Collectors.joining(", "));
    }

    static String appendSegment(String base, String seg) {
        // Skip empties, root, and unresolved placeholders (${...} / #{...}) — a templated base path can't be matched
        // statically, mirroring how the spec side skips a templated servers[].url.
        return (seg == null || seg.isBlank() || seg.equals("/") || seg.contains("{")) ? base : joinPath(base, seg);
    }

    /** Default {@code application.{yml,yaml,properties}} (preferring src/main/resources; never test configs). */
    static List<String> appConfigNames() {
        return List.of("application.yml", "application.yaml", "application.properties");
    }

    /** A profile-specific config {@code application-<profile>.{yml,yaml,properties}}; group 1 is the profile name.
     *  {@code (.+)} (not {@code [^.]+}) so multi-dot profile names like {@code application-prod.local.yml} are read. */
    static final java.util.regex.Pattern PROFILE_CONFIG =
            java.util.regex.Pattern.compile("application-(.+)\\.(yml|yaml|properties)");

    /** Discovered application configs, split into default (profile-less) and profile-specific files. */
    record AppConfigs(List<Path> defaults, List<Path> profiles) {}

    static AppConfigs findAppConfigs(Path root) {
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

    static List<Path> preferMainResources(List<Path> configs) {
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
    static List<Properties> loadConfigDocuments(Path cfg) {
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
    static String onProfileLabel(Properties props) {
        // Spring Boot 2.4+ document-activation key, then the deprecated pre-2.4 `spring.profiles:` leaf (NOT
        // spring.profiles.active/.include/.group, which set/compose active profiles rather than gate a document).
        String modern = profileMarker(props, "spring.config.activate.on-profile");
        return modern.isEmpty() ? profileMarker(props, "spring.profiles") : modern;
    }

    /** The profile(s) a document is gated to under {@code key}, scalar or flattened list form, or "" when none. */
    static String profileMarker(Properties props, String key) {
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

    private static final Map<String, String> MEDIA_TYPE_CONSTANTS = Map.ofEntries(
            Map.entry("APPLICATION_JSON", "application/json"),
            Map.entry("APPLICATION_PROBLEM_JSON", "application/problem+json"),
            Map.entry("APPLICATION_PROBLEM_XML", "application/problem+xml"),
            Map.entry("APPLICATION_XML", "application/xml"),
            Map.entry("TEXT_PLAIN", "text/plain"),
            Map.entry("TEXT_HTML", "text/html"),
            Map.entry("APPLICATION_PDF", "application/pdf"),
            Map.entry("APPLICATION_OCTET_STREAM", "application/octet-stream"));

    static String mediaTypeFromExpr(Expression e) {
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
        String field = t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
        String suffix = field.endsWith("_VALUE") ? field.substring(0, field.length() - "_VALUE".length()) : field;
        String mapped = MEDIA_TYPE_CONSTANTS.get(suffix);
        if (mapped != null) {
            return mapped;   // fast path — the common constants, no reflection
        }
        // Fall back to reflecting the real value of a MediaType.*_VALUE String constant not in the fast-path map
        // (MULTIPART_FORM_DATA_VALUE, TEXT_EVENT_STREAM_VALUE, IMAGE_*_VALUE, …). Without this the raw source text
        // ("MediaType.MULTIPART_FORM_DATA_VALUE") leaks out as a media type and fires a false CONSUMES_PRODUCES_MISMATCH.
        if (field.endsWith("_VALUE")) {
            try {
                Object v = org.springframework.http.MediaType.class.getField(field).get(null);
                if (v instanceof String s) {
                    return s;
                }
            } catch (ReflectiveOperationException ignore) {
                // not a resolvable MediaType constant — fall through to null (caller leaves consumes/produces empty)
            }
        }
        return null;
    }
}
