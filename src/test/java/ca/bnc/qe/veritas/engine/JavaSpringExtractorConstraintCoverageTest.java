package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Constraint-extraction gaps a deep review confirmed: @Pattern's regexp was double-escaped (false CONSTRAINT_GAP on
 * any escaped regex); @DecimalMin/@DecimalMax and @Positive/@Negative/@PositiveOrZero/@NegativeOrZero were never read
 * (silent-drop of a real numeric bound).
 */
class JavaSpringExtractorConstraintCoverageTest {

    private static ConstraintSet field(Path dir, String dto, String name) throws Exception {
        Files.writeString(dir.resolve("Dto.java"), dto);
        Files.writeString(dir.resolve("C.java"), """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/x") public Dto x(@RequestBody Dto d) { return null; } }
            """);
        SchemaModel s = new JavaSpringExtractor().extract(dir).schemas().get("Dto");
        return s.fields().stream().filter(f -> f.jsonName().equals(name)).findFirst().orElseThrow().constraints();
    }

    @Test
    void patternRegexpIsUnescaped(@TempDir Path dir) throws Exception {
        ConstraintSet c = field(dir, """
            import jakarta.validation.constraints.Pattern;
            class Dto { @Pattern(regexp = "\\\\d+\\\\.\\\\d{2}") public String price; }
            """, "price");
        // the extracted pattern must be the REAL regex \\d+\\.\\d{2}, not the source-level double-escaped \\\\d+...
        assertThat(c.pattern()).isEqualTo("\\d+\\.\\d{2}");
    }

    @Test
    void decimalMinMaxAreExtractedWithExclusivity(@TempDir Path dir) throws Exception {
        ConstraintSet c = field(dir, """
            import jakarta.validation.constraints.*;
            import java.math.BigDecimal;
            class Dto { @DecimalMin(value = "100.00", inclusive = false) @DecimalMax("999.99") public BigDecimal amount; }
            """, "amount");
        assertThat(c.minimum()).isEqualTo(100.0);
        assertThat(c.maximum()).isEqualTo(999.99);
        assertThat(c.exclusiveMin()).isTrue();
    }

    @Test
    void positiveAndNegativeImplyZeroBounds(@TempDir Path dir) throws Exception {
        ConstraintSet pos = field(dir, """
            import jakarta.validation.constraints.Positive;
            class Dto { @Positive public Integer qty; }
            """, "qty");
        assertThat(pos.minimum()).isEqualTo(0.0);
        assertThat(pos.exclusiveMin()).isTrue();

        ConstraintSet poz = field(dir, """
            import jakarta.validation.constraints.PositiveOrZero;
            class Dto { @PositiveOrZero public Integer qty; }
            """, "qty");
        assertThat(poz.minimum()).isEqualTo(0.0);
        assertThat(poz.exclusiveMin()).isNull();
    }
}
