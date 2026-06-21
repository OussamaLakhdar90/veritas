package ca.bnc.qe.veritas.cost;

/** One model in the catalog. {@code enabled} is nullable in YAML and defaults to true. */
public record ModelSpec(
        String id,
        ModelTier tier,
        double requestMultiplier,
        double creditsPerMTokIn,
        double creditsPerMTokOut,
        Boolean enabled
) {
    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
