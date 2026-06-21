package ca.bnc.qe.veritas.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CostEstimatorTest {

    private ModelCatalog catalog(BillingMode mode, double creditUsd) {
        return new ModelCatalog(mode, 0.04, creditUsd, true, List.of(
                new ModelSpec("gpt-4.1", ModelTier.ECONOMY, 0.0, 0.0, 0.0, null),
                new ModelSpec("claude-sonnet-4", ModelTier.STANDARD, 1.0, 0.0, 0.0, null),
                new ModelSpec("claude-opus-4", ModelTier.FRONTIER, 10.0, 0.0, 0.0, null),
                new ModelSpec("gemini", ModelTier.ECONOMY, 0.25, 2.0, 8.0, null)));
    }

    @Test
    void perRequest_cliFloorsZeroMultiplierToOne() {
        CostResult r = new CostEstimator(catalog(BillingMode.PER_REQUEST, 0.0)).estimate("gpt-4.1", "prompt", "resp");
        assertThat(r.premiumRequests()).isEqualTo(1.0);
        assertThat(r.estCostUsd()).isEqualTo(0.04);
    }

    @Test
    void perRequest_appliesMultiplier() {
        CostResult r = new CostEstimator(catalog(BillingMode.PER_REQUEST, 0.0)).estimate("claude-opus-4", "p", "r");
        assertThat(r.premiumRequests()).isEqualTo(10.0);
        assertThat(r.estCostUsd()).isEqualTo(0.40);
    }

    @Test
    void usageCredits_estimatesTokensAndZeroRequests() {
        CostResult r = new CostEstimator(catalog(BillingMode.USAGE_CREDITS, 1.0)).estimate("gemini", "aaaaaaaa", "bbbb");
        assertThat(r.billingMode()).isEqualTo(BillingMode.USAGE_CREDITS);
        assertThat(r.premiumRequests()).isEqualTo(0.0);
        assertThat(r.estTokensIn()).isEqualTo(2);   // (8+3)/4
        assertThat(r.estTokensOut()).isEqualTo(1);  // (4+3)/4
        assertThat(r.estCostUsd()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void unknownModel_defaultsToMultiplierOne() {
        CostResult r = new CostEstimator(catalog(BillingMode.PER_REQUEST, 0.0)).estimate("unknown", "p", "r");
        assertThat(r.premiumRequests()).isEqualTo(1.0);
        assertThat(r.estCostUsd()).isEqualTo(0.04);
    }
}
