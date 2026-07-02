package ca.bnc.qe.veritas.activity;

import ca.bnc.qe.veritas.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** "I've seen this" for one activity item — server-side so a dismissal survives reloads and browsers. */
@Entity
@Table(name = "activity_ack")
@Getter
@Setter
public class ActivityAck extends AuditableEntity {

    /** The acknowledged item's entity id (scan / fix train / codegen run). */
    @Column(length = 36, unique = true)
    private String itemId;
}
