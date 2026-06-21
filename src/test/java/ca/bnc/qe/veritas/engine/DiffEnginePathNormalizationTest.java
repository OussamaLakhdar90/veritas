package ca.bnc.qe.veritas.engine;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/** Comparison confidence: equivalent paths must NOT produce false MISSING/EXTRA findings (deterministic). */
class DiffEnginePathNormalizationTest {

    private final DiffEngine diff = new DiffEngine();
    private final ca.bnc.qe.veritas.engine.model.SourceRef src =
            ca.bnc.qe.veritas.engine.model.SourceRef.code("X.java", 1, 1, "x");

    private Endpoint ep(String path) {
        return new Endpoint(HttpMethod.GET, path, "op", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, List.of(), src);
    }

    private ApiModel model(String source, Endpoint... eps) {
        return new ApiModel(source, null, null, null, List.of(eps), Map.of());
    }

    @Test
    void pathVariableNameDifferenceMatchesAndReportsNameMismatchOnly() {
        List<Finding> f = diff.diffCodeVsSpec(model("code", ep("/policies/{id}")),
                model("repo-spec", ep("/policies/{policyId}")));
        Set<FindingType> types = f.stream().map(Finding::getType).collect(toSet());
        assertThat(types).doesNotContain(FindingType.MISSING_ENDPOINT, FindingType.EXTRA_ENDPOINT);
        assertThat(types).contains(FindingType.PATH_VAR_NAME_MISMATCH);
    }

    @Test
    void trailingSlashAndCaseDoNotCauseFalseFindings() {
        List<Finding> f = diff.diffCodeVsSpec(model("code", ep("/Policies/{id}/")),
                model("repo-spec", ep("/policies/{id}")));
        Set<FindingType> types = f.stream().map(Finding::getType).collect(toSet());
        assertThat(types).doesNotContain(FindingType.MISSING_ENDPOINT, FindingType.EXTRA_ENDPOINT);
    }

    @Test
    void identicalContractYieldsNoStructuralFindings() {
        List<Finding> f = diff.diffCodeVsSpec(model("code", ep("/policies/{id}"), ep("/policies")),
                model("repo-spec", ep("/policies/{id}"), ep("/policies")));
        Set<FindingType> types = f.stream().map(Finding::getType).collect(toSet());
        assertThat(types).doesNotContain(FindingType.MISSING_ENDPOINT, FindingType.EXTRA_ENDPOINT,
                FindingType.VERB_MISMATCH, FindingType.PATH_VAR_NAME_MISMATCH);
    }
}
