package ca.bnc.qe.veritas.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.persistence.DefectLink;
import ca.bnc.qe.veritas.persistence.DefectLinkRepository;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.skill.GateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pure-Mockito branch coverage for {@link DefectService} (reserve-then-write idempotency, the unique-constraint
 * race re-read, the attach-YAML non-fatal path, browseUrl edge cases, gate rejection) plus the wiki/plain
 * rendering branches of {@link DefectComposer}. A NEW class — does not touch the existing Spring-based tests.
 */
@ExtendWith(MockitoExtension.class)
class DefectServicesCoverageTest {

    @Mock private FindingRecordRepository findingRepository;
    @Mock private ScanRepository scanRepository;
    @Mock private DefectLinkRepository defectRepository;
    @Mock private JiraClient jira;
    @Mock private GateService gateService;
    @Mock private Preflight preflight;

    private ConnectionsProperties connections;
    private DefectComposer composer;
    private DefectService service;

    @BeforeEach
    void setUp() {
        connections = new ConnectionsProperties();
        connections.getJira().setBaseUrl("https://jira.bnc.ca");
        composer = new DefectComposer();   // Cloud-style plain composer (deterministic title/labels)
        service = new DefectService(findingRepository, scanRepository, defectRepository, composer,
                jira, connections, gateService, preflight);
    }

    private FindingRecord finding(String id, String scanId) {
        FindingRecord f = new FindingRecord();
        f.setId(id);
        f.setScanId(scanId);
        f.setType("MISSING_ENDPOINT");
        f.setSeverity("CRITICAL");
        f.setLayer("L2");
        f.setEndpoint("POST /v1/transfers");
        f.setStatus("OPEN");
        return f;
    }

    private void approveGate() {
        when(gateService.await(any(), eq("CREATE_DEFECT"), any()))
                .thenReturn(new GateService.Decision(true, "gate-1", "APPROVED"));
    }

    // ---- idempotency guard: an already-created link short-circuits before the gate ----

    @Test
    void returnsExistingLinkWhenAlreadyCreatedInJira() {
        DefectLink existing = new DefectLink();
        existing.setId("link-existing");
        existing.setFindingId("f1");
        existing.setJiraKey("CIAM-100");
        existing.setCreatedInJira(true);
        when(defectRepository.findByFindingId("f1")).thenReturn(Optional.of(existing));

        DefectLink result = service.createDefect("f1", "CIAM", "Bug", "tester");

        assertThat(result).isSameAs(existing);
        assertThat(result.getJiraKey()).isEqualTo("CIAM-100");
        verify(preflight).createDefect("f1", "CIAM");
        verify(gateService, never()).await(any(), any(), any());
        verify(jira, never()).createIssue(any());
        verify(defectRepository, never()).save(any());
    }

    // ---- gate not approved → IllegalStateException carrying the gate id, nothing written ----

    @Test
    void throwsWhenGateNotApproved() {
        when(defectRepository.findByFindingId("f1")).thenReturn(Optional.empty());
        when(gateService.await(eq("f1"), eq("CREATE_DEFECT"), eq("tester")))
                .thenReturn(new GateService.Decision(false, "gate-9", "PENDING"));

        assertThatThrownBy(() -> service.createDefect("f1", "CIAM", "Bug", "tester"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("f1")
                .hasMessageContaining("gate-9");

        verify(preflight, never()).requireJiraWriteScope(any());
        verify(jira, never()).createIssue(any());
        verify(defectRepository, never()).save(any());
    }

    // ---- unknown finding (after gate) → IllegalArgumentException ----

    @Test
    void throwsWhenFindingUnknown() {
        when(defectRepository.findByFindingId("missing")).thenReturn(Optional.empty());
        approveGate();
        when(findingRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDefect("missing", "CIAM", "Bug", "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown finding missing");

        verify(preflight).requireJiraWriteScope("CIAM");
        verify(jira, never()).createIssue(any());
    }

    // ---- happy path: new reservation, scan resolves a service name, attaches YAML, flips status ----

    @Test
    void createsDefectReservesThenWritesAndAttachesYaml() {
        FindingRecord f = finding("f1", "scan-1");
        f.setProposedFix("  /policies:\n    get: {}");
        when(defectRepository.findByFindingId("f1")).thenReturn(Optional.empty());
        approveGate();
        when(findingRepository.findById("f1")).thenReturn(Optional.of(f));
        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        when(scanRepository.findById("scan-1")).thenReturn(Optional.of(scan));
        when(defectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jira.createIssue(any())).thenReturn("CIAM-777");

        DefectLink link = service.createDefect("f1", "CIAM", "Bug", "tester");

        assertThat(link.getJiraKey()).isEqualTo("CIAM-777");
        assertThat(link.isCreatedInJira()).isTrue();
        assertThat(link.getJiraUrl()).isEqualTo("https://jira.bnc.ca/browse/CIAM-777");
        assertThat(link.getCreatedBy()).isEqualTo("tester");
        assertThat(link.getFindingId()).isEqualTo("f1");
        assertThat(link.getScanId()).isEqualTo("scan-1");
        assertThat(link.getLastSyncedAt()).isNotNull();
        assertThat(f.getStatus()).isEqualTo("JIRA_CREATED");

        // service name flows into the composed title
        ArgumentCaptor<JiraCreateRequest> req = ArgumentCaptor.forClass(JiraCreateRequest.class);
        verify(jira).createIssue(req.capture());
        assertThat(req.getValue().summary()).isEqualTo("ciam-policies — POST /v1/transfers — missing endpoint");

        verify(jira).attachFile(eq("CIAM-777"), eq("corrected-openapi.yaml"), eq("  /policies:\n    get: {}"));
        verify(findingRepository).save(f);
        // reservation save + post-write save = 2 saves
        verify(defectRepository, times(2)).save(any());
    }

    // ---- no scan found → service name is null; no proposed fix → no attach ----

    @Test
    void createsDefectWithNullServiceNameAndNoAttachWhenNoProposedFix() {
        FindingRecord f = finding("f2", "scan-x");
        f.setProposedFix(null);
        when(defectRepository.findByFindingId("f2")).thenReturn(Optional.empty());
        approveGate();
        when(findingRepository.findById("f2")).thenReturn(Optional.of(f));
        when(scanRepository.findById("scan-x")).thenReturn(Optional.empty());
        when(defectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jira.createIssue(any())).thenReturn("CIAM-1");

        DefectLink link = service.createDefect("f2", "CIAM", "Bug", "tester");

        assertThat(link.isCreatedInJira()).isTrue();
        ArgumentCaptor<JiraCreateRequest> req = ArgumentCaptor.forClass(JiraCreateRequest.class);
        verify(jira).createIssue(req.capture());
        // null service name → leading separator only
        assertThat(req.getValue().summary()).isEqualTo(" — POST /v1/transfers — missing endpoint");
        verify(jira, never()).attachFile(any(), any(), any());
    }

    // ---- proposed fix present but blank → still no attach (notBlank branch) ----

    @Test
    void skipsAttachWhenProposedFixIsBlank() {
        FindingRecord f = finding("f3", "scan-1");
        f.setProposedFix("   ");
        when(defectRepository.findByFindingId("f3")).thenReturn(Optional.empty());
        approveGate();
        when(findingRepository.findById("f3")).thenReturn(Optional.of(f));
        when(scanRepository.findById("scan-1")).thenReturn(Optional.empty());
        when(defectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jira.createIssue(any())).thenReturn("CIAM-2");

        service.createDefect("f3", "CIAM", "Bug", "tester");

        verify(jira, never()).attachFile(any(), any(), any());
    }

    // ---- attach throws → swallowed (non-fatal); status still flips and link is returned ----

    @Test
    void attachFailureIsNonFatal() {
        FindingRecord f = finding("f4", "scan-1");
        f.setProposedFix("openapi: 3.0.0");
        when(defectRepository.findByFindingId("f4")).thenReturn(Optional.empty());
        approveGate();
        when(findingRepository.findById("f4")).thenReturn(Optional.of(f));
        when(scanRepository.findById("scan-1")).thenReturn(Optional.empty());
        when(defectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jira.createIssue(any())).thenReturn("CIAM-3");
        doThrow(new RuntimeException("attach boom"))
                .when(jira).attachFile(eq("CIAM-3"), any(), any());

        DefectLink link = service.createDefect("f4", "CIAM", "Bug", "tester");

        assertThat(link.getJiraKey()).isEqualTo("CIAM-3");
        assertThat(link.isCreatedInJira()).isTrue();
        assertThat(f.getStatus()).isEqualTo("JIRA_CREATED");
        verify(findingRepository).save(f);
    }

    // ---- unique-constraint race on the reservation save → re-read the winner's row ----

    @Test
    void racingReservationReReadsWinnerRow() {
        FindingRecord f = finding("f5", "scan-1");
        when(defectRepository.findByFindingId("f5"))
                .thenReturn(Optional.empty())     // top-of-method guard sees nothing
                .thenReturn(Optional.of(winnerRow()));  // re-read after the violation
        approveGate();
        when(findingRepository.findById("f5")).thenReturn(Optional.of(f));
        when(scanRepository.findById("scan-1")).thenReturn(Optional.empty());
        when(defectRepository.save(any())).thenThrow(new DataIntegrityViolationException("dup findingId"));

        DefectLink result = service.createDefect("f5", "CIAM", "Bug", "tester");

        assertThat(result.getId()).isEqualTo("winner");
        assertThat(result.getJiraKey()).isEqualTo("CIAM-WINNER");
        // the loser bails before the outward Jira write
        verify(jira, never()).createIssue(any());
        verify(findingRepository, never()).save(any());
    }

    private DefectLink winnerRow() {
        DefectLink w = new DefectLink();
        w.setId("winner");
        w.setFindingId("f5");
        w.setJiraKey("CIAM-WINNER");
        w.setCreatedInJira(true);
        return w;
    }

    // ---- race where the winner row vanishes on re-read → original violation is rethrown ----

    @Test
    void racingReservationRethrowsWhenWinnerRowGone() {
        FindingRecord f = finding("f6", "scan-1");
        when(defectRepository.findByFindingId("f6"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        approveGate();
        when(findingRepository.findById("f6")).thenReturn(Optional.of(f));
        when(scanRepository.findById("scan-1")).thenReturn(Optional.empty());
        when(defectRepository.save(any())).thenThrow(new DataIntegrityViolationException("dup findingId"));

        assertThatThrownBy(() -> service.createDefect("f6", "CIAM", "Bug", "tester"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("dup findingId");
    }

    // ---- a reserved-but-uncreated link (createdInJira=false) is reused; no second reservation save ----

    @Test
    void reusesReservedButUncreatedLinkWithoutReReserving() {
        DefectLink reserved = new DefectLink();
        reserved.setId("reserved-1");
        reserved.setFindingId("f7");
        reserved.setScanId("scan-1");
        reserved.setCreatedInJira(false);
        FindingRecord f = finding("f7", "scan-1");
        when(defectRepository.findByFindingId("f7")).thenReturn(Optional.of(reserved));
        approveGate();
        when(findingRepository.findById("f7")).thenReturn(Optional.of(f));
        when(scanRepository.findById("scan-1")).thenReturn(Optional.empty());
        when(defectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jira.createIssue(any())).thenReturn("CIAM-7");

        DefectLink link = service.createDefect("f7", "CIAM", "Bug", "tester");

        assertThat(link.getId()).isEqualTo("reserved-1");
        assertThat(link.getJiraKey()).isEqualTo("CIAM-7");
        assertThat(link.isCreatedInJira()).isTrue();
        // only the post-write save (no reservation save for the reused row)
        verify(defectRepository, times(1)).save(any());
    }

    // ---- issueType defaults to "Bug" when null (composer branch) ----

    @Test
    void defaultsIssueTypeToBugWhenNull() {
        FindingRecord f = finding("f8", "scan-1");
        when(defectRepository.findByFindingId("f8")).thenReturn(Optional.empty());
        approveGate();
        when(findingRepository.findById("f8")).thenReturn(Optional.of(f));
        when(scanRepository.findById("scan-1")).thenReturn(Optional.empty());
        when(defectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jira.createIssue(any())).thenReturn("CIAM-8");

        service.createDefect("f8", "CIAM", null, "tester");

        ArgumentCaptor<JiraCreateRequest> req = ArgumentCaptor.forClass(JiraCreateRequest.class);
        verify(jira).createIssue(req.capture());
        assertThat(req.getValue().issueType()).isEqualTo("Bug");
    }

    // ---- browseUrl: blank base URL → null jiraUrl ----

    @Test
    void browseUrlIsNullWhenBaseUrlBlank() {
        connections.getJira().setBaseUrl("");
        FindingRecord f = finding("f9", "scan-1");
        when(defectRepository.findByFindingId("f9")).thenReturn(Optional.empty());
        approveGate();
        when(findingRepository.findById("f9")).thenReturn(Optional.of(f));
        when(scanRepository.findById("scan-1")).thenReturn(Optional.empty());
        when(defectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jira.createIssue(any())).thenReturn("CIAM-9");

        DefectLink link = service.createDefect("f9", "CIAM", "Bug", "tester");

        assertThat(link.getJiraUrl()).isNull();
    }

    // ---- browseUrl: trailing-slash base is trimmed before /browse/ ----

    @Test
    void browseUrlTrimsTrailingSlash() {
        connections.getJira().setBaseUrl("https://jira.bnc.ca/");
        FindingRecord f = finding("f10", "scan-1");
        when(defectRepository.findByFindingId("f10")).thenReturn(Optional.empty());
        approveGate();
        when(findingRepository.findById("f10")).thenReturn(Optional.of(f));
        when(scanRepository.findById("scan-1")).thenReturn(Optional.empty());
        when(defectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jira.createIssue(any())).thenReturn("CIAM-10");

        DefectLink link = service.createDefect("f10", "CIAM", "Bug", "tester");

        assertThat(link.getJiraUrl()).isEqualTo("https://jira.bnc.ca/browse/CIAM-10");
    }

    // ============================ DefectComposer branches ============================

    @Test
    void composerPlainSkipsBlankOptionalSectionsAndDefaultsType() {
        FindingRecord f = new FindingRecord();   // type null → "contract issue"; endpoint null → nz("")
        f.setSeverity("MAJOR");
        f.setLayer("L1");

        JiraCreateRequest req = new DefectComposer().compose(f, "svc", "CIAM", "Task");

        assertThat(req.issueType()).isEqualTo("Task");
        assertThat(req.summary()).isEqualTo("svc —  — contract issue");
        // only the Severity/Layer/Spec/Confidence line — no summary/explanation/evidence/yaml/fix/reference
        assertThat(req.descriptionParagraphs()).hasSize(1);
        assertThat(req.descriptionParagraphs().get(0))
                .contains("Severity: MAJOR").contains("Layer: L1")
                .contains("Spec: ").contains("Confidence: ");
        assertThat(req.labels()).containsExactly("contract-validation", "layer-L1", "severity-MAJOR");
    }

    @Test
    void composerPlainEmitsEvidenceWithoutLineWhenStartLineNull() {
        FindingRecord f = new FindingRecord();
        f.setType("EXTRA_ENDPOINT");
        f.setEndpoint("GET /x");
        f.setSeverity("MINOR");
        f.setLayer("L3");
        f.setSummary("sum");
        f.setExplanation("why");
        f.setCodeFile("Ctrl.java");
        f.setCodeStartLine(null);   // evidence line has no ":<n>" suffix
        f.setCodeSnippet("doX();");
        f.setCurrentYamlFragment("a: 1");
        f.setProposedFix("a: 2");
        f.setCitation("CTFL 1.1");

        JiraCreateRequest req = new DefectComposer().compose(f, "svc", "CIAM", null);

        assertThat(req.descriptionParagraphs())
                .anyMatch(p -> p.equals("sum"))
                .anyMatch(p -> p.equals("why"))
                .anyMatch(p -> p.equals("Evidence: Ctrl.java"))   // no ":line"
                .anyMatch(p -> p.equals("doX();"))
                .anyMatch(p -> p.equals("Current YAML: a: 1"))
                .anyMatch(p -> p.equals("Proposed fix: a: 2"))
                .anyMatch(p -> p.equals("Reference: CTFL 1.1"));
    }

    @Test
    void composerWikiEmitsHeadedSectionsAndFooter() {
        ConnectionsProperties c = new ConnectionsProperties();
        c.getJira().setEdition("SERVER_DC");
        FindingRecord f = new FindingRecord();
        f.setType("STATUS_CODE_MISSING");
        f.setEndpoint("GET /policies");
        f.setSeverity("MAJOR");
        f.setLayer("L4");
        f.setSummary("Spec omits 404");
        f.setCodeFile("PolicyController.java");
        f.setCodeStartLine(45);
        f.setCodeSnippet("return notFound();");
        f.setCurrentYamlFragment("get: {}");
        f.setProposedFix("404: {}");
        f.setCitation("CTFL 2.2");

        JiraCreateRequest req = new DefectComposer(c).compose(f, "svc", "CIAM", null);
        String desc = String.join("\n\n", req.descriptionParagraphs());

        assertThat(desc).contains("h3. Details");
        assertThat(desc).contains("h3. Code Evidence").contains("Evidence: PolicyController.java:45");
        assertThat(desc).contains("{code:java}").contains("return notFound();");
        assertThat(desc).contains("h3. Current YAML").contains("{code:yaml}");
        assertThat(desc).contains("h3. Proposed Fix (Corrected YAML)");
        assertThat(desc).contains("h3. Reference").contains("CTFL 2.2");
        assertThat(desc).contains("Generated by Veritas Contract Validation Agent");
    }

    @Test
    void composerWikiCodeEvidenceWithoutSnippetOmitsCodeBlock() {
        ConnectionsProperties c = new ConnectionsProperties();
        c.getJira().setEdition("SERVER_DC");
        FindingRecord f = new FindingRecord();
        f.setType("MISSING_ENDPOINT");
        f.setEndpoint("POST /y");
        f.setSeverity("CRITICAL");
        f.setLayer("L2");
        f.setCodeFile("Y.java");
        f.setCodeStartLine(null);   // no line suffix
        f.setCodeSnippet(null);     // no {code:java} block

        JiraCreateRequest req = new DefectComposer(c).compose(f, "svc", "CIAM", null);

        assertThat(req.descriptionParagraphs())
                .anyMatch(p -> p.startsWith("h3. Code Evidence")
                        && p.contains("Evidence: Y.java")
                        && !p.contains("{code:java}"));
        // no summary/explanation/yaml/fix/reference sections were added
        assertThat(req.descriptionParagraphs())
                .noneMatch(p -> p.startsWith("h3. Current YAML"))
                .noneMatch(p -> p.startsWith("h3. Reference"));
    }

    @Test
    void composerEditionNullDefaultsToPlain() {
        ConnectionsProperties c = new ConnectionsProperties();
        c.getJira().setEdition(null);   // not SERVER_DC → plain paragraphs
        FindingRecord f = new FindingRecord();
        f.setType("MISSING_ENDPOINT");
        f.setEndpoint("POST /z");
        f.setSeverity("CRITICAL");
        f.setLayer("L2");
        f.setProposedFix("fix");

        String desc = String.join("\n", new DefectComposer(c).compose(f, "svc", "CIAM", null)
                .descriptionParagraphs());

        assertThat(desc).contains("Proposed fix: fix");
        assertThat(desc).doesNotContain("{code:yaml}");
        assertThat(desc).doesNotContain("Generated by Veritas Contract Validation Agent");
    }

    @Test
    void composerNullConnectionsDefaultsToPlain() {
        DefectComposer comp = new DefectComposer((ConnectionsProperties) null);   // wiki=false branch
        FindingRecord f = new FindingRecord();
        f.setType("EXTRA");
        f.setEndpoint("GET /n");
        f.setSeverity("MINOR");
        f.setLayer("L3");

        JiraCreateRequest req = comp.compose(f, "svc", "CIAM", null);

        assertThat(req.descriptionParagraphs())
                .noneMatch(p -> p.contains("h3."));
        assertThat(req.summary()).isEqualTo("svc — GET /n — extra");
    }
}
