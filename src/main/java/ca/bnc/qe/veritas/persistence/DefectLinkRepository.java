package ca.bnc.qe.veritas.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefectLinkRepository extends JpaRepository<DefectLink, String> {
    Optional<DefectLink> findByFindingId(String findingId);
    List<DefectLink> findByCreatedInJiraTrueAndJiraStatusCategoryNot(String doneCategory);
    List<DefectLink> findAllByOrderByCreatedAtDesc();
}
