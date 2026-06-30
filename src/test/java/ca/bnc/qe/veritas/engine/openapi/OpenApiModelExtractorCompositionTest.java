package ca.bnc.qe.veritas.engine.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import org.junit.jupiter.api.Test;

/**
 * Spec features the extractor used to silently drop into an empty/structureless model now either degrade gracefully
 * (allOf inline flatten, content-typed param, prefer-JSON body) or surface an honest blind spot (oneOf/anyOf,
 * additionalProperties, ranged/default responses) — the spec side finally has the blind-spot channel the code side has.
 */
class OpenApiModelExtractorCompositionTest {

    private final OpenApiModelExtractor extractor = new OpenApiModelExtractor();

    private ApiModel model(String yaml) {
        return extractor.extract("spec", yaml).model();
    }

    @Test
    void allOfInlineMembersAreFlattenedIntoFields() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                Dog:
                  allOf:
                    - $ref: '#/components/schemas/Animal'
                    - type: object
                      properties:
                        bark: { type: string }
                Animal:
                  type: object
                  properties:
                    name: { type: string }
            """);
        assertThat(m.schemas().get("Dog").fields()).extracting(FieldModel::jsonName).contains("bark");
    }

    @Test
    void oneOfCompositionIsSurfacedAsABlindSpot() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                Pet:
                  oneOf:
                    - $ref: '#/components/schemas/Cat'
                    - $ref: '#/components/schemas/Dog'
                Cat: { type: object, properties: { meow: { type: string } } }
                Dog: { type: object, properties: { bark: { type: string } } }
            """);
        assertThat(m.blindSpots()).anySatisfy(b -> assertThat(b).contains("Pet").contains("oneOf"));
    }

    @Test
    void additionalPropertiesMapIsSurfacedAsABlindSpot() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                Bag:
                  type: object
                  additionalProperties:
                    type: string
            """);
        assertThat(m.blindSpots())
                .anySatisfy(b -> assertThat(b).contains("Bag").contains("additionalProperties"));
    }

    @Test
    void rangedAndDefaultResponseKeysAreSurfaced() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths:
              /x:
                get:
                  responses:
                    '2XX': { description: ok }
                    default: { description: err }
            """);
        assertThat(m.blindSpots()).anySatisfy(b -> assertThat(b).contains("2XX"));
        assertThat(m.blindSpots()).anySatisfy(b -> assertThat(b).contains("default"));
    }

    @Test
    void contentTypedParameterResolvesItsSchema() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths:
              /x:
                get:
                  parameters:
                    - name: filter
                      in: query
                      content:
                        application/json:
                          schema: { type: string }
                  responses:
                    '200': { description: ok }
            """);
        Endpoint ep = m.endpoints().get(0);
        ParamModel filter = ep.params().stream().filter(p -> p.name().equals("filter")).findFirst().orElseThrow();
        assertThat(filter.type()).isEqualTo("string");   // pulled from content[].schema, not left null/opaque
    }

    @Test
    void multiMediaTypeBodyPrefersTheJsonSchema() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths:
              /x:
                post:
                  requestBody:
                    content:
                      application/xml: { schema: { $ref: '#/components/schemas/XmlDto' } }
                      application/json: { schema: { $ref: '#/components/schemas/JsonDto' } }
                  responses:
                    '200': { description: ok }
            components:
              schemas:
                XmlDto: { type: object, properties: { a: { type: string } } }
                JsonDto: { type: object, properties: { b: { type: string } } }
            """);
        // XML is listed first; JSON must still be chosen deterministically.
        assertThat(m.endpoints().get(0).requestBody().schemaRef()).isEqualTo("JsonDto");
    }
}
