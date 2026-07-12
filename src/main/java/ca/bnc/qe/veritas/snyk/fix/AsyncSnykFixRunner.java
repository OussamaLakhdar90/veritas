package ca.bnc.qe.veritas.snyk.fix;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import ca.bnc.qe.veritas.codegen.GeneratedFileWriter;
import ca.bnc.qe.veritas.codegen.PrPublisher;
import ca.bnc.qe.veritas.skill.ConflictException;
import ca.bnc.qe.veritas.skill.NotFoundException;
import ca.bnc.qe.veritas.snyk.fix.CascadePlanner.AppInput;
import ca.bnc.qe.veritas.snyk.fix.CascadePlanner.FrameworkPoms;
import ca.bnc.qe.veritas.snyk.fix.CascadeVerifier.ConsumerBuild;
import ca.bnc.qe.veritas.snyk.fix.CascadeVerifier.ModuleBuild;
import ca.bnc.qe.veritas.snyk.fix.CascadeVerifier.ReactorInputs;
import ca.bnc.qe.veritas.snyk.fix.CascadeVerifier.ReactorResult;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Runs a Snyk fix train off-thread: clone the framework + selected consumer repos, plan the cascade, get the
 * advisory breaking-change verdict, create/drive the Jira ticket, edit the poms, run the local reactor build, push
 * the branches, then decide. <b>Clean</b> (LLM non-breaking AND reactor passed) → open the PR train + Jira In
 * Review. <b>Breaking</b> → hold the PRs (push-only) and await the user. Never auto-merges. Heavy I/O — the state
 * machine's decision + PR-open + Jira logic lives in the unit-tested {@link SnykFixActions}/{@link SnykFixJiraService}.
 */
@Component
@Slf4j
public class AsyncSnykFixRunner {

    private final WorkspaceService workspace;
    private final CascadePlanner planner;
    private final CascadeVerifier verifier;
    private final BreakingChangeService breakingChange;
    private final BuildCommandAdvisor buildCommandAdvisor;
    private final FixDiffValidator fixDiffValidator;
    private final SnykFixJiraService jiraService;
    private final SnykFixActions actions;
    private final ReviewerSuggester reviewerSuggester;
    private final GeneratedFileWriter fileWriter;
    private final PrPublisher prPublisher;
    private final FrameworkProperties fw;
    private final SnykFixTrainRepository trains;
    private final SnykFixStepRepository steps;
    private final ObjectMapper mapper;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "snyk-fix");
        t.setDaemon(true);
        return t;
    });

    public AsyncSnykFixRunner(WorkspaceService workspace, CascadePlanner planner, CascadeVerifier verifier,
                              BreakingChangeService breakingChange, BuildCommandAdvisor buildCommandAdvisor,
                              FixDiffValidator fixDiffValidator, SnykFixJiraService jiraService, SnykFixActions actions,
                              ReviewerSuggester reviewerSuggester, GeneratedFileWriter fileWriter,
                              PrPublisher prPublisher, FrameworkProperties fw, SnykFixTrainRepository trains,
                              SnykFixStepRepository steps, ObjectMapper mapper) {
        this.workspace = workspace;
        this.planner = planner;
        this.verifier = verifier;
        this.breakingChange = breakingChange;
        this.buildCommandAdvisor = buildCommandAdvisor;
        this.fixDiffValidator = fixDiffValidator;
        this.jiraService = jiraService;
        this.actions = actions;
        this.reviewerSuggester = reviewerSuggester;
        this.fileWriter = fileWriter;
        this.prPublisher = prPublisher;
        this.fw = fw;
        this.trains = trains;
        this.steps = steps;
        this.mapper = mapper;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Create the train row + kick off the cascade on a background thread; returns the train id immediately. */
    public String submit(SnykFixRequest req) {
        SnykFixRequest effective = req;
        // Idempotency: if a fix is already in flight for this watch + coordinate, reuse it rather than starting a
        // duplicate train that would clone/push the same branches (a poll re-alert or a double click can trigger this).
        if (req.watchId() != null && req.coordinate() != null) {
            List<SnykFixTrain> priors = trains.findByWatchIdAndCoordinate(req.watchId(), req.coordinate());
            for (SnykFixTrain existing : priors) {
                if (SnykFixStatus.NON_TERMINAL.contains(existing.getStatus())) {
                    log.info("Snyk fix already in flight for {} on watch {} — reusing train {}",
                            req.coordinate(), req.watchId(), existing.getId());
                    return existing.getId();
                }
            }
            // Relaunch continuity: if the caller supplied no key, carry forward the most-recent CANCELLED/FAILED prior
            // train's Jira key — the branch is deterministic from it (branchName(key, project)), so the relaunch reuses
            // the SAME ticket + branch (accumulating, not duplicating) instead of filing a fresh one. Never from a DONE
            // train — that ticket is closed, so a regressed vuln gets a new one.
            if (isBlank(req.jiraKey())) {
                String carried = carryForwardJiraKey(priors);
                if (carried != null) {
                    effective = req.withJiraKey(carried);
                    log.info("Snyk fix relaunch for {} on watch {}: reusing prior Jira key {} (and its branch).",
                            req.coordinate(), req.watchId(), carried);
                }
            }
        }
        SnykFixTrain train = new SnykFixTrain();
        train.setWatchId(effective.watchId());
        train.setIssueId(effective.issueId());
        train.setCoordinate(effective.coordinate());
        train.setOldVersion(effective.oldVersion());
        train.setFixedIn(effective.fixedIn());
        train.setSeverity(effective.severity());
        train.setAppIds(effective.appIds() == null ? "" : String.join(",", effective.appIds()));
        train.setJiraKey(effective.jiraKey());      // requested-or-carried key (may be null) — kept so confirm can rebuild
        train.setStoryKey(effective.storyKey());    // the shared bulk story (null for a single fix) — groups the batch
        train.setJiraProject(effective.jiraProject());
        train.setJiraIssueType(effective.jiraIssueType());
        train.setOwner(effective.owner());
        train.setStatus(SnykFixStatus.PLANNING);
        train.setStartedAt(Instant.now());
        SnykFixTrain saved = trains.save(train);
        final String id = saved.getId();
        final SnykFixRequest toRun = effective;
        // autoConfirm=false (the wizard default) pauses at AWAITING_CONFIRM for the review step; true runs straight through.
        pool.submit(() -> run(id, toRun, Map.of(), Map.of(), !toRun.autoConfirm()));
        return id;
    }

    /** The Jira key to carry into a relaunch: the most-recent incomplete (CANCELLED/FAILED) prior train that has one,
     *  so cancel → relaunch reuses the same ticket + branch. Never a DONE train (its ticket is already closed). */
    private static String carryForwardJiraKey(List<SnykFixTrain> priors) {
        return priors.stream()
                .filter(t -> SnykFixStatus.CANCELLED.equals(t.getStatus()) || SnykFixStatus.FAILED.equals(t.getStatus()))
                .filter(t -> t.getJiraKey() != null && !t.getJiraKey().isBlank())
                .max(Comparator.comparing(SnykFixTrain::getStartedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(SnykFixTrain::getJiraKey)
                .orElse(null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Resume a paused (AWAITING_CONFIRM) train with the user's per-module version + per-step reviewer edits: re-clone,
     * re-plan with the overrides, then run the rest of the cascade. Rebuilds the request from the persisted train.
     */
    public void confirm(String trainId, Map<String, String> versionOverrides,
                        Map<Integer, List<String>> reviewerOverrides) {
        SnykFixTrain train = trains.findById(trainId)
                .orElseThrow(() -> new NotFoundException("Fix train not found: " + trainId));
        if (!SnykFixStatus.AWAITING_CONFIRM.equals(train.getStatus())) {
            throw new ConflictException(
                    "Fix train " + trainId + " is not awaiting confirmation (" + train.getStatus() + ").");
        }
        SnykFixRequest req = new SnykFixRequest(train.getWatchId(), train.getIssueId(), train.getCoordinate(),
                train.getOldVersion(), train.getFixedIn(), train.getSeverity(), splitAppIds(train.getAppIds()),
                train.getJiraKey(), train.getJiraProject(), train.getJiraIssueType(), List.of(), train.getOwner(), true,
                train.getStoryKey());
        train.setStatus(SnykFixStatus.PLANNING);
        train.setStartedAt(Instant.now());   // reset the runtime clock so the reconciler measures the execute phase
        trains.save(train);
        Map<String, String> vo = versionOverrides == null ? Map.of() : versionOverrides;
        Map<Integer, List<String>> ro = reviewerOverrides == null ? Map.of() : reviewerOverrides;
        pool.submit(() -> run(trainId, req, vo, ro, false));
    }

    private static List<String> splitAppIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }

    private void run(String trainId, SnykFixRequest req, Map<String, String> versionOverrides,
                     Map<Integer, List<String>> reviewerOverrides, boolean pauseForConfirm) {
        // Attach the train id to EVERY downstream log (clone / reactor / push / Jira) so a failure in the shared
        // "snyk-fix" pool can be tied back to its train + coordinate — otherwise concurrent bulk trains are
        // indistinguishable in the console. Requires [%X{trainId:-}] in the logback pattern.
        MDC.put("trainId", trainId);
        log.info("Snyk fix train {}: START {} {}->{} apps={} (confirm-pause={})",
                trainId, req.coordinate(), req.oldVersion(), req.fixedIn(), req.appIds(), pauseForConfirm);
        String stage = SnykFixStatus.PLANNING;
        Map<String, Path> clones = new LinkedHashMap<>();
        try {
            SnykFixTrain train = trains.findById(trainId).orElseThrow();
            String[] coord = req.coordinate().split(":", 2);
            String groupId = coord[0];
            String artifactId = coord.length > 1 ? coord[1] : coord[0];

            // 1) PLANNING — clone framework + consumers, read poms, plan the cascade, persist the steps.
            FrameworkPoms poms = cloneFramework(clones);
            List<AppInput> apps = cloneConsumers(req.appIds(), clones);
            List<CascadeStep> plan = planner.plan(groupId, artifactId, req.fixedIn(), poms, apps, versionOverrides);
            persistSteps(trainId, plan, clones, req.jiraKey(), req.reviewersOverride(), reviewerOverrides,
                    groupId, artifactId, req.fixedIn());

            // 1a) Nothing to push? Every step is manual (the framework already ships the safe version and no selected
            // app needs a bump). Decide HONESTLY instead of dead-ending at AWAITING_MANUAL_FIX or opening a change-less
            // PR: ALREADY_FIXED (benign terminal) when the BOM genuinely pins the coordinate at fixedIn; FAILED when
            // the fix couldn't be applied at all (nothing pins it here and no app has a local version to bump).
            String terminal = terminalOutcomeIfNothingActionable(steps.findByTrainIdOrderByStepOrder(trainId),
                    FixValidator.satisfies(poms.bom(), groupId, artifactId, req.fixedIn()));   // at-or-above, never downgrade
            if (terminal != null) {
                train.setStatus(terminal);
                train.setFinishedAt(Instant.now());
                if (SnykFixStatus.ALREADY_FIXED.equals(terminal)) {
                    train.setStageDetail("The framework already ships a safe version of " + req.coordinate() + " (at or "
                            + "above " + req.fixedIn() + ") and no selected app needs a bump — nothing to release; no "
                            + "PR opened (not downgraded).");
                } else {
                    train.setFailedStage(SnykFixStatus.PLANNING);
                    train.setErrorMessage("No automated fix could be applied for " + req.coordinate() + " → "
                            + req.fixedIn() + " — the BOM doesn't pin it here and no selected app has a local version "
                            + "to bump. Fix by hand (see the manual steps).");
                }
                trains.save(train);
                return;   // terminal — the finally cleans the clones
            }

            // 1b) Pause for the user to review the cascade (edit versions/reviewers) before anything runs.
            if (pauseForConfirm) {
                stage(train, SnykFixStatus.AWAITING_CONFIRM, "Review the cascade, then confirm to run it.");
                return;   // the finally cleans the clones; confirm() re-clones with the user's overrides
            }

            // 2) CHECKING — advisory breaking-change verdict (never gates on its own).
            // NOTE: every train save below reassigns `train` — the entity is detached between these calls, and a
            // discarded save() return would leave a stale @Version and self-inflict an optimistic-lock failure.
            stage = SnykFixStatus.CHECKING;
            train = stage(train, stage, "Assessing whether the upgrade is a breaking change…");
            BreakingVerdict verdict = breakingChange.judge(req.coordinate(), req.oldVersion(), req.fixedIn(),
                    usageSites(clones, artifactId), req.owner(), trainId);
            train.setVerdictJson(mapper.writeValueAsString(verdict));
            train.setBreaking(verdict.breaking());

            // 3) JIRA — create-or-use the ticket (carrying the facts) and move it to In Progress (before correcting).
            String jiraKey = jiraService.ensureTicket(train, req.jiraKey(), req.jiraProject(), req.jiraIssueType(), verdict);
            train.setJiraKey(jiraKey);
            train.setJiraProject(req.jiraProject());
            train.setJiraSummary(jiraService.summary(jiraKey));   // the ticket's name, surfaced in each PR body
            String jiraStatus = jiraService.transitionTo(jiraKey, SnykFixJiraService.Phase.IN_PROGRESS);
            if (jiraStatus != null) {
                train.setJiraStatus(jiraStatus);   // surface the live ticket status (In Progress) on the card
            }
            train = trains.save(train);

            // 4) VERIFYING — apply the edits to the clones, then the local reactor build (the real gate).
            // Reset the runtime clock here: the reactor (4x mvn install + N x mvn test) is by far the longest phase,
            // so the staleness reconciler should measure from its start, not from submit/clone/LLM time.
            stage = SnykFixStatus.VERIFYING;
            train.setStartedAt(Instant.now());
            train = stage(train, stage, "Building the upgraded framework + running each app's tests locally…");
            // Ask the AI advisor how to build & test each app (visible in the tracker), then run the reactor with the
            // per-app command so a consumer's own test-config quirk isn't mistaken for a breaking change.
            Map<String, String> appCommands = resolveConsumerBuildCommands(trainId, apps, clones, req.owner());
            train = trains.findById(trainId).orElseThrow();   // the advisor advanced stageDetail; resync the detached train
            ReactorResult reactor = runReactor(clones, apps, appCommands);
            train.setReactorPassed(reactor.passed());
            train.setReactorInconclusive(reactor.inconclusive());
            train.setReactorFailingLabel(reactor.failingLabel());
            train.setReactorOutputTail(reactor.outputTail());
            if (!reactor.passed()) {
                // Pinpoint the module that broke the build so the stepper can flag exactly that étape.
                train.setFailedStepOrder(
                        failedStepOrder(steps.findByTrainIdOrderByStepOrder(trainId), reactor.failingLabel()));
            }
            train = trains.save(train);

            // 4b) AI cross-check (advisory, NON-blocking): a plain-language read of what the BOM diff actually changed
            // and whether it fixes the vuln. The deterministic effective-version gate already validated the fix — this
            // is the explainable "here's what the AI sees" the user asked for, surfaced on the card. Never gates.
            train = recordFixDiffVerdict(train, poms.bom(), clones, req);

            // 5) PUSH — always push the version-bump branches (even on a breaking change).
            stage = SnykFixStatus.OPENING_PRS;
            train = stage(train, stage, "Pushing the version-bump branches…");
            pushBranches(trainId, clones, train.getJiraKey(), req.coordinate(), req.fixedIn());

            // 6) DECIDE — clean opens the PR train (+ Jira In Review); breaking holds the PRs and awaits the user.
            // Both outcomes (PR_OPEN / AWAITING_MANUAL_FIX) are NON-terminal human-wait states, so we do NOT stamp
            // finishedAt here — that is set only when the train is genuinely terminal (markMerged→DONE, or FAILED
            // in the catch). Stamping it now would let the retention sweeper prune a still-open fix.
            actions.decide(train, steps.findByTrainIdOrderByStepOrder(trainId));
        } catch (Exception e) {
            final String failedAt = stage;
            if (isConcurrentFinalize(e) && finalizedElsewhere(trainId)) {
                // Another writer (the reconciler, a restart recovery, or an app shutdown) has ALREADY finalized this
                // train (it is no longer machine-driven). That writer's state — with its own clear reason — wins; do
                // NOT clobber it with the cryptic "Row was updated or deleted by another transaction" this race
                // surfaces. We only take this branch when the train is genuinely finalized elsewhere, so a lock that
                // left the train still in-flight falls through to markFailed rather than stranding it as a spinner.
                log.warn("Snyk fix train {} was finalized by another writer during {} — leaving its state as-is.",
                        trainId, failedAt);
            } else {
                // Pass the throwable (last arg) so the full cause chain — the JGit TransportException, the Jira 400,
                // the reactor UncheckedIOException — prints, and add the coordinate so the line is self-explanatory.
                log.error("Snyk fix train {} ({} {}->{}, apps={}) FAILED at {}: {}", trainId, req.coordinate(),
                        req.oldVersion(), req.fixedIn(), req.appIds(), failedAt, e.getMessage(), e);
                // Never store a blank reason — several failures (NoSuchElement/NPE) carry a null message, which would
                // leave the UI with a red badge and no explanation.
                markFailed(trainId, failedAt, failureReason(e));
            }
        } finally {
            clones.values().forEach(workspace::cleanup);
            MDC.remove("trainId");
        }
    }

    private FrameworkPoms cloneFramework(Map<String, Path> clones) {
        String project = fw.getProject();
        // The framework repos are REQUIRED — a failed clone here (auth/connectivity/wrong slug) must fail the train
        // fast with a clear reason, not silently drop the module and later resurface as a misleading consumer-test
        // failure in the reactor.
        String bom = readPom(cloneRequired(project, fw.getBomRepo(), "BOM", clones));
        String core = readPom(cloneRequired(project, fw.getCoreRepo(), "core", clones));
        String api = readPom(cloneRequired(project, fw.getApiRepo(), "api", clones));
        String web = readPom(cloneRequired(project, fw.getWebRepo(), "web", clones));
        return new FrameworkPoms(bom, core, api, web);
    }

    /** Clone a REQUIRED framework repo — throw (fail the train at PLANNING) if it can't be cloned. */
    Path cloneRequired(String project, String repoSlug, String label, Map<String, Path> clones) {
        Path dir = clone(project, repoSlug, clones);
        if (dir == null) {
            throw new IllegalStateException("Couldn't clone the framework " + label + " repo " + project + "/"
                    + repoSlug + " — check repo connectivity/credentials (Settings → Bitbucket).");
        }
        return dir;
    }

    private List<AppInput> cloneConsumers(List<String> appIds, Map<String, Path> clones) {
        List<AppInput> apps = new ArrayList<>();
        if (appIds == null) {
            return apps;
        }
        for (String appId : appIds) {
            String pom = readPom(clone(appId, fw.getConsumerRepo(), clones));
            apps.add(new AppInput(appId, appId, pom));
        }
        return apps;
    }

    private Path clone(String project, String repoSlug, Map<String, Path> clones) {
        try {
            Path dir = workspace.resolve(project, repoSlug, fw.getBranch(), null);
            clones.put(project + "/" + repoSlug, dir);
            return dir;
        } catch (RuntimeException e) {
            log.warn("Could not clone {}/{} for the fix cascade: {}", project, repoSlug, e.getMessage());
            return null;
        }
    }

    private String readPom(Path repoDir) {
        if (repoDir == null) {
            return null;
        }
        try {
            Path pom = repoDir.resolve("pom.xml");
            return Files.exists(pom) ? Files.readString(pom) : null;
        } catch (IOException e) {
            return null;
        }
    }

    void persistSteps(String trainId, List<CascadeStep> plan, Map<String, Path> clones, String jiraKey,
                      List<String> reviewersOverride, Map<Integer, List<String>> reviewerOverrides,
                      String groupId, String artifactId, String fixedIn) {
        steps.deleteByTrainId(trainId);   // a confirm-time re-plan replaces the preview steps rather than duplicating
        for (CascadeStep cs : plan) {
            SnykFixStep s = new SnykFixStep();
            s.setTrainId(trainId);
            s.setStepOrder(cs.order());
            s.setBitbucketProject(cs.bitbucketProject());
            s.setRepoSlug(cs.repoSlug());
            s.setBranch(branchName(jiraKey, cs.bitbucketProject()));   // per-project feature branch (preview; re-set at push)
            s.setPomPath(cs.pomPath());
            s.setModuleLabel(cs.moduleLabel());
            s.setDiffPreview(cs.diffPreview());
            s.setNewModuleVersion(cs.newModuleVersion());
            s.setManual(cs.manual());
            s.setReason(cs.reason());
            s.setStatus(cs.manual() ? SnykFixStatus.MANUAL : SnykFixStatus.PLANNED);
            s.setReviewersJson(reviewersJson(cs, clones, reviewersOverride, reviewerOverrides));
            steps.save(s);
            if (!cs.manual()) {
                try {
                    applyStepEdits(cs, clones, groupId, artifactId, fixedIn);
                } catch (RuntimeException editFailed) {
                    // FAIL-FAST: never let a train proceed to the reactor/push with an edit that didn't apply. A clean
                    // build on an un-edited pom is a FALSE PASS — it would open a change-less PR and mark the vuln
                    // fixed while the branch still carries the old version. Mark the step, then abort the train.
                    s.setStatus(SnykFixStatus.STEP_FAILED);
                    s.setStageDetail(null);
                    s.setReason("Could not apply the version edit: " + editFailed.getMessage());
                    steps.save(s);
                    throw editFailed;
                }
            }
        }
    }

    /** Apply the planned version edits to the cloned pom (so the reactor build + push see the new versions). Throws
     *  on any failure — the caller aborts the train rather than shipping an unedited (no-op) "fix". */
    private void applyStepEdits(CascadeStep cs, Map<String, Path> clones, String groupId, String artifactId,
                               String fixedIn) {
        Path repoDir = clones.get(cs.bitbucketProject() + "/" + cs.repoSlug());
        if (repoDir == null) {
            throw new IllegalStateException("Repo " + cs.repoSlug() + " was not cloned — can't apply the version edit.");
        }
        String edited;
        Path pomPath;
        try {
            pomPath = repoDir.resolve(cs.pomPath());
            edited = CascadePlanner.applyEdits(Files.readString(pomPath), cs.edits());
        } catch (Exception e) {
            throw new IllegalStateException("Could not apply the version edit to " + cs.repoSlug()
                    + "/pom.xml: " + e.getMessage(), e);
        }
        assertVulnPinned(cs.moduleLabel(), edited, groupId, artifactId, fixedIn);   // throws → train FAILED, no push
        try {
            fileWriter.write(pomPath, cs.repoSlug() + "/pom.xml", edited);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the edited " + cs.repoSlug() + "/pom.xml: "
                    + e.getMessage(), e);
        }
    }

    /**
     * AUTHORITATIVE FIX GATE (BOM only): the BOM is the one place the cascade pins the vulnerable coordinate. After its
     * edits, the pom's EFFECTIVE managed version for the coordinate MUST equal {@code fixedIn} — otherwise the edit was
     * a no-op or hit the wrong target and only the release {@code <version>} moved (the change-less-"fix" bug). Throw so
     * the train fails rather than push + PR a "fix" that fixed nothing. No-op for non-BOM steps (they never pin the
     * coordinate; consumer effectiveness needs the resolved tree and is validated separately).
     */
    static void assertVulnPinned(String moduleLabel, String editedPom, String groupId, String artifactId, String fixedIn) {
        if ("BOM".equals(moduleLabel) && !FixValidator.managesAtVersion(editedPom, groupId, artifactId, fixedIn)) {
            String effective = FixValidator.effectiveVersion(editedPom, groupId, artifactId);
            throw new IllegalStateException("The fix did not raise " + groupId + ":" + artifactId + " to " + fixedIn
                    + " (effective version " + (effective == null ? "not managed here" : effective) + ") — the edit was "
                    + "a no-op or hit the wrong target; refusing to push a change-less fix.");
        }
    }

    /**
     * When NOTHING in the plan is actionable (every step is manual → nothing to push), the terminal outcome: {@code
     * ALREADY_FIXED} (benign — the framework already ships the safe version) when {@code bomAlreadyAtFixedIn}, else
     * {@code FAILED} (the fix genuinely couldn't be applied). {@code null} when there IS an actionable step (proceed).
     */
    static String terminalOutcomeIfNothingActionable(List<SnykFixStep> trainSteps, boolean bomAlreadyAtFixedIn) {
        if (trainSteps.stream().anyMatch(s -> !s.isManual())) {
            return null;
        }
        return bomAlreadyAtFixedIn ? SnykFixStatus.ALREADY_FIXED : SnykFixStatus.FAILED;
    }

    /** Reviewers for a step: the user's per-step edit wins, then a global override, then git-history suggestion. */
    private String reviewersJson(CascadeStep cs, Map<String, Path> clones, List<String> globalOverride,
                                 Map<Integer, List<String>> perStep) {
        try {
            List<String> stepOverride = perStep == null ? null : perStep.get(cs.order());
            List<String> reviewers;
            if (stepOverride != null && !stepOverride.isEmpty()) {
                reviewers = stepOverride;
            } else if (globalOverride != null && !globalOverride.isEmpty()) {
                reviewers = globalOverride;
            } else {
                Path dir = clones.get(cs.bitbucketProject() + "/" + cs.repoSlug());
                reviewers = dir == null ? List.of() : reviewerSuggester.suggest(dir, cs.pomPath(), 3);
            }
            return mapper.writeValueAsString(reviewers);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Ask the AI advisor how to build &amp; test each consumer app, surfacing the work AND the chosen command in the
     * live tracker (per the "every AI action is visible" principle). Returns appId → the guard-safe {@code mvn}
     * command. Loads + advances its own train copy for the stageDetail; the caller re-syncs afterwards.
     */
    Map<String, String> resolveConsumerBuildCommands(String trainId, List<AppInput> apps,
                                                     Map<String, Path> clones, String owner) {
        Map<String, String> commands = new LinkedHashMap<>();
        if (apps.isEmpty()) {
            return commands;
        }
        SnykFixTrain train = trains.findById(trainId).orElseThrow();
        for (AppInput app : apps) {
            Path dir = clones.get(app.appId() + "/" + fw.getConsumerRepo());
            if (dir == null) {
                continue;
            }
            train = stage(train, SnykFixStatus.VERIFYING, "Working out how to build & test " + app.appId() + "…");
            BuildCommandAdvisor.BuildCommand advised =
                    buildCommandAdvisor.resolve(app.appId(), fw.getConsumerRepo(), dir, owner, trainId);
            commands.put(app.appId(), advised.command());
            train = stage(train, SnykFixStatus.VERIFYING, buildCommandDetail(app.appId(), advised));
        }
        return commands;
    }

    /**
     * The honest live-tracker line for a resolved build command: a configured override (AI-derived with a note) reads
     * as an operator override; a genuine AI choice reads as "Will test …"; a degraded fallback reads as "AI couldn't
     * choose … using the default" with the reason — never presenting a silent degrade as an AI decision.
     */
    private static String buildCommandDetail(String appId, BuildCommandAdvisor.BuildCommand advised) {
        boolean hasNote = advised.note() != null && !advised.note().isBlank();
        if (advised.aiDerived()) {
            return "Will test " + appId + " with: " + advised.command()
                    + (hasNote ? " (" + advised.note() + ")" : "");
        }
        return "AI couldn't choose a build command for " + appId
                + (hasNote ? " (" + advised.note() + ")" : "")
                + " — using the default: " + advised.command();
    }

    private ReactorResult runReactor(Map<String, Path> clones, List<AppInput> apps, Map<String, String> appCommands) {
        Path localRepo;
        try {
            localRepo = Files.createTempDirectory("veritas-snyk-m2-");
        } catch (IOException e) {
            // Infrastructure failure (disk full / temp not writable) — NOT a breaking change. Throw so run() marks
            // the train FAILED at VERIFYING rather than mis-reporting a non-existent breaking change to the user.
            throw new UncheckedIOException("Could not create a temp local Maven repo for the reactor build", e);
        }
        try {
            List<ModuleBuild> framework = new ArrayList<>();
            addModule(framework, "BOM", clones.get(fw.getProject() + "/" + fw.getBomRepo()));
            addModule(framework, "core", clones.get(fw.getProject() + "/" + fw.getCoreRepo()));
            addModule(framework, "api", clones.get(fw.getProject() + "/" + fw.getApiRepo()));
            addModule(framework, "web", clones.get(fw.getProject() + "/" + fw.getWebRepo()));
            List<ConsumerBuild> consumers = new ArrayList<>();
            for (AppInput app : apps) {
                Path dir = clones.get(app.appId() + "/" + fw.getConsumerRepo());
                if (dir != null) {
                    consumers.add(new ConsumerBuild(app.appId(), dir, appCommands.get(app.appId())));
                }
            }
            return verifier.verify(new ReactorInputs(localRepo, framework, consumers));
        } finally {
            deleteRecursively(localRepo);   // the throwaway m2 repo is hundreds of MB — never leak it to temp
        }
    }

    /** Best-effort recursive delete of the throwaway local Maven repo. */
    private void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort — a locked pack file on Windows; the OS temp cleaner gets it eventually
                }
            });
        } catch (IOException e) {
            log.debug("Could not delete temp local repo {}: {}", root, e.getMessage());
        }
    }

    private void addModule(List<ModuleBuild> list, String label, Path dir) {
        if (dir != null) {
            list.add(new ModuleBuild(label, dir));
        }
    }

    void pushBranches(String trainId, Map<String, Path> clones, String jiraKey, String coordinate, String fixedIn) {
        int pushed = 0;
        int failed = 0;
        String commit = commitMessage(jiraKey, coordinate, fixedIn);   // same subject on every repo of this fix
        for (SnykFixStep s : steps.findByTrainIdOrderByStepOrder(trainId)) {
            if (s.isManual()) {
                continue;
            }
            Path repoDir = clones.get(s.getBitbucketProject() + "/" + s.getRepoSlug());
            if (repoDir == null) {
                continue;
            }
            // Authoritative branch from the FINAL Jira key (the ticket may have been created during the JIRA step, after
            // persistSteps set the preview) — per Bitbucket project so all its repos share one feature branch.
            String branch = branchName(jiraKey, s.getBitbucketProject());
            s.setBranch(branch);
            // Mark this module active BEFORE the push so a poll mid-loop shows the stepper advancing module by module.
            s.setStatus(SnykFixStatus.RUNNING);
            s.setStageDetail("Pushing " + s.getModuleLabel() + "…");
            steps.save(s);
            try {
                PrPublisher.PushOutcome outcome = prPublisher.pushBranch(repoDir, s.getRepoSlug(), branch, commit);
                s.setStatus(SnykFixStatus.BRANCH_PUSHED);
                // Record the concrete outcome so the stepper isn't blind: the commit sha (shown as a chip) and a
                // persistent, non-null success line naming the branch — on success, not just a reverted planned diff.
                s.setCommitSha(outcome == null ? null : outcome.commitSha());
                s.setStageDetail(pushedDetail(outcome, branch));
                s.setReason(null);   // clear any stale reason from a prior failed attempt on this step
                steps.save(s);
                pushed++;
            } catch (RuntimeException e) {
                // Context + full stack (last arg) so the console names the branch and carries the JGit/auth cause.
                log.warn("Snyk fix: push FAILED for step {} ({}) branch {}: {}",
                        s.getStepOrder(), s.getRepoSlug(), branch, e.getMessage(), e);
                s.setStatus(SnykFixStatus.STEP_FAILED);
                s.setStageDetail(null);
                s.setReason("push failed: " + e.getMessage());
                steps.save(s);
                failed++;
            }
        }
        if (failed > 0) {
            log.error("Snyk fix: {} of {} branch push(es) FAILED — the train will hold for manual action "
                    + "(check the Bitbucket access token's write scope in Settings).", failed, pushed + failed);
        } else {
            log.info("Snyk fix: pushed {} branch(es) cleanly.", pushed);
        }
    }

    /**
     * Record the advisory AI read of what the fix changed (old BOM pom vs the edited BOM pom on the clone). NON-blocking
     * and best-effort: a judge failure is swallowed so it can never stop a fix — the deterministic gate is what
     * validated it. Reassigns the saved train (the {@code @Version} discipline).
     */
    SnykFixTrain recordFixDiffVerdict(SnykFixTrain train, String oldBomPom, Map<String, Path> clones,
                                      SnykFixRequest req) {
        try {
            String newBomPom = readPom(clones.get(fw.getProject() + "/" + fw.getBomRepo()));
            FixDiffVerdict verdict = fixDiffValidator.explain(req.coordinate(), req.oldVersion(), req.fixedIn(),
                    oldBomPom, newBomPom, req.owner(), train.getId());
            train.setFixDiffJson(mapper.writeValueAsString(verdict));
        } catch (Exception e) {
            log.debug("Fix-diff verdict not recorded for train {}: {}", train.getId(), e.getMessage());
        }
        return trains.save(train);
    }

    /** A light usage-site hint for the LLM: which cloned poms reference the vulnerable artifact by name. */
    private String usageSites(Map<String, Path> clones, String artifactId) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Path> e : clones.entrySet()) {
            String pom = readPom(e.getValue());
            if (pom != null && pom.contains(artifactId)) {
                sb.append(e.getKey()).append(" references ").append(artifactId).append('\n');
            }
        }
        return sb.length() == 0 ? "(no direct references found in the framework/consumer poms)" : sb.toString();
    }

    /**
     * Persist a status/detail transition and RETURN the saved instance. run() reuses a single detached train across
     * many saves; on a {@code @Version} entity, {@code save()} (a JPA merge) increments the version on a fresh managed
     * copy and does NOT write it back to the passed detached instance — so the caller MUST reassign the return, or the
     * next save merges a stale version and throws "Row was updated or deleted by another transaction" with no
     * concurrent writer. Reassigning keeps the in-memory version in lockstep with the DB.
     */
    private SnykFixTrain stage(SnykFixTrain train, String status, String detail) {
        log.info("Snyk fix train {}: {} — {}", train.getId(), status, detail);
        train.setStatus(status);
        train.setStageDetail(detail);
        return trains.save(train);
    }

    /** Max commit-subject length — BNC's commit hook keeps subjects short; an overlong message is truncated to fit. */
    private static final int COMMIT_SUBJECT_MAX = 72;

    /**
     * The fix branch — BNC's "feature" type, off {@code develop}, named PER Bitbucket project with the Jira key:
     * {@code feature/<KEY>-snyk-fix-app-<n>} (e.g. {@code feature/LSIST-439-snyk-fix-app-7488}). Per-project (not per
     * repo), so every repo in one project shares a branch and each project gets its own. Matches the branching model so
     * the push is accepted; the key links it to the ticket dev panel.
     */
    static String branchName(String jiraKey, String bitbucketProject) {
        String proj = projectSlug(bitbucketProject);
        String key = safeKey(jiraKey);
        return key == null ? "feature/snyk-fix-" + proj : "feature/" + key + "-snyk-fix-" + proj;
    }

    /** A Bitbucket project key normalised for a branch name: {@code APP7488 -> app-7488}; anything else lowercased. */
    static String projectSlug(String project) {
        String p = project == null ? "" : project.trim().toLowerCase(Locale.ROOT);
        if (p.startsWith("app") && p.length() > 3 && p.substring(3).chars().allMatch(Character::isDigit)) {
            return "app-" + p.substring(3);
        }
        String cleaned = p.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return cleaned.isEmpty() ? "app" : cleaned;
    }

    /**
     * The commit subject — {@code <KEY>: <short>}, key-prefixed so Bitbucket links the commit to the ticket, kept short
     * for the BNC commit-length limit. Names the single fixed dependency by its artifactId, e.g.
     * {@code LSIST-439: Bump commons-lang3 to 3.18.0}.
     */
    static String commitMessage(String jiraKey, String coordinate, String fixedIn) {
        String artifact = coordinate == null || coordinate.isBlank()
                ? "the dependency" : coordinate.substring(coordinate.indexOf(':') + 1);
        String body = "Bump " + artifact + " to " + (fixedIn == null || fixedIn.isBlank() ? "the safe version" : fixedIn);
        String key = safeKey(jiraKey);
        String msg = key == null ? body : key + ": " + body;
        return msg.length() <= COMMIT_SUBJECT_MAX ? msg : msg.substring(0, COMMIT_SUBJECT_MAX - 3) + "...";
    }

    /**
     * A persistent, human success line for a pushed step — names the commit sha and the branch, and whether the branch
     * was created or updated (accumulated onto). Never null, so the stepper always SAYS what was done on success rather
     * than reverting to a blank/planned line. Defensive against a null outcome (never returned by the real publisher).
     */
    static String pushedDetail(PrPublisher.PushOutcome outcome, String branch) {
        if (outcome == null) {
            return "Branch " + branch + " pushed.";
        }
        String sha = outcome.commitSha();
        String shortSha = sha == null || sha.isBlank() ? "(no commit)" : sha.substring(0, Math.min(7, sha.length()));
        return "Committed " + shortSha + " to " + outcome.branch()
                + (outcome.created() ? " (new branch)." : " (updated existing branch).");
    }

    /** A Jira key only when it looks like one (PROJ-123); a blank/malformed value never lands in a git ref. */
    private static String safeKey(String jiraKey) {
        if (jiraKey == null) {
            return null;
        }
        String k = jiraKey.trim();
        return k.matches("[A-Z][A-Z0-9]*-[0-9]+") ? k : null;
    }

    /** A non-blank failure reason for the UI: the exception's message, or its class name when the message is
     *  null/blank (several failures — NoSuchElement/NPE — carry no message, which would leave a red badge unexplained). */
    static String failureReason(Throwable e) {
        return (e.getMessage() == null || e.getMessage().isBlank()) ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Mark a train FAILED after a real error — but only while this worker still owns it (a MACHINE_DRIVEN state).
     * If another writer already finalized it (FAILED) or handed it to a human (AWAITING_MANUAL_FIX / PR_OPEN), that
     * state wins. The save itself is guarded against the very optimistic-lock race this method exists to survive.
     */
    private void markFailed(String trainId, String failedAt, String message) {
        try {
            trains.findById(trainId).ifPresent(t -> {
                if (!SnykFixStatus.MACHINE_DRIVEN.contains(t.getStatus())) {
                    return;
                }
                t.setStatus(SnykFixStatus.FAILED);
                t.setFailedStage(failedAt);
                t.setErrorMessage(message);
                t.setFinishedAt(Instant.now());
                trains.save(t);
            });
        } catch (OptimisticLockingFailureException race) {
            log.warn("Snyk fix train {} FAILED-mark lost the write race (already finalized elsewhere).", trainId);
        }
    }

    /** True only when the train has ALREADY been finalized by another writer (no longer a machine-driven state, or
     *  gone). Used to distinguish a genuine "someone else won" race from a self-inflicted/spurious lock that left
     *  the train still in-flight — the latter must be marked FAILED, not left as a stuck spinner. */
    private boolean finalizedElsewhere(String trainId) {
        return trains.findById(trainId)
                .map(t -> !SnykFixStatus.MACHINE_DRIVEN.contains(t.getStatus()))
                .orElse(true);
    }

    /**
     * True when a throwable (or anything in its cause chain) is the optimistic-lock race that fires when another
     * writer finalizes a train row underneath this worker — a benign "someone else won", not a real fix failure.
     * Also matches the raw Hibernate {@code StaleObjectStateException} in case it surfaces untranslated.
     */
    static boolean isConcurrentFinalize(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OptimisticLockingFailureException
                    || "StaleObjectStateException".equals(t.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /** The step order of the module the reactor build failed on (a framework label or a consumer app-id), or null. */
    static Integer failedStepOrder(List<SnykFixStep> trainSteps, String failingLabel) {
        if (failingLabel == null || failingLabel.isBlank() || trainSteps == null) {
            return null;
        }
        for (SnykFixStep s : trainSteps) {
            String label = s.getModuleLabel();
            if (failingLabel.equals(label) || ("consumer:" + failingLabel).equals(label)) {
                return s.getStepOrder();
            }
        }
        return null;
    }
}
