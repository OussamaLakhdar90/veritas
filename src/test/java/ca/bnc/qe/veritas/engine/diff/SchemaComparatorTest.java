package ca.bnc.qe.veritas.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/** A schema-field finding must carry the code field's OWN source location as traceable code evidence, plus the
 *  spec-side locus ("<specSchemaName>#<field>") used for the cross-endpoint root-cause collapse (S13i-1). */
class SchemaComparatorTest {

    @Test
    void fieldMissingFromSpecCarriesTheCodeFieldsSourceAsEvidenceAndTheSpecLocus() {
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
        // Spec locus anchors the root cause on the SPEC schema name (which contains a dot) + the field, '#'-separated.
        assertThat(f.getSpecLocus()).isEqualTo("password.complexity#excludeAttributes");
    }

    @Test
    void fieldExtraInSpecCarriesTheSpecLocus() {
        SchemaModel codeSchema = new SchemaModel("Wrapper", "object", List.of(), null, null);
        SchemaModel specSchema = new SchemaModel("policies", "object",
                List.of(new FieldModel("legacyField", "string", null, false, null, null, null)), null, null);

        List<Finding> findings = new ArrayList<>();
        SchemaComparator.compareSchema(findings, "code-vs-spec", "GET /policies response", codeSchema, specSchema);

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.getType()).isEqualTo(FindingType.SCHEMA_FIELD_EXTRA);
        assertThat(f.getSpecLocus()).isEqualTo("policies#legacyField");
    }

    @Test
    void nestedFlipTypeMismatchCarriesTheSpecLocus() {
        // Code binds `complexity` to a nested DTO ($ref), the spec declares the same field as a bare scalar → a
        // provable object-vs-scalar flip in fieldDiffByBinding, which must carry the spec locus of the bound schema.
        ConstraintSet none = new ConstraintSet(null, null, null, null, null, null, null, null, null);
        SchemaModel nestedDto = new SchemaModel("Complexity", "object",
                List.of(new FieldModel("minLength", "integer", null, false, none, null, null)), null, null);
        FieldModel codeField = new FieldModel("complexity", "object", null, false, none, "Complexity",
                SourceRef.code("Wrapper.java", 5, 5, null));
        FieldModel specField = new FieldModel("complexity", "string", null, false, none, null, null);
        SchemaModel codeWrapper = new SchemaModel("Wrapper", "object", List.of(codeField), null, null);
        SchemaModel specWrapper = new SchemaModel("policies", "object", List.of(specField), null, null);

        ApiModel code = new ApiModel("code", null, null, null, List.of(),
                java.util.Map.of("Wrapper", codeWrapper, "Complexity", nestedDto));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(),
                java.util.Map.of("policies", specWrapper));

        List<Finding> findings = new ArrayList<>();
        SchemaComparator.fieldDiffByBinding(findings, code, spec, "Wrapper", "policies", "GET /policies response",
                new HashSet<>(), SchemaComparator.MAX_SCHEMA_DEPTH);

        assertThat(findings).anyMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_TYPE_MISMATCH
                && "policies#complexity#object~scalar".equals(f.getSpecLocus()));
    }

    @Test
    void scalarTypeMismatchLocusCarriesTheMismatchKind() {
        // The TYPE_MISMATCH locus embeds WHAT the mismatch is ("<schema>#<field>#<codeType>~<specType>") so two
        // DIFFERENT type defects on the same spec field never collapse into one survivor that hides the other.
        ConstraintSet none = new ConstraintSet(null, null, null, null, null, null, null, null, null);
        SchemaModel codeSchema = new SchemaModel("Order", "object",
                List.of(new FieldModel("total", "integer", null, false, none, null, null)), null, null);
        SchemaModel specSchema = new SchemaModel("Order", "object",
                List.of(new FieldModel("total", "string", null, false, none, null, null)), null, null);

        List<Finding> findings = new ArrayList<>();
        SchemaComparator.compareSchema(findings, "code-vs-spec", "Order", codeSchema, specSchema);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getType()).isEqualTo(FindingType.SCHEMA_FIELD_TYPE_MISMATCH);
        assertThat(findings.get(0).getSpecLocus()).isEqualTo("Order#total#integer~string");
    }

    @Test
    void scalarArrayItemTypeOnCodeSideDoesNotFalseDiffAgainstUntypedSpecArray() {
        // The extractor now carries a scalar element type as refSchema "string[]" (so the corrected YAML can emit
        // items:{type:string}). A faithful spec array of the same field commonly has NO item type (refSchema=null).
        // The comparator must raise NO SCHEMA_FIELD_* finding off that item-type difference — item type is not a
        // comparison key. Guards the diff-safety contract of the scalar-array element-type fix (field-compare path).
        FieldModel codeTags = new FieldModel("tags", "array", null, false, ConstraintSet.empty(), "string[]",
                SourceRef.code("Bag.java", 10, 10, null));
        FieldModel specTags = new FieldModel("tags", "array", null, false, ConstraintSet.empty(), null, null);
        SchemaModel codeSchema = new SchemaModel("Bag", "object", List.of(codeTags), null, null);
        SchemaModel specSchema = new SchemaModel("Bag", "object", List.of(specTags), null, null);

        List<Finding> findings = new ArrayList<>();
        SchemaComparator.compareSchema(findings, "code-vs-spec", "GET /bags response", codeSchema, specSchema);

        assertThat(findings).isEmpty();
    }

    @Test
    void scalarArrayItemTypeDoesNotProduceAnObjectVsScalarFlipInBinding() {
        // Binding path: code field is an array carrying a scalar item type ("string[]"); the spec field is the same
        // untyped array (refSchema null). This must NOT be read as an object-vs-scalar flip — both are arrays.
        FieldModel codeTags = new FieldModel("tags", "array", null, false, ConstraintSet.empty(), "string[]",
                SourceRef.code("Bag.java", 10, 10, null));
        FieldModel specTags = new FieldModel("tags", "array", null, false, ConstraintSet.empty(), null, null);
        SchemaModel codeWrapper = new SchemaModel("Bag", "object", List.of(codeTags), null, null);
        SchemaModel specWrapper = new SchemaModel("bag", "object", List.of(specTags), null, null);
        ApiModel code = new ApiModel("code", null, null, null, List.of(), java.util.Map.of("Bag", codeWrapper));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(), java.util.Map.of("bag", specWrapper));

        List<Finding> findings = new ArrayList<>();
        SchemaComparator.fieldDiffByBinding(findings, code, spec, "Bag", "bag", "GET /bags response",
                new HashSet<>(), SchemaComparator.MAX_SCHEMA_DEPTH);

        assertThat(findings).isEmpty();
    }

    @Test
    void constraintGapEmissionCarriesNoSpecLocus() {
        // CONSTRAINT_GAP is deliberately OUTSIDE the spec-locus collapse family — the engine must not hand it a
        // locus either, locking both halves of the two-change regression path (engine emission + service key).
        ConstraintSet none = new ConstraintSet(null, null, null, null, null, null, null, null, null);
        SchemaModel codeSchema = new SchemaModel("Order", "object",
                List.of(new FieldModel("total", "integer", null, true, none, null, null)), null, null);
        SchemaModel specSchema = new SchemaModel("Order", "object",
                List.of(new FieldModel("total", "integer", null, false, none, null, null)), null, null);

        List<Finding> findings = new ArrayList<>();
        SchemaComparator.compareSchema(findings, "code-vs-spec", "Order", codeSchema, specSchema);

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.getType()).isEqualTo(FindingType.CONSTRAINT_GAP);   // required in code, optional in the spec
        assertThat(f.getSpecLocus()).isNull();
    }
}
