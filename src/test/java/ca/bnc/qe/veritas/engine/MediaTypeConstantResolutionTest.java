package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S13j-2: consumes/produces declared with a {@code MediaType.*_VALUE} constant outside the small fast-path map
 * (MULTIPART_FORM_DATA_VALUE, TEXT_EVENT_STREAM_VALUE, …) used to leak the raw source text as a media type and fire a
 * false CONSUMES_PRODUCES_MISMATCH. The extractor now reflectively resolves the constant to its real value, and an
 * unresolvable constant is skipped (leaving consumes/produces honestly empty) rather than emitted as a literal.
 */
class MediaTypeConstantResolutionTest {

    private static final String HDR = "package demo;\n"
            + "import org.springframework.web.bind.annotation.*;\n"
            + "import org.springframework.http.MediaType;\n";

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).hasSize(1);
        return m.endpoints().get(0);
    }

    @Test
    void multipartFormDataValueConstantResolvesToTheRealMediaType(@TempDir Path dir) throws Exception {
        write(dir, "C.java", HDR + "@RestController class C {"
                + " @PostMapping(value=\"/upload\", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)"
                + " String g(){return null;} }");

        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        assertThat(e.consumes()).containsExactly("multipart/form-data");
    }

    @Test
    void textEventStreamValueConstantResolvesToTheRealMediaType(@TempDir Path dir) throws Exception {
        write(dir, "C.java", HDR + "@RestController class C {"
                + " @GetMapping(value=\"/stream\", produces = MediaType.TEXT_EVENT_STREAM_VALUE)"
                + " String g(){return null;} }");

        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        assertThat(e.produces()).containsExactly("text/event-stream");
    }

    @Test
    void multipartConsumesDoesNotFalseDiffAgainstAMultipartSpec(@TempDir Path dir) throws Exception {
        write(dir, "C.java", HDR + "@RestController class C {"
                + " @PostMapping(value=\"/upload\", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)"
                + " String g(){return null;} }");
        ApiModel code = new JavaSpringExtractor().extract(dir);

        String spec = """
                openapi: 3.0.1
                info: {title: t, version: '1'}
                paths:
                  /upload:
                    post:
                      requestBody:
                        content:
                          multipart/form-data: {schema: {type: object}}
                      responses: {'200': {description: ok}}
                """;
        SpecParse parse = new OpenApiModelExtractor().extract("repo-spec", spec);
        assertThat(parse.parsed()).isTrue();

        List<Finding> findings = new DiffEngine().diffCodeVsSpec(code, parse.model());
        assertThat(findings).noneMatch(f -> f.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH);
    }
}
