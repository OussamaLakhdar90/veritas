package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

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
import ca.bnc.qe.veritas.engine.openapi.CorrectedSpecBuilder;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

/**
 * Branch coverage for {@link CorrectedSpecBuilder}: feeds an {@link ApiModel} (code is the source of truth)
 * plus an optional original spec, and asserts the emitted corrected YAML — title defaulting, schema/components
 * presence, operationId / parameters / requestBody / responses / security branches, type and $ref handling,
 * every status-description case, and the additive {@code x-*} extension overlay (root / info / path / operation,
 * code wins on collision; unparseable / null / blank original short-circuits).
 */
class CorrectedSpecBuilderBranchTest {

    private final CorrectedSpecBuilder builder = new CorrectedSpecBuilder();
    private final YAMLMapper yaml = new YAMLMapper();
    private static final SourceRef SRC = SourceRef.code("X.java", 1, 1, "x");

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String s) {
        try {
            return yaml.readValue(s, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ParamModel param(String name, ParamLocation loc, String type, String format) {
        return new ParamModel(name, loc, type, format, true, ConstraintSet.empty(), SRC);
    }

    private Endpoint get(String path, List<ParamModel> params, List<ResponseModel> responses) {
        return new Endpoint(HttpMethod.GET, path, "getX", params, null, responses, null, null, List.of(), SRC);
    }

    private ApiModel model(List<Endpoint> endpoints, Map<String, SchemaModel> schemas) {
        return new ApiModel("code", null, null, null, endpoints, schemas);
    }

    // ---- title defaulting -------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void nullTitleDefaultsToCorrectedApi() {
        ApiModel code = model(List.of(get("/a", List.of(), List.of())), Map.of());
        Map<String, Object> root = parse(builder.build(code, null));
        Map<String, Object> info = (Map<String, Object>) root.get("info");
        assertThat(info).containsEntry("title", "Corrected API").containsEntry("version", "1.0.0");
        assertThat(root).containsEntry("openapi", "3.0.3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void providedTitleIsUsed() {
        ApiModel code = model(List.of(get("/a", List.of(), List.of())), Map.of());
        Map<String, Object> info = (Map<String, Object>) parse(builder.build(code, "My API")).get("info");
        assertThat(info).containsEntry("title", "My API");
    }

    // ---- schemas / components presence + required / properties ------------

    @Test
    void nullSchemasOmitsComponents() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(get("/a", List.of(), List.of())), null);
        assertThat(parse(builder.build(code, "t"))).doesNotContainKey("components");
    }

    @Test
    void emptySchemasOmitsComponents() {
        ApiModel code = model(List.of(get("/a", List.of(), List.of())), Map.of());
        assertThat(parse(builder.build(code, "t"))).doesNotContainKey("components");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaWithMixedRequiredEmitsRequiredListAndProperties() {
        SchemaModel thing = new SchemaModel("Thing", "object", List.of(
                new FieldModel("name", "string", null, true, ConstraintSet.empty(), null, SRC),
                new FieldModel("when", "string", "date-time", true, ConstraintSet.empty(), null, SRC),
                new FieldModel("note", "string", null, false, ConstraintSet.empty(), null, SRC),
                new FieldModel("owner", null, null, false, ConstraintSet.empty(), "Person", SRC)), null, SRC);
        ApiModel code = model(List.of(get("/a", List.of(), List.of())), Map.of("Thing", thing));

        Map<String, Object> root = parse(builder.build(code, "t"));
        Map<String, Object> components = (Map<String, Object>) root.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> th = (Map<String, Object>) schemas.get("Thing");

        assertThat(th).containsEntry("type", "object");
        assertThat((List<String>) th.get("required")).containsExactly("name", "when");
        Map<String, Object> props = (Map<String, Object>) th.get("properties");
        assertThat(props).containsOnlyKeys("name", "when", "note", "owner");
        // format propagated
        assertThat((Map<String, Object>) props.get("when")).containsEntry("format", "date-time");
        // refSchema field becomes a $ref
        assertThat((Map<String, Object>) props.get("owner"))
                .containsEntry("$ref", "#/components/schemas/Person");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaWithNoRequiredFieldsOmitsRequiredKey() {
        SchemaModel thing = new SchemaModel("Thing", "object", List.of(
                new FieldModel("note", "string", null, false, ConstraintSet.empty(), null, SRC)), null, SRC);
        ApiModel code = model(List.of(get("/a", List.of(), List.of())), Map.of("Thing", thing));
        Map<String, Object> th = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) parse(builder.build(code, "t")).get("components")).get("schemas")).get("Thing");
        assertThat(th).doesNotContainKey("required").containsKey("properties");
    }

    // ---- operation: operationId / params / responses default / security ---

    @Test
    @SuppressWarnings("unchecked")
    void nullOperationIdIsOmittedAndEmptyResponsesDefaultTo200() {
        Endpoint ep = new Endpoint(HttpMethod.GET, "/a", null, List.of(), null, List.of(), null, null, List.of(), SRC);
        Map<String, Object> op = operationFor(builder.build(model(List.of(ep), Map.of()), "t"), "/a", "get");

        assertThat(op).doesNotContainKey("operationId");
        assertThat(op).doesNotContainKey("parameters");
        Map<String, Object> responses = (Map<String, Object>) op.get("responses");
        assertThat(responses).containsOnlyKeys("200");
        assertThat((Map<String, Object>) responses.get("200")).containsEntry("description", "OK");
        assertThat(op).doesNotContainKey("security");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parametersEmittedWithTypeFormatAndLocationLowercased() {
        ParamModel id = param("id", ParamLocation.PATH, "integer", "int64");
        ParamModel q = param("q", ParamLocation.QUERY, null, null);  // null type → "string"
        Endpoint ep = new Endpoint(HttpMethod.GET, "/a/{id}", "getX", List.of(id, q), null,
                List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null, null, List.of(), SRC);

        Map<String, Object> op = operationFor(builder.build(model(List.of(ep), Map.of()), "t"), "/a/{id}", "get");
        assertThat(op).containsEntry("operationId", "getX");
        List<Object> params = (List<Object>) op.get("parameters");
        assertThat(params).hasSize(2);
        Map<String, Object> p0 = (Map<String, Object>) params.get(0);
        assertThat(p0).containsEntry("name", "id").containsEntry("in", "path").containsEntry("required", true);
        Map<String, Object> p0schema = (Map<String, Object>) p0.get("schema");
        assertThat(p0schema).containsEntry("type", "integer").containsEntry("format", "int64");
        Map<String, Object> p1 = (Map<String, Object>) params.get(1);
        assertThat(p1).containsEntry("in", "query");
        Map<String, Object> p1schema = (Map<String, Object>) p1.get("schema");
        assertThat(p1schema).containsEntry("type", "string").doesNotContainKey("format");  // null type+format
    }

    @Test
    @SuppressWarnings("unchecked")
    void securityRolesEmitBearerAuthRequirement() {
        Endpoint ep = new Endpoint(HttpMethod.GET, "/secure", "getX", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null, null, List.of("ROLE_ADMIN"), SRC);
        Map<String, Object> op = operationFor(builder.build(model(List.of(ep), Map.of()), "t"), "/secure", "get");
        List<Object> security = (List<Object>) op.get("security");
        assertThat(security).hasSize(1);
        assertThat((Map<String, Object>) security.get(0)).containsEntry("bearerAuth", List.of());
    }

    // ---- requestBody branches --------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void requestBodyWithSchemaRefEmittedAsJsonContent() {
        RequestBodyModel body = new RequestBodyModel("CreateReq", true, true, null, SRC);
        Endpoint ep = new Endpoint(HttpMethod.POST, "/things", "createX", List.of(), body,
                List.of(new ResponseModel(201, null, null, "RETURN", SRC)), null, null, List.of(), SRC);
        Map<String, Object> op = operationFor(builder.build(model(List.of(ep), Map.of()), "t"), "/things", "post");

        Map<String, Object> rb = (Map<String, Object>) op.get("requestBody");
        assertThat(rb).containsEntry("required", true);
        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) rb.get("content")).get("application/json")).get("schema");
        assertThat(schema).containsEntry("$ref", "#/components/schemas/CreateReq");
        // 201 description
        assertThat((Map<String, Object>) ((Map<String, Object>) op.get("responses")).get("201"))
                .containsEntry("description", "Created");
    }

    @Test
    void requestBodyWithNullSchemaRefIsOmitted() {
        RequestBodyModel body = new RequestBodyModel(null, true, true, null, SRC);
        Endpoint ep = new Endpoint(HttpMethod.POST, "/things", "createX", List.of(), body,
                List.of(new ResponseModel(202, null, null, "RETURN", SRC)), null, null, List.of(), SRC);
        Map<String, Object> op = operationFor(builder.build(model(List.of(ep), Map.of()), "t"), "/things", "post");
        assertThat(op).doesNotContainKey("requestBody");
    }

    // ---- response schemaRef: array, primitive, $ref ----------------------

    @Test
    @SuppressWarnings("unchecked")
    void responseSchemaRefArrayProducesArrayItemsRef() {
        Endpoint ep = get("/list", List.of(),
                List.of(new ResponseModel(200, "Thing[]", null, "RETURN", SRC)));
        Map<String, Object> resp = responseFor(builder.build(model(List.of(ep), Map.of()), "t"), "/list", "get", "200");
        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) resp.get("content")).get("application/json")).get("schema");
        assertThat(schema).containsEntry("type", "array");
        assertThat((Map<String, Object>) schema.get("items")).containsEntry("$ref", "#/components/schemas/Thing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void responseSchemaRefPrimitiveProducesInlineType() {
        Endpoint ep = get("/count", List.of(),
                List.of(new ResponseModel(200, "integer", null, "RETURN", SRC)));
        Map<String, Object> resp = responseFor(builder.build(model(List.of(ep), Map.of()), "t"), "/count", "get", "200");
        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) resp.get("content")).get("application/json")).get("schema");
        assertThat(schema).containsEntry("type", "integer").doesNotContainKey("$ref");
    }

    @Test
    @SuppressWarnings("unchecked")
    void responseSchemaRefObjectProducesComponentsRef() {
        Endpoint ep = get("/one", List.of(),
                List.of(new ResponseModel(200, "Thing", null, "RETURN", SRC)));
        Map<String, Object> resp = responseFor(builder.build(model(List.of(ep), Map.of()), "t"), "/one", "get", "200");
        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) resp.get("content")).get("application/json")).get("schema");
        assertThat(schema).containsEntry("$ref", "#/components/schemas/Thing");
    }

    // ---- descFor: every mapped status + the default ----------------------

    @Test
    @SuppressWarnings("unchecked")
    void everyStatusDescriptionBranchIsCovered() {
        int[] codes = {200, 201, 202, 204, 400, 401, 403, 404, 409, 422, 500, 418};
        List<ResponseModel> responses = new java.util.ArrayList<>();
        for (int c : codes) {
            responses.add(new ResponseModel(c, null, null, "RETURN", SRC));
        }
        Endpoint ep = get("/all", List.of(), responses);
        Map<String, Object> resp = (Map<String, Object>)
                operationFor(builder.build(model(List.of(ep), Map.of()), "t"), "/all", "get").get("responses");

        assertThat((Map<String, Object>) resp.get("200")).containsEntry("description", "OK");
        assertThat((Map<String, Object>) resp.get("201")).containsEntry("description", "Created");
        assertThat((Map<String, Object>) resp.get("202")).containsEntry("description", "Accepted");
        assertThat((Map<String, Object>) resp.get("204")).containsEntry("description", "No Content");
        assertThat((Map<String, Object>) resp.get("400")).containsEntry("description", "Bad Request");
        assertThat((Map<String, Object>) resp.get("401")).containsEntry("description", "Unauthorized");
        assertThat((Map<String, Object>) resp.get("403")).containsEntry("description", "Forbidden");
        assertThat((Map<String, Object>) resp.get("404")).containsEntry("description", "Not Found");
        assertThat((Map<String, Object>) resp.get("409")).containsEntry("description", "Conflict");
        assertThat((Map<String, Object>) resp.get("422")).containsEntry("description", "Unprocessable Entity");
        assertThat((Map<String, Object>) resp.get("500")).containsEntry("description", "Internal Server Error");
        assertThat((Map<String, Object>) resp.get("418")).containsEntry("description", "Response 418");
    }

    // ---- multiple methods on a single path (computeIfAbsent reuse) --------

    @Test
    @SuppressWarnings("unchecked")
    void twoMethodsShareOnePathItem() {
        Endpoint g = new Endpoint(HttpMethod.GET, "/things", "listX", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null, null, List.of(), SRC);
        Endpoint p = new Endpoint(HttpMethod.POST, "/things", "createX",
                List.of(), new RequestBodyModel("Req", true, true, null, SRC),
                List.of(new ResponseModel(201, null, null, "RETURN", SRC)), null, null, List.of(), SRC);

        Map<String, Object> paths = (Map<String, Object>) parse(builder.build(model(List.of(g, p), Map.of()), "t")).get("paths");
        Map<String, Object> item = (Map<String, Object>) paths.get("/things");
        assertThat(item).containsOnlyKeys("get", "post");
    }

    // ---- extension overlay: null / blank / unparseable short-circuit ------

    @Test
    void nullOriginalSpecLeavesNoExtensions() {
        ApiModel code = model(List.of(get("/a", List.of(), List.of())), Map.of());
        assertThat(parse(builder.build(code, "t", null)))
                .doesNotContainKey("x-root-ext");
    }

    @Test
    void blankOriginalSpecIsIgnored() {
        ApiModel code = model(List.of(get("/a", List.of(), List.of())), Map.of());
        Map<String, Object> root = parse(builder.build(code, "t", "   \n  "));
        assertThat(root.keySet()).noneMatch(k -> k.startsWith("x-"));
    }

    @Test
    void unparseableOriginalSpecIsNonFatalAndDropsExtensions() {
        ApiModel code = model(List.of(get("/a", List.of(), List.of())), Map.of());
        // ":::not yaml: [unbalanced" is not a parseable mapping
        Map<String, Object> root = parse(builder.build(code, "t", "::: : : [ } not valid yaml"));
        assertThat(root.keySet()).noneMatch(k -> k.startsWith("x-"));
        assertThat(root).containsKey("paths");  // build still succeeds
    }

    // ---- extension overlay: root / info / path / operation, code wins -----

    @Test
    @SuppressWarnings("unchecked")
    void extensionsOverlaidAtRootInfoPathAndOperationLevels() {
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things", "listX", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null, null, List.of(), SRC);
        ApiModel code = model(List.of(ep), Map.of());

        String original = """
                openapi: 3.0.0
                x-root-ext: rootVal
                info:
                  title: Old
                  x-info-ext: infoVal
                paths:
                  /things:
                    x-path-ext: pathVal
                    get:
                      x-op-ext: opVal
                """;

        Map<String, Object> root = parse(builder.build(code, "New", original));

        assertThat(root).containsEntry("x-root-ext", "rootVal");
        assertThat((Map<String, Object>) root.get("info")).containsEntry("x-info-ext", "infoVal");
        Map<String, Object> pathItem = (Map<String, Object>) ((Map<String, Object>) root.get("paths")).get("/things");
        assertThat(pathItem).containsEntry("x-path-ext", "pathVal");
        assertThat((Map<String, Object>) pathItem.get("get")).containsEntry("x-op-ext", "opVal");
    }

    @Test
    @SuppressWarnings("unchecked")
    void originalInfoWinsWholesaleAndOrphanPathsIgnored() {
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things", "listX", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null, null, List.of(), SRC);
        ApiModel code = model(List.of(ep), Map.of());

        // The original's info block replaces the placeholder wholesale (S13k-2): its real title/version/description
        // and any x- extensions carry through, so the corrected doc reflects the true API metadata, not "RealTitle".
        String original = """
                info:
                  title: ShouldOverride
                  version: 9.9.9
                  description: real-description
                  x-info-ext: kept
                paths:
                  /unknown-path:
                    x-orphan: dropped
                """;

        Map<String, Object> root = parse(builder.build(code, "RealTitle", original));
        Map<String, Object> info = (Map<String, Object>) root.get("info");
        assertThat(info).containsEntry("title", "ShouldOverride");   // original info wins
        assertThat(info).containsEntry("version", "9.9.9");          // original version, not placeholder 1.0.0
        assertThat(info).containsEntry("description", "real-description");   // full info block preserved
        assertThat(info).containsEntry("x-info-ext", "kept");        // x- extension carried through with the block
        // path in original but absent in corrected → skipped (no NPE), corrected path untouched
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        assertThat(paths).containsKey("/things").doesNotContainKey("/unknown-path");
    }

    @Test
    @SuppressWarnings("unchecked")
    void overlaySkipsNonMapPathItemsAndNonMapOperations() {
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things", "listX", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null, null, List.of(), SRC);
        ApiModel code = model(List.of(ep), Map.of());

        // /things path item is a scalar (not a map) → overlay loop must skip it without error.
        String original = """
                paths:
                  /things: just-a-string
                """;
        Map<String, Object> root = parse(builder.build(code, "t", original));
        // corrected /things remains the generated operation map, untouched
        Map<String, Object> things = (Map<String, Object>) ((Map<String, Object>) root.get("paths")).get("/things");
        assertThat(things).containsKey("get");
    }

    @Test
    @SuppressWarnings("unchecked")
    void overlaySkipsScalarOperationValueWithinMatchingPath() {
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things", "listX", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null, null, List.of(), SRC);
        ApiModel code = model(List.of(ep), Map.of());

        // operation value "summary" is a scalar, not a map → inner copyExt branch must be skipped.
        String original = """
                paths:
                  /things:
                    x-path-ext: kept
                    summary: a scalar op-level value
                    get:
                      x-op-ext: opKept
                """;
        Map<String, Object> root = parse(builder.build(code, "t", original));
        Map<String, Object> item = (Map<String, Object>) ((Map<String, Object>) root.get("paths")).get("/things");
        assertThat(item).containsEntry("x-path-ext", "kept");
        assertThat((Map<String, Object>) item.get("get")).containsEntry("x-op-ext", "opKept");
        assertThat(item).doesNotContainKey("summary");  // non-x- not copied
    }

    @Test
    void infoNotAMapInOriginalIsToleratedNoCrash() {
        ApiModel code = model(List.of(get("/a", List.of(), List.of())), Map.of());
        // info is a scalar in original → instanceof Map guard false → skip
        String original = "info: just-a-string\nx-root-ext: r\n";
        Map<String, Object> root = parse(builder.build(code, "t", original));
        assertThat(root).containsEntry("x-root-ext", "r");
        // info stays the generated map
        assertThat(root.get("info")).isInstanceOf(Map.class);
    }

    // ---- helpers ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> operationFor(String yamlStr, String path, String method) {
        Map<String, Object> paths = (Map<String, Object>) parse(yamlStr).get("paths");
        Map<String, Object> item = (Map<String, Object>) paths.get(path);
        return (Map<String, Object>) item.get(method);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseFor(String yamlStr, String path, String method, String status) {
        return (Map<String, Object>) ((Map<String, Object>) operationFor(yamlStr, path, method).get("responses")).get(status);
    }
}
