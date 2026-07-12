package ca.bnc.qe.veritas.snyk.fix;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.skill.ConflictException;
import ca.bnc.qe.veritas.skill.NotFoundException;
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
        boolean llmBreaking = train.isBreaking();
        boolean inconclusive = Boolean.TRUE.equals(train.getReactorInconclusive());
        // A genuine reactor break is a non-pass that is NOT the app's own config/infra failing (that's inconclusive).
        boolean reactorFailed = Boolean.FALSE.equals(train.getReactorPassed()) && !inconclusive;
        // "breaking" (the train field, used for the PR body) means a REAL build break OR the advisory LLM's concern.
        // But the HOLD REASON must not conflate the two: an INCONCLUSIVE reactor (the app's own build config broke) is
        // NOT a confirmed breaking change even when the advisory LLM independently flagged one — otherwise the card
        // headlines "breaking change" while the log says "not a code break", which reads as a contradiction.
        train.setBreaking(reactorFailed || llmBreaking);
        String decision = reactorFailed ? "HOLD PRs (build failed)"
                : inconclusive ? "HOLD PRs (verification inconclusive)"
                : llmBreaking ? "HOLD PRs (AI-flagged, build passed)"
                : "open the PR train automatically";
        log.info("Snyk fix train {}: DECIDE — llmBreaking={}, reactorFailed={}, inconclusive={} → {}", train.getId(),
                llmBreaking, reactorFailed, inconclusive, decision);
        if (reactorFailed) {
            // A genuine compile failure against the upgraded framework — the real gate broke. Hold; name the module.
            train.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
            train.setStageDetail(breakingDetail(train));
        } else if (inconclusive) {
            // We COULDN'T verify — the build infrastructure failed (a dependency couldn't be fetched, not the upgrade).
            // Hold with an honest reason; if the advisory AI separately flagged a possible breaking change, mention it
            // as advisory — never present an unverified fix as a confirmed breaking change.
            train.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
            train.setStageDetail(inconclusiveDetail(train)
                    + (llmBreaking ? " Separately, the AI flagged a possible breaking change (advisory) — review it, "
                            + "but note the build itself was not verified." : ""));
        } else if (llmBreaking) {
            // The consumers COMPILED against the upgrade, but the advisory AI flagged a possible breaking change — hold
            // for a human look. Phrase it as advisory, not a confirmed break (the real gate, the compile, was clean).
            train.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
            train.setStageDetail("Every app compiled against the upgraded framework, but the AI flagged a possible "
                    + "breaking change (advisory). The version-bump branches are pushed; review the AI's reasoning, "
                    + "then open the PRs (yourself or via Veritas).");
        } else {
            openAll(train, trainSteps, SnykFixStatus.BY_VERITAS);
            markPrTrainOpenedOrHeld(train, trainSteps,
                    "Clean — every app compiles against the upgraded framework; PR train opened, reviewers assigned. "
                            + "The apps' own tests run in CI/Jenkins after the PR is reviewed and merged.");
        }
        trains.save(train);
    }

    /**
     * After an {@link #openAll} pass, advance to PR_OPEN + Jira In Review only when every actionable step's PR is
     * actually open; if some open calls failed (steps left at BRANCH_PUSHED) fall back to AWAITING_MANUAL_FIX so the
     * user can retry or open them by hand — never claim PR_OPEN (and never move Jira) when zero PRs exist.
     */
    private void markPrTrainOpenedOrHeld(SnykFixTrain train, List<SnykFixStep> trainSteps, String openedDetail) {
        if (allActionableOpen(trainSteps)) {
            train.setStatus(SnykFixStatus.PR_OPEN);
            train.setStageDetail(openedDetail);
            recordJiraStatus(train, jiraService.transitionTo(train.getJiraKey(), SnykFixJiraService.Phase.IN_REVIEW));
            return;
        }
        train.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
        // Distinguish "some PRs failed to open (branches are up)" from the total failure "nothing pushed at all",
        // so the held detail is honest instead of claiming pushed branches that don't exist.
        boolean anyBranchUp = trainSteps.stream()
                .anyMatch(s -> !s.isManual() && !SnykFixStatus.STEP_FAILED.equals(s.getStatus()));
        if (anyBranchUp) {
            train.setStageDetail("Some PRs could not be opened automatically — the branches are pushed; "
                    + "retry, or open the remaining PRs yourself.");
        } else {
            train.setStageDetail("No PR was opened — every version-bump push failed (commonly the Bitbucket "
                    + "access token lacks write scope — check Settings), so there is no branch to open a PR against. "
                    + "Fix access and retry.");
            log.error("Snyk fix train {}: every branch push FAILED — holding at AWAITING_MANUAL_FIX with no PRs "
                    + "(no branch was pushed).", train.getId());
        }
    }

    /** The user asks Veritas to open the held PRs (breaking-change path, after they adapted the branches). */
    public SnykFixTrain openHeldPrs(String trainId) {
        SnykFixTrain train = trains.findById(trainId).orElseThrow(
                () -> new NotFoundException("Fix train not found: " + trainId));
        requireStatus(train, SnykFixStatus.AWAITING_MANUAL_FIX);
        // Claim the train first (optimistic-lock via @Version) so a concurrent open-prs click can't also open the PRs.
        // Reset the runtime clock too: the train may have waited days in AWAITING_MANUAL_FIX, and OPENING_PRS is a
        // MACHINE_DRIVEN state — without this the staleness reconciler would immediately fail the just-resumed train.
        train.setStatus(SnykFixStatus.OPENING_PRS);
        train.setStartedAt(Instant.now());
        // Reassign the saved instance: `train` is detached and @Version-guarded, so a discarded save() return would
        // leave a stale version and make the second save below throw "Row was updated…" with no concurrent writer.
        train = trains.save(train);
        List<SnykFixStep> trainSteps = steps.findByTrainIdOrderByStepOrder(trainId);
        openAll(train, trainSteps, SnykFixStatus.BY_VERITAS);
        markPrTrainOpenedOrHeld(train, trainSteps, "PRs opened by Veritas; awaiting human merge.");
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
                () -> new NotFoundException("Fix train not found: " + trainId));
        requireStatus(train, SnykFixStatus.AWAITING_MANUAL_FIX, SnykFixStatus.PR_OPEN);
        List<SnykFixStep> trainSteps = steps.findByTrainIdOrderByStepOrder(trainId);
        for (SnykFixStep s : trainSteps) {
            // Only a step that isn't manual and doesn't already have an open/merged PR: never overwrite a PR
            // (e.g. one Veritas already opened) with a user-supplied URL.
            if (s.getStepOrder() == stepOrder && !s.isManual()
                    && !SnykFixStatus.STEP_PR_OPEN.equals(s.getStatus())
                    && !SnykFixStatus.MERGED.equals(s.getStatus())) {
                s.setPrUrl(prUrl);
                s.setPrOpenedBy(SnykFixStatus.BY_USER);
                s.setStatus(SnykFixStatus.STEP_PR_OPEN);
                steps.save(s);
            }
        }
        if (allActionableOpen(trainSteps)) {
            train.setStatus(SnykFixStatus.PR_OPEN);
            recordJiraStatus(train, jiraService.transitionTo(train.getJiraKey(), SnykFixJiraService.Phase.IN_REVIEW));
            trains.save(train);
        }
        return train;
    }

    /** All PRs merged (by a human) — close the train and move the Jira to Done. Veritas never merges itself. */
    public SnykFixTrain markMerged(String trainId) {
        SnykFixTrain train = trains.findById(trainId).orElseThrow(
                () -> new NotFoundException("Fix train not found: " + trainId));
        // Only a train whose PRs are actually open can be marked merged — never PLANNING/AWAITING_*/VERIFYING.
        requireStatus(train, SnykFixStatus.PR_OPEN);
        for (SnykFixStep s : steps.findByTrainIdOrderByStepOrder(trainId)) {
            // Only a step whose PR was actually open becomes MERGED — never a push-failed (STEP_FAILED) or
            // still-BRANCH_PUSHED step that never had a PR, which would falsely record it as shipped.
            if (SnykFixStatus.STEP_PR_OPEN.equals(s.getStatus())) {
                s.setStatus(SnykFixStatus.MERGED);
                steps.save(s);
            }
        }
        train.setStatus(SnykFixStatus.DONE);
        train.setFinishedAt(Instant.now());
        recordJiraStatus(train, jiraService.transitionTo(train.getJiraKey(), SnykFixJiraService.Phase.DONE));
        return trains.save(train);
    }

    /**
     * The user abandons a train that is waiting on them — a third choice besides "open the PRs" / "record my PR", so a
     * build-failed (or breaking, or reviewed) fix isn't a dead-end that forces a PR or a DB delete. Allowed ONLY from
     * the human-wait states; moves the train to the terminal CANCELLED (honest, muted — never the red FAILED). The
     * Jira ticket and any pushed branch(es) are left AS-IS on purpose, so a relaunch reuses them (the branch is
     * deterministic from the carried-forward Jira key). A machine-driven hang is handled by the reconciler, not here.
     */
    public SnykFixTrain cancel(String trainId) {
        SnykFixTrain train = trains.findById(trainId).orElseThrow(
                () -> new NotFoundException("Fix train not found: " + trainId));
        requireStatus(train, SnykFixStatus.AWAITING_CONFIRM, SnykFixStatus.AWAITING_MANUAL_FIX, SnykFixStatus.PR_OPEN);
        train.setStatus(SnykFixStatus.CANCELLED);
        train.setFinishedAt(Instant.now());   // terminal → the retention sweeper may eventually prune it
        train.setStageDetail("Fix abandoned. The Jira ticket and any pushed branch(es) were left as-is — relaunch to "
                + "reuse them, or open the PRs later.");
        return trains.save(train);
    }

    /** Persist the live Jira status the transition landed on (null when nothing moved — keep the last known status). */
    private static void recordJiraStatus(SnykFixTrain train, String status) {
        if (status != null && !status.isBlank()) {
            train.setJiraStatus(status);
        }
    }

    private void openAll(SnykFixTrain train, List<SnykFixStep> trainSteps, String openedBy) {
        for (SnykFixStep s : trainSteps) {
            // Skip manual steps, already-open/merged PRs, and steps whose branch push FAILED (no branch to open a PR against).
            if (s.isManual() || SnykFixStatus.STEP_PR_OPEN.equals(s.getStatus())
                    || SnykFixStatus.MERGED.equals(s.getStatus()) || SnykFixStatus.STEP_FAILED.equals(s.getStatus())) {
                continue;
            }
            openStepPr(train, s, openedBy);
        }
    }

    /** Guard a public action against being invoked from an unexpected lifecycle state. */
    private void requireStatus(SnykFixTrain train, String... allowed) {
        for (String a : allowed) {
            if (a.equals(train.getStatus())) {
                return;
            }
        }
        throw new ConflictException("Fix train " + train.getId() + " is " + train.getStatus()
                + "; this action requires " + String.join(" or ", allowed) + ".");
    }

    /** The action-needed line for a held (inconclusive) train — the build infrastructure failed, not the upgrade. */
    private static String inconclusiveDetail(SnykFixTrain train) {
        String where = train.getReactorFailingLabel() != null && !train.getReactorFailingLabel().isBlank()
                ? " (" + train.getReactorFailingLabel() + ")" : "";
        return "Verification inconclusive — the build infrastructure failed" + where + " (a dependency couldn't be "
                + "fetched), not the dependency change. The version-bump branches are pushed; review the build, then "
                + "open the PRs (yourself or via Veritas).";
    }

    /** The action-needed line for a held (breaking) train — names the module that broke the build when one did. */
    private static String breakingDetail(SnykFixTrain train) {
        String base = "the version-bump branches are pushed. Adapt the code + test, then open the PRs (yourself or "
                + "via Veritas).";
        if (Boolean.FALSE.equals(train.getReactorPassed())
                && train.getReactorFailingLabel() != null && !train.getReactorFailingLabel().isBlank()) {
            return "Action needed — the local build failed at " + train.getReactorFailingLabel() + "; " + base;
        }
        return "Action needed — breaking change; " + base;
    }

    /** Open one step's PR in its Bitbucket project with its reviewers; records the URL + who opened it. */
    void openStepPr(SnykFixTrain train, SnykFixStep step, String openedBy) {
        // Mark this module active before the (slow) PR-open so a poll shows the stepper advancing to it.
        step.setStatus(SnykFixStatus.RUNNING);
        step.setStageDetail("Opening the PR for " + step.getModuleLabel() + "…");
        steps.save(step);
        try {
            String url = gitHost.openPullRequest(new GitHost.PullRequestSpec(
                    step.getBitbucketProject(), step.getRepoSlug(), step.getBranch(), fw.getBranch(),
                    prTitle(train, step), prBody(train, step), reviewers(step)));
            step.setPrUrl(url);
            step.setPrOpenedBy(openedBy);
            step.setStatus(SnykFixStatus.STEP_PR_OPEN);
            step.setStageDetail(null);   // the "View PR" link is now the persistent indication for this step
            step.setReason(null);        // clear any stale "PR open failed…" reason from an earlier retry
            steps.save(step);
        } catch (RuntimeException e) {
            log.warn("Opening PR for step {} ({}) failed: {}", step.getModuleLabel(), step.getRepoSlug(), e.getMessage());
            step.setStatus(SnykFixStatus.BRANCH_PUSHED);   // branch is up; the user can retry / open it manually
            step.setStageDetail(null);
            step.setReason("PR open failed: " + e.getMessage());
            steps.save(step);
        }
    }

    /**
     * True when every <em>actionable</em> step's PR is open (or merged). Manual steps have no PR; a push-failed
     * (STEP_FAILED) step has no branch to open a PR against, so both are excluded — otherwise a single failed push
     * would block the whole train from ever completing through the record-your-own-PR / open-held-PRs flows.
     */
    private boolean allActionableOpen(List<SnykFixStep> trainSteps) {
        List<SnykFixStep> actionable = trainSteps.stream()
                .filter(s -> !s.isManual() && !SnykFixStatus.STEP_FAILED.equals(s.getStatus()))
                .toList();
        // Guard the vacuous case: if every pushable step failed (or there are none), the actionable set is empty and
        // allMatch would return true — falsely claiming PR_OPEN with zero PRs. A train is only "open" when at least one
        // real PR exists AND nothing pushable is still pending.
        return !actionable.isEmpty()
                && actionable.stream().allMatch(s -> SnykFixStatus.STEP_PR_OPEN.equals(s.getStatus())
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
            b.append("Jira: ").append(train.getJiraKey());
            if (train.getJiraSummary() != null && !train.getJiraSummary().isBlank()) {
                b.append(" — ").append(train.getJiraSummary());   // the ticket's name, for reviewer context
            }
            b.append("\n");
        }
        b.append("\nHumans merge this — Veritas never auto-merges.");
        Instant startedAt = train.getStartedAt();   // referenced only to keep the body deterministic per-train
        if (startedAt != null) {
            b.append("\nFix train started ").append(startedAt).append(".");
        }
        return b.toString();
    }
}
