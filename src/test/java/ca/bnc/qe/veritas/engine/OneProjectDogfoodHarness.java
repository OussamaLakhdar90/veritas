package ca.bnc.qe.veritas.engine;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import org.junit.jupiter.api.Test;

/**
 * Generic single-project dogfood harness: runs the REAL JavaSpringExtractor over one cloned repo and prints every
 * extracted endpoint (method, full path, response codes, request-body DTO), the DTO list, and the blind spots — so the
 * extraction can be graded against hand-built ground truth. Point it at a repo root with {@code -Dproj=<path>}
 * (optionally a sub-path with {@code -Dproj.src=...}). Guarded so CI skips it.
 */
class OneProjectDogfoodHarness {

    @Test
    void extractOne() throws Exception {
        String proj = System.getProperty("proj");
        assumeTrue(proj != null, "set -Dproj=<repo root>");
        Path src = Path.of(proj, System.getProperty("proj.src", "."));
        assumeTrue(Files.isDirectory(src), "not a dir: " + src);

        ApiModel m = new JavaSpringExtractor().extract(src);
        System.out.println("\n@@@@ PROJECT " + proj + " @@@@");
        System.out.println("@@@@ ENDPOINTS=" + m.endpoints().size() + " DTOS=" + m.schemas().size()
                + " BLINDSPOTS=" + m.blindSpots().size() + " @@@@");
        for (Endpoint e : m.endpoints()) {
            System.out.println("EP| " + e.signature()
                    + " | responses=" + e.responses().stream().map(r -> String.valueOf(r.statusCode())).toList()
                    + (e.requestBody() != null && e.requestBody().schemaRef() != null
                    ? " | body=" + e.requestBody().schemaRef() : ""));
        }
        for (String dto : m.schemas().keySet()) {
            System.out.println("DTO| " + dto);
        }
        for (String b : m.blindSpots()) {
            System.out.println("BLIND| " + b);
        }
        System.out.println("@@@@ END @@@@");
    }
}
