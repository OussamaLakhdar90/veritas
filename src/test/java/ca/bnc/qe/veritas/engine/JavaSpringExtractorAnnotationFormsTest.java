package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Single-member and array annotation forms were mis-parsed by firstString (it returns the single-member value for ANY
 * requested member). So @RequestParam("q") / @RequestHeader("X") / @CookieValue("c") read required=false (false
 * PARAM_REQUIRED_MISMATCH), @Secured({A,B}) truncated to A, and @JsonIgnore(false) was treated as ignored.
 */
class JavaSpringExtractorAnnotationFormsTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).hasSize(1);
        return m.endpoints().get(0);
    }

    @Test
    void singleMemberRequestParamIsRequiredByDefault(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C {
                @GetMapping("/x") public Object x(@RequestParam("q") String q) { return null; }
            }
            """);
        ParamModel q = only(new JavaSpringExtractor().extract(dir)).params().stream()
                .filter(p -> p.name().equals("q")).findFirst().orElseThrow();
        assertThat(q.required()).isTrue();
    }

    @Test
    void singleMemberHeaderAndCookieAreRequiredByDefault(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C {
                @GetMapping("/x") public Object x(@RequestHeader("X-Trace") String t, @CookieValue("sid") String s) {
                    return null;
                }
            }
            """);
        assertThat(only(new JavaSpringExtractor().extract(dir)).params())
                .allSatisfy(p -> assertThat(p.required()).isTrue());
    }

    @Test
    void namedRequiredFalseAndDefaultValueStillMakeParamOptional(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C {
                @GetMapping("/x") public Object x(
                    @RequestParam(value = "a", required = false) String a,
                    @RequestParam(value = "b", defaultValue = "0") String b) { return null; }
            }
            """);
        assertThat(only(new JavaSpringExtractor().extract(dir)).params())
                .allSatisfy(p -> assertThat(p.required()).isFalse());
    }

    @Test
    void securedAndRolesAllowedArraysKeepAllRoles(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.annotation.Secured;
            @RestController class C {
                @Secured({"ROLE_ADMIN", "ROLE_USER"})
                @GetMapping("/x") public Object x() { return null; }
            }
            """);
        assertThat(only(new JavaSpringExtractor().extract(dir)).security())
                .contains("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void jsonIgnoreFalseFieldStaysInTheSchema(@TempDir Path dir) throws Exception {
        write(dir, "Dto.java", """
            import com.fasterxml.jackson.annotation.JsonIgnore;
            class Dto {
                @JsonIgnore(false) public String shown;
                @JsonIgnore(value = false) public String alsoShown;
                @JsonIgnore public String hidden;
            }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/x") public Dto x() { return null; } }
            """);
        var dto = new JavaSpringExtractor().extract(dir).schemas().get("Dto");
        // Both the single-member @JsonIgnore(false) AND the normal @JsonIgnore(value = false) re-enable a wire field —
        // dropping the latter (it fell through to "ignored") produced a false SCHEMA_FIELD_EXTRA.
        assertThat(dto.fields()).extracting(FieldModel::jsonName)
                .contains("shown", "alsoShown").doesNotContain("hidden");
    }
}
