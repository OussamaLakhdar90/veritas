package ca.bnc.qe.veritas.engine;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.finding.Finding;
import org.junit.jupiter.api.Test;

/**
 * Dogfood harness — runs Veritas's REAL deterministic engines (JavaSpringExtractor + OpenApiModelExtractor +
 * DiffEngine) over real cloned GitHub projects, printing what it extracts so the result can be validated by hand.
 * Guarded by {@code -Ddogfood.root=<dir of clones>} so it is a no-op in CI.
 */
class RealWorldDogfoodHarness {

    private record Proj(String dir, String srcRel, String specRel) {}

    @Test
    void runOverRealProjects() throws Exception {
        String root = System.getProperty("dogfood.root");
        assumeTrue(root != null && Files.isDirectory(Path.of(root)), "set -Ddogfood.root to the clones dir");

        JavaSpringExtractor codeExtractor = new JavaSpringExtractor();
        OpenApiModelExtractor specExtractor = new OpenApiModelExtractor();
        DiffEngine diff = new DiffEngine();

        List<Proj> projects = List.of(
                new Proj("spring-petclinic-rest", "src/main/java", "src/main/resources/openapi.yml"),
                new Proj("spring-boot-realworld-example-app", "src/main/java", null),
                new Proj("piggymetrics", ".", null));   // multi-module → scan the whole repo

        for (Proj p : projects) {
            Path src = Path.of(root, p.dir(), p.srcRel());
            System.out.println("\n############ " + p.dir() + " ############");
            if (!Files.isDirectory(src)) {
                System.out.println("  (source dir not found: " + src + ")");
                continue;
            }
            ApiModel code = codeExtractor.extract(src);
            System.out.println("EXTRACTED CODE MODEL: " + code.endpoints().size() + " endpoints, "
                    + code.schemas().size() + " DTOs, " + code.blindSpots().size() + " blind spots");
            for (Endpoint e : code.endpoints()) {
                System.out.println("  " + e.signature()
                        + "  [responses " + e.responses().stream().map(r -> String.valueOf(r.statusCode())).toList() + "]"
                        + (e.requestBody() != null && e.requestBody().schemaRef() != null
                        ? " body=" + e.requestBody().schemaRef() : ""));
            }
            if (!code.blindSpots().isEmpty()) {
                System.out.println("  blind spots: " + code.blindSpots());
            }

            if (p.specRel() != null) {
                Path specPath = Path.of(root, p.dir(), p.specRel());
                if (Files.exists(specPath)) {
                    var parse = specExtractor.extract("spec", Files.readString(specPath));
                    ApiModel spec = parse.model();
                    System.out.println("EXTRACTED SPEC MODEL: " + spec.endpoints().size() + " endpoints, "
                            + spec.schemas().size() + " schemas (parsed=" + parse.parsed() + ")");
                    List<Finding> findings = diff.diffCodeVsSpec(code, spec);
                    System.out.println("CONTRACT DIFF (code vs spec): " + findings.size() + " deterministic finding(s)");
                    for (Finding f : findings) {
                        System.out.println("  - " + f);
                    }
                }
            }
        }
        System.out.println("\n############ end dogfood ############");
    }
}
