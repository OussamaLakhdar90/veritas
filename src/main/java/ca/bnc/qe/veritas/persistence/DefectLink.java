package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Links a finding to the Jira defect created from it (tracks "Jira created or not" + cached status). */
@Entity
@Table(name = "defect_link", indexes = @Index(name = "idx_defect_finding", columnList = "findingId", unique = true))
@Getter
@Setter
public class DefectLink extends AuditableEntity {

    @Column(nullable = false, unique = true)
    private String findingId;

    private String scanId;
    private String jiraKey;
    private String jiraUrl;
    private String jiraStatus;
    private String jiraStatusCategory;
    private boolean createdInJira;
    private String createdBy;
    private Instant lastSyncedAt;
}
