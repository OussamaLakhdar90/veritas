package ca.bnc.qe.veritas.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.catalog.ServiceCatalog;
import ca.bnc.qe.veritas.catalog.ServiceSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** GET /services returns the catalog the dashboard picker consumes. */
@WebMvcTest(ServicesController.class)
class ServicesControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private ServiceCatalog catalog;

    @Test
    void listsServicesWithPerStageCounts() throws Exception {
        when(catalog.catalog()).thenReturn(List.of(new ServiceSummary("ciam-policies", 2, 9, 12, 0, 5, 4)));

        mvc.perform(get("/api/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("ciam-policies"))
                .andExpect(jsonPath("$[0].conditions").value(9))
                .andExpect(jsonPath("$[0].cases").value(12));
    }
}
