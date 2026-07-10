package ca.bnc.qe.veritas.evolve;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassificationTrainRepository extends JpaRepository<ClassificationTrain, String> {

    List<ClassificationTrain> findAllByOrderByCreatedAtDesc();

    /** The single still-open (not-yet-MERGED) train for a type, if any — enforces one active proposal per type. */
    Optional<ClassificationTrain> findFirstByFindingTypeAndStatusNotOrderByCreatedAtDesc(String findingType,
                                                                                         String status);
}
