package ca.bnc.qe.veritas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import ca.bnc.qe.veritas.evidence.feature.Feature;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndex;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexBuilder;
import ca.bnc.qe.veritas.evidence.feature.FeatureIndexResult;
import ca.bnc.qe.veritas.evidence.feature.FeatureStatus;
import ca.bnc.qe.veritas.evidence.feature.Gap;
import ca.bnc.qe.veritas.evidence.feature.GapKind;
import ca.bnc.qe.veritas.evidence.feature.GapReport;
import ca.bnc.qe.veritas.evidence.feature.MultiSourceStrategyService;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.settings.CurrentUser;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The multi-source strategy endpoint: builds the SourceSelection (incl. the code-arm clone+extract) and returns 201. */
@WebMvcTest(MultiSourceStrategyController.class)
class MultiSourceStrategyControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private WorkspaceService workspace;
    @MockBean private JavaSpringExtractor extractor;
    @MockBean private FeatureIndexBuilder featureIndexBuilder;
    @MockBean private MultiSourceStrategyService strategyService;
    @MockBean private CurrentUser currentUser;

    private TestStrategy stubGenerated() {
        when(currentUser.principalId()).thenReturn("alice");
        TestStrategy s = new TestStrategy();
        s.setServiceName("ciam-policies");
        s.setSource("multi-source");
        s.setStatus("DRAFT");
        when(strategyService.generate(any(), any(), any())).thenReturn(s);
        return s;
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
    void noSourceSelectedIsA400() throws Exception {
        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verify(strategyService, never()).generate(any(), any(), any());
    }

    @Test
    void previewReturnsTheFeatureIndexAndCostEstimateWithoutGenerating() throws Exception {
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
        when(featureIndexBuilder.build(any(), any())).thenReturn(new FeatureIndexResult(idx, gaps, ex));

        mvc.perform(post("/api/v1/services/ciam-policies/multi-source-strategy/preview")
                        .contentType("application/json").content("{\"jira\":{\"jql\":\"project = CIAM\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[0].displayName").value("login"))
                .andExpect(jsonPath("$.features[0].status").value("PLANNED"))
                .andExpect(jsonPath("$.features[0].units[0].id").value("JIRA-1"))
                .andExpect(jsonPath("$.gaps[0].kind").value("PLANNED_NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.mix.jira").value(true))
                .andExpect(jsonPath("$.redactionCount").value(2))
                .andExpect(jsonPath("$.hardFail").value(false))
                .andExpect(jsonPath("$.estimatedCostUsd").value(0.035));   // 1 feature × the per-feature estimate

        verify(strategyService, never()).generate(any(), any(), any());   // preview never spends on synthesis
    }
}
