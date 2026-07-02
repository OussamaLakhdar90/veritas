package ca.bnc.qe.veritas.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.snyk.fix.AsyncSnykFixRunner;
import ca.bnc.qe.veritas.snyk.fix.BreakingVerdict;
import ca.bnc.qe.veritas.snyk.fix.SnykFixActions;
import ca.bnc.qe.veritas.snyk.fix.SnykFixRequest;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStep;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepView;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrain;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Snyk auto-fix API: start a release-cascade fix (async), watch its train + steps, and drive the breaking-change
 * human-in-the-loop path (Veritas opens the held PRs, or the user records their own, then mark it merged). Outward
 * git/Jira actions are gated behind the user starting the fix; Veritas never auto-merges.
 */
@RestController
@RequestMapping("/api/v1")
public class SnykFixController {

    private final AsyncSnykFixRunner runner;
    private final SnykFixActions actions;
    private final SnykFixTrainRepository trains;
    private final SnykFixStepRepository steps;
    private final ObjectMapper mapper;

    public SnykFixController(AsyncSnykFixRunner runner, SnykFixActions actions, SnykFixTrainRepository trains,
                            SnykFixStepRepository steps, ObjectMapper mapper) {
        this.runner = runner;
        this.actions = actions;
        this.trains = trains;
        this.steps = steps;
        this.mapper = mapper;
    }

    /**
     * Start a fix (plans the cascade off-thread). {@code autoConfirm} defaults false → the train pauses at
     * AWAITING_CONFIRM for the review step; the wizard then calls {@code /confirm}. {@code autoConfirm=true} runs straight through.
     */
    @PostMapping("/snyk/fixes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> startFix(@RequestBody StartFixRequest req) {
        // Fail fast with a 400 rather than 202-accepting a malformed request that only surfaces later as a FAILED train.
        require(req.coordinate(), "coordinate (groupId:artifactId)");
        require(req.fixedIn(), "fixedIn (the safe version to upgrade to)");
        if (req.appIds() == null || req.appIds().isEmpty()) {
            throw new IllegalArgumentException("At least one app-id is required.");
        }
        String id = runner.submit(new SnykFixRequest(req.watchId(), req.issueId(), req.coordinate(),
                req.oldVersion(), req.fixedIn(), req.severity(), req.appIds(), req.jiraKey(), req.jiraProject(),
                req.jiraIssueType(), req.reviewers(), req.owner(), req.autoConfirm()));
        return Map.of("trainId", id);
    }

    /** Confirm a paused (AWAITING_CONFIRM) train with per-module version + per-step reviewer edits — resumes the cascade. */
    @PostMapping("/snyk/fixes/{id}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SnykFixTrainView confirm(@PathVariable String id, @RequestBody(required = false) ConfirmFixRequest req) {
        ConfirmFixRequest body = req == null ? new ConfirmFixRequest(Map.of(), Map.of()) : req;
        runner.confirm(id, body.versionOverrides() == null ? Map.of() : body.versionOverrides(),
                body.reviewerOverrides() == null ? Map.of() : body.reviewerOverrides());
        return fix(id);
    }

    @GetMapping("/snyk/fixes")
    public List<SnykFixTrainView> fixes() {
        return trains.findAllByOrderByStartedAtDesc().stream().map(this::toView).toList();
    }

    @GetMapping("/snyk/fixes/{id}")
    public SnykFixTrainView fix(@PathVariable String id) {
        return trains.findById(id).map(this::toView)
                .orElseThrow(() -> new ca.bnc.qe.veritas.skill.NotFoundException("Fix train not found: " + id));
    }

    /** The planned cascade (steps + diffs + per-repo reviewers) for the confirm step — an alias of {@code GET /{id}}. */
    @GetMapping("/snyk/fixes/{id}/plan")
    public SnykFixTrainView plan(@PathVariable String id) {
        return fix(id);
    }

    /** Breaking-change path: Veritas opens the held PRs (after the user adapted the branches). */
    @PostMapping("/snyk/fixes/{id}/open-prs")
    public SnykFixTrainView openPrs(@PathVariable String id) {
        return toView(actions.openHeldPrs(id));
    }

    /** Breaking-change path: the user opened a PR themselves for one step — record its URL. */
    @PostMapping("/snyk/fixes/{id}/steps/{order}/pr")
    public SnykFixTrainView recordUserPr(@PathVariable String id, @PathVariable int order,
                                         @RequestBody RecordPrRequest req) {
        return toView(actions.recordUserPr(id, order, req.prUrl()));
    }

    /** All PRs merged (human) — close the train + move the Jira to Done. */
    @PostMapping("/snyk/fixes/{id}/mark-merged")
    public SnykFixTrainView markMerged(@PathVariable String id) {
        return toView(actions.markMerged(id));
    }

    private SnykFixTrainView toView(SnykFixTrain t) {
        List<SnykFixStepView> stepViews = new ArrayList<>();
        for (SnykFixStep s : steps.findByTrainIdOrderByStepOrder(t.getId())) {
            stepViews.add(new SnykFixStepView(s.getStepOrder(), s.getModuleLabel(), s.getBitbucketProject(),
                    s.getRepoSlug(), s.getBranch(), s.getPomPath(), s.getDiffPreview(), s.getNewModuleVersion(),
                    s.getPrUrl(), s.getPrOpenedBy(), s.getStatus(), s.isManual(), s.getReason(),
                    parseList(s.getReviewersJson())));
        }
        return new SnykFixTrainView(t.getId(), t.getCoordinate(), t.getOldVersion(), t.getFixedIn(), t.getSeverity(),
                t.getAppIds(), t.getJiraKey(), t.getStatus(), t.getStageDetail(), t.isBreaking(),
                t.getReactorPassed(), t.getReactorFailingLabel(), t.getReactorOutputTail(),
                parseVerdict(t.getVerdictJson()), t.getStartedAt(), stepViews);
    }

    private BreakingVerdict parseVerdict(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, BreakingVerdict.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseList(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            for (JsonNode n : mapper.readTree(json)) {
                out.add(n.asText());
            }
        } catch (Exception e) {
            // leave empty
        }
        return out;
    }

    public record StartFixRequest(String watchId, String issueId, String coordinate, String oldVersion,
                                  String fixedIn, String severity, List<String> appIds, String jiraKey,
                                  String jiraProject, String jiraIssueType, List<String> reviewers, String owner,
                                  boolean autoConfirm) {}

    /** The user's edits from the review step: per-module (BOM/core/api/web) new versions + per-step-order reviewers. */
    public record ConfirmFixRequest(Map<String, String> versionOverrides,
                                    Map<Integer, List<String>> reviewerOverrides) {}

    public record RecordPrRequest(String prUrl) {}

    private static void require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required.");
        }
    }
}
