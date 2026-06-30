package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bean Validation values written idiomatically (long literal @Min(0L), constant @Size(min=MIN)) used to abort the whole
 * scan via unguarded Integer/Double.valueOf; and @ResponseStatus of anything but 200/201/202/204 collapsed to a phantom
 * 200. Both are core-extractor correctness/robustness fixes.
 */
class JavaSpringExtractorConstraintStatusTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static FieldModel field(SchemaModel s, String name) {
        return s.fields().stream().filter(f -> f.jsonName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void longLiteralConstraintsAreParsedAndDoNotCrash(@TempDir Path dir) throws Exception {
        write(dir, "Dto.java", """
            import jakarta.validation.constraints.*;
            class Dto {
                @Min(0L) @Max(100L) public Long amount;
                @Size(min = 2, max = 64) public String name;
            }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/x") public Dto x(@RequestBody Dto d) { return null; } }
            """);

        SchemaModel dto = new JavaSpringExtractor().extract(dir).schemas().get("Dto");   // must not throw
        assertThat(field(dto, "amount").constraints().minimum()).isEqualTo(0.0);   // @Min(0L) parsed, not crashed
        assertThat(field(dto, "amount").constraints().maximum()).isEqualTo(100.0);
        assertThat(field(dto, "name").constraints().minLength()).isEqualTo(2);
    }

    @Test
    void constantValuedConstraintDegradesToNullWithoutCrashing(@TempDir Path dir) throws Exception {
        write(dir, "Dto.java", """
            import jakarta.validation.constraints.*;
            class Dto {
                static final int MIN = 3;
                @Size(min = MIN) public String code;
            }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/x") public Dto x(@RequestBody Dto d) { return null; } }
            """);

        SchemaModel dto = new JavaSpringExtractor().extract(dir).schemas().get("Dto");   // must not throw
        assertThat(field(dto, "code").constraints().minLength()).isNull();   // unresolvable constant → not extracted
    }

    @Test
    void responseStatusResolvesTheFullStatusSetNotJustFourCodes(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.HttpStatus;
            @RestController class C {
                @ResponseStatus(HttpStatus.CONFLICT)
                @PostMapping("/x") public Object x() { return null; }
            }
            """);

        var ep = new JavaSpringExtractor().extract(dir).endpoints().get(0);
        assertThat(ep.responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(409));   // was a phantom 200
    }
}
