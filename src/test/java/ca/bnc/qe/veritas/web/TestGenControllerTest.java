package ca.bnc.qe.veritas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec;
import ca.bnc.qe.veritas.codegen.plan.TestGenService;
import ca.bnc.qe.veritas.codegen.plan.TestPlan;
import ca.bnc.qe.veritas.codegen.plan.TestPlanItem;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService;
import ca.bnc.qe.veritas.codegen.plan.TestPlanService.RepoRef;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The test-gen endpoints: the preflight plan, and the (no-push) generate that scopes to the selected endpoints. */
@WebMvcTest(TestGenController.class)
class TestGenControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private TestPlanService service;
    @MockBean private TestGenService testGen;
    @MockBean private ca.bnc.qe.veritas.codegen.ServiceAuthProfileService authProfiles;

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

    @Test
    void generateScopesToTheSelectedEndpointsAndReturns202WithoutPushing() throws Exception {
        CodegenRun run = new CodegenRun();
        run.setServiceName("ciam");
        run.setBuildStatus("SKIPPED");
        when(testGen.generate(eq("ciam"),
                eq(new RepoRef("APP1", "ciam", "develop", null)),
                eq(new RepoRef("APP1", "ciam-tests", "develop", null)),
                eq(new java.util.LinkedHashSet<>(List.of("POST /policies"))), eq("alice"), eq("CIAM-1842"),
                eq(ServiceAuthSpec.none())))
                .thenReturn(run);

        mvc.perform(post("/api/v1/services/ciam/test-gen/generate").contentType("application/json").content("""
                        {"appId":"APP1","serviceRepoSlug":"ciam","serviceBranch":"develop",
                         "outputRepoSlug":"ciam-tests","outputBranch":"develop",
                         "endpoints":["POST /policies"],"owner":"alice","jiraKey":"CIAM-1842"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.serviceName").value("ciam"))
                .andExpect(jsonPath("$.buildStatus").value("SKIPPED"));

        // Returns the run for review — the push/PR is a separate, user-clicked publish step. Jira key is forwarded.
        verify(testGen).generate(eq("ciam"), eq(new RepoRef("APP1", "ciam", "develop", null)),
                eq(new RepoRef("APP1", "ciam-tests", "develop", null)),
                eq(new java.util.LinkedHashSet<>(List.of("POST /policies"))), eq("alice"), eq("CIAM-1842"),
                eq(ServiceAuthSpec.none()));
        // The declared token setup is persisted per service for next-run pre-fill (here: public ⇒ none()).
        verify(authProfiles).save(eq("APP1"), eq("ciam"), eq(ServiceAuthSpec.none()));
    }

    @Test
    void generatePersistsAndForwardsTheDeclaredOktaAuth() throws Exception {
        CodegenRun run = new CodegenRun();
        run.setServiceName("ciam");
        run.setBuildStatus("SKIPPED");
        when(testGen.generate(eq("ciam"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), eq("alice"), eq("CIAM-1842"),
                org.mockito.ArgumentMatchers.any())).thenReturn(run);

        mvc.perform(post("/api/v1/services/ciam/test-gen/generate").contentType("application/json").content("""
                        {"appId":"APP1","serviceRepoSlug":"ciam","outputRepoSlug":"ciam-tests",
                         "endpoints":["GET /policies/x"],"owner":"alice","jiraKey":"CIAM-1842",
                         "serviceAuth":{"authenticated":true,"tokenUrl":"https://okta/v1/token","clientId":"0oaX",
                           "privateKeyField":"CIAM_PRIVATE_KEY","credentialsFile":"oktaCredentials.json",
                           "scopes":[{"name":"WRITE","value":"ciam:policy:write"}]}}"""))
                .andExpect(status().isAccepted());

        org.mockito.ArgumentCaptor<ServiceAuthSpec> saved = org.mockito.ArgumentCaptor.forClass(ServiceAuthSpec.class);
        verify(authProfiles).save(eq("APP1"), eq("ciam"), saved.capture());
        assertThat(saved.getValue().authenticated()).isTrue();
        assertThat(saved.getValue().clientId()).isEqualTo("0oaX");
        assertThat(saved.getValue().scopes()).hasSize(1);
        assertThat(saved.getValue().scopes().get(0).name()).isEqualTo("WRITE");
    }

    @Test
    void authProfileEndpointReturnsTheSavedSpec() throws Exception {
        when(authProfiles.find("APP1", "ciam")).thenReturn(new ServiceAuthSpec(true,
                "https://okta/v1/token", "0oaX", "CIAM_PRIVATE_KEY", "oktaCredentials.json",
                List.of(new ServiceAuthSpec.Scope("READ", "ciam:policy:read"))));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/services/ciam/test-gen/auth-profile?appId=APP1&serviceRepoSlug=ciam"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.clientId").value("0oaX"))
                .andExpect(jsonPath("$.scopes[0].name").value("READ"));
    }
}
