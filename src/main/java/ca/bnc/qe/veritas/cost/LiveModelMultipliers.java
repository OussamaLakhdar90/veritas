package ca.bnc.qe.veritas.cost;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Live premium-request multipliers fetched from Copilot's {@code /models} endpoint (GitHub's authoritative
 * source — there is no separate pricing API). When populated, {@link CostEstimator} prefers these over the
 * static {@code models.yaml} multipliers, making per-action cost reflect real billing.
 */
@Component
public class LiveModelMultipliers {

    private final Map<String, Double> multipliers = new ConcurrentHashMap<>();
    private final Set<String> premium = ConcurrentHashMap.newKeySet();

    public void update(Map<String, Double> freshMultipliers, Set<String> premiumIds) {
        multipliers.clear();
        if (freshMultipliers != null) {
            freshMultipliers.forEach((k, v) -> {
                if (k != null && v != null) {
                    multipliers.put(k, v);
                }
            });
        }
        premium.clear();
        if (premiumIds != null) {
            premium.addAll(premiumIds);
        }
    }

    public Optional<Double> multiplier(String modelId) {
        return Optional.ofNullable(modelId == null ? null : multipliers.get(modelId));
    }

    public boolean isPremium(String modelId) {
        return modelId != null && premium.contains(modelId);
    }

    public int size() {
        return multipliers.size();
    }
}
