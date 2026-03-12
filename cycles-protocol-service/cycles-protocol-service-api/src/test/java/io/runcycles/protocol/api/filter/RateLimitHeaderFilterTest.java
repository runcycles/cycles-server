package io.runcycles.protocol.api.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RateLimitHeaderFilter")
class RateLimitHeaderFilterTest {

    private final RateLimitHeaderFilter filter = new RateLimitHeaderFilter();

    @Test
    void shouldSetRateLimitHeadersForV1Paths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/v1/reservations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("-1");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("0");
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldNotSetHeadersForNonV1Paths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-RateLimit-Remaining")).isNull();
        assertThat(response.getHeader("X-RateLimit-Reset")).isNull();
        verify(chain).doFilter(request, response);
    }
}
