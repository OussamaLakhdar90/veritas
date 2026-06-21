package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One row per LLM action across ALL skills — the per-action cost ledger. Written every time a skill calls
 * the model, so real (non-echo) spend is visible to the cost API, not just the echo demo's RunStep rows.
 */
@Entity
@Table(name = "cost_entry", indexes = {
        @Index(name = "idx_cost_skill", columnList = "skill"),
        @Index(name = "idx_cost_created", columnList = "createdAt")
})
@Getter
@Setter
public class CostEntry extends AuditableEntity {

    private String skill;        // validate-contract | test-strategy | release-test-plan | create-test-cases | review-test-cases | implement-tests
    private String action;       // the step within the skill (reconcile, synthesize, generate, review, …)
    private String model;
    private String billingMode;  // PER_REQUEST | USAGE_CREDITS
    private double premiumRequests;
    private long estTokensIn;
    private long estTokensOut;
    private double estCostUsd;
    private String owner;
    private String refId;        // optional link to the produced artifact (scanId, planId, testCaseId, …)
}
