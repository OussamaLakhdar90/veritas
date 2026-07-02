package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnykFixStepRepository extends JpaRepository<SnykFixStep, String> {

    List<SnykFixStep> findByTrainIdOrderByStepOrder(String trainId);

    /** Drop a train's steps so a confirm-time re-plan replaces them (rather than duplicating). */
    void deleteByTrainId(String trainId);

    /** How many PRs the fix engine has actually opened across all trains (a step gets a prUrl when its PR opens). */
    long countByPrUrlIsNotNull();
}
