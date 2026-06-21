package ca.bnc.qe.veritas.skill;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.persistence.GateDecision;
import ca.bnc.qe.veritas.persistence.GateDecisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GateServiceTest {

    @Autowired
    private GateService gateService;

    @Autowired
    private GateDecisionRepository repository;

    @Test
    void autoApprovesAndAudits() {
        GateService.Decision d = gateService.await("run-1", "CREATE_DEFECT", "alice");

        assertThat(d.approved()).isTrue();           // default veritas.gate.auto-approve=true
        assertThat(d.gateId()).isNotBlank();
        GateDecision persisted = repository.findById(d.gateId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("APPROVED");
        assertThat(persisted.getApprover()).isEqualTo("alice");
        assertThat(persisted.getAction()).isEqualTo("CREATE_DEFECT");
    }

    @Test
    void approvesAPendingDecision() {
        GateDecision pending = newPending("run-2", "OPEN_PR");
        GateDecision approved = gateService.approve(pending.getId(), "bob");
        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getApprover()).isEqualTo("bob");
        assertThat(approved.getDecidedAt()).isNotNull();
    }

    @Test
    void rejectsAPendingDecision() {
        GateDecision pending = newPending("run-3", "OPEN_PR");
        GateDecision rejected = gateService.reject(pending.getId(), "carol", "not now");
        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getNote()).isEqualTo("not now");
    }

    @Test
    void cannotFlipAnAlreadyDecidedGate() {
        GateDecision pending = newPending("run-4", "OPEN_PR");
        gateService.reject(pending.getId(), "carol", "no");
        // a decided (REJECTED) gate must not be flippable to APPROVED
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateService.approve(pending.getId(), "mallory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already REJECTED");
    }

    private GateDecision newPending(String runId, String action) {
        GateDecision g = new GateDecision();
        g.setRunId(runId);
        g.setAction(action);
        g.setStatus("PENDING");
        return repository.save(g);
    }
}
