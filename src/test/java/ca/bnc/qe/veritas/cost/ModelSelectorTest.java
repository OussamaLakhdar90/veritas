package ca.bnc.qe.veritas.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.skill.Step;
import ca.bnc.qe.veritas.skill.StepKind;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ModelSelectorTest {

    private ModelSelector selector() {
        ModelCatalog catalog = new ModelCatalog(BillingMode.PER_REQUEST, 0.04, 0.0, true, List.of(
                new ModelSpec("gpt-4.1", ModelTier.ECONOMY, 0.0, 0.0, 0.0, null),
                new ModelSpec("gemini", ModelTier.ECONOMY, 0.25, 0.0, 0.0, null),
                new ModelSpec("claude-sonnet-4", ModelTier.STANDARD, 1.0, 0.0, 0.0, null),
                new ModelSpec("disabled-deep", ModelTier.DEEP, 5.0, 0.0, 0.0, false)));
        ModelPolicy policy = new ModelPolicy(Map.of(
                "economy", new ModelPolicy.TierPolicy("gpt-4.1", List.of("gemini")),
                "standard", new ModelPolicy.TierPolicy("claude-sonnet-4", List.of("gpt-4.1")),
                "deep", new ModelPolicy.TierPolicy("disabled-deep", List.of("claude-sonnet-4")),
                "frontier", new ModelPolicy.TierPolicy("nope", List.of("also-nope"))));
        ModelSelector selector = new ModelSelector(catalog, policy);
        ReflectionTestUtils.setField(selector, "defaultModel", "DEFAULT");
        return selector;
    }

    private Step llmStep(String model, ModelTier tier) {
        return new Step("s", StepKind.LLM, null, "p.md", model, tier, null, "sc.json", "out", null);
    }

    @Test
    void resolvesTierPrimary() {
        assertThat(selector().resolve(llmStep(null, ModelTier.ECONOMY))).isEqualTo("gpt-4.1");
    }

    @Test
    void explicitModelOverrideWins() {
        assertThat(selector().resolve(llmStep("claude-opus-4", ModelTier.ECONOMY))).isEqualTo("claude-opus-4");
    }

    @Test
    void skipsDisabledPrimaryAndUsesFallback() {
        assertThat(selector().resolve(llmStep(null, ModelTier.DEEP))).isEqualTo("claude-sonnet-4");
    }

    @Test
    void fallsBackToDefaultWhenNoneResolvable() {
        assertThat(selector().resolve(llmStep(null, ModelTier.FRONTIER))).isEqualTo("DEFAULT");
    }

    @Test
    void nullTierDefaultsToStandard() {
        assertThat(selector().resolve(llmStep(null, null))).isEqualTo("claude-sonnet-4");
    }

    @Test
    void costAwarePicksCheapestEnabledModelAtTier() {
        // Two STANDARD models; cost-aware should pick the cheaper one even though it's the policy fallback.
        ModelCatalog catalog = new ModelCatalog(BillingMode.PER_REQUEST, 0.04, 0.0, true, List.of(
                new ModelSpec("pricey-standard", ModelTier.STANDARD, 5.0, 0.0, 0.0, null),
                new ModelSpec("cheap-standard", ModelTier.STANDARD, 1.0, 0.0, 0.0, null)));
        ModelPolicy policy = new ModelPolicy(Map.of(
                "standard", new ModelPolicy.TierPolicy("pricey-standard", List.of("cheap-standard"))));
        ModelSelector selector = new ModelSelector(catalog, policy);
        assertThat(selector.resolveTier(ModelTier.STANDARD)).isEqualTo("cheap-standard");
    }

    @Test
    void costAwareUsesCreditRatesUnderUsageBilling() {
        ModelCatalog catalog = new ModelCatalog(BillingMode.USAGE_CREDITS, 0.04, 0.01, true, List.of(
                new ModelSpec("opus", ModelTier.DEEP, 27, 500, 2500, null),     // 3000 credits
                new ModelSpec("gemini", ModelTier.DEEP, 6, 200, 1200, null)));   // 1400 credits → cheaper
        ModelPolicy policy = new ModelPolicy(Map.of(
                "deep", new ModelPolicy.TierPolicy("opus", List.of("gemini"))));
        assertThat(new ModelSelector(catalog, policy).resolveTier(ModelTier.DEEP)).isEqualTo("gemini");
    }
}
