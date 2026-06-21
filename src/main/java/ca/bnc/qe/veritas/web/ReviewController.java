package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.persistence.ReviewResult;
import ca.bnc.qe.veritas.persistence.ReviewResultRepository;
import ca.bnc.qe.veritas.testmgmt.ReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Review REST (ISTQB Test Analyst): list prior reviews + run a review over Xray tests selected by JQL. */
@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewResultRepository repository;
    private final ReviewService service;

    public ReviewController(ReviewResultRepository repository, ReviewService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping("/reviews")
    public List<ReviewResult> list(@RequestParam String targetKey) {
        return repository.findByTargetKeyOrderByCreatedAtDesc(targetKey);
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<ReviewResult> review(@RequestBody ReviewRequest req) {
        return service.reviewByJql(req.jql(), req.owner() == null ? "api" : req.owner(), req.apply());
    }

    public record ReviewRequest(String jql, boolean apply, String owner) {}
}
