package ca.bnc.qe.veritas.settings;

/** Polled sign-in state: {@code state} is PENDING | AUTHORIZED | EXPIRED | ERROR. */
public record CopilotLoginStatus(String state, String message) {
}
