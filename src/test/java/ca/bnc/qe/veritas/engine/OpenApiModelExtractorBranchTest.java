package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.engine.openapi.SpecPresence;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage companion to {@link OpenApiModelExtractorPresenceTest}: drives the uncovered
 * error/edge paths of {@link OpenApiModelExtractor#extract} and {@link OpenApiModelExtractor#presenceOf}
 * with inline OpenAPI 3 / Swagger 2.0 documents — version detection, $ref chains (param + schema, with
 * cycle and dangling guards), prose-enum lift, array/items handling in contentSchemaRef, parameter
 * locations, success-vs-error produces filtering, and the unparseable-content path.
 */
class OpenApiModelExtractorBranchTest {

    private final OpenApiModelExtractor extractor = new OpenApiModelExtractor();

    private Endpoint endpoint(SpecParse p, HttpMethod method, String path) {
        return p.model().endpoints().stream()
                .filter(e -> e.method() == method && e.pathTemplate().equals(path))
                .findFirst().orElseThrow(() -> new AssertionError("no endpoint " + method + " " + path));
    }

    private ParamModel param(Endpoint e, String name) {
        return e.params().stream().filter(pm -> name.equals(pm.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no param " + name));
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: top-level guards & version detection
    // ----------------------------------------------------------------------------------------------------

    @Test
    void unparseableContentReturnsNotParsedWithNullModel() {
        SpecParse p = extractor.extract("bad", "this is :: not : valid : yaml ::: at all\n\t- broken");
        // OpenAPIParser yields a null OpenAPI for junk → parsed=false, model=null, messages still a (non-null) list
        assertThat(p.parsed()).isFalse();
        assertThat(p.model()).isNull();
        assertThat(p.messages()).isNotNull();
    }

    @Test
    void emptyStringYieldsNoModel() {
        SpecParse p = extractor.extract("empty", "");
        assertThat(p.parsed()).isFalse();
        assertThat(p.model()).isNull();
    }

    @Test
    void detectsSwagger20VersionAndConvertsToEndpoints() {
        String swagger = """
                swagger: "2.0"
                info:
                  title: Legacy API
                  version: 9.9.9
                basePath: /
                paths:
                  /things/{id}:
                    get:
                      parameters:
                        - name: id
                          in: path
                          required: true
                          type: string
                      responses:
                        '200':
                          description: ok
                          schema:
                            $ref: '#/definitions/Thing'
                definitions:
                  Thing:
                    type: object
                    properties:
                      id:
                        type: string
                """;
        SpecParse p = extractor.extract("legacy", swagger);
        assertThat(p.parsed()).isTrue();
        // detectVersion: content contains "swagger:" + "2.0" → "2.0"
        assertThat(p.model().openApiVersion()).isEqualTo("2.0");
        assertThat(p.model().version()).isEqualTo("2.0");
        assertThat(p.model().title()).isEqualTo("Legacy API");
        Endpoint e = endpoint(p, HttpMethod.GET, "/things/{id}");
        // 2.0 schema:$ref → converted to a named response schema ref
        assertThat(e.responses().get(0).schemaRef()).isEqualTo("Thing");
        // Thing definition lifted into components/schemas
        assertThat(p.model().schemas()).containsKey("Thing");
    }

    @Test
    void detectsOpenApi3VersionFromDocument() {
        String oas = """
                openapi: 3.0.3
                info:
                  title: Modern API
                  version: 2.0.0
                paths: {}
                """;
        SpecParse p = extractor.extract("modern", oas);
        assertThat(p.parsed()).isTrue();
        // no "swagger:"/"2.0" tokens → falls through to openApi.getOpenapi()
        assertThat(p.model().openApiVersion()).isEqualTo("3.0.3");
        assertThat(p.model().endpoints()).isEmpty();
    }

    @Test
    void nullInfoAndNoPathsLeavesTitleNullAndEndpointsEmpty() {
        // valid-enough OAS with no info/paths exercises the openApi.getInfo()==null + getPaths()==null branches
        String oas = """
                openapi: 3.0.1
                paths: {}
                """;
        SpecParse p = extractor.extract("noinfo", oas);
        assertThat(p.parsed()).isTrue();
        assertThat(p.model().title()).isNull();
        assertThat(p.model().endpoints()).isEmpty();
        assertThat(p.model().schemas()).isEmpty();
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: parameter locations, required-by-location, prose enum
    // ----------------------------------------------------------------------------------------------------

    @Test
    void mapsEveryParameterLocationAndPathImpliesRequired() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /search/{seg}:
                    get:
                      parameters:
                        - name: seg
                          in: path
                          schema: { type: string }
                        - name: q
                          in: query
                          schema: { type: string }
                        - name: X-Trace
                          in: header
                          required: true
                          schema: { type: string }
                        - name: sid
                          in: cookie
                          schema: { type: string }
                      responses:
                        '204': { description: no content }
                """;
        SpecParse p = extractor.extract("locs", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/search/{seg}");
        assertThat(param(e, "seg").location()).isEqualTo(ParamLocation.PATH);
        // path param: required even though `required` omitted
        assertThat(param(e, "seg").required()).isTrue();
        assertThat(param(e, "q").location()).isEqualTo(ParamLocation.QUERY);
        assertThat(param(e, "q").required()).isFalse();
        assertThat(param(e, "X-Trace").location()).isEqualTo(ParamLocation.HEADER);
        assertThat(param(e, "X-Trace").required()).isTrue();
        assertThat(param(e, "sid").location()).isEqualTo(ParamLocation.COOKIE);
    }

    @Test
    void queryParameterTakesDefaultLocationArm() {
        // A plain query parameter exercises the switch's `default -> QUERY` arm (the only arm reached for
        // any non-path/header/cookie `in`, including the in==null coalesce).
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /x:
                    get:
                      parameters:
                        - name: floating
                          in: query
                          schema: { type: string }
                      responses:
                        '200': { description: ok }
                """;
        SpecParse p = extractor.extract("defaultarm", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/x");
        ParamModel floating = param(e, "floating");
        assertThat(floating.location()).isEqualTo(ParamLocation.QUERY);
        assertThat(floating.required()).isFalse();
        assertThat(floating.type()).isEqualTo("string");
    }

    @Test
    void liftsEnumFromDescriptionProseWhenNoMachineEnum() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /accounts:
                    get:
                      parameters:
                        - name: type
                          in: query
                          description: "Must be one of [INDIVIDUAL, NBC2, NBC4]"
                          schema: { type: string }
                      responses:
                        '200': { description: ok }
                """;
        SpecParse p = extractor.extract("prose", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/accounts");
        ParamModel type = param(e, "type");
        assertThat(type.constraints().enumValues()).containsExactly("INDIVIDUAL", "NBC2", "NBC4");
    }

    @Test
    void machineEnumWinsAndProseLiftIsSkipped() {
        // schema enum present → the `cs.enumValues() == null || isEmpty()` guard is false, prose path NOT taken
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /accounts:
                    get:
                      parameters:
                        - name: type
                          in: query
                          description: "Must be one of [PROSE_A, PROSE_B]"
                          schema:
                            type: string
                            enum: [REAL_X, REAL_Y]
                      responses:
                        '200': { description: ok }
                """;
        SpecParse p = extractor.extract("machineenum", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/accounts");
        assertThat(param(e, "type").constraints().enumValues()).containsExactly("REAL_X", "REAL_Y");
    }

    @Test
    void descriptionWithoutBracketedEnumLeavesEnumNull() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /accounts:
                    get:
                      parameters:
                        - name: q
                          in: query
                          description: "A free-form search term with no allowed-value list"
                          schema: { type: string }
                      responses:
                        '200': { description: ok }
                """;
        SpecParse p = extractor.extract("noprose", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/accounts");
        ConstraintSet cs = param(e, "q").constraints();
        assertThat(cs.enumValues()).isNull();
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: $ref chains for parameters and schemas (resolve, cycle, dangling)
    // ----------------------------------------------------------------------------------------------------

    @Test
    void resolvesComponentParameterRefIncludingItsSchemaEnum() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /accounts:
                    get:
                      parameters:
                        - $ref: '#/components/parameters/AccountType'
                      responses:
                        '200': { description: ok }
                components:
                  parameters:
                    AccountType:
                      name: accountType
                      in: query
                      required: true
                      schema:
                        $ref: '#/components/schemas/AccountTypeEnum'
                  schemas:
                    AccountTypeEnum:
                      type: string
                      enum: [CHEQUING, SAVINGS]
                """;
        SpecParse p = extractor.extract("refparam", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/accounts");
        ParamModel pm = param(e, "accountType");
        assertThat(pm.location()).isEqualTo(ParamLocation.QUERY);
        assertThat(pm.required()).isTrue();
        assertThat(pm.type()).isEqualTo("string");
        // schema $ref chain followed → enum visible
        assertThat(pm.constraints().enumValues()).containsExactly("CHEQUING", "SAVINGS");
    }

    @Test
    void followsTransitiveSchemaRefChainToReachEnum() {
        // schema:{$ref:A}, A:{$ref:B}, B:{enum:[…]} — multiple hops, cycle-guarded
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /accounts:
                    get:
                      parameters:
                        - name: kind
                          in: query
                          schema:
                            $ref: '#/components/schemas/KindA'
                      responses:
                        '200': { description: ok }
                components:
                  schemas:
                    KindA:
                      $ref: '#/components/schemas/KindB'
                    KindB:
                      type: string
                      enum: [ONE, TWO, THREE]
                """;
        SpecParse p = extractor.extract("transitive", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/accounts");
        ParamModel pm = param(e, "kind");
        assertThat(pm.type()).isEqualTo("string");
        assertThat(pm.constraints().enumValues()).containsExactly("ONE", "TWO", "THREE");
    }

    @Test
    void schemaRefCycleIsGuardedAndYieldsNoType() {
        // A → B → A cycle: the seen-set guard must terminate the loop without NPE/stack overflow.
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /loop:
                    get:
                      parameters:
                        - name: c
                          in: query
                          schema:
                            $ref: '#/components/schemas/Cyc1'
                      responses:
                        '200': { description: ok }
                components:
                  schemas:
                    Cyc1:
                      $ref: '#/components/schemas/Cyc2'
                    Cyc2:
                      $ref: '#/components/schemas/Cyc1'
                """;
        SpecParse p = extractor.extract("cycle", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/loop");
        ParamModel pm = param(e, "c");
        // never lands on a concrete type → type/format null, no enum
        assertThat(pm.type()).isNull();
        assertThat(pm.format()).isNull();
        assertThat(pm.constraints().enumValues()).isNull();
    }

    @Test
    void danglingSchemaRefBreaksChainAndLeavesTypeNull() {
        // schema $ref points at a non-existent component → resolvedSchema == null → break.
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /missing:
                    get:
                      parameters:
                        - name: g
                          in: query
                          schema:
                            $ref: '#/components/schemas/DoesNotExist'
                      responses:
                        '200': { description: ok }
                components:
                  schemas:
                    Other:
                      type: string
                """;
        SpecParse p = extractor.extract("dangling", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/missing");
        assertThat(param(e, "g").type()).isNull();
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: request body, consumes/produces, success-vs-error filtering
    // ----------------------------------------------------------------------------------------------------

    @Test
    void requestBodyConsumesAndProducesAndErrorContentTypeFiltering() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /widgets:
                    post:
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              $ref: '#/components/schemas/Widget'
                      responses:
                        '201':
                          description: created
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Widget'
                        '500':
                          description: oops
                          content:
                            application/problem+json:
                              schema:
                                $ref: '#/components/schemas/Problem'
                components:
                  schemas:
                    Widget:
                      type: object
                      properties:
                        id: { type: string }
                    Problem:
                      type: object
                      properties:
                        detail: { type: string }
                """;
        SpecParse p = extractor.extract("body", oas);
        Endpoint e = endpoint(p, HttpMethod.POST, "/widgets");
        // consumes = request-body content types
        assertThat(e.consumes()).containsExactly("application/json");
        assertThat(e.requestBody()).isNotNull();
        assertThat(e.requestBody().required()).isTrue();
        assertThat(e.requestBody().schemaRef()).isEqualTo("Widget");
        // produces reflects ONLY 2xx; the 500's application/problem+json must NOT leak in
        assertThat(e.produces()).containsExactly("application/json");
        // both responses captured, error status parsed
        assertThat(e.responses()).extracting(ResponseModel::statusCode)
                .containsExactlyInAnyOrder(201, 500);
        ResponseModel err = e.responses().stream().filter(r -> r.statusCode() == 500).findFirst().orElseThrow();
        assertThat(err.schemaRef()).isEqualTo("Problem");
        assertThat(err.origin()).isEqualTo("SPEC");
    }

    @Test
    void operationWithNoRequestBodyHasEmptyConsumesAndNullBody() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /ping:
                    get:
                      responses:
                        '200': { description: ok }
                """;
        SpecParse p = extractor.extract("noreqbody", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/ping");
        assertThat(e.requestBody()).isNull();
        assertThat(e.consumes()).isEmpty();
        // 204/200 with no content → produces empty, responses present
        assertThat(e.produces()).isEmpty();
        assertThat(e.responses()).hasSize(1);
    }

    @Test
    void nonNumericResponseKeyIsSkipped() {
        // 'default' response key → parseStatus returns null → continue (response dropped).
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /defaults:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: { type: string }
                        default:
                          description: fallback
                          content:
                            application/json:
                              schema: { type: string }
                """;
        SpecParse p = extractor.extract("defkey", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/defaults");
        assertThat(e.responses()).extracting(ResponseModel::statusCode).containsExactly(200);
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: contentSchemaRef array/items branches
    // ----------------------------------------------------------------------------------------------------

    @Test
    void arrayOfRefResponseYieldsBracketedSchemaRef() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /widgets:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: array
                                items:
                                  $ref: '#/components/schemas/Widget'
                components:
                  schemas:
                    Widget:
                      type: object
                      properties:
                        id: { type: string }
                """;
        SpecParse p = extractor.extract("arrref", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/widgets");
        assertThat(e.responses().get(0).schemaRef()).isEqualTo("Widget[]");
    }

    @Test
    void arrayOfPrimitivesYieldsNullSchemaRef() {
        // array whose items are an inline primitive (no $ref) → type "array" branch → null (not "array").
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /tags:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: array
                                items:
                                  type: string
                """;
        SpecParse p = extractor.extract("arrprim", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/tags");
        assertThat(e.responses().get(0).schemaRef()).isNull();
        assertThat(e.responses().get(0).mediaTypes()).containsExactly("application/json");
    }

    @Test
    void inlinePrimitiveResponseSchemaYieldsItsType() {
        // schema with no $ref, no items, not array → falls to sc.getType().
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /count:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            text/plain:
                              schema:
                                type: integer
                """;
        SpecParse p = extractor.extract("prim", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/count");
        assertThat(e.responses().get(0).schemaRef()).isEqualTo("integer");
    }

    @Test
    void responseWithoutContentHasNullSchemaRefAndNullMediaTypes() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /gone:
                    delete:
                      responses:
                        '204': { description: gone }
                """;
        SpecParse p = extractor.extract("nocontent", oas);
        Endpoint e = endpoint(p, HttpMethod.DELETE, "/gone");
        ResponseModel r = e.responses().get(0);
        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.schemaRef()).isNull();
        assertThat(r.mediaTypes()).isNull();
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: schema/component extraction (toSchema, fields, refs, enums)
    // ----------------------------------------------------------------------------------------------------

    @Test
    void componentSchemaFieldsCaptureTypesRequiredRefsAndConstraints() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths: {}
                components:
                  schemas:
                    Account:
                      type: object
                      required: [id]
                      properties:
                        id:
                          type: string
                          maxLength: 36
                          minLength: 1
                          pattern: '^[a-z]+$'
                        balance:
                          type: number
                          format: double
                          minimum: 0
                          maximum: 1000000
                        owner:
                          $ref: '#/components/schemas/Owner'
                    Owner:
                      type: object
                      properties:
                        name: { type: string }
                    Currency:
                      type: string
                      enum: [CAD, USD, EUR]
                """;
        SpecParse p = extractor.extract("schemas", oas);
        SchemaModel account = p.model().schemas().get("Account");
        assertThat(account).isNotNull();
        assertThat(account.type()).isEqualTo("object");

        FieldModel id = account.fields().stream().filter(f -> f.jsonName().equals("id")).findFirst().orElseThrow();
        assertThat(id.type()).isEqualTo("string");
        assertThat(id.required()).isTrue();
        assertThat(id.constraints().maxLength()).isEqualTo(36);
        assertThat(id.constraints().minLength()).isEqualTo(1);
        assertThat(id.constraints().pattern()).isEqualTo("^[a-z]+$");

        FieldModel balance = account.fields().stream().filter(f -> f.jsonName().equals("balance")).findFirst().orElseThrow();
        assertThat(balance.required()).isFalse();
        assertThat(balance.format()).isEqualTo("double");
        assertThat(balance.constraints().minimum()).isEqualTo(0.0);
        assertThat(balance.constraints().maximum()).isEqualTo(1000000.0);

        FieldModel owner = account.fields().stream().filter(f -> f.jsonName().equals("owner")).findFirst().orElseThrow();
        assertThat(owner.refSchema()).isEqualTo("Owner");

        // enum-typed component → enumValues on the SchemaModel itself
        SchemaModel currency = p.model().schemas().get("Currency");
        assertThat(currency.enumValues()).containsExactly("CAD", "USD", "EUR");
        // and no fields (no properties branch)
        assertThat(currency.fields()).isEmpty();
    }

    @Test
    void schemaWithoutPropertiesProducesNoFields() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths: {}
                components:
                  schemas:
                    Bare:
                      type: object
                """;
        SpecParse p = extractor.extract("bare", oas);
        SchemaModel bare = p.model().schemas().get("Bare");
        assertThat(bare.fields()).isEmpty();
        assertThat(bare.enumValues()).isNull();
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: multiple operations on one path + operation/global security
    // ----------------------------------------------------------------------------------------------------

    @Test
    void operationSecurityOverridesGlobalAndUnsecuredFallsBackToGlobal() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                security:
                  - globalAuth: []
                paths:
                  /secured:
                    get:
                      security:
                        - opAuth: []
                      responses:
                        '200': { description: ok }
                    delete:
                      responses:
                        '204': { description: gone }
                components:
                  securitySchemes:
                    globalAuth: { type: http, scheme: bearer }
                    opAuth: { type: http, scheme: bearer }
                """;
        SpecParse p = extractor.extract("sec", oas);
        Endpoint get = endpoint(p, HttpMethod.GET, "/secured");
        // operation security overrides global
        assertThat(get.security()).containsExactly("opAuth");
        Endpoint del = endpoint(p, HttpMethod.DELETE, "/secured");
        // no operation security → inherits global
        assertThat(del.security()).containsExactly("globalAuth");
    }

    @Test
    void noSecurityAnywhereYieldsEmptySecurityList() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /open:
                    get:
                      responses:
                        '200': { description: ok }
                """;
        SpecParse p = extractor.extract("opensec", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/open");
        assertThat(e.security()).isEmpty();
        assertThat(e.operationId()).isNull();
    }

    // ----------------------------------------------------------------------------------------------------
    // presenceOf: edge cases not exercised by the presence test fixtures
    // ----------------------------------------------------------------------------------------------------

    @Test
    void presenceOfUnparseableContentReturnsEmpty() {
        SpecPresence p = extractor.presenceOf("::: not a spec :::\n\t-broken:");
        assertThat(p).isEqualTo(SpecPresence.empty());
        assertThat(p.anyResponseHasExamples()).isFalse();
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isFalse();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }

    @Test
    void presenceOfDetectsMediaTypeExampleAndSchemaExample() {
        // mt.getExample() != null branch (inline single example on the media type)
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: { type: string }
                              example: "hello"
                components:
                  schemas:
                    S:
                      type: object
                      properties:
                        x: { type: string }
                """;
        SpecPresence p = extractor.presenceOf(oas);
        assertThat(p.anyResponseHasExamples()).isTrue();
        assertThat(p.anySchemaHasProperties()).isTrue();
        assertThat(p.anySchemaHasConstraints()).isFalse();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }

    @Test
    void presenceOfDetectsExamplesMapAndErrorResponse() {
        // mt.getExamples() non-empty branch + a 4xx error response
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /b:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: { type: string }
                              examples:
                                sample:
                                  value: "v"
                        '404': { description: not found }
                """;
        SpecPresence p = extractor.presenceOf(oas);
        assertThat(p.anyResponseHasExamples()).isTrue();
        assertThat(p.anyErrorResponseDeclared()).isTrue();
    }

    @Test
    void presenceOfDetectsSchemaExampleAndNestedPropertyConstraint() {
        // schema-level example (mt.getSchema().getExample()) + a constraint nested inside a property schema
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /c:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                example: { id: "1" }
                components:
                  schemas:
                    Wrapper:
                      type: object
                      properties:
                        code:
                          type: string
                          pattern: '^[0-9]+$'
                """;
        SpecPresence p = extractor.presenceOf(oas);
        assertThat(p.anyResponseHasExamples()).isTrue();
        // schemaHasConstraints recurses into properties → pattern on nested prop counts
        assertThat(p.anySchemaHasConstraints()).isTrue();
        assertThat(p.anySchemaHasProperties()).isTrue();
    }

    @Test
    void presenceOfWithNoComponentsAndNoResponsesIsAllFalse() {
        // getComponents()==null (no components block) and an operation whose responses are present-but-clean
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /d:
                    get:
                      responses:
                        '200': { description: ok }
                """;
        SpecPresence p = extractor.presenceOf(oas);
        assertThat(p.anyResponseHasExamples()).isFalse();
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isFalse();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }
}