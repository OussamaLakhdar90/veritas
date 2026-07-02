package ca.bnc.qe.veritas.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/** A schema-field finding must carry the code field's OWN source location as traceable code evidence. */
class SchemaComparatorTest {

    @Test
    void fieldMissingFromSpecCarriesTheCodeFieldsSourceAsEvidence() {
        FieldModel excludeAttributes = new FieldModel("excludeAttributes", "array", null, false, null, null,
                SourceRef.code("src/main/java/ca/bnc/PasswordComplexity.java", 42, 42, null));
        SchemaModel codeSchema = new SchemaModel("PasswordComplexity", "object", List.of(excludeAttributes), null, null);
        SchemaModel specSchema = new SchemaModel("password.complexity", "object", List.of(), null, null);

        List<Finding> findings = new ArrayList<>();
        SchemaComparator.compareSchema(findings, "code-vs-spec",
                "GET /ciam/policies response.password.complexity", codeSchema, specSchema);

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.getType()).isEqualTo(FindingType.SCHEMA_FIELD_MISSING);
        // The evidence points at the DTO field's own file + line — not null, and not the endpoint.
        assertThat(f.getCodeEvidence()).isNotNull();
        assertThat(f.getCodeEvidence().location()).isEqualTo("src/main/java/ca/bnc/PasswordComplexity.java");
        assertThat(f.getCodeEvidence().startLine()).isEqualTo(42);
    }
}
