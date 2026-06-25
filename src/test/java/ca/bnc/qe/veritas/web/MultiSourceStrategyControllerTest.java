package ca.bnc.qe.veritas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.ExtractionResult;
import ca.bnc.qe.veritas.evidence.FetchProvenance;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import ca.bnc.qe.veritas.evidence.UnitType;
import ca.bnc.qe.veritas.evidence.feature.AsyncStrategyGenerator;
import ca.bnc.qe.veritas.evidence.feature.Feature;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndex;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexBuilder;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexResult;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexSnapshotService;
import ca.bnc.qe.veritas.evidence.feature.FeatureStatus;
import ca.bnc.qe.veritas.evidence.feature.Gap;
import ca.bnc.qe.veritas.evidence.feature.GapKind;
import ca.bnc.qe.veritas.evidence.feature.GapReport;
import ca.bnc.qe.veritas.evidence.feature.MultiSourceStrategyService;
import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshot;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.settings.CurrentUser;
import ca.bnc.qe.veritas.skill.ConflictException;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The multi-source strategy endpoint: the one-shot generate (builds the SourceSelection incl. the code-arm
 * clone+extract → 201), and the §6 preview → edit → generate-from-snapshot flow over a persisted, editable index.
 */
@WebMvcTest(MultiSourceStrategyController.class)
class MultiSourceStrategyControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private WorkspaceService workspace;
    @MockBean private JavaSpringExtractor extractor;
    @MockBean private FeatureIndexBuilder featureIndexBuilder;
    @MockBean private MultiSourceStrategyService strategyService;
    @MockBean private FeatureIndexSnapshotService snapshotService;
    @MockBean private AsyncStrategyGenerator asyncStrategyGenerator;
    @MockBean private ca.bnc.qe.veritas.evidence.SourceExpander sourceExpander;
    @MockBean private CurrentUser currentUser;

    @BeforeEach
    void stubPrincipal() {
        when(currentUser.principalId()).thenReturn("alice");   // matches stubSnapshot owner → ownership passes
    }

    private TestStrategy stubGenerated() {
        TestStrategy s = new TestStrategy();
        s.setServiceName("ciam-policies");
        s.setSource("multi-source");
        s.setStatus("DRAFT");
        when(strategyService.generate(any(), any(), any())).thenReturn(s);
        return s;
    }

    private static FeatureIndexSnapshot stubSnapshot(String id, String service, String owner) {
        FeatureIndexSnapshot s = new FeatureIndexSnapshot();
        s.setId(id);
        s.setServiceName(service);
        s.setOwner(owner);
        return s;
    }

    /** A minimal one-feature result, for the snapshot read/edit/generate paths (the round-trip is tested elsewhere). */
    private static FeatureIndexResult oneFeatureResult() {
        EvidenceUnit jira = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "Get policy", "x", null, Set.of());
        FeatureIndex idx = new FeatureIndex(
                Map.of("feat-1", new Feature("feat-1", "Policies", List.of("JIRA-1"), FeatureStatus.PLANNED)),
                Map.of("JIRA-1", jira), Set.of(), Set.of(), new SourceMix(false, true, false), "src");
        ExtractionResult ex = new ExtractionResult(List.of(jira), new FetchProvenance(Map.of()),
                new SourceMix(false, true, false), 0, Set.of());
        return new FeatureIndexResult(idx, new GapReport(List.of(), Set.of()), ex);
    }

    @Test
    void jiraOnlyBuildsTheSelectionAndReturns201_withoutCloning() throws Exception {
        stubGenerated();
        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy")
                        .contentType("application/json")
                        .content("{\"jira\":{\"jql\":\"project = CIAM\",\"maxResults\":25}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("multi-source"))
                .andExpect(jsonPath("$.serviceName").value("ciam-policies"));

        verify(workspace, never()).resolve(any(), any(), any(), any());   // no code arm → no clone
        ArgumentCaptor<SourceSelection> sel = ArgumentCaptor.forClass(SourceSelection.class);
        verify(strategyService).generate(eq("ciam-policies"), sel.capture(), eq("alice"));
        assertThat(sel.getValue().hasJira()).isTrue();
        assertThat(sel.getValue().hasCode()).isFalse();
        assertThat(sel.getValue().maxResults()).isEqualTo(25);
    }

    @Test
    void theCodeArmClonesAndExtractsThenGenerates() throws Exception {
        stubGenerated();
        when(workspace.resolve(eq("APP7571"), eq("ciam-policies"), eq("develop"), any()))
                .thenReturn(Path.of("/tmp/clone"));
        when(extractor.extract(any())).thenReturn(new ApiModel("code", "ciam-policies", "1", null, List.of(), Map.of()));

        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy")
                        .contentType("application/json")
                        .content("{\"code\":{\"appId\":\"APP7571\",\"repoSlug\":\"ciam-policies\",\"branch\":\"develop\"}}"))
                .andExpect(status().isCreated());

        verify(workspace).resolve("APP7571", "ciam-policies", "develop", null);
        verify(extractor).extract(Path.of("/tmp/clone"));
        ArgumentCaptor<SourceSelection> sel = ArgumentCaptor.forClass(SourceSelection.class);
        verify(strategyService).generate(eq("ciam-policies"), sel.capture(), eq("alice"));
        assertThat(sel.getValue().hasCode()).isTrue();
    }

    @Test
    void aJiraEpicKeyExpandsToAChildIssuesJqlSelection() throws Exception {
        stubGenerated();
        when(sourceExpander.jqlForEpic("CIAM-100")).thenReturn("parent = \"CIAM-100\"");

        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy")
                        .contentType("application/json").content("{\"jira\":{\"epicKey\":\"CIAM-100\"}}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<SourceSelection> sel = ArgumentCaptor.forClass(SourceSelection.class);
        verify(strategyService).generate(eq("ciam-policies"), sel.capture(), eq("alice"));
        assertThat(sel.getValue().hasJira()).isTrue();
        assertThat(sel.getValue().jql()).isEqualTo("parent = \"CIAM-100\"");
    }

    @Test
    void aConfluenceRootPageExpandsToTheDescendantPageIds() throws Exception {
        stubGenerated();
        when(sourceExpander.pageIdsForRoot("987")).thenReturn(List.of("987", "988"));

        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy")
                        .contentType("application/json").content("{\"confluence\":{\"rootPageId\":\"987\"}}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<SourceSelection> sel = ArgumentCaptor.forClass(SourceSelection.class);
        verify(strategyService).generate(eq("ciam-policies"), sel.capture(), eq("alice"));
        assertThat(sel.getValue().pageIds()).contains("987", "988");
    }

    @Test
    void noSourceSelectedIsA400() throws Exception {
        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verify(strategyService, never()).generate(any(), any(), any());
    }

    @Test
    void previewPersistsASnapshotAndReturnsTheFeatureIndexWithoutGenerating() throws Exception {
        when(currentUser.principalId()).thenReturn("alice");
        EvidenceUnit jira = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "Get policy", "x", null, Set.of());
        FeatureIndex idx = new FeatureIndex(
                Map.of("feat-1", new Feature("feat-1", "login", List.of("JIRA-1"), FeatureStatus.PLANNED)),
                Map.of("JIRA-1", jira), Set.of(), Set.of(), new SourceMix(false, true, false), "src");
        GapReport gaps = new GapReport(
                List.of(new Gap(GapKind.PLANNED_NOT_IMPLEMENTED, "feat-1", "specified but not built", List.of("JIRA-1"))),
                Set.of());
        ExtractionResult ex = new ExtractionResult(List.of(), new FetchProvenance(Map.of()),
                new SourceMix(false, true, false), 2, Set.of());
        FeatureIndexResult result = new FeatureIndexResult(idx, gaps, ex);
        when(featureIndexBuilder.build(any(), any())).thenReturn(result);

        FeatureIndexSnapshot snap = stubSnapshot("snap-1", "ciam-policies", "alice");
        when(snapshotService.create(eq("ciam-policies"), any(), eq("alice"))).thenReturn(snap);
        when(snapshotService.resultOf(snap)).thenReturn(result);
        when(snapshotService.pinnedOf(snap)).thenReturn(Set.of());

        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy/preview")
                        .contentType("application/json").content("{\"jira\":{\"jql\":\"project = CIAM\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value("snap-1"))
                .andExpect(jsonPath("$.features[0].displayName").value("login"))
                .andExpect(jsonPath("$.features[0].status").value("PLANNED"))
                .andExpect(jsonPath("$.features[0].units[0].id").value("JIRA-1"))
                .andExpect(jsonPath("$.features[0].pinned").value(false))
                .andExpect(jsonPath("$.gaps[0].kind").value("PLANNED_NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.mix.jira").value(true))
                .andExpect(jsonPath("$.redactionCount").value(2))
                .andExpect(jsonPath("$.hardFail").value(false))
                .andExpect(jsonPath("$.estimatedCostUsd").value(0.035));   // 1 feature × the per-feature estimate

        verify(snapshotService).create(eq("ciam-policies"), any(), eq("alice"));
        verify(strategyService, never()).generate(any(), any(), any());   // preview never spends on synthesis
    }

    @Test
    void getSnapshotReturnsThePreviewOr404() throws Exception {
        FeatureIndexSnapshot snap = stubSnapshot("snap-1", "ciam-policies", "alice");
        when(snapshotService.find("snap-1")).thenReturn(Optional.of(snap));
        when(snapshotService.resultOf(snap)).thenReturn(oneFeatureResult());
        when(snapshotService.pinnedOf(snap)).thenReturn(Set.of("feat-1"));

        mvc.perform(get("/api/v1/multi-source-strategy/snapshots/snap-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value("snap-1"))
                .andExpect(jsonPath("$.features[0].pinned").value(true));

        when(snapshotService.find("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/multi-source-strategy/snapshots/nope")).andExpect(status().isNotFound());
    }

    @Test
    void mergeAppliesTheEditAndReturnsTheUpdatedPreview() throws Exception {
        FeatureIndexSnapshot snap = stubSnapshot("snap-1", "ciam-policies", "alice");
        when(snapshotService.find("snap-1")).thenReturn(Optional.of(snap));
        when(snapshotService.merge(eq(snap), any(), eq("Policies"))).thenReturn(snap);
        when(snapshotService.resultOf(snap)).thenReturn(oneFeatureResult());
        when(snapshotService.pinnedOf(snap)).thenReturn(Set.of());

        mvc.perform(patch("/api/v1/multi-source-strategy/snapshots/snap-1/merge")
                        .contentType("application/json").content("{\"featureIds\":[\"a\",\"b\"],\"name\":\"Policies\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value("snap-1"))
                .andExpect(jsonPath("$.features.length()").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(snapshotService).merge(eq(snap), ids.capture(), eq("Policies"));
        assertThat(ids.getValue()).containsExactly("a", "b");
    }

    @Test
    void generateFromSnapshotClaimsThenReturns202AndKicksOffAsync() throws Exception {
        FeatureIndexSnapshot snap = stubSnapshot("snap-1", "ciam-policies", "alice");
        when(snapshotService.find("snap-1")).thenReturn(Optional.of(snap));
        when(snapshotService.claimForGeneration("snap-1")).thenReturn(snap);   // atomic one-shot claim (the 409 gate)

        mvc.perform(post("/api/v1/multi-source-strategy/snapshots/snap-1/strategy"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.snapshotId").value("snap-1"))
                .andExpect(jsonPath("$.status").value("GENERATING"));

        verify(snapshotService).claimForGeneration("snap-1");
        verify(asyncStrategyGenerator).submit(snap, null, null);   // off-thread; no prior context (not a re-run)
        verify(strategyService, never()).generateFromIndex(any(), any(), any());   // not on the request thread
        verify(snapshotService, never()).linkGenerated(any(), any());
        verify(featureIndexBuilder, never()).build(any(), any());   // no second pipeline run
    }

    @Test
    void generatingFromACarriedForwardSnapshotResolvesAndPassesThePriorStrategyForReuse() throws Exception {
        FeatureIndexSnapshot child = stubSnapshot("snap-2", "ciam-policies", "alice");
        child.setCarriedForwardFrom("snap-1");
        FeatureIndexSnapshot parent = stubSnapshot("snap-1", "ciam-policies", "alice");
        parent.setGeneratedStrategyId("strat-1");
        when(snapshotService.find("snap-2")).thenReturn(Optional.of(child));
        when(snapshotService.find("snap-1")).thenReturn(Optional.of(parent));
        when(snapshotService.claimForGeneration("snap-2")).thenReturn(child);
        TestStrategy priorStrat = new TestStrategy();
        priorStrat.setId("strat-1");
        when(strategyService.findStrategy("strat-1")).thenReturn(Optional.of(priorStrat));
        FeatureIndexResult priorIndex = oneFeatureResult();
        when(snapshotService.resultOf(parent)).thenReturn(priorIndex);

        mvc.perform(post("/api/v1/multi-source-strategy/snapshots/snap-2/strategy"))
                .andExpect(status().isAccepted());

        verify(asyncStrategyGenerator).submit(child, priorIndex, priorStrat);   // incremental reuse context resolved
    }

    @Test
    void aRejectedClaimIs409AndDoesNotKickOffAsync() throws Exception {
        FeatureIndexSnapshot snap = stubSnapshot("snap-1", "ciam-policies", "alice");
        when(snapshotService.find("snap-1")).thenReturn(Optional.of(snap));
        when(snapshotService.claimForGeneration("snap-1"))
                .thenThrow(new ConflictException("already generated"));   // claim refuses a second/concurrent generate

        mvc.perform(post("/api/v1/multi-source-strategy/snapshots/snap-1/strategy"))
                .andExpect(status().isConflict());

        verify(asyncStrategyGenerator, never()).submit(any());   // no duplicate paid synthesis kicked off
    }

    @Test
    void theSnapshotPollExposesTheGenerationStatusFields() throws Exception {
        FeatureIndexSnapshot snap = stubSnapshot("snap-1", "ciam-policies", "alice");
        snap.setGeneratedStrategyId("strat-1");   // synthesis finished → the poll target the wizard navigates on
        when(snapshotService.find("snap-1")).thenReturn(Optional.of(snap));
        when(snapshotService.resultOf(snap)).thenReturn(oneFeatureResult());
        when(snapshotService.pinnedOf(snap)).thenReturn(Set.of());

        mvc.perform(get("/api/v1/multi-source-strategy/snapshots/snap-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedStrategyId").value("strat-1"));
    }

    @Test
    void aSnapshotOwnedByAnotherUserIs404() throws Exception {
        FeatureIndexSnapshot bobs = stubSnapshot("snap-1", "ciam-policies", "bob");
        when(snapshotService.find("snap-1")).thenReturn(Optional.of(bobs));   // current principal is "alice"

        mvc.perform(get("/api/v1/multi-source-strategy/snapshots/snap-1")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/multi-source-strategy/snapshots/snap-1/strategy")).andExpect(status().isNotFound());
        verify(snapshotService, never()).claimForGeneration(any());
    }

    @Test
    void pinWithoutAValueIs400() throws Exception {
        mvc.perform(patch("/api/v1/multi-source-strategy/snapshots/snap-1/pin")
                        .contentType("application/json").content("{\"featureId\":\"feat-1\"}"))
                .andExpect(status().isBadRequest());
        verify(snapshotService, never()).pin(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void previewWithCarryForwardReExtractsAndCarriesTheReviewerEditsForward() throws Exception {
        FeatureIndexResult fresh = oneFeatureResult();
        when(featureIndexBuilder.build(any(), any())).thenReturn(fresh);

        FeatureIndexSnapshot prior = stubSnapshot("snap-prior", "ciam-policies", "alice");
        when(snapshotService.find("snap-prior")).thenReturn(Optional.of(prior));

        FeatureIndexSnapshot carried = stubSnapshot("snap-2", "ciam-policies", "alice");
        when(snapshotService.createCarryingForward(eq("ciam-policies"), eq(fresh), eq("alice"), eq(prior)))
                .thenReturn(new FeatureIndexSnapshotService.CarryForward(carried,
                        List.of("Couldn't re-apply a rename (\"Legacy name\"): that feature is no longer present.")));
        when(snapshotService.resultOf(carried)).thenReturn(fresh);
        when(snapshotService.pinnedOf(carried)).thenReturn(Set.of("feat-1"));

        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy/preview?carryForwardFrom=snap-prior")
                        .contentType("application/json").content("{\"jira\":{\"jql\":\"project = CIAM\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value("snap-2"))
                .andExpect(jsonPath("$.features[0].pinned").value(true))   // the carried-forward pin
                .andExpect(jsonPath("$.carryForwardNotes.length()").value(1));   // the un-replayable edit is surfaced

        verify(snapshotService).createCarryingForward(eq("ciam-policies"), eq(fresh), eq("alice"), eq(prior));
        verify(snapshotService, never()).create(any(), any(), any());   // carry-forward path, not a plain new preview
    }

    @Test
    void carryForwardFromAMissingOrUnownedPriorIs400() throws Exception {
        when(featureIndexBuilder.build(any(), any())).thenReturn(oneFeatureResult());
        when(snapshotService.find("nope")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy/preview?carryForwardFrom=nope")
                        .contentType("application/json").content("{\"jira\":{\"jql\":\"project = CIAM\"}}"))
                .andExpect(status().isBadRequest());
        verify(snapshotService, never()).createCarryingForward(any(), any(), any(), any());
    }

    @Test
    void generateAndEditOnAMissingSnapshotAre404() throws Exception {
        when(snapshotService.find("nope")).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/multi-source-strategy/snapshots/nope/strategy")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/multi-source-strategy/snapshots/nope/pin")
                        .contentType("application/json").content("{\"featureId\":\"x\",\"pinned\":true}"))
                .andExpect(status().isNotFound());
        verify(strategyService, never()).generateFromIndex(any(), any(), any());
    }
}
