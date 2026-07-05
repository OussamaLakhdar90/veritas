package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Type-fidelity fixes the deep review confirmed: collection/array params were typed "object" (false PARAM_TYPE_MISMATCH
 * vs an array spec); @Size on a collection became a bogus minLength (false CONSTRAINT_GAP); a @RequestParam Map bind-all
 * became a phantom param; and a nested-brace regex path var corrupted the pathTemplate.
 */
class JavaSpringExtractorTypeFidelityTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static ApiModel extract(Path dir) {
        return new JavaSpringExtractor().extract(dir);
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).hasSize(1);
        return m.endpoints().get(0);
    }

    @Test
    void collectionAndArrayParamsAreTypedArray(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import java.util.List;
            @RestController class C {
                @GetMapping("/x") public Object x(@RequestParam List<String> tags, @RequestParam String[] ids) {
                    return null;
                }
            }
            """);
        var params = only(extract(dir)).params();
        assertThat(params).filteredOn(p -> p.name().equals("tags")).singleElement()
                .satisfies(p -> assertThat(p.type()).isEqualTo("array"));
        assertThat(params).filteredOn(p -> p.name().equals("ids")).singleElement()
                .satisfies(p -> assertThat(p.type()).isEqualTo("array"));
    }

    @Test
    void sizeOnACollectionFieldIsNotAMinLengthConstraint(@TempDir Path dir) throws Exception {
        write(dir, "Dto.java", """
            import jakarta.validation.constraints.Size;
            import java.util.List;
            class Dto { @Size(min = 1, max = 5) public List<String> tags; }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/x") public Dto x(@RequestBody Dto d) { return null; } }
            """);
        var tags = extract(dir).schemas().get("Dto").fields().stream()
                .filter(f -> f.jsonName().equals("tags")).findFirst().orElseThrow();
        assertThat(tags.type()).isEqualTo("array");
        assertThat(tags.constraints().minLength()).isNull();
        assertThat(tags.constraints().maxLength()).isNull();
    }

    @Test
    void scalarCollectionFieldsCarryTheirElementType(@TempDir Path dir) throws Exception {
        // Regression: a collection of a SCALAR (List<String>, String[], …) dropped its element type — modelled as a
        // bare type:array with no items, which the corrected YAML then rendered self-contradictorily against a String[]
        // field. The element's OpenAPI type is now carried via the refSchema "<type>[]" convention (DTO arrays keep
        // "<Dto>[]"; an unmappable element stays null). byte[] remains a binary string payload, not an array.
        write(dir, "Dto.java", """
            import java.util.List;
            class Dto {
                public List<String> tags;
                public List<Integer> counts;
                public List<Boolean> flags;
                public String[] codes;
                public List<UnknownThing> misc;
                public byte[] blob;
            }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/x") public Dto x(@RequestBody Dto d) { return null; } }
            """);
        var fields = extract(dir).schemas().get("Dto").fields();
        assertThat(field(fields, "tags").refSchema()).isEqualTo("string[]");
        assertThat(field(fields, "counts").refSchema()).isEqualTo("integer[]");
        assertThat(field(fields, "flags").refSchema()).isEqualTo("boolean[]");
        assertThat(field(fields, "codes").refSchema()).isEqualTo("string[]");
        // An element type that doesn't map to a scalar (unknown, not a scanned DTO) stays a bare array — no invented
        // items:{type:object} that could false-diff against a faithful spec.
        assertThat(field(fields, "misc").refSchema()).isNull();
        // byte[] is a base64 STRING payload, not a JSON array — unchanged by the element-type fix.
        FieldModel blob = field(fields, "blob");
        assertThat(blob.type()).isEqualTo("string");
        assertThat(blob.format()).isEqualTo("byte");
        assertThat(blob.refSchema()).isNull();
    }

    private static FieldModel field(List<FieldModel> fields, String name) {
        return fields.stream().filter(f -> f.jsonName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void requestParamMapBindAllSurfacesABlindSpotNotAParam(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import java.util.Map;
            @RestController class C {
                @GetMapping("/x") public Object x(@RequestParam Map<String, String> all) { return null; }
            }
            """);
        ApiModel m = extract(dir);
        assertThat(only(m).params()).noneSatisfy(p -> assertThat(p.name()).isEqualTo("all"));
        assertThat(m.blindSpots()).anySatisfy(b -> assertThat(b).contains("binds all query params"));
    }

    @Test
    void nestedBraceRegexPathVarIsNormalizedCleanly(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C {
                @GetMapping("/u/{id:[0-9]{2}}") public Object x(@PathVariable String id) { return null; }
            }
            """);
        assertThat(only(extract(dir)).pathTemplate()).isEqualTo("/u/{id}");   // was "/u/{id}}"
    }
}
