package ca.bnc.qe.veritas.engine.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.stereotype.Component;

/**
 * Builds a drop-in corrected OpenAPI 3.0 document <b>deterministically from the code {@link ApiModel}</b>
 * (code is the source of truth). Used as the reliable corrected YAML when the LLM reconcile is unavailable or
 * its output fails round-trip validation. Pure + deterministic — no LLM.
 */
@Component
public class CorrectedSpecBuilder {

    private final YAMLMapper yaml = new YAMLMapper();

    public String build(ApiModel code, String title) {
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

        try {
            return yaml.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Corrected YAML serialization failed: " + ex.getMessage(), ex);
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
                resp.put("content", Map.of("application/json", Map.of("schema", refSchema(r.schemaRef()))));
            }
            responses.put(String.valueOf(r.statusCode()), resp);
        }
        if (responses.isEmpty()) {
            responses.put("200", Map.of("description", "OK"));
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
            return Map.of("type", "array", "items", Map.of("$ref", "#/components/schemas/" + ref.replace("[]", "")));
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
