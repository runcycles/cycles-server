package io.runcycles.protocol.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.api.filter.TraceContextFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("OperationalEndpointAuthFilter")
class OperationalEndpointAuthFilterTest {

    private OperationalEndpointAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new OperationalEndpointAuthFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "adminApiKey", "admin-secret");
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req-ops");
        request.setAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE,
                "0123456789abcdef0123456789abcdef");
    }

    @Test
    void readinessHealth_isPublicForProbes() throws Exception {
        request.setRequestURI("/actuator/health/readiness");

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void livenessHealth_isPublicForProbes() throws Exception {
        request.setRequestURI("/actuator/health/liveness");

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aggregateHealth_withoutAdminKey_returns401() throws Exception {
        request.setRequestURI("/actuator/health");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("req-ops");
        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER))
                .isEqualTo("0123456789abcdef0123456789abcdef");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void prometheus_withoutAdminKey_returns401() throws Exception {
        request.setRequestURI("/actuator/prometheus");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).get("trace_id").asText())
                .isEqualTo("0123456789abcdef0123456789abcdef");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void apiDocs_withValidAdminKey_passesThrough() throws Exception {
        request.setRequestURI("/api-docs");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void swagger_withInvalidAdminKey_returns401() throws Exception {
        request.setRequestURI("/swagger-ui.html");
        request.addHeader("X-Admin-API-Key", "wrong");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void protectedEndpoint_withoutConfiguredAdminKey_returns500() throws Exception {
        ReflectionTestUtils.setField(filter, "adminApiKey", "");
        request.setRequestURI("/actuator/info");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void protocolEvidenceEndpoint_isNotProtectedHere() throws Exception {
        request.setRequestURI("/v1/evidence/abc");

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
    }
}
