package ca.bnc.qe.veritas.web;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags every request with a correlation id in the SLF4J {@link MDC} so all log lines for one request/scan share a
 * traceable id ({@code %X{correlationId}} in the log pattern) — the missing piece for local post-mortem debugging
 * across the async scan/strategy workers. Honours an inbound {@code X-Correlation-Id} (sanitised) so a caller can
 * thread its own id through, otherwise generates a short one, and echoes it back on the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = sanitize(request.getHeader(HEADER));
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Keep only log-safe characters and bound the length — an inbound header must never inject into the log line. */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
