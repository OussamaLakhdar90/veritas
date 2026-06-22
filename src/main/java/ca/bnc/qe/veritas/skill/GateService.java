package ca.bnc.qe.veritas.skill;

import java.time.Instant;
import ca.bnc.qe.veritas.persistence.GateDecision;
import ca.bnc.qe.veritas.persistence.GateDecisionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Human-approval gate for outward actions (Jira/Xray writes, git PR). Every gated action is persisted as a
 * {@link GateDecision} (audit: action, approver, timestamp). When {@code veritas.gate.auto-approve=true}
 * (default for local/CLI, where the invoking command IS the approval) the decision is auto-approved; set it
 * to {@code false} (server mode) to require explicit approval via the gate API before the action proceeds.
 */
@Service
@Slf4j
public class GateService {

    @Value("${veritas.gate.auto-approve:true}")
    private boolean autoApprove;

    private final GateDecisionRepository repository;

    public GateService(GateDecisionRepository repository) {
        this.repository = repository;
    }

    public Decision await(String runId, String action, String requester) {
        GateDecision decision = new GateDecision();
        decision.setRunId(runId);
        decision.setAction(action);
        if (autoApprove) {
            decision.setStatus("APPROVED");
            decision.setApprover(requester == null ? "auto" : requester);
            decision.setDecidedAt(Instant.now());
        } else {
            decision.setStatus("PENDING");
        }
        decision = repository.save(decision);
        if (!autoApprove) {
            log.info("Gate {} PENDING approval for action {} (run {})", decision.getId(), action, runId);
        }
        return new Decision(autoApprove, decision.getId(), decision.getStatus());
    }

    public GateDecision approve(String gateId, String approver) {
        GateDecision d = requirePending(gateId);
        d.setStatus("APPROVED");
        d.setApprover(approver);
        d.setDecidedAt(Instant.now());
        return repository.save(d);
    }

    public GateDecision reject(String gateId, String approver, String note) {
        GateDecision d = requirePending(gateId);
        d.setStatus("REJECTED");
        d.setApprover(approver);
        d.setNote(note);
        d.setDecidedAt(Instant.now());
        return repository.save(d);
    }

    /** Only a PENDING gate may be decided — prevents flipping an already-decided (e.g. REJECTED) gate. */
    private GateDecision requirePending(String gateId) {
        GateDecision d = repository.findById(gateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gate " + gateId));
        if (!"PENDING".equals(d.getStatus())) {
            throw new ConflictException("Gate " + gateId + " is already " + d.getStatus()
                    + "; only a PENDING gate can be approved or rejected.");
        }
        return d;
    }

    public record Decision(boolean approved, String gateId, String status) {}
}
