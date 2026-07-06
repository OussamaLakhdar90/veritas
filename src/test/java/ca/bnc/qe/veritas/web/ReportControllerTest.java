package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    void downloadAddsAttachmentHeaderWithTheReadableName() throws Exception {
        // ?download=true serves the SAME live HTML but as a named attachment, so the user can save the report and
        // keep its accept/reject controls working offline (the report JS is self-contained).
        Scan s = new Scan();
        s.setServiceName("ciam-policies");
        s.setModel("claude-opus-4-8");
        s.setStartedAt(java.time.Instant.parse("2026-06-30T14:15:22Z"));
        when(scanRepository.findById("abc")).thenReturn(Optional.of(s));
        FindingRecord r = new FindingRecord();
        r.setType("MISSING_ENDPOINT");
        r.setSeverity("CRITICAL");
        when(findingRepository.findByScanIdOrderBySeverityAsc("abc")).thenReturn(List.of(r));
        when(renderer.renderHtml(any(), any(), any())).thenReturn("<html>LIVE</html>");

        String expectedName = ca.bnc.qe.veritas.report.ReportNaming.baseName(s) + ".html";
        mvc.perform(get("/api/v1/scans/abc/report").param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("<html>LIVE</html>"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + expectedName + "\""));
    }

    @Test
    void returns404WhenNoLiveFindingsAndNoFileOnDisk() throws Exception {
        when(scanRepository.findById("zzz")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/scans/zzz/report")).andExpect(status().isNotFound());
    }

    @Test
    void servesTheCorrectedSpecYamlAsASiblingOfTheReport() throws Exception {
        // The report's "Download the corrected OpenAPI YAML" link is a sibling of /scans/{id}/report; this endpoint
        // serves the file the writer produced (same ReportNaming name) so the link resolves in the dashboard, not just
        // as an on-disk file. Previously there was NO endpoint at all → the link 404'd in every served report.
        Scan s = new Scan();
        s.setId("abc");
        s.setServiceName("ciam-policies");
        when(scanRepository.findById("abc")).thenReturn(Optional.of(s));
        String name = ca.bnc.qe.veritas.report.ReportNaming.correctedSpecName(s);   // openapi.corrected-abc.yaml
        java.nio.file.Path out = java.nio.file.Files.createDirectories(java.nio.file.Path.of("out"));
        java.nio.file.Path f = out.resolve(name);
        java.nio.file.Files.writeString(f, "openapi: 3.0.3\n");
        try {
            mvc.perform(get("/api/v1/scans/abc/" + name))
                    .andExpect(status().isOk())
                    .andExpect(content().string("openapi: 3.0.3\n"))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + name + "\""));
        } finally {
            java.nio.file.Files.deleteIfExists(f);
        }
    }

    @Test
    void correctedSpec404sWhenTheArtifactWasNeverWritten() throws Exception {
        // A clean contract (no proposed fix) writes no corrected spec → 404, not a 200 with an empty/misleading body.
        Scan s = new Scan();
        s.setId("abc");
        when(scanRepository.findById("abc")).thenReturn(Optional.of(s));
        mvc.perform(get("/api/v1/scans/abc/" + ca.bnc.qe.veritas.report.ReportNaming.correctedSpecName(s)))
                .andExpect(status().isNotFound());
    }

    @Test
    void correctedSpec404sForAnUnknownScan() throws Exception {
        when(scanRepository.findById("zzz")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/scans/zzz/openapi.corrected-zzz.yaml")).andExpect(status().isNotFound());
    }

    @Test
    void fallsBackToTheReadableNamedFileWhenNoLiveFindings() throws Exception {
        // A clean-contract scan persists no findings → the controller serves the as-scanned file, found by the SAME
        // readable name the writer used (proving ReportNaming is the single source of truth for both sides).
        Scan s = new Scan();
        s.setServiceName("ciam-policies");
        s.setModel("claude-opus-4-8");
        s.setStartedAt(java.time.Instant.parse("2026-06-30T14:15:22Z"));
        when(scanRepository.findById("abc")).thenReturn(Optional.of(s));
        when(findingRepository.findByScanIdOrderBySeverityAsc("abc")).thenReturn(List.of());   // no live findings

        java.nio.file.Path out = java.nio.file.Files.createDirectories(java.nio.file.Path.of("out"));
        java.nio.file.Path f = out.resolve(ca.bnc.qe.veritas.report.ReportNaming.baseName(s) + ".html");
        java.nio.file.Files.writeString(f, "<html>AS-SCANNED</html>");
        try {
            mvc.perform(get("/api/v1/scans/abc/report"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("<html>AS-SCANNED</html>"));
        } finally {
            java.nio.file.Files.deleteIfExists(f);
        }
    }
}
