package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.engine.openapi.SpecPresence;
import org.junit.jupiter.api.Test;

/**
 * Second branch-coverage companion to {@link OpenApiModelExtractorBranchTest}, targeting the red/yellow
 * (uncovered / partially-covered) arms its sibling does not reach: the {@code paths==null} guards in both
 * {@link OpenApiModelExtractor#extract} and {@link OpenApiModelExtractor#presenceOf}; every individual
 * constraint keyword in {@code schemaHasConstraints} (minLength / maxLength / minimum / maximum / pattern /
 * format / enum); {@code schemaHasProperties} with an empty/missing properties map; {@code presenceOf} edge
 * cases (operation with no responses, non-numeric status key, components-without-schemas, response media
 * type with no content / no schema); a parameter with NO schema node at all (the {@code schema==null} arms
 * and the {@code in==null} switch coalesce); a blank parameter description; and the {@code getOpenapi()==null}
 * arm of version detection. New cases only — no overlap with the existing two test classes.
 */
class OpenApiModelExtractorBranch2Test {

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
    // extract: openApi.getPaths() == null  (no `paths` key at all)  + getOpenapi() default arm
    // ----------------------------------------------------------------------------------------------------

    @Test
    void extractWithNoPathsKeyLeavesEndpointsEmptyButKeepsSchemas() {
        // No top-level `paths:` -> openApi.getPaths() == null (L55 false arm). Components still extracted.
        String oas = """
                openapi: 3.0.1
                info: { title: NoPaths, version: 1 }
                components:
                  schemas:
                    Solo:
                      type: object
                      properties:
                        id: { type: string }
                """;
        SpecParse p = extractor.extract("nopaths", oas);
        assertThat(p.parsed()).isTrue();
        assertThat(p.model().endpoints()).isEmpty();
        assertThat(p.model().title()).isEqualTo("NoPaths");
        // schemas branch still runs despite null paths
        assertThat(p.model().schemas()).containsKey("Solo");
        assertThat(p.model().schemas().get("Solo").fields()).extracting(f -> f.jsonName()).containsExactly("id");
    }

    @Test
    void componentsBlockWithoutSchemasMapLeavesSchemasEmpty() {
        // components present but getSchemas() == null -> L67 right-hand arm false; schemas map stays empty.
        String oas = """
                openapi: 3.0.2
                info: { title: OnlySchemes, version: 1 }
                paths:
                  /p:
                    get:
                      responses:
                        '200': { description: ok }
                components:
                  securitySchemes:
                    bearerAuth: { type: http, scheme: bearer }
                """;
        SpecParse p = extractor.extract("onlyschemes", oas);
        assertThat(p.parsed()).isTrue();
        assertThat(p.model().schemas()).isEmpty();
        // getOpenapi() non-null here ("3.0.2"); detectVersion falls through to it (no swagger:/2.0 tokens)
        assertThat(p.model().openApiVersion()).isEqualTo("3.0.2");
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: parameter with NO schema node  -> schema==null arms (L258/L259) + in==null coalesce (L227)
    // ----------------------------------------------------------------------------------------------------

    @Test
    void parameterWithoutSchemaNodeYieldsNullTypeAndFormat() {
        // A query parameter declared with no `schema:` -> p.getSchema() == null, so the schema-ref while-loop
        // body never runs and type/format both resolve via the schema==null arm to null.
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /noschema:
                    get:
                      parameters:
                        - name: bare
                          in: query
                      responses:
                        '200': { description: ok }
                """;
        SpecParse p = extractor.extract("noschema", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/noschema");
        ParamModel bare = param(e, "bare");
        assertThat(bare.location()).isEqualTo(ParamLocation.QUERY);
        assertThat(bare.type()).isNull();
        assertThat(bare.format()).isNull();
        assertThat(bare.required()).isFalse();
        assertThat(bare.constraints().isEmpty()).isTrue();
    }

    @Test
    void parameterWithBlankDescriptionSkipsProseEnumLift() {
        // description present but blank -> enumFromDescription's `description.isBlank()` arm -> returns null,
        // so no prose enum is lifted and enumValues stays null.
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /blankdesc:
                    get:
                      parameters:
                        - name: q
                          in: query
                          description: "   "
                          schema: { type: string }
                      responses:
                        '200': { description: ok }
                """;
        SpecParse p = extractor.extract("blankdesc", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/blankdesc");
        assertThat(param(e, "q").constraints().enumValues()).isNull();
        assertThat(param(e, "q").type()).isEqualTo("string");
    }

    // ----------------------------------------------------------------------------------------------------
    // presenceOf: openApi.getPaths() == null  (no paths) + components without schemas
    // ----------------------------------------------------------------------------------------------------

    @Test
    void presenceOfWithNoPathsAndNoComponentsIsAllFalse() {
        // No `paths:` -> getPaths()==null arm (L93 false); no components -> L113 false. Everything stays false.
        String oas = """
                openapi: 3.0.1
                info: { title: Empty, version: 1 }
                """;
        SpecPresence p = extractor.presenceOf(oas);
        assertThat(p.anyResponseHasExamples()).isFalse();
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isFalse();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }

    @Test
    void presenceOfComponentsWithoutSchemasMapStaysPropsAndConstraintsFalse() {
        // components block present but no `schemas:` -> getSchemas()==null arm (L113 right side false):
        // the schema loop is skipped so props/constraints stay false even though a (clean) response exists.
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /p:
                    get:
                      responses:
                        '200': { description: ok }
                components:
                  securitySchemes:
                    bearerAuth: { type: http, scheme: bearer }
                """;
        SpecPresence p = extractor.presenceOf(oas);
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isFalse();
        assertThat(p.anyResponseHasExamples()).isFalse();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }

    @Test
    void presenceOfNonNumericStatusKeyTreatedAsNonErrorAndCleanSchema() {
        // `default` response key -> parseStatus null -> `status != null` short-circuits (L101 false arm),
        // so it is NOT counted as an error. The component schema has properties but no constraints, exercising
        // schemaHasProperties true + schemaHasConstraints false.
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /d:
                    get:
                      responses:
                        '200':
                          description: ok
                        default:
                          description: fallback
                components:
                  schemas:
                    Plain:
                      type: object
                      properties:
                        name: { type: string }
                """;
        SpecPresence p = extractor.presenceOf(oas);
        // no numeric >=400 status anywhere
        assertThat(p.anyErrorResponseDeclared()).isFalse();
        assertThat(p.anySchemaHasProperties()).isTrue();
        // a plain string property with no keywords -> no constraints
        assertThat(p.anySchemaHasConstraints()).isFalse();
        assertThat(p.anyResponseHasExamples()).isFalse();
    }

    @Test
    void presenceOfResponseWithoutContentHasNoExamples() {
        // 200 response with NO content block -> responseHasExamples sees resp.getContent()==null (L127 right arm)
        // -> returns false; with a bare object component (no properties) schemaHasProperties false arm (L115).
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /nc:
                    get:
                      responses:
                        '200': { description: no content here }
                components:
                  schemas:
                    Bare:
                      type: object
                """;
        SpecPresence p = extractor.presenceOf(oas);
        assertThat(p.anyResponseHasExamples()).isFalse();
        // bare object -> no properties, no constraints
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isFalse();
    }

    @Test
    void presenceOfResponseContentWithoutExampleOrSchemaExampleHasNoExamples() {
        // media type present with a $ref schema (so getExample()==null, getExamples()==null, and the resolved
        // schema carries no example) -> both example-checking branches in responseHasExamples stay false, but the
        // for-loop body still runs (content present). Drives the "no example" exit (L138 return false).
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /noex:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Plain'
                components:
                  schemas:
                    Plain:
                      type: object
                      properties:
                        name: { type: string }
                """;
        SpecPresence p = extractor.presenceOf(oas);
        assertThat(p.anyResponseHasExamples()).isFalse();
        assertThat(p.anySchemaHasProperties()).isTrue();
    }

    // ----------------------------------------------------------------------------------------------------
    // presenceOf: schemaHasConstraints — each individual keyword arm (minLength | maxLength | minimum |
    // maximum | format | enum) placed DIRECTLY on a top-level component schema so the corresponding clause of
    // the first `if` short-circuits true on its own. The sibling test only covered `pattern` (and only via the
    // nested-property recursion), so the other keyword arms are red/yellow.
    // ----------------------------------------------------------------------------------------------------

    @Test
    void presenceOfDetectsMinLengthOnTopLevelSchema() {
        assertSoleConstraintDetected("""
                    C:
                      type: string
                      minLength: 1
                """);
    }

    @Test
    void presenceOfDetectsMaxLengthOnTopLevelSchema() {
        assertSoleConstraintDetected("""
                    C:
                      type: string
                      maxLength: 8
                """);
    }

    @Test
    void presenceOfDetectsMinimumOnTopLevelSchema() {
        assertSoleConstraintDetected("""
                    C:
                      type: integer
                      minimum: 0
                """);
    }

    @Test
    void presenceOfDetectsMaximumOnTopLevelSchema() {
        assertSoleConstraintDetected("""
                    C:
                      type: integer
                      maximum: 100
                """);
    }

    @Test
    void presenceOfDetectsFormatOnTopLevelSchema() {
        assertSoleConstraintDetected("""
                    C:
                      type: string
                      format: email
                """);
    }

    @Test
    void presenceOfDetectsEnumOnTopLevelSchema() {
        // enum clause (L152 enum arm) on a top-level schema; short-circuits before the properties recursion.
        assertSoleConstraintDetected("""
                    C:
                      type: string
                      enum: [A, B, C]
                """);
    }

    /**
     * Assert that a single component schema carrying exactly one constraint keyword (and NO properties) is
     * detected as having a constraint. The schema text is fully-formed and correctly indented to slot under
     * {@code components.schemas}; because it has no {@code properties} map, the recursion arm is skipped and
     * {@code schemaHasProperties} stays false — isolating the keyword clause under test.
     */
    private void assertSoleConstraintDetected(String schemaYaml) {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /p:
                    get:
                      responses:
                        '200': { description: ok }
                components:
                  schemas:
                """ + schemaYaml;
        SpecPresence p = extractor.presenceOf(oas);
        assertThat(p.anySchemaHasConstraints()).isTrue();
        // a constraint-only schema declares no `properties` map -> schemaHasProperties false arm
        assertThat(p.anySchemaHasProperties()).isFalse();
        // none of these fixtures declares an example or a >=400 status
        assertThat(p.anyResponseHasExamples()).isFalse();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: operation with NO responses block at all  -> op.getResponses() == null (L182 false arm)
    // ----------------------------------------------------------------------------------------------------

    @Test
    void operationWithoutResponsesBlockHasEmptyResponsesAndProduces() {
        // An operation with no `responses:` key -> op.getResponses()==null (L182 false), so the response loop is
        // skipped entirely: responses empty, produces empty, body still resolved.
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /noresp:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: { type: object }
                """;
        SpecParse p = extractor.extract("noresp", oas);
        Endpoint e = endpoint(p, HttpMethod.POST, "/noresp");
        assertThat(e.responses()).isEmpty();
        assertThat(e.produces()).isEmpty();
        assertThat(e.consumes()).containsExactly("application/json");
        assertThat(e.requestBody()).isNotNull();
    }

    @Test
    void requestBodyDeclaredWithoutContentYieldsNullBody() {
        // requestBody node present but with NO `content:` -> body.getContent()==null (L286 right arm) -> null body,
        // and consumes coalesces to empty.
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /emptybody:
                    post:
                      requestBody:
                        description: a body with no content map
                        required: true
                      responses:
                        '200': { description: ok }
                """;
        SpecParse p = extractor.extract("emptybody", oas);
        Endpoint e = endpoint(p, HttpMethod.POST, "/emptybody");
        assertThat(e.requestBody()).isNull();
        assertThat(e.consumes()).isEmpty();
    }

    // ----------------------------------------------------------------------------------------------------
    // extract: 1xx / 3xx success-band edges of  status >= 200 && status < 300  (L191 partial)
    // ----------------------------------------------------------------------------------------------------

    @Test
    void redirectResponseContentTypeDoesNotInflateProduces() {
        // A 302 (>=300) response WITH content must not feed `produces` (status<300 sub-branch false), while the
        // 200 success content does. Exercises the upper bound of the success-band check.
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /redir:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: { type: string }
                        '302':
                          description: moved
                          content:
                            text/html:
                              schema: { type: string }
                """;
        SpecParse p = extractor.extract("redir", oas);
        Endpoint e = endpoint(p, HttpMethod.GET, "/redir");
        // only the 2xx content type contributes to produces; text/html from the 302 must be excluded
        assertThat(e.produces()).containsExactly("application/json");
        assertThat(e.responses()).extracting(ResponseModel::statusCode).containsExactlyInAnyOrder(200, 302);
    }
}