package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.evolve.ClassificationTrain;
import ca.bnc.qe.veritas.evolve.EngineEvolutionService;
import ca.bnc.qe.veritas.settings.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Engine-Evolution API: view classification proposals (trains), recompute them from the field votes, challenge the
 * AI's suggestion (override + a required reason), open the gated promotion PR, and mark it merged. Every outward git
 * action is gated behind human approval + a configured target repo; Veritas never auto-merges.
 */
@RestController
@RequestMapping("/api/v1/engine-evolution")
public class EvolveController {

    private final EngineEvolutionService service;
    private final CurrentUser currentUser;

    public EvolveController(EngineEvolutionService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** All classification trains, newest first. */
    @GetMapping("/proposals")
    public List<ClassificationTrain> proposals() {
        return service.all();
    }

    /** Recompute proposals from the accumulated field votes (upserts one open train per pending type). */
    @PostMapping("/refresh")
    public List<ClassificationTrain> refresh() {
        return service.refresh(currentUser.principalId());
    }

    /** Override the suggested severity with a required reason (captured for the PR audit trail). */
    @PostMapping("/proposals/{id}/challenge")
    public ClassificationTrain challenge(@PathVariable String id, @RequestBody ChallengeRequest req) {
        return service.challenge(id, req.severity(), req.comment());
    }

    /** Open the gated deterministic promotion PR against Veritas's own repo. */
    @PostMapping("/proposals/{id}/open-pr")
    public ClassificationTrain openPr(@PathVariable String id) {
        return service.openPr(id, currentUser.principalId());
    }

    /** Record that the human merged the promotion PR (the learned classification is live after the next deploy). */
    @PostMapping("/proposals/{id}/mark-merged")
    public ClassificationTrain markMerged(@PathVariable String id) {
        return service.markMerged(id);
    }

    public record ChallengeRequest(String severity, String comment) {}
}
