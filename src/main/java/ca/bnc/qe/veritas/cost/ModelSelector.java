package ca.bnc.qe.veritas.cost;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ca.bnc.qe.veritas.skill.Step;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Picks the concrete model for an LLM step. An explicit {@code model:} override always wins. Otherwise the
 * step's {@code tier} is resolved <b>cost-aware</b>: among the enabled catalog models tagged with that tier,
 * the cheapest is chosen (by USAGE_CREDITS rate, or by premium-request multiplier — live {@code /models}
 * multiplier preferred — under PER_REQUEST), the policy primary breaking ties. If no catalog model carries
 * the tier, the policy (primary → fallbacks) provides an availability fallback, then the configured default.
 * Config-driven so Copilot model churn is a YAML edit, not a code change.
 */
@Service
@Slf4j
public class ModelSelector {

    private final ModelCatalog catalog;
    private final ModelPolicy policy;
    private final LiveModelMultipliers live;

    @Value("${veritas.llm.model:claude-sonnet-4.6}")
    private String defaultModel;

    /** Default whole-prompt cap when a model has no configured context window. */
    @Value("${veritas.llm.max-prompt-tokens:60000}")
    private int maxPromptTokens;

    /** Per-model context windows (tokens), e.g. {@code veritas.llm.context-windows: {claude-opus-4.8: 200000}}. */
    @Value("#{${veritas.llm.context-windows:{:}}}")
    private java.util.Map<String, Integer> contextWindows = java.util.Map.of();

    @org.springframework.beans.factory.annotation.Autowired
    public ModelSelector(ModelCatalog catalog, ModelPolicy policy, LiveModelMultipliers live) {
        this.catalog = catalog;
        this.policy = policy;
        this.live = live;
    }

    /** Convenience for unit tests: no live multipliers. */
    public ModelSelector(ModelCatalog catalog, ModelPolicy policy) {
        this(catalog, policy, new LiveModelMultipliers());
    }

    /**
     * The whole-prompt token cap to use for a given model — its configured context window, or the global
     * {@code veritas.llm.max-prompt-tokens} default. Pass this to {@code PromptComposer.compose(...,cap)} so a
     * bigger-context model gets a bigger prompt budget (per-model context routing).
     */
    public int promptTokenCap(String modelId) {
        Integer window = modelId == null ? null : contextWindows.get(modelId);
        return window != null && window > 0 ? window : maxPromptTokens;
    }

    public String resolve(Step step) {
        if (step.model() != null && !step.model().isBlank()) {
            return step.model();
        }
        return resolveTier(step.tier() != null ? step.tier() : ModelTier.STANDARD);
    }

    /** Resolve a tier directly (used by dedicated services that aren't driven by a skill manifest). */
    public String resolveTier(ModelTier tier) {
        ModelPolicy.TierPolicy tierPolicy = policy.tiers() == null
                ? null
                : policy.tiers().get(tier.name().toLowerCase(Locale.ROOT));

        // 1) Cost-aware: the cheapest enabled catalog model carrying this tier.
        String cheapest = cheapestAtTier(tier, tierPolicy);
        if (cheapest != null) {
            return cheapest;
        }
        // 2) Availability fallback: policy primary → fallbacks (first enabled).
        if (tierPolicy != null) {
            for (String candidate : candidates(tierPolicy)) {
                if (catalog.find(candidate).map(ModelSpec::isEnabled).orElse(false)) {
                    return candidate;
                }
            }
        }
        log.warn("No catalog model for tier {}; falling back to default '{}'", tier, defaultModel);
        return defaultModel;
    }

    private String cheapestAtTier(ModelTier tier, ModelPolicy.TierPolicy tierPolicy) {
        if (catalog.models() == null) {
            return null;
        }
        String primary = tierPolicy == null ? null : tierPolicy.primary();
        ModelSpec best = null;
        double bestCost = Double.MAX_VALUE;
        for (ModelSpec m : catalog.models()) {
            if (m.tier() != tier || !m.isEnabled()) {
                continue;
            }
            double cost = costProxy(m);
            // strictly cheaper wins; on a tie prefer the policy primary for stability
            if (cost < bestCost || (cost == bestCost && m.id().equals(primary))) {
                best = m;
                bestCost = cost;
            }
        }
        return best == null ? null : best.id();
    }

    /** Relative cost used to rank models within a tier. */
    private double costProxy(ModelSpec m) {
        if (catalog.mode() == BillingMode.USAGE_CREDITS) {
            return m.creditsPerMTokIn() + m.creditsPerMTokOut();
        }
        return live.multiplier(m.id()).orElse(m.requestMultiplier());
    }

    private List<String> candidates(ModelPolicy.TierPolicy tierPolicy) {
        List<String> all = new ArrayList<>();
        if (tierPolicy.primary() != null) {
            all.add(tierPolicy.primary());
        }
        if (tierPolicy.fallbacks() != null) {
            all.addAll(tierPolicy.fallbacks());
        }
        return all;
    }
}
