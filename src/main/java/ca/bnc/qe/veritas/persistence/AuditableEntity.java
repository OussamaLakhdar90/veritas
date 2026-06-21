package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Base for all entities: assigned UUID id (portable to Postgres) + create/update timestamps. */
@MappedSuperclass
@Getter
@Setter
public abstract class AuditableEntity {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
