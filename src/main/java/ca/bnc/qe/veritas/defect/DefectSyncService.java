package ca.bnc.qe.veritas.defect;

import java.time.Instant;
import java.util.List;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraStatus;
import ca.bnc.qe.veritas.persistence.DefectLink;
import ca.bnc.qe.veritas.persistence.DefectLinkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Refreshes the cached Jira status of every created defect (read-only outward call). Each link is synced
 * independently so one failing issue never aborts the batch; the cached {@code jiraStatus}/
 * {@code jiraStatusCategory} feed the dashboard's live-status column without per-render Jira calls.
 */
@Service
@Slf4j
public class DefectSyncService {

    private final DefectLinkRepository defects;
    private final JiraClient jira;

    public DefectSyncService(DefectLinkRepository defects, JiraClient jira) {
        this.defects = defects;
        this.jira = jira;
    }

    /** Sync every open linked defect (skips already-closed ones, includes never-synced). Returns how many changed. */
    public int syncAll() {
        List<DefectLink> links = defects.findNeedingStatusSync();
        int updated = 0;
        for (DefectLink link : links) {
            if (sync(link)) {
                updated++;
            }
        }
        log.info("Defect status sync: {} link(s) checked, {} updated", links.size(), updated);
        return updated;
    }

    /** Sync one link. Returns true if its cached status changed. Never throws — failures are logged. */
    public boolean sync(DefectLink link) {
        try {
            JiraStatus status = jira.getStatus(link.getJiraKey());
            boolean changed = !equalsNullSafe(link.getJiraStatus(), status.name())
                    || !equalsNullSafe(link.getJiraStatusCategory(), status.categoryKey());
            link.setJiraStatus(status.name());
            link.setJiraStatusCategory(status.categoryKey());
            link.setLastSyncedAt(Instant.now());
            defects.save(link);
            return changed;
        } catch (Exception e) {
            log.warn("Defect status sync failed for {}: {}", link.getJiraKey(), e.getMessage());
            return false;
        }
    }

    private static boolean equalsNullSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
