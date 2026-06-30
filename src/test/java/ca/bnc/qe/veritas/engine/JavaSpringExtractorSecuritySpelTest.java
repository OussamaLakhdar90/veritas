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
    void jsr250PermitAllMethodOverridesASecuredClass(@TempDir Path dir) throws Exception {
        // @PermitAll (JSR-250) on a @RolesAllowed class opens that method — must NOT inherit the class roles.
        assertThat(extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import jakarta.annotation.security.RolesAllowed;
            import jakarta.annotation.security.PermitAll;
            @RestController @RolesAllowed("ADMIN")
            class C { @PermitAll @GetMapping("/health") public String h() { return "ok"; } }
            """).security()).isEmpty();
    }

    @Test
    void jsr250DenyAllStaysSecured(@TempDir Path dir) throws Exception {
        assertThat(extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import jakarta.annotation.security.DenyAll;
            @RestController class C { @DenyAll @GetMapping("/x") public String x() { return "x"; } }
            """).security()).isNotEmpty();
    }

    @Test
    void anonymousRoleSentinelsAreNotSecuring(@TempDir Path dir) throws Exception {
        assertThat(extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.annotation.Secured;
            @RestController class C {
                @Secured("IS_AUTHENTICATED_ANONYMOUSLY")
                @GetMapping("/a") public String a() { return "a"; }
            }
            """).security()).isEmpty();
    }

    @Test
    void unresolvedComposedSecurityAnnotationReadsAsSecuredWithABlindSpot(@TempDir Path dir) throws Exception {
        // @AdminOnly is NOT defined in the scanned sources (it lives in a shared jar) — a security-suggestive annotation
        // we can't resolve must read as secured-unknown (not OPEN, a security false-negative) + surface a blind spot.
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C {
                @AdminOnly @DeleteMapping("/users/{id}") public void del(@PathVariable String id) {}
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(only(m).security()).isNotEmpty();
        assertThat(m.blindSpots().toString()).contains("AdminOnly").contains("could not be resolved");
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
    void isAnonymousIsOpenButDenyAllIsLockedNotOpen(@TempDir Path dir) throws Exception {
        // isAnonymous() = open → unsecured
        assertThat(extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController class C {
                @PreAuthorize("isAnonymous()")
                @GetMapping("/a") public String a() { return "a"; }
            }
            """).security()).isEmpty();

        // denyAll() = LOCKED (403 to everyone), the OPPOSITE of open → must NOT read as unsecured (security false-neg)
        assertThat(extractOne(dir, """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController class C {
                @PreAuthorize("denyAll()")
                @GetMapping("/d") public String d() { return "d"; }
            }
            """).security()).isNotEmpty();
    }
}
