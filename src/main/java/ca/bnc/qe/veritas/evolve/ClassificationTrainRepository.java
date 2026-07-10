package ca.bnc.qe.veritas.evolve;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassificationTrainRepository extends JpaRepository<ClassificationTrain, String> {

    List<ClassificationTrain> findAllByOrderByCreatedAtDesc();

    /** The most recent train for a type (any status): refresh updates a non-terminal one, or respects a terminal
     *  (MERGED / DISMISSED) one by not re-proposing — enforcing one active proposal per type. */
    Optional<ClassificationTrain> findFirstByFindingTypeOrderByCreatedAtDesc(String findingType);
}
