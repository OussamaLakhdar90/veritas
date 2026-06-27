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

    /**
     * Gate an outward action. In auto-approve mode (local/CLI) the decision is recorded APPROVED and the action
     * proceeds immediately. In server mode this is two-phase and idempotent on re-call:
     * <ol>
     *   <li>if a human already APPROVED this {@code (runId, action)}, consume that approval and proceed;</li>
     *   <li>else if a PENDING gate already exists for it, return that one (re-clicking does not mint duplicates);</li>
     *   <li>else create a fresh PENDING gate and block until it is approved.</li>
     * </ol>
     * One approval authorises exactly one action (the gate is marked CONSUMED), so a later identical action requires
     * a fresh approval — the bank's "every outward write is individually approved" posture.
     */
    public Decision await(String runId, String action, String requester) {
        if (autoApprove) {
            GateDecision decision = new GateDecision();
            decision.setRunId(runId);
            decision.setAction(action);
            decision.setStatus("APPROVED");
            decision.setApprover(requester == null ? "auto" : requester);
            decision.setDecidedAt(Instant.now());
            decision = repository.save(decision);
            return new Decision(true, decision.getId(), "APPROVED");
        }

        // Server mode: resume an approval if one is waiting (this is what makes the gate actually completable).
        var approved = repository.findFirstByRunIdAndActionAndStatusOrderByCreatedAtDesc(runId, action, "APPROVED");
        if (approved.isPresent()) {
            GateDecision g = approved.get();
            g.setStatus("CONSUMED");   // one approval → one action; a later identical action needs a fresh approval
            repository.save(g);
            log.info("Gate {} APPROVED — proceeding with {} (run {})", g.getId(), action, runId);
            return new Decision(true, g.getId(), "APPROVED");
        }
        // Don't mint a duplicate PENDING gate when the user re-triggers the action while it's still awaiting approval.
        var pending = repository.findFirstByRunIdAndActionAndStatusOrderByCreatedAtDesc(runId, action, "PENDING");
        if (pending.isPresent()) {
            return new Decision(false, pending.get().getId(), "PENDING");
        }
        GateDecision decision = new GateDecision();
        decision.setRunId(runId);
        decision.setAction(action);
        decision.setStatus("PENDING");
        decision = repository.save(decision);
        log.info("Gate {} PENDING approval for action {} (run {})", decision.getId(), action, runId);
        return new Decision(false, decision.getId(), "PENDING");
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
