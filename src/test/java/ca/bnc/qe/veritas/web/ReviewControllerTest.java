package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.persistence.ReviewResultRepository;
import ca.bnc.qe.veritas.testmgmt.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The review API: list prior reviews by target key, and run a JQL review (202, delegating to the service). */
@WebMvcTest(ReviewController.class)
class ReviewControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private ReviewResultRepository repository;
    @MockBean private ReviewService service;

    @Test
    void listsReviewsByTargetKey() throws Exception {
        when(repository.findByTargetKeyOrderByCreatedAtDesc("CIAM-1")).thenReturn(List.of());
        mvc.perform(get("/api/v1/reviews").param("targetKey", "CIAM-1")).andExpect(status().isOk());
        verify(repository).findByTargetKeyOrderByCreatedAtDesc("CIAM-1");
    }

    @Test
    void runReviewReturns202AndDelegates() throws Exception {
        when(service.reviewByJql(eq("project = CIAM"), any(), eq(false), any())).thenReturn(List.of());
        mvc.perform(post("/api/v1/reviews").contentType("application/json")
                        .content("{\"jql\":\"project = CIAM\",\"apply\":false}"))
                .andExpect(status().isAccepted());
        // no testKeys in the body → empty set passed → review every match
        verify(service).reviewByJql(eq("project = CIAM"), any(), eq(false), eq(java.util.Set.of()));
    }

    @Test
    void runReviewWithSelectedKeysDelegatesThatSubset() throws Exception {
        when(service.reviewByJql(eq("project = CIAM"), any(), eq(false), any())).thenReturn(List.of());
        mvc.perform(post("/api/v1/reviews").contentType("application/json")
                        .content("{\"jql\":\"project = CIAM\",\"apply\":false,\"testKeys\":[\"CIAM-2\",\"CIAM-5\"]}"))
                .andExpect(status().isAccepted());
        verify(service).reviewByJql(eq("project = CIAM"), any(), eq(false),
                eq(new java.util.LinkedHashSet<>(List.of("CIAM-2", "CIAM-5"))));
    }

    @Test
    void candidatesListsTestsForAJql() throws Exception {
        when(service.candidates("project = CIAM")).thenReturn(List.of(
                new ca.bnc.qe.veritas.integration.xray.XrayTest("CIAM-1", "1", "Create policy", "Manual", List.of())));
        mvc.perform(get("/api/v1/reviews/candidates").param("jql", "project = CIAM"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].key").value("CIAM-1"));
        verify(service).candidates("project = CIAM");
    }
}
