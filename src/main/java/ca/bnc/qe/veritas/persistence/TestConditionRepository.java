package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestConditionRepository extends JpaRepository<TestCondition, String> {

    /** All conditions for a service, newest first (backs the service-scoped list view). */
    List<TestCondition> findByServiceNameOrderByCreatedAtDesc(String serviceName);

    /** Conditions derived from a given strategy, ordered by their list id (backs the Test Condition List doc/view). */
    List<TestCondition> findByTestStrategyIdOrderByConditionRefAsc(String testStrategyId);

    /** The current batch for a (service, strategy) — used to supersede on regeneration. */
    List<TestCondition> findByServiceNameAndTestStrategyId(String serviceName, String testStrategyId);
}
