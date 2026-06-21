package ca.bnc.qe.veritas.web;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the generated management report HTML so the dashboard can link to it. */
@RestController
@RequestMapping("/api/v1")
public class ReportController {

    @GetMapping(value = "/scans/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(@PathVariable String id) throws Exception {
        Path file = Path.of("out", "contract-report-" + id + ".html");
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(Files.readString(file));
    }
}
