package ca.bnc.qe.veritas.cost;

import ca.bnc.qe.veritas.persistence.CostEntry;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import org.springframework.stereotype.Service;

/**
 * Records the cost of a single LLM action to the per-action ledger ({@link CostEntry}) and returns the
 * {@link CostResult}. Every skill calls this for every model call, so the cost API reflects real spend —
 * the explicit "cost per LLM, for every action done" requirement. Estimation is delegated to
 * {@link CostEstimator}; this adds the durable, queryable ledger row.
 */
@Service
public class CostRecorder {

    private final CostEstimator estimator;
    private final CostEntryRepository repository;
    private final ca.bnc.qe.veritas.llm.LlmCallContext callContext;

    public CostRecorder(CostEstimator estimator, CostEntryRepository repository,
                        ca.bnc.qe.veritas.llm.LlmCallContext callContext) {
        this.estimator = estimator;
        this.repository = repository;
        this.callContext = callContext;
    }

    public CostResult record(String skill, String action, String model, String prompt, String response, String owner) {
        return record(skill, action, model, prompt, response, owner, null);
    }

    public CostResult record(String skill, String action, String model, String prompt, String response,
                             String owner, String refId) {
        // A cache HIT spent no tokens — bill it as zero instead of a full-cost row that overstates spend.
        // (Action name is left unchanged so the ledger's action semantics hold; a zeroed cost is the hit signal.)
        boolean cached = callContext.consumeCached();
        CostResult cost = cached ? CostResult.zero(model) : estimator.estimate(model, prompt, response);
        CostEntry entry = new CostEntry();
        entry.setSkill(skill);
        entry.setAction(action);
        entry.setModel(cost.model());
        entry.setBillingMode(cost.billingMode() != null ? cost.billingMode().name() : null);
        entry.setPremiumRequests(cost.premiumRequests());
        entry.setEstTokensIn(cost.estTokensIn());
        entry.setEstTokensOut(cost.estTokensOut());
        entry.setEstCostUsd(cost.estCostUsd());
        entry.setOwner(owner);
        entry.setRefId(refId);
        repository.save(entry);
        return cost;
    }
}
