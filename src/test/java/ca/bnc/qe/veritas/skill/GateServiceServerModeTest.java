package ca.bnc.qe.veritas.skill;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.persistence.GateDecision;
import ca.bnc.qe.veritas.persistence.GateDecisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** Server mode (auto-approve=false): the gate must actually be completable — approving resumes the action. */
@SpringBootTest
@TestPropertySource(properties = "veritas.gate.auto-approve=false")
class GateServiceServerModeTest {

    @Autowired private GateService gateService;
    @Autowired private GateDecisionRepository repository;

    @Test
    void firstCallBlocksApprovalResumesAndOneApprovalAuthorisesOneAction() {
        String run = "srv-1";

        // 1. First call blocks: a PENDING gate, action does not proceed.
        GateService.Decision first = gateService.await(run, "OPEN_PR", "alice");
        assertThat(first.approved()).isFalse();
        assertThat(first.status()).isEqualTo("PENDING");

        // 2. Re-triggering while pending must NOT mint a duplicate — same gate id comes back.
        GateService.Decision retrigger = gateService.await(run, "OPEN_PR", "alice");
        assertThat(retrigger.approved()).isFalse();
        assertThat(retrigger.gateId()).isEqualTo(first.gateId());

        // 3. Human approves on the Gates page.
        gateService.approve(first.gateId(), "approver");

        // 4. The action is retried → now it proceeds (the bug was that this stayed blocked forever).
        GateService.Decision resumed = gateService.await(run, "OPEN_PR", "alice");
        assertThat(resumed.approved()).isTrue();
        assertThat(repository.findById(first.gateId()).orElseThrow().getStatus())
                .isEqualTo("CONSUMED");   // the approval was spent on this one action

        // 5. A later identical action needs a FRESH approval — the consumed one can't be reused.
        GateService.Decision again = gateService.await(run, "OPEN_PR", "alice");
        assertThat(again.approved()).isFalse();
        assertThat(again.gateId()).isNotEqualTo(first.gateId());
    }

    @Test
    void approvalsAreScopedToTheirOwnRunAndAction() {
        gateService.await("srv-2", "OPEN_PR", "alice");
        GateDecision pending = repository.findFirstByRunIdAndActionAndStatusOrderByCreatedAtDesc(
                "srv-2", "OPEN_PR", "PENDING").orElseThrow();
        gateService.approve(pending.getId(), "approver");

        // A different action on the same run is NOT covered by the OPEN_PR approval.
        assertThat(gateService.await("srv-2", "CREATE_DEFECT", "alice").approved()).isFalse();
        // A different run is NOT covered either.
        assertThat(gateService.await("srv-3", "OPEN_PR", "alice").approved()).isFalse();
    }
}
