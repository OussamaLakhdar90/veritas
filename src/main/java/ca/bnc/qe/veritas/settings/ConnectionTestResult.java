package ca.bnc.qe.veritas.settings;

/**
 * Outcome of a live connection probe. {@code reachable} = the host answered; {@code authenticated} = the token
 * was accepted; {@code status} = HTTP status (0 if none); {@code message} = a user-facing summary (never a token).
 */
public record ConnectionTestResult(String service, boolean reachable, boolean authenticated, int status, String message) {
}
