package ca.bnc.qe.veritas.cost;

/**
 * The cost of a single LLM step. Persisted on {@code RunStep} and summed on {@code SkillRun}.
 * {@code tokensActual} = the token counts are the provider's real {@code usage}, not the ~4-chars/token estimate.
 */
public record CostResult(
        String model,
        BillingMode billingMode,
        double premiumRequests,
        long estTokensIn,
        long estTokensOut,
        double estCostUsd,
        boolean tokensActual
) {
    public static CostResult zero(String model) {
        return new CostResult(model, BillingMode.PER_REQUEST, 0, 0, 0, 0.0, false);
    }
}
