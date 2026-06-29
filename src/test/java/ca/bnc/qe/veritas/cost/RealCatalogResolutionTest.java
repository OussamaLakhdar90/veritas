package ca.bnc.qe.veritas.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import ca.bnc.qe.veritas.contract.Thoroughness;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.Test;

/**
 * Guards the PRODUCTION model catalog (models.yaml + model-policy.yaml): a DEEP-thoroughness scan must run on the
 * strongest model (Claude Opus 4.8 today), not a cheap mid-tier "deep" model — resolved from the catalog, not
 * hardcoded. Mirrors {@code ModelConfig}'s YAML loading.
 */
class RealCatalogResolutionTest {

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .addModule(new ParameterNamesModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .build();

    private static <T> T load(String path, Class<T> type) throws Exception {
        try (InputStream in = RealCatalogResolutionTest.class.getResourceAsStream(path)) {
            return YAML.readValue(in, type);
        }
    }

    @Test
    void deepThoroughnessResolvesToTheFrontierModel() throws Exception {
        ModelCatalog catalog = load("/veritas/models.yaml", ModelCatalog.class);
        ModelPolicy policy = load("/veritas/model-policy.yaml", ModelPolicy.class);
        ModelSelector selector = new ModelSelector(catalog, policy);

        // DEEP thoroughness escalates to the FRONTIER tier...
        assertThat(Thoroughness.DEEP.tier()).isEqualTo(ModelTier.FRONTIER);
        // ...which the cost-aware selector resolves to Opus 4.8 (cheapest + policy primary of frontier).
        assertThat(selector.resolveTier(Thoroughness.DEEP.tier())).isEqualTo("claude-opus-4.8");
        // The escalation is meaningful: the mid-tier "deep" band resolves to a different (cheaper) model.
        assertThat(selector.resolveTier(ModelTier.DEEP)).isNotEqualTo("claude-opus-4.8");
    }
}
