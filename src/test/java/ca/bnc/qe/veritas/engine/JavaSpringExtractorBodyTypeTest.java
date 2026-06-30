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
 * Response body-type fidelity a discovery pass confirmed: a byte[] return leaked the literal "byte[]" (DiffEngine.
 * arrayRef reads it as an array → false RESPONSE_SCHEMA_MISMATCH vs a string/binary spec); and Page/Slice/PagedModel/
 * CollectionModel were unwrapped to T[] (a bare array) → false array-vs-object vs the correct paged-OBJECT spec.
 */
class JavaSpringExtractorBodyTypeTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).hasSize(1);
        return m.endpoints().get(0);
    }

    @Test
    void byteArrayReturnIsABinaryStringNotAnArray(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/file") public byte[] g() { return null; } }
            """);
        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        // the success response schemaRef must NOT end with "[]" (which DiffEngine.arrayRef reads as a JSON array)
        assertThat(e.responses()).allSatisfy(r ->
                assertThat(r.schemaRef() == null || !r.schemaRef().endsWith("[]")).isTrue());
        assertThat(e.responses()).anySatisfy(r -> assertThat(r.schemaRef()).isEqualTo("string"));
    }

    @Test
    void realArrayReturnStaysAnArray(@TempDir Path dir) throws Exception {
        write(dir, "Widget.java", "public class Widget { public String id; }");
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/w") public Widget[] g() { return null; } }
            """);
        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        assertThat(e.responses()).anySatisfy(r -> assertThat(r.schemaRef()).isEqualTo("Widget[]"));
    }

    @Test
    void pageReturnIsAnObjectEnvelopeWithABlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "Widget.java", "public class Widget { public String id; }");
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.data.domain.Page;
            @RestController class C { @GetMapping("/w") public Page<Widget> g() { return null; } }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        Endpoint e = only(m);
        // no bare "Widget[]" array ref leaks (which would false-diff vs the paged-object spec)
        assertThat(e.responses()).noneSatisfy(r -> assertThat(r.schemaRef()).isEqualTo("Widget[]"));
        assertThat(m.blindSpots().toString()).contains("paged");
    }
}
