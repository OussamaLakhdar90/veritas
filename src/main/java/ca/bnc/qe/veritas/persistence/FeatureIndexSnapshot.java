package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * A persisted, editable feature index from a multi-source <b>preview</b> (design §6). It lets the §6 wizard
 * preview the clustered features <b>once</b>, edit them (merge / rename / pin), and then generate the strategy
 * from the <b>same</b> index — instead of re-running the whole extract → seed → tag pipeline (and re-cloning the
 * code arm) a second time on generate.
 *
 * <p>{@code resultJson} is the serialized {@code FeatureIndexResult} (the feature index + deterministic gaps +
 * extraction provenance) — the editable spine. The evidence text it holds is already redacted at extraction
 * ({@code Redactor}); a snapshot is session-scoped working state (a TTL/cleanup sweep is a documented follow-up,
 * like the temp-clone-dir cleanup).
 */
@Entity
@Table(name = "feature_index_snapshot")
@Getter
@Setter
public class FeatureIndexSnapshot extends AuditableEntity {

    private String serviceName;
    private String owner;

    /** The serialized {@code FeatureIndexResult} (index + gaps + extraction). Mutated in place by the edit layer. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String resultJson;

    /** Reviewer-pinned (confirmed/locked) feature ids, as a JSON string array. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String pinnedFeatureIds;

    /**
     * The ordered log of reviewer overrides (rename/merge/pin) as a JSON array of {@code FeatureEdit}, keyed by the
     * affected features' member unit ids — so the edits can be <b>carried forward</b> onto a freshly re-extracted
     * index (design §3.2 lineage re-run) rather than being lost when the code/Jira/Confluence sources change.
     * Nullable: pre-existing snapshots (and an unedited preview) have no log, read as empty.
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String editsJson;

    /** The snapshot this one was re-extracted from (carry-forward lineage); null for an original preview. */
    private String carriedForwardFrom;

    /** Set once a strategy is generated from this snapshot (audit link back to the {@link TestStrategy}); null until then. */
    private String generatedStrategyId;

    /**
     * Set when an <b>async</b> generation fails (the claim is released at the same moment). The poll keys "failed" on
     * this being non-null — NOT on the absence of a claim — so a poll landing between the claim and the worker's
     * first write can't be misread as a failure. Cleared on a fresh claim and on a successful link.
     */
    private String generationError;

    /**
     * Claim marker for generation: set (under the optimistic lock) the moment a generate is admitted, so a
     * concurrent second generate is rejected <b>before</b> the expensive, paid synthesis runs — not after. Cleared
     * if that synthesis fails, so a legitimate retry can re-claim. {@code null} = no generation in flight.
     */
    private Instant generationStartedAt;

    /**
     * Optimistic-lock guard: every edit (rename/merge/pin) is a read-modify-write over the whole {@code resultJson}
     * blob, so two overlapping edits to the same snapshot would otherwise be a silent lost-update. A stale write
     * fails loudly (mapped to 409) instead of clobbering the other reviewer's change. Scoped to this entity (not
     * {@link AuditableEntity}) to keep the blast radius small, mirroring {@code Scan}.
     */
    @Version
    private Long version;
}
