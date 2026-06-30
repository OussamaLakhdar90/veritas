package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Security blind spots a fresh deep review confirmed: @PostAuthorize (a real post-invocation authorization annotation)
 * was never read, so a @PostAuthorize-only endpoint was treated as UNSECURED — fabricating a CRITICAL SECURITY_MISMATCH
 * against a secured spec (and dropping the real code-secured-vs-open divergence); and @PreAuthorize("true") (an
 * always-permit SpEL, the OPEN counterpart of permitAll()) was read as a real authorization constraint.
 */
class FreshReviewSecurityTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static ApiModel spec(String yaml) {
        return new OpenApiModelExtractor().extract("spec", yaml).model();
    }

    private static final String SECURED_SPEC = """
        openapi: 3.0.1
        info: { title: t, version: '1' }
        paths:
          /x:
            get:
              responses: { '200': { description: ok } }
              security: [ { bearerAuth: [] } ]
        components:
          securitySchemes: { bearerAuth: { type: http, scheme: bearer } }
        """;
    private static final String OPEN_SPEC = """
        openapi: 3.0.1
        info: { title: t, version: '1' }
        paths:
          /x: { get: { responses: { '200': { description: ok } } } }
        """;

    @Test
    void postAuthorizeIsReadAsSecured(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PostAuthorize;
            @RestController class C {
                @PostAuthorize("hasRole('ADMIN')")
                @GetMapping("/x") public String x() { return "x"; }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        assertThat(code.endpoints().get(0).security()).isNotEmpty();
        // code IS secured and the spec agrees → no mismatch
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec(SECURED_SPEC)))
                .noneMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH);
        // code IS secured but the spec declares none → a real mismatch must fire (not be silently dropped)
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec(OPEN_SPEC)))
                .anyMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH);
    }

    @Test
    void preAuthorizeTrueIsOpenNotSecured(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController class C {
                @PreAuthorize("true")
                @GetMapping("/x") public String x() { return "x"; }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        assertThat(code.endpoints().get(0).security()).isEmpty();
        // @PreAuthorize("true") permits everyone → open → agrees with an open spec, no fabricated CRITICAL
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec(OPEN_SPEC)))
                .noneMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH);
    }
}
