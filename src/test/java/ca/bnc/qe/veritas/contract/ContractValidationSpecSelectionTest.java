package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.CorrectedSpecBuilder;
import org.junit.jupiter.api.Test;

/**
 * The corrected drop-in YAML must carry the REAL info/servers even when the metadata-rich OpenAPI spec is not the
 * first source supplied to the scan. Regression: the pipeline used to take {@code req.specs().get(0)} blindly, so a
 * non-OpenAPI first source (a Confluence page, a fragment) left the corrected YAML with placeholder info/servers.
 */
class ContractValidationSpecSelectionTest {

    private static final CorrectedSpecBuilder BUILDER = new CorrectedSpecBuilder();

    private static final String REAL = "openapi: 3.0.0\ninfo:\n  title: CIAM Policies\n  version: 1.0.5\n"
            + "servers:\n  - url: https://api.example.com/ciam\npaths: {}\n";
    private static final String FRAGMENT = "paths:\n  /x:\n    get:\n      responses: {}\n";   // no info/servers

    @Test
    void picksTheSpecWithMetadataEvenWhenItIsNotFirst() {
        // [fragment, real] — a blind get(0) picks the fragment and loses info/servers; selection must find `real`.
        String picked = ContractValidationService.pickMetadataSpec(
                List.of(new SpecInput("frag", FRAGMENT), new SpecInput("real", REAL)), BUILDER);
        assertThat(picked).isEqualTo(REAL);
    }

    @Test
    void selectedSpecMakesTheCorrectedYamlCarryRealInfoAndServers() {
        // End-to-end: the wrong-order pick, overlaid onto a code-built corrected YAML, yields the real metadata
        // (not the "Corrected API"/1.0.0/no-servers placeholder).
        String picked = ContractValidationService.pickMetadataSpec(
                List.of(new SpecInput("frag", FRAGMENT), new SpecInput("real", REAL)), BUILDER);
        String corrected = new CorrectedSpecBuilder().build(emptyCode(), "Placeholder", picked);
        assertThat(corrected).contains("CIAM Policies").contains("1.0.5").contains("https://api.example.com/ciam");
    }

    @Test
    void fallsBackToFirstNonBlankWhenNoSpecHasMetadata_andNullWhenEmpty() {
        assertThat(ContractValidationService.pickMetadataSpec(
                List.of(new SpecInput("a", "   "), new SpecInput("b", FRAGMENT)), BUILDER)).isEqualTo(FRAGMENT);
        assertThat(ContractValidationService.pickMetadataSpec(List.of(), BUILDER)).isNull();
    }

    private static ApiModel emptyCode() {
        return new ApiModel("code", null, null, null, List.of(), Map.of());
    }
}
