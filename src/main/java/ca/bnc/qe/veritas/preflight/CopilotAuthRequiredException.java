package ca.bnc.qe.veritas.preflight;

import java.util.List;

/**
 * Thrown when a generative step needs GitHub Copilot but it isn't connected (no token, or the token
 * expired/was revoked). A specialization of {@link PreconditionException} so existing handling still
 * applies, but the API maps it to a distinct {@code code: "copilot-auth-required"} so the dashboard can
 * pop the sign-in flow instead of showing an opaque error.
 */
public class CopilotAuthRequiredException extends PreconditionException {

    /** Stable discriminator the frontend matches on (RFC-7807 problem {@code code}). */
    public static final String CODE = "copilot-auth-required";

    public CopilotAuthRequiredException(String skill) {
        super(skill, List.of("GitHub Copilot is not connected, and this step needs it. "
                + "Sign in to Copilot (the banner, or Settings → GitHub Copilot), then try again."));
    }
}
