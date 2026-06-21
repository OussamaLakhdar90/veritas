package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.ScanRepository;
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
    void patchRejectsUnknownStatus() throws Exception {
        FindingRecord f = new FindingRecord();
        when(findingRepository.findById("f1")).thenReturn(Optional.of(f));
        mvc.perform(patch("/api/v1/findings/f1").contentType("application/json").content("{\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }
}
