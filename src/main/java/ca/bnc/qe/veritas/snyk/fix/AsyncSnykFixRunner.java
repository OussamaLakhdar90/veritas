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
                              BreakingChangeService breakingChange, SnykFixJiraService jiraService,
                              SnykFixActions actions, ReviewerSuggester reviewerSuggester,
                              GeneratedFileWriter fileWriter, PrPublisher prPublisher, FrameworkProperties fw,
                              SnykFixTrainRepository trains, SnykFixStepRepository steps, ObjectMapper mapper) {
        this.workspace = workspace;
        this.planner = planner;
        this.verifier = verifier;
        this.breakingChange = breakingChange;
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
        // Idempotency: if a fix is already in flight for this watch + coordinate, reuse it rather than starting a
        // duplicate train that would clone/push the same branches (a poll re-alert or a double click can trigger this).
        if (req.watchId() != null && req.coordinate() != null) {
            for (SnykFixTrain existing : trains.findByWatchIdAndCoordinate(req.watchId(), req.coordinate())) {
                if (SnykFixStatus.NON_TERMINAL.contains(existing.getStatus())) {
                    log.info("Snyk fix already in flight for {} on watch {} — reusing train {}",
                            req.coordinate(), req.watchId(), existing.getId());
                    return existing.getId();
                }
            }
        }
        SnykFixTrain train = new SnykFixTrain();
        train.setWatchId(req.watchId());
        train.setIssueId(req.issueId());
        train.setCoordinate(req.coordinate());
        train.setOldVersion(req.oldVersion());
        train.setFixedIn(req.fixedIn());
        train.setSeverity(req.severity());
        train.setAppIds(req.appIds() == null ? "" : String.join(",", req.appIds()));
        train.setJiraKey(req.jiraKey());            // requested key (may be null) — kept so confirm can rebuild
        train.setJiraProject(req.jiraProject());
        train.setJiraIssueType(req.jiraIssueType());
        train.setOwner(req.owner());
        train.setStatus(SnykFixStatus.PLANNING);
        train.setStartedAt(Instant.now());
        SnykFixTrain saved = trains.save(train);
        final String id = saved.getId();
        // autoConfirm=false (the wizard default) pauses at AWAITING_CONFIRM for the review step; true runs straight through.
        pool.submit(() -> run(id, req, Map.of(), Map.of(), !req.autoConfirm()));
        return id;
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
                train.getJiraKey(), train.getJiraProject(), train.getJiraIssueType(), List.of(), train.getOwner(), true);
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
        String stage = SnykFixStatus.PLANNING;
        Map<String, Path> clones = new LinkedHashMap<>();
        try {
            SnykFixTrain train = trains.findById(trainId).orElseThrow();
            String[] coord = req.coordinate().split(":", 2);
            String groupId = coord[0];
            String artifactId = coord.length > 1 ? coord[1] : coord[0];
            String branch = branchName(trainId, req.jiraKey());

            // 1) PLANNING — clone framework + consumers, read poms, plan the cascade, persist the steps.
            FrameworkPoms poms = cloneFramework(clones);
            List<AppInput> apps = cloneConsumers(req.appIds(), clones);
            List<CascadeStep> plan = planner.plan(groupId, artifactId, req.fixedIn(), poms, apps, versionOverrides);
            persistSteps(trainId, plan, clones, branch, req.reviewersOverride(), reviewerOverrides);

            // 1b) Pause for the user to review the cascade (edit versions/reviewers) before anything runs.
            if (pauseForConfirm) {
                stage(train, SnykFixStatus.AWAITING_CONFIRM, "Review the cascade, then confirm to run it.");
                return;   // the finally cleans the clones; confirm() re-clones with the user's overrides
            }

            // 2) CHECKING — advisory breaking-change verdict (never gates on its own).
            stage = SnykFixStatus.CHECKING;
            stage(train, stage, "Assessing whether the upgrade is a breaking change…");
            BreakingVerdict verdict = breakingChange.judge(req.coordinate(), req.oldVersion(), req.fixedIn(),
                    usageSites(clones, artifactId), req.owner(), trainId);
            train.setVerdictJson(mapper.writeValueAsString(verdict));
            train.setBreaking(verdict.breaking());

            // 3) JIRA — create-or-use the ticket (carrying the facts) and move it to In Progress (before correcting).
            String jiraKey = jiraService.ensureTicket(train, req.jiraKey(), req.jiraProject(), req.jiraIssueType(), verdict);
            train.setJiraKey(jiraKey);
            train.setJiraProject(req.jiraProject());
            train.setJiraSummary(jiraService.summary(jiraKey));   // the ticket's name, surfaced in each PR body
            trains.save(train);
            jiraService.transitionTo(jiraKey, SnykFixJiraService.Phase.IN_PROGRESS);

            // 4) VERIFYING — apply the edits to the clones, then the local reactor build (the real gate).
            // Reset the runtime clock here: the reactor (4x mvn install + N x mvn test) is by far the longest phase,
            // so the staleness reconciler should measure from its start, not from submit/clone/LLM time.
            stage = SnykFixStatus.VERIFYING;
            train.setStartedAt(Instant.now());
            stage(train, stage, "Building the upgraded framework + running each app's tests locally…");
            ReactorResult reactor = runReactor(clones, apps);
            train.setReactorPassed(reactor.passed());
            train.setReactorFailingLabel(reactor.failingLabel());
            train.setReactorOutputTail(reactor.outputTail());
            if (!reactor.passed()) {
                // Pinpoint the module that broke the build so the stepper can flag exactly that étape.
                train.setFailedStepOrder(
                        failedStepOrder(steps.findByTrainIdOrderByStepOrder(trainId), reactor.failingLabel()));
            }
            trains.save(train);

            // 5) PUSH — always push the version-bump branches (even on a breaking change).
            stage = SnykFixStatus.OPENING_PRS;
            stage(train, stage, "Pushing the version-bump branches…");
            pushBranches(trainId, clones, branch, train.getJiraKey());

            // 6) DECIDE — clean opens the PR train (+ Jira In Review); breaking holds the PRs and awaits the user.
            // Both outcomes (PR_OPEN / AWAITING_MANUAL_FIX) are NON-terminal human-wait states, so we do NOT stamp
            // finishedAt here — that is set only when the train is genuinely terminal (markMerged→DONE, or FAILED
            // in the catch). Stamping it now would let the retention sweeper prune a still-open fix.
            actions.decide(train, steps.findByTrainIdOrderByStepOrder(trainId));
        } catch (Exception e) {
            final String failedAt = stage;
            log.error("Snyk fix train {} failed at {}: {}", trainId, failedAt, e.getMessage());
            trains.findById(trainId).ifPresent(t -> {
                t.setStatus(SnykFixStatus.FAILED);
                t.setFailedStage(failedAt);
                t.setErrorMessage(e.getMessage());
                t.setFinishedAt(Instant.now());
                trains.save(t);
            });
        } finally {
            clones.values().forEach(workspace::cleanup);
        }
    }

    private FrameworkPoms cloneFramework(Map<String, Path> clones) {
        String project = fw.getProject();
        String bom = readPom(clone(project, fw.getBomRepo(), clones));
        String core = readPom(clone(project, fw.getCoreRepo(), clones));
        String api = readPom(clone(project, fw.getApiRepo(), clones));
        String web = readPom(clone(project, fw.getWebRepo(), clones));
        return new FrameworkPoms(bom, core, api, web);
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

    private void persistSteps(String trainId, List<CascadeStep> plan, Map<String, Path> clones, String branch,
                              List<String> reviewersOverride, Map<Integer, List<String>> reviewerOverrides) {
        steps.deleteByTrainId(trainId);   // a confirm-time re-plan replaces the preview steps rather than duplicating
        for (CascadeStep cs : plan) {
            SnykFixStep s = new SnykFixStep();
            s.setTrainId(trainId);
            s.setStepOrder(cs.order());
            s.setBitbucketProject(cs.bitbucketProject());
            s.setRepoSlug(cs.repoSlug());
            s.setBranch(branch);
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
                applyStepEdits(cs, clones);
            }
        }
    }

    /** Apply the planned version edits to the cloned pom (so the reactor build + push see the new versions). */
    private void applyStepEdits(CascadeStep cs, Map<String, Path> clones) {
        Path repoDir = clones.get(cs.bitbucketProject() + "/" + cs.repoSlug());
        if (repoDir == null) {
            return;
        }
        try {
            Path pomPath = repoDir.resolve(cs.pomPath());
            String edited = CascadePlanner.applyEdits(Files.readString(pomPath), cs.edits());
            fileWriter.write(pomPath, cs.repoSlug() + "/pom.xml", edited);
        } catch (Exception e) {
            log.warn("Applying edits to {} failed: {}", cs.repoSlug(), e.getMessage());
        }
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

    private ReactorResult runReactor(Map<String, Path> clones, List<AppInput> apps) {
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
                    consumers.add(new ConsumerBuild(app.appId(), dir));
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

    void pushBranches(String trainId, Map<String, Path> clones, String branch, String jiraKey) {
        for (SnykFixStep s : steps.findByTrainIdOrderByStepOrder(trainId)) {
            if (s.isManual()) {
                continue;
            }
            Path repoDir = clones.get(s.getBitbucketProject() + "/" + s.getRepoSlug());
            if (repoDir == null) {
                continue;
            }
            // Mark this module active BEFORE the push so a poll mid-loop shows the stepper advancing module by module.
            s.setStatus(SnykFixStatus.RUNNING);
            s.setStageDetail("Pushing " + s.getModuleLabel() + "…");
            steps.save(s);
            try {
                prPublisher.pushBranch(repoDir, s.getRepoSlug(), branch,
                        commitMessage(jiraKey, s.getModuleLabel(), s.getDiffPreview()));
                s.setStatus(SnykFixStatus.BRANCH_PUSHED);
                s.setStageDetail(null);
                steps.save(s);
            } catch (RuntimeException e) {
                log.warn("Pushing the fix branch for {} failed: {}", s.getRepoSlug(), e.getMessage());
                s.setStatus(SnykFixStatus.STEP_FAILED);
                s.setStageDetail(null);
                s.setReason("push failed: " + e.getMessage());
                steps.save(s);
            }
        }
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

    private void stage(SnykFixTrain train, String status, String detail) {
        train.setStatus(status);
        train.setStageDetail(detail);
        trains.save(train);
    }

    /** The fix branch, embedding the Jira key when it's a real key so the branch links to the ticket's dev panel. */
    static String branchName(String trainId, String jiraKey) {
        String suffix = "snyk-fix-" + trainId.substring(0, Math.min(8, trainId.length()));
        String key = safeKey(jiraKey);
        return key == null ? "veritas/" + suffix : "veritas/" + key + "/" + suffix;
    }

    /** The per-module commit, prefixed with the Jira key when known so Bitbucket links the commit to the ticket. */
    static String commitMessage(String jiraKey, String moduleLabel, String diffPreview) {
        String base = "Snyk fix: " + moduleLabel + " — " + diffPreview;
        String key = safeKey(jiraKey);
        return key == null ? base : key + " " + base;
    }

    /** A Jira key only when it looks like one (PROJ-123); a blank/malformed value never lands in a git ref. */
    private static String safeKey(String jiraKey) {
        if (jiraKey == null) {
            return null;
        }
        String k = jiraKey.trim();
        return k.matches("[A-Z][A-Z0-9]*-[0-9]+") ? k : null;
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
