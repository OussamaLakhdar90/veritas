package ca.bnc.qe.veritas.engine.openapi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Builds a drop-in corrected OpenAPI 3.0 document <b>deterministically from the code {@link ApiModel}</b>
 * (code is the source of truth). Used as the reliable corrected YAML when the LLM reconcile is unavailable or
 * its output fails round-trip validation. Pure + deterministic — no LLM.
 */
@Component
@Slf4j
public class CorrectedSpecBuilder {

    // snakeyaml 2.x defaults to a 3 MB code-point limit and rejects duplicate keys, but the spec EXTRACTOR uses
    // swagger-parser (neither limit). Without matching that here, a large real /v3/api-docs (springdoc emits one
    // minified line, easily > 3 MB) parses for the extractor yet THROWS when this mapper re-reads it for the metadata
    // overlay — so info/servers silently fall back to placeholders. Lift both limits to match swagger-parser.
    private final YAMLMapper yaml = YAMLMapper.builder(
            YAMLFactory.builder().loaderOptions(bigLoaderOptions()).build()).build();

    private static LoaderOptions bigLoaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);   // no 3 MB cap — match swagger-parser
        options.setAllowDuplicateKeys(true);            // last-wins, like swagger-parser (some hand-rolled specs repeat keys)
        return options;
    }

    public String build(ApiModel code, String title) {
        return build(code, title, null);
    }

    /**
     * Build the corrected spec from code, then overlay any {@code x-*} vendor extensions from the original spec
     * (root, info, path-item and operation level) so integration metadata (e.g. {@code x-amazon-apigateway-*},
     * {@code x-google-backend}) survives the round-trip. Code still wins on behaviour; extensions are additive.
     */
    public String build(ApiModel code, String title, String originalSpecYaml) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.0.3");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title == null ? "Corrected API" : title);
        info.put("version", "1.0.0");
        root.put("info", info);

        Map<String, Object> paths = new LinkedHashMap<>();
        for (Endpoint e : code.endpoints()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pathItem = (Map<String, Object>) paths.computeIfAbsent(e.pathTemplate(),
                    k -> new LinkedHashMap<String, Object>());
            pathItem.put(e.method().name().toLowerCase(), operation(e));
        }
        root.put("paths", paths);

        if (code.schemas() != null && !code.schemas().isEmpty()) {
            Map<String, Object> schemas = new LinkedHashMap<>();
            code.schemas().forEach((name, s) -> schemas.put(name, schema(s)));
            root.put("components", Map.of("schemas", schemas));
        }

        if (originalSpecYaml != null && !originalSpecYaml.isBlank()) {
            overlayExtensions(root, originalSpecYaml);
            preserveOriginalMetadata(root, originalSpecYaml);
        }

        try {
            return yaml.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Corrected YAML serialization failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Overlay the original spec's real metadata onto an <b>already-built corrected YAML from any source</b> — the LLM
     * reconcile <i>or</i> the deterministic {@link #build}: the {@code info} block (real title/version), {@code servers},
     * per-response {@code example(s)}, and {@code x-*} vendor extensions. This is what makes the corrected spec an
     * honest drop-in replacement — its identity and servers match the original; only paths/schemas are code-corrected.
     * The LLM invents placeholder {@code info}/{@code servers} it was never given, so its output must pass through this
     * before it can claim to be a drop-in. Returns the input unchanged when either document is absent or unparseable
     * (non-fatal — better a verbatim corrected spec than none).
     */
    @SuppressWarnings("unchecked")
    public String withOriginalMetadata(String correctedYaml, String originalSpecYaml) {
        if (correctedYaml == null || correctedYaml.isBlank()
                || originalSpecYaml == null || originalSpecYaml.isBlank()) {
            return correctedYaml;
        }
        Map<String, Object> root;
        try {
            root = yaml.readValue(correctedYaml, Map.class);
        } catch (Exception e) {
            log.warn("CorrectedSpecBuilder: corrected YAML is not a mapping — returned as-is, no metadata overlay ({})",
                    e.toString());
            return correctedYaml;   // corrected YAML isn't a mapping we can enrich → leave it exactly as-is
        }
        if (root == null) {
            return correctedYaml;
        }
        overlayExtensions(root, originalSpecYaml);
        preserveOriginalMetadata(root, originalSpecYaml);
        try {
            return yaml.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("CorrectedSpecBuilder: re-serialization failed — returned pre-overlay corrected YAML ({})",
                    e.toString());
            return correctedYaml;   // re-serialization failed → return the untouched original, never null
        }
    }

    /**
     * Sanitise an already-built corrected spec so it is <b>valid OpenAPI</b>: replace every {@code $ref} that points at
     * a schema NOT defined under {@code components/schemas} with a permissive inline {@code {type: object}}. A dangling
     * $ref — a generic {@code Object} error body from the deterministic build, or an LLM-invented schema name — makes
     * the "drop-in replacement" invalid (validators + codegen fail on it), yet a parse-only round-trip gate never
     * catches it (swagger-parser tolerates dangling refs). Applied at the write boundary to whichever corrected YAML is
     * chosen, so no dangling ref can ever ship. Returns the input unchanged when it is blank or not a mapping.
     */
    @SuppressWarnings("unchecked")
    public String withoutDanglingRefs(String correctedYaml) {
        if (correctedYaml == null || correctedYaml.isBlank()) {
            return correctedYaml;
        }
        Map<String, Object> root;
        try {
            root = yaml.readValue(correctedYaml, Map.class);
        } catch (Exception e) {
            return correctedYaml;   // not a mapping we can walk → leave it exactly as-is
        }
        if (root == null) {
            return correctedYaml;
        }
        resolveDanglingSchemaRefs(root);
        try {
            return yaml.writeValueAsString(root);
        } catch (Exception e) {
            return correctedYaml;
        }
    }

    /**
     * Enrich the corrected spec's ERROR responses (4xx/5xx) so a generic {@code {type: object}} body references the
     * ORIGINAL spec's documented error schema (e.g. {@code error-model}) instead of an anonymous object. The code
     * models error bodies as an untyped {@code Object}; the original usually documents a real error contract. The
     * schema is referenced DIRECTLY under the CORRECTED response's own media type — code wins on media type, so an
     * {@code application/problem+json} body is never swapped back to the spec's {@code application/json}, and the
     * spec's {@code examples} are never dragged in (both would be regressions). Matching is PATH-INDEPENDENT: the
     * code's routes routinely differ from the spec's by a context-path prefix or a renamed path variable
     * (e.g. {@code /ciam/policies} vs {@code /policies}, {@code {app}} vs {@code {appId}}), so an exact path match
     * almost never holds; instead we take the error schema the original documents for its OWN error responses and
     * reference it wherever the corrected body is still generic. A code-authoritative structured body (its own
     * {@code $ref}) is left untouched. Referenced schemas are copied transitively into {@code components}. Purely
     * additive; the caller re-validates (round-trip + dangling-ref sanitise), so this can never make a valid spec
     * invalid. Returns the input unchanged on any problem or when there is nothing to enrich.
     */
    @SuppressWarnings("unchecked")
    public String withErrorSchemasFromSpec(String correctedYaml, String originalSpecYaml) {
        if (correctedYaml == null || correctedYaml.isBlank() || originalSpecYaml == null || originalSpecYaml.isBlank()) {
            return correctedYaml;
        }
        Map<String, Object> root;
        Map<String, Object> orig;
        try {
            root = yaml.readValue(correctedYaml, Map.class);
            orig = yaml.readValue(originalSpecYaml, Map.class);
        } catch (Exception e) {
            return correctedYaml;
        }
        if (root == null || orig == null || !(root.get("paths") instanceof Map<?, ?> rPaths)) {
            return correctedYaml;
        }
        Map<String, Object> origSchemas = componentSchemas(orig);
        if (origSchemas == null || origSchemas.isEmpty()) {
            return correctedYaml;   // nothing structured to borrow
        }
        // What error schema does the ORIGINAL document for its OWN errors? Captured per-status where it varies, plus a
        // single canonical fallback. Deliberately PATH-INDEPENDENT: the code's routes routinely differ from the spec's
        // by a context-path prefix or a renamed path variable (/ciam/policies vs /policies, {app} vs {appId}), so an
        // exact path match almost never holds — that is why the old adopt-by-path approach silently enriched nothing.
        Map<String, String> byStatus = new LinkedHashMap<>();
        Set<String> documented = new LinkedHashSet<>();
        collectDocumentedErrorSchemas(orig, byStatus, documented);
        String canonical = documented.size() == 1 ? documented.iterator().next() : null;
        if (byStatus.isEmpty() && canonical == null) {
            return correctedYaml;   // the original documents no single structured error schema → nothing to reference
        }
        Set<String> wanted = new LinkedHashSet<>();
        if (!referenceErrorSchemas(asMap(rPaths), byStatus, canonical, origSchemas, wanted)) {
            return correctedYaml;   // no generic error body to enrich → leave verbatim
        }
        copySchemasTransitively(wanted, origSchemas, targetSchemas(root));
        try {
            return yaml.writeValueAsString(root);
        } catch (Exception e) {
            return correctedYaml;
        }
    }

    /** Scan the original's error responses (4xx/5xx, resolving {@code $ref: #/components/responses/*}) and record the
     *  leaf schema each references: {@code byStatus} maps a status to its schema when that status uses exactly one, and
     *  {@code documented} accumulates every distinct error schema (so a spec with a single {@code error-model} yields a
     *  canonical fallback). Media types are irrelevant here — only the referenced schema NAME is collected. */
    @SuppressWarnings("unchecked")
    private void collectDocumentedErrorSchemas(Map<String, Object> orig, Map<String, String> byStatus,
                                               Set<String> documented) {
        if (!(orig.get("paths") instanceof Map<?, ?> paths)) {
            return;
        }
        Map<String, Set<String>> perStatus = new LinkedHashMap<>();
        for (Object item : asMap(paths).values()) {
            if (!(item instanceof Map<?, ?> pathItem)) {
                continue;
            }
            for (Object op : asMap(pathItem).values()) {
                if (!(op instanceof Map<?, ?> opMap) || !(asMap(opMap).get("responses") instanceof Map<?, ?> resps)) {
                    continue;
                }
                for (Map.Entry<?, ?> r : asMap(resps).entrySet()) {
                    String status = String.valueOf(r.getKey());
                    if (!isErrorStatus(status) || !(r.getValue() instanceof Map<?, ?> resp)) {
                        continue;
                    }
                    Set<String> refs = new LinkedHashSet<>();
                    collectSchemaRefNames(resolveResponseContent(asMap(resp), orig), refs);
                    if (refs.isEmpty()) {
                        continue;
                    }
                    documented.addAll(refs);
                    perStatus.computeIfAbsent(status, k -> new LinkedHashSet<>()).addAll(refs);
                }
            }
        }
        perStatus.forEach((status, refs) -> {
            if (refs.size() == 1) {
                byStatus.put(status, refs.iterator().next());   // unambiguous per-status mapping
            }
        });
    }

    /** The error response's {@code content}, following a single {@code $ref: #/components/responses/<name>} into the
     *  original document (specs commonly define shared error responses once and reference them from every operation). */
    @SuppressWarnings("unchecked")
    private Object resolveResponseContent(Map<String, Object> resp, Map<String, Object> orig) {
        if (resp.get("$ref") instanceof String ref && ref.startsWith("#/components/responses/")) {
            String name = ref.substring("#/components/responses/".length());
            if (orig.get("components") instanceof Map<?, ?> comps
                    && asMap(comps).get("responses") instanceof Map<?, ?> responses
                    && asMap(responses).get(name) instanceof Map<?, ?> target) {
                return asMap(target).get("content");
            }
            return null;
        }
        return resp.get("content");
    }

    /** For every corrected error response whose body is still the generic {@code {type: object}} placeholder, point its
     *  schema(s) at the documented error schema (the per-status one, else the single canonical one), KEEPING the
     *  corrected response's own media type. A code-authoritative structured body ($ref) is left alone. Returns true if
     *  anything was referenced; records the schema names to copy into {@code components}. */
    @SuppressWarnings("unchecked")
    private boolean referenceErrorSchemas(Map<String, Object> rPaths, Map<String, String> byStatus, String canonical,
                                          Map<String, Object> origSchemas, Set<String> wanted) {
        boolean changed = false;
        for (Object item : rPaths.values()) {
            if (!(item instanceof Map<?, ?> pathItem)) {
                continue;
            }
            for (Object op : asMap(pathItem).values()) {
                if (!(op instanceof Map<?, ?> opMap) || !(asMap(opMap).get("responses") instanceof Map<?, ?> resps)) {
                    continue;
                }
                for (Map.Entry<?, ?> r : asMap(resps).entrySet()) {
                    String status = String.valueOf(r.getKey());
                    if (!isErrorStatus(status) || !(r.getValue() instanceof Map<?, ?> resp)) {
                        continue;
                    }
                    Map<String, Object> respMap = asMap(resp);
                    if (!isGenericErrorBody(respMap)) {
                        continue;   // code gave a structured error body → code wins, leave it
                    }
                    String name = byStatus.getOrDefault(status, canonical);
                    if (name == null || !origSchemas.containsKey(name)) {
                        continue;   // nothing documented for this status and no single canonical schema
                    }
                    if (pointSchemasAt(respMap.get("content"), name)) {
                        wanted.add(name);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    /** Replace every media type's {@code schema} in this response's {@code content} with a {@code $ref} to
     *  {@code #/components/schemas/<name>}, keeping the media-type keys (code wins on media type). Never fabricates
     *  content — returns false when there is no content to enrich. */
    @SuppressWarnings("unchecked")
    private boolean pointSchemasAt(Object content, String name) {
        if (!(content instanceof Map<?, ?> byMedia) || ((Map<String, Object>) byMedia).isEmpty()) {
            return false;
        }
        boolean set = false;
        for (Object media : asMap(byMedia).values()) {
            if (media instanceof Map<?, ?> mediaMap) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("$ref", "#/components/schemas/" + name);
                asMap(mediaMap).put("schema", ref);
                set = true;
            }
        }
        return set;
    }

    /** True when the corrected error response has NO structured body — an inline {@code {type: object}} placeholder
     *  (or nothing). When it already references a real schema, the CODE gave a structured error body and wins. */
    private boolean isGenericErrorBody(Map<String, Object> rResp) {
        Set<String> refs = new LinkedHashSet<>();
        collectSchemaRefNames(rResp.get("content"), refs);
        return refs.isEmpty();
    }

    /** Copy {@code wanted} schemas (and everything they reference, transitively) from the original components into the
     *  corrected ones — but never overwrite a schema the corrected already defines (code stays authoritative). */
    private void copySchemasTransitively(Set<String> wanted, Map<String, Object> origSchemas,
                                         Map<String, Object> targetSchemas) {
        Deque<String> queue = new ArrayDeque<>(wanted);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (targetSchemas.containsKey(name) || !origSchemas.containsKey(name)) {
                continue;   // already present (code's own) or absent upstream → skip (withoutDanglingRefs backstops)
            }
            Object schema = origSchemas.get(name);
            targetSchemas.put(name, deepCopy(schema));
            Set<String> nested = new LinkedHashSet<>();
            collectSchemaRefNames(schema, nested);
            queue.addAll(nested);
        }
    }

    /** The corrected document's {@code components/schemas} map, creating {@code components}/{@code schemas} if absent. */
    private Map<String, Object> targetSchemas(Map<String, Object> root) {
        Map<String, Object> schemas = componentSchemas(root);
        if (schemas != null) {
            return schemas;
        }
        Map<String, Object> comps = root.get("components") instanceof Map<?, ?> c ? asMap(c) : new LinkedHashMap<>();
        Map<String, Object> created = new LinkedHashMap<>();
        comps.put("schemas", created);
        root.put("components", comps);
        return created;
    }

    private void collectSchemaRefNames(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> m) {
            if (m.get("$ref") instanceof String r && r.startsWith("#/components/schemas/")) {
                out.add(r.substring("#/components/schemas/".length()));
            }
            for (Object v : m.values()) {
                collectSchemaRefNames(v, out);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collectSchemaRefNames(v, out);
            }
        }
    }

    private Object deepCopy(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object v : list) {
                out.add(deepCopy(v));
            }
            return out;
        }
        return node;
    }

    private boolean isErrorStatus(String status) {
        return status != null && !status.isEmpty() && (status.charAt(0) == '4' || status.charAt(0) == '5');
    }

    /**
     * True when this spec text parses AND carries the metadata the overlay lifts — a non-empty {@code info} block,
     * a non-empty {@code servers} list, or a Swagger-2.0 {@code host}. Used to choose the RIGHT spec source when a
     * scan supplies several (a Confluence page, a fragment, and the real OpenAPI doc): pick one that actually has
     * info/servers so the corrected YAML's metadata is real, not the first source by accident.
     */
    @SuppressWarnings("unchecked")
    public boolean carriesMetadata(String specYaml) {
        if (specYaml == null || specYaml.isBlank()) {
            return false;
        }
        Map<String, Object> orig;
        try {
            orig = yaml.readValue(specYaml, Map.class);
        } catch (Exception e) {
            return false;   // unparseable → can't lift metadata from it
        }
        if (orig == null) {
            return false;
        }
        boolean hasInfo = orig.get("info") instanceof Map<?, ?> info && !info.isEmpty();
        boolean hasServers = orig.get("servers") instanceof List<?> servers && !servers.isEmpty();
        boolean hasHost = orig.get("host") instanceof String host && !host.isBlank();
        return hasInfo || hasServers || hasHost;
    }

    /** Copy {@code x-*} extensions from the original spec into the corrected document (additive, code wins). */
    @SuppressWarnings("unchecked")
    private void overlayExtensions(Map<String, Object> root, String originalSpecYaml) {
        Map<String, Object> orig;
        try {
            orig = yaml.readValue(originalSpecYaml, Map.class);
        } catch (Exception e) {
            log.warn("CorrectedSpecBuilder: original spec unparseable for x-* overlay — extensions not carried "
                    + "(len={}, {})", originalSpecYaml.length(), e.toString());
            return;   // unparseable original → nothing to preserve, non-fatal
        }
        copyExt(orig, root);
        if (orig.get("info") instanceof Map<?, ?> oi && root.get("info") instanceof Map) {
            copyExt((Map<String, Object>) oi, (Map<String, Object>) root.get("info"));
        }
        if (orig.get("paths") instanceof Map<?, ?> oPaths && root.get("paths") instanceof Map) {
            Map<String, Object> rPaths = (Map<String, Object>) root.get("paths");
            for (Map.Entry<?, ?> pe : oPaths.entrySet()) {
                if (!(pe.getValue() instanceof Map<?, ?> oItem) || !(rPaths.get(pe.getKey()) instanceof Map)) {
                    continue;
                }
                Map<String, Object> rItem = (Map<String, Object>) rPaths.get(pe.getKey());
                copyExt((Map<String, Object>) oItem, rItem);
                for (Map.Entry<?, ?> me : oItem.entrySet()) {
                    if (me.getValue() instanceof Map<?, ?> oOp && rItem.get(me.getKey()) instanceof Map) {
                        copyExt((Map<String, Object>) oOp, (Map<String, Object>) rItem.get(me.getKey()));
                    }
                }
            }
        }
    }

    private void copyExt(Map<String, Object> from, Map<String, Object> to) {
        for (Map.Entry<String, Object> e : from.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith("x-") && !to.containsKey(e.getKey())) {
                to.put(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Copy through real metadata the code model cannot carry — the original {@code info} block (so the real title and
     * version replace the hard-coded placeholders), the {@code servers} array, and per-response {@code example(s)} —
     * so the corrected YAML reads as a drop-in replacement rather than regressing genuine spec metadata. Paths and
     * schemas stay code-derived; only info/servers/examples are lifted. Unparseable original → no-op (non-fatal).
     */
    @SuppressWarnings("unchecked")
    private void preserveOriginalMetadata(Map<String, Object> root, String originalSpecYaml) {
        Map<String, Object> orig;
        try {
            orig = yaml.readValue(originalSpecYaml, Map.class);
        } catch (Exception e) {
            log.warn("CorrectedSpecBuilder: original spec unparseable — corrected YAML keeps placeholder "
                    + "info/servers/examples (len={}, {})", originalSpecYaml.length(), e.toString());
            return;   // unparseable original → keep placeholder metadata, non-fatal
        }
        if (orig == null) {
            return;
        }
        if (orig.get("info") instanceof Map<?, ?> oInfo && !oInfo.isEmpty()) {
            root.put("info", new LinkedHashMap<>((Map<String, Object>) oInfo));   // real title/version/etc. wins
        }
        if (orig.get("servers") instanceof List<?> oServers && !oServers.isEmpty()) {
            root.put("servers", new ArrayList<>(oServers));   // verbatim server list (OpenAPI 3)
        } else {
            List<Map<String, Object>> synth = swagger2Servers(orig);   // Swagger 2.0 host+basePath+schemes
            if (!synth.isEmpty()) {
                root.put("servers", synth);
            }
        }
        if (orig.get("paths") instanceof Map<?, ?> oPaths && root.get("paths") instanceof Map) {
            preserveResponseExamples((Map<String, Object>) oPaths, (Map<String, Object>) root.get("paths"));
        }
        preserveComponentNames(root, orig);   // restore the spec's component names — a drop-in must not rename $ref keys
    }

    /**
     * Restore the ORIGINAL spec's component names in the corrected YAML. The corrected schemas are code-derived and
     * keyed by Java DTO class names (PascalCase); the original spec used its own names (e.g. kebab/dotted). Renaming
     * published component/{@code $ref} keys leaks Java naming into the public contract and breaks consumer codegen —
     * a "drop-in replacement" must keep them. Match a corrected schema to a spec schema by their PROPERTY-name set
     * (the wire fields agree even when the component key differs), then rename the corrected component keys + every
     * {@code $ref} uniformly (so it still round-trips). Only confident, unique, injective, non-colliding matches are
     * renamed; everything else keeps its code name.
     */
    private void preserveComponentNames(Map<String, Object> root, Map<String, Object> orig) {
        Map<String, Object> specSchemas = componentSchemas(orig);
        Map<String, Object> codeSchemas = componentSchemas(root);
        if (specSchemas == null || specSchemas.isEmpty() || codeSchemas == null || codeSchemas.isEmpty()) {
            return;
        }
        Map<String, String> rename = new LinkedHashMap<>();   // codeName -> specName
        Set<String> takenSpecNames = new HashSet<>();
        for (Map.Entry<String, Object> ce : codeSchemas.entrySet()) {
            String codeName = ce.getKey();
            Set<String> codeProps = propertyNames(ce.getValue());
            if (codeProps.isEmpty()) {
                continue;
            }
            String best = null;
            boolean ambiguous = false;
            for (Map.Entry<String, Object> se : specSchemas.entrySet()) {
                String specName = se.getKey();
                if (specName.equals(codeName) || takenSpecNames.contains(specName)
                        || codeSchemas.containsKey(specName)) {
                    continue;   // already same, already claimed, or would collide with an existing code schema
                }
                Set<String> specProps = propertyNames(se.getValue());
                // The corrected schema is a superset of the spec's fields (code may add fields); require the spec's
                // fields to be a non-empty subset of the code's — a strong structural signal these are the same schema.
                if (!specProps.isEmpty() && codeProps.containsAll(specProps)) {
                    if (best != null) {
                        ambiguous = true;   // more than one spec schema matches → don't guess
                        break;
                    }
                    best = specName;
                }
            }
            if (best != null && !ambiguous) {
                rename.put(codeName, best);
                takenSpecNames.add(best);
            }
        }
        if (rename.isEmpty()) {
            return;
        }
        // Rewrite every $ref across the WHOLE document, rebuilding into fresh mutable maps — the deterministic build()
        // tree is full of immutable Map.of literals, so we must not mutate in place. Then rename the component KEYS on
        // the rebuilt schemas map. Applied uniformly (keys + refs), so the corrected YAML still round-trips.
        Map<String, Object> rewritten = asMap(renameRefs(root, rename));
        Object componentsObj = rewritten.get("components");
        if (componentsObj instanceof Map<?, ?>) {
            Map<String, Object> comps = asMap(componentsObj);
            if (comps.get("schemas") instanceof Map<?, ?>) {
                Map<String, Object> renamedKeys = new LinkedHashMap<>();
                asMap(comps.get("schemas")).forEach((k, v) -> renamedKeys.put(rename.getOrDefault(k, k), v));
                comps.put("schemas", renamedKeys);   // comps is a fresh mutable map from renameRefs
            }
        }
        root.clear();
        root.putAll(rewritten);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> componentSchemas(Map<String, Object> doc) {
        if (doc.get("components") instanceof Map<?, ?> c
                && ((Map<String, Object>) c).get("schemas") instanceof Map<?, ?> s) {
            return (Map<String, Object>) s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> propertyNames(Object schema) {
        if (schema instanceof Map<?, ?> m && ((Map<String, Object>) m).get("properties") instanceof Map<?, ?> p) {
            return new HashSet<>(((Map<String, Object>) p).keySet());
        }
        return Set.of();
    }

    /**
     * Recursively rewrite every {@code $ref: #/components/schemas/<old>} to its renamed name, rebuilding into fresh
     * mutable maps/lists. Never mutates in place — the deterministic build tree uses immutable {@code Map.of} literals.
     */
    private Object renameRefs(Object node, Map<String, String> rename) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if ("$ref".equals(k) && v instanceof String r && r.startsWith("#/components/schemas/")) {
                    String target = r.substring("#/components/schemas/".length());
                    out.put(k, rename.containsKey(target) ? "#/components/schemas/" + rename.get(target) : r);
                } else {
                    out.put(k, renameRefs(v, rename));
                }
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object v : list) {
                out.add(renameRefs(v, rename));
            }
            return out;
        }
        return node;
    }

    /**
     * Swagger 2.0 has no {@code servers}; synthesize it from {@code <scheme>://<host><basePath>} so the corrected YAML
     * still carries the real server(s) instead of dropping them. Empty when there is no usable {@code host}.
     */
    private List<Map<String, Object>> swagger2Servers(Map<String, Object> orig) {
        if (!(orig.get("host") instanceof String host) || host.isBlank()) {
            return List.of();
        }
        String basePath = orig.get("basePath") instanceof String bp ? bp : "";
        List<?> schemes = orig.get("schemes") instanceof List<?> s && !s.isEmpty() ? s : List.of("https");
        List<Map<String, Object>> servers = new ArrayList<>();
        for (Object scheme : schemes) {
            servers.add(Map.of("url", scheme + "://" + host + basePath));
        }
        return servers;
    }

    /** For every corrected path+method+status that also exists in the original, lift the original response's
     *  {@code example(s)} (response-level and per-content-type) into the corrected response. Match by path string +
     *  method + status code; anything not present in BOTH is skipped. */
    @SuppressWarnings("unchecked")
    private void preserveResponseExamples(Map<String, Object> oPaths, Map<String, Object> rPaths) {
        for (Map.Entry<String, Object> re : rPaths.entrySet()) {
            if (!(re.getValue() instanceof Map<?, ?> rItem) || !(oPaths.get(re.getKey()) instanceof Map<?, ?> oItem)) {
                continue;
            }
            for (Map.Entry<?, ?> rm : ((Map<String, Object>) rItem).entrySet()) {
                if (!(rm.getValue() instanceof Map<?, ?> rOp) || !(oItem.get(rm.getKey()) instanceof Map<?, ?> oOp)) {
                    continue;
                }
                if (!(rOp.get("responses") instanceof Map<?, ?> rResps) || !(oOp.get("responses") instanceof Map<?, ?> oResps)) {
                    continue;
                }
                for (Map.Entry<?, ?> rr : rResps.entrySet()) {
                    if (rr.getValue() instanceof Map<?, ?> rResp && oResps.get(rr.getKey()) instanceof Map<?, ?> oResp) {
                        copyResponseExamples((Map<String, Object>) oResp, (Map<String, Object>) rResp);
                    }
                }
            }
        }
    }

    /** Copy the response-level {@code example} and each content-type's {@code example(s)} from the original response
     *  into the corrected one, mutating the corrected content maps (which are built immutable) into writable copies. */
    @SuppressWarnings("unchecked")
    private void copyResponseExamples(Map<String, Object> oResp, Map<String, Object> rResp) {
        if (oResp.containsKey("example") && !rResp.containsKey("example")) {
            rResp.put("example", oResp.get("example"));
        }
        if (!(oResp.get("content") instanceof Map<?, ?> oContent)) {
            return;
        }
        Map<String, Object> rContent = rResp.get("content") instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        for (Map.Entry<?, ?> oc : oContent.entrySet()) {
            if (!(oc.getValue() instanceof Map<?, ?> oMedia)) {
                continue;
            }
            Object hasEx = oMedia.get("examples");
            Object hasSingle = oMedia.get("example");
            if (hasEx == null && hasSingle == null) {
                continue;   // nothing to lift for this content type
            }
            Map<String, Object> rMedia = rContent.get(oc.getKey()) instanceof Map<?, ?> rm
                    ? new LinkedHashMap<>((Map<String, Object>) rm)
                    : new LinkedHashMap<>();
            if (hasEx != null && !rMedia.containsKey("examples")) {
                rMedia.put("examples", hasEx);
            }
            if (hasSingle != null && !rMedia.containsKey("example")) {
                rMedia.put("example", hasSingle);
            }
            rContent.put((String) oc.getKey(), rMedia);
        }
        if (!rContent.isEmpty()) {
            rResp.put("content", rContent);
        }
    }

    private Map<String, Object> operation(Endpoint e) {
        Map<String, Object> op = new LinkedHashMap<>();
        if (e.operationId() != null) {
            op.put("operationId", e.operationId());
        }
        List<Object> params = new ArrayList<>();
        for (ParamModel p : e.params()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("in", p.location().name().toLowerCase());
            pm.put("required", p.required());
            pm.put("schema", typeSchema(p.type(), p.format(), null));
            params.add(pm);
        }
        if (!params.isEmpty()) {
            op.put("parameters", params);
        }
        if (e.requestBody() != null && e.requestBody().schemaRef() != null) {
            op.put("requestBody", Map.of(
                    "required", e.requestBody().required(),
                    "content", Map.of("application/json", Map.of("schema", refSchema(e.requestBody().schemaRef())))));
        }
        Map<String, Object> responses = new LinkedHashMap<>();
        for (ResponseModel r : e.responses()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("description", descFor(r.statusCode()));
            if (r.schemaRef() != null) {
                // Emit the media type(s) the CODE declares (e.g. application/problem+json for RFC-7807 error bodies),
                // not a hardcoded application/json — otherwise the corrected spec contradicts its own findings that
                // flag the media-type drift. Same schema under each declared type; default to JSON when none captured.
                Map<String, Object> schema = Map.of("schema", refSchema(r.schemaRef()));
                List<String> media = r.mediaTypes() == null || r.mediaTypes().isEmpty()
                        ? List.of("application/json") : r.mediaTypes();
                Map<String, Object> content = new LinkedHashMap<>();
                for (String mt : media) {
                    content.put(mt, schema);
                }
                resp.put("content", content);
            }
            responses.put(String.valueOf(r.statusCode()), resp);
        }
        if (responses.isEmpty()) {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("description", "OK");
            responses.put("200", ok);   // mutable so metadata preservation can lift an original 200 example into it
        }
        op.put("responses", responses);
        if (e.security() != null && !e.security().isEmpty()) {
            op.put("security", List.of(Map.of("bearerAuth", List.of())));
        }
        return op;
    }

    private Map<String, Object> schema(SchemaModel s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        List<String> required = new ArrayList<>();
        Map<String, Object> props = new LinkedHashMap<>();
        for (FieldModel f : s.fields()) {
            props.put(f.jsonName(), typeSchema(f.type(), f.format(), f.refSchema()));
            if (f.required()) {
                required.add(f.jsonName());
            }
        }
        if (!required.isEmpty()) {
            out.put("required", required);
        }
        out.put("properties", props);
        return out;
    }

    private Map<String, Object> typeSchema(String type, String format, String refSchema) {
        if (refSchema != null) {
            return refSchema(refSchema);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type == null || "object".equals(type) ? "string" : type);   // unknown → string (safe, valid)
        if (format != null) {
            m.put("format", format);
        }
        return m;
    }

    private Map<String, Object> refSchema(String ref) {
        if (ref != null && ref.endsWith("[]")) {
            String base = ref.substring(0, ref.length() - 2);
            Map<String, Object> items = isPrimitive(base)
                    ? Map.of("type", base)                                    // scalar array → items:{type:string}
                    : Map.of("$ref", "#/components/schemas/" + base);         // DTO array → items:{$ref}
            return Map.of("type", "array", "items", items);
        }
        if (ref != null && isPrimitive(ref)) {
            return Map.of("type", ref);
        }
        return Map.of("$ref", "#/components/schemas/" + ref);
    }

    private boolean isPrimitive(String t) {
        return switch (t) {
            case "string", "integer", "number", "boolean", "array", "object" -> true;
            default -> false;
        };
    }

    /**
     * Replace every {@code $ref} that points at a schema NOT defined in {@code components/schemas} with a permissive
     * inline {@code {type: object}}. A dangling $ref — a generic {@code Object} error body, or an LLM-invented name —
     * makes the corrected spec INVALID OpenAPI (validators and codegen fail on it), defeating the whole "drop-in
     * replacement" purpose; a parse-only round-trip check never catches it (swagger-parser tolerates dangling refs).
     * Applied to the WHOLE document (responses, request bodies, schema fields, array items).
     */
    private void resolveDanglingSchemaRefs(Map<String, Object> root) {
        Map<String, Object> schemas = componentSchemas(root);
        Set<String> defined = schemas == null ? Set.of() : new HashSet<>(schemas.keySet());
        Map<String, Object> resolved = asMap(resolveDanglingRefs(root, defined));
        root.clear();
        root.putAll(resolved);
    }

    /** Rebuild the node, replacing any {@code {$ref: #/components/schemas/<undefined>}} with {@code {type: object}}. */
    private Object resolveDanglingRefs(Object node, Set<String> defined) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("$ref") instanceof String r && r.startsWith("#/components/schemas/")
                    && !defined.contains(r.substring("#/components/schemas/".length()))) {
                return Map.of("type", "object");   // undefined target → permissive free-form object (valid OpenAPI)
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), resolveDanglingRefs(e.getValue(), defined));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object v : list) {
                out.add(resolveDanglingRefs(v, defined));
            }
            return out;
        }
        return node;
    }

    private String descFor(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 500 -> "Internal Server Error";
            default -> "Response " + status;
        };
    }
}
