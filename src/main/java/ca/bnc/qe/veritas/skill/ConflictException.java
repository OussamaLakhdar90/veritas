package ca.bnc.qe.veritas.skill;

/**
 * A request conflicts with current state (HTTP 409) — e.g. deciding a gate that is already APPROVED/REJECTED.
 * Distinct from a generic {@link IllegalStateException} (which stays a 500) so the REST layer can map it precisely.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
