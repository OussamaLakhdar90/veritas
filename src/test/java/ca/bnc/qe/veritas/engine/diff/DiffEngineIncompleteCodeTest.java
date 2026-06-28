package ca.bnc.qe.veritas.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/**
 * Found by dogfooding on spring-petclinic-rest (a spec-first / openapi-generator project): when controllers delegate
 * their @*Mapping to interfaces outside the scanned sources, the code extraction is known-incomplete — so a spec
 * endpoint "not found in code" is unverifiable, not a dead endpoint. The diff must not emit misleading "dead spec?"
 * findings it can't stand behind; it surfaces one honest summary instead.
 */
class DiffEngineIncompleteCodeTest {

    private static final SourceRef SRC = SourceRef.code("X.java", 1, 1, "x");

    private static Endpoint ep(HttpMethod m, String path) {
        return new Endpoint(m, path, m + path, List.of(), null, List.of(), null, null, List.of(), SRC);
    }

    private static ApiModel spec(Endpoint... eps) {
        return new ApiModel("repo-spec", null, null, null, List.of(eps), Map.of(), List.of());
    }

    @Test
    void suppressesDeadSpecFindingsWhenCodeExtractionIsKnownIncomplete() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(), Map.of(), List.of(
                "Controller 'OwnerRestControllerV1' has no mappings on its own methods but implements [OwnersApi]; "
                        + "mappings declared on interfaces are not analysed."));
        ApiModel spec = spec(ep(HttpMethod.GET, "/owners"), ep(HttpMethod.POST, "/owners"));

        List<Finding> findings = new DiffEngine().diffCodeVsSpec(code, spec);

        // No per-endpoint "dead spec?" claims, and exactly one honest summary covering the 2 unverifiable endpoints.
        assertThat(findings).noneMatch(f -> f.getSummary() != null
                && f.getSummary().contains("not found in code (dead spec?)"));
        assertThat(findings).filteredOn(f -> f.getType() == FindingType.EXTRA_ENDPOINT).singleElement()
                .satisfies(f -> {
                    assertThat(f.getSummary()).contains("could not be cross-checked").contains("2 spec endpoint");
                    assertThat(f.getConfidence().name()).isEqualTo("LOW");
                });
    }

    @Test
    void stillFlagsDeadSpecWhenCodeExtractionIsComplete() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(), Map.of(), List.of());   // complete
        ApiModel spec = spec(ep(HttpMethod.GET, "/owners"));

        List<Finding> findings = new DiffEngine().diffCodeVsSpec(code, spec);

        assertThat(findings).anyMatch(f -> f.getSummary() != null
                && f.getSummary().contains("not found in code (dead spec?)"));
    }
}
