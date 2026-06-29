package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.cost.ModelTier;
import org.junit.jupiter.api.Test;

/** The per-scan cost/rigour dial maps to model tiers and parses user input safely (unknown → STANDARD). */
class ThoroughnessTest {

    @Test
    void mapsToModelTiers() {
        assertThat(Thoroughness.ECONOMY.tier()).isEqualTo(ModelTier.ECONOMY);
        assertThat(Thoroughness.STANDARD.tier()).isEqualTo(ModelTier.STANDARD);
        // DEEP escalates to the FRONTIER tier so the strongest model (Opus 4.8 today) runs the reconcile.
        assertThat(Thoroughness.DEEP.tier()).isEqualTo(ModelTier.FRONTIER);
    }

    @Test
    void fromOrDefaultIsCaseInsensitiveAndFallsBackToStandard() {
        assertThat(Thoroughness.fromOrDefault("deep")).isEqualTo(Thoroughness.DEEP);
        assertThat(Thoroughness.fromOrDefault("  Economy ")).isEqualTo(Thoroughness.ECONOMY);
        assertThat(Thoroughness.fromOrDefault(null)).isEqualTo(Thoroughness.STANDARD);
        assertThat(Thoroughness.fromOrDefault("")).isEqualTo(Thoroughness.STANDARD);
        assertThat(Thoroughness.fromOrDefault("nonsense")).isEqualTo(Thoroughness.STANDARD);
    }
}
