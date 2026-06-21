package ca.bnc.qe.veritas.cost;

/** The estimated cost of a single LLM step. Persisted on {@code RunStep} and summed on {@code SkillRun}. */
public record CostResult(
        String model,
        BillingMode billingMode,
        double premiumRequests,
        long estTokensIn,
        long estTokensOut,
        double estCostUsd
) {
    public static CostResult zero(String model) {
        return new CostResult(model, BillingMode.PER_REQUEST, 0, 0, 0, 0.0);
    }
}
