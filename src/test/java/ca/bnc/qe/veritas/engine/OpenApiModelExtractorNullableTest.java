package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import org.junit.jupiter.api.Test;

/**
 * nullable:true (value may be null) is orthogonal to required (key must be present) and has no model channel, so a
 * non-null code field vs a nullable spec field is not compared. Surface it as a blind spot rather than silently drop it.
 */
class OpenApiModelExtractorNullableTest {

    @Test
    void nullablePropertySurfacesABlindSpot() {
        ApiModel m = new OpenApiModelExtractor().extract("spec", """
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                D:
                  type: object
                  required: [name]
                  properties:
                    name: { type: string, nullable: true }
            """).model();
        assertThat(m.blindSpots().toString()).contains("nullable");
    }

    @Test
    void aSchemaWithoutNullableHasNoNullableBlindSpot() {
        ApiModel m = new OpenApiModelExtractor().extract("spec", """
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                D:
                  type: object
                  properties:
                    name: { type: string }
            """).model();
        assertThat(m.blindSpots().toString()).doesNotContain("nullable");
    }
}
