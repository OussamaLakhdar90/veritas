package ca.bnc.qe.veritas.engine;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/** Deterministic engine verification — NO LLM. Code vs a divergent Swagger 2.0 spec, plus spec-vs-spec drift. */
class ContractEngineTest {

    private final JavaSpringExtractor codeExtractor = new JavaSpringExtractor();
    private final OpenApiModelExtractor specExtractor = new OpenApiModelExtractor();
    private final DiffEngine diff = new DiffEngine();

    private Path codeRoot() throws Exception {
        return Path.of(getClass().getClassLoader().getResource("fixtures/policies").toURI());
    }

    private String spec(String name) throws Exception {
        return Files.readString(Path.of(getClass().getClassLoader().getResource("fixtures/" + name).toURI()));
    }

    @Test
    void detectsContractDivergence() throws Exception {
        ApiModel code = codeExtractor.extract(codeRoot());
        SpecParse parse = specExtractor.extract("repo-spec", spec("policies-spec.yaml"));
        assertThat(parse.parsed()).isTrue();

        List<Finding> findings = diff.diffCodeVsSpec(code, parse.model());
        Set<FindingType> types = findings.stream().map(Finding::getType).collect(toSet());

        assertThat(types).contains(
                FindingType.MISSING_ENDPOINT,        // POST /api/v1/policies not in spec
                FindingType.EXTRA_ENDPOINT,          // GET /api/v1/policies/all not in code
                FindingType.PATH_VAR_NAME_MISMATCH,  // {id} vs {policyId}
                FindingType.SCHEMA_FIELD_MISSING);   // PolicyResponse.version absent in spec

        assertThat(findings).anyMatch(f -> f.getType() == FindingType.MISSING_ENDPOINT
                && "POST /api/v1/policies".equals(f.getEndpoint()));
        assertThat(findings).anyMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING
                && f.getEndpoint() != null && f.getEndpoint().contains("version"));
        // path-variable mismatch must NOT also be double-reported as PARAM_MISSING/EXTRA
        assertThat(types).doesNotContain(FindingType.PARAM_MISSING, FindingType.PARAM_EXTRA);
    }

    @Test
    void resolvesComposedMetaAnnotatedMapping() throws Exception {
        ApiModel code = codeExtractor.extract(
                Path.of(getClass().getClassLoader().getResource("fixtures/meta").toURI()));
        // @ApiGet (meta-annotated with @GetMapping) must be recognized as a GET endpoint
        assertThat(code.endpoints()).anyMatch(e -> e.signature().equals("GET /api/v1/things/{id}"));
    }

    @Test
    void detectsSpecDrift() throws Exception {
        ApiModel repo = specExtractor.extract("repo-spec", spec("policies-spec.yaml")).model();
        ApiModel confluence = specExtractor.extract("confluence-spec", spec("policies-spec-confluence.yaml")).model();

        List<Finding> drift = diff.diffSpecVsSpec(repo, confluence);

        assertThat(drift).isNotEmpty();
        assertThat(drift).allMatch(f -> f.getType() == FindingType.SPEC_DRIFT);
    }
}
