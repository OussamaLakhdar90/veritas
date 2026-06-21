package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Revision workflow: generate (v1) → revise a section (immutable v2) → approve, with version history kept. */
@SpringBootTest
class TestStrategyRevisionTest {

    @Autowired private TestStrategyService service;
    @Autowired private TestStrategyRepository repository;

    @Test
    void generateReviseApproveKeepsVersionHistory() {
        TestStrategy v1 = service.generate("svc-rev", "Endpoints:\n- GET /policies\n", "CODE", "tester");
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v1.getLineageId()).isEqualTo(v1.getId());
        assertThat(v1.getStatus()).isEqualTo("DRAFT");

        TestStrategy v2 = service.reviseSection(v1.getId(), "summary", "\"revised by the test manager\"", "qa-lead");
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getLineageId()).isEqualTo(v1.getId());
        assertThat(v2.getRevisedBy()).isEqualTo("qa-lead");
        assertThat(v2.getDeliverableJson()).contains("revised by the test manager");

        TestStrategy approved = service.approve(v2.getId(), "manager");
        assertThat(approved.getStatus()).isEqualTo("APPROVED");

        List<TestStrategy> versions = repository.findByLineageIdOrderByVersionDesc(v1.getId());
        assertThat(versions).hasSize(2);                         // both versions retained (immutable history)
        assertThat(versions.get(0).getVersion()).isEqualTo(2);  // newest first
    }
}
