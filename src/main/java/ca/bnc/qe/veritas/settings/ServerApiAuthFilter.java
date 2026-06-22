package ca.bnc.qe.veritas.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-token gate over {@code /api/v1/**}, active ONLY on the {@code server} profile (multi-user / EC2).
 * Local single-user runs are unaffected — this bean isn't created off the {@code server} profile, so the
 * existing 127.0.0.1 deployment and every test keep working without auth.
 *
 * <p>It validates {@code Authorization: Bearer <token>} against {@code veritas.server.api-token} in constant
 * time. This is the working seam: a real OIDC/SAML resource-server filter replaces it once the bank's IdP is
 * chosen — the controllers and {@link CurrentUser} contract don't change. Fails closed: if no token is
 * configured on the server profile, every protected request is rejected (503) rather than left open.
 */
@Component
@Profile("server")
@Slf4j
public class ServerApiAuthFilter extends OncePerRequestFilter {

    @Value("${veritas.server.api-token:}")
    private String apiToken;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only guard the API; static dashboard assets + health are public.
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (apiToken == null || apiToken.isBlank()) {
            log.error("server profile active but veritas.server.api-token is not set — rejecting API requests");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "API authentication is not configured");
            return;
        }
        String header = request.getHeader("Authorization");
        String presented = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (presented == null || !constantTimeEquals(presented, apiToken)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
