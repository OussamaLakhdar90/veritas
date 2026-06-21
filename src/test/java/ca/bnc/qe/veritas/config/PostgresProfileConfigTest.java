package ca.bnc.qe.veritas.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Guards the server (postgres) profile: valid YAML, Postgres driver + dialect wired. */
class PostgresProfileConfigTest {

    @Test
    void postgresProfileSelectsPostgresDriverAndDialect() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("postgres", new ClassPathResource("application-postgres.yml"));
        assertThat(sources).isNotEmpty();
        PropertySource<?> ps = sources.get(0);

        assertThat(ps.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
        assertThat(ps.getProperty("spring.jpa.properties.hibernate.dialect"))
                .isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
        // connection details must come from the environment (no hardcoded secrets)
        assertThat(ps.getProperty("spring.datasource.url").toString()).contains("${VERITAS_DB_URL");
    }
}
