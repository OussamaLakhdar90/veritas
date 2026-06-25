package ca.bnc.qe.veritas.evidence.feature;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshot;
import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshotRepository;
import ca.bnc.qe.veritas.skill.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a multi-source preview's {@link FeatureIndexResult} as an editable {@link FeatureIndexSnapshot}, and
 * applies the deterministic ($0) override layer the §6 wizard exposes — <b>rename</b>, <b>merge</b>, and
 * <b>pin</b>. The snapshot is the single source of truth from preview through generate, so generating reuses the
 * already-extracted index (no second pipeline run / repo clone) and reflects the reviewer's edits.
 *
 * <p>Every edit re-runs the deterministic {@link GapDetector} over the edited index, so the coverage gaps (whose
 * messages embed the feature names and whose targets are feature ids) never drift from the clustering they describe.
 */
@Service
@Slf4j
public class FeatureIndexSnapshotService {

    private final FeatureIndexSnapshotRepository repository;
    private final GapDetector gapDetector;
    private final ObjectMapper mapper;
    private final Duration generationLease;

    public FeatureIndexSnapshotService(FeatureIndexSnapshotRepository repository, GapDetector gapDetector,
                                       ObjectMapper mapper,
                                       @Value("${veritas.multi-source.generation-lease-minutes:15}") long leaseMinutes) {
        this.repository = repository;
        this.gapDetector = gapDetector;
        this.mapper = mapper;
        this.generationLease = Duration.ofMinutes(leaseMinutes);
    }

    /** Persist a freshly-built preview result as an editable snapshot. */
    public FeatureIndexSnapshot create(String serviceName, FeatureIndexResult result, String owner) {
        FeatureIndexSnapshot s = new FeatureIndexSnapshot();
        s.setServiceName(serviceName);
        s.setOwner(owner);
        s.setResultJson(writeJson(result));
        s.setPinnedFeatureIds("[]");
        s.setEditsJson("[]");
        FeatureIndexSnapshot saved = repository.save(s);
        log.info("Persisted feature-index snapshot {} for {}: {} feature(s)", saved.getId(), serviceName,
                result.index().features().size());
        return saved;
    }

    /**
     * Persist a freshly re-extracted preview as a NEW snapshot, carrying the reviewer's edits forward from
     * {@code prior} (design §3.2 lineage re-run). The prior snapshot's {@link FeatureEdit} log is
     * {@link FeatureEditReplay replayed} onto the fresh index by unit-id overlap, so the reviewer's renames / merges
     * / pins survive the code/Jira/Confluence change instead of being re-done by hand. Edits whose features no
     * longer exist are reported (not guessed) in the returned notes. The log itself is copied onto the new snapshot
     * so a further re-run re-applies the same reviewer intent. A brand-new id is minted (the original is left intact
     * as history); ownership follows the principal doing the re-run.
     */
    public CarryForward createCarryingForward(String serviceName, FeatureIndexResult fresh, String owner,
                                              FeatureIndexSnapshot prior) {
        List<FeatureEdit> edits = editsOf(prior);
        FeatureEditReplay.Outcome outcome = FeatureEditReplay.apply(fresh, edits, gapDetector);
        FeatureIndexSnapshot s = new FeatureIndexSnapshot();
        s.setServiceName(serviceName);
        s.setOwner(owner);
        s.setResultJson(writeJson(outcome.result()));
        s.setPinnedFeatureIds(writeJson(new ArrayList<>(outcome.pins())));
        s.setEditsJson(writeJson(edits));
        s.setCarriedForwardFrom(prior.getId());
        FeatureIndexSnapshot saved = repository.save(s);
        log.info("Carried {} edit(s) forward from snapshot {} into {} for {} ({} could not be re-applied)",
                edits.size(), prior.getId(), saved.getId(), serviceName, outcome.notes().size());
        return new CarryForward(saved, outcome.notes());
    }

    /** A re-extracted snapshot plus any reviewer edits that could not be re-applied (their features vanished). */
    public record CarryForward(FeatureIndexSnapshot snapshot, List<String> notes) {}

    /** The reviewer-override log (rename/merge/pin), oldest first; empty for an unedited or pre-existing snapshot. */
    public List<FeatureEdit> editsOf(FeatureIndexSnapshot snapshot) {
        return readEdits(snapshot.getEditsJson());
    }

    public Optional<FeatureIndexSnapshot> find(String id) {
        return repository.findById(id);
    }

    public FeatureIndexResult resultOf(FeatureIndexSnapshot snapshot) {
        return readResult(snapshot.getResultJson());
    }

    public Set<String> pinnedOf(FeatureIndexSnapshot snapshot) {
        return readPins(snapshot.getPinnedFeatureIds());
    }

    /**
     * Atomically admit exactly one generation for a snapshot, BEFORE the (paid) synthesis runs. Re-reads the row
     * inside the transaction and, under the {@code @Version} optimistic lock, sets the claim marker — so two
     * concurrent generate calls can't both pass the check and double-spend on synthesis: the loser fails the
     * version check ({@code OptimisticLockingFailureException} → 409) or sees the claim/audit-link already set
     * ({@link ConflictException} → 409). Returns the claimed snapshot; the caller synthesizes outside this tx
     * (so no DB row is locked across the slow LLM calls) and then {@link #linkGenerated} or {@link #releaseClaim}.
     *
     * <p>The claim is a <b>lease</b>, not a permanent flag: a claim older than {@code generationLease} is treated
     * as abandoned (e.g. the process died mid-synthesis) and may be re-claimed, so a crash can't wedge a snapshot
     * non-generatable forever. A completed generation ({@code generatedStrategyId} set) stays a permanent reject.
     */
    @Transactional
    public FeatureIndexSnapshot claimForGeneration(String id) {
        FeatureIndexSnapshot s = repository.findById(id)
                .orElseThrow(() -> new ConflictException("This preview no longer exists — start a new one."));
        if (s.getGeneratedStrategyId() != null) {
            throw new ConflictException("This preview already generated strategy " + s.getGeneratedStrategyId()
                    + " — start a new preview to generate again.");
        }
        Instant inFlight = s.getGenerationStartedAt();
        if (inFlight != null && inFlight.isAfter(Instant.now().minus(generationLease))) {
            throw new ConflictException("A strategy is already being generated from this preview.");
        }
        s.setGenerationStartedAt(Instant.now());   // (re)claim — a claim older than the lease is abandoned, take it
        s.setGenerationError(null);                 // a fresh attempt clears any error from a previous failed run
        return repository.save(s);
    }

    /**
     * Record the generated strategy (audit link) and clear the claim — a column-scoped bulk update, so it neither
     * contends with the optimistic {@code @Version} of a concurrent feature edit nor throws if the row was swept
     * (it just affects 0 rows). Takes the id, not the detached entity handed back from {@link #claimForGeneration},
     * to avoid a stale-version stomp.
     */
    @Transactional
    public void linkGenerated(String id, String strategyId) {
        if (repository.linkGenerated(id, strategyId) == 0) {
            log.warn("Snapshot {} vanished before its generated strategy {} could be linked", id, strategyId);
        }
    }

    /** Release a generation claim so a legitimate retry can re-claim (column-scoped bulk update; never throws on a concurrent edit). */
    @Transactional
    public void releaseClaim(String id) {
        repository.releaseClaim(id);
    }

    /**
     * Record an async synthesis failure: release the claim AND store the error, so the polling wizard can surface it
     * (and a legitimate retry can re-claim). Column-scoped bulk update — never contends with a concurrent edit. The
     * message is truncated to keep parity with how {@code Scan.errorMessage} is stored and avoid column overflow.
     */
    @Transactional
    public void failGeneration(String id, String error) {
        String msg = error == null ? "Generation failed." : error.length() > 2000 ? error.substring(0, 2000) : error;
        repository.failGeneration(id, msg);
    }

    // ---- the override layer (deterministic, $0) -------------------------------------------------

    /** Rename one feature's display label. Its content-derived id and its membership are unchanged. */
    public FeatureIndexSnapshot rename(FeatureIndexSnapshot snapshot, String featureId, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A new feature name is required.");
        }
        FeatureIndexResult result = resultOf(snapshot);
        Feature target = require(result.index(), featureId);

        Map<String, Feature> features = new LinkedHashMap<>(result.index().features());
        features.put(featureId, new Feature(featureId, name.strip(), target.unitIds(), target.status()));
        return persist(snapshot, rebuild(result, features), pinnedOf(snapshot),
                FeatureEdit.rename(target.unitIds(), name.strip()));
    }

    /**
     * Merge two or more features into one — the manual cross-source merge the conservative seed and the LLM tagger
     * left separate. The merged feature unions the member units, recomputes its content-derived id and its
     * source-presence status, and inherits a pin if any of the merged features was pinned.
     */
    public FeatureIndexSnapshot merge(FeatureIndexSnapshot snapshot, List<String> featureIds, String name) {
        FeatureIndexResult result = resultOf(snapshot);
        FeatureIndex index = result.index();

        // Distinct, non-blank, order-preserving. Validate membership explicitly (naming any unknown id) BEFORE the
        // count check, so a stray/stale id is a clear 400 rather than a silently-dropped partial merge.
        List<String> ids = new ArrayList<>();
        for (String id : featureIds == null ? List.<String>of() : featureIds) {
            if (id != null && !id.isBlank() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        List<String> missing = ids.stream().filter(id -> !index.features().containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("No feature(s) " + missing + " in this snapshot.");
        }
        if (ids.size() < 2) {
            throw new IllegalArgumentException("Merging needs at least two distinct existing features.");
        }

        // Union the member unit ids (sorted, stable) → recompute the content-derived id with the seeder's scheme.
        Set<String> unitIdSet = new TreeSet<>();
        List<List<String>> sourceGroups = new ArrayList<>();
        for (String id : ids) {
            List<String> memberUnits = index.features().get(id).unitIds();
            unitIdSet.addAll(memberUnits);
            sourceGroups.add(List.copyOf(memberUnits));
        }
        List<String> mergedUnitIds = List.copyOf(unitIdSet);
        String mergedId = "feat-" + EvidenceId.hash8(String.join("|", mergedUnitIds));

        List<EvidenceUnit> mergedUnits = new ArrayList<>();
        for (String uid : mergedUnitIds) {
            EvidenceUnit u = index.unitsById().get(uid);
            if (u != null) {
                mergedUnits.add(u);
            }
        }
        String mergedName = (name != null && !name.isBlank()) ? name.strip() : largestName(index, ids);
        Feature merged = new Feature(mergedId, mergedName, mergedUnitIds, FeatureStatusEngine.statusOf(mergedUnits));

        // Rebuild the feature map: drop the merged-away features; put the new one where the first one was.
        Map<String, Feature> features = new LinkedHashMap<>();
        boolean placed = false;
        for (Map.Entry<String, Feature> e : index.features().entrySet()) {
            if (ids.contains(e.getKey())) {
                if (!placed) {
                    features.put(mergedId, merged);
                    placed = true;
                }
            } else {
                features.put(e.getKey(), e.getValue());
            }
        }

        // Carry the pins forward: a merged feature is pinned iff any of its sources was pinned.
        Set<String> pins = pinnedOf(snapshot);
        if (pins.removeAll(ids)) {
            pins.add(mergedId);
        }
        log.info("Merged features {} → {} ({} units) in snapshot {}", ids, mergedId, mergedUnitIds.size(),
                snapshot.getId());
        return persist(snapshot, rebuild(result, features), pins, FeatureEdit.merge(sourceGroups, mergedName));
    }

    /** Pin (reviewer-confirm / lock) or unpin a feature. A pin is metadata only; it never changes the clustering. */
    public FeatureIndexSnapshot pin(FeatureIndexSnapshot snapshot, String featureId, boolean pinned) {
        Feature target = require(resultOf(snapshot).index(), featureId);
        Set<String> pins = pinnedOf(snapshot);
        if (pinned) {
            pins.add(featureId);
        } else {
            pins.remove(featureId);
        }
        snapshot.setPinnedFeatureIds(writeJson(new ArrayList<>(pins)));
        appendEdit(snapshot, FeatureEdit.pin(target.unitIds(), pinned));
        return repository.save(snapshot);
    }

    // ---- internals ------------------------------------------------------------------------------

    /** A new result with the edited feature map, fresh deterministic gaps, and the original extraction. */
    private FeatureIndexResult rebuild(FeatureIndexResult original, Map<String, Feature> features) {
        FeatureIndex idx = original.index();
        FeatureIndex edited = new FeatureIndex(features, idx.unitsById(), idx.crossCuttingIds(),
                idx.unassignedUnitIds(), idx.mix(), idx.sourceDigest());
        return new FeatureIndexResult(edited, gapDetector.detect(edited), original.extraction());
    }

    private FeatureIndexSnapshot persist(FeatureIndexSnapshot snapshot, FeatureIndexResult result, Set<String> pins,
                                         FeatureEdit edit) {
        snapshot.setResultJson(writeJson(result));
        snapshot.setPinnedFeatureIds(writeJson(new ArrayList<>(pins)));
        appendEdit(snapshot, edit);
        return repository.save(snapshot);
    }

    /** Append one override to the snapshot's edit log (in memory; the caller's {@code save} persists it). */
    private void appendEdit(FeatureIndexSnapshot snapshot, FeatureEdit edit) {
        if (edit == null) {
            return;
        }
        List<FeatureEdit> log = new ArrayList<>(editsOf(snapshot));
        log.add(edit);
        snapshot.setEditsJson(writeJson(log));
    }

    private static Feature require(FeatureIndex index, String featureId) {
        // Guard null/blank BEFORE the lookup: index.features() is an immutable Map.copyOf, whose get(null) throws an
        // NPE (not returns null) — so an unchecked null would surface as a 500 instead of a clean 400.
        if (featureId == null || featureId.isBlank()) {
            throw new IllegalArgumentException("A feature id is required.");
        }
        Feature f = index.features().get(featureId);
        if (f == null) {
            throw new IllegalArgumentException("No feature '" + featureId + "' in this snapshot.");
        }
        return f;
    }

    /** The display name of the largest (most-units) of the merged features — the most representative default. */
    private static String largestName(FeatureIndex index, List<String> ids) {
        return ids.stream().map(index.features()::get).filter(Objects::nonNull)
                .max(Comparator.comparingInt(f -> f.unitIds().size()))
                .map(Feature::displayName).orElse(ids.get(0));
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize feature-index snapshot state", e);
        }
    }

    private FeatureIndexResult readResult(String json) {
        try {
            return mapper.readValue(json, FeatureIndexResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt feature-index snapshot", e);
        }
    }

    private Set<String> readPins(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            return new LinkedHashSet<>(mapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt pinned-feature ids in snapshot", e);
        }
    }

    private List<FeatureEdit> readEdits(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<FeatureEdit>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt feature-edit log in snapshot", e);
        }
    }
}
