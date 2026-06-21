package ca.bnc.qe.veritas.cost;

import org.springframework.stereotype.Service;

/**
 * Estimates the dollar cost of one LLM call under whichever billing mode is configured.
 *
 * <ul>
 *   <li><b>PER_REQUEST</b> (legacy): one {@code copilot -p} = {@code max(1, multiplier)} premium requests
 *       when the CLI floor applies, times {@code pricePerRequestUsd}.</li>
 *   <li><b>USAGE_CREDITS</b> (>= 2026-06): token-based credits. The CLI doesn't reliably expose token
 *       counts, so tokens are estimated from character length (~4 chars/token) until real counts exist.</li>
 * </ul>
 */
@Service
public class CostEstimator {

    private final ModelCatalog catalog;
    private final LiveModelMultipliers live;

    /** Spring uses this — live multipliers from Copilot's /models override the static catalog when present. */
    @org.springframework.beans.factory.annotation.Autowired
    public CostEstimator(ModelCatalog catalog, LiveModelMultipliers live) {
        this.catalog = catalog;
        this.live = live;
    }

    /** Convenience for unit tests: no live multipliers (static catalog only). */
    public CostEstimator(ModelCatalog catalog) {
        this(catalog, new LiveModelMultipliers());
    }

    public CostResult estimate(String modelId, String prompt, String response) {
        long tokensIn = estimateTokens(prompt);
        long tokensOut = estimateTokens(response);
        ModelSpec spec = catalog.find(modelId).orElse(null);
        BillingMode mode = catalog.mode();

        if (mode == BillingMode.USAGE_CREDITS) {
            double inRate = spec != null ? spec.creditsPerMTokIn() : 0.0;
            double outRate = spec != null ? spec.creditsPerMTokOut() : 0.0;
            double credits = (tokensIn / 1_000_000.0) * inRate + (tokensOut / 1_000_000.0) * outRate;
            double cost = credits * catalog.creditUsd();
            return new CostResult(modelId, mode, 0.0, tokensIn, tokensOut, round(cost));
        }

        double staticMultiplier = spec != null ? spec.requestMultiplier() : 1.0;
        double multiplier = live.multiplier(modelId).orElse(staticMultiplier);   // live /models wins when present
        double requests = catalog.cliCounts() ? Math.max(1.0, multiplier) : multiplier;
        double cost = requests * catalog.pricePerRequestUsd();
        return new CostResult(modelId, mode, requests, tokensIn, tokensOut, round(cost));
    }

    /** ~4 characters per token — a planning heuristic until the CLI exposes real usage. */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + 3) / 4;
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
