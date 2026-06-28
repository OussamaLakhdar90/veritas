package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.defect.DefectService;
import ca.bnc.qe.veritas.defect.DefectSyncService;
import ca.bnc.qe.veritas.persistence.DefectLink;
import ca.bnc.qe.veritas.persistence.DefectLinkRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Read the linked Jira defects, create a defect from a finding, and refresh defect statuses (dashboard). */
@RestController
@RequestMapping("/api/v1")
public class DefectController {

    private final DefectService defectService;
    private final DefectSyncService syncService;
    private final DefectLinkRepository defectLinks;

    public DefectController(DefectService defectService, DefectSyncService syncService,
                            DefectLinkRepository defectLinks) {
        this.defectService = defectService;
        this.syncService = syncService;
        this.defectLinks = defectLinks;
    }

    /** All linked defects, newest first — backs the Defects dashboard page. */
    @GetMapping("/defects")
    public List<DefectLink> defects() {
        return defectLinks.findAllByOrderByCreatedAtDesc();
    }

    /** Aggregate defect metrics: totals, open/closed, and distributions by severity, status, and service. */
    @GetMapping("/defects/metrics")
    public ca.bnc.qe.veritas.defect.DefectMetrics metrics() {
        return ca.bnc.qe.veritas.defect.DefectMetrics.of(defectLinks.findAllByOrderByCreatedAtDesc());
    }

    @PostMapping("/findings/{id}/defect")
    @ResponseStatus(HttpStatus.ACCEPTED)   // gated Jira write (pending approval) — matches the other gated triggers
    public DefectLink createDefect(@PathVariable String id, @RequestBody DefectRequest req) {
        return defectService.createDefect(id, req.projectKey(), req.issueType(), "api");
    }

    /** Manually refresh the cached Jira status of every linked defect. */
    @PostMapping("/defects/sync")
    public Map<String, Integer> syncDefects() {
        return Map.of("updated", syncService.syncAll());
    }

    public record DefectRequest(String projectKey, String issueType) {}
}
