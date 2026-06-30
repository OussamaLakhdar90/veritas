package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parameter / body construct coverage gaps an audit flagged: multipart bodies, @ModelAttribute command objects,
 * Pageable/Sort, Optional-wrapped params, and MediaType.*_VALUE constants in produces/consumes.
 */
class JavaSpringExtractorParamCoverageTest {

    private static void writeJava(Path dir, String name, String content) throws Exception {
        Files.writeString(Files.createDirectories(dir.resolve("src/main/java")).resolve(name), content);
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).hasSize(1);
        return m.endpoints().get(0);
    }

    @Test
    void multipartRequestPartIsModelledAsAFormDataBody(@TempDir Path dir) throws Exception {
        writeJava(dir, "UploadController.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.web.multipart.MultipartFile;
            @RestController
            class UploadController {
                @PostMapping("/upload")
                public Object upload(@RequestPart("file") MultipartFile file, @RequestPart("meta") MetaDto meta) {
                    return null;
                }
            }
            """);

        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        assertThat(e.requestBody()).isNotNull();
        assertThat(e.requestBody().mediaTypes()).contains("multipart/form-data");   // not seen as bodyless
    }

    @Test
    void modelAttributeCommandObjectSurfacesABlindSpot(@TempDir Path dir) throws Exception {
        writeJava(dir, "SearchController.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController
            class SearchController {
                @GetMapping("/search")
                public Object search(@ModelAttribute SearchCriteria criteria) { return null; }
            }
            """);

        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.blindSpots())
                .anySatisfy(b -> assertThat(b).contains("@ModelAttribute").contains("SearchCriteria"));
    }

    @Test
    void pageableSurfacesABlindSpotRatherThanGuessingPageSizeSort(@TempDir Path dir) throws Exception {
        writeJava(dir, "ListController.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.data.domain.Pageable;
            @RestController
            class ListController {
                @GetMapping("/items")
                public Object items(Pageable pageable) { return null; }
            }
            """);

        ApiModel m = new JavaSpringExtractor().extract(dir);
        // We don't fabricate page/size/sort (names are configurable, sort is array) — guessing them mis-diffs.
        assertThat(only(m).params()).noneSatisfy(p -> assertThat(p.name()).isIn("page", "size", "sort"));
        assertThat(m.blindSpots()).anySatisfy(b -> assertThat(b).contains("Pageable").contains("page/size/sort"));
    }

    @Test
    void optionalRequestParamUnwrapsToInnerTypeAndIsNotRequired(@TempDir Path dir) throws Exception {
        writeJava(dir, "OptController.java", """
            import org.springframework.web.bind.annotation.*;
            import java.util.Optional;
            @RestController
            class OptController {
                @GetMapping("/q")
                public Object q(@RequestParam Optional<String> term) { return null; }
            }
            """);

        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        ParamModel term = e.params().stream().filter(p -> p.name().equals("term")).findFirst().orElseThrow();
        assertThat(term.type()).isEqualTo("string");   // not "object"
        assertThat(term.required()).isFalse();
    }

    @Test
    void mediaTypeValueConstantsResolveToTheRealMediaType(@TempDir Path dir) throws Exception {
        writeJava(dir, "MtController.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.MediaType;
            @RestController
            class MtController {
                @PostMapping(value = "/x", produces = MediaType.APPLICATION_JSON_VALUE)
                public Object x() { return null; }
            }
            """);

        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        assertThat(e.produces()).contains("application/json");   // not "MediaType.APPLICATION_JSON_VALUE"
    }
}
