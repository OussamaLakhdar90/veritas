package ca.bnc.qe.veritas.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** In-app connection configuration (base URL / edition / workspace / auth-type per integration; no secrets). */
@RestController
@RequestMapping("/api/v1/settings/connections")
public class ConnectionsController {

    private final ConnectionsConfigService service;

    public ConnectionsController(ConnectionsConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ConnectionsView current() {
        return service.current();
    }

    @PutMapping
    public UpdateConnectionsResponse update(@RequestBody ConnectionsView connections) {
        return service.update(connections);
    }
}
