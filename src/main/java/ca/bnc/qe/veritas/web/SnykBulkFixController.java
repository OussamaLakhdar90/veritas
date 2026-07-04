package ca.bnc.qe.veritas.web;

import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixRequest;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixResult;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "Fix vulnerabilities" batch endpoint: files one epic + one ticket per app (under the epic) and starts a fix
 * train per selected vulnerability. Returns 202 — the epic + app tickets are created synchronously (a few Jira
 * calls), then each train runs on the fix pool. A missing Jira token surfaces as the shared 422 secret-required.
 */
@RestController
@RequestMapping("/api/v1")
public class SnykBulkFixController {

    private final SnykBulkFixService service;

    public SnykBulkFixController(SnykBulkFixService service) {
        this.service = service;
    }

    @PostMapping("/snyk/fixes/bulk")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SnykBulkFixResult bulkFix(@RequestBody SnykBulkFixRequest req) {
        return service.launch(req);
    }
}
