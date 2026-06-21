package ca.bnc.qe.veritas.cli;

import java.util.List;
import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.persistence.ReviewResult;
import ca.bnc.qe.veritas.testmgmt.ReviewService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Review Xray test cases (ISTQB Test Analyst): score + corrected steps; optionally apply to Xray. */
@Component
@Command(name = "review-test-cases", description = "Review Xray test cases (ISTQB Test Analyst) and score them.")
public class ReviewTestCasesCommand implements Callable<Integer> {

    private final ReviewService reviewService;

    @Option(names = "--jql", required = true, description = "JQL selecting the Xray tests to review.")
    private String jql;

    @Option(names = "--apply", description = "Apply corrected steps back to Xray (gated outward action).")
    private boolean apply;

    public ReviewTestCasesCommand(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public Integer call() {
        List<ReviewResult> results = reviewService.reviewByJql(jql, "local", apply);
        double cost = results.stream().mapToDouble(ReviewResult::getEstCostUsd).sum();
        double avg = results.stream().mapToDouble(ReviewResult::getScore).average().orElse(0);
        System.out.println("Reviewed " + results.size() + " test case(s)" + (apply ? " (applied)" : ""));
        System.out.printf("Average score: %.1f · Est. LLM cost: $%.4f%n", avg, cost);
        return 0;
    }
}
