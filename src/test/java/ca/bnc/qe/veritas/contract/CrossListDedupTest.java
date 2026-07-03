package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.report.ContractReportRenderer;
import org.junit.jupiter.api.Test;

/** Fix 5: the cross-list dedup key must not collapse findings from different specs or case-distinct param names.
 *  S13i-1: the root-cause collapse now keys spec-schema fields on the SPEC locus, so a shared spec schema $ref'd by
 *  several endpoints (different code DTO files) collapses to one scored finding.
 *  S13i-4: the code-locus key carries the normalized summary — endpoint-level findings of one type share the
 *  controller METHOD's SourceRef, so two distinct defects on one endpoint must never share a key. */
class CrossListDedupTest {

    private Finding f(FindingType t, String endpoint, String specSource, String summary) {
        return Finding.builder().findingId("x").type(t).layer(Layer.L4).severity(Severity.MAJOR)
                .confidence(Confidence.MEDIUM).origin("DETERMINISTIC").endpoint(endpoint)
                .specSource(specSource).summary(summary).build();
    }

    @Test
    void sameFindingFromDifferentSpecsGetsDistinctKeys() {
        String k1 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "repo-spec",
                "Code can return 500 but the spec doesn't document it"));
        String k2 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "confluence-spec",
                "Code can return 500 but the spec doesn't document it"));
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void caseDistinctParameterFindingsGetDistinctKeys() {
        String k1 = ContractValidationService.dedupKey(f(FindingType.PARAM_TYPE_MISMATCH, "GET /x", "repo-spec",
                "Parameter 'X' type — code string vs spec integer"));
        String k2 = ContractValidationService.dedupKey(f(FindingType.PARAM_TYPE_MISMATCH, "GET /x", "repo-spec",
                "Parameter 'x' type — code string vs spec integer"));
        assertThat(k1).isNotEqualTo(k2);
    }

    /** A same-name shared-DTO field finding — the components-loop shape: the summary names the SCHEMA (identical
     *  across endpoints), and the evidence is the field's own file + line. */
    private Finding fieldFinding(String endpoint, String file, int line) {
        return Finding.builder().findingId(endpoint).type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint(endpoint)
                .specSource("code-vs-spec")
                .summary("Field 'excludeAttributes' of PasswordComplexity is in code but missing from the spec schema")
                .codeEvidence(SourceRef.code(file, line, line, null)).build();
    }

    /** A schema-field finding anchored on the SPEC locus (shared spec schema + field). */
    private Finding specLocusFinding(FindingType t, String endpoint, String file, int line, String specSource,
                                     String specLocus, String summary) {
        return Finding.builder().findingId(endpoint + specLocus).type(t).layer(Layer.L4).severity(Severity.MAJOR)
                .confidence(t == FindingType.SCHEMA_FIELD_EXTRA ? Confidence.LOW : Confidence.HIGH)
                .origin("DETERMINISTIC").endpoint(endpoint).specSource(specSource).specLocus(specLocus)
                .codeEvidence(file == null ? null : SourceRef.code(file, line, line, null)).summary(summary).build();
    }

    @Test
    void sameSharedDtoFieldAcrossEndpointsCollapsesToOneFindingWithAffectedEndpoints() {
        Finding a = fieldFinding("GET /ciam/policies", "PasswordComplexity.java", 42);
        Finding b = fieldFinding("GET /ciam/policies/{app}", "PasswordComplexity.java", 42);   // same field/line
        Finding unrelated = f(FindingType.PARAM_TYPE_MISMATCH, "GET /y", "code-vs-spec", "Parameter 'q' type differs");

        List<Finding> out = ContractValidationService.collapseByRootCause(List.of(a, b, unrelated));

        assertThat(out).hasSize(2);   // the two field findings became one; the unrelated one is untouched
        Finding merged = out.get(0);
        assertThat(merged.getType()).isEqualTo(FindingType.SCHEMA_FIELD_MISSING);
        assertThat(merged.getAffectedEndpoints())
                .containsExactly("GET /ciam/policies", "GET /ciam/policies/{app}");
    }

    @Test
    void findingsWithoutCodeEvidenceAreNeverCollapsed() {
        Finding a = f(FindingType.PARAM_MISSING, "GET /a", "code-vs-spec", "Param 'x' documented but ignored");
        Finding b = f(FindingType.PARAM_MISSING, "GET /b", "code-vs-spec", "Param 'x' documented but ignored");
        List<Finding> out = ContractValidationService.collapseByRootCause(List.of(a, b));
        assertThat(out).hasSize(2);   // no code anchor and no spec locus → never merged across endpoints
    }

    @Test
    void exactDuplicatesShareAKeyIgnoringTrailingPunctuation() {
        String k1 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "repo-spec",
                "Code can return 500 but the spec doesn't document it"));
        String k2 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "repo-spec",
                "Code can return 500 but the spec doesn't document it."));
        assertThat(k1).isEqualTo(k2);
    }

    // ──────────────── S13i-4: distinct same-endpoint defects must survive the collapse ────────────────

    @Test
    void twoUndocumentedStatusesOnOneEndpointSharingTheControllerSourceRefAreNotCollapsed() {
        // Endpoint-level findings all carry the controller METHOD's SourceRef — the summary is what tells an
        // undocumented 500 apart from an undocumented 406, so both must survive.
        SourceRef method = SourceRef.code("PolicyController.java", 30, 30, null);
        Finding s500 = f(FindingType.STATUS_CODE_MISSING, "GET /policies", "code-vs-spec",
                "Code can return 500 but the spec doesn't document it").toBuilder()
                .findingId("s500").codeEvidence(method).build();
        Finding s406 = f(FindingType.STATUS_CODE_MISSING, "GET /policies", "code-vs-spec",
                "Code can return 406 but the spec doesn't document it").toBuilder()
                .findingId("s406").codeEvidence(method).build();
        assertThat(ContractValidationService.collapseByRootCause(List.of(s500, s406))).hasSize(2);
    }

    @Test
    void twoMissingParamsOnOneEndpointSharingTheControllerSourceRefAreNotCollapsed() {
        SourceRef method = SourceRef.code("PolicyController.java", 30, 30, null);
        Finding px = f(FindingType.PARAM_MISSING, "GET /policies", "code-vs-spec",
                "Parameter 'x' (QUERY) is in code but missing from the spec").toBuilder()
                .findingId("px").codeEvidence(method).build();
        Finding py = f(FindingType.PARAM_MISSING, "GET /policies", "code-vs-spec",
                "Parameter 'y' (QUERY) is in code but missing from the spec").toBuilder()
                .findingId("py").codeEvidence(method).build();
        assertThat(ContractValidationService.collapseByRootCause(List.of(px, py))).hasSize(2);
    }

    @Test
    void errorNoteStillListsBothStatusesAfterTheRealCollapseRuns() {
        // End-to-end-ish: run collapseByRootCause FIRST (as the service does), then render — the demoted 500 AND 406
        // on one endpoint must BOTH reach the §1 error note (pre-S13i-4 the second was swallowed by the collapse).
        SourceRef method = SourceRef.code("PolicyController.java", 30, 30, null);
        Finding s500 = f(FindingType.STATUS_CODE_MISSING, "GET /policies", "code-vs-spec",
                "Code can return 500 but the spec doesn't document it").toBuilder()
                .findingId("s500").confidence(Confidence.LOW).codeEvidence(method).build();
        Finding s406 = f(FindingType.STATUS_CODE_MISSING, "GET /policies", "code-vs-spec",
                "Code can return 406 but the spec doesn't document it").toBuilder()
                .findingId("s406").confidence(Confidence.LOW).codeEvidence(method).build();

        List<Finding> collapsed = ContractValidationService.collapseByRootCause(List.of(s500, s406));
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        String html = new ContractReportRenderer().renderHtml(scan, collapsed);

        assertThat(html).contains("Undocumented error responses")
                .contains("HTTP 406").contains("HTTP 500");
    }

    // ───────────────────────── S13i-1: spec-locus root-cause collapse ─────────────────────────

    @Test
    void sharedSpecSchemaFieldAcrossDifferentCodeFilesCollapsesOnTheSpecLocus() {
        // Two endpoints return DIFFERENT code DTOs (different files/lines) that both $ref ONE shared spec schema
        // lacking the field. A code-locus key could NEVER collapse them; the spec locus does.
        Finding a = specLocusFinding(FindingType.SCHEMA_FIELD_MISSING, "GET /policies", "PolicyWrapper.java", 10,
                "code-vs-spec", "policies#excludeAttributes",
                "Field 'excludeAttributes' of X is in code but missing from the spec schema");
        Finding b = specLocusFinding(FindingType.SCHEMA_FIELD_MISSING, "GET /policies/{app}", "AppPolicyWrapper.java", 99,
                "code-vs-spec", "policies#excludeAttributes",
                "Field 'excludeAttributes' of Y is in code but missing from the spec schema");

        List<Finding> out = ContractValidationService.collapseByRootCause(List.of(a, b));

        assertThat(out).hasSize(1);
        Finding merged = out.get(0);
        assertThat(merged.getAffectedEndpoints()).containsExactly("GET /policies", "GET /policies/{app}");
        assertThat(merged.getSummary())
                .contains("shared spec schema 'policies'").contains("excludeAttributes")
                .contains("missing from the spec schema");
        // The survivor fingerprint is the order-independent root-cause hash — feeding the pair in the reverse order
        // must yield the SAME id (file-walk order must not reset a reviewer's disposition between scans).
        Finding mergedReversed = ContractValidationService.collapseByRootCause(List.of(b, a)).get(0);
        assertThat(merged.getFindingId()).isNotBlank().isEqualTo(mergedReversed.getFindingId());
    }

    @Test
    void sameSpecLocusAcrossDifferentSpecSourcesDoesNotMerge() {
        Finding repo = specLocusFinding(FindingType.SCHEMA_FIELD_MISSING, "GET /policies", "A.java", 1,
                "code-vs-repo-spec", "policies#excludeAttributes", "Field 'excludeAttributes' missing");
        Finding conf = specLocusFinding(FindingType.SCHEMA_FIELD_MISSING, "GET /policies", "A.java", 1,
                "code-vs-confluence-spec", "policies#excludeAttributes", "Field 'excludeAttributes' missing");
        // Key family S| — specSource is part of the key, so the two specs stay two findings.
        assertThat(ContractValidationService.rootCauseKey(repo))
                .isNotEqualTo(ContractValidationService.rootCauseKey(conf));
        assertThat(ContractValidationService.collapseByRootCause(List.of(repo, conf))).hasSize(2);
    }

    @Test
    void sameCodeLocusAcrossDifferentSpecSourcesDoesNotMerge() {
        // Key family C| — a code-anchored finding (no spec locus) diffed against two specs must also stay distinct.
        Finding repo = f(FindingType.RESPONSE_SCHEMA_MISMATCH, "GET /x", "code-vs-repo-spec", "schema differs")
                .toBuilder().codeEvidence(SourceRef.code("X.java", 5, 5, null)).build();
        Finding conf = f(FindingType.RESPONSE_SCHEMA_MISMATCH, "GET /x", "code-vs-confluence-spec", "schema differs")
                .toBuilder().codeEvidence(SourceRef.code("X.java", 5, 5, null)).build();
        assertThat(ContractValidationService.codeLocusKey(repo))
                .isNotEqualTo(ContractValidationService.codeLocusKey(conf));
        assertThat(ContractValidationService.collapseByRootCause(List.of(repo, conf))).hasSize(2);
    }

    @Test
    void nullSpecLocusSchemaFieldFallsBackToTheCodeLocusKey() {
        // A schema-field type without a spec locus (anonymous spec schema) must still collapse on the code locus.
        Finding a = fieldFinding("GET /a", "Shared.java", 7);   // SCHEMA_FIELD_MISSING, no specLocus set
        Finding b = fieldFinding("GET /b", "Shared.java", 7);
        assertThat(ContractValidationService.rootCauseKey(a)).startsWith("C|");
        List<Finding> out = ContractValidationService.collapseByRootCause(List.of(a, b));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getAffectedEndpoints()).containsExactly("GET /a", "GET /b");
    }

    @Test
    void constraintGapWithIdenticalSpecFieldDoesNotMergeOnSpecLocus() {
        // CONSTRAINT_GAP is excluded from the spec-locus family EVEN when a specLocus is present on the finding
        // (per-endpoint constraint values can legitimately differ); with no code evidence it has no code locus either,
        // so the two endpoints stay two findings. Locks the SPEC_LOCUS_TYPES membership check itself.
        Finding a = f(FindingType.CONSTRAINT_GAP, "GET /a", "code-vs-spec",
                "Field 'amount' is required in code but optional in the spec").toBuilder()
                .specLocus("policies#amount").build();
        Finding b = f(FindingType.CONSTRAINT_GAP, "GET /b", "code-vs-spec",
                "Field 'amount' is required in code but optional in the spec").toBuilder()
                .specLocus("policies#amount").build();
        assertThat(ContractValidationService.rootCauseKey(a)).isNull();
        assertThat(ContractValidationService.collapseByRootCause(List.of(a, b))).hasSize(2);
    }

    @Test
    void schemaFieldExtraMergesOnSpecLocusAndRewritesTheSummary() {
        Finding a = specLocusFinding(FindingType.SCHEMA_FIELD_EXTRA, "GET /policies", null, 0, "code-vs-spec",
                "policies#legacyField", "Field 'legacyField' of X is in the spec but not in code");
        Finding b = specLocusFinding(FindingType.SCHEMA_FIELD_EXTRA, "GET /policies/{app}", null, 0, "code-vs-spec",
                "policies#legacyField", "Field 'legacyField' of Y is in the spec but not in code");
        List<Finding> out = ContractValidationService.collapseByRootCause(List.of(a, b));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getSummary())
                .contains("shared spec schema 'policies'").contains("legacyField")
                .contains("in the spec but not in code");
    }

    @Test
    void dotNamedSpecSchemaSplitsSchemaAndFieldAtTheFirstHash() {
        // "password.complexity#excludeAttributes" — schema name contains a dot; split at the FIRST '#'.
        Finding a = specLocusFinding(FindingType.SCHEMA_FIELD_MISSING, "GET /policies", "A.java", 1, "code-vs-spec",
                "password.complexity#excludeAttributes", "Field 'excludeAttributes' of X is in code but missing");
        Finding b = specLocusFinding(FindingType.SCHEMA_FIELD_MISSING, "GET /policies/{app}", "B.java", 2, "code-vs-spec",
                "password.complexity#excludeAttributes", "Field 'excludeAttributes' of Y is in code but missing");
        List<Finding> out = ContractValidationService.collapseByRootCause(List.of(a, b));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getSummary())
                .contains("shared spec schema 'password.complexity'")
                .contains("Field 'excludeAttributes'");
    }

    @Test
    void fieldNameContainingHashKeepsItsFullNameInTheRewrite() {
        // A JSON field name CAN contain '#' (@JsonProperty("card#number")) while an OpenAPI schema name never can —
        // the rewrite must split at the FIRST '#': schema 'Payment', field 'card#number'.
        Finding a = specLocusFinding(FindingType.SCHEMA_FIELD_MISSING, "GET /pay", "A.java", 1, "code-vs-spec",
                "Payment#card#number", "Field 'card#number' of X is in code but missing");
        Finding b = specLocusFinding(FindingType.SCHEMA_FIELD_MISSING, "GET /pay/{id}", "B.java", 2, "code-vs-spec",
                "Payment#card#number", "Field 'card#number' of Y is in code but missing");
        List<Finding> out = ContractValidationService.collapseByRootCause(List.of(a, b));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getSummary())
                .contains("shared spec schema 'Payment'")
                .contains("Field 'card#number'");
    }

    @Test
    void typeMismatchOfTheSameKindMergesOnSpecLocusButKeepsItsOriginalSummary() {
        // Same mismatch KIND (integer~string) on the same spec field across two endpoints → one spec edit → merge.
        Finding a = specLocusFinding(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, "GET /policies", "A.java", 1, "code-vs-spec",
                "policies#amount#integer~string", "Field 'amount' type — code integer vs spec string");
        Finding b = specLocusFinding(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, "GET /policies/{app}", "B.java", 2,
                "code-vs-spec", "policies#amount#integer~string", "Field 'amount' type — code integer vs spec string");
        List<Finding> out = ContractValidationService.collapseByRootCause(List.of(a, b));
        assertThat(out).hasSize(1);
        // TYPE_MISMATCH is de-duplicated for scoring but keeps the first finding's wording (not rewritten).
        assertThat(out.get(0).getSummary()).isEqualTo("Field 'amount' type — code integer vs spec string");
        assertThat(out.get(0).getAffectedEndpoints()).containsExactly("GET /policies", "GET /policies/{app}");
    }

    @Test
    void typeMismatchesOfDifferentKindsOnTheSameSpecFieldDoNotMerge() {
        // Scalar drift on one endpoint, object-vs-scalar flip on another — two DIFFERENT defects (no single spec
        // edit fixes both), so the kind token in the locus must keep them apart.
        Finding scalarDrift = specLocusFinding(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, "GET /policies", "A.java", 1,
                "code-vs-spec", "policies#amount#integer~string", "Field 'amount' type — code integer vs spec string");
        Finding objectFlip = specLocusFinding(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, "GET /policies/{app}", "B.java", 2,
                "code-vs-spec", "policies#amount#object~scalar",
                "Field 'amount' of GET /policies/{app} response is a nested object/array in code but a scalar (string) in the spec");
        assertThat(ContractValidationService.rootCauseKey(scalarDrift))
                .isNotEqualTo(ContractValidationService.rootCauseKey(objectFlip));
        assertThat(ContractValidationService.collapseByRootCause(List.of(scalarDrift, objectFlip))).hasSize(2);
    }
}
