package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.settings.CurrentUser;
import ca.bnc.qe.veritas.vcs.BitbucketLinkBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer test: PATCH /findings/{id} triages a finding (status), and rejects unknown statuses. */
@WebMvcTest(FindingsController.class)
class FindingsControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private ScanRepository scanRepository;
    @MockBean private FindingRecordRepository findingRepository;
    @MockBean private CurrentUser currentUser;
    @MockBean private BitbucketLinkBuilder linkBuilder;

    @Test
    void scansTrendZeroFillsTheWindowAndSumsTodaysFindings() throws Exception {
        Scan s = new Scan();
        s.setServiceName("svc");
        s.setTotalFindings(4);
        s.setStartedAt(java.time.Instant.now());   // falls in today's bucket
        when(scanRepository.findAll()).thenReturn(List.of(s));

        mvc.perform(get("/api/v1/scans/trend?days=7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[6].scans").value(1))
                .andExpect(jsonPath("$[6].findings").value(4))
                .andExpect(jsonPath("$[0].scans").value(0));
    }

    @Test
    void patchUpdatesStatus() throws Exception {
        FindingRecord f = new FindingRecord();
        f.setStatus("OPEN");
        when(findingRepository.findById("f1")).thenReturn(Optional.of(f));
        when(findingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/findings/f1").contentType("application/json").content("{\"status\":\"WONT_FIX\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WONT_FIX"));
    }

    @Test
    void patchRecordsDispositionAuditTrail() throws Exception {
        when(currentUser.principalId()).thenReturn("alice");
        FindingRecord f = new FindingRecord();
        f.setStatus("OPEN");
        when(findingRepository.findById("f1")).thenReturn(Optional.of(f));
        when(findingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/findings/f1").contentType("application/json")
                        .content("{\"status\":\"REJECTED\",\"note\":\"false positive — security is in the filter chain\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedBy").value("alice"))
                .andExpect(jsonPath("$.reviewedAt").exists())
                .andExpect(jsonPath("$.reviewNote").value("false positive — security is in the filter chain"));
    }

    @Test
    void patchRejectsUnknownStatus() throws Exception {
        FindingRecord f = new FindingRecord();
        when(findingRepository.findById("f1")).thenReturn(Optional.of(f));
        mvc.perform(patch("/api/v1/findings/f1").contentType("application/json").content("{\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchSetsAUserSeverityOverrideWithAuditLeavingTheEngineSeverityIntact() throws Exception {
        when(currentUser.principalId()).thenReturn("alice");
        FindingRecord f = new FindingRecord();
        f.setSeverity("MINOR");
        f.setAiDisputed(true);   // severity is editable only where the engine asked for help (disputed / unspecified)
        when(findingRepository.findById("f1")).thenReturn(Optional.of(f));
        when(findingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/findings/f1").contentType("application/json").content("{\"severity\":\"critical\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userSeverity").value("CRITICAL"))
                .andExpect(jsonPath("$.severity").value("MINOR"))   // the engine classification is never altered
                .andExpect(jsonPath("$.reviewedBy").value("alice"))
                .andExpect(jsonPath("$.reviewedAt").exists());
    }

    @Test
    void patchRejectsASeverityOverrideOnAConfidentFinding() throws Exception {
        FindingRecord f = new FindingRecord();
        f.setSeverity("MINOR");   // confident: not disputed, not UNSPECIFIED → the classification is read-only
        when(findingRepository.findById("f1")).thenReturn(Optional.of(f));
        mvc.perform(patch("/api/v1/findings/f1").contentType("application/json").content("{\"severity\":\"critical\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patchRejectsUnknownSeverity() throws Exception {
        FindingRecord f = new FindingRecord();
        when(findingRepository.findById("f1")).thenReturn(Optional.of(f));
        mvc.perform(patch("/api/v1/findings/f1").contentType("application/json").content("{\"severity\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scanByIdExposesLiveStageForThePollingStepper() throws Exception {
        Scan s = new Scan();
        s.setServiceName("ciam-policies");
        s.setStatus(RunStatus.RUNNING);
        s.setStage("CLONING");
        when(scanRepository.findById("s1")).thenReturn(Optional.of(s));

        mvc.perform(get("/api/v1/scans/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.stage").value("CLONING"));
    }

    @Test
    void scanByIdReturns404WhenUnknown() throws Exception {
        when(scanRepository.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/scans/nope")).andExpect(status().isNotFound());
    }

    @Test
    void findingsAreFacetedBySeverityServerSide() throws Exception {
        FindingRecord crit = new FindingRecord();
        crit.setSeverity("CRITICAL");
        crit.setSummary("a blocker");
        FindingRecord minor = new FindingRecord();
        minor.setSeverity("MINOR");
        minor.setSummary("a nit");
        when(findingRepository.findByScanId("s1")).thenReturn(List.of(crit, minor));

        mvc.perform(get("/api/v1/scans/s1/findings?severity=critical"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].severity").value("CRITICAL"));
    }

    @Test
    void findingsCarryABitbucketDeepLinkToTheirCodeEvidence() throws Exception {
        Scan scan = new Scan();
        scan.setAppId("APP7571");
        scan.setRepoSlug("ciam-policies");
        scan.setGitRef("develop");
        FindingRecord f = new FindingRecord();
        f.setSeverity("MAJOR");
        f.setCodeFile("src/main/java/ca/bnc/PolicyController.java");
        f.setCodeStartLine(45);
        when(scanRepository.findById("s1")).thenReturn(Optional.of(scan));
        when(findingRepository.findByScanId("s1")).thenReturn(List.of(f));
        when(linkBuilder.fileLink(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of("https://git.bnc.ca/projects/APP7571/repos/ciam-policies/browse/src/main/java/ca/bnc/PolicyController.java?at=refs%2Fheads%2Fdevelop#45"));

        mvc.perform(get("/api/v1/scans/s1/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codeUrl").value(org.hamcrest.Matchers.containsString("/browse/")));
    }
}
