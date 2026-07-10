package ca.bnc.qe.veritas.evolve;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.codegen.GeneratedFileWriter;
import ca.bnc.qe.veritas.codegen.PrPublisher;
import ca.bnc.qe.veritas.config.EvolveProperties;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import ca.bnc.qe.veritas.skill.ConflictException;
import ca.bnc.qe.veritas.skill.GateService;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the Engine-Evolution loop: turn field votes into classification proposals ({@link #refresh}), let a
 * maintainer override the suggestion with a reason ({@link #challenge}), then open the deterministic promotion PR
 * against Veritas's own repo ({@link #openPr}) — gated behind human approval ({@link GateService}), a git write
 * scope, and a configured target repo. The AI only suggests; the {@link SeverityCatalogEditor} renders the exact
 * one-{@code case} diff, so the change is bounded and lint-clean. Never auto-merges; a human merges, then
 * {@link #markMerged}. Mirrors the Snyk fix train.
 */
@Service
@Slf4j
public class EngineEvolutionService {

    static final String DIFF_ENGINE_PATH = "src/main/java/ca/bnc/qe/veritas/engine/diff/DiffEngine.java";
    private static final Set<String> ALLOWED_SEVERITY = Set.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO");

    private final ClassificationProposalService proposalService;
    private final ClassificationTrainRepository trains;
    private final SeverityCatalogEditor editor;
    private final WorkspaceService workspace;
    private final GeneratedFileWriter fileWriter;
    private final PrPublisher prPublisher;
    private final GateService gateService;
    private final Preflight preflight;
    private final EvolveProperties props;

    public EngineEvolutionService(ClassificationProposalService proposalService, ClassificationTrainRepository trains,
                                  SeverityCatalogEditor editor, WorkspaceService workspace,
                                  GeneratedFileWriter fileWriter, PrPublisher prPublisher, GateService gateService,
                                  Preflight preflight, EvolveProperties props) {
        this.proposalService = proposalService;
        this.trains = trains;
        this.editor = editor;
        this.workspace = workspace;
        this.fileWriter = fileWriter;
        this.prPublisher = prPublisher;
        this.gateService = gateService;
        this.preflight = preflight;
        this.props = props;
    }

    public List<ClassificationTrain> all() {
        return trains.findAllByOrderByCreatedAtDesc();
    }

    /** Recompute proposals from the field votes and upsert one open train per pending type. Returns all trains. */
    public List<ClassificationTrain> refresh(String owner) {
        for (ClassificationProposal p : proposalService.computeProposals(owner)) {
            ClassificationTrain existing = trains
                    .findFirstByFindingTypeOrderByCreatedAtDesc(p.findingType().name()).orElse(null);
            if (existing != null && isTerminal(existing.getStatus())) {
                continue;   // MERGED or DISMISSED — respect the maintainer's terminal decision; don't re-propose.
            }
            ClassificationTrain t = existing != null ? existing : new ClassificationTrain();
            boolean seedable = t.getStatus() == null || "PROPOSED".equals(t.getStatus());
            t.setFindingType(p.findingType().name());
            // Evidence always reflects the latest field votes.
            t.setVoteCount(p.voteCount());
            t.setDistinctServices(p.distinctServices());
            t.setVoteBreakdown(renderBreakdown(p.voteBreakdown()));
            // FREEZE the AI snapshot + the seeded decision once a maintainer has acted (CHALLENGED / PR_OPEN), so a
            // later refresh can't rewrite the suggestion they reviewed or silently drop their override in the PR body.
            if (seedable) {
                t.setAiSuggestedSeverity(p.suggestedSeverity().name());
                t.setAiSuggested(p.aiSuggested());
                t.setAiRationale(p.rationale());
                t.setFinalSeverity(p.suggestedSeverity().name());
                t.setStatus("PROPOSED");
            }
            if (t.getOwner() == null) {
                t.setOwner(owner);
            }
            trains.save(t);
        }
        return all();
    }

    /** A maintainer overrides the suggested severity (a required reason is captured for the PR audit trail). */
    public ClassificationTrain challenge(String trainId, String severity, String comment) {
        ClassificationTrain t = load(trainId);
        requireStatus(t, "override", "PROPOSED", "CHALLENGED");
        String sv = severity == null ? "" : severity.toUpperCase(Locale.ROOT);
        if (!ALLOWED_SEVERITY.contains(sv)) {
            throw new IllegalArgumentException("Unknown severity '" + severity + "'. Allowed: " + ALLOWED_SEVERITY);
        }
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("A comment explaining the override is required.");
        }
        t.setFinalSeverity(sv);
        t.setMaintainerComment(comment);
        t.setStatus("CHALLENGED");
        return trains.save(t);
    }

    /**
     * A maintainer decides a type should NOT be classified via the loop (weak / contentious / not-now signal).
     * Terminal: {@link #refresh} won't re-propose the type, so a dismissed proposal stays dismissed.
     */
    public ClassificationTrain dismiss(String trainId, String reason) {
        ClassificationTrain t = load(trainId);
        requireStatus(t, "dismiss", "PROPOSED", "CHALLENGED");
        if (reason != null && !reason.isBlank()) {
            t.setMaintainerComment(reason);
        }
        t.setStatus("DISMISSED");
        t.setFinishedAt(Instant.now());
        return trains.save(t);
    }

    /**
     * Open the deterministic promotion PR against Veritas's own repo — after human approval ({@link GateService}),
     * a git write scope, and a configured target repo. Clones, renders the one-{@code case} edit, pushes, cleans up.
     */
    public ClassificationTrain openPr(String trainId, String owner) {
        ClassificationTrain t = load(trainId);
        requireStatus(t, "open a PR for", "PROPOSED", "CHALLENGED");
        if (!props.repoConfigured()) {
            throw new PreconditionException("engine-evolution", List.of(
                    "Set veritas.evolve.repo-app-id and veritas.evolve.repo-slug (Veritas's own repo) before opening "
                            + "a classification PR. Proposals are collected and shown until then."));
        }
        GateService.Decision gate = gateService.await(t.getId(), "OPEN_CLASSIFICATION_PR", owner);
        t.setGateId(gate.gateId());
        if (!gate.approved()) {
            trains.save(t);
            throw new ConflictException("Classification PR for " + t.getFindingType()
                    + " is awaiting approval (gate " + gate.gateId() + "). Approve it, then open the PR.");
        }
        preflight.requireGitWriteScope();

        FindingType type = FindingType.valueOf(t.getFindingType());
        Severity severity = Severity.valueOf(t.getFinalSeverity());
        Path workingCopy = null;
        try {
            workingCopy = workspace.resolve(props.getRepoAppId(), props.getRepoSlug(), props.getTargetBranch(), null);
            String edited = editor.promote(Files.readString(workingCopy.resolve(DIFF_ENGINE_PATH)), type, severity);
            fileWriter.writeWithin(workingCopy, DIFF_ENGINE_PATH, edited);
            String title = "Engine Evolution: classify " + type.name() + " as " + severity.name();
            PrPublisher.PrResult result = prPublisher.publish(new PrPublisher.PrRequest(
                    workingCopy, props.getRepoSlug(), branchName(type, severity), props.getTargetBranch(),
                    title, prBody(t, type, severity), title));
            t.setBranch(result.branch());
            t.setPrUrl(result.prUrl());
            t.setAppId(props.getRepoAppId());
            t.setRepoSlug(props.getRepoSlug());
            t.setStatus("PR_OPEN");
            t.setFinishedAt(Instant.now());
            return trains.save(t);
        } catch (IOException e) {
            throw new IllegalStateException("Could not edit DiffEngine.java in the working copy: " + e.getMessage(), e);
        } finally {
            workspace.cleanup(workingCopy);
        }
    }

    /** The human merged the PR — close the loop (the learned classification is live after the next deploy). */
    public ClassificationTrain markMerged(String trainId) {
        ClassificationTrain t = load(trainId);
        requireStatus(t, "mark merged", "PR_OPEN");
        t.setStatus("MERGED");
        t.setFinishedAt(Instant.now());
        return trains.save(t);
    }

    private ClassificationTrain load(String id) {
        return trains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown classification train " + id));
    }

    /** Enforce the train lifecycle — a wrong-state transition is a 409, not a silent no-op or a duplicate PR. */
    private static void requireStatus(ClassificationTrain t, String action, String... allowed) {
        for (String s : allowed) {
            if (s.equals(t.getStatus())) {
                return;
            }
        }
        throw new ConflictException("Cannot " + action + " classification " + t.getFindingType() + " — it is "
                + t.getStatus() + " (allowed: " + String.join(" / ", allowed) + ").");
    }

    /** MERGED / DISMISSED are terminal — refresh must never resurrect or re-propose such a train. */
    private static boolean isTerminal(String status) {
        return "MERGED".equals(status) || "DISMISSED".equals(status);
    }

    private static String branchName(FindingType type, Severity severity) {
        return "veritas/classify-" + type.name().toLowerCase(Locale.ROOT).replace('_', '-')
                + "-" + severity.name().toLowerCase(Locale.ROOT);
    }

    private static String renderBreakdown(Map<Severity, Integer> breakdown) {
        return breakdown.entrySet().stream()
                .map(e -> e.getKey().name() + ":" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String prBody(ClassificationTrain t, FindingType type, Severity finalSev) {
        StringBuilder sb = new StringBuilder("## Engine Evolution — classify `").append(type.name()).append("`\n\n");
        sb.append("**Proposed severity:** ").append(finalSev.name()).append("\n\n### AI assessment (rubric-based)\n");
        sb.append(t.isAiSuggested()
                ? "The classifier applied the same consumer-impact rubric `DiffEngine.severityOf` uses and suggested **"
                        + t.getAiSuggestedSeverity() + "**.\n\n> " + nz(t.getAiRationale()) + "\n\n"
                : "The AI was offline; the suggestion defaulted to the field consensus (**"
                        + t.getAiSuggestedSeverity() + "**).\n\n");
        sb.append("### Field evidence\n").append(t.getVoteCount()).append(" human classification vote(s) across ")
                .append(t.getDistinctServices()).append(" service(s): ").append(nz(t.getVoteBreakdown())).append("\n\n");
        sb.append("### Maintainer decision\n");
        // Decided by whether the maintainer left an override reason (challenge), NOT by comparing severities — a
        // refresh can move the AI suggestion onto the final severity without erasing the human override.
        if (t.getMaintainerComment() != null && !t.getMaintainerComment().isBlank()) {
            sb.append("Overrode the AI's ").append(t.getAiSuggestedSeverity()).append(" → **").append(finalSev.name())
                    .append("**. Reason: ").append(t.getMaintainerComment()).append("\n\n");
        } else {
            sb.append("Accepted the suggestion.\n\n");
        }
        sb.append("### Change\n- `DiffEngine.severityOf`: add `case ").append(type.name()).append(" -> Severity.")
                .append(finalSev.name()).append(";`\n- Remove `").append(type.name())
                .append("` from `PENDING_CLASSIFICATION`.\n\n");
        sb.append("Breaking-ness stays type-derived (`DiffEngine.isBreaking`) — this severity change can never hide a "
                + "consumer-breaking change.\n\n_Opened by Veritas Engine Evolution; review before merge._");
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
