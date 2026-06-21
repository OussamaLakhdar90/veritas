package ca.bnc.qe.veritas.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void preconditionMapsTo422WithProblemList() {
        ProblemDetail pd = handler.onPrecondition(
                new PreconditionException("create-defect", List.of("Finding id is required.")));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getProperties()).containsKey("problems");
        assertThat(pd.getProperties().get("remediation").toString()).contains("doctor");
    }

    @Test
    void badArgumentMapsTo400() {
        ProblemDetail pd = handler.onBadArgument(new IllegalArgumentException("Unknown finding x"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }
}
