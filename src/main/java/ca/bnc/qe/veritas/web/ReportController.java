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

    /** Scan ids are UUIDs; constrain to a safe charset so {id} can never escape the report directory. */
    private static final java.util.regex.Pattern SAFE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @GetMapping(value = "/scans/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(@PathVariable String id) throws Exception {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            return ResponseEntity.notFound().build();   // reject path-traversal / unexpected ids outright
        }
        Path base = Path.of("out").toAbsolutePath().normalize();
        Path file = base.resolve("contract-report-" + id + ".html").normalize();
        // Defence in depth: the resolved file must stay under the report directory even if the charset check changed.
        if (!file.startsWith(base) || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(Files.readString(file));
    }
}
