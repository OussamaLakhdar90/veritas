package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.persistence.GateDecision;
import ca.bnc.qe.veritas.persistence.GateDecisionRepository;
import ca.bnc.qe.veritas.skill.GateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Human-approval gate API: list pending decisions and approve/reject outward actions (audited). */
@RestController
@RequestMapping("/api/v1")
public class GateController {

    private final GateService gateService;
    private final GateDecisionRepository repository;

    public GateController(GateService gateService, GateDecisionRepository repository) {
        this.gateService = gateService;
        this.repository = repository;
    }

    @GetMapping("/gates")
    public List<GateDecision> gates(@RequestParam(defaultValue = "PENDING") String status) {
        return repository.findByStatusOrderByCreatedAtDesc(status);
    }

    @PostMapping("/gates/{id}/approve")
    public GateDecision approve(@PathVariable String id, @RequestBody(required = false) ApproveRequest req) {
        return gateService.approve(id, req != null && req.approver() != null ? req.approver() : "api");
    }

    @PostMapping("/gates/{id}/reject")
    public GateDecision reject(@PathVariable String id, @RequestBody(required = false) RejectRequest req) {
        return gateService.reject(id, req != null && req.approver() != null ? req.approver() : "api",
                req != null ? req.note() : null);
    }

    public record ApproveRequest(String approver) {}

    public record RejectRequest(String approver, String note) {}
}
