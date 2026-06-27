package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Record of a template-driven test-generation run (files written, build status, PR, cost). */
@Entity
@Table(name = "codegen_run")
@Getter
@Setter
public class CodegenRun extends AuditableEntity {

    private String serviceName;
    private String templateSource;
    private String outputRepo;
    private String jiraKey;          // work item this run commits under — prefixes the branch/commit/PR so Jira links it
    private String branch;
    private String prUrl;
    private String buildStatus;     // PASS | FAIL | REPAIRED | SKIPPED

    @Lob
    @Column(columnDefinition = "TEXT")
    private String filesWritten;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String todos;

    private String approvedBy;
    private double estCostUsd;
}
