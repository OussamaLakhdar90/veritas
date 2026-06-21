package ca.bnc.qe.veritas.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Locks the researched June-2026 GitHub Copilot billing math:
 * USAGE_CREDITS = per-token → AI credits (1 credit = $0.01); PER_REQUEST = multiplier × $/request (legacy).
 */
class CopilotPricingTest {

    @Test
    void usageCreditsCostMatchesPerMillionTokenRates() {
        // Sonnet 4.6: $3/1M in, $15/1M out → 300 / 1500 credits per 1M (1 credit = $0.01).
        ModelCatalog cat = new ModelCatalog(BillingMode.USAGE_CREDITS, 0.04, 0.01, true,
                List.of(new ModelSpec("claude-sonnet-4.6", ModelTier.STANDARD, 9, 300, 1500, true)));
        CostResult r = new CostEstimator(cat).estimate("claude-sonnet-4.6", "a".repeat(4000), "b".repeat(4000));
        assertThat(r.estTokensIn()).isEqualTo(1000);
        assertThat(r.estTokensOut()).isEqualTo(1000);
        // (1000/1e6*300 + 1000/1e6*1500) credits * $0.01 = (0.3 + 1.5) * 0.01 = $0.018
        assertThat(r.estCostUsd()).isEqualTo(0.018);
    }

    @Test
    void legacyPerRequestUsesMultiplier() {
        ModelCatalog cat = new ModelCatalog(BillingMode.PER_REQUEST, 0.04, 0.01, true,
                List.of(new ModelSpec("claude-opus-4.8", ModelTier.FRONTIER, 27, 500, 2500, true)));
        CostResult r = new CostEstimator(cat).estimate("claude-opus-4.8", "p", "r");
        assertThat(r.premiumRequests()).isEqualTo(27.0);     // 27× premium requests
        assertThat(r.estCostUsd()).isEqualTo(1.08);          // 27 × $0.04
    }

    @Test
    void realCatalogLoadsWithJune2026Rates() throws Exception {
        ModelCatalog cat = new ModelConfig().modelCatalog(new DefaultResourceLoader());
        assertThat(cat.mode()).isEqualTo(BillingMode.USAGE_CREDITS);
        assertThat(cat.creditUsd()).isEqualTo(0.01);
        assertThat(cat.find("claude-sonnet-4.6").orElseThrow().creditsPerMTokOut()).isEqualTo(1500);
        assertThat(cat.find("claude-opus-4.8").orElseThrow().requestMultiplier()).isEqualTo(27.0);
    }
}
