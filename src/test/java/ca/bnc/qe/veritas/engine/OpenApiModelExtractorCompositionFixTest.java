package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import org.junit.jupiter.api.Test;

/**
 * OpenAPI composition blind spots a discovery pass confirmed: a $ref allOf member (the canonical inheritance idiom) was
 * NOT flattened, so a code DTO with the inherited fields produced a false SCHEMA_FIELD_MISSING for every inherited field
 * when the schema shared a name; a property-level allOf:[{$ref}] (nullable-wrapper) dropped its binding; and a $ref-typed
 * enum property never surfaced the target's enum.
 */
class OpenApiModelExtractorCompositionFixTest {

    private ApiModel model(String yaml) {
        return new OpenApiModelExtractor().extract("spec", yaml).model();
    }

    private FieldModel field(SchemaModel s, String name) {
        return s.fields().stream().filter(f -> f.jsonName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void refAllOfInheritedFieldsAreFlattenedAndNoBlindSpotRemains() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                Sub:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      properties: { bark: { type: string } }
                Base:
                  type: object
                  properties: { name: { type: string } }
            """);
        // Sub now carries BOTH the inherited `name` and its own `bark` — so a code `class Sub extends Base` no longer
        // false-diffs `name` as SCHEMA_FIELD_MISSING.
        assertThat(m.schemas().get("Sub").fields()).extracting(FieldModel::jsonName)
                .contains("name", "bark");
        // And the advisory "composition not fully compared" blind spot for Sub is gone (it IS fully compared now).
        assertThat(m.blindSpots()).noneSatisfy(b -> assertThat(b).contains("'Sub'").contains("composition"));
    }

    @Test
    void pureInheritanceAllOfFlattensTheBase() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                Sub:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                Base:
                  type: object
                  properties: { id: { type: integer } }
            """);
        assertThat(m.schemas().get("Sub").fields()).extracting(FieldModel::jsonName).contains("id");
    }

    @Test
    void propertyLevelAllOfRefKeepsItsBinding() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                Outer:
                  type: object
                  properties:
                    child:
                      allOf:
                        - $ref: '#/components/schemas/Child'
                Child:
                  type: object
                  properties: { z: { type: string } }
            """);
        assertThat(field(m.schemas().get("Outer"), "child").refSchema()).isEqualTo("Child");
    }

    @Test
    void refTypedEnumPropertySurfacesTheTargetEnum() {
        ApiModel m = model("""
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                Status: { type: string, enum: [ACTIVE, CLOSED] }
                Holder:
                  type: object
                  properties:
                    status: { $ref: '#/components/schemas/Status' }
            """);
        FieldModel status = field(m.schemas().get("Holder"), "status");
        assertThat(status.type()).isEqualTo("string");                          // pulled from the Status leaf
        assertThat(status.constraints().enumValues()).containsExactly("ACTIVE", "CLOSED");   // enum now surfaced
        assertThat(status.refSchema()).isEqualTo("Status");                     // ref kept (rendering + name-diff)
    }
}
