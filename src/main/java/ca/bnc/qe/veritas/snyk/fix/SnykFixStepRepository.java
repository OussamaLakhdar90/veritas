package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnykFixStepRepository extends JpaRepository<SnykFixStep, String> {

    List<SnykFixStep> findByTrainIdOrderByStepOrder(String trainId);
}
