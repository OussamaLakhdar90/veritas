package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Three MEDIUM gaps a deep review confirmed: a base class's class-level @RequestMapping was dropped for inherited
 * handlers (silent wrong path); a nested object-ref-vs-scalar field flip was silently dropped; and the spec's flattened
 * query params false-diffed as PARAM_EXTRA against a code endpoint that binds them via a command object/Pageable.
 */
class DeepReviewRoutingDiffTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void baseClassRequestMappingAppliesToInheritedHandlers(@TempDir Path dir) throws Exception {
        write(dir, "AbstractCtrl.java", """
            import org.springframework.web.bind.annotation.*;
            @RequestMapping("/base")
            public abstract class AbstractCtrl { @GetMapping("/items") public String list() { return "x"; } }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController public class C extends AbstractCtrl {}
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints()).anySatisfy(e -> assertThat(e.pathTemplate()).isEqualTo("/base/items"));
    }

    @Test
    void nestedObjectRefVsScalarFieldFlipIsFlagged(@TempDir Path dir) throws Exception {
        write(dir, "Person.java", "public class Person { public String name; }");
        write(dir, "Wrapper.java", "public class Wrapper { public Person owner; }");
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/x") public Wrapper g() { return null; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        // spec: the SAME response is a differently-named schema whose `owner` field is a bare string, not an object.
        ApiModel spec = new OpenApiModelExtractor().extract("spec", """
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /x:
                get:
                  responses:
                    '200':
                      description: ok
                      content: { application/json: { schema: { $ref: '#/components/schemas/policies' } } }
            components:
              schemas:
                policies: { type: object, properties: { owner: { type: string } } }
            """).model();
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .anyMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_TYPE_MISMATCH
                        && f.getSummary() != null && f.getSummary().contains("owner"));
    }

    @Test
    void commandObjectFlattenedSpecParamsAreNotFalsePARAM_EXTRA(@TempDir Path dir) throws Exception {
        write(dir, "OwnerSearch.java", "public class OwnerSearch { public String firstName; public String lastName; }");
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/owners") public Object find(OwnerSearch criteria) { return null; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = new OpenApiModelExtractor().extract("spec", """
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /owners:
                get:
                  parameters:
                    - { name: firstName, in: query, schema: { type: string } }
                    - { name: lastName, in: query, schema: { type: string } }
                  responses: { '200': { description: ok } }
            """).model();
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.PARAM_EXTRA);
    }
}
