package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import org.junit.jupiter.api.Test;

/**
 * Spec-side false positives the deep review confirmed: an OPTIONAL security requirement (an empty {} alternative) was
 * read as "requires X" (false SECURITY_MISMATCH vs unsecured code); and an array-of-$ref property dropped its element
 * schema (refName(get$ref()) is null for an array — the items.$ref lives one level down).
 */
class OpenApiModelExtractorSpecFpTest {

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

    @Test
    void optionalSecurityIsNotComparedAsRequired() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /x:
                    get:
                      security:
                        - ApiKey: []
                        - {}
                      responses: { '200': { description: ok } }
                components:
                  securitySchemes:
                    ApiKey: { type: apiKey, name: X-Key, in: header }
                """;
        SpecParse p = extractor.extract("optsec", oas);
        assertThat(endpoint(p, HttpMethod.GET, "/x").security()).isEmpty();   // optional → not "requires ApiKey"
        assertThat(p.model().blindSpots()).anySatisfy(b -> assertThat(b).contains("OPTIONAL security"));
    }

    @Test
    void requiredSecurityWithoutAnEmptyAlternativeStaysRequired() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /x:
                    get:
                      security:
                        - ApiKey: []
                      responses: { '200': { description: ok } }
                components:
                  securitySchemes:
                    ApiKey: { type: apiKey, name: X-Key, in: header }
                """;
        SpecParse p = extractor.extract("reqsec", oas);
        assertThat(endpoint(p, HttpMethod.GET, "/x").security()).containsExactly("ApiKey");
    }

    @Test
    void arrayOfRefPropertyKeepsItsElementSchema() {
        String oas = """
                openapi: 3.0.1
                info: { title: P, version: 1 }
                paths:
                  /x:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/Bag' }
                components:
                  schemas:
                    Item:
                      type: object
                      properties: { id: { type: integer } }
                    Bag:
                      type: object
                      properties:
                        items:
                          type: array
                          items: { $ref: '#/components/schemas/Item' }
                """;
        SpecParse p = extractor.extract("arr", oas);
        SchemaModel bag = p.model().schemas().get("Bag");
        FieldModel itemsField = bag.fields().stream()
                .filter(f -> f.jsonName().equals("items")).findFirst().orElseThrow();
        assertThat(itemsField.type()).isEqualTo("array");
        assertThat(itemsField.refSchema()).isEqualTo("Item[]");   // was null — items.$ref was dropped
    }
}
