package ca.bnc.qe.veritas.cost;

import java.util.List;
import java.util.Map;

/** Parsed from {@code model-policy.yaml}: tier name (lowercase) -> preferred model + fallbacks. */
public record ModelPolicy(Map<String, TierPolicy> tiers) {

    public record TierPolicy(String primary, List<String> fallbacks) {}
}
