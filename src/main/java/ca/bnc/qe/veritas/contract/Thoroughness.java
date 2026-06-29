package ca.bnc.qe.veritas.contract;

import java.util.Locale;
import ca.bnc.qe.veritas.cost.ModelTier;

/**
 * Per-scan cost/rigour dial for the LLM reconcile step — lets a reviewer trade spend for depth on a given run:
 * a quick sanity check on the cheapest model, the balanced default, or the strongest model for a release gate.
 * The deterministic extract/diff is unaffected; only the (paid) reconcile model tier changes.
 */
public enum Thoroughness {
    /** Cheapest model — a quick, low-cost reconcile. */
    ECONOMY(ModelTier.ECONOMY),
    /** The balanced default. */
    STANDARD(ModelTier.STANDARD),
    /**
     * The strongest model — the most rigorous reconcile (e.g. a release gate). Resolves to the FRONTIER tier so the
     * selector picks the top-tier model (Claude Opus 4.8 today — the cheapest/primary frontier model, multiplier 27)
     * rather than a cheaper mid-tier "deep" model (gpt-5.4 / gemini-3.1-pro, multiplier 6). Catalog-driven: the
     * concrete model comes from models.yaml / model-policy.yaml, never hardcoded here.
     */
    DEEP(ModelTier.FRONTIER);

    private final ModelTier tier;

    Thoroughness(ModelTier tier) {
        this.tier = tier;
    }

    public ModelTier tier() {
        return tier;
    }

    /** Parse a user-supplied value (case-insensitive); null/blank/unknown falls back to {@link #STANDARD}. */
    public static Thoroughness fromOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }
}
