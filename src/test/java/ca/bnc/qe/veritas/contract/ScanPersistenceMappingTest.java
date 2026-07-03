package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Write-side mapping: {@code complete()} must copy onto the persisted {@link FindingRecord} every field the
 *  read-side {@link FindingMapper} rebuilds — here the spec-locus round-trip half that only the write side owns. */
class ScanPersistenceMappingTest {

    @Test
    void writesTheSpecLocusToTheFindingRecordNullSafe() {
        ScanRepository scanRepository = mock(ScanRepository.class);
        FindingRecordRepository findingRepository = mock(FindingRecordRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(findingRepository.findPriorDispositions(anyList(), any())).thenReturn(List.of());
        ScanPersistence persistence = new ScanPersistence(scanRepository, findingRepository, txManager);

        Finding withLocus = Finding.builder().findingId("f1").type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                .endpoint("GET /policies").specSource("code-vs-spec")
                .specLocus("password.complexity#excludeAttributes")
                .summary("Field 'excludeAttributes' is in code but missing from the spec schema").build();
        Finding withoutLocus = withLocus.toBuilder().findingId("f2").specLocus(null).summary("no locus").build();

        persistence.complete(new Scan(), List.of(withLocus, withoutLocus), Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FindingRecord>> saved = ArgumentCaptor.forClass((Class) List.class);
        verify(findingRepository).saveAll(saved.capture());
        List<FindingRecord> records = saved.getValue();
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getSpecLocus()).isEqualTo("password.complexity#excludeAttributes");
        assertThat(records.get(1).getSpecLocus()).isNull();
    }
}
