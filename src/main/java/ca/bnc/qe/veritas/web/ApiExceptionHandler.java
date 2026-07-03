package ca.bnc.qe.veritas.web;

import ca.bnc.qe.veritas.preflight.CopilotAuthRequiredException;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import ca.bnc.qe.veritas.secret.SecretRequiredException;
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

    @ExceptionHandler(SecretRequiredException.class)
    public ProblemDetail onSecretRequired(SecretRequiredException e) {
        // Same 422 + problem list as any precondition, plus a stable code + the missing key so the dashboard can
        // pop an inline "connect this token" panel (deep-linking Settings) instead of a red toast.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Connect a required secret");
        pd.setProperty("code", SecretRequiredException.CODE);
        pd.setProperty("key", e.getKey());
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

    @ExceptionHandler(ca.bnc.qe.veritas.skill.NotFoundException.class)
    public ProblemDetail onNotFound(ca.bnc.qe.veritas.skill.NotFoundException e) {
        // A missing resource (unknown id) — 404, not 400/500, so the dashboard shows "it may have been removed".
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Not found");
        return pd;
    }

    @ExceptionHandler(ca.bnc.qe.veritas.skill.ConflictException.class)
    public ProblemDetail onConflict(ca.bnc.qe.veritas.skill.ConflictException e) {
        // e.g. deciding an already-decided gate (concurrent double-click / re-submit) — a conflict, not a 500.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Conflict");
        return pd;
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ProblemDetail onOptimisticLock(org.springframework.dao.OptimisticLockingFailureException e) {
        // A @Version-guarded row (e.g. a feature-index snapshot) was changed by a concurrent request between read
        // and write — surface it as a conflict so the caller reloads and retries, rather than a silent lost update.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This item was changed by another request — reload and try again.");
        pd.setTitle("Conflict");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        log.error("Unhandled API error", e);
        // Redact known secret values + Bearer/Basic tokens from the detail, exactly as the log masker does — an
        // exception message can carry a token (a URL with credentials, an auth header) that must not reach the client.
        String detail = ca.bnc.qe.veritas.secret.LogMasker.mask(
                e.getMessage() == null ? "Internal error" : e.getMessage(),
                ca.bnc.qe.veritas.secret.SecretRegistry.snapshot());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, detail);
        pd.setTitle("Internal error");
        return pd;
    }
}
