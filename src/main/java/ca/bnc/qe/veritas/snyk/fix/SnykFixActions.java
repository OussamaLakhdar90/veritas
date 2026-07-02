package ca.bnc.qe.veritas.snyk.fix;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.vcs.GitHost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The decision + PR-opening + user-action logic of a fix train — the part that decides <b>clean vs breaking</b> and
 * tracks who opens each PR. Kept free of clone/build I/O so the state machine is unit-testable: the async runner does
 * the heavy lifting (clone/edit/build/push) and calls {@link #decide}; the controller calls {@link #openHeldPrs} and
 * {@link #recordUserPr} for the breaking-change human-in-the-loop path. Never auto-merges.
 */
@Service
@Slf4j
public class SnykFixActions {

    private final GitHost gitHost;
    private final SnykFixJiraService jiraService;
    private final FrameworkProperties fw;
    private final SnykFixTrainRepository trains;
    private final SnykFixStepRepository steps;
    private final ObjectMapper mapper;

    public SnykFixActions(GitHost gitHost, SnykFixJiraService jiraService, FrameworkProperties fw,
                          SnykFixTrainRepository trains, SnykFixStepRepository steps, ObjectMapper mapper) {
        this.gitHost = gitHost;
        this.jiraService = jiraService;
        this.fw = fw;
        this.trains = trains;
        this.steps = steps;
        this.mapper = mapper;
    }

    /**
     * Decide the outcome once the branches are pushed and both verdicts are in. Clean → Veritas opens the PR train +
     * Jira → In Review. Breaking (LLM breaking OR reactor failed) → hold the PRs, await the user; Jira stays In Progress.
     */
    public void decide(SnykFixTrain train, List<SnykFixStep> trainSteps) {
        train.setBreaking(train.isBreaking() || Boolean.FALSE.equals(train.getReactorPassed()));
        if (!train.isBreaking()) {
            openAll(train, trainSteps, SnykFixStatus.BY_VERITAS);
            train.setStatus(SnykFixStatus.PR_OPEN);
            train.setStageDetail("Clean — PR train opened; reviewers assigned; awaiting human merge.");
            jiraService.transitionTo(train.getJiraKey(), SnykFixJiraService.Phase.IN_REVIEW);
        } else {
            train.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
            train.setStageDetail("Breaking change — the version-bump branches are pushed. Adapt the code + test, "
                    + "then open the PRs (yourself or via Veritas).");
        }
        trains.save(train);
    }

    /** The user asks Veritas to open the held PRs (breaking-change path, after they adapted the branches). */
    public SnykFixTrain openHeldPrs(String trainId) {
        SnykFixTrain train = trains.findById(trainId).orElseThrow(
                () -> new ca.bnc.qe.veritas.skill.NotFoundException("Fix train not found: " + trainId));
        List<SnykFixStep> trainSteps = steps.findByTrainIdOrderByStepOrder(trainId);
        openAll(train, trainSteps, SnykFixStatus.BY_VERITAS);
        train.setStatus(SnykFixStatus.PR_OPEN);
        train.setStageDetail("PRs opened by Veritas; awaiting human merge.");
        jiraService.transitionTo(train.getJiraKey(), SnykFixJiraService.Phase.IN_REVIEW);
        return trains.save(train);
    }

    /** The user opened a PR themselves for one step — record it and advance the train if all PRs are now open. */
    public SnykFixTrain recordUserPr(String trainId, int stepOrder, String prUrl) {
        // The URL is rendered as a clickable link in every reviewer's browser — reject anything but http(s) so a
        // javascript:/data: value can't become a stored-XSS href.
        if (prUrl == null || !prUrl.matches("(?i)https?://\\S+")) {
            throw new IllegalArgumentException("PR URL must be an http(s) link.");
        }
        SnykFixTrain train = trains.findById(trainId).orElseThrow(
                () -> new ca.bnc.qe.veritas.skill.NotFoundException("Fix train not found: " + trainId));
        List<SnykFixStep> trainSteps = steps.findByTrainIdOrderByStepOrder(trainId);
        for (SnykFixStep s : trainSteps) {
            if (s.getStepOrder() == stepOrder && !s.isManual()) {
                s.setPrUrl(prUrl);
                s.setPrOpenedBy(SnykFixStatus.BY_USER);
                s.setStatus(SnykFixStatus.STEP_PR_OPEN);
                steps.save(s);
            }
        }
        if (allActionableOpen(trainSteps)) {
            train.setStatus(SnykFixStatus.PR_OPEN);
            jiraService.transitionTo(train.getJiraKey(), SnykFixJiraService.Phase.IN_REVIEW);
            trains.save(train);
        }
        return train;
    }

    /** All PRs merged (by a human) — close the train and move the Jira to Done. Veritas never merges itself. */
    public SnykFixTrain markMerged(String trainId) {
        SnykFixTrain train = trains.findById(trainId).orElseThrow(
                () -> new ca.bnc.qe.veritas.skill.NotFoundException("Fix train not found: " + trainId));
        for (SnykFixStep s : steps.findByTrainIdOrderByStepOrder(trainId)) {
            if (!s.isManual()) {
                s.setStatus(SnykFixStatus.MERGED);
                steps.save(s);
            }
        }
        train.setStatus(SnykFixStatus.DONE);
        train.setFinishedAt(Instant.now());
        jiraService.transitionTo(train.getJiraKey(), SnykFixJiraService.Phase.DONE);
        return trains.save(train);
    }

    private void openAll(SnykFixTrain train, List<SnykFixStep> trainSteps, String openedBy) {
        for (SnykFixStep s : trainSteps) {
            if (s.isManual() || SnykFixStatus.STEP_PR_OPEN.equals(s.getStatus()) || SnykFixStatus.MERGED.equals(s.getStatus())) {
                continue;
            }
            openStepPr(train, s, openedBy);
        }
    }

    /** Open one step's PR in its Bitbucket project with its reviewers; records the URL + who opened it. */
    void openStepPr(SnykFixTrain train, SnykFixStep step, String openedBy) {
        try {
            String url = gitHost.openPullRequest(new GitHost.PullRequestSpec(
                    step.getBitbucketProject(), step.getRepoSlug(), step.getBranch(), fw.getBranch(),
                    prTitle(train, step), prBody(train, step), reviewers(step)));
            step.setPrUrl(url);
            step.setPrOpenedBy(openedBy);
            step.setStatus(SnykFixStatus.STEP_PR_OPEN);
            steps.save(step);
        } catch (RuntimeException e) {
            log.warn("Opening PR for step {} ({}) failed: {}", step.getModuleLabel(), step.getRepoSlug(), e.getMessage());
            step.setStatus(SnykFixStatus.BRANCH_PUSHED);   // branch is up; the user can retry / open it manually
            step.setReason("PR open failed: " + e.getMessage());
            steps.save(step);
        }
    }

    private boolean allActionableOpen(List<SnykFixStep> trainSteps) {
        return trainSteps.stream().filter(s -> !s.isManual())
                .allMatch(s -> SnykFixStatus.STEP_PR_OPEN.equals(s.getStatus())
                        || SnykFixStatus.MERGED.equals(s.getStatus()));
    }

    private List<String> reviewers(SnykFixStep step) {
        List<String> out = new ArrayList<>();
        if (step.getReviewersJson() == null || step.getReviewersJson().isBlank()) {
            return out;
        }
        try {
            for (JsonNode n : mapper.readTree(step.getReviewersJson())) {
                out.add(n.asText());
            }
        } catch (Exception e) {
            log.debug("Could not parse reviewers for step {}: {}", step.getRepoSlug(), e.getMessage());
        }
        return out;
    }

    private String prTitle(SnykFixTrain train, SnykFixStep step) {
        String key = train.getJiraKey() == null || train.getJiraKey().isBlank() ? "" : train.getJiraKey() + " ";
        return key + "Snyk fix (" + step.getModuleLabel() + "): bump to safe version " + train.getFixedIn();
    }

    private String prBody(SnykFixTrain train, SnykFixStep step) {
        StringBuilder b = new StringBuilder();
        b.append("Automated Snyk dependency-security fix.\n\n");
        b.append("Change: ").append(step.getDiffPreview()).append("\n\n");
        b.append("Dependency: ").append(train.getCoordinate())
                .append(" (").append(train.getOldVersion()).append(" -> ").append(train.getFixedIn()).append(")\n");
        b.append("Snyk severity: ").append(train.getSeverity() == null ? "" : train.getSeverity()).append("\n");
        b.append("Risk: ").append(train.isBreaking() ? "breaking change flagged — reviewed manually"
                : "no breaking change expected (advisory)").append("\n");
        b.append("Local reactor build: ").append(Boolean.TRUE.equals(train.getReactorPassed())
                ? "passed (mvn install + per-app mvn test)" : "see the fix train for details").append("\n");
        if (train.getJiraKey() != null && !train.getJiraKey().isBlank()) {
            b.append("Jira: ").append(train.getJiraKey()).append("\n");
        }
        b.append("\nHumans merge this — Veritas never auto-merges.");
        Instant startedAt = train.getStartedAt();   // referenced only to keep the body deterministic per-train
        if (startedAt != null) {
            b.append("\nFix train started ").append(startedAt).append(".");
        }
        return b.toString();
    }
}
