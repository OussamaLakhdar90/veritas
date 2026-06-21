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

    public CostRecorder(CostEstimator estimator, CostEntryRepository repository) {
        this.estimator = estimator;
        this.repository = repository;
    }

    public CostResult record(String skill, String action, String model, String prompt, String response, String owner) {
        return record(skill, action, model, prompt, response, owner, null);
    }

    public CostResult record(String skill, String action, String model, String prompt, String response,
                             String owner, String refId) {
        CostResult cost = estimator.estimate(model, prompt, response);
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
