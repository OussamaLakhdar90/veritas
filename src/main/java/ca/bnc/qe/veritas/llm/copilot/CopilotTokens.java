package ca.bnc.qe.veritas.llm.copilot;

import java.time.Instant;

/** Value types for the Copilot auth flow. */
public final class CopilotTokens {

    private CopilotTokens() {
    }

    /** Persisted GitHub OAuth token (device-flow result). */
    public record StoredOAuthToken(String accessToken, String tokenType, String scope) {}

    /** Short-lived Copilot session token used as the Bearer for api.githubcopilot.com. */
    public record SessionToken(String token, Instant expiresAt) {}

    /** Device-flow handshake response; userCode/verificationUri are shown to the human. */
    public record DeviceCode(String deviceCode, String userCode, String verificationUri, int interval, int expiresIn) {}
}
