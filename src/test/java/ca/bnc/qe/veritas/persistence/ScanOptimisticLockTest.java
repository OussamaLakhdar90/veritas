package ca.bnc.qe.veritas.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The Scan {@code @Version} guards against a lost update: while the worker thread issues many progress saves, the
 * stale-timeout {@code ScanReconciler} may concurrently mark a long-running scan FAILED. Without versioning, a
 * stale worker save would silently overwrite that FAILED and resurrect a dead scan. With it, the stale save is
 * rejected and the newer (authoritative) write survives.
 */
@SpringBootTest
class ScanOptimisticLockTest {

    @Autowired
    private ScanRepository scans;

    @Test
    void staleScanSaveIsRejectedAndNewerWriteSurvives() {
        Scan s = new Scan();
        s.setServiceName("opt-lock-svc");
        s.setStatus(RunStatus.RUNNING);
        s.setStage("QUEUED");
        Scan stale = scans.save(s);                       // version 0 — our stale snapshot
        String id = stale.getId();

        Scan fresh = scans.findById(id).orElseThrow();    // a separate copy, still version 0
        fresh.setStage("CLONING");
        scans.save(fresh);                                // DB version 0 -> 1

        stale.setStage("EXTRACTING");                     // still version 0 — must conflict with DB version 1
        assertThatThrownBy(() -> scans.save(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // the newer write was not clobbered
        assertThat(scans.findById(id).orElseThrow().getStage()).isEqualTo("CLONING");
    }
}
