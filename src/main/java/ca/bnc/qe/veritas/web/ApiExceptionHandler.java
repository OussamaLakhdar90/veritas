package ca.bnc.qe.veritas.web;

import ca.bnc.qe.veritas.preflight.CopilotAuthRequiredException;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC-7807 error mapping for the REST API. A failed precondition is the user's input/config problem, so it
 * returns 422 with the actionable problem list (not an opaque 500); bad ids → 400; anything else → 500.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(CopilotAuthRequiredException.class)
    public ProblemDetail onCopilotAuthRequired(CopilotAuthRequiredException e) {
        // Same 422 + problem list as any precondition, plus a stable code the dashboard keys on to open sign-in.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Connect GitHub Copilot");
        pd.setProperty("code", CopilotAuthRequiredException.CODE);
        pd.setProperty("problems", e.problems());
        return pd;
    }

    @ExceptionHandler(PreconditionException.class)
    public ProblemDetail onPrecondition(PreconditionException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Preconditions not met");
        pd.setProperty("problems", e.problems());
        pd.setProperty("remediation", "Run `veritas doctor` or see docs/configuration.md");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onBadArgument(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid request");
        return pd;
    }

    @ExceptionHandler(ca.bnc.qe.veritas.skill.ConflictException.class)
    public ProblemDetail onConflict(ca.bnc.qe.veritas.skill.ConflictException e) {
        // e.g. deciding an already-decided gate (concurrent double-click / re-submit) — a conflict, not a 500.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Conflict");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        log.error("Unhandled API error", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                e.getMessage() == null ? "Internal error" : e.getMessage());
        pd.setTitle("Internal error");
        return pd;
    }
}
