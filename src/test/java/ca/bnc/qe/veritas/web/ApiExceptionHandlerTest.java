package ca.bnc.qe.veritas.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.preflight.CopilotAuthRequiredException;
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
    void copilotAuthRequiredCarriesTheCodeTheUiKeysOn() {
        ProblemDetail pd = handler.onCopilotAuthRequired(new CopilotAuthRequiredException("test-strategy"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getProperties().get("code")).isEqualTo(CopilotAuthRequiredException.CODE);
        assertThat(pd.getProperties()).containsKey("problems");
    }

    @Test
    void badArgumentMapsTo400() {
        ProblemDetail pd = handler.onBadArgument(new IllegalArgumentException("Unknown finding x"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void the500DetailRedactsAuthTokensSoSecretsDoNotLeakToTheClient() {
        ProblemDetail pd = handler.onUnexpected(
                new RuntimeException("git push failed: https call with Authorization: Bearer ghp_supersecret123"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(pd.getDetail()).contains("Bearer ***").doesNotContain("ghp_supersecret123");
    }

    @Test
    void the500DetailFallsBackWhenTheMessageIsNull() {
        ProblemDetail pd = handler.onUnexpected(new RuntimeException());
        assertThat(pd.getDetail()).isEqualTo("Internal error");
    }
}
