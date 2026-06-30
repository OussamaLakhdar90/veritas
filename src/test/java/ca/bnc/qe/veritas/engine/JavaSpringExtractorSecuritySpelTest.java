package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The @PreAuthorize annotation path skipped the permitAll-normalization the SecurityFilterChain path enforces, so an
 * explicitly-open method (permitAll/isAnonymous/denyAll) fabricated a false CRITICAL SECURITY_MISMATCH; and method-level
 * security was UNIONED with the class default instead of overriding it (Spring is most-specific-wins).
 */
class JavaSpringExtractorSecuritySpelTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).hasSize(1);
        return m.endpoints().get(0);
    }

    private static Endpoint extractOne(Path dir, String controller) throws Exception {
        write(dir, "C.java", controller);
        return only(new JavaSpringExtractor().extract(dir));
    }

    @Test
    void permitAllMethodIsNotModelledAsSecured(@TempDir Path dir) throws Exception {
        Endpoint e = extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController class C {
                @PreAuthorize("permitAll()")
                @GetMapping("/health") public String h() { return "ok"; }
            }
            """);
        assertThat(e.security()).isEmpty();
    }

    @Test
    void hasRoleMethodStaysSecured(@TempDir Path dir) throws Exception {
        Endpoint e = extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController class C {
                @PreAuthorize("hasRole('ADMIN')")
                @GetMapping("/x") public String x() { return "x"; }
            }
            """);
        assertThat(e.security()).isNotEmpty();
    }

    @Test
    void methodPermitAllOverridesASecuredClass(@TempDir Path dir) throws Exception {
        Endpoint e = extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController
            @PreAuthorize("hasRole('ADMIN')")
            class C {
                @PreAuthorize("permitAll()")
                @GetMapping("/health") public String h() { return "ok"; }
            }
            """);
        assertThat(e.security()).isEmpty();   // method override → open, NOT unioned with the class default
    }

    @Test
    void methodWithoutSecurityInheritsTheClassDefault(@TempDir Path dir) throws Exception {
        Endpoint e = extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.annotation.Secured;
            @RestController
            @Secured("ROLE_ADMIN")
            class C {
                @GetMapping("/x") public String x() { return "x"; }
            }
            """);
        assertThat(e.security()).contains("ROLE_ADMIN");
    }

    @Test
    void isAnonymousAndDenyAllAreNotSecuring(@TempDir Path dir) throws Exception {
        assertThat(extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController class C {
                @PreAuthorize("isAnonymous()")
                @GetMapping("/a") public String a() { return "a"; }
            }
            """).security()).isEmpty();

        assertThat(extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController class C {
                @PreAuthorize("denyAll()")
                @GetMapping("/d") public String d() { return "d"; }
            }
            """).security()).isEmpty();
    }
}
