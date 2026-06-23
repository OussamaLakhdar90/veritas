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

        // A2: @RestControllerAdvice error responses attached to the endpoint, with the status resolved from each
        // handler's mechanism (@ResponseStatus, ResponseEntity.status, framework exception, ProblemDetail) — never
        // a blind default-500.
        assertThat(get.responses()).anyMatch(r -> r.statusCode() == 404);   // @ResponseStatus(NOT_FOUND)
        assertThat(get.responses()).anyMatch(r -> r.statusCode() == 500);   // ResponseEntity.status(INTERNAL_SERVER_ERROR)
        assertThat(get.responses()).anyMatch(r -> r.statusCode() == 406);   // NotAcceptableStatusException → framework map
        assertThat(get.responses()).anyMatch(r -> r.statusCode() == 422);   // ProblemDetail.forStatusAndDetail(UNPROCESSABLE)
        assertThat(code.schemas()).containsKey("ErrorBody");
    }
}
