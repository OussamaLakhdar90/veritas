package ca.bnc.qe.veritas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class ServerApiAuthFilterTest {

    private ServerApiAuthFilter filter(String token) {
        ServerApiAuthFilter f = new ServerApiAuthFilter();
        ReflectionTestUtils.setField(f, "apiToken", token);
        return f;
    }

    @Test
    void rejectsApiRequestWithoutBearerToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/scans");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter("secret").doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void passesApiRequestWithCorrectToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/scans");
        req.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter("secret").doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void failsClosedWhenNoTokenConfigured() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/settings/secrets");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter("").doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void leavesNonApiPathsAlone() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter("secret").doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);   // static assets are public
    }
}
