package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.codegen.plan.TestPlan;
import ca.bnc.qe.veritas.codegen.plan.TestPlanItem;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService.RepoRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The test-gen preflight endpoint: maps the request to two RepoRefs and returns the reconciliation plan. */
@WebMvcTest(TestGenController.class)
class TestGenControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private TestPlanService service;

    @Test
    void planDelegatesBothReposAndReturnsThePlan() throws Exception {
        TestPlan plan = new TestPlan("ciam", TestPlan.REFACTOR,
                List.of(new TestPlanItem(TestPlanItem.GAP, "GET", "/policies", "GET /policies", null, "add a test")), 4);
        when(service.plan(eq("ciam"), eq(new RepoRef("APP1", "ciam", "develop", null)),
                eq(new RepoRef("APP1", "ciam-tests", "develop", null)))).thenReturn(plan);

        mvc.perform(post("/api/v1/services/ciam/test-gen/plan").contentType("application/json").content("""
                        {"appId":"APP1","serviceRepoSlug":"ciam","serviceBranch":"develop",
                         "testRepoSlug":"ciam-tests","testBranch":"develop"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("REFACTOR"))
                .andExpect(jsonPath("$.items[0].status").value("GAP"))
                .andExpect(jsonPath("$.items[0].signature").value("GET /policies"));

        verify(service).plan(eq("ciam"), eq(new RepoRef("APP1", "ciam", "develop", null)),
                eq(new RepoRef("APP1", "ciam-tests", "develop", null)));
    }

    @Test
    void noTestRepoSlugStillReachesTheServiceAsAScratchRequest() throws Exception {
        when(service.plan(eq("ciam"), eq(new RepoRef("APP1", "ciam", null, null)),
                eq(new RepoRef("APP1", null, null, null))))
                .thenReturn(new TestPlan("ciam", TestPlan.SCRATCH, List.of(), 0));

        mvc.perform(post("/api/v1/services/ciam/test-gen/plan").contentType("application/json")
                        .content("{\"appId\":\"APP1\",\"serviceRepoSlug\":\"ciam\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SCRATCH"));
    }
}
