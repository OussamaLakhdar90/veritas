package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * S13j-1 regression guard: the LLM enrich/dispute graft keys on the id the LLM saw (the PRE-collapse finding id).
 * A spec-keyed multi-endpoint collapse re-fingerprints the survivor, so grafting AFTER the collapse always missed
 * the shared-schema case — explanation/proposedFix silently dropped and a disputed false positive never marked
 * {@code aiDisputed}. The graft must therefore run in the real pipeline order (dedup → graft → collapse), and the
 * survivor must carry those fields through the collapse; {@link ScanPersistence} must also persist the explanation
 * even when the on-disk enrich overlay is keyed on the pre-collapse id.
 */
class SharedSpecSchemaEnrichmentCarryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Two SCHEMA_FIELD_MISSING with the SAME shared spec locus/source on two endpoints, each with its OWN (distinct)
     *  code evidence — so ONLY the spec locus can merge them (the code-locus key never would). */
    private List<Finding> twoSharedSchemaFindings() {
        Finding a = Finding.builder().findingId("A-original").type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                .endpoint("GET /policies").specSource("code-vs-spec").specLocus("policies#excludeAttributes")
                .codeEvidence(SourceRef.code("PolicyWrapperA.java", 3, 3, null))
                .summary("Field 'excludeAttributes' of policies is in code but missing from the spec schema").build();
        Finding b = Finding.builder().findingId("B-original").type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                .endpoint("GET /policies/{app}").specSource("code-vs-spec").specLocus("policies#excludeAttributes")
                .codeEvidence(SourceRef.code("PolicyWrapperB.java", 3, 3, null))
                .summary("Field 'excludeAttributes' of policies is in code but missing from the spec schema").build();
        return List.of(a, b);
    }

    private JsonNode enrichNode(String explanation, String proposedFix) {
        return mapper.createObjectNode().put("explanation", explanation).put("proposedFix", proposedFix);
    }

    @Test
    void survivorCarriesEnrichmentAndDisputeGraftedUnderPreCollapseId() {
        List<Finding> raw = twoSharedSchemaFindings();
        // The LLM saw the PRE-collapse findings and keyed its enrichment + dispute on the FIRST occurrence's id.
        Map<String, JsonNode> enrich = new HashMap<>();
        enrich.put("A-original", enrichNode("shared schema is missing this field", "add excludeAttributes to policies"));
        Map<String, String> disputes = new HashMap<>();
        disputes.put("A-original", "field is intentionally internal-only");

        // Real pipeline order: dedup → graft → collapse.
        List<Finding> deduped = ContractValidationService.dedupCrossList(raw);
        List<Finding> grafted = ContractValidationService.graftEnrichment(deduped, enrich, disputes);
        List<Finding> collapsed = ContractValidationService.collapseByRootCause(grafted);

        List<Finding> missing = collapsed.stream()
                .filter(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING).toList();
        assertThat(missing).hasSize(1);
        Finding survivor = missing.get(0);
        assertThat(survivor.getAffectedEndpoints()).hasSize(2);
        assertThat(survivor.getExplanation()).isEqualTo("shared schema is missing this field");
        assertThat(survivor.getProposedFix()).isEqualTo("add excludeAttributes to policies");
        assertThat(survivor.isAiDisputed()).isTrue();
        assertThat(survivor.getAiDisputeReason()).isEqualTo("field is intentionally internal-only");
        // The survivor was re-fingerprinted on the root cause, so its id is NOT the pre-collapse id the LLM keyed on.
        assertThat(survivor.getFindingId()).isNotEqualTo("A-original");
    }

    @Test
    void graftingAfterCollapseWouldMissTheReKeyedSurvivor() {
        // This is the regression itself: grafting AFTER the collapse (the old order) keys on the survivor's re-keyed
        // id, which the LLM never saw — so the enrichment/dispute is silently dropped. Grafting BEFORE the collapse
        // (the fix) catches each pre-collapse finding under the id the LLM actually keyed on.
        List<Finding> raw = twoSharedSchemaFindings();
        Map<String, JsonNode> enrich = new HashMap<>();
        enrich.put("A-original", enrichNode("shared schema is missing this field", "add excludeAttributes to policies"));
        Map<String, String> disputes = new HashMap<>();
        disputes.put("A-original", "field is intentionally internal-only");

        // OLD order: collapse first, then graft on the re-keyed survivor → MISS.
        List<Finding> oldOrder = ContractValidationService.graftEnrichment(
                ContractValidationService.collapseByRootCause(ContractValidationService.dedupCrossList(raw)),
                enrich, disputes);
        Finding oldSurvivor = oldOrder.stream()
                .filter(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING).findFirst().orElseThrow();
        assertThat(oldSurvivor.getExplanation()).isNull();
        assertThat(oldSurvivor.isAiDisputed()).isFalse();

        // NEW order (the fix): graft first, then collapse → the survivor carries the enrichment + dispute through.
        Finding newSurvivor = ContractValidationService.collapseByRootCause(
                ContractValidationService.graftEnrichment(ContractValidationService.dedupCrossList(raw), enrich, disputes))
                .stream().filter(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING).findFirst().orElseThrow();
        assertThat(newSurvivor.getExplanation()).isEqualTo("shared schema is missing this field");
        assertThat(newSurvivor.isAiDisputed()).isTrue();
    }

    @Test
    void scanPersistenceEmitsFindingExplanationEvenWhenOverlayMissesId() {
        ScanRepository scanRepository = mock(ScanRepository.class);
        FindingRecordRepository findingRepository = mock(FindingRecordRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(findingRepository.findPriorDispositions(anyList(), any())).thenReturn(List.of());
        ScanPersistence persistence = new ScanPersistence(scanRepository, findingRepository, txManager);

        // A re-keyed survivor as it leaves validate(): the explanation is already grafted onto the in-memory finding,
        // but the on-disk enrich overlay is keyed on the PRE-collapse id, so it MISSES this survivor's new id. The
        // record must still persist the finding's own explanation (the ScanPersistence seed) rather than dropping it.
        Finding survivor = Finding.builder().findingId("re-keyed-root-hash").type(FindingType.SCHEMA_FIELD_MISSING)
                .layer(Layer.L4).severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                .endpoint("GET /policies").specSource("code-vs-spec").specLocus("policies#excludeAttributes")
                .codeEvidence(SourceRef.code("PolicyWrapperA.java", 3, 3, null))
                .summary("Field 'excludeAttributes' of shared spec schema 'policies' is in code but missing from the spec schema")
                .explanation("shared schema is missing this field").build();
        Map<String, JsonNode> overlayMissingThisId = new HashMap<>();
        overlayMissingThisId.put("A-original", enrichNode("stale", "stale"));   // keyed on the pre-collapse id — a miss

        persistence.complete(new Scan(), List.of(survivor), overlayMissingThisId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FindingRecord>> saved = ArgumentCaptor.forClass((Class) List.class);
        verify(findingRepository).saveAll(saved.capture());
        List<FindingRecord> records = saved.getValue();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getExplanation()).isEqualTo("shared schema is missing this field");
    }
}
