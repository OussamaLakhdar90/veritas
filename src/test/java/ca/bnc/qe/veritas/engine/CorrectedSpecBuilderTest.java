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
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.engine.openapi.CorrectedSpecBuilder;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import org.junit.jupiter.api.Test;

/** A6: the deterministic corrected OpenAPI must be valid (round-trips) and carry the code's endpoints/schemas. */
class CorrectedSpecBuilderTest {

    @Test
    void buildsValidRoundTrippingSpecFromCode() {
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        ParamModel id = new ParamModel("id", ParamLocation.PATH, "string", null, true, ConstraintSet.empty(), src);
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things/{id}", "getThing", List.of(id), null,
                List.of(new ResponseModel(200, "Thing", null, "RETURN", src)), null, null, List.of(), src);
        SchemaModel thing = new SchemaModel("Thing", "object",
                List.of(new FieldModel("name", "string", null, true, ConstraintSet.empty(), null, src)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep), Map.of("Thing", thing));

        String yaml = new CorrectedSpecBuilder().build(code, "Things API");
        SpecParse reparsed = new OpenApiModelExtractor().extract("corrected", yaml);

        assertThat(reparsed.parsed()).isTrue();   // valid OpenAPI — round-trips cleanly
        assertThat(reparsed.model().endpoints()).anyMatch(e -> e.signature().equals("GET /things/{id}"));
        assertThat(reparsed.model().schemas()).containsKey("Thing");
    }
}
