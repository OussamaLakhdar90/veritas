package ca.bnc.qe.veritas.settings;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped principal for the {@code server} profile. Derives the user from the identity header the
 * fronting auth proxy / OIDC filter sets ({@code X-Veritas-User}, falling back to {@code X-Forwarded-User});
 * defaults to {@code "server"} when none is present. Keying secrets/connections by this id gives per-user
 * isolation on a shared host. When the bank's OIDC filter lands, it populates the same header (or this bean
 * reads the authenticated principal directly) — no call site changes.
 */
@Component
@Profile("server")
@Primary
@RequestScope
public class ServerCurrentUser implements CurrentUser {

    private final HttpServletRequest request;

    public ServerCurrentUser(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public String principalId() {
        String user = header("X-Veritas-User");
        if (user == null) {
            user = header("X-Forwarded-User");
        }
        return user == null ? "server" : user;
    }

    private String header(String name) {
        String v = request.getHeader(name);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
