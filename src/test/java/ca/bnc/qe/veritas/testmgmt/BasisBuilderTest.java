package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.ingest.IngestService;
import org.junit.jupiter.api.Test;

/**
 * The code basis fed to the strategy LLM is RICH — params, request body (+validated), responses, security, and a
 * Data-models section with DTO fields + validation constraints — not just the endpoint signatures (the gap the
 * spring-petclinic dogfood surfaced). Driven through the real {@link JavaSpringExtractor} on code fixtures.
 */
class BasisBuilderTest {

    private final BasisBuilder builder = new BasisBuilder(new JavaSpringExtractor(), mock(IngestService.class));

    private Path fixture(String name) throws Exception {
        return Path.of(getClass().getClassLoader().getResource("fixtures/" + name).toURI());
    }

    @Test
    void basisIncludesEndpointsRequestBodyAndAValidatedFlag() throws Exception {
        String basis = builder.fromRepo(fixture("policies"));
        assertThat(basis).startsWith("API surface (from code):");
        assertThat(basis).contains("POST /api/v1/policies");
        // POST createPolicy(@RequestBody @Valid PolicyRequest) → body line with the validated flag.
        assertThat(basis).contains("body: PolicyRequest").contains("validated");
    }

    @Test
    void basisIncludesADataModelsSectionWithFieldConstraints() throws Exception {
        String basis = builder.fromRepo(fixture("policies"));
        assertThat(basis).contains("Data models (from code):");
        assertThat(basis).contains("PolicyRequest");
        // @Size(max = 10) on the `code` field → surfaced as a maxLen constraint the LLM can reason about.
        assertThat(basis).contains("maxLen 10");
    }

    @Test
    void basisAnnotatesSecuredEndpoints() throws Exception {
        // SecuredController has @PreAuthorize("hasRole('ADMIN')") → the endpoint carries a [secured: …] tag.
        String basis = builder.fromRepo(fixture("secured"));
        assertThat(basis).contains("[secured:");
    }
}
