package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.ExtractionResult;
import ca.bnc.qe.veritas.evidence.FetchProvenance;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshot;
import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * The persisted feature-index snapshot against the REAL Spring context + DB: proves the production Jackson config
 * round-trips the {@link FeatureIndexResult} graph losslessly (the unit test uses a hand-built mapper), the JPA
 * mapping (the {@code TEXT} columns) survives a save→reload, and the {@code @Version} optimistic lock actually
 * rejects a stale concurrent edit (the §6 lost-update guard).
 */
@SpringBootTest
class FeatureIndexSnapshotIT {

    @Autowired private FeatureIndexSnapshotService service;
    @Autowired private FeatureIndexSnapshotRepository repository;

    private static final String JIRA_ID = "JIRA-1";
    private static final String CODE_ID = "CODE:PolicyController#GET /policies";

    private static FeatureIndexResult sample() {
        EvidenceUnit jira = EvidenceUnit.of(JIRA_ID, SourceKind.JIRA, UnitType.REQUIREMENT,
                "Get policy", "Get a policy by id.", "https://jira/JIRA-1", Set.of("policy"));
        EvidenceUnit code = EvidenceUnit.of(CODE_ID, SourceKind.CODE, UnitType.ENDPOINT,
                "GET /policies", "Returns the policies.", null, Set.of("policy"));
        Map<String, EvidenceUnit> byId = Map.of(JIRA_ID, jira, CODE_ID, code);
        Map<String, Feature> features = new LinkedHashMap<>();
        features.put("feat-jira", new Feature("feat-jira", "Get policy", List.of(JIRA_ID), FeatureStatus.PLANNED));
        features.put("feat-code", new Feature("feat-code", "GET /policies", List.of(CODE_ID), FeatureStatus.UNDOCUMENTED));
        SourceMix mix = new SourceMix(true, true, false);
        FeatureIndex index = new FeatureIndex(features, byId, Set.of(), Set.of(), mix, "src-it");
        ExtractionResult extraction = new ExtractionResult(List.of(jira, code),
                new FetchProvenance(Map.of(
                        SourceKind.JIRA, new FetchProvenance.Counts(1, 1, List.of()),
                        SourceKind.CODE, new FetchProvenance.Counts(1, 1, List.of()))),
                mix, 0, Set.of(SourceKind.JIRA, SourceKind.CODE));
        return new FeatureIndexResult(index, new GapDetector().detect(index), extraction);
    }

    @Test
    void persistsAndReloadsLosslesslyThroughTheRealMapperAndDb() {
        FeatureIndexResult original = sample();
        String id = service.create("ciam-policies", original, "alice").getId();

        FeatureIndexSnapshot reloaded = repository.findById(id).orElseThrow();
        assertThat(service.resultOf(reloaded)).isEqualTo(original);   // production ObjectMapper + TEXT column round-trip
    }

    @Test
    void mergeIsPersistedAndReloadable() {
        FeatureIndexSnapshot saved = service.create("svc", sample(), "alice");
        service.merge(saved, List.of("feat-jira", "feat-code"), "Policies");

        FeatureIndexResult after = service.resultOf(repository.findById(saved.getId()).orElseThrow());
        String expectedId = "feat-" + EvidenceId.hash8(CODE_ID + "|" + JIRA_ID);
        assertThat(after.index().features()).hasSize(1).containsKey(expectedId);
        assertThat(after.index().features().get(expectedId).status()).isEqualTo(FeatureStatus.IMPLEMENTED);
    }

    @Test
    void aStaleConcurrentEditIsRejectedByTheOptimisticLock() {
        String id = service.create("svc", sample(), "alice").getId();
        FeatureIndexSnapshot first = repository.findById(id).orElseThrow();
        FeatureIndexSnapshot stale = repository.findById(id).orElseThrow();   // a second, independently-loaded copy

        service.rename(first, "feat-jira", "Renamed first");                  // bumps the version in the DB

        assertThatThrownBy(() -> service.rename(stale, "feat-jira", "Renamed stale"))
                .isInstanceOf(OptimisticLockingFailureException.class);       // the stale write loses, loudly
    }
}
