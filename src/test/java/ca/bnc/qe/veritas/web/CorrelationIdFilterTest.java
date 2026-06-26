package ca.bnc.qe.veritas.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesACorrelationIdSetsItInMdcDuringTheChainAndEchoesItBack() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] duringChain = new String[1];
        FilterChain chain = (rq, rs) -> duringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(req, res, chain);

        assertThat(duringChain[0]).isNotBlank();
        assertThat(res.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(duringChain[0]);
        // Cleared after the request so the worker thread doesn't leak the id into the next request.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void honoursAnInboundCorrelationIdHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CorrelationIdFilter.HEADER, "trace-abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] duringChain = new String[1];

        filter.doFilter(req, res, (rq, rs) -> duringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertThat(duringChain[0]).isEqualTo("trace-abc-123");
        assertThat(res.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("trace-abc-123");
    }

    @Test
    void sanitizeStripsUnsafeCharactersAndBoundsLength() {
        assertThat(CorrelationIdFilter.sanitize("bad\nid; rm -rf")).isEqualTo("badidrm-rf");
        assertThat(CorrelationIdFilter.sanitize(null)).isEmpty();
        assertThat(CorrelationIdFilter.sanitize("a".repeat(200))).hasSize(64);
    }

    @Test
    void clearsTheMdcEvenWhenTheChainThrows() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        try {
            filter.doFilter(req, res, (rq, rs) -> {
                throw new RuntimeException("boom");
            });
        } catch (Exception ignored) {
            // expected
        }
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
