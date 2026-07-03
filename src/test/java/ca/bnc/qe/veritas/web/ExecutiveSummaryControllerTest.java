package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The executive rollup: latest-scan-per-service verdicts, disposition tallies, breaking-caught total. */
@WebMvcTest(ExecutiveSummaryController.class)
class ExecutiveSummaryControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private ScanRepository scans;
    @MockBean private FindingRecordRepository findings;

    private static Scan scan(String id, String service, Integer fidelity, Integer previous, int daysAgo) {
        Scan s = new Scan();
        s.setServiceName(service);
        s.setStatus(RunStatus.COMPLETED);
        s.setFidelityScore(fidelity);
        s.setPreviousFidelityScore(previous);
        s.setStartedAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        s.setId(id);
        return s;
    }

    private static FindingRecord rec(String type, String severity, String status, boolean disputed) {
        FindingRecord r = new FindingRecord();
        r.setType(type);
        r.setSeverity(severity);
        r.setStatus(status);
        r.setAiDisputed(disputed);
        if (!"OPEN".equals(status)) {
            r.setReviewedAt(Instant.now());
        }
        return r;
    }

    @Test
    void rollsUpVerdictsDispositionsAndBreakingTotal() throws Exception {
        // payments: an OLDER scan must be ignored — only s2 (the latest, 92, all-additive PASS) counts.
        when(scans.findAll()).thenReturn(List.of(
                scan("s1", "payments", 80, null, 20),
                scan("s2", "payments", 92, 88, 1),
                scan("s3", "loans", 62, 60, 2)));
        when(findings.findByScanIdOrderBySeverityAsc("s2")).thenReturn(List.of(
                rec("SCHEMA_FIELD_MISSING", "MINOR", "ACCEPTED", false),
                rec("STATUS_CODE_MISSING", "MINOR", "OPEN", true)));
        when(findings.findByScanIdOrderBySeverityAsc("s3")).thenReturn(List.of(
                rec("MISSING_ENDPOINT", "CRITICAL", "OPEN", false),          // blocking + breaking → FAIL
                rec("SCHEMA_FIELD_TYPE_MISMATCH", "MAJOR", "JIRA_CREATED", false),
                rec("SCHEMA_FIELD_TYPE_MISMATCH", "MAJOR", "REJECTED", false)));   // dismissed → not "caught"
        when(findings.countDistinctCaughtByTypes(anyCollection())).thenReturn(14L);

        mvc.perform(get("/api/v1/summary/executive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.breakingFindingsCaught").value(14))
                .andExpect(jsonPath("$.totals.blockingOpen").value(1))
                .andExpect(jsonPath("$.totals.disputedByAi").value(1))
                // alphabetical: loans first
                .andExpect(jsonPath("$.perService[0].service").value("loans"))
                .andExpect(jsonPath("$.perService[0].fidelity").value(62))
                .andExpect(jsonPath("$.perService[0].delta").value(2))
                .andExpect(jsonPath("$.perService[0].releaseSafe").value("FAIL"))
                .andExpect(jsonPath("$.perService[0].breakingCount").value(2))   // rejected one excluded
                .andExpect(jsonPath("$.perService[1].service").value("payments"))
                .andExpect(jsonPath("$.perService[1].fidelity").value(92))
                .andExpect(jsonPath("$.perService[1].delta").value(4))
                .andExpect(jsonPath("$.perService[1].releaseSafe").value("PASS"))
                .andExpect(jsonPath("$.perService[1].latestScanId").value("s2"))
                .andExpect(jsonPath("$.dispositions.accepted").value(1))
                .andExpect(jsonPath("$.dispositions.rejected").value(1))
                .andExpect(jsonPath("$.dispositions.jiraCreated").value(1))
                .andExpect(jsonPath("$.dispositions.open").value(2));
    }

    @Test
    void emptyPortfolioReturnsZeroesNotErrors() throws Exception {
        when(scans.findAll()).thenReturn(List.of());
        when(findings.countDistinctCaughtByTypes(anyCollection())).thenReturn(0L);
        mvc.perform(get("/api/v1/summary/executive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.breakingFindingsCaught").value(0))
                .andExpect(jsonPath("$.perService").isEmpty());
    }

    @Test
    void fidelityTrendBucketsPerDayIntoARisingSeries() throws Exception {
        // payments improves 60→90 (5d ago → 1d ago); loans joins the portfolio at 70 (3d ago). The daily mean must
        // start at 60 (only payments), jump when loans appears, and finish at 80 = mean(90, 70) — a rising series.
        when(scans.findAll()).thenReturn(List.of(
                scan("p1", "payments", 60, null, 5),
                scan("p2", "payments", 90, 60, 1),
                scan("l1", "loans", 70, null, 3)));

        mvc.perform(get("/api/v1/summary/fidelity-trend").param("days", "7"))
                .andExpect(status().isOk())
                // Leading days with no data are skipped: the series starts the day payments first scored (60)…
                .andExpect(jsonPath("$[0].value").value(60))
                // …and ends today at the portfolio mean of both services' latest scores, mean(90, 70) = 80.
                .andExpect(jsonPath("$[-1].value").value(80));
    }

    @Test
    void fidelityTrendIsEmptyWithoutData() throws Exception {
        when(scans.findAll()).thenReturn(List.of());
        mvc.perform(get("/api/v1/summary/fidelity-trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
