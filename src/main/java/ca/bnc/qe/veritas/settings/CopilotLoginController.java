package ca.bnc.qe.veritas.settings;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** In-app GitHub→Copilot sign-in (device flow) so a non-technical user never touches the CLI. */
@RestController
@RequestMapping("/api/v1/settings/copilot")
public class CopilotLoginController {

    private final CopilotLoginService service;

    public CopilotLoginController(CopilotLoginService service) {
        this.service = service;
    }

    /** Whether a Copilot OAuth token is already stored. */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("authenticated", service.isAuthenticated());
    }

    /** Begin sign-in: returns the user code + verification URL to display. */
    @PostMapping("/login/start")
    public CopilotLoginStart start() {
        return service.start();
    }

    /** Poll a sign-in session: PENDING | AUTHORIZED | EXPIRED | ERROR. */
    @GetMapping("/login/status")
    public CopilotLoginStatus loginStatus(@RequestParam String id) {
        return service.status(id);
    }

    @PostMapping("/signout")
    public ResponseEntity<Void> signOut() {
        service.signOut();
        return ResponseEntity.noContent().build();
    }
}
