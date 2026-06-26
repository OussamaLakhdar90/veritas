package ca.bnc.qe.veritas.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi metadata for Veritas's OWN REST API. The full, always-current endpoint reference is served
 * live at {@code /swagger-ui} ({@code /v3/api-docs} for the raw spec), so the README no longer has to hand-maintain
 * a stale list (it documented 7 of ~62 endpoints). These paths are outside {@code /api/v1/**}, so the server-profile
 * auth filter does not gate them.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI veritasOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Veritas API")
                .version("0.1.0")
                .description("Copilot-wrapped API quality platform: OpenAPI contract validation, ISTQB test "
                        + "management (strategies / plans / cases / reviews), and template-driven test generation."));
    }
}
