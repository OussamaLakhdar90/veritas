package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Locks the extractor robustness fixes confirmed by the failure audit: regex path-vars, paged/async/reactive
 * response wrappers, raw ResponseEntity, and honest blind spots for placeholder / constant / multi-path paths.
 */
class JavaSpringExtractorRobustnessTest {

    private ApiModel extract(Path dir, String className, String body) throws Exception {
        Files.writeString(dir.resolve(className + ".java"),
                "package demo;\nimport org.springframework.web.bind.annotation.*;\n"
                        + "import org.springframework.http.ResponseEntity;\n"
                        + "import java.util.List;\nimport java.util.concurrent.CompletableFuture;\n"
                        + "import org.springframework.data.domain.Page;\n" + body);
        return new JavaSpringExtractor().extract(dir);
    }

    private String pathOf(ApiModel m) {
        return m.endpoints().isEmpty() ? null : m.endpoints().get(0).pathTemplate();
    }

    @Test
    void normalizesRegexPathVariables(@TempDir Path dir) throws Exception {
        ApiModel m = extract(dir, "RegexCtrl",
                "@RestController class RegexCtrl { @GetMapping(\"/users/{id:\\\\d+}\") String g(@PathVariable String id){return null;} }");
        assertThat(pathOf(m)).isEqualTo("/users/{id}");   // regex constraint stripped
    }

    @Test
    void unwrapsPageToInnerDtoArray(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Widget.java"), "package demo; public class Widget { public String id; }");
        ApiModel m = extract(dir, "PageCtrl",
                "@RestController class PageCtrl { @GetMapping(\"/w\") Page<Widget> g(){return null;} }");
        Endpoint e = m.endpoints().get(0);
        // body is a collection of Widget, and Page itself is not emitted as a phantom schema / blind spot
        assertThat(e.responses().toString()).contains("Widget");
        assertThat(m.blindSpots().toString()).doesNotContain("Page");
    }

    @Test
    void unwrapsCompletableFutureToInner(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Order.java"), "package demo; public class Order { public String id; }");
        ApiModel m = extract(dir, "AsyncCtrl",
                "@RestController class AsyncCtrl { @PostMapping(\"/o\") CompletableFuture<Order> g(){return null;} }");
        assertThat(m.blindSpots().toString()).doesNotContain("CompletableFuture");
        assertThat(m.endpoints().get(0).responses().toString()).contains("Order");
    }

    @Test
    void rawResponseEntityIsNotEmittedAsASchema(@TempDir Path dir) throws Exception {
        ApiModel m = extract(dir, "RawCtrl",
                "@RestController class RawCtrl { @PostMapping(\"/c\") ResponseEntity c(){return null;} }");
        assertThat(m.schemas()).doesNotContainKey("ResponseEntity");
        assertThat(m.blindSpots().toString()).doesNotContain("ResponseEntity");
    }

    @Test
    void placeholderPathRecordsBlindSpot(@TempDir Path dir) throws Exception {
        ApiModel m = extract(dir, "PhCtrl",
                "@RestController class PhCtrl { @GetMapping(\"${api.base}/x\") String g(){return null;} }");
        assertThat(m.blindSpots().toString()).contains("placeholder");
    }

    @Test
    void resolvesConstantPathFromSources(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Routes.java"),
                "package demo; public class Routes { public static final String RENEW = \"/renew\"; }");
        ApiModel m = extract(dir, "ConstCtrl",
                "@RestController class ConstCtrl { @GetMapping(Routes.RENEW) String g(){return null;} }");
        assertThat(pathOf(m)).isEqualTo("/renew");                       // resolved, not "/Routes.RENEW"
        assertThat(m.blindSpots().toString()).doesNotContain("constant");
    }

    @Test
    void emitsAllPathsForMultiPathMapping(@TempDir Path dir) throws Exception {
        ApiModel m = extract(dir, "MultiCtrl",
                "@RestController class MultiCtrl { @GetMapping({\"/a\",\"/b\"}) String g(){return null;} }");
        assertThat(m.endpoints()).extracting(Endpoint::pathTemplate).contains("/a", "/b");
    }

    @Test
    void emitsAllMethodsForMultiMethodMapping(@TempDir Path dir) throws Exception {
        ApiModel m = extract(dir, "MmCtrl",
                "@RestController class MmCtrl { @RequestMapping(value=\"/multi\","
                        + " method={RequestMethod.PUT, RequestMethod.PATCH}) String g(){return null;} }");
        assertThat(m.endpoints()).extracting(e -> e.method().name()).contains("PUT", "PATCH");
    }

    @Test
    void interfaceDeclaredMappingsRecordBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Api.java"),
                "package demo;\nimport org.springframework.web.bind.annotation.*;\n"
                        + "public interface Api { @GetMapping(\"/ping\") String ping(); }");
        ApiModel m = extract(dir, "ImplCtrl",
                "@RestController class ImplCtrl implements Api { public String ping(){return null;} }");
        assertThat(m.blindSpots().toString()).contains("interfaces are not analysed");
    }
}
