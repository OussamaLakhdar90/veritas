package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.snyk.fix.AsyncSnykFixRunner;
import ca.bnc.qe.veritas.snyk.fix.SnykFixActions;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStatus;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStep;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrain;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import ca.bnc.qe.veritas.vcs.BitbucketLinkBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer test for the Snyk auto-fix API: start (202), view the train, and the human-in-the-loop PR actions. */
@WebMvcTest(SnykFixController.class)
class SnykFixControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AsyncSnykFixRunner runner;
    @MockBean
    private SnykFixActions actions;
    @MockBean
    private SnykFixTrainRepository trains;
    @MockBean
    private SnykFixStepRepository steps;
    @MockBean
    private BitbucketLinkBuilder links;

    private SnykFixTrain train() {
        SnykFixTrain t = new SnykFixTrain();
        t.setId("t1");
        t.setCoordinate("com.x:jackson-databind");
        t.setFixedIn("2.15.0");
        t.setSeverity("critical");
        t.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
        return t;
    }

    private SnykFixStep step() {
        SnykFixStep s = new SnykFixStep();
        s.setTrainId("t1");
        s.setStepOrder(1);
        s.setModuleLabel("BOM");
        s.setRepoSlug("lsist-test-framework-bom");
        s.setBitbucketProject("APP7488");
        s.setBranch("feature/CIAM-1-snyk-fix-app-7488");
        s.setStatus(SnykFixStatus.BRANCH_PUSHED);
        s.setReviewersJson("[\"alice\"]");
        return s;
    }

    @Test
    void startFixReturns202AndTrainId() throws Exception {
        when(runner.submit(any())).thenReturn("train-1");
        mvc.perform(post("/api/v1/snyk/fixes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchId\":\"w1\",\"issueId\":\"i1\",\"coordinate\":\"com.x:y\","
                                + "\"oldVersion\":\"3.1.1\",\"fixedIn\":\"2.15.0\",\"severity\":\"critical\","
                                + "\"appIds\":[\"app7576\"],\"jiraProject\":\"CIAM\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trainId").value("train-1"));
    }

    @Test
    void startFixWithoutACoordinateReturns400() throws Exception {
        mvc.perform(post("/api/v1/snyk/fixes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fixedIn\":\"2.15.0\",\"appIds\":[\"app7576\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startFixRejectsACoordinateWithXmlMetacharactersWith400() throws Exception {
        // An XML-injection payload in the coordinate must be refused at the door, never reach the pom editor / mvn.
        mvc.perform(post("/api/v1/snyk/fixes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinate\":\"g:a</artifactId><build>x\",\"fixedIn\":\"2.15.0\","
                                + "\"appIds\":[\"app7576\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmRejectsAVersionOverrideWithXmlMetacharactersWith400() throws Exception {
        mvc.perform(post("/api/v1/snyk/fixes/t1/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionOverrides\":{\"BOM\":\"1.0</version><build>evil\"}}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(runner, org.mockito.Mockito.never()).confirm(anyString(), any(), any());
    }

    @Test
    void unknownFixTrainReturns404() throws Exception {
        when(trains.findById("missing")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/snyk/fixes/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getFixReturnsTheTrainWithStepsAndAViewBranchLink() throws Exception {
        SnykFixTrain t = train();
        t.setJiraKey("CIAM-1");
        t.setJiraStatus("In Review");
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(step()));
        // A pushed-but-no-PR step gets a browse link (the "we said pushed but I can't find it" trust fix).
        when(links.branchLink("APP7488", "lsist-test-framework-bom", "feature/CIAM-1-snyk-fix-app-7488"))
                .thenReturn(Optional.of("https://git.bnc.ca/projects/APP7488/repos/lsist-test-framework-bom/browse?at=refs/heads/feature/CIAM-1-snyk-fix-app-7488"));
        mvc.perform(get("/api/v1/snyk/fixes/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_MANUAL_FIX"))
                .andExpect(jsonPath("$.jiraStatus").value("In Review"))              // the live Jira status is exposed
                .andExpect(jsonPath("$.steps[0].moduleLabel").value("BOM"))
                .andExpect(jsonPath("$.steps[0].reviewers[0]").value("alice"))
                .andExpect(jsonPath("$.steps[0].branchUrl").value(org.hamcrest.Matchers.containsString(
                        "refs/heads/feature/CIAM-1-snyk-fix-app-7488")));
    }

    @Test
    void confirmInvokesTheRunnerAndReturns202WithTheTrain() throws Exception {
        when(trains.findById("t1")).thenReturn(Optional.of(train()));
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(step()));
        mvc.perform(post("/api/v1/snyk/fixes/t1/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionOverrides\":{\"BOM\":\"1.1.0\"},"
                                + "\"reviewerOverrides\":{\"1\":[\"bob\"]}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("t1"));
        org.mockito.Mockito.verify(runner).confirm(eq("t1"),
                eq(java.util.Map.of("BOM", "1.1.0")), eq(java.util.Map.of(1, List.of("bob"))));
    }

    @Test
    void openPrsInvokesTheAction() throws Exception {
        when(actions.openHeldPrs("t1")).thenReturn(train());
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(step()));
        mvc.perform(post("/api/v1/snyk/fixes/t1/open-prs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t1"));
    }

    @Test
    void recordUserPrInvokesTheAction() throws Exception {
        when(actions.recordUserPr(eq("t1"), anyInt(), anyString())).thenReturn(train());
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(step()));
        mvc.perform(post("/api/v1/snyk/fixes/t1/steps/1/pr").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prUrl\":\"http://host/pr/1\"}"))
                .andExpect(status().isOk());
    }
}
