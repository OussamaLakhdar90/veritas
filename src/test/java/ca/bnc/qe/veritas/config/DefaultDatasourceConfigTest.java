package ca.bnc.qe.veritas.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the DEFAULT (local SQLite) datasource path.
 *
 * <p>Regression: a bare relative {@code jdbc:sqlite:veritas.db} resolves against the JVM working directory, so
 * rebuilding/relaunching Veritas from a different cwd opens a DIFFERENT (empty) database and silently loses
 * run-over-run scan history — which reset the report Trend line and made it falsely claim "first scan of this
 * service". The default path MUST be anchored under the user home ({@code ~/.veritas}) so history survives a
 * relaunch from any directory. This test fails if anyone reverts the URL to a cwd-relative form.
 */
class DefaultDatasourceConfigTest {

    @Test
    void defaultSqliteUrlIsAnchoredUnderUserHome_notCwdRelative() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();
        String url = String.valueOf(sources.get(0).getProperty("spring.datasource.url"));

        assertThat(url)
                .as("default SQLite DB must live under ~/.veritas so scan history survives a relaunch from any cwd")
                .contains("${user.home}")
                .contains("/.veritas/veritas.db");
        // The cwd-relative form (path with no directory anchor) is exactly the regression this guards against.
        assertThat(url)
                .as("datasource URL must not be a cwd-relative sqlite path")
                .doesNotContain("jdbc:sqlite:veritas.db");
    }
}
