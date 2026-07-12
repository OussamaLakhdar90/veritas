package ca.bnc.qe.veritas.snyk.fix;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deterministic, operator-set overrides for the Snyk fix reactor's per-app build command — the escape hatch for when
 * the {@link BuildCommandAdvisor} can't work out the right command (a Copilot outage, or an app whose test setup is too
 * unusual to infer) and the {@code mvn -q -B test} default fails for the wrong reason (e.g. a TestNG {@code
 * <suiteXmlFile>} the pom reads from an undefined property, surfacing as {@code has null value}).
 *
 * <p>Configure {@code veritas.snyk.fix.build-commands.<APPID>=<mvn command>}, e.g.
 * <pre>
 * veritas.snyk.fix.build-commands.APP7571=mvn -q -B -DsuiteXmlFile=src/test/resources/suites/regression.xml test
 * </pre>
 * An override is checked <b>before</b> the cache and the AI (so it always wins and is immune to model availability) but
 * is still run through {@link BuildCommandGuard} by the advisor — a misconfigured/unsafe override is ignored, not
 * executed. Keys are matched case-insensitively on the app id.
 */
@Component
@ConfigurationProperties(prefix = "veritas.snyk.fix")
@Getter
@Setter
public class BuildCommandProperties {

    /** appId (case-insensitive) → the exact {@code mvn} command to test that app with. Empty by default. */
    private Map<String, String> buildCommands = new LinkedHashMap<>();

    /** The configured command for an app, if any (trimmed; blank/absent → empty). Case-insensitive on the app id. */
    public Optional<String> override(String appId) {
        if (appId == null || buildCommands.isEmpty()) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> e : buildCommands.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(appId)) {
                String cmd = e.getValue();
                return cmd == null || cmd.isBlank() ? Optional.empty() : Optional.of(cmd.trim());
            }
        }
        return Optional.empty();
    }
}
