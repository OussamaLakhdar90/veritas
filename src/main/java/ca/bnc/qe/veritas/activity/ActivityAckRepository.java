package ca.bnc.qe.veritas.activity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityAckRepository extends JpaRepository<ActivityAck, String> {
}
