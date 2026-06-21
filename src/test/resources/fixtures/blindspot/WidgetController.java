package demo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns a DTO that is NOT present in the scanned sources, to exercise blind-spot recording. */
@RestController
@RequestMapping("/widgets")
public class WidgetController {

    @GetMapping("/{id}")
    public ResponseEntity<ExternalWidget> get(@PathVariable String id) {
        return ResponseEntity.ok(null);
    }
}
