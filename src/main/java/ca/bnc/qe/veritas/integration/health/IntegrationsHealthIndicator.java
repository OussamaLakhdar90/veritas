package ca.bnc.qe.veritas.integration.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.settings.ConnectionTester;
import ca.bnc.qe.veritas.settings.ConnectionTestResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health contributor for the downstream integrations (Jira / Bitbucket / Confluence / Xray / Copilot),
 * reusing the same authenticated read-only probe as the in-app "Test Connection" button ({@link ConnectionTester}).
 * Exposed at {@code /actuator/health/integrations} (its own group, see application.yml).
 *
 * <p><b>Probing is opt-in</b> ({@code veritas.health.integrations.enabled=true}, off by default): each scrape makes
 * live network calls, so by default this reports UP without probing (it must not sit on a k8s liveness path
 * unconfigured). When enabled: UP only when every <i>configured</i> downstream authenticates; an unconfigured one is
 * reported but never drags health down; any configured-but-failing one → DOWN. The bean is always registered (so the
 * {@code integrations} health group resolves), only its probing is gated.
 */
@Component("downstreamIntegrations")
public class IntegrationsHealthIndicator implements HealthIndicator {

    static final List<String> SERVICES = List.of("jira", "bitbucket", "confluence", "xray", "copilot");

    private final ConnectionTester tester;
    private final boolean enabled;

    public IntegrationsHealthIndicator(ConnectionTester tester,
                                       @Value("${veritas.health.integrations.enabled:false}") boolean enabled) {
        this.tester = tester;
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up().withDetail("probing", "disabled — set veritas.health.integrations.enabled=true").build();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        int configured = 0;
        int down = 0;
        for (String service : SERVICES) {
            ConnectionTestResult r = safeTest(service);
            boolean notConfigured = !r.reachable() && !r.authenticated()
                    && r.message() != null && r.message().startsWith("Not configured");
            String state = r.authenticated() ? "UP" : notConfigured ? "NOT_CONFIGURED" : "DOWN";
            if (!notConfigured) {
                configured++;
                if (!r.authenticated()) {
                    down++;
                }
            }
            details.put(service, Map.of("state", state, "status", r.status(), "message", r.message()));
        }
        Health.Builder builder = configured == 0 ? Health.unknown() : down == 0 ? Health.up() : Health.down();
        return builder.withDetails(details).build();
    }

    private ConnectionTestResult safeTest(String service) {
        try {
            return tester.test(service);
        } catch (RuntimeException e) {
            return new ConnectionTestResult(service, false, false, 0, "Probe failed: " + e.getMessage());
        }
    }
}
