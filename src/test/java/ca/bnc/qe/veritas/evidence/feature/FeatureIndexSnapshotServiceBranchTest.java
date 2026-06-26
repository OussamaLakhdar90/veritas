package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Branch-maximising companion to {@link FeatureIndexSnapshotServiceTest}. Where the sibling proves the happy paths,
 * this targets the uncovered edges of every public method: the edit-layer guards (null/blank/unknown ids, dedup of
 * merge ids, blank merge name -> largest default, pin-not-carried, unpin), the lease/claim branches (missing row,
 * permanent-generated reject, lease boundary, error-clearing), the edits log (append/no-op), carry-forward with
 * un-replayable notes and a pin carried, the error truncation in {@code failGeneration}, and the JSON read/write
 * guards (null/blank -> empty, corrupt -> {@code IllegalStateException}).
 */
class FeatureIndexSnapshotServiceBranchTest {

    private static final String JIRA_ID = "JIRA-1";
    private static final String CODE_ID = "CODE:PolicyController#GET /policies";
    private static final String CONF_ID = "CONFLUENCE:Page#policy";

    private FeatureIndexSnapshotRepository repository;
    private FeatureIndexSnapshotService service;

    @BeforeEach
    void setUp() {
        repository = mock(FeatureIndexSnapshotRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new FeatureIndexSnapshotService(repository, new GapDetector(),
                new ObjectMapper().findAndRegisterModules(), 15);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private FeatureIndexResult sampleResult() {
        EvidenceUnit jira = EvidenceUnit.of(JIRA_ID, SourceKind.JIRA, UnitType.REQUIREMENT,
                "Get policy", "As a caller I can get a policy by id.", "https://jira/JIRA-1", Set.of("policy"));
        EvidenceUnit code = EvidenceUnit.of(CODE_ID, SourceKind.CODE, UnitType.ENDPOINT,
                "GET /policies", "Returns the policies.", null, Set.of("policy"));
        Map<String, EvidenceUnit> byId = Map.of(JIRA_ID, jira, CODE_ID, code);
        Map<String, Feature> features = new LinkedHashMap<>();
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

    // ---- find ----------------------------------------------------------------------------------

    @Test
    void findDelegatesToRepositoryAndPropagatesPresenceAndAbsence() {
        FeatureIndexSnapshot s = new FeatureIndexSnapshot();
        when(repository.findById("present")).thenReturn(Optional.of(s));
        when(repository.findById("absent")).thenReturn(Optional.empty());

        assertThat(service.find("present")).containsSame(s);
        assertThat(service.find("absent")).isEmpty();
    }

    // ---- rename guards -------------------------------------------------------------------------

    @Test
    void renameRejectsANullName() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        assertThatThrownBy(() -> service.rename(saved, "feat-jira", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("new feature name is required");
    }

    @Test
    void renameRejectsANullFeatureId() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        assertThatThrownBy(() -> service.rename(saved, null, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feature id is required");
    }

    @Test
    void renameRejectsABlankFeatureId() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        assertThatThrownBy(() -> service.rename(saved, "   ", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feature id is required");
    }

    @Test
    void renameKeepsMembershipAndOnlyTouchesTheTargetFeature() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");

        FeatureIndexResult after = service.resultOf(service.rename(saved, "feat-code", "List policies"));

        assertThat(after.index().features().get("feat-code").displayName()).isEqualTo("List policies");
        // the other feature is untouched
        assertThat(after.index().features().get("feat-jira").displayName()).isEqualTo("Get policy");
        // membership is preserved
        assertThat(after.index().features().get("feat-code").unitIds()).containsExactly(CODE_ID);
    }

    // ---- merge guards & branches ----------------------------------------------------------------

    @Test
    void mergeRejectsANullFeatureIdList() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        // null list -> treated as empty -> "missing" is empty -> fails the < 2 distinct check.
        assertThatThrownBy(() -> service.merge(saved, null, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two distinct");
    }

    @Test
    void mergeDropsNullAndBlankAndDuplicateIdsBeforeTheCountCheck() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        // after filtering null/blank/dups only "feat-jira" survives -> < 2 -> 400 (NOT a "missing" error).
        assertThatThrownBy(() -> service.merge(saved,
                Arrays.asList("feat-jira", null, "  ", "feat-jira"), "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two distinct");
    }

    @Test
    void mergeNamesTheUnknownIdsBeforeTheCountCheck() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        // two distinct ids but one is unknown -> the membership check fires first and names the stray id.
        assertThatThrownBy(() -> service.merge(saved, List.of("feat-jira", "ghost"), "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void mergeWithBlankNameDefaultsToTheLargestFeatureName() {
        // Make feat-jira the larger feature (2 units) so largestName picks it deterministically (no tie).
        FeatureIndexResult base = sampleResultWithJiraOwningTwoUnits();
        FeatureIndexSnapshot saved = service.create("svc", base, "alice");

        FeatureIndexResult after = service.resultOf(
                service.merge(saved, List.of("feat-jira", "feat-code"), "   "));   // blank -> largest wins

        // feat-jira (2 units) is the largest -> its name "Get policy" becomes the merged label.
        assertThat(after.index().features()).hasSize(1);
        assertThat(after.index().features().values()).first()
                .extracting(Feature::displayName).isEqualTo("Get policy");
    }

    @Test
    void mergeDoesNotCarryAPinWhenNoneOfTheSourcesWasPinned() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        // no pins at all -> removeAll(ids) returns false -> the merged feature is unpinned.
        FeatureIndexSnapshot merged = service.merge(saved, List.of("feat-jira", "feat-code"), "Policies");

        assertThat(service.pinnedOf(merged)).isEmpty();
    }

    @Test
    void mergeKeepsAnUnrelatedPinUntouchedAndStillDoesNotPinTheMergedFeature() {
        // Add a third feature, pin it, then merge the other two: removeAll(ids) is false (the pin isn't in ids),
        // so the pin stays on the third feature and the merged feature is NOT pinned.
        FeatureIndexResult base = sampleResultWithThirdFeature();
        FeatureIndexSnapshot saved = service.create("svc", base, "alice");
        service.pin(saved, "feat-extra", true);

        FeatureIndexSnapshot merged = service.merge(saved, List.of("feat-jira", "feat-code"), "Policies");

        assertThat(service.pinnedOf(merged)).containsExactly("feat-extra");
    }

    @Test
    void mergeRecomputesIdFromTheSortedUnitUnion() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        FeatureIndexSnapshot merged = service.merge(saved, List.of("feat-jira", "feat-code"), "Policies");

        String expectedId = "feat-" + EvidenceId.hash8(CODE_ID + "|" + JIRA_ID);   // sorted: CODE_ID < JIRA_ID
        FeatureIndexResult after = service.resultOf(merged);
        assertThat(after.index().features()).containsKey(expectedId);
        assertThat(after.index().features().get(expectedId).unitIds()).containsExactly(CODE_ID, JIRA_ID);
    }

    // ---- pin branches --------------------------------------------------------------------------

    @Test
    void pinThenUnpinClearsThePinAndRecordsBothEdits() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");

        service.pin(saved, "feat-jira", true);
        assertThat(service.pinnedOf(saved)).containsExactly("feat-jira");

        service.pin(saved, "feat-jira", false);   // the unpin branch (pins.remove)
        assertThat(service.pinnedOf(saved)).isEmpty();

        assertThat(service.editsOf(saved)).extracting(FeatureEdit::kind)
                .containsExactly(FeatureEdit.Kind.PIN, FeatureEdit.Kind.PIN);
        assertThat(service.editsOf(saved)).extracting(FeatureEdit::pinned)
                .containsExactly(true, false);
    }

    @Test
    void unpinningANeverPinnedFeatureIsANoOpButStillLogsTheEdit() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");

        service.pin(saved, "feat-jira", false);   // remove() on an absent element -> still empty, still logged

        assertThat(service.pinnedOf(saved)).isEmpty();
        assertThat(service.editsOf(saved)).extracting(FeatureEdit::kind).containsExactly(FeatureEdit.Kind.PIN);
    }

    @Test
    void pinRejectsAnUnknownFeature() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        assertThatThrownBy(() -> service.pin(saved, "ghost", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    // ---- editsOf / appendEdit ------------------------------------------------------------------

    @Test
    void editsOfAFreshSnapshotIsEmptyAndAFreshSnapshotPinsAreEmpty() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        assertThat(service.editsOf(saved)).isEmpty();
        assertThat(service.pinnedOf(saved)).isEmpty();
    }

    @Test
    void editsOfTreatsNullEditsJsonAsEmpty() {
        FeatureIndexSnapshot bare = new FeatureIndexSnapshot();   // editsJson == null
        assertThat(service.editsOf(bare)).isEmpty();
    }

    // ---- claimForGeneration branches -----------------------------------------------------------

    @Test
    void claimForGenerationRejectsAMissingSnapshot() {
        when(repository.findById("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.claimForGeneration("gone"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no longer exists");
    }

    @Test
    void claimForGenerationRejectsAnAlreadyGeneratedSnapshotNamingTheStrategy() {
        FeatureIndexSnapshot s = service.create("svc", sampleResult(), "alice");
        s.setGeneratedStrategyId("strat-7");
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.claimForGeneration(s.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("strat-7");
    }

    @Test
    void claimForGenerationRejectsAClaimStillWithinTheLease() {
        FeatureIndexSnapshot s = service.create("svc", sampleResult(), "alice");
        s.setGenerationStartedAt(Instant.now().minus(Duration.ofMinutes(5)));   // within the 15-min lease
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.claimForGeneration(s.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already being generated");
    }

    @Test
    void claimForGenerationSucceedsWhenNoClaimInFlightAndStampsTheTime() {
        FeatureIndexSnapshot s = service.create("svc", sampleResult(), "alice");   // generationStartedAt == null
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        Instant before = Instant.now().minus(Duration.ofSeconds(1));
        FeatureIndexSnapshot claimed = service.claimForGeneration(s.getId());

        assertThat(claimed.getGenerationStartedAt()).isAfter(before);
        assertThat(claimed.getGenerationError()).isNull();
        // The claimed entity is the very row that was re-read and saved under the optimistic lock.
        assertThat(claimed).isSameAs(s);
    }

    @Test
    void claimForGenerationTreatsAClaimOlderThanTheLeaseAsAbandonedAndReClaims() {
        FeatureIndexSnapshot s = service.create("svc", sampleResult(), "alice");
        Instant stale = Instant.now().minus(Duration.ofMinutes(20));   // older than the 15-min lease
        s.setGenerationStartedAt(stale);
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        FeatureIndexSnapshot reclaimed = service.claimForGeneration(s.getId());

        assertThat(reclaimed.getGenerationStartedAt()).isAfter(stale);
        assertThat(reclaimed.getGenerationStartedAt()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    // ---- linkGenerated / releaseClaim / failGeneration -----------------------------------------

    @Test
    void linkGeneratedDelegatesWhenItAffectsARow() {
        when(repository.linkGenerated("snap-9", "strat-9")).thenReturn(1);

        service.linkGenerated("snap-9", "strat-9");

        verify(repository).linkGenerated("snap-9", "strat-9");
    }

    @Test
    void linkGeneratedToleratesAVanishedRow() {
        when(repository.linkGenerated("snap-x", "strat-x")).thenReturn(0);   // 0 rows -> warn branch, no throw

        service.linkGenerated("snap-x", "strat-x");

        verify(repository).linkGenerated("snap-x", "strat-x");
    }

    @Test
    void releaseClaimDelegatesToTheColumnScopedUpdate() {
        service.releaseClaim("snap-r");
        verify(repository).releaseClaim("snap-r");
    }

    @Test
    void failGenerationStoresAGivenShortMessageVerbatim() {
        service.failGeneration("snap-f", "boom");
        verify(repository).failGeneration("snap-f", "boom");
    }

    @Test
    void failGenerationSubstitutesADefaultForANullMessage() {
        service.failGeneration("snap-f", null);
        verify(repository).failGeneration("snap-f", "Generation failed.");
    }

    @Test
    void failGenerationTruncatesAnOverlongMessageToTwoThousandChars() {
        String huge = "x".repeat(2500);
        service.failGeneration("snap-f", huge);

        verify(repository).failGeneration("snap-f", "x".repeat(2000));   // exactly the 2000-char prefix
    }

    @Test
    void failGenerationLeavesAMessageAtTheLimitUntouched() {
        String exact = "y".repeat(2000);   // length == 2000 -> NOT > 2000 -> not truncated
        service.failGeneration("snap-f", exact);

        verify(repository).failGeneration("snap-f", exact);
    }

    // ---- createCarryingForward -----------------------------------------------------------------

    @Test
    void carryForwardReportsAnEditWhoseFeatureVanishedAsAnUnReplayableNote() {
        FeatureIndexSnapshot original = service.create("svc", sampleResult(), "alice");
        service.rename(original, "feat-jira", "Get a policy by id");   // keyed by JIRA_ID's units

        // Re-extraction that NO LONGER contains the jira unit -> the rename can't re-apply -> a note, not a guess.
        FeatureIndexResult reExtracted = codeOnlyResult();
        FeatureIndexSnapshotService.CarryForward carried =
                service.createCarryingForward("svc", reExtracted, "alice", original);

        assertThat(carried.notes()).isNotEmpty();
        assertThat(carried.notes()).anyMatch(n -> n.contains("rename"));
        assertThat(carried.snapshot().getCarriedForwardFrom()).isEqualTo(original.getId());
        // The edit log is still copied forward verbatim (so a later re-run with the unit back re-applies it).
        assertThat(service.editsOf(carried.snapshot())).hasSize(1);
    }

    @Test
    void carryForwardFromAnUneditedPriorCopiesAnEmptyLogAndNoNotes() {
        FeatureIndexSnapshot original = service.create("svc", sampleResult(), "alice");   // no edits

        FeatureIndexSnapshotService.CarryForward carried =
                service.createCarryingForward("svc", sampleResult(), "bob", original);

        assertThat(carried.notes()).isEmpty();
        assertThat(service.editsOf(carried.snapshot())).isEmpty();
        assertThat(carried.snapshot().getOwner()).isEqualTo("bob");   // ownership follows the re-runner
        assertThat(service.pinnedOf(carried.snapshot())).isEmpty();
    }

    @Test
    void carryForwardReTargetsAPinOntoTheFreshFeatureId() {
        FeatureIndexSnapshot original = service.create("svc", sampleResult(), "alice");
        service.pin(original, "feat-code", true);

        FeatureIndexResult reExtracted = withFeatureIds(sampleResult(), "feat-jira-v2", "feat-code-v2");
        FeatureIndexSnapshotService.CarryForward carried =
                service.createCarryingForward("svc", reExtracted, "alice", original);

        // The pin (keyed by CODE_ID's units) re-targets onto the re-clustered id by unit overlap.
        assertThat(service.pinnedOf(carried.snapshot())).containsExactly("feat-code-v2");
        assertThat(carried.notes()).isEmpty();
    }

    // ---- JSON read guards (corrupt -> IllegalStateException) -----------------------------------

    @Test
    void resultOfThrowsOnCorruptResultJson() {
        FeatureIndexSnapshot bad = new FeatureIndexSnapshot();
        bad.setResultJson("{ not valid json");
        assertThatThrownBy(() -> service.resultOf(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corrupt feature-index snapshot");
    }

    @Test
    void pinnedOfThrowsOnCorruptPinnedJsonButTreatsBlankAsEmpty() {
        FeatureIndexSnapshot blank = new FeatureIndexSnapshot();
        blank.setPinnedFeatureIds("   ");                       // blank -> empty set, no throw
        assertThat(service.pinnedOf(blank)).isEmpty();

        FeatureIndexSnapshot bad = new FeatureIndexSnapshot();
        bad.setPinnedFeatureIds("{ not a list");
        assertThatThrownBy(() -> service.pinnedOf(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corrupt pinned-feature ids");
    }

    @Test
    void pinnedOfTreatsNullJsonAsEmpty() {
        FeatureIndexSnapshot bare = new FeatureIndexSnapshot();   // pinnedFeatureIds == null
        assertThat(service.pinnedOf(bare)).isEmpty();
    }

    @Test
    void editsOfThrowsOnCorruptEditsJsonButTreatsBlankAsEmpty() {
        FeatureIndexSnapshot blank = new FeatureIndexSnapshot();
        blank.setEditsJson("");                                 // blank -> empty list, no throw
        assertThat(service.editsOf(blank)).isEmpty();

        FeatureIndexSnapshot bad = new FeatureIndexSnapshot();
        bad.setEditsJson("{ not a list");
        assertThatThrownBy(() -> service.editsOf(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corrupt feature-edit log");
    }

    // ---- create / no spurious reads ------------------------------------------------------------

    @Test
    void createSeedsEmptyPinAndEditLogsAndReturnsTheSavedEntity() {
        FeatureIndexResult original = sampleResult();
        FeatureIndexSnapshot saved = service.create("ciam", original, "carol");

        assertThat(saved.getServiceName()).isEqualTo("ciam");
        assertThat(saved.getOwner()).isEqualTo("carol");
        assertThat(saved.getPinnedFeatureIds()).isEqualTo("[]");
        assertThat(saved.getEditsJson()).isEqualTo("[]");
        assertThat(service.resultOf(saved)).isEqualTo(original);
        verify(repository).save(any());
        // create() persists straight from the in-memory result -- it never re-reads the row.
        verify(repository, never()).findById(any());
    }

    @Test
    void renameOnAnUnknownFeatureNeverTouchesTheRepository() {
        FeatureIndexSnapshot saved = service.create("svc", sampleResult(), "alice");
        FeatureIndexSnapshotRepository freshRepo = mock(FeatureIndexSnapshotRepository.class);
        FeatureIndexSnapshotService freshService = new FeatureIndexSnapshotService(freshRepo, new GapDetector(),
                new ObjectMapper().findAndRegisterModules(), 15);

        assertThatThrownBy(() -> freshService.rename(saved, "ghost", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(freshRepo);   // the guard fires before any save
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A variant where feat-jira owns two units (so it is strictly the largest in a merge -- no tie). */
    private FeatureIndexResult sampleResultWithJiraOwningTwoUnits() {
        EvidenceUnit jira = EvidenceUnit.of(JIRA_ID, SourceKind.JIRA, UnitType.REQUIREMENT,
                "Get policy", "As a caller I can get a policy by id.", "https://jira/JIRA-1", Set.of("policy"));
        EvidenceUnit conf = EvidenceUnit.of(CONF_ID, SourceKind.CONFLUENCE, UnitType.STANDARD,
                "Policy page", "Policy docs.", null, Set.of("policy"));
        EvidenceUnit code = EvidenceUnit.of(CODE_ID, SourceKind.CODE, UnitType.ENDPOINT,
                "GET /policies", "Returns the policies.", null, Set.of("policy"));
        Map<String, EvidenceUnit> byId = Map.of(JIRA_ID, jira, CONF_ID, conf, CODE_ID, code);
        Map<String, Feature> features = new LinkedHashMap<>();
        features.put("feat-jira", new Feature("feat-jira", "Get policy", List.of(JIRA_ID, CONF_ID), FeatureStatus.PLANNED));
        features.put("feat-code", new Feature("feat-code", "GET /policies", List.of(CODE_ID), FeatureStatus.UNDOCUMENTED));
        SourceMix mix = new SourceMix(true, true, true);
        FeatureIndex index = new FeatureIndex(features, byId, Set.of(), Set.of(), mix, "src-test2");
        ExtractionResult extraction = extractionOf(List.of(jira, conf, code), mix);
        return new FeatureIndexResult(index, new GapDetector().detect(index), extraction);
    }

    /** The two sample features PLUS an unrelated third feature (its own unit), for the pin-not-carried path. */
    private FeatureIndexResult sampleResultWithThirdFeature() {
        EvidenceUnit jira = EvidenceUnit.of(JIRA_ID, SourceKind.JIRA, UnitType.REQUIREMENT,
                "Get policy", "As a caller I can get a policy by id.", "https://jira/JIRA-1", Set.of("policy"));
        EvidenceUnit code = EvidenceUnit.of(CODE_ID, SourceKind.CODE, UnitType.ENDPOINT,
                "GET /policies", "Returns the policies.", null, Set.of("policy"));
        EvidenceUnit conf = EvidenceUnit.of(CONF_ID, SourceKind.CONFLUENCE, UnitType.STANDARD,
                "Audit page", "Audit docs.", null, Set.of("audit"));
        Map<String, EvidenceUnit> byId = Map.of(JIRA_ID, jira, CODE_ID, code, CONF_ID, conf);
        Map<String, Feature> features = new LinkedHashMap<>();
        features.put("feat-jira", new Feature("feat-jira", "Get policy", List.of(JIRA_ID), FeatureStatus.PLANNED));
        features.put("feat-code", new Feature("feat-code", "GET /policies", List.of(CODE_ID), FeatureStatus.UNDOCUMENTED));
        features.put("feat-extra", new Feature("feat-extra", "Audit", List.of(CONF_ID), FeatureStatus.PLANNED));
        SourceMix mix = new SourceMix(true, true, true);
        FeatureIndex index = new FeatureIndex(features, byId, Set.of(), Set.of(), mix, "src-test3");
        ExtractionResult extraction = extractionOf(List.of(jira, code, conf), mix);
        return new FeatureIndexResult(index, new GapDetector().detect(index), extraction);
    }

    /** A re-extraction that contains only the CODE unit (the jira unit vanished) -> a rename keyed by it can't replay. */
    private FeatureIndexResult codeOnlyResult() {
        EvidenceUnit code = EvidenceUnit.of(CODE_ID, SourceKind.CODE, UnitType.ENDPOINT,
                "GET /policies", "Returns the policies.", null, Set.of("policy"));
        Map<String, EvidenceUnit> byId = Map.of(CODE_ID, code);
        Map<String, Feature> features = new LinkedHashMap<>();
        features.put("feat-code", new Feature("feat-code", "GET /policies", List.of(CODE_ID), FeatureStatus.UNDOCUMENTED));
        SourceMix mix = new SourceMix(true, false, false);
        FeatureIndex index = new FeatureIndex(features, byId, Set.of(), Set.of(), mix, "src-code-only");
        ExtractionResult extraction = extractionOf(List.of(code), mix);
        return new FeatureIndexResult(index, new GapDetector().detect(index), extraction);
    }

    /** Re-key the two sample features under new ids (same units) -- simulating re-clustering. */
    private FeatureIndexResult withFeatureIds(FeatureIndexResult base, String jiraId, String codeId) {
        FeatureIndex idx = base.index();
        Feature j = idx.features().get("feat-jira");
        Feature c = idx.features().get("feat-code");
        Map<String, Feature> remapped = new LinkedHashMap<>();
        remapped.put(jiraId, new Feature(jiraId, j.displayName(), j.unitIds(), j.status()));
        remapped.put(codeId, new Feature(codeId, c.displayName(), c.unitIds(), c.status()));
        FeatureIndex ni = new FeatureIndex(remapped, idx.unitsById(), idx.crossCuttingIds(), idx.unassignedUnitIds(),
                idx.mix(), idx.sourceDigest());
        return new FeatureIndexResult(ni, new GapDetector().detect(ni), base.extraction());
    }

    private ExtractionResult extractionOf(List<EvidenceUnit> units, SourceMix mix) {
        Map<SourceKind, FetchProvenance.Counts> counts = new LinkedHashMap<>();
        Set<SourceKind> kinds = new LinkedHashSet<>();
        for (EvidenceUnit u : units) {
            kinds.add(u.source());
            counts.merge(u.source(), new FetchProvenance.Counts(1, 1, List.of()),
                    (a, b) -> new FetchProvenance.Counts(a.requested() + 1, a.fetched() + 1, List.of()));
        }
        return new ExtractionResult(new ArrayList<>(units), new FetchProvenance(counts), mix, 0, kinds);
    }
}