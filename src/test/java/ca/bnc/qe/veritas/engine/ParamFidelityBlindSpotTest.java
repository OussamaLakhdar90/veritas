package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Param-fidelity blind spots a discovery pass confirmed: header names matched case-sensitively (false PARAM_MISSING +
 * PARAM_EXTRA for the same header, RFC 9110 says header names are case-insensitive); and an implicit @ModelAttribute
 * command object + a @MatrixVariable were silently dropped (no param, no blind spot).
 */
class ParamFidelityBlindSpotTest {

    private static final SourceRef SRC = SourceRef.code("X.java", 1, 1, "x");

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static ParamModel header(String name) {
        return new ParamModel(name, ParamLocation.HEADER, "string", null, true, ConstraintSet.empty(), SRC);
    }

    private static Endpoint get(String path, ParamModel... params) {
        return new Endpoint(HttpMethod.GET, path, "op", List.of(params), null,
                List.of(), null, null, List.of(), SRC);
    }

    private static ApiModel model(String src, Endpoint ep) {
        return new ApiModel(src, null, null, null, List.of(ep), Map.of());
    }

    @Test
    void headerNamesAreMatchedCaseInsensitively() {
        ApiModel code = model("code", get("/ping", header("X-Trace-ID")));
        ApiModel spec = model("repo-spec", get("/ping", header("X-Trace-Id")));   // differs only by casing

        List<Finding> fs = new DiffEngine().diffCodeVsSpec(code, spec);
        assertThat(fs).noneMatch(f -> f.getType() == FindingType.PARAM_MISSING
                || f.getType() == FindingType.PARAM_EXTRA);
    }

    @Test
    void differentQueryParamCasingStillDiffs() {
        ParamModel codeQ = new ParamModel("id", ParamLocation.QUERY, "string", null, true, ConstraintSet.empty(), SRC);
        ParamModel specQ = new ParamModel("ID", ParamLocation.QUERY, "string", null, true, ConstraintSet.empty(), SRC);
        ApiModel code = model("code", get("/x", codeQ));
        ApiModel spec = model("repo-spec", get("/x", specQ));

        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .anyMatch(f -> f.getType() == FindingType.PARAM_MISSING || f.getType() == FindingType.PARAM_EXTRA);
    }

    @Test
    void implicitModelAttributeCommandObjectSurfacesABlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "SearchCriteria.java", "public class SearchCriteria { public String name; public String city; }");
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/search") public Object a(SearchCriteria criteria) { return null; } }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints().get(0).params()).noneSatisfy(p -> assertThat(p.name()).isEqualTo("criteria"));
        assertThat(m.blindSpots().toString()).contains("implicit").contains("command object");
    }

    @Test
    void matrixVariableSurfacesABlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C {
                @GetMapping("/cars/{brand}")
                public Object a(@PathVariable String brand, @MatrixVariable int year) { return null; }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.blindSpots().toString()).contains("MatrixVariable");
    }

    @Test
    void injectedTypeParamIsNotTreatedAsACommandObject(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import jakarta.servlet.http.HttpServletRequest;
            @RestController class C { @GetMapping("/x") public Object a(HttpServletRequest req) { return null; } }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.blindSpots().toString()).doesNotContain("command object");
    }
}
