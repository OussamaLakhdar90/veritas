package ca.bnc.qe.veritas.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DefectLinkRepository extends JpaRepository<DefectLink, String> {
    Optional<DefectLink> findByFindingId(String findingId);
    List<DefectLink> findByCreatedInJiraTrueAndJiraStatusCategoryNot(String doneCategory);
    List<DefectLink> findAllByOrderByCreatedAtDesc();

    /**
     * Links the status sweep should refresh: created in Jira, with a key, and NOT already closed — but INCLUDING
     * never-synced rows (null category), which a plain {@code …CategoryNot('done')} would wrongly exclude (SQL NULL
     * never satisfies {@code <> 'done'}). Replaces a full-table {@code findAll()} scan in DefectSyncService.
     */
    @Query("select d from DefectLink d where d.createdInJira = true and d.jiraKey is not null and d.jiraKey <> '' "
            + "and (d.jiraStatusCategory is null or lower(d.jiraStatusCategory) <> 'done')")
    List<DefectLink> findNeedingStatusSync();
}
