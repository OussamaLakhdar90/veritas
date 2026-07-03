package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.report.FidelityScore;
import org.junit.jupiter.api.Test;

/**
 * S13i-1 real-pipeline regression guard: two endpoints return TWO DIFFERENT code DTOs (different files) that both
 * $ref ONE shared spec component schema lacking a field. The scored {@code SCHEMA_FIELD_MISSING} must collapse to
 * ONE finding across both endpoints (the fix is a single edit to the shared spec schema), NOT two separate MAJORs —
 * the exact duplication regression the round-2 review flagged. Runs the real extractor → diff → collapse, no Spring.
 */
class SharedSpecSchemaCollapseTest {

    private final JavaSpringExtractor javaExtractor = new JavaSpringExtractor();
    private final OpenApiModelExtractor specExtractor = new OpenApiModelExtractor();
    private final DiffEngine diffEngine = new DiffEngine();

    // Two controllers, two DIFFERENT wrapper DTOs (separate files), each carrying `excludeAttributes`.
    private static final String CTRL_A =
            "import org.springframework.web.bind.annotation.*;\n@RestController\n@RequestMapping(\"/policies\")\n"
                    + "public class PoliciesController {\n  @GetMapping\n  public PolicyWrapperA get(){return null;}\n}\n";
    private static final String CTRL_B =
            "import org.springframework.web.bind.annotation.*;\n@RestController\n@RequestMapping(\"/policies\")\n"
                    + "public class AppPoliciesController {\n  @GetMapping(\"/{app}\")\n"
                    + "  public PolicyWrapperB byApp(@PathVariable String app){return null;}\n}\n";
    private static final String DTO_A =
            "public class PolicyWrapperA {\n  private int minLength;\n  private String[] excludeAttributes;\n}\n";
    private static final String DTO_B =
            "public class PolicyWrapperB {\n  private int minLength;\n  private String[] excludeAttributes;\n}\n";

    // ONE shared component schema `policies` referenced by BOTH endpoints' 200 response — and it LACKS excludeAttributes.
    private static final String SPEC = """
            openapi: 3.0.1
            info: {title: t, version: '1'}
            paths:
              /policies:
                get:
                  responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/policies'}}}}}
              /policies/{app}:
                get:
                  parameters: [{name: app, in: path, required: true, schema: {type: string}}]
                  responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/policies'}}}}}
            components:
              schemas:
                policies:
                  type: object
                  properties:
                    minLength: {type: integer}
            """;

    private List<Finding> rawFindings() throws Exception {
        Path dir = Files.createTempDirectory("shared-spec-");
        Files.writeString(dir.resolve("PoliciesController.java"), CTRL_A);
        Files.writeString(dir.resolve("AppPoliciesController.java"), CTRL_B);
        Files.writeString(dir.resolve("PolicyWrapperA.java"), DTO_A);
        Files.writeString(dir.resolve("PolicyWrapperB.java"), DTO_B);
        ApiModel code = javaExtractor.extract(dir);
        SpecParse parse = specExtractor.extract("repo-spec", SPEC);
        assertThat(parse.parsed()).isTrue();
        return diffEngine.diffCodeVsSpec(code, parse.model());
    }

    private static List<Finding> excludeAttributesMissing(List<Finding> findings) {
        return findings.stream()
                .filter(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING
                        && f.getSummary() != null && f.getSummary().contains("excludeAttributes"))
                .toList();
    }

    @Test
    void sharedSpecSchemaFieldCollapsesToOneScoredFindingAcrossBothEndpoints() throws Exception {
        List<Finding> raw = rawFindings();
        List<Finding> rawMissing = excludeAttributesMissing(raw);

        // Pre-collapse: BOTH endpoints flag excludeAttributes as missing on the SAME shared spec schema — same spec
        // locus, distinct endpoints, and NO code-locus anchor (the extractor attaches no per-field source), so only
        // the spec locus can collapse them (a code-locus key never could — the round-2 regression).
        assertThat(rawMissing).hasSize(2);
        assertThat(rawMissing).allSatisfy(f -> assertThat(f.getSpecLocus()).isEqualTo("policies#excludeAttributes"));
        assertThat(rawMissing.get(0).getEndpoint()).isNotEqualTo(rawMissing.get(1).getEndpoint());
        assertThat(rawMissing).allSatisfy(f ->
                assertThat(f.getCodeEvidence() == null || f.getCodeEvidence().location() == null).isTrue());

        List<Finding> collapsed = ContractValidationService.collapseByRootCause(raw);
        List<Finding> collapsedMissing = excludeAttributesMissing(collapsed);

        // Post-collapse: EXACTLY ONE scored SCHEMA_FIELD_MISSING, listing both endpoints, naming the shared spec schema.
        assertThat(collapsedMissing).hasSize(1);
        Finding survivor = collapsedMissing.get(0);
        assertThat(survivor.getAffectedEndpoints()).hasSize(2);
        assertThat(survivor.getSummary()).contains("shared spec schema");
        // The survivor fingerprint is the order-independent root-cause hash: collapsing the SAME raw findings in
        // reverse order must yield the SAME id — file-walk / controller-enumeration order must never decide the
        // surviving fingerprint (a reviewer's disposition would silently reset between scans).
        List<Finding> reversed = new ArrayList<>(raw);
        Collections.reverse(reversed);
        Finding survivorReversed =
                excludeAttributesMissing(ContractValidationService.collapseByRootCause(reversed)).get(0);
        assertThat(survivor.getFindingId()).isNotBlank().isEqualTo(survivorReversed.getFindingId());

        // Score: the -8 MAJOR charge for excludeAttributes is counted ONCE after collapse, not twice.
        assertThat(FidelityScore.of(collapsed)).isEqualTo(FidelityScore.of(raw) + 8);
    }
}
