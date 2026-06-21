package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Audit record for a human-approval gate on an outward action (create defect, create/update Xray, PR). */
@Entity
@Table(name = "gate_decision", indexes = @Index(name = "idx_gate_status", columnList = "status"))
@Getter
@Setter
public class GateDecision extends AuditableEntity {

    private String runId;
    private String action;          // CREATE_DEFECT | CREATE_TESTS | UPDATE_TESTS | OPEN_PR | ...
    private String status;          // PENDING | APPROVED | REJECTED
    private String approver;
    private Instant decidedAt;

    @Column(length = 1000)
    private String note;
}
