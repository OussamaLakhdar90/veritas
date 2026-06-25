package ca.bnc.qe.veritas.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.report.StrategyRationaleRenderer;
import ca.bnc.qe.veritas.report.WhyDocRenderer;
import ca.bnc.qe.veritas.testmgmt.TestStrategyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The strategy why-doc endpoint renders HTML for a known strategy and 404s for an unknown one. */
@WebMvcTest(StrategyController.class)
class StrategyControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private TestStrategyRepository repository;
    @MockBean private TestStrategyService service;
    @MockBean private StrategyRationaleRenderer rationaleRenderer;
    @MockBean private WhyDocRenderer whyDocRenderer;

    @Test
    void whyDocReturnsHtmlForAKnownStrategy() throws Exception {
        TestStrategy s = new TestStrategy();
        s.setId("strat-1");
        when(repository.findById("strat-1")).thenReturn(Optional.of(s));
        when(whyDocRenderer.renderHtml(s)).thenReturn("<html><body>evidence why-doc</body></html>");

        mvc.perform(get("/api/v1/strategies/strat-1/why-doc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("evidence why-doc")));
    }

    @Test
    void whyDocIs404ForAnUnknownStrategy() throws Exception {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/strategies/nope/why-doc")).andExpect(status().isNotFound());
    }
}
