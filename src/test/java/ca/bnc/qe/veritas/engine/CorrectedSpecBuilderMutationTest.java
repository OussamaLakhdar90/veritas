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
 * Mutation-killing tests for {@link CorrectedSpecBuilder}. Each test parses the produced YAML back into a
 * nested {@code Map} and asserts EXACT concrete values so that every YAML-correction branch (per finding type)
 * and every {@code x-*} preservation step produces an observable, distinguishable result.
 *
 * <p>New file (does not touch the existing {@code CorrectedSpecBuilderTest}).
 */
class CorrectedSpecBuilderMutationTest {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final SourceRef SRC = SourceRef.code("X.java", 1, 1, "x");

    private final CorrectedSpecBuilder builder = new CorrectedSpecBuilder();

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String spec) {
        try {
            return YAML.readValue(spec, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("test could not parse produced YAML: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    private static Endpoint getEndpoint(HttpMethod method, String path, String opId, List<ParamModel> params,
                                        RequestBodyModel body, List<ResponseModel> responses, List<String> security) {
        return new Endpoint(method, path, opId, params, body, responses, null, null,
                security == null ? List.of() : security, SRC);
    }

    private ApiModel api(List<Endpoint> endpoints, Map<String, SchemaModel> schemas) {
        return new ApiModel("code", null, null, null, endpoints, schemas);
    }

    // ---- L37 / L39: openapi version literal + title null-coalescing ----------------------------------

    @Test
    void titleProvided_isUsedVerbatim_andOpenApiVersionIs303() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> root = parse(builder.build(api(List.of(ep), null), "My Title"));

        // L37: literal "3.0.3" must survive (EmptyObjectReturn / value mutators on build()).
        assertThat(root.get("openapi")).isEqualTo("3.0.3");
        // L39: title != null -> use the provided title, NOT the "Corrected API" fallback.
        assertThat(asMap(root.get("info")).get("title")).isEqualTo("My Title");
        assertThat(asMap(root.get("info")).get("version")).isEqualTo("1.0.0");
    }

    @Test
    void titleNull_fallsBackToCorrectedApi() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> root = parse(builder.build(api(List.of(ep), null), null));

        // L39: title == null -> the literal fallback. Distinguishes the ternary from its negation.
        assertThat(asMap(root.get("info")).get("title")).isEqualTo("Corrected API");
    }

    // ---- L109: operationId present vs absent --------------------------------------------------------

    @Test
    void operationIdPresent_isEmitted() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "getThing",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        assertThat(op).containsEntry("operationId", "getThing");
    }

    @Test
    void operationIdNull_isOmitted() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", null,
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        // L109: operationId == null -> key absent. Negation would emit a null operationId key.
        assertThat(op).doesNotContainKey("operationId");
    }

    // ---- L121: parameters present vs empty ---------------------------------------------------------

    @Test
    void parametersEmitted_withExactNameInLocationRequiredSchema() {
        ParamModel p = new ParamModel("id", ParamLocation.PATH, "integer", "int64", true, ConstraintSet.empty(), SRC);
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p/{id}", "op",
                List.of(p), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p/{id}")).get("get"));

        // L121: params non-empty -> the "parameters" key must be present.
        assertThat(op).containsKey("parameters");
        List<Object> params = asList(op.get("parameters"));
        assertThat(params).hasSize(1);
        Map<String, Object> pm = asMap(params.get(0));
        assertThat(pm).containsEntry("name", "id");
        assertThat(pm).containsEntry("in", "path");          // location lower-cased
        assertThat(pm).containsEntry("required", true);
        Map<String, Object> pschema = asMap(pm.get("schema"));
        assertThat(pschema).containsEntry("type", "integer"); // typeSchema: known type kept
        assertThat(pschema).containsEntry("format", "int64");  // L172: format != null -> emitted
    }

    @Test
    void noParameters_keyOmitted() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        // L121: params empty -> "parameters" key absent. Negation would emit an empty list.
        assertThat(op).doesNotContainKey("parameters");
    }

    // ---- L124: requestBody present vs absent -------------------------------------------------------

    @Test
    void requestBodyPresent_emitsRequiredAndJsonSchemaRef() {
        RequestBodyModel body = new RequestBodyModel("CreateReq", true, true, null, SRC);
        Endpoint ep = getEndpoint(HttpMethod.POST, "/p", "op",
                List.of(), body, List.of(new ResponseModel(201, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("post"));

        // L124: requestBody != null && schemaRef != null -> emit the requestBody node.
        assertThat(op).containsKey("requestBody");
        Map<String, Object> rb = asMap(op.get("requestBody"));
        assertThat(rb).containsEntry("required", true);
        Map<String, Object> content = asMap(rb.get("content"));
        Map<String, Object> json = asMap(content.get("application/json"));
        Map<String, Object> schema = asMap(json.get("schema"));
        assertThat(schema).containsEntry("$ref", "#/components/schemas/CreateReq");
    }

    @Test
    void noRequestBody_keyOmitted() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        // L124: requestBody null -> key absent.
        assertThat(op).doesNotContainKey("requestBody");
    }

    @Test
    void requestBodyWithNullSchemaRef_keyOmitted() {
        RequestBodyModel body = new RequestBodyModel(null, true, true, null, SRC);
        Endpoint ep = getEndpoint(HttpMethod.POST, "/p", "op",
                List.of(), body, List.of(new ResponseModel(201, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("post"));
        // L124: second clause schemaRef == null -> still omitted.
        assertThat(op).doesNotContainKey("requestBody");
    }

    // ---- L133: response schemaRef present vs absent ------------------------------------------------

    @Test
    void responseWithSchemaRef_emitsJsonContent_andCorrectDescription() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, "Thing", null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> responses = asMap(op.get("responses"));
        Map<String, Object> r200 = asMap(responses.get("200"));

        assertThat(r200).containsEntry("description", "OK"); // L196 descFor(200)
        // L133: schemaRef != null -> "content" present with the $ref.
        assertThat(r200).containsKey("content");
        Map<String, Object> schema = asMap(asMap(asMap(r200.get("content")).get("application/json")).get("schema"));
        assertThat(schema).containsEntry("$ref", "#/components/schemas/Thing");
    }

    @Test
    void responseWithoutSchemaRef_hasNoContent() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(204, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> r204 = asMap(asMap(op.get("responses")).get("204"));
        // L133: schemaRef null -> no "content" key.
        assertThat(r204).doesNotContainKey("content");
        assertThat(r204).containsEntry("description", "No Content"); // L196 descFor(204)
    }

    // ---- L138: responses empty -> default 200/OK ---------------------------------------------------

    @Test
    void noResponses_defaultsTo200Ok() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op", List.of(), null, List.of(), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> responses = asMap(op.get("responses"));
        // L138: responses empty -> inject the single default 200 OK.
        assertThat(responses).containsOnlyKeys("200");
        assertThat(asMap(responses.get("200"))).containsEntry("description", "OK");
    }

    @Test
    void withResponses_defaultNotInjected() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(404, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> responses = asMap(op.get("responses"));
        // L138: responses NOT empty -> no fabricated 200; only the real 404.
        assertThat(responses).containsOnlyKeys("404");
        assertThat(asMap(responses.get("404"))).containsEntry("description", "Not Found");
    }

    // ---- L142: security present vs absent ----------------------------------------------------------

    @Test
    void securityPresent_emitsBearerAuthRequirement() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), List.of("ROLE_ADMIN"));
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        // L142: security != null && !empty -> emit [{bearerAuth: []}].
        assertThat(op).containsKey("security");
        List<Object> sec = asList(op.get("security"));
        assertThat(sec).hasSize(1);
        Map<String, Object> req = asMap(sec.get(0));
        assertThat(req).containsKey("bearerAuth");
        assertThat(asList(req.get("bearerAuth"))).isEmpty();
    }

    @Test
    void securityEmpty_keyOmitted() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), List.of());
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        // L142: security empty -> no "security" key.
        assertThat(op).doesNotContainKey("security");
    }

    // ---- L145: operation() return value ------------------------------------------------------------
    // Covered: every operation()-based test above asserts a NON-empty op map (operationId/responses),
    // so EmptyObjectReturnVals on operation() (returning {}) is killed by, e.g., operationIdPresent_isEmitted.

    // ---- L148-163: schema() — required list + properties + return ----------------------------------

    @Test
    void schemaEmitsRequiredListAndProperties() {
        SchemaModel s = new SchemaModel("Thing", "object", List.of(
                new FieldModel("name", "string", null, true, ConstraintSet.empty(), null, SRC),
                new FieldModel("age", "integer", "int32", false, ConstraintSet.empty(), null, SRC)), null, SRC);
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, "Thing", null, "RETURN", SRC)), null);
        Map<String, Object> root = parse(builder.build(api(List.of(ep), Map.of("Thing", s)), "T"));
        Map<String, Object> schema = asMap(asMap(asMap(root.get("components")).get("schemas")).get("Thing"));

        assertThat(schema).containsEntry("type", "object");
        // L155: required field -> name in required; L159: required list non-empty -> "required" key present.
        assertThat(asList(schema.get("required"))).containsExactly("name");
        // L163: schema() returns the populated map (not {}). Properties must carry both fields.
        Map<String, Object> props = asMap(schema.get("properties"));
        assertThat(props).containsKeys("name", "age");
        assertThat(asMap(props.get("name"))).containsEntry("type", "string");
        Map<String, Object> age = asMap(props.get("age"));
        assertThat(age).containsEntry("type", "integer");
        assertThat(age).containsEntry("format", "int32"); // L172 format emitted
    }

    @Test
    void schemaWithNoRequiredFields_omitsRequiredKey() {
        SchemaModel s = new SchemaModel("Opt", "object", List.of(
                new FieldModel("note", "string", null, false, ConstraintSet.empty(), null, SRC)), null, SRC);
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, "Opt", null, "RETURN", SRC)), null);
        Map<String, Object> root = parse(builder.build(api(List.of(ep), Map.of("Opt", s)), "T"));
        Map<String, Object> schema = asMap(asMap(asMap(root.get("components")).get("schemas")).get("Opt"));
        // L155: field NOT required -> excluded; L159: required list empty -> "required" key absent.
        assertThat(schema).doesNotContainKey("required");
        assertThat(asMap(schema.get("properties"))).containsKey("note");
    }

    // ---- L166-175: typeSchema — refSchema path, unknown->string, format ----------------------------

    @Test
    void fieldWithRefSchema_producesRefNotInlineType() {
        SchemaModel s = new SchemaModel("Parent", "object", List.of(
                new FieldModel("child", "object", null, true, ConstraintSet.empty(), "Child", SRC)), null, SRC);
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, "Parent", null, "RETURN", SRC)), null);
        Map<String, Object> root = parse(builder.build(api(List.of(ep), Map.of("Parent", s)), "T"));
        Map<String, Object> props = asMap(asMap(asMap(asMap(root.get("components")).get("schemas")).get("Parent")).get("properties"));
        Map<String, Object> child = asMap(props.get("child"));
        // L167: refSchema != null -> delegate to refSchema(): a $ref, NOT {type: object/string}.
        assertThat(child).containsEntry("$ref", "#/components/schemas/Child");
        assertThat(child).doesNotContainKey("type");
    }

    @Test
    void unknownAndObjectTypesCollapseToString_butKnownTypesKept() {
        // Param with type "object" -> coerced to "string"; param with type null -> "string"; "boolean" -> kept.
        ParamModel objParam = new ParamModel("a", ParamLocation.QUERY, "object", null, false, ConstraintSet.empty(), SRC);
        ParamModel nullParam = new ParamModel("b", ParamLocation.QUERY, null, null, false, ConstraintSet.empty(), SRC);
        ParamModel boolParam = new ParamModel("c", ParamLocation.QUERY, "boolean", null, false, ConstraintSet.empty(), SRC);
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(objParam, nullParam, boolParam), null,
                List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        List<Object> params = asList(op.get("parameters"));

        // L171: type == null || "object".equals(type) ? "string" : type
        assertThat(asMap(asMap(params.get(0)).get("schema"))).containsEntry("type", "string"); // "object" -> string
        assertThat(asMap(asMap(params.get(1)).get("schema"))).containsEntry("type", "string"); // null   -> string
        assertThat(asMap(asMap(params.get(2)).get("schema"))).containsEntry("type", "boolean"); // boolean kept
    }

    @Test
    void noFormat_formatKeyOmitted() {
        ParamModel p = new ParamModel("a", ParamLocation.QUERY, "string", null, false, ConstraintSet.empty(), SRC);
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(p), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> schema = asMap(asMap(asList(op.get("parameters")).get(0)).get("schema"));
        // L172: format == null -> no "format" key.
        assertThat(schema).doesNotContainKey("format");
        assertThat(schema).containsOnlyKeys("type");
    }

    // ---- L178-193: refSchema — array, primitive, object-ref, isPrimitive switch --------------------

    @Test
    void arrayRef_producesArrayOfItemsRef() {
        // Response schemaRef "Thing[]" -> array with items $ref to Thing.
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, "Thing[]", null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> schema = asMap(asMap(asMap(asMap(op.get("responses")).get("200")).get("content")).get("application/json"));
        Map<String, Object> arr = asMap(schema.get("schema"));
        // L179: ref.endsWith("[]") -> array. L180: the array map (type+items) is returned, not {}.
        assertThat(arr).containsEntry("type", "array");
        Map<String, Object> items = asMap(arr.get("items"));
        assertThat(items).containsEntry("$ref", "#/components/schemas/Thing"); // "[]" stripped
        assertThat(arr).doesNotContainKey("$ref");
    }

    @Test
    void primitiveRef_producesInlineTypeNotObjectRef() {
        // schemaRef "string" is a primitive -> {type: string}, NOT a $ref.
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, "string", null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> schema = asMap(asMap(asMap(asMap(asMap(op.get("responses")).get("200")).get("content")).get("application/json")).get("schema"));
        // L182: isPrimitive("string") true -> {type: string}. L183: that map returned, not {}.
        assertThat(schema).containsEntry("type", "string");
        assertThat(schema).doesNotContainKey("$ref");
    }

    @Test
    void objectRef_producesComponentsRef() {
        // schemaRef "Thing" is NOT primitive and not an array -> $ref. Distinguishes isPrimitive() returning true.
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, "Thing", null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> schema = asMap(asMap(asMap(asMap(asMap(op.get("responses")).get("200")).get("content")).get("application/json")).get("schema"));
        // L182/L189: isPrimitive("Thing") must be FALSE -> $ref form. L185: that $ref map returned, not {}.
        assertThat(schema).containsEntry("$ref", "#/components/schemas/Thing");
        assertThat(schema).doesNotContainKey("type");
    }

    @Test
    void allPrimitiveNamesAreInlined_nonPrimitivesAreRefs() {
        // Drives isPrimitive(L188-193) true for each declared primitive and false for an object name.
        for (String prim : List.of("string", "integer", "number", "boolean", "array", "object")) {
            Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                    List.of(), null, List.of(new ResponseModel(200, prim, null, "RETURN", SRC)), null);
            Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
            Map<String, Object> schema = asMap(asMap(asMap(asMap(asMap(op.get("responses")).get("200")).get("content")).get("application/json")).get("schema"));
            assertThat(schema).as("primitive %s inlined", prim).containsEntry("type", prim);
            assertThat(schema).as("primitive %s not a $ref", prim).doesNotContainKey("$ref");
        }
        // Non-primitive name -> $ref (isPrimitive false).
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, "Widget", null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> schema = asMap(asMap(asMap(asMap(asMap(op.get("responses")).get("200")).get("content")).get("application/json")).get("schema"));
        assertThat(schema).containsEntry("$ref", "#/components/schemas/Widget");
    }

    // ---- L195-209: descFor — every status code maps to the exact phrase ----------------------------

    @Test
    void descriptionsAreExactPerStatusCode() {
        Map<Integer, String> expected = new java.util.LinkedHashMap<>();
        expected.put(200, "OK");
        expected.put(201, "Created");
        expected.put(202, "Accepted");
        expected.put(204, "No Content");
        expected.put(400, "Bad Request");
        expected.put(401, "Unauthorized");
        expected.put(403, "Forbidden");
        expected.put(404, "Not Found");
        expected.put(409, "Conflict");
        expected.put(422, "Unprocessable Entity");
        expected.put(500, "Internal Server Error");

        expected.forEach((code, desc) -> {
            Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                    List.of(), null, List.of(new ResponseModel(code, null, null, "RETURN", SRC)), null);
            Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(ep), null), "T")).get("paths")).get("/p")).get("get"));
            Map<String, Object> resp = asMap(asMap(op.get("responses")).get(String.valueOf(code)));
            assertThat(resp).as("status %d description", code).containsEntry("description", desc);
        });

        // default branch: "Response " + status (distinguishes the EmptyObjectReturn / default arm).
        Endpoint odd = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(418, null, null, "RETURN", SRC)), null);
        Map<String, Object> op = asMap(asMap(asMap(parse(builder.build(api(List.of(odd), null), "T")).get("paths")).get("/p")).get("get"));
        Map<String, Object> resp = asMap(asMap(op.get("responses")).get("418"));
        assertThat(resp).containsEntry("description", "Response 418");
    }

    // ---- L58 + L69-105: x-* extension overlay at every level ---------------------------------------

    private String specWithExtensions() {
        return """
                openapi: 3.0.0
                x-root-ext: rootVal
                plainRootKey: nope
                info:
                  title: Orig
                  x-info-ext: infoVal
                paths:
                  /p:
                    x-path-ext: pathVal
                    get:
                      x-op-ext: opVal
                      responses:
                        '200':
                          description: ok
                """;
    }

    @Test
    void extensionsOverlaidAtRootInfoPathAndOperation_codeStillWins() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> root = parse(builder.build(api(List.of(ep), null), "Code Title", specWithExtensions()));

        // L58/L78/L101: root-level x- copied; non-x key NOT copied.
        assertThat(root).containsEntry("x-root-ext", "rootVal");
        assertThat(root).doesNotContainKey("plainRootKey"); // L101 startsWith("x-") guard
        // L79/L80: info-level x- copied. Code wins on title (still "Code Title", not "Orig").
        Map<String, Object> info = asMap(root.get("info"));
        assertThat(info).containsEntry("x-info-ext", "infoVal");
        assertThat(info).containsEntry("title", "Code Title");
        // L82/L85/L89: path-item-level x- copied.
        Map<String, Object> pathItem = asMap(asMap(root.get("paths")).get("/p"));
        assertThat(pathItem).containsEntry("x-path-ext", "pathVal");
        // L91/L92: operation-level x- copied.
        Map<String, Object> op = asMap(pathItem.get("get"));
        assertThat(op).containsEntry("x-op-ext", "opVal");
    }

    @Test
    void noOriginalSpec_meansNoExtensionsAndNoCrash() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        // L58: blank originalSpecYaml -> overlay skipped entirely.
        Map<String, Object> root = parse(builder.build(api(List.of(ep), null), "T", "   "));
        assertThat(root).doesNotContainKey("x-root-ext");
        assertThat(root.keySet()).containsExactlyInAnyOrder("openapi", "info", "paths");
    }

    @Test
    void unparseableOriginalSpec_isNonFatal_noExtensions() {
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        // overlayExtensions catch-branch: garbage original -> code-only output, no crash.
        Map<String, Object> root = parse(builder.build(api(List.of(ep), null), "T", "\t: : not yaml ]["));
        assertThat(asMap(root.get("info"))).containsEntry("title", "T");
        assertThat(root).doesNotContainKey("x-root-ext");
    }

    @Test
    void extensionOnPathNotInCode_isIgnored() {
        // Original has a path that the code model does not declare -> rPaths.get(...) not a Map -> skipped (L85).
        String spec = """
                openapi: 3.0.0
                paths:
                  /other:
                    x-path-ext: shouldNotAppear
                    get:
                      responses:
                        '200':
                          description: ok
                """;
        Endpoint ep = getEndpoint(HttpMethod.GET, "/p", "op",
                List.of(), null, List.of(new ResponseModel(200, null, null, "RETURN", SRC)), null);
        Map<String, Object> root = parse(builder.build(api(List.of(ep), null), "T", spec));
        // "/other" path absent from code output; its extension must not leak anywhere.
        assertThat(asMap(root.get("paths"))).containsOnlyKeys("/p");
        assertThat(asMap(asMap(root.get("paths")).get("/p"))).doesNotContainKey("x-path-ext");
    }
}