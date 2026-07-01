package ca.bnc.qe.veritas.snyk;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a snapshot and its vulnerabilities atomically, so a reader never sees a snapshot with a count that
 * disagrees with its stored {@link SnykVuln} rows.
 */
@Component
public class SnykScanPersistence {

    private final SnykSnapshotRepository snapshots;
    private final SnykVulnRepository vulns;

    public SnykScanPersistence(SnykSnapshotRepository snapshots, SnykVulnRepository vulns) {
        this.snapshots = snapshots;
        this.vulns = vulns;
    }

    @Transactional
    public SnykSnapshot save(SnykSnapshot snapshot, List<SnykVuln> vulnRows) {
        SnykSnapshot saved = snapshots.save(snapshot);
        for (SnykVuln v : vulnRows) {
            v.setSnapshotId(saved.getId());
        }
        vulns.saveAll(vulnRows);
        return saved;
    }
}
