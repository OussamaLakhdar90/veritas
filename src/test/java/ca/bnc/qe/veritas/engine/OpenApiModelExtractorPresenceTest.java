package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecPresence;
import org.junit.jupiter.api.Test;

/** Fix 3: presence facts from a fully-resolved parse, used to fact-check the LLM's L5/L6 absence judgements. */
class OpenApiModelExtractorPresenceTest {

    private final OpenApiModelExtractor extractor = new OpenApiModelExtractor();

    private String fixture(String name) throws Exception {
        return Files.readString(Path.of(getClass().getClassLoader().getResource("fixtures/" + name).toURI()));
    }

    @Test
    void resolvesRefExamplesAndConstraintsAsPresent() throws Exception {
        SpecPresence p = extractor.presenceOf(fixture("policies-spec-examples.yaml"));
        assertThat(p.anyResponseHasExamples()).isTrue();       // examples: { $ref: ... } resolved → present
        assertThat(p.anySchemaHasProperties()).isTrue();       // $ref-bound PolicyResponse has properties
        assertThat(p.anySchemaHasConstraints()).isTrue();      // maxLength on id
        assertThat(p.anyErrorResponseDeclared()).isTrue();     // 404 declared
    }

    @Test
    void reportsGenuineAbsenceWhenSpecHasNoExamplesOrConstraints() throws Exception {
        SpecPresence p = extractor.presenceOf(fixture("policies-spec.yaml"));
        assertThat(p.anyResponseHasExamples()).isFalse();
        assertThat(p.anySchemaHasProperties()).isTrue();       // PolicyResponse has id/name
        assertThat(p.anySchemaHasConstraints()).isFalse();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }

    @Test
    void contradictsAbsenceClaimOnlyWhenEveryClaimIsFalse() {
        SpecPresence all = new SpecPresence(true, true, true, true);
        assertThat(all.contradictsAbsenceClaim("No response examples present")).isTrue();
        assertThat(all.contradictsAbsenceClaim("Schema defines no properties or constraints")).isTrue();

        // a finding whose claim is genuinely true (spec really has no examples) is kept, even if other facts are true
        SpecPresence noExamples = new SpecPresence(false, true, false, false);
        assertThat(noExamples.contradictsAbsenceClaim(
                "Spec is a weak test basis — no examples, constraints, or error responses")).isFalse();

        // a non-absence finding is never touched
        assertThat(all.contradictsAbsenceClaim("Inconsistent resource naming across endpoints")).isFalse();
    }
}
