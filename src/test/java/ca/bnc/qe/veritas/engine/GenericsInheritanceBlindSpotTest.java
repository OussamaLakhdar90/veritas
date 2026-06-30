package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generics/inheritance/routing blind spots a discovery pass confirmed: a generic base-controller type variable was
 * silently dropped (resolved as a ResolvedTypeVariable, so the unresolved-type blind spot was skipped); the
 * extends-an-unscanned-base blind spot wasn't recognized by codeExtractionIncomplete (false dead-spec); and a MIXED
 * controller (own endpoint + mapped interface) dropped the interface routes AND suppressed the interface blind spot.
 */
class GenericsInheritanceBlindSpotTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void genericBaseControllerTypeVariableSurfacesABlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "WidgetDto.java", "public class WidgetDto { public String id; }");
        write(dir, "AbstractCrudController.java", """
            import org.springframework.web.bind.annotation.*;
            public abstract class AbstractCrudController<T> {
                @GetMapping("/{id}") public T getOne(@PathVariable Long id) { return null; }
                @PostMapping public T create(@RequestBody T body) { return null; }
            }
            """);
        write(dir, "WidgetController.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/widgets")
            public class WidgetController extends AbstractCrudController<WidgetDto> {}
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        // the unbindable type variable T is surfaced as a blind spot rather than silently dropped
        assertThat(m.blindSpots().toString()).contains("'T'");
    }

    @Test
    void extendsUnscannedBaseIsRecognizedAsIncompleteNotDeadSpec(@TempDir Path dir) throws Exception {
        write(dir, "FooController.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/foo")
            public class FooController extends com.acme.ExternalCrudBase<String> {}
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        assertThat(code.blindSpots().toString()).contains("not in the scanned sources");

        // the spec documents base-declared endpoints → must fold into the single LOW incomplete summary, NOT MEDIUM dead-spec
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        Endpoint specGet = new Endpoint(HttpMethod.GET, "/foo/{id}", "op", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of(), src);
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(specGet), Map.of());

        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.EXTRA_ENDPOINT
                        && f.getSummary() != null && f.getSummary().contains("not found in code (dead spec?)"));
    }

    @Test
    void mixedControllerWithOwnEndpointAndMappedInterfaceSurfacesABlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "ReadApi.java", """
            import org.springframework.web.bind.annotation.*;
            public interface ReadApi { @GetMapping("/read") default String read() { return "r"; } }
            """);
        write(dir, "MixedController.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/mixed")
            public class MixedController implements ReadApi {
                @PostMapping("/write") public String write(@RequestBody String b) { return b; }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        // own endpoint POST /mixed/write is emitted, AND the interface route is surfaced as a blind spot (not dropped)
        assertThat(m.endpoints()).anySatisfy(e -> assertThat(e.signature()).contains("/mixed/write"));
        assertThat(m.blindSpots().toString()).contains("mappings declared on interfaces are not analysed");
    }
}
