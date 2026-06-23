package secured;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.NotAcceptableStatusException;

@RestControllerAdvice
public class GlobalAdvice {

    // status from @ResponseStatus → 404
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorBody handleNotFound(RuntimeException e) {
        return null;
    }

    // no @ResponseStatus — status must come from ResponseEntity.status(...) in the body → 500 (not a default guess)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleSystemExceptions(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
    }

    // no @ResponseStatus, no body status — resolved from the well-known framework exception → 406
    @ExceptionHandler(NotAcceptableStatusException.class)
    public ErrorBody handleNotAcceptable(NotAcceptableStatusException e) {
        return null;
    }

    // status from ProblemDetail.forStatusAndDetail(...) → 422
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadArgument(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "bad argument");
    }

    // pathological: a handler annotated with a 2xx status — an exception handler's output is an error response, so
    // this 200 must NOT be attached to endpoints as a phantom success body.
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.OK)
    public ErrorBody handleOddly(IllegalStateException e) {
        return null;
    }
}
