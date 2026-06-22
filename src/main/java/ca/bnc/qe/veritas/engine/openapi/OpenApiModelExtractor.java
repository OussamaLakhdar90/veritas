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
        List<Endpoint> endpoints = new ArrayList<>();
        if (openApi.getPaths() != null) {
            for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
                String path = pathEntry.getKey();
                Map<PathItem.HttpMethod, Operation> ops = pathEntry.getValue().readOperationsMap();
                for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : ops.entrySet()) {
                    endpoints.add(toEndpoint(specId, path, opEntry.getKey(), opEntry.getValue(), openApi.getSecurity()));
                }
            }
        }

        Map<String, SchemaModel> schemas = new LinkedHashMap<>();
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            for (Map.Entry<String, Schema> s : openApi.getComponents().getSchemas().entrySet()) {
                schemas.put(s.getKey(), toSchema(specId, s.getKey(), s.getValue()));
            }
        }

        String title = openApi.getInfo() != null ? openApi.getInfo().getTitle() : null;
        ApiModel model = new ApiModel(specId, title, version, version, endpoints, schemas);
        return new SpecParse(model, messages, true);
    }

    private Endpoint toEndpoint(String specId, String path, PathItem.HttpMethod m, Operation op,
                               List<io.swagger.v3.oas.models.security.SecurityRequirement> globalSecurity) {
        HttpMethod method = HttpMethod.valueOf(m.name());
        List<ParamModel> params = new ArrayList<>();
        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                params.add(toParam(specId, path, p));
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
                    continue;
                }
                var content = r.getValue() == null ? null : r.getValue().getContent();
                producesSet.addAll(orEmpty(mediaTypes(content)));
                responses.add(new ResponseModel(status, contentSchemaRef(content), mediaTypes(content), "SPEC",
                        SourceRef.spec(specId, "/paths" + path + "/" + method.name().toLowerCase() + "/responses/" + status, null)));
            }
        }
        // operation security overrides global; the scheme names are what we compare for presence
        List<io.swagger.v3.oas.models.security.SecurityRequirement> sec =
                op.getSecurity() != null ? op.getSecurity() : globalSecurity;
        List<String> security = new ArrayList<>();
        if (sec != null) {
            sec.forEach(r -> security.addAll(r.keySet()));
        }
        return new Endpoint(method, path, op.getOperationId(), params, body, responses,
                consumes, new ArrayList<>(producesSet), security,
                SourceRef.spec(specId, "/paths" + path + "/" + method.name().toLowerCase(), null));
    }

    private ParamModel toParam(String specId, String path, Parameter p) {
        ParamLocation loc = switch (p.getIn() == null ? "query" : p.getIn()) {
            case "path" -> ParamLocation.PATH;
            case "header" -> ParamLocation.HEADER;
            case "cookie" -> ParamLocation.COOKIE;
            default -> ParamLocation.QUERY;
        };
        Schema schema = p.getSchema();
        boolean required = Boolean.TRUE.equals(p.getRequired()) || loc == ParamLocation.PATH;
        return new ParamModel(p.getName(), loc,
                schema != null ? schema.getType() : null,
                schema != null ? schema.getFormat() : null,
                required, constraints(schema),
                SourceRef.spec(specId, "/paths" + path + "/parameters/" + p.getName(), null));
    }

    private RequestBodyModel toRequestBody(String specId, String path, RequestBody body) {
        if (body == null || body.getContent() == null) {
            return null;
        }
        return new RequestBodyModel(contentSchemaRef(body.getContent()), Boolean.TRUE.equals(body.getRequired()),
                false, mediaTypes(body.getContent()), SourceRef.spec(specId, "/paths" + path + "/requestBody", null));
    }

    @SuppressWarnings("rawtypes")
    private SchemaModel toSchema(String specId, String name, Schema schema) {
        List<FieldModel> fields = new ArrayList<>();
        List<String> required = schema.getRequired() == null ? List.of() : schema.getRequired();
        if (schema.getProperties() != null) {
            Map<String, Schema> props = schema.getProperties();
            for (Map.Entry<String, Schema> prop : props.entrySet()) {
                Schema ps = prop.getValue();
                fields.add(new FieldModel(prop.getKey(), ps.getType(), ps.getFormat(),
                        required.contains(prop.getKey()), constraints(ps), refName(ps.get$ref()),
                        SourceRef.spec(specId, "/components/schemas/" + name + "/properties/" + prop.getKey(), null)));
            }
        }
        return new SchemaModel(name, schema.getType(), fields, enumStrings(schema),
                SourceRef.spec(specId, "/components/schemas/" + name, null));
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
        for (MediaType mt : content.values()) {
            if (mt.getSchema() != null) {
                Schema sc = mt.getSchema();
                if (sc.get$ref() != null) {
                    return refName(sc.get$ref());
                }
                if (sc.getItems() != null && sc.getItems().get$ref() != null) {
                    return refName(sc.getItems().get$ref()) + "[]";
                }
                return sc.getType();
            }
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
