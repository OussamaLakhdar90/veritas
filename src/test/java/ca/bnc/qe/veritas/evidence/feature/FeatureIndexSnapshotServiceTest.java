package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import ca.bnc.qe.veritas.skill.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The persisted, editable feature index (§6): the {@link FeatureIndexResult} round-trips through JSON losslessly,
 * and the deterministic override layer (rename / merge / pin) behaves — recomputing the content-derived id and the
 * status on merge, carrying pins, and refreshing the gaps so they never drift from the edited clustering.
 */
class FeatureIndexSnapshotServiceTest {

    private static final String JIRA_ID = "JIRA-1";
    private static final String CODE_ID = "CODE:PolicyController#GET /policies";

    private FeatureIndexSnapshotRepository repository;
    private FeatureIndexSnapshotService service;

    @BeforeEach
    void setUp() {
        repository = mock(FeatureIndexSnapshotRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new FeatureIndexSnapshotService(repository, new GapDetector(),
                new ObjectMapper().findAndRegisterModules(), 15);   // 15-minute generation lease
    }

    private FeatureIndexResult sampleResult() {
        EvidenceUnit jira = EvidenceUnit.of(JIRA_ID, SourceKind.JIRA, UnitType.REQUIREMENT,
                "Get policy", "As a caller I can get a policy by id.", "https://jira/JIRA-1", Set.of("policy"));
        EvidenceUnit code = EvidenceUnit.of(CODE_ID, SourceKind.CODE, UnitType.ENDPOINT,
                "GET /policies", "Returns the policies.", null, Set.of("policy"));
        Map<String, EvidenceUnit> byId = Map.of(JIRA_ID, jira, CODE_ID, code);
        Map<String, Feature> features = new java.util.LinkedHashMap<>();
        features.put("feat-jira", new Feature("feat-jira", "Get policy", List.of(JIRA_ID), FeatureStatus.PLANNED));
        features.put("feat-code", new Feature("feat-code", "GET /policies", List.of(CODE_ID), FeatureStatus.UNDOCUMENTED));
        SourceMix mix = new SourceMix(true, true, false);
        FeatureIndex index = new FeatureIndex(features, byId, Set.of(), Set.of(), mix, "src-test");
        ExtractionResult extraction = new ExtractionResult(List.of(jira, code),
                new FetchProvenance(Map.of(
                        SourceKind.JIRA, new FetchProvenance.Counts(1, 1, List.of()),
                        SourceKind.CODE, new FetchProvenance.Counts(1, 1, List.of()))),
                mix, 0, Set.of(SourceKind.JIRA, SourceKind.CODE));
        return new FeatureIndexResult(index, new GapDetector().detect(index), extraction);
    }

    @Test
    void persistsAndRoundTripsTheResultLosslessly() {
        FeatureIndexResult original = sampleResult();
        FeatureIndexSnapshot saved = service.create("ciam-policies", original, "alice");

        assertThat(saved.getServiceName()).isEqualTo("ciam-policies");
        assertThat(saved.getOwner()).isEqualTo("alice");
        assertThat(service.resultOf(saved)).isEqualTo(original);   // deep value equality across the record graph
        assertThat(service.pinnedOf(saved)).isEmpty();
    }

    @Test
    void renameChangesOnlyTheNameAndRefreshesTheGapText() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");

        FeatureIndexResult after = service.resultOf(service.rename(saved, "feat-jira", "  Get a policy by id  "));

        Feature renamed = after.index().features().get("feat-jira");
        assertThat(renamed.displayName()).isEqualTo("Get a policy by id");   // trimmed
        assertThat(renamed.featureId()).isEqualTo("feat-jira");               // id + membership unchanged
        assertThat(renamed.unitIds()).containsExactly(JIRA_ID);
        assertThat(renamed.status()).isEqualTo(FeatureStatus.PLANNED);
        // The PLANNED gap message embeds the display name → it must track the rename.
        assertThat(after.gaps().gaps()).anyMatch(g -> g.message().contains("Get a policy by id"));
    }

    @Test
    void mergeUnionsUnitsRecomputesIdAndStatusAndCarriesThePin() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        service.pin(saved, "feat-code", true);   // pin one of the two before merging

        FeatureIndexSnapshot merged = service.merge(saved, List.of("feat-jira", "feat-code"), "Policies");
        FeatureIndexResult after = service.resultOf(merged);

        assertThat(after.index().features()).hasSize(1);
        String expectedId = "feat-" + EvidenceId.hash8(CODE_ID + "|" + JIRA_ID);   // sorted union, seeder's scheme
        Feature f = after.index().features().get(expectedId);
        assertThat(f).isNotNull();
        assertThat(f.displayName()).isEqualTo("Policies");
        assertThat(f.unitIds()).containsExactly(CODE_ID, JIRA_ID);                 // sorted
        assertThat(f.status()).isEqualTo(FeatureStatus.IMPLEMENTED);               // code + intent
        // The pin followed the merge onto the new id.
        assertThat(service.pinnedOf(merged)).containsExactly(expectedId);
        // No more PLANNED/UNDOCUMENTED presence gaps — the split was the gap, and it's gone.
        assertThat(after.gaps().gaps()).noneMatch(g -> g.kind() == GapKind.PLANNED_NOT_IMPLEMENTED
                || g.kind() == GapKind.IMPLEMENTED_UNDOCUMENTED);
    }

    @Test
    void mergeDefaultsTheNameToTheLargestFeature() {
        FeatureIndexResult base = sampleResult();
        FeatureIndexSnapshot saved = service.create("svc", base, "alice");

        FeatureIndexResult after = service.resultOf(service.merge(saved, List.of("feat-jira", "feat-code"), null));

        // Both features have one unit → tie → the first listed wins ("Get policy").
        assertThat(after.index().features().values()).first()
                .extracting(Feature::displayName).isEqualTo("Get policy");
    }

    @Test
    void claimForGenerationMarksTheClaimAndRejectsASecondClaim() {
        FeatureIndexSnapshot s = service.create("svc", sampleResult(), "alice");
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        FeatureIndexSnapshot claimed = service.claimForGeneration(s.getId());
        assertThat(claimed.getGenerationStartedAt()).isNotNull();

        // A second claim (a concurrent/repeat generate) sees the in-flight marker → conflict, before any synthesis.
        assertThatThrownBy(() -> service.claimForGeneration(s.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void claimForGenerationRejectsAnAlreadyGeneratedSnapshot() {
        FeatureIndexSnapshot s = service.create("svc", sampleResult(), "alice");
        s.setGeneratedStrategyId("strat-1");
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.claimForGeneration(s.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void anAbandonedClaimPastTheLeaseCanBeReClaimed() {
        FeatureIndexSnapshot s = service.create("svc", sampleResult(), "alice");
        s.setGenerationStartedAt(Instant.now().minus(java.time.Duration.ofMinutes(30)));   // past the 15-min lease
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        FeatureIndexSnapshot reclaimed = service.claimForGeneration(s.getId());   // abandoned → re-claimable

        assertThat(reclaimed.getGenerationStartedAt()).isAfter(Instant.now().minus(java.time.Duration.ofMinutes(1)));
    }

    @Test
    void linkGeneratedAndReleaseClaimAreColumnScopedBulkUpdates() {
        when(repository.linkGenerated("snap-1", "strat-1")).thenReturn(1);

        service.linkGenerated("snap-1", "strat-1");
        service.releaseClaim("snap-1");

        verify(repository).linkGenerated("snap-1", "strat-1");   // not a save() of a (possibly stale) detached entity
        verify(repository).releaseClaim("snap-1");
    }

    @Test
    void rejectsBadEdits() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");

        assertThatThrownBy(() -> service.rename(saved, "feat-jira", "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename(saved, "no-such-feature", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(saved, List.of("feat-jira"), null))
                .isInstanceOf(IllegalArgumentException.class);   // need ≥2
        assertThatThrownBy(() -> service.merge(saved, List.of("feat-jira", "no-such-feature"), null))
                .isInstanceOf(IllegalArgumentException.class);   // only one is real
        assertThatThrownBy(() -> service.pin(saved, "no-such-feature", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
