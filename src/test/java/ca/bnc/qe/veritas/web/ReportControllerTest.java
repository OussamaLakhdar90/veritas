package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.report.ContractReportRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The report endpoint re-renders LIVE from persisted findings (so a recorded disposition shows up). */
@WebMvcTest(ReportController.class)
class ReportControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private ScanRepository scanRepository;
    @MockBean private FindingRecordRepository findingRepository;
    @MockBean private ContractReportRenderer renderer;

    @Test
    void reRendersLiveFromPersistedFindings() throws Exception {
        Scan s = new Scan();
        s.setServiceName("ciam-policies");
        when(scanRepository.findById("abc")).thenReturn(Optional.of(s));
        FindingRecord r = new FindingRecord();
        r.setType("MISSING_ENDPOINT");
        r.setSeverity("CRITICAL");
        r.setStatus("REJECTED");
        when(findingRepository.findByScanIdOrderBySeverityAsc("abc")).thenReturn(List.of(r));
        when(renderer.renderHtml(any(), any(), any())).thenReturn("<html>LIVE</html>");

        mvc.perform(get("/api/v1/scans/abc/report"))
                .andExpect(status().isOk())
                .andExpect(content().string("<html>LIVE</html>"));
    }

    @Test
    void returns404WhenNoLiveFindingsAndNoFileOnDisk() throws Exception {
        when(scanRepository.findById("zzz")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/scans/zzz/report")).andExpect(status().isNotFound());
    }
}
