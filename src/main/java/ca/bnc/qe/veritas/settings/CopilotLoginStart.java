package ca.bnc.qe.veritas.settings;

/** Returned when a Copilot sign-in starts: show the user code + verification URL, then poll status by {@code id}. */
public record CopilotLoginStart(String id, String userCode, String verificationUri, long expiresIn) {
}
