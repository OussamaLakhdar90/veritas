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
}
