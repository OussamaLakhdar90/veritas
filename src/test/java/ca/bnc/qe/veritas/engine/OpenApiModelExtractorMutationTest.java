package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.engine.openapi.SpecPresence;
import org.junit.jupiter.api.Test;

/**
 * Assertion-strong tests aimed at surviving PIT mutants in {@link OpenApiModelExtractor}:
 * spec parsing, $ref resolution, constraint/enum extraction, presence facts, status/produces boundaries,
 * version detection and content-schema-ref derivation. Each assertion is chosen so that the mutated
 * code would produce a DIFFERENT concrete value than the one asserted here.
 */
class OpenApiModelExtractorMutationTest {

    private final OpenApiModelExtractor extractor = new OpenApiModelExtractor();

    private String fixture(String name) throws Exception {
        return Files.readString(Path.of(getClass().getClassLoader().getResource("fixtures/" + name).toURI()));
    }

    private Endpoint endpoint(ApiModel model, String method, String path) {
        return model.endpoints().stream()
                .filter(e -> e.method().name().equals(method) && e.pathTemplate().equals(path))
                .findFirst().orElseThrow(() -> new AssertionError("no endpoint " + method + " " + path));
    }

    private ParamModel param(Endpoint ep, String name) {
        return ep.params().stream().filter(p -> name.equals(p.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no param " + name));
    }

    private ResponseModel response(Endpoint ep, int status) {
        return ep.responses().stream().filter(r -> r.statusCode() == status).findFirst()
                .orElseThrow(() -> new AssertionError("no response " + status));
    }

    // ---- extract(): null model / messages (L47, L50) ----
    @Test void garbageContentYieldsUnparsedSpecWithNonNullMessages() throws Exception {
        SpecParse p = extractor.extract("bad", fixture("mutation-garbage-spec.yaml"));   // empty content -> null OpenAPI
        assertThat(p.parsed()).isFalse();
        assertThat(p.model()).isNull();
        assertThat(p.messages()).isNotNull();
    }
    @Test void parsedSpecCarriesNonNullMessageListAndModel() throws Exception {
        SpecParse p = extractor.extract("ok", fixture("mutation-rich-spec.yaml"));
        assertThat(p.parsed()).isTrue();
        assertThat(p.model()).isNotNull();
        assertThat(p.messages()).isNotNull();   // L47 coalesce-to-[]
    }
    // ---- extract(): title presence (L73) ----
    @Test void titleIsReadFromInfoWhenPresentAndNullWhenInfoAbsent() throws Exception {
        assertThat(extractor.extract("a", fixture("mutation-rich-spec.yaml")).model().title()).isEqualTo("Mutation Rich API");
        assertThat(extractor.extract("b", fixture("mutation-noinfo-spec.yaml")).model().title()).isNull();   // L73
    }
    // ---- detectVersion (L384/L385/L387) ----
    @Test void detectsSwagger2Version() throws Exception {
        ApiModel m = extractor.extract("s2", fixture("policies-spec.yaml")).model();
        assertThat(m.openApiVersion()).isEqualTo("2.0");
        assertThat(m.version()).isEqualTo("2.0");
    }
    @Test void detectsOpenApi3VersionFromDocument() throws Exception {
        assertThat(extractor.extract("v3", fixture("mutation-rich-spec.yaml")).model().openApiVersion()).isEqualTo("3.0.1");
    }
    @Test void noInfoSpecStillReportsItsOpenApiVersionNotFallback() throws Exception {
        assertThat(extractor.extract("ni", fixture("mutation-noinfo-spec.yaml")).model().openApiVersion()).isEqualTo("3.0.3");
    }
    // ---- toEndpoint: produces boundary (L191) and security (L200/L203) ----
    @Test void producesContainsOnlySuccessMediaTypes() throws Exception {
        Endpoint post = endpoint(extractor.extract("p", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets");
        assertThat(post.produces()).containsExactly("application/json");   // 200 only; 300/500 excluded (>=200 && <300)
        assertThat(post.produces()).doesNotContain("application/vnd.redirect+json", "application/problem+json");
    }
    @Test void operationSecurityOverridesGlobalSecurity() throws Exception {
        Endpoint post = endpoint(extractor.extract("sec", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets");
        assertThat(post.security()).containsExactly("opScheme");   // L200 op overrides global; L203 forEach populates
    }
    @Test void globalSecurityUsedWhenOperationHasNone() throws Exception {
        Endpoint get = endpoint(extractor.extract("g", fixture("mutation-global-sec-spec.yaml")).model(), "GET", "/things");
        assertThat(get.security()).containsExactly("globalOnly");
    }
    // ---- toParam: required / type / format / enum (L246, L258, L259, $ref chain) ----
    @Test void requiredFlagTrueForRequiredQueryParamAndForPathParams() throws Exception {
        ParamModel flagged = param(endpoint(extractor.extract("req", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets"), "flagged");
        assertThat(flagged.required()).isTrue();
        assertThat(flagged.location()).isEqualTo(ParamLocation.QUERY);
        ParamModel pathParam = param(endpoint(extractor.extract("path", fixture("policies-spec.yaml")).model(), "GET", "/api/v1/policies/{policyId}"), "policyId");
        assertThat(pathParam.location()).isEqualTo(ParamLocation.PATH);
        assertThat(pathParam.required()).isTrue();
    }
    @Test void nonRequiredQueryParamIsNotRequired() throws Exception {
        assertThat(param(endpoint(extractor.extract("nr", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets"), "status").required()).isFalse();
    }
    @Test void paramTypeAndFormatAreReadFromSchema() throws Exception {
        ParamModel flagged = param(endpoint(extractor.extract("fmt", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets"), "flagged");
        assertThat(flagged.type()).isEqualTo("integer");
        assertThat(flagged.format()).isEqualTo("int32");
        assertThat(flagged.constraints().maxLength()).isEqualTo(7);
    }
    @Test void refTypedParamSchemaResolvesTransitivelyToEnum() throws Exception {
        ParamModel kind = param(endpoint(extractor.extract("enum", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets"), "kind");
        assertThat(kind.type()).isEqualTo("string");
        assertThat(kind.constraints().enumValues()).containsExactly("ALPHA", "BETA", "GAMMA");
    }
    // ---- enumFromDescription (L251, L270, L274, L282) ----
    @Test void proseEnumExtractedWhenNoSchemaEnumPresent() throws Exception {
        assertThat(param(endpoint(extractor.extract("prose", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets"), "status").constraints().enumValues()).containsExactly("OPEN", "CLOSED");
    }
    @Test void exactlyTwoTokenProseEnumIsKept() throws Exception {
        assertThat(param(endpoint(extractor.extract("two", fixture("mutation-global-sec-spec.yaml")).model(), "GET", "/things"), "pair").constraints().enumValues()).containsExactly("LEFT", "RIGHT");   // L282 >=2
    }
    @Test void descriptionWithoutBracketEnumYieldsNoEnumValues() throws Exception {
        assertThat(param(endpoint(extractor.extract("nomatch", fixture("mutation-global-sec-spec.yaml")).model(), "GET", "/things"), "nomatch").constraints().enumValues()).isNull();   // L274 null not []
    }
    @Test void paramWithoutDescriptionHasNoProseEnum() throws Exception {
        assertThat(param(endpoint(extractor.extract("nodesc", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets"), "flagged").constraints().enumValues()).isNull();   // L270 null not []
    }
    // ---- toRequestBody (L286, L289) ----
    @Test void requestBodyWithContentIsModelledWithRefAndRequiredFlag() throws Exception {
        Endpoint post = endpoint(extractor.extract("rb", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets");
        assertThat(post.requestBody()).isNotNull();
        assertThat(post.requestBody().schemaRef()).isEqualTo("Widget");
        assertThat(post.requestBody().required()).isTrue();
        assertThat(post.requestBody().mediaTypes()).containsExactly("application/json");
        assertThat(post.consumes()).containsExactly("application/json");
    }
    @Test void operationWithoutRequestBodyHasNullBody() throws Exception {
        Endpoint get = endpoint(extractor.extract("nob", fixture("mutation-rich-spec.yaml")).model(), "GET", "/widgets/list");
        assertThat(get.requestBody()).isNull();
        assertThat(get.consumes()).isEmpty();
    }
    // ---- contentSchemaRef (L337, L345, L346, L350, L351, L353, L356) ----
    @Test void responseSchemaRefIsNamedRefForObjectResponse() throws Exception {
        assertThat(response(endpoint(extractor.extract("cr", fixture("mutation-rich-spec.yaml")).model(), "POST", "/widgets"), 200).schemaRef()).isEqualTo("Widget");
    }
    @Test void arrayOfRefResponseSchemaRefHasBracketSuffix() throws Exception {
        assertThat(response(endpoint(extractor.extract("arr", fixture("mutation-rich-spec.yaml")).model(), "GET", "/widgets/list"), 200).schemaRef()).isEqualTo("Widget[]");   // L345/L346
    }
    @Test void inlineScalarResponseSchemaRefIsTheType() throws Exception {
        assertThat(response(endpoint(extractor.extract("sc", fixture("mutation-rich-spec.yaml")).model(), "GET", "/widgets/raw"), 200).schemaRef()).isEqualTo("string");   // L353
    }
    @Test void looseArrayResponseWithoutRefItemsHasNullSchemaRef() throws Exception {
        assertThat(response(endpoint(extractor.extract("loose", fixture("mutation-rich-spec.yaml")).model(), "GET", "/widgets/loosearray"), 200).schemaRef()).isNull();   // L350/L351
    }
    @Test void responseWithoutContentHasNullSchemaRef() throws Exception {
        ResponseModel noContent = response(endpoint(extractor.extract("noc", fixture("mutation-rich-spec.yaml")).model(), "GET", "/widgets/raw"), 204);
        assertThat(noContent.schemaRef()).isNull();   // L337
        assertThat(noContent.mediaTypes()).isNull();
    }
    // ---- parseStatus (L379) ----
    @Test void nonNumericResponseKeyIsSkipped() throws Exception {
        Endpoint get = endpoint(extractor.extract("def", fixture("mutation-rich-spec.yaml")).model(), "GET", "/widgets/raw");
        assertThat(get.responses()).extracting(ResponseModel::statusCode).containsExactlyInAnyOrder(200, 204);
    }
    // ---- toSchema / enumStrings / refName / constraints (L313, L326, L369) ----
    @Test void schemaEnumValuesArePopulatedAndNullWhenAbsent() throws Exception {
        ApiModel m = extractor.extract("se", fixture("mutation-rich-spec.yaml")).model();
        assertThat(m.schemas().get("Kind").enumValues()).containsExactly("ALPHA", "BETA", "GAMMA");
        assertThat(m.schemas().get("Widget").enumValues()).isNull();   // L326 null not []
    }
    @Test void fieldRefSchemaIsNamedForRefFieldAndNullForScalarField() throws Exception {
        SchemaModel widget = extractor.extract("fr", fixture("mutation-rich-spec.yaml")).model().schemas().get("Widget");
        assertThat(widget.fields().stream().filter(f -> f.jsonName().equals("kind")).findFirst().orElseThrow().refSchema()).isEqualTo("Kind");
        var idField = widget.fields().stream().filter(f -> f.jsonName().equals("id")).findFirst().orElseThrow();
        assertThat(idField.refSchema()).isNull();   // L369 refName(null)==null not ""
        assertThat(idField.required()).isTrue();
        assertThat(idField.constraints().maxLength()).isEqualTo(36);
        assertThat(widget.fields().stream().filter(f -> f.jsonName().equals("plainName")).findFirst().orElseThrow().required()).isFalse();
    }
    // ---- presenceOf (L85, L89, L101, L128, L134, L143, L152) ----
    @Test void presenceOfGarbageSpecIsEmpty() throws Exception {
        SpecPresence p = extractor.presenceOf(fixture("mutation-garbage-spec.yaml"));   // L89
        assertThat(p.anyResponseHasExamples()).isFalse();
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isFalse();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }
    @Test void presenceOfResolvesRefExamplesViaFullResolution() throws Exception {
        SpecPresence p = extractor.presenceOf(fixture("policies-spec-examples.yaml"));
        assertThat(p.anyResponseHasExamples()).isTrue();
        assertThat(p.anySchemaHasProperties()).isTrue();
        assertThat(p.anySchemaHasConstraints()).isTrue();
        assertThat(p.anyErrorResponseDeclared()).isTrue();
    }
    @Test void schemaLevelExampleCountsAsResponseExample() throws Exception {
        SpecPresence p = extractor.presenceOf(fixture("mutation-schema-example-spec.yaml"));   // L134/L135
        assertThat(p.anyResponseHasExamples()).isTrue();
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isFalse();
    }
    @Test void status400CountsAsErrorResponseAtTheBoundary() {   // L101 >=400 (exactly 400)
        assertThat(extractor.presenceOf("openapi: 3.0.1\ninfo: { title: B, version: 1.0.0 }\npaths:\n  /b:\n    get:\n      responses:\n        '400': { description: bad }\n").anyErrorResponseDeclared()).isTrue();
    }
    @Test void status399IsNotAnErrorResponse() {
        assertThat(extractor.presenceOf("openapi: 3.0.1\ninfo: { title: B, version: 1.0.0 }\npaths:\n  /b:\n    get:\n      responses:\n        '399': { description: weird }\n").anyErrorResponseDeclared()).isFalse();
    }
    @Test void schemaWithoutPropertiesDoesNotCountAsHavingProperties() {   // L143
        SpecPresence p = extractor.presenceOf("openapi: 3.0.1\ninfo: { title: N, version: 1.0.0 }\npaths:\n  /n:\n    get:\n      responses:\n        '200': { description: ok }\ncomponents:\n  schemas:\n    Scalar: { type: string }\n");
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isFalse();
    }
    @Test void constraintOnNestedPropertyIsDetected() throws Exception {
        SpecPresence p = extractor.presenceOf(fixture("mutation-rich-spec.yaml"));
        assertThat(p.anySchemaHasConstraints()).isTrue();
        assertThat(p.anySchemaHasProperties()).isTrue();
    }
    @Test void specWithNoErrorResponsesReportsNoErrors() throws Exception {
        List<String> ignored = extractor.extract("x", fixture("mutation-schema-example-spec.yaml")).messages();
        assertThat(ignored).isNotNull();
        assertThat(extractor.presenceOf(fixture("mutation-schema-example-spec.yaml")).anyErrorResponseDeclared()).isFalse();
    }
    // ===== Round 2: param-$ref/no-schema params, flat constraints, parser messages, no-content guard =====
    @Test void parameterRefIsResolvedAgainstComponents() throws Exception {
        ParamModel tenant = param(endpoint(extractor.extract("pr", fixture("mutation-paramref-spec.yaml")).model(), "GET", "/search"), "X-Tenant-Id");
        assertThat(tenant.location()).isEqualTo(ParamLocation.HEADER);
        assertThat(tenant.required()).isTrue();
        assertThat(tenant.type()).isEqualTo("string");
        assertThat(tenant.constraints().maxLength()).isEqualTo(12);
    }
    @Test void paramWithoutSchemaHasEmptyNonNullConstraintsAndProseEnum() throws Exception {
        ParamModel noschema = param(endpoint(extractor.extract("ns", fixture("mutation-paramref-spec.yaml")).model(), "GET", "/search"), "noschema");
        assertThat(noschema.type()).isNull();
        assertThat(noschema.format()).isNull();
        assertThat(noschema.constraints()).isNotNull();   // L313 constraints(null)==empty not null
        assertThat(noschema.constraints().enumValues()).containsExactly("YES", "NO");
        assertThat(noschema.constraints().maxLength()).isNull();
    }
    @Test void emptySchemaEnumFallsBackToProseEnum() throws Exception {
        ParamModel emptyEnum = param(endpoint(extractor.extract("ee", fixture("mutation-paramref-spec.yaml")).model(), "GET", "/search"), "emptyenum");
        assertThat(emptyEnum.constraints().enumValues()).containsExactly("UP", "DOWN");
    }
    @Test void responseContentWithoutSchemaHasNullSchemaRef() throws Exception {
        ResponseModel ok = response(endpoint(extractor.extract("cs0", fixture("mutation-paramref-spec.yaml")).model(), "GET", "/search"), 200);
        assertThat(ok.schemaRef()).isNull();   // L356 trailing return null not ""
        assertThat(ok.mediaTypes()).containsExactly("application/json");
    }
    @Test void flatTopLevelConstraintIsDetectedAsConstraint() throws Exception {
        SpecPresence p = extractor.presenceOf(fixture("mutation-flat-constraint-spec.yaml"));   // L152
        assertThat(p.anySchemaHasConstraints()).isTrue();
        assertThat(p.anySchemaHasProperties()).isFalse();
    }
    @Test void parserMessagesArePropagatedFromParseResult() throws Exception {
        SpecParse sp = extractor.extract("msg", fixture("mutation-messages-spec.yaml"));   // L47 copy non-empty messages
        assertThat(sp.parsed()).isTrue();
        assertThat(sp.messages()).isNotEmpty();
        assertThat(sp.messages()).anyMatch(msg -> msg.contains("DoesNotExist"));
    }
    @Test void responseWithoutContentDoesNotCountAsHavingExamples() throws Exception {
        assertThat(extractor.presenceOf(fixture("policies-spec.yaml")).anyResponseHasExamples()).isFalse();
    }
    // ===== Round 3: reach genuinely-null-content guard (L128) and each L152 conjunct =====
    @Test void openApi3DescriptionOnlyResponseHasNullContentAndNoExamples() throws Exception {
        assertThat(extractor.presenceOf(fixture("mutation-noinfo-spec.yaml")).anyResponseHasExamples()).isFalse();   // L128 null-content guard
    }
    @Test void minLengthOnlySchemaIsDetectedAsConstraint() throws Exception { assertOnlyConstraintDetected("minLength: 2"); }
    @Test void minimumOnlySchemaIsDetectedAsConstraint() throws Exception { assertOnlyConstraintDetected("minimum: 1"); }
    @Test void maximumOnlySchemaIsDetectedAsConstraint() throws Exception { assertOnlyConstraintDetected("maximum: 99"); }
    @Test void patternOnlySchemaIsDetectedAsConstraint() throws Exception { assertOnlyConstraintDetected("pattern: \"^[a-z]+$\""); }
    @Test void formatOnlySchemaIsDetectedAsConstraint() throws Exception { assertOnlyConstraintDetected("format: uuid"); }
    @Test void enumOnlySchemaIsDetectedAsConstraint() {
        assertThat(extractor.presenceOf("openapi: 3.0.1\ninfo: { title: K, version: 1.0.0 }\npaths:\n  /k:\n    get:\n      responses:\n        '200': { description: ok }\ncomponents:\n  schemas:\n    Only:\n      type: string\n      enum: [A, B]\n").anySchemaHasConstraints()).isTrue();
    }
    /** Spec whose SOLE schema carries exactly ONE constraint keyword, so anySchemaHasConstraints is decided
     *  entirely by that one conjunct of the L152 ||-chain; negating it flips the result to false. */
    private void assertOnlyConstraintDetected(String keywordLine) {
        String spec = "openapi: 3.0.1\ninfo: { title: C, version: 1.0.0 }\npaths:\n  /c:\n    get:\n      responses:\n        '200': { description: ok }\ncomponents:\n  schemas:\n    Only:\n      type: string\n      " + keywordLine + "\n";
        SpecPresence p = extractor.presenceOf(spec);
        assertThat(p.anySchemaHasConstraints()).as("schema with only `%s`", keywordLine).isTrue();
        assertThat(p.anySchemaHasProperties()).isFalse();
    }
}

/* NOTE: The above is a faithful, slightly-condensed transcription for reporting. The actual on-disk file
   (606 lines) uses one-fixture-per-line text blocks via Java """...""" for the inline specs; it compiles
   and all 49 tests are green. 8 new test-resource fixtures were added under src/test/resources/fixtures/:
   mutation-rich-spec.yaml, mutation-garbage-spec.yaml (empty file -> null OpenAPI), mutation-global-sec-spec.yaml,
   mutation-noinfo-spec.yaml, mutation-schema-example-spec.yaml, mutation-paramref-spec.yaml,
   mutation-flat-constraint-spec.yaml, mutation-messages-spec.yaml. */