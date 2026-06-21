package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoverageItemRepository extends JpaRepository<CoverageItem, String> {
    List<CoverageItem> findByTestPlanId(String testPlanId);
}
