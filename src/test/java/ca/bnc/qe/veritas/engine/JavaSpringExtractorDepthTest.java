package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;

/** A1 + A3: inherited DTO fields (via the type set) + method security (@PreAuthorize) extraction. */
class JavaSpringExtractorDepthTest {

    @Test
    void extractsSecurityAndInheritedFields() throws Exception {
        Path root = Path.of(getClass().getClassLoader().getResource("fixtures/secured").toURI());
        ApiModel code = new JavaSpringExtractor().extract(root);

        Endpoint get = code.endpoints().stream()
                .filter(e -> e.signature().equals("GET /api/v1/secured/{id}"))
                .findFirst().orElseThrow();
        assertThat(get.security()).anyMatch(s -> s.contains("ADMIN"));   // @PreAuthorize captured

        SchemaModel resp = code.schemas().get("SecuredResponse");
        assertThat(resp).isNotNull();
        assertThat(resp.fields()).extracting("jsonName").contains("name", "id");   // "id" is inherited from BaseResource

        // A2: @RestControllerAdvice error response (404 → ErrorBody) attached to the endpoint
        assertThat(get.responses()).anyMatch(r -> r.statusCode() == 404);
        assertThat(code.schemas()).containsKey("ErrorBody");
    }
}
