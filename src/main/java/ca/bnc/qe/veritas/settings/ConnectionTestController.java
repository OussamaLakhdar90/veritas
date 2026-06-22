package ca.bnc.qe.veritas.settings;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live "Test Connection" — a read-only authenticated probe per integration. */
@RestController
@RequestMapping("/api/v1/settings/connections")
public class ConnectionTestController {

    private final ConnectionTester tester;

    public ConnectionTestController(ConnectionTester tester) {
        this.tester = tester;
    }

    @PostMapping("/{service}/test")
    public ConnectionTestResult test(@PathVariable String service) {
        return tester.test(service);
    }
}
