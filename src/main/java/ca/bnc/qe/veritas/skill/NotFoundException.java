package ca.bnc.qe.veritas.skill;

/**
 * A requested resource does not exist (HTTP 404) — e.g. a fix train / watch / alert id that isn't in the store.
 * Distinct from {@link IllegalArgumentException} (a 400 "bad input") so a missing resource maps to the right status
 * and the dashboard's "it may have been removed" message.
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
