package ca.bnc.qe.veritas.web;

import ca.bnc.qe.veritas.execution.ExecutionService;
import ca.bnc.qe.veritas.execution.ExecutionSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-completion: read the latest execution status of a JQL's tests back from Xray and return a completion summary
 * (passed/failed/blocked/not-run + deviations). Read-only; the Xray read is heavily logged for live debugging.
 */
@RestController
@RequestMapping("/api/v1")
public class ExecutionController {

    private final ExecutionService execution;

    public ExecutionController(ExecutionService execution) {
        this.execution = execution;
    }

    @GetMapping("/execution/completion")
    public ExecutionSummary completion(@RequestParam String jql,
                                       @RequestParam(required = false) String service,
                                       @RequestParam(required = false, defaultValue = "api") String owner) {
        return execution.completion(service, jql, owner);
    }
}
