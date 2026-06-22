package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Slice A extractor fixes: ResponseEntity builder status codes + envelope (ApiResponse&lt;T&gt;) unwrapping. */
class JavaSpringExtractorResponseEnvelopeTest {

    private static final String HDR = "package demo;\nimport org.springframework.web.bind.annotation.*;\n"
            + "import org.springframework.http.ResponseEntity;\n";

    @Test
    void responseEntityCreatedYields201WithBody(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @PostMapping(\"/u\") ResponseEntity<User> create(){ return ResponseEntity.created(null).body(null); } }");

        Endpoint e = new JavaSpringExtractor().extract(dir).endpoints().get(0);

        assertThat(e.responses()).anyMatch(r -> r.statusCode() == 201 && "User".equals(r.schemaRef()));
        assertThat(e.responses()).noneMatch(r -> r.statusCode() == 200);   // no longer defaulted to 200
    }

    @Test
    void responseEntityNoContentYields204(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @DeleteMapping(\"/u/{id}\") ResponseEntity<Void> del(@PathVariable String id){ return ResponseEntity.noContent().build(); } }");

        Endpoint e = new JavaSpringExtractor().extract(dir).endpoints().get(0);

        assertThat(e.responses()).anyMatch(r -> r.statusCode() == 204);
    }

    @Test
    void responseEntityStatusHttpStatusIsResolved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR + "import org.springframework.http.HttpStatus;\n@RestController class C {"
                + " @PostMapping(\"/u\") ResponseEntity<String> a(){ return ResponseEntity.status(HttpStatus.ACCEPTED).body(\"x\"); } }");

        Endpoint e = new JavaSpringExtractor().extract(dir).endpoints().get(0);

        assertThat(e.responses()).anyMatch(r -> r.statusCode() == 202);
    }

    @Test
    void apiResponseEnvelopeUnwrapsToInnerPayload(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("ApiResponse.java"), "package demo; public class ApiResponse<T> { public T data; }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {"
                + " @GetMapping(\"/u\") ApiResponse<User> get(){ return null; } }");

        ApiModel m = new JavaSpringExtractor().extract(dir);
        Endpoint e = m.endpoints().get(0);

        assertThat(e.responses()).anyMatch(r -> "User".equals(r.schemaRef()));   // not "ApiResponse"
        assertThat(m.schemas()).containsKey("User");
    }
}
