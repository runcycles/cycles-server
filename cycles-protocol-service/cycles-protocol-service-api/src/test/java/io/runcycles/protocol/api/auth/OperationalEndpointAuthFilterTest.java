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
import java.lang.reflect.Method;

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
    void protectedEndpoint_withoutCorrelationAttributes_generatesIds() throws Exception {
        request.removeAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        request.removeAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE);
        request.setRequestURI("/actuator/prometheus");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER)).isNotBlank();
        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))
                .isEqualTo(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.get("request_id").asText())
                .isEqualTo(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
        assertThat(body.get("trace_id").asText())
                .isEqualTo(response.getHeader(TraceContextFilter.TRACE_ID_HEADER));
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

    @Test
    void nullPathBlankCredentialsAndNullConfigurationCoverDefensiveBranches() throws Exception {
        request.setRequestURI(null);
        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.setRequestURI("/actuator/info");
        request.addHeader("X-Admin-API-Key", " ");
        filter.doFilterInternal(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);

        ReflectionTestUtils.setField(filter, "adminApiKey", null);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.setRequestURI("/actuator/info");
        filter.doFilterInternal(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    void everyProtectedPrefixAndExistingCorrelationHeaderAlternativeIsHandled() throws Exception {
        for (String path : new String[]{"/v3/api-docs/openapi.json", "/webjars/swagger-ui.js"}) {
            MockHttpServletRequest protectedRequest = new MockHttpServletRequest();
            protectedRequest.setRequestURI(path);
            protectedRequest.addHeader("X-Admin-API-Key", "admin-secret");
            filter.doFilterInternal(protectedRequest, new MockHttpServletResponse(), chain);
        }

        request.setRequestURI("/actuator/info");
        request.setAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE, " ");
        response.setHeader(RequestIdFilter.REQUEST_ID_HEADER, "existing-request");
        response.setHeader(TraceContextFilter.TRACE_ID_HEADER, "existing-trace");
        filter.doFilterInternal(request, response, chain);
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("existing-request");
        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER)).isEqualTo("existing-trace");
    }

    @Test
    void logSanitizerCoversNullAndControlCharacters() throws Exception {
        Method method = OperationalEndpointAuthFilter.class
            .getDeclaredMethod("safeLogValue", Object.class);
        method.setAccessible(true);
        assertThat(method.invoke(null, new Object[]{null})).isNull();
        assertThat(method.invoke(null, "a\r\nb")).isEqualTo("a  b");
    }
}
