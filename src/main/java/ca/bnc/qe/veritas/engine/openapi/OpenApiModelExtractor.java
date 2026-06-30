package ca.bnc.qe.veritas.engine.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Component;

/**
 * Parses an OpenAPI 3.x OR Swagger 2.0 document (swagger-parser auto-detects and converts 2.0 → 3.x) into
 * the canonical {@link ApiModel}. Parser messages are returned for L1 structural findings.
 */
@Component
public class OpenApiModelExtractor {

    public SpecParse extract(String specId, String content) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        // Deliberately NOT setResolveFully(true): full resolution INLINES every $ref, so a response/property that
        // references a named DTO loses its name (get$ref()==null) and reads as a bare "object" — which made the
        // DiffEngine emit a false RESPONSE_SCHEMA_MISMATCH on essentially every typed-DTO endpoint. With plain
        // resolve, components/schemas are still populated and $ref nodes keep their names so we can match by DTO name.
        // OpenAPIParser (not OpenAPIV3Parser) multiplexes parser extensions, incl. the Swagger 2.0 → 3.x converter.
        SwaggerParseResult result = new OpenAPIParser().readContents(content, null, options);
        List<String> messages = result.getMessages() == null ? new ArrayList<>() : new ArrayList<>(result.getMessages());
        OpenAPI openApi = result.getOpenAPI();
        if (openApi == null) {
            return new SpecParse(null, messages, false);
        }

        String version = detectVersion(content, openApi);
        // A spec path is relative to servers[].url, so prepend its base path (e.g. servers:/api → /api/owner) for an
        // apples-to-apples comparison with the code's controller mappings. Ignoring it (the prior behaviour) made every
        // endpoint look MISSING/EXTRA whenever the spec declared a base path the code's @RequestMapping also carries.
        String basePath = serverBasePath(openApi);
        // Spec features this extractor cannot fully model (schema composition, content-typed params, ranged/default
        // responses, free-form maps) are surfaced here rather than silently degrading into an empty model — mirroring
        // the code side's blind-spot honesty.
        List<String> blindSpots = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();
        if (openApi.getPaths() != null) {
            for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
                String path = joinBase(basePath, pathEntry.getKey());
                Map<PathItem.HttpMethod, Operation> ops = pathEntry.getValue().readOperationsMap();
                for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : ops.entrySet()) {
                    endpoints.add(toEndpoint(specId, path, opEntry.getKey(), opEntry.getValue(),
                            openApi.getSecurity(), openApi.getComponents(), blindSpots));
                }
            }
        }

        Map<String, SchemaModel> schemas = new LinkedHashMap<>();
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            Map<String, Schema> rawSchemas = openApi.getComponents().getSchemas();
            for (Map.Entry<String, Schema> s : rawSchemas.entrySet()) {
                schemas.put(s.getKey(), toSchema(specId, s.getKey(), s.getValue(), rawSchemas, blindSpots));
            }
        }

        String title = openApi.getInfo() != null ? openApi.getInfo().getTitle() : null;
        ApiModel model = new ApiModel(specId, title, version, version, endpoints, schemas, blindSpots);
        return new SpecParse(model, messages, true);
    }

    /**
     * The base path of the first {@code servers[].url}: an absolute URL (e.g. {@code http://host:8080/api} → {@code
     * /api}), a relative base ({@code /api} → {@code /api}), or none/root (→ {@code ""}, no prefix). A server whose
     * scheme/host/port is templated keeps its LITERAL path ({@code https://{env}.bnc.ca/ciam} → {@code /ciam}); only a
     * server whose PATH itself is templated ({@code https://api/{basePath}}, {@code /{version}}) or a fully-templated
     * server ({@code {server}}) yields {@code ""} — there is no static base to recover, so we surface it rather than
     * guess (keeping the code/spec base-path comparison symmetric). Trailing slash stripped.
     */
    static String serverBasePath(OpenAPI openApi) {
        if (openApi.getServers() == null || openApi.getServers().isEmpty()) {
            return "";
        }
        // Aggregate the literal base path of EVERY server, not just servers[0]. If they disagree, apply none — mirror
        // the code side, which refuses a prefix when the app config declares more than one base, rather than guessing
        // servers[0]. (A templated/unresolvable server contributes nothing, so a resolvable sibling still wins.)
        java.util.LinkedHashSet<String> bases = new java.util.LinkedHashSet<>();
        for (io.swagger.v3.oas.models.servers.Server server : openApi.getServers()) {
            String b = basePathOf(server.getUrl());
            if (!b.isEmpty()) {
                bases.add(b);
            }
        }
        return bases.size() == 1 ? bases.iterator().next() : "";
    }

    /** The normalized literal base path of a single {@code servers[].url}, or "" when none/root/unresolvable. */
    private static String basePathOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String p;
        if (url.contains("{")) {
            // Templated: URI.create chokes on '{', so salvage the literal path lexically — but only if the PATH is
            // literal. A '{' remaining in the extracted path means we refuse to assert a base.
            p = literalPathOfTemplatedServer(url);
        } else if (url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {   // absolute URL → take the path component only
            try {
                p = java.net.URI.create(url).getPath();
            } catch (RuntimeException e) {
                return "";
            }
        } else {
            p = url;   // already a relative base path
        }
        if (p == null || p.isBlank() || p.equals("/")) {
            return "";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /**
     * The literal path of a templated server URL, or {@code ""} when no static path can be recovered. Drops any
     * query/fragment, takes the path after {@code scheme://authority} (or the whole string for a relative URL), and
     * returns {@code ""} if that path still contains a {@code {var}} placeholder (a templated path segment, or a
     * fully-templated server with no resolvable path). The caller normalizes the result (leading/trailing slash).
     */
    private static String literalPathOfTemplatedServer(String url) {
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        String path;
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);   // first '/' after the (possibly templated) authority
            path = slash >= 0 ? s.substring(slash) : "";
        } else if (s.startsWith("/")) {
            path = s;   // relative base path
        } else {
            return "";  // no scheme and not path-rooted (e.g. "{server}") — nothing to recover
        }
        if (path.contains("{")) {
            return "";
        }
        return percentDecode(path);   // match the non-templated URI.create branch, which decodes %xx
    }

    /** Decode {@code %xx} escapes in a path so the templated and non-templated branches agree; '+' stays literal. */
    private static String percentDecode(String path) {
        if (path.indexOf('%') < 0) {
            return path;
        }
        try {
            return java.net.URLDecoder.decode(path.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return path;   // malformed escape — keep the raw literal rather than fail
        }
    }

    /** Prepend {@code base} to a spec {@code path} (base already has no trailing slash; path may be "" or "/"). */
    static String joinBase(String base, String path) {
        if (base.isEmpty()) {
            return path;
        }
        if (path == null || path.isEmpty() || path.equals("/")) {
            return base;
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    /**
     * Presence facts from a FULLY-resolved parse (setResolveFully), used ONLY to fact-check the LLM's "absence"
     * judgements — examples and schema properties/constraints behind a {@code $ref} are inlined here and so count as
     * present. Kept separate from {@link #extract} (whose name-preserving parse the DiffEngine depends on).
     */
    public SpecPresence presenceOf(String content) {
        ParseOptions options = new ParseOptions();
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIParser().readContents(content, null, options);
        OpenAPI openApi = result == null ? null : result.getOpenAPI();
        if (openApi == null) {
            return SpecPresence.empty();
        }
        boolean examples = false;
        boolean errors = false;
        if (openApi.getPaths() != null) {
            for (PathItem item : openApi.getPaths().values()) {
                for (Operation op : item.readOperationsMap().values()) {
                    if (op.getResponses() == null) {
                        continue;
                    }
                    for (Map.Entry<String, ApiResponse> r : op.getResponses().entrySet()) {
                        Integer status = parseStatus(r.getKey());
                        if (status != null && status >= 400) {
                            errors = true;
                        }
                        if (responseHasExamples(r.getValue())) {
                            examples = true;
                        }
                    }
                }
            }
        }
        boolean props = false;
        boolean constraints = false;
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            for (Schema<?> s : openApi.getComponents().getSchemas().values()) {
                if (schemaHasProperties(s)) {
                    props = true;
                }
                if (schemaHasConstraints(s)) {
                    constraints = true;
                }
            }
        }
        return new SpecPresence(examples, props, constraints, errors);
    }

    private boolean responseHasExamples(ApiResponse resp) {
        if (resp == null || resp.getContent() == null) {
            return false;
        }
        for (MediaType mt : resp.getContent().values()) {
            if (mt.getExample() != null || (mt.getExamples() != null && !mt.getExamples().isEmpty())) {
                return true;
            }
            if (mt.getSchema() != null && mt.getSchema().getExample() != null) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    private boolean schemaHasProperties(Schema s) {
        return s != null && s.getProperties() != null && !s.getProperties().isEmpty();
    }

    @SuppressWarnings("rawtypes")
    private boolean schemaHasConstraints(Schema s) {
        if (s == null) {
            return false;
        }
        if (s.getMinLength() != null || s.getMaxLength() != null || s.getMinimum() != null || s.getMaximum() != null
                || s.getPattern() != null || s.getFormat() != null || (s.getEnum() != null && !s.getEnum().isEmpty())) {
            return true;
        }
        if (s.getProperties() != null) {
            for (Object v : s.getProperties().values()) {
                if (schemaHasConstraints((Schema) v)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Endpoint toEndpoint(String specId, String path, PathItem.HttpMethod m, Operation op,
                               List<io.swagger.v3.oas.models.security.SecurityRequirement> globalSecurity,
                               io.swagger.v3.oas.models.Components components, List<String> blindSpots) {
        HttpMethod method = HttpMethod.valueOf(m.name());
        List<ParamModel> params = new ArrayList<>();
        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                params.add(toParam(specId, path, p, components, blindSpots));
            }
        }
        RequestBodyModel body = toRequestBody(specId, path, op.getRequestBody());
        // consumes = request-body content types; produces = union of response content types (for the media-type diff).
        // mediaTypes(...) is null when a body/response has no content, so coalesce to empty everywhere.
        List<String> consumes = op.getRequestBody() == null ? List.of()
                : orEmpty(mediaTypes(op.getRequestBody().getContent()));
        java.util.LinkedHashSet<String> producesSet = new java.util.LinkedHashSet<>();
        List<ResponseModel> responses = new ArrayList<>();
        if (op.getResponses() != null) {
            for (Map.Entry<String, ApiResponse> r : op.getResponses().entrySet()) {
                Integer status = parseStatus(r.getKey());
                if (status == null) {
                    // A range ('2XX'/'5XX') or 'default' key isn't a concrete status — surface it rather than silently
                    // dropping the response (its schema/media type would otherwise be invisible to the status diff).
                    blindSpots.add("Spec response '" + r.getKey() + "' on " + method.name() + " " + path
                            + " is a range/default key, not a concrete status code — not compared.");
                    continue;
                }
                var content = r.getValue() == null ? null : r.getValue().getContent();
                // `produces` reflects the SUCCESS content type (what @*Mapping(produces=...) governs on the code side);
                // error responses (e.g. application/problem+json on a 500) must not inflate it into a false mismatch.
                if (status >= 200 && status < 300) {
                    producesSet.addAll(orEmpty(mediaTypes(content)));
                }
                responses.add(new ResponseModel(status, contentSchemaRef(content), mediaTypes(content), "SPEC",
                        SourceRef.spec(specId, "/paths" + path + "/" + method.name().toLowerCase() + "/responses/" + status, null)));
            }
        }
        // operation security overrides global; the scheme names are what we compare for presence
        List<io.swagger.v3.oas.models.security.SecurityRequirement> sec =
                op.getSecurity() != null ? op.getSecurity() : globalSecurity;
        List<String> security = new ArrayList<>();
        boolean optionalAuth = false;
        if (sec != null) {
            for (var r : sec) {
                if (r == null || r.keySet() == null || r.keySet().isEmpty()) {
                    optionalAuth = true;   // an empty {} requirement is the OpenAPI sentinel for "anonymous permitted"
                } else {
                    security.addAll(r.keySet());
                }
            }
        }
        if (optionalAuth && !security.isEmpty()) {
            // The operation lists schemes but ALSO permits anonymous access (an empty {} alternative). Reporting it as
            // "requires X" would false-flag unsecured code as a SECURITY_MISMATCH, so model it as unsecured for the diff
            // and surface the optional schemes as a blind spot instead.
            blindSpots.add("Spec " + method.name() + " " + path + " declares OPTIONAL security (an empty {} alternative "
                    + "alongside " + security + ") — anonymous access is permitted, so it is not compared as required.");
            security = new ArrayList<>();
        }
        return new Endpoint(method, path, op.getOperationId(), params, body, responses,
                consumes, new ArrayList<>(producesSet), security,
                SourceRef.spec(specId, "/paths" + path + "/" + method.name().toLowerCase(), null));
    }

    private ParamModel toParam(String specId, String path, Parameter p, io.swagger.v3.oas.models.Components components,
                               List<String> blindSpots) {
        // setResolve(true) dereferences SCHEMA $refs in bodies/responses, but leaves a PARAMETER $ref — and a
        // parameter whose SCHEMA is itself a $ref to a reusable type/enum — UNRESOLVED. Dereference both by name
        // against components so the param's name, schema and (crucially) its enum are visible; otherwise an
        // enum-value drift on a $ref-typed param (the spec advertising a value the code rejects, and the code
        // accepting values the spec never documents) is silently missed. Stays name-preserving — no
        // setResolveFully, which would inline named DTOs and break schema-name matching elsewhere.
        // Follow the parameter $ref chain (a component param may itself $ref another), cycle-guarded.
        java.util.Set<String> seenParamRefs = new java.util.HashSet<>();
        while (p.get$ref() != null && components != null && components.getParameters() != null
                && seenParamRefs.add(p.get$ref())) {
            Parameter resolvedParam = components.getParameters().get(refName(p.get$ref()));
            if (resolvedParam == null) {
                break;
            }
            p = resolvedParam;
        }
        ParamLocation loc = switch (p.getIn() == null ? "query" : p.getIn()) {
            case "path" -> ParamLocation.PATH;
            case "header" -> ParamLocation.HEADER;
            case "cookie" -> ParamLocation.COOKIE;
            default -> ParamLocation.QUERY;
        };
        Schema schema = p.getSchema();
        // A complex param can be declared via `content` (parameter.content[media].schema) instead of `schema`; pull the
        // schema from the single content entry so its type/constraints/enum aren't lost (it would look opaque otherwise).
        if (schema == null && p.getContent() != null) {
            for (MediaType mt : p.getContent().values()) {
                if (mt != null && mt.getSchema() != null) {
                    schema = mt.getSchema();
                    break;
                }
            }
        }
        // deepObject serialization can't be reconciled against Spring's flat binding — surface it rather than guess.
        if (p.getStyle() != null && "deepobject".equalsIgnoreCase(p.getStyle().toString())) {
            blindSpots.add("Spec parameter '" + p.getName() + "' on " + path + " uses deepObject serialization, which "
                    + "is not modelled against the code binding.");
        }
        // Follow the schema $ref chain TRANSITIVELY (schema:{$ref:A}, A:{$ref:B}, B:{enum:[…]}) — a reusable enum is
        // often reached through more than one hop — with a cycle guard. Otherwise the enum stays invisible and the
        // value-level drift is missed.
        java.util.Set<String> seenSchemaRefs = new java.util.HashSet<>();
        while (schema != null && schema.get$ref() != null && components != null && components.getSchemas() != null
                && seenSchemaRefs.add(schema.get$ref())) {
            Schema resolvedSchema = components.getSchemas().get(refName(schema.get$ref()));
            if (resolvedSchema == null) {
                break;
            }
            schema = resolvedSchema;
        }
        boolean required = Boolean.TRUE.equals(p.getRequired()) || loc == ParamLocation.PATH;
        ConstraintSet cs = constraints(schema);
        // The allowed value set is often only in the DESCRIPTION prose ("Must be one of [A, B, C]"), not a
        // machine-readable enum. Parse it (best-effort) so an enum drift — the code accepting/rejecting values the
        // spec advertises — becomes a contract-testable CONSTRAINT_GAP rather than an invisible string-vs-string match.
        if (cs.enumValues() == null || cs.enumValues().isEmpty()) {
            List<String> prose = enumFromDescription(p.getDescription());
            if (prose != null) {
                cs = cs.withEnumFromDescription(prose);   // documented in prose — NOT a formal schema enum
            }
        }
        return new ParamModel(p.getName(), loc,
                schema != null ? schema.getType() : null,
                schema != null ? schema.getFormat() : null,
                required, cs,
                SourceRef.spec(specId, "/paths" + path + "/parameters/" + p.getName(), null));
    }

    /** Bracketed list of >=2 UPPER_SNAKE tokens in a description, e.g. "Must be one of [INDIVIDUAL, NBC2, NBC4]". */
    private static final java.util.regex.Pattern PROSE_ENUM = java.util.regex.Pattern.compile(
            "\\[\\s*([A-Z][A-Z0-9_]*(?:\\s*,\\s*[A-Z][A-Z0-9_]*)+)\\s*\\]");

    private List<String> enumFromDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        var m = PROSE_ENUM.matcher(description);
        if (!m.find()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String tok : m.group(1).split("\\s*,\\s*")) {
            if (!tok.isBlank()) {
                out.add(tok.trim());
            }
        }
        return out.size() >= 2 ? out : null;
    }

    private RequestBodyModel toRequestBody(String specId, String path, RequestBody body) {
        if (body == null || body.getContent() == null) {
            return null;
        }
        return new RequestBodyModel(contentSchemaRef(body.getContent()), Boolean.TRUE.equals(body.getRequired()),
                false, mediaTypes(body.getContent()), SourceRef.spec(specId, "/paths" + path + "/requestBody", null));
    }

    @SuppressWarnings("rawtypes")
    private SchemaModel toSchema(String specId, String name, Schema schema, Map<String, Schema> allSchemas,
                                List<String> blindSpots) {
        List<FieldModel> fields = new ArrayList<>();
        List<String> required = schema.getRequired() == null ? List.of() : schema.getRequired();
        collectSchemaProps(specId, name, schema.getProperties(), required, fields, allSchemas);
        // allOf (inheritance/mixin): merge each member's properties so the composed fields are visible to the diff.
        // INLINE members are flattened directly; a $ref member (the canonical inheritance idiom) is RESOLVED from
        // components and its properties flattened transitively (cycle-guarded). Only a genuinely unresolvable member
        // (external $ref, or nested oneOf/anyOf) leaves the field set incomplete and is surfaced as a blind spot.
        boolean unflattenedAllOf = false;
        if (schema.getAllOf() != null) {
            for (Object o : schema.getAllOf()) {
                Schema member = (Schema) o;
                if (member.getProperties() != null && !member.getProperties().isEmpty()) {
                    List<String> mreq = member.getRequired() == null ? required : member.getRequired();
                    collectSchemaProps(specId, name, member.getProperties(), mreq, fields, allSchemas);
                } else if (member.get$ref() != null) {
                    if (!flattenRefInto(specId, name, member.get$ref(), allSchemas, fields, new java.util.HashSet<>())) {
                        unflattenedAllOf = true;
                    }
                } else if (member.getAllOf() != null) {
                    unflattenedAllOf = true;
                }
            }
        }
        if (hasUnmodelledComposition(schema) || unflattenedAllOf) {
            blindSpots.add("Spec schema '" + name + "' uses " + compositionKinds(schema, unflattenedAllOf)
                    + " composition; its composed structure is not fully compared (field-level diff may be incomplete).");
        }
        // nullable:true (value may be null) is orthogonal to required (key must be present) and has no model channel,
        // so a non-null code field vs a nullable spec field is not compared — surface it rather than silently drop it.
        if (schema.getProperties() != null && schema.getProperties().values().stream()
                .anyMatch(p -> p instanceof Schema s && Boolean.TRUE.equals(s.getNullable()))) {
            blindSpots.add("Spec schema '" + name + "' declares nullable propert(ies); nullability (a non-null code "
                    + "field vs a nullable spec field) is not compared — verify it.");
        }
        return new SchemaModel(name, schema.getType(), fields, enumStrings(schema),
                SourceRef.spec(specId, "/components/schemas/" + name, null));
    }

    /** Flatten a $ref-ed schema's properties (and its own allOf chain) into {@code fields}. Returns false when the ref
     *  cannot be resolved from components (external/dangling) so the caller records an honest blind spot. */
    @SuppressWarnings("rawtypes")
    private boolean flattenRefInto(String specId, String holder, String ref, Map<String, Schema> allSchemas,
                                   List<FieldModel> fields, java.util.Set<String> visited) {
        if (allSchemas == null) {
            return false;
        }
        String target = refName(ref);
        if (!visited.add(target)) {
            return true;   // cycle already visited — stop without claiming incompleteness
        }
        Schema base = allSchemas.get(target);
        if (base == null) {
            return false;   // unresolvable (external/dangling) → blind spot
        }
        List<String> req = base.getRequired() == null ? List.of() : base.getRequired();
        collectSchemaProps(specId, holder, base.getProperties(), req, fields, allSchemas);
        boolean ok = true;
        if (base.getAllOf() != null) {
            for (Object o : base.getAllOf()) {
                Schema m = (Schema) o;
                if (m.getProperties() != null && !m.getProperties().isEmpty()) {
                    collectSchemaProps(specId, holder, m.getProperties(),
                            m.getRequired() == null ? List.of() : m.getRequired(), fields, allSchemas);
                } else if (m.get$ref() != null) {
                    ok &= flattenRefInto(specId, holder, m.get$ref(), allSchemas, fields, visited);
                } else if (m.getAllOf() != null) {
                    ok = false;
                }
            }
        }
        return ok;
    }

    @SuppressWarnings("rawtypes")
    private void collectSchemaProps(String specId, String name, Map<String, Schema> props, List<String> required,
                                    List<FieldModel> fields, Map<String, Schema> allSchemas) {
        if (props == null) {
            return;
        }
        for (Map.Entry<String, Schema> prop : props.entrySet()) {
            String key = prop.getKey();
            if (fields.stream().anyMatch(f -> f.jsonName().equals(key))) {
                continue;   // own property already collected wins over an inherited one of the same name (allOf merge)
            }
            Schema ps = prop.getValue();
            String type = ps.getType();
            String format = ps.getFormat();
            ConstraintSet cs = constraints(ps);
            String refSchema = propRefSchema(ps);
            // A property that binds to a $ref-ed SCALAR LEAF (an enum/constrained primitive component, e.g.
            // status:{$ref:Status} where Status is `type:string, enum:[...]`) must surface that enum/type onto the
            // field — otherwise a real enum-value drift is mis-reported as a generic "constraints not exposed" gap.
            // Mirrors toParam's param-$ref enum follow. Object-DTO refs keep refSchema for name-based field-diff.
            String directRef = directRefName(ps);
            if (directRef != null && allSchemas != null) {
                Schema leaf = allSchemas.get(directRef);
                if (isScalarLeaf(leaf)) {
                    // Pull the enum/type/constraints onto the field so a value-level drift is precise; KEEP refSchema so
                    // rendering (corrected YAML) still reuses the named component and name-based diff is unaffected.
                    type = leaf.getType();
                    format = leaf.getFormat();
                    cs = constraints(leaf);
                }
            }
            fields.add(new FieldModel(key, type, format, required.contains(key), cs, refSchema,
                    SourceRef.spec(specId, "/components/schemas/" + name + "/properties/" + key, null)));
        }
    }

    /** A direct $ref target name on a property, including the single-member {@code allOf:[{$ref}]} (nullable-wrapper)
     *  idiom — but NOT an array-of-$ref (that stays an array, handled by propRefSchema). */
    @SuppressWarnings("rawtypes")
    private String directRefName(Schema ps) {
        if (ps.get$ref() != null) {
            return refName(ps.get$ref());
        }
        if (ps.getAllOf() != null && ps.getAllOf().size() == 1) {
            Schema only = (Schema) ps.getAllOf().get(0);
            if (only.get$ref() != null) {
                return refName(only.get$ref());
            }
        }
        return null;
    }

    /** True when a referenced schema is a scalar leaf (no properties, no allOf) carrying an enum or a non-object type. */
    @SuppressWarnings("rawtypes")
    private boolean isScalarLeaf(Schema s) {
        return s != null && s.getProperties() == null && s.getAllOf() == null
                && (s.getEnum() != null || (s.getType() != null && !"object".equals(s.getType())));
    }

    /** The named schema a property binds to: a direct $ref, the single-member allOf:[{$ref}] (nullable-wrapper) idiom,
     *  or "Foo[]" for an array-of-$ref (mirrors the code side's collection convention). Was {@code refName(ps.get$ref())},
     *  which is null for arrays and for the allOf-wrapped form — silently dropping the bound element DTO. */
    @SuppressWarnings("rawtypes")
    private String propRefSchema(Schema ps) {
        if (ps.get$ref() != null) {
            return refName(ps.get$ref());
        }
        if (ps.getItems() != null && ps.getItems().get$ref() != null) {
            return refName(ps.getItems().get$ref()) + "[]";
        }
        if (ps.getAllOf() != null && ps.getAllOf().size() == 1) {
            Schema only = (Schema) ps.getAllOf().get(0);
            if (only.get$ref() != null) {
                return refName(only.get$ref());
            }
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private boolean hasUnmodelledComposition(Schema s) {
        return s.getOneOf() != null || s.getAnyOf() != null || s.getDiscriminator() != null
                || s.getAdditionalProperties() instanceof Schema;   // additionalProperties:{schema} = free-form map
    }

    @SuppressWarnings("rawtypes")
    private String compositionKinds(Schema s, boolean unflattenedAllOf) {
        List<String> kinds = new ArrayList<>();
        if (s.getOneOf() != null) {
            kinds.add("oneOf");
        }
        if (s.getAnyOf() != null) {
            kinds.add("anyOf");
        }
        if (unflattenedAllOf) {
            kinds.add("allOf($ref)");
        }
        if (s.getDiscriminator() != null) {
            kinds.add("discriminator");
        }
        if (s.getAdditionalProperties() instanceof Schema) {
            kinds.add("additionalProperties");
        }
        return String.join(" / ", kinds);
    }

    @SuppressWarnings("rawtypes")
    private ConstraintSet constraints(Schema s) {
        if (s == null) {
            return ConstraintSet.empty();
        }
        return new ConstraintSet(
                s.getMinLength(), s.getMaxLength(),
                s.getMinimum() == null ? null : s.getMinimum().doubleValue(),
                s.getMaximum() == null ? null : s.getMaximum().doubleValue(),
                s.getExclusiveMinimum(), s.getExclusiveMaximum(),
                s.getPattern(), enumStrings(s), s.getFormat());
    }

    @SuppressWarnings("rawtypes")
    private List<String> enumStrings(Schema s) {
        if (s == null || s.getEnum() == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : s.getEnum()) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private String contentSchemaRef(io.swagger.v3.oas.models.media.Content content) {
        if (content == null) {
            return null;
        }
        // Prefer a JSON media type deterministically when a body declares several (application/json + application/xml);
        // first-wins on the Content map iteration order would otherwise bind an arbitrary schema.
        MediaType chosen = null;
        for (Map.Entry<String, MediaType> e : content.entrySet()) {
            if (e.getValue() == null || e.getValue().getSchema() == null) {
                continue;
            }
            if (chosen == null) {
                chosen = e.getValue();
            }
            if (e.getKey() != null && e.getKey().toLowerCase(java.util.Locale.ROOT).contains("json")) {
                chosen = e.getValue();
                break;
            }
        }
        if (chosen != null) {
            Schema sc = chosen.getSchema();
            if (sc.get$ref() != null) {
                return refName(sc.get$ref());
            }
            if (sc.getItems() != null && sc.getItems().get$ref() != null) {
                return refName(sc.getItems().get$ref()) + "[]";
            }
            // "array" with unresolvable items is a container, not a named schema — return null (unknown)
            // rather than the literal keyword "array", which would confuse name-based diff comparisons.
            if ("array".equals(sc.getType())) {
                return null;
            }
            return sc.getType();
        }
        return null;
    }

    private List<String> mediaTypes(io.swagger.v3.oas.models.media.Content content) {
        return content == null ? null : new ArrayList<>(content.keySet());
    }

    private List<String> orEmpty(List<String> v) {
        return v == null ? List.of() : v;
    }

    private String refName(String ref) {
        if (ref == null) {
            return null;
        }
        int i = ref.lastIndexOf('/');
        return i >= 0 ? ref.substring(i + 1) : ref;
    }

    private Integer parseStatus(String key) {
        try {
            return Integer.parseInt(key.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String detectVersion(String content, OpenAPI openApi) {
        if (content != null && content.contains("swagger:") && content.contains("2.0")) {
            return "2.0";
        }
        return openApi.getOpenapi() != null ? openApi.getOpenapi() : "3.0";
    }
}
