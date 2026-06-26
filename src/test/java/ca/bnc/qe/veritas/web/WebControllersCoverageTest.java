package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.persistence.TestCaseRepository;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.report.StrategyRationaleRenderer;
import ca.bnc.qe.veritas.report.WhyDocRenderer;
import ca.bnc.qe.veritas.skill.ConflictException;
import ca.bnc.qe.veritas.testmgmt.CreateTestCasesService;
import ca.bnc.qe.veritas.testmgmt.TestStrategyService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Branch-maximising web-layer coverage for {@link StrategyController} and {@link TestCaseController}: every
 * found/404 branch, the actor/owner default-to-"api" branches (verified via real captured values), the
 * versions lineage-fallback branches, and the 400 (unknown status) / 409 (illegal transition) error paths
 * routed through {@link ApiExceptionHandler}. Mirrors the existing *ControllerTest @WebMvcTest style.
 */
@WebMvcTest({StrategyController.class, TestCaseController.class})
class WebControllersCoverageTest {

    @Autowired private MockMvc mvc;

    @MockBean private TestStrategyRepository strategyRepository;
    @MockBean private TestStrategyService strategyService;
    @MockBean private StrategyRationaleRenderer rationaleRenderer;
    @MockBean private WhyDocRenderer whyDocRenderer;
    @MockBean private TestCaseRepository testCaseRepository;
    @MockBean private CreateTestCasesService testCaseService;

    private static TestStrategy strategy(String id) {
        TestStrategy s = new TestStrategy();
        s.setId(id);
        s.setServiceName("payments");
        s.setStatus("DRAFT");
        return s;
    }

    private static TestCase testCase(String id, String status) {
        TestCase tc = new TestCase();
        tc.setId(id);
        tc.setStatus(status);
        return tc;
    }

    // ---------------------------------------------------------------------------------------------------
    // StrategyController
    // ---------------------------------------------------------------------------------------------------

    @Test
    void listStrategiesReturnsRepositoryRowsForTheService() throws Exception {
        TestStrategy s = strategy("strat-1");
        when(strategyRepository.findByServiceNameOrderByCreatedAtDesc("payments"))
                .thenReturn(List.of(s));

        mvc.perform(get("/api/v1/services/payments/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("strat-1"))
                .andExpect(jsonPath("$[0].serviceName").value("payments"));

        verify(strategyRepository).findByServiceNameOrderByCreatedAtDesc("payments");
    }

    @Test
    void getStrategyReturnsItWhenPresent() throws Exception {
        when(strategyRepository.findById("strat-1")).thenReturn(Optional.of(strategy("strat-1")));

        mvc.perform(get("/api/v1/strategies/strat-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("strat-1"));
    }

    @Test
    void getStrategyIs404WhenAbsent() throws Exception {
        when(strategyRepository.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/strategies/nope")).andExpect(status().isNotFound());
    }

    @Test
    void rationaleRendersHtmlWhenPresent() throws Exception {
        TestStrategy s = strategy("strat-1");
        when(strategyRepository.findById("strat-1")).thenReturn(Optional.of(s));
        when(rationaleRenderer.renderHtml(s)).thenReturn("<html>why rationale</html>");

        mvc.perform(get("/api/v1/strategies/strat-1/rationale"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("why rationale")));
    }

    @Test
    void rationaleIs404WhenAbsent() throws Exception {
        when(strategyRepository.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/strategies/nope/rationale")).andExpect(status().isNotFound());
    }

    @Test
    void whyDocRendersHtmlWhenPresent() throws Exception {
        TestStrategy s = strategy("strat-1");
        when(strategyRepository.findById("strat-1")).thenReturn(Optional.of(s));
        when(whyDocRenderer.renderHtml(s)).thenReturn("<html>evidence why-doc</html>");

        mvc.perform(get("/api/v1/strategies/strat-1/why-doc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("evidence why-doc")));
    }

    @Test
    void whyDocIs404WhenAbsent() throws Exception {
        when(strategyRepository.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/strategies/nope/why-doc")).andExpect(status().isNotFound());
    }

    @Test
    void versionsUsesLineageIdWhenSet() throws Exception {
        TestStrategy head = strategy("strat-2");
        head.setLineageId("lineage-1");
        when(strategyRepository.findById("strat-2")).thenReturn(Optional.of(head));
        TestStrategy v1 = strategy("strat-1");
        TestStrategy v2 = strategy("strat-2");
        when(strategyRepository.findByLineageIdOrderByVersionDesc("lineage-1"))
                .thenReturn(List.of(v2, v1));

        mvc.perform(get("/api/v1/strategies/strat-2/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("strat-2"));

        // The lineage id (not the requested id) drove the history query.
        verify(strategyRepository).findByLineageIdOrderByVersionDesc("lineage-1");
    }

    @Test
    void versionsFallsBackToIdWhenLineageIdNull() throws Exception {
        TestStrategy head = strategy("strat-1");
        head.setLineageId(null);
        when(strategyRepository.findById("strat-1")).thenReturn(Optional.of(head));
        when(strategyRepository.findByLineageIdOrderByVersionDesc("strat-1"))
                .thenReturn(List.of(head));

        mvc.perform(get("/api/v1/strategies/strat-1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)));

        verify(strategyRepository).findByLineageIdOrderByVersionDesc("strat-1");
    }

    @Test
    void versionsFallsBackToIdWhenStrategyAbsent() throws Exception {
        when(strategyRepository.findById("ghost")).thenReturn(Optional.empty());
        when(strategyRepository.findByLineageIdOrderByVersionDesc("ghost"))
                .thenReturn(List.of());

        mvc.perform(get("/api/v1/strategies/ghost/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));

        verify(strategyRepository).findByLineageIdOrderByVersionDesc("ghost");
    }

    @Test
    void reviseSectionUsesSuppliedActor() throws Exception {
        when(strategyService.reviseSection(eq("strat-1"), eq("scope"), eq("new-content"), eq("alice")))
                .thenReturn(strategy("strat-1"));

        mvc.perform(patch("/api/v1/strategies/strat-1/sections/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"new-content\",\"actor\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("strat-1"));

        verify(strategyService).reviseSection("strat-1", "scope", "new-content", "alice");
    }

    @Test
    void reviseSectionDefaultsActorToApiWhenNull() throws Exception {
        when(strategyService.reviseSection(eq("strat-1"), eq("scope"), eq("new-content"), eq("api")))
                .thenReturn(strategy("strat-1"));

        mvc.perform(patch("/api/v1/strategies/strat-1/sections/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"new-content\"}"))
                .andExpect(status().isOk());

        // null actor became "api".
        verify(strategyService).reviseSection("strat-1", "scope", "new-content", "api");
    }

    @Test
    void regenerateSectionPassesGuidanceAndActorWhenBodyPresent() throws Exception {
        when(strategyService.regenerateSection(eq("strat-1"), eq("risks"), eq("be terse"), eq("bob")))
                .thenReturn(strategy("strat-1"));

        mvc.perform(post("/api/v1/strategies/strat-1/sections/risks/regenerate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guidance\":\"be terse\",\"actor\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("strat-1"));

        verify(strategyService).regenerateSection("strat-1", "risks", "be terse", "bob");
    }

    @Test
    void regenerateSectionDefaultsActorToApiWhenActorNull() throws Exception {
        when(strategyService.regenerateSection(eq("strat-1"), eq("risks"), eq("be terse"), eq("api")))
                .thenReturn(strategy("strat-1"));

        mvc.perform(post("/api/v1/strategies/strat-1/sections/risks/regenerate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guidance\":\"be terse\"}"))
                .andExpect(status().isOk());

        verify(strategyService).regenerateSection("strat-1", "risks", "be terse", "api");
    }

    @Test
    void regenerateSectionWithNoBodyUsesNullGuidanceAndApiActor() throws Exception {
        when(strategyService.regenerateSection(eq("strat-1"), eq("risks"), isNull(), eq("api")))
                .thenReturn(strategy("strat-1"));

        mvc.perform(post("/api/v1/strategies/strat-1/sections/risks/regenerate"))
                .andExpect(status().isOk());

        // No request body -> guidance null, actor "api".
        verify(strategyService).regenerateSection("strat-1", "risks", null, "api");
    }

    @Test
    void approveUsesSuppliedActor() throws Exception {
        when(strategyService.approve(eq("strat-1"), eq("carol"))).thenReturn(strategy("strat-1"));

        mvc.perform(post("/api/v1/strategies/strat-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"carol\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("strat-1"));

        verify(strategyService).approve("strat-1", "carol");
    }

    @Test
    void approveDefaultsActorToApiWhenNoBody() throws Exception {
        when(strategyService.approve(eq("strat-1"), eq("api"))).thenReturn(strategy("strat-1"));

        mvc.perform(post("/api/v1/strategies/strat-1/approve"))
                .andExpect(status().isOk());

        verify(strategyService).approve("strat-1", "api");
    }

    @Test
    void approveDefaultsActorToApiWhenActorNullInBody() throws Exception {
        when(strategyService.approve(eq("strat-1"), eq("api"))).thenReturn(strategy("strat-1"));

        mvc.perform(post("/api/v1/strategies/strat-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(strategyService).approve("strat-1", "api");
    }

    @Test
    void generateStrategyUsesSuppliedOwnerAndIs202() throws Exception {
        when(strategyService.generate(eq("payments"), eq("the-basis"), eq("CODE"), eq("dave")))
                .thenReturn(strategy("strat-new"));

        mvc.perform(post("/api/v1/services/payments/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"the-basis\",\"source\":\"CODE\",\"owner\":\"dave\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("strat-new"));

        verify(strategyService).generate("payments", "the-basis", "CODE", "dave");
    }

    @Test
    void generateStrategyDefaultsOwnerToApiWhenNull() throws Exception {
        when(strategyService.generate(eq("payments"), eq("the-basis"), eq("CODE"), eq("api")))
                .thenReturn(strategy("strat-new"));

        mvc.perform(post("/api/v1/services/payments/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"the-basis\",\"source\":\"CODE\"}"))
                .andExpect(status().isAccepted());

        verify(strategyService).generate("payments", "the-basis", "CODE", "api");
    }

    // ---------------------------------------------------------------------------------------------------
    // TestCaseController
    // ---------------------------------------------------------------------------------------------------

    @Test
    void listTestCasesReturnsRepositoryRows() throws Exception {
        when(testCaseRepository.findByServiceNameOrderByCreatedAtDesc("payments"))
                .thenReturn(List.of(testCase("tc1", "PROPOSED")));

        mvc.perform(get("/api/v1/services/payments/test-cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("tc1"));
    }

    @Test
    void getTestCaseReturnsItWhenPresent() throws Exception {
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(testCase("tc1", "PROPOSED")));

        mvc.perform(get("/api/v1/test-cases/tc1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("tc1"))
                .andExpect(jsonPath("$.status").value("PROPOSED"));
    }

    @Test
    void getTestCaseIs404WhenAbsent() throws Exception {
        when(testCaseRepository.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/test-cases/nope")).andExpect(status().isNotFound());
    }

    @Test
    void generateTestCasesUsesSuppliedOwnerAndIs202() throws Exception {
        when(testCaseService.generate(eq("payments"), eq("endpoints"), eq("dave")))
                .thenReturn(List.of(testCase("tc1", "PROPOSED")));

        mvc.perform(post("/api/v1/services/payments/test-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"endpoints\",\"owner\":\"dave\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$", Matchers.hasSize(1)));

        verify(testCaseService).generate("payments", "endpoints", "dave");
    }

    @Test
    void generateTestCasesDefaultsOwnerToApiWhenNull() throws Exception {
        when(testCaseService.generate(eq("payments"), eq("endpoints"), eq("api")))
                .thenReturn(List.of());

        mvc.perform(post("/api/v1/services/payments/test-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"endpoints\"}"))
                .andExpect(status().isAccepted());

        verify(testCaseService).generate("payments", "endpoints", "api");
    }

    @Test
    void patchIs404WhenCaseAbsent() throws Exception {
        when(testCaseRepository.findById("nope")).thenReturn(Optional.empty());

        mvc.perform(patch("/api/v1/test-cases/nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchApprovesAndStampsSuppliedActor() throws Exception {
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(testCase("tc1", "PROPOSED")));
        when(testCaseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/test-cases/tc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"approved\",\"actor\":\"qa-lead\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("qa-lead"));
    }

    @Test
    void patchApproveDefaultsApproverToApiWhenActorNull() throws Exception {
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(testCase("tc1", "PROPOSED")));
        when(testCaseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/test-cases/tc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("api"));
    }

    @Test
    void patchNonApproveTransitionDoesNotStampApprover() throws Exception {
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(testCase("tc1", "PROPOSED")));
        when(testCaseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/test-cases/tc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approvedBy").doesNotExist());
    }

    @Test
    void patchSameStatusIsAllowedNoTransitionCheck() throws Exception {
        // current == target -> the !s.equals(current) guard short-circuits, so even IMPLEMENTED->IMPLEMENTED is ok.
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(testCase("tc1", "IMPLEMENTED")));
        when(testCaseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/test-cases/tc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IMPLEMENTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPLEMENTED"));
    }

    @Test
    void patchTreatsNullCurrentStatusAsProposed() throws Exception {
        // tc.getStatus() == null -> current defaults to PROPOSED, so PROPOSED->APPROVED is a legal transition.
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(testCase("tc1", null)));
        when(testCaseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/test-cases/tc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void patchUnknownStatusIs400() throws Exception {
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(testCase("tc1", "PROPOSED")));

        mvc.perform(patch("/api/v1/test-cases/tc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchIllegalTransitionIs409() throws Exception {
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(testCase("tc1", "IMPLEMENTED")));

        mvc.perform(patch("/api/v1/test-cases/tc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PROPOSED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patchTitleOnlyUpdatesTitleWithoutTouchingStatus() throws Exception {
        // status null branch skipped entirely; only the title branch runs.
        TestCase existing = testCase("tc1", "PROPOSED");
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(existing));
        when(testCaseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/test-cases/tc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed case\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed case"))
                .andExpect(jsonPath("$.status").value("PROPOSED"));
    }

    @Test
    void patchEmptyBodyJustSavesUnchanged() throws Exception {
        // Both status and title null -> neither branch runs, save echoes the unchanged case.
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(testCase("tc1", "APPROVED")));
        when(testCaseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/test-cases/tc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void pushIs404WhenCaseAbsent() throws Exception {
        when(testCaseRepository.findById("nope")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/test-cases/nope/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectKey\":\"PAY\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pushUsesSuppliedOwner() throws Exception {
        TestCase tc = testCase("tc1", "APPROVED");
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(tc));
        TestCase pushed = testCase("tc1", "CREATED_IN_XRAY");
        pushed.setXrayKey("PAY-42");
        when(testCaseService.pushToXray(eq(tc), eq("PAY"), eq("dave"))).thenReturn(pushed);

        mvc.perform(post("/api/v1/test-cases/tc1/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectKey\":\"PAY\",\"owner\":\"dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.xrayKey").value("PAY-42"))
                .andExpect(jsonPath("$.status").value("CREATED_IN_XRAY"));

        verify(testCaseService).pushToXray(tc, "PAY", "dave");
    }

    @Test
    void pushDefaultsOwnerToApiWhenNull() throws Exception {
        TestCase tc = testCase("tc1", "APPROVED");
        when(testCaseRepository.findById("tc1")).thenReturn(Optional.of(tc));
        when(testCaseService.pushToXray(eq(tc), eq("PAY"), eq("api"))).thenReturn(tc);

        mvc.perform(post("/api/v1/test-cases/tc1/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectKey\":\"PAY\"}"))
                .andExpect(status().isOk());

        verify(testCaseService).pushToXray(tc, "PAY", "api");
    }
}