package ca.bnc.qe.veritas.engine.extract.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;

/**
 * S13i-5: every DTO field must carry its OWN source location (file + declared line) so a schema-field finding
 * (SCHEMA_FIELD_MISSING / TYPE_MISMATCH / …) traces to the exact field and the report can render a clickable code
 * link — the write-side half of the user-visible "Missing from the specification" cards had no code link before.
 */
class ConstraintReaderFieldSourceTest {

    private final JavaSpringExtractor extractor = new JavaSpringExtractor();

    @Test
    void everyDtoFieldCarriesItsOwnFileAndDeclaredLineAsSource() throws Exception {
        Path dir = Files.createTempDirectory("field-src-");
        // A controller (so the DTO is reachable + registered as a schema) and the DTO with fields on known lines.
        Files.writeString(dir.resolve("WidgetController.java"),
                "import org.springframework.web.bind.annotation.*;\n@RestController\n@RequestMapping(\"/widgets\")\n"
                        + "public class WidgetController {\n  @GetMapping\n  public Widget get(){return null;}\n}\n");
        // line 1: package-less class header; `id` is declared on line 2, `name` on line 3.
        Files.writeString(dir.resolve("Widget.java"),
                "public class Widget {\n  private int id;\n  private String name;\n}\n");

        ApiModel code = extractor.extract(dir);
        SchemaModel widget = code.schemas().get("Widget");
        assertThat(widget).isNotNull();

        FieldModel id = field(widget, "id");
        FieldModel name = field(widget, "name");

        // Each field's source is non-null, points at the DTO file, and carries the field's own declared line.
        assertThat(id.source()).isNotNull();
        assertThat(id.source().location()).endsWith("Widget.java");
        assertThat(id.source().startLine()).isEqualTo(2);

        assertThat(name.source()).isNotNull();
        assertThat(name.source().location()).endsWith("Widget.java");
        assertThat(name.source().startLine()).isEqualTo(3);
    }

    private static FieldModel field(SchemaModel s, String jsonName) {
        return s.fields().stream().filter(f -> jsonName.equals(f.jsonName())).findFirst().orElseThrow();
    }
}
