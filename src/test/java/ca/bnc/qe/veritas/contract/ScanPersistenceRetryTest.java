package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * complete() must survive a transient SQLite write-lock collision: under concurrent scans the finishing writer
 * can hit SQLITE_BUSY — before the bounded retry, a fully successful scan was reported FAILED with a raw
 * "database is locked" error. Non-lock failures must still fail fast (no blind retrying).
 */
class ScanPersistenceRetryTest {

    private ScanRepository scanRepository;
    private FindingRecordRepository findingRepository;
    private ScanPersistence persistence;
    private Scan scan;

    @BeforeEach
    void setUp() {
        scanRepository = mock(ScanRepository.class);
        findingRepository = mock(FindingRecordRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        persistence = new ScanPersistence(scanRepository, findingRepository, txManager);
        scan = new Scan();
        when(findingRepository.findPriorDispositions(anyList(), any())).thenReturn(List.of());
    }

    private static RuntimeException busy() {
        return new RuntimeException("could not execute statement",
                new RuntimeException("[SQLITE_BUSY] The database file is locked (database is locked)"));
    }

    @Test
    void retriesThroughTransientLockAndSucceeds() {
        when(findingRepository.saveAll(anyList()))
                .thenThrow(busy())
                .thenThrow(busy())
                .thenAnswer(inv -> inv.getArgument(0));

        persistence.complete(scan, List.of(), Map.of());

        verify(findingRepository, times(3)).saveAll(anyList());
        verify(scanRepository, times(1)).save(scan);   // only the successful attempt reaches the scan write
    }

    @Test
    void exhaustsRetriesThenSurfacesTheLockError() {
        when(findingRepository.saveAll(anyList())).thenThrow(busy());

        assertThatThrownBy(() -> persistence.complete(scan, List.of(), Map.of()))
                .hasStackTraceContaining("SQLITE_BUSY");
        verify(findingRepository, times(3)).saveAll(anyList());
    }

    @Test
    void nonLockFailureFailsFastWithoutRetry() {
        when(findingRepository.saveAll(anyList())).thenThrow(new IllegalStateException("constraint violation"));

        assertThatThrownBy(() -> persistence.complete(scan, List.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        verify(findingRepository, times(1)).saveAll(anyList());
    }

    @Test
    void busyDetectionWalksTheCauseChain() {
        assertThat(ScanPersistence.isSqliteBusy(busy())).isTrue();
        assertThat(ScanPersistence.isSqliteBusy(new RuntimeException("database is locked"))).isTrue();
        assertThat(ScanPersistence.isSqliteBusy(new RuntimeException("boom"))).isFalse();
        assertThat(ScanPersistence.isSqliteBusy(new RuntimeException((String) null))).isFalse();
    }
}
