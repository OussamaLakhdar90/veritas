package ca.bnc.qe.veritas.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.vcs.GitHost;
import ca.bnc.qe.veritas.vcs.RepoInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The repo-picker front door: list repos under an app-id and a repo's branches. */
@WebMvcTest(ReposController.class)
class ReposControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private GitHost gitHost;

    @Test
    void listsReposForAnAppId() throws Exception {
        when(gitHost.discoverRepos("APP7571")).thenReturn(List.of(
                new RepoInfo("ciam-policies", "CIAM Policies", null, "develop", "https://git/x.git", "APP7571", null)));
        mvc.perform(get("/api/v1/repos").param("appId", "APP7571"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("ciam-policies"));
    }

    @Test
    void listsBranchesForARepo() throws Exception {
        when(gitHost.listBranches("APP7571", "ciam-policies")).thenReturn(List.of("develop", "main"));
        mvc.perform(get("/api/v1/repos/ciam-policies/branches").param("appId", "APP7571"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("develop"));
    }

    @Test
    void searchesBitbucketUsersForTheReviewerPicker() throws Exception {
        when(gitHost.searchUsers("ali", 25)).thenReturn(List.of(
                new GitHost.GitUser("alice", "Alice Ng"), new GitHost.GitUser("alicia", "Alicia Ba")));
        mvc.perform(get("/api/v1/bitbucket/users").param("query", "ali"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("alice"))
                .andExpect(jsonPath("$[0].displayName").value("Alice Ng"));
    }
}
