package ca.bnc.qe.veritas.codegen.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.SchemaModel;

/**
 * Narrows an {@link ApiModel} to a chosen subset of endpoints (by {@link Endpoint#signature()}) plus the schemas those
 * endpoints reach — so generation produces tests for only the endpoints the user selected in the wizard. Filtering the
 * model (rather than the generated files) means the ENDPOINTS list, DATA_MODELS, and the deterministic .http emit are
 * all scoped consistently in one place. An empty/blank scope is a no-op (generate for the whole service).
 */
public final class EndpointScope {

    private EndpointScope() {
    }

    public static ApiModel filter(ApiModel model, Set<String> signatures) {
        if (model == null || signatures == null || signatures.isEmpty()) {
            return model;
        }
        List<Endpoint> kept = new ArrayList<>();
        for (Endpoint e : model.endpoints()) {
            if (signatures.contains(e.signature())) {
                kept.add(e);
            }
        }
        Map<String, SchemaModel> all = model.schemas() == null ? Map.of() : model.schemas();
        return new ApiModel(model.source(), model.title(), model.version(), model.openApiVersion(),
                kept, reachableSchemas(kept, all), model.blindSpots());
    }

    /** Transitive closure of schemas referenced by the kept endpoints (request/response refs, then nested field refs). */
    private static Map<String, SchemaModel> reachableSchemas(List<Endpoint> endpoints, Map<String, SchemaModel> all) {
        Map<String, SchemaModel> out = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Endpoint e : endpoints) {
            if (e.requestBody() != null) {
                enqueue(queue, e.requestBody().schemaRef());
            }
            if (e.responses() != null) {
                e.responses().forEach(r -> enqueue(queue, r.schemaRef()));
            }
        }
        while (!queue.isEmpty()) {
            String name = baseName(queue.poll());
            if (name == null || out.containsKey(name)) {
                continue;
            }
            SchemaModel schema = all.get(name);
            if (schema == null) {
                continue;   // referenced but not in the map (external/unresolved) — already a blind spot upstream
            }
            out.put(name, schema);
            if (schema.fields() != null) {
                schema.fields().forEach(f -> enqueue(queue, f.refSchema()));
            }
        }
        return out;
    }

    private static void enqueue(Deque<String> queue, String ref) {
        if (ref != null && !ref.isBlank()) {
            queue.add(ref);
        }
    }

    /** Reduce a schema reference to the map key (simple name): strips {@code []}, {@code List<…>}, and $ref pointers. */
    private static String baseName(String ref) {
        if (ref == null) {
            return null;
        }
        String s = ref.trim();
        if (s.endsWith("[]")) {
            s = s.substring(0, s.length() - 2);
        }
        int lt = s.indexOf('<');
        int gt = s.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            s = s.substring(lt + 1, gt);   // List<Foo> → Foo
        }
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);    // #/components/schemas/Foo → Foo
        }
        return s.isBlank() ? null : s;
    }
}
