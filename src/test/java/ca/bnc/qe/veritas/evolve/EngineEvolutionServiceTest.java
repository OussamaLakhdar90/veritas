package ca.bnc.qe.veritas.evolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class EngineEvolutionServiceTest {

    private final ClassificationProposalService proposalService = mock(ClassificationProposalService.class);
    private final ClassificationTrainRepository trains = mock(ClassificationTrainRepository.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final PrPublisher prPublisher = mock(PrPublisher.class);
    private final GateService gateService = mock(GateService.class);
    private final Preflight preflight = mock(Preflight.class);
    private final EvolveProperties props = new EvolveProperties();
    // Real editor + file writer so the actual deterministic edit is exercised end-to-end.
    private final EngineEvolutionService service = new EngineEvolutionService(proposalService, trains,
            new SeverityCatalogEditor(), workspace, new GeneratedFileWriter(new ObjectMapper()), prPublisher,
            gateService, preflight, props);

    private static ClassificationTrain train(String id, String type, String finalSeverity) {
        ClassificationTrain t = new ClassificationTrain();
        t.setId(id);
        t.setFindingType(type);
        t.setFinalSeverity(finalSeverity);
        t.setStatus("PROPOSED");
        return t;
    }

    @Test
    void refreshCreatesAProposedTrainFromTheFieldVotes() {
        when(proposalService.computeProposals("alice")).thenReturn(List.of(new ClassificationProposal(
                FindingType.STATUS_CODE_MISSING, Severity.MAJOR, true, "rubric says MAJOR", 5, 2,
                Map.of(Severity.MAJOR, 4, Severity.CRITICAL, 1))));
        when(trains.findFirstByFindingTypeOrderByCreatedAtDesc("STATUS_CODE_MISSING")).thenReturn(Optional.empty());
        when(trains.save(any())).thenAnswer(i -> i.getArgument(0));
        when(trains.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        service.refresh("alice");

        ArgumentCaptor<ClassificationTrain> cap = ArgumentCaptor.forClass(ClassificationTrain.class);
        verify(trains).save(cap.capture());
        ClassificationTrain saved = cap.getValue();
        assertThat(saved.getFindingType()).isEqualTo("STATUS_CODE_MISSING");
        assertThat(saved.getAiSuggestedSeverity()).isEqualTo("MAJOR");
        assertThat(saved.getFinalSeverity()).isEqualTo("MAJOR");
        assertThat(saved.getStatus()).isEqualTo("PROPOSED");
        assertThat(saved.getVoteBreakdown()).contains("MAJOR:4");
    }

    @Test
    void challengeOverridesTheSeverityWithARequiredComment() {
        ClassificationTrain t = train("t1", "STATUS_CODE_MISSING", "MAJOR");
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(trains.save(any())).thenAnswer(i -> i.getArgument(0));

        ClassificationTrain out = service.challenge("t1", "critical", "It breaks a running consumer.");

        assertThat(out.getFinalSeverity()).isEqualTo("CRITICAL");
        assertThat(out.getMaintainerComment()).isEqualTo("It breaks a running consumer.");
        assertThat(out.getStatus()).isEqualTo("CHALLENGED");
    }

    @Test
    void challengeRejectsABadSeverityOrAnEmptyComment() {
        when(trains.findById("t1")).thenReturn(Optional.of(train("t1", "STATUS_CODE_MISSING", "MAJOR")));
        assertThatThrownBy(() -> service.challenge("t1", "BOGUS", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.challenge("t1", "MAJOR", "  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openPrIsDisabledUntilTheTargetRepoIsConfigured() {
        when(trains.findById("t1")).thenReturn(Optional.of(train("t1", "STATUS_CODE_MISSING", "MAJOR")));
        assertThatThrownBy(() -> service.openPr("t1", "alice"))
                .isInstanceOf(PreconditionException.class);
    }

    @Test
    void openPrHoldsWhenTheApprovalGateIsPending() {
        props.setRepoAppId("app");
        props.setRepoSlug("veritas");
        when(trains.findById("t1")).thenReturn(Optional.of(train("t1", "STATUS_CODE_MISSING", "MAJOR")));
        when(trains.save(any())).thenAnswer(i -> i.getArgument(0));
        when(gateService.await("t1", "OPEN_CLASSIFICATION_PR", "alice"))
                .thenReturn(new GateService.Decision(false, "gate-1", "PENDING"));

        assertThatThrownBy(() -> service.openPr("t1", "alice")).isInstanceOf(ConflictException.class);
    }

    @Test
    void openPrRendersTheDiffAndPublishesWithTheAuditTrail(@TempDir Path tmp) throws Exception {
        Path diffEngine = tmp.resolve(EngineEvolutionService.DIFF_ENGINE_PATH);
        Files.createDirectories(diffEngine.getParent());
        Files.writeString(diffEngine, """
                package ca.bnc.qe.veritas.engine.diff;
                class DiffEngine {
                    static final Set<FindingType> PENDING_CLASSIFICATION = Set.of(FindingType.STATUS_CODE_MISSING);
                    static Severity severityOf(FindingType t) {
                        return switch (t) {
                            case MISSING_ENDPOINT -> Severity.CRITICAL;
                            default -> Severity.UNSPECIFIED;
                        };
                    }
                }
                """);
        props.setRepoAppId("app");
        props.setRepoSlug("veritas");
        ClassificationTrain t = train("t1", "STATUS_CODE_MISSING", "MAJOR");
        t.setAiSuggestedSeverity("MINOR");   // AI suggested MINOR; the maintainer overrode to MAJOR
        t.setAiSuggested(true);
        t.setMaintainerComment("It breaks a running consumer.");
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(trains.save(any())).thenAnswer(i -> i.getArgument(0));
        when(gateService.await("t1", "OPEN_CLASSIFICATION_PR", "alice"))
                .thenReturn(new GateService.Decision(true, "gate-1", "APPROVED"));
        when(workspace.resolve("app", "veritas", "main", null)).thenReturn(tmp);
        when(prPublisher.publish(any())).thenReturn(new PrPublisher.PrResult("branch-x", "https://host/pr/9"));

        ClassificationTrain out = service.openPr("t1", "alice");

        assertThat(out.getStatus()).isEqualTo("PR_OPEN");
        assertThat(out.getPrUrl()).isEqualTo("https://host/pr/9");
        // The working copy's DiffEngine.java was actually edited by the deterministic renderer.
        assertThat(Files.readString(diffEngine)).contains("case STATUS_CODE_MISSING -> Severity.MAJOR;");
        // The PR body carries the full audit trail: AI suggestion + the maintainer's override + reason.
        ArgumentCaptor<PrPublisher.PrRequest> pr = ArgumentCaptor.forClass(PrPublisher.PrRequest.class);
        verify(prPublisher).publish(pr.capture());
        assertThat(pr.getValue().description())
                .contains("classify `STATUS_CODE_MISSING`")
                .contains("Overrode the AI's MINOR")
                .contains("It breaks a running consumer.");
        verify(workspace).cleanup(tmp);
    }

    @Test
    void markMergedClosesTheLoop() {
        ClassificationTrain t = train("t1", "STATUS_CODE_MISSING", "MAJOR");
        t.setStatus("PR_OPEN");
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(trains.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.markMerged("t1").getStatus()).isEqualTo("MERGED");
    }

    @Test
    void rejectsWrongStateTransitions() {
        // mark-merged on a train that never opened a PR, challenge on a MERGED train, and open-PR on an
        // already-open train are all 409s (ConflictException) — not silent no-ops or duplicate PRs.
        when(trains.findById("p")).thenReturn(Optional.of(train("p", "STATUS_CODE_MISSING", "MAJOR")));   // PROPOSED
        assertThatThrownBy(() -> service.markMerged("p")).isInstanceOf(ConflictException.class);

        ClassificationTrain merged = train("m", "STATUS_CODE_MISSING", "MAJOR");
        merged.setStatus("MERGED");
        when(trains.findById("m")).thenReturn(Optional.of(merged));
        assertThatThrownBy(() -> service.challenge("m", "CRITICAL", "reason")).isInstanceOf(ConflictException.class);

        props.setRepoAppId("app");
        props.setRepoSlug("veritas");
        ClassificationTrain open = train("o", "STATUS_CODE_MISSING", "MAJOR");
        open.setStatus("PR_OPEN");
        when(trains.findById("o")).thenReturn(Optional.of(open));
        assertThatThrownBy(() -> service.openPr("o", "alice")).isInstanceOf(ConflictException.class);
    }

    @Test
    void dismissIsTerminalAndRejectsAWrongState() {
        when(trains.findById("t1")).thenReturn(Optional.of(train("t1", "STATUS_CODE_MISSING", "MAJOR")));
        when(trains.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.dismiss("t1", "too contentious to classify yet").getStatus()).isEqualTo("DISMISSED");

        ClassificationTrain merged = train("m", "STATUS_CODE_MISSING", "MAJOR");
        merged.setStatus("MERGED");
        when(trains.findById("m")).thenReturn(Optional.of(merged));
        assertThatThrownBy(() -> service.dismiss("m", "x")).isInstanceOf(ConflictException.class);
    }

    @Test
    void refreshDoesNotResurrectATerminalTrain() {
        when(proposalService.computeProposals("alice")).thenReturn(List.of(new ClassificationProposal(
                FindingType.STATUS_CODE_MISSING, Severity.MAJOR, true, "r", 5, 2, Map.of(Severity.MAJOR, 5))));
        ClassificationTrain dismissed = train("d", "STATUS_CODE_MISSING", "MAJOR");
        dismissed.setStatus("DISMISSED");
        when(trains.findFirstByFindingTypeOrderByCreatedAtDesc("STATUS_CODE_MISSING")).thenReturn(Optional.of(dismissed));
        when(trains.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(dismissed));

        service.refresh("alice");
        verify(trains, never()).save(any());   // a dismissed type is not re-proposed
    }

    /** A minimal DiffEngine.java shape the deterministic editor can promote against (its two anchors + allowlist). */
    private static final String SAMPLE_DIFF_ENGINE = """
            package ca.bnc.qe.veritas.engine.diff;
            class DiffEngine {
                static final Set<FindingType> PENDING_CLASSIFICATION = Set.of(FindingType.STATUS_CODE_MISSING);
                static Severity severityOf(FindingType t) {
                    return switch (t) {
                        case MISSING_ENDPOINT -> Severity.CRITICAL;
                        default -> Severity.UNSPECIFIED;
                    };
                }
            }
            """;

    private void enableDryRun(Path sourceRoot, Path outputRoot) {
        props.getDryRun().setEnabled(true);
        props.getDryRun().setSourceDir(sourceRoot.toString());
        props.getDryRun().setOutputDir(outputRoot.toString());
    }

    @Test
    void dryRunIsRefusedWhenTheDebugFlagIsOff() {
        // enabled defaults to false → refused before any repository / clone / PR interaction.
        assertThatThrownBy(() -> service.dryRunPromote("t1")).isInstanceOf(PreconditionException.class);
        verifyNoInteractions(trains, workspace, prPublisher, gateService);
    }

    @Test
    void dryRunRendersTheEditAndManifestFromALocalCheckoutWithoutCloneOrPr(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("checkout");
        Path diffEngine = src.resolve(EngineEvolutionService.DIFF_ENGINE_PATH);
        Files.createDirectories(diffEngine.getParent());
        Files.writeString(diffEngine, SAMPLE_DIFF_ENGINE);
        Path out = tmp.resolve("out");
        enableDryRun(src, out);
        ClassificationTrain t = train("t1", "STATUS_CODE_MISSING", "MAJOR");
        t.setAiSuggestedSeverity("MAJOR");
        t.setAiSuggested(true);
        when(trains.findById("t1")).thenReturn(Optional.of(t));

        DryRunPreview preview = service.dryRunPromote("t1");

        // The REAL deterministic edit was written to the review folder…
        assertThat(Files.readString(Path.of(preview.editedFilePath())))
                .contains("case STATUS_CODE_MISSING -> Severity.MAJOR;");
        // …the local source checkout was NOT modified (read-only)…
        assertThat(Files.readString(diffEngine)).doesNotContain("case STATUS_CODE_MISSING");
        // …the manifest carries the type, severity, and the mocked PR…
        assertThat(Files.readString(Path.of(preview.manifestPath())))
                .contains("DRY RUN").contains("STATUS_CODE_MISSING").contains("MAJOR")
                .contains("Would-be PR");
        assertThat(preview.mockBranch()).isEqualTo("veritas/classify-status-code-missing-major");
        // …and NOTHING outward ran: no clone, gate, write-scope, or PR, and the train stays PROPOSED (not opened).
        verifyNoInteractions(workspace, prPublisher, gateService, preflight);
        assertThat(t.getStatus()).isEqualTo("PROPOSED");
    }

    @Test
    void dryRunRequiresASourceDir(@TempDir Path tmp) {
        props.getDryRun().setEnabled(true);   // enabled, but source-dir left blank
        ClassificationTrain t = train("t1", "STATUS_CODE_MISSING", "MAJOR");
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.dryRunPromote("t1")).isInstanceOf(PreconditionException.class);
        verifyNoInteractions(workspace, prPublisher);
    }

    @Test
    void dryRunFailsClearlyWhenDiffEngineIsMissing(@TempDir Path tmp) {
        enableDryRun(tmp, tmp.resolve("out"));   // source dir exists but has no DiffEngine.java
        ClassificationTrain t = train("t1", "STATUS_CODE_MISSING", "MAJOR");
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.dryRunPromote("t1")).isInstanceOf(PreconditionException.class);
    }

    @Test
    void dryRunRejectsATerminalState(@TempDir Path tmp) {
        enableDryRun(tmp, tmp.resolve("out"));
        ClassificationTrain t = train("t1", "STATUS_CODE_MISSING", "MAJOR");
        t.setStatus("MERGED");   // a merged proposal can't be dry-run
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.dryRunPromote("t1")).isInstanceOf(ConflictException.class);
    }
}
