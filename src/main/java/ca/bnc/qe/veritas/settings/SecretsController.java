package ca.bnc.qe.veritas.settings;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** In-app token configuration. Write-only for values; GET reports only which keys are set. */
@RestController
@RequestMapping("/api/v1/settings/secrets")
public class SecretsController {

    private final SecretWriteService service;

    public SecretsController(SecretWriteService service) {
        this.service = service;
    }

    /** {key -> isSet} for every known secret. Never returns a value. */
    @GetMapping
    public Map<String, Boolean> status() {
        return service.status();
    }

    @PostMapping
    public ResponseEntity<Void> set(@RequestBody SetSecretRequest request) {
        service.set(request.key(), request.value());
        return ResponseEntity.noContent().build();   // 204 — never echo the value back
    }

    public record SetSecretRequest(String key, String value) {
    }
}
