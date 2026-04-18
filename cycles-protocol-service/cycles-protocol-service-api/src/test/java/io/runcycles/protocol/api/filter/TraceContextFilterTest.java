package io.runcycles.protocol.api.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("TraceContextFilter — W3C Trace Context extraction")
class TraceContextFilterTest {

    private final TraceContextFilter filter = new TraceContextFilter();

    private MockHttpServletResponse runFilter(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
        return response;
    }

    @Test
    @DisplayName("extracts trace_id from valid traceparent header")
    void extractsFromValidTraceparent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        MockHttpServletResponse response = runFilter(request);

        assertThat(request.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(request.getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE))
                .isEqualTo("01");
        assertThat(request.getAttribute(TraceContextFilter.TRACE_INBOUND_W3C_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("preserves inbound trace-flags byte (sampled=0 not overridden)")
    void preservesInboundTraceFlags() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");

        runFilter(request);

        assertThat(request.getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE)).isEqualTo("00");
        assertThat(request.getAttribute(TraceContextFilter.TRACE_INBOUND_W3C_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("extracts from valid X-Cycles-Trace-Id when traceparent absent")
    void extractsFromFlatHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Cycles-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736");

        MockHttpServletResponse response = runFilter(request);

        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(request.getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE))
                .isEqualTo("01");
        assertThat(request.getAttribute(TraceContextFilter.TRACE_INBOUND_W3C_ATTRIBUTE))
                .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("traceparent wins over X-Cycles-Trace-Id when both valid and disagree")
    void traceparentWinsOverFlatHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.addHeader("X-Cycles-Trace-Id", "ffffffffffffffffffffffffffffffff");

        MockHttpServletResponse response = runFilter(request);

        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("generates new trace_id when no inbound headers")
    void generatesWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        MockHttpServletResponse response = runFilter(request);

        String traceId = response.getHeader(TraceContextFilter.TRACE_ID_HEADER);
        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");
        assertThat(request.getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE)).isEqualTo("01");
        assertThat(request.getAttribute(TraceContextFilter.TRACE_INBOUND_W3C_ATTRIBUTE))
                .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("malformed traceparent falls through (does not reject)")
    void malformedTraceparentFallsThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "not-a-valid-traceparent");

        MockHttpServletResponse response = runFilter(request);

        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER)).matches("^[0-9a-f]{32}$");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("malformed X-Cycles-Trace-Id falls through to generation")
    void malformedFlatHeaderFallsThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Cycles-Trace-Id", "not-32-hex");

        MockHttpServletResponse response = runFilter(request);

        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER)).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("all-zero traceparent trace-id rejected, falls through")
    void allZeroTraceIdRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01");

        MockHttpServletResponse response = runFilter(request);

        String traceId = response.getHeader(TraceContextFilter.TRACE_ID_HEADER);
        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    @DisplayName("all-zero span-id in traceparent rejected, falls through")
    void allZeroSpanIdRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01");

        MockHttpServletResponse response = runFilter(request);

        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER))
                .isNotEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("all-zero X-Cycles-Trace-Id rejected, falls through")
    void allZeroFlatHeaderRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Cycles-Trace-Id", "00000000000000000000000000000000");

        MockHttpServletResponse response = runFilter(request);

        String traceId = response.getHeader(TraceContextFilter.TRACE_ID_HEADER);
        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    @DisplayName("uppercase input normalized to lowercase")
    void uppercaseNormalized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Cycles-Trace-Id", "4BF92F3577B34DA6A3CE929D0E0E4736");

        MockHttpServletResponse response = runFilter(request);

        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("currentTraceId returns attribute value")
    void currentTraceIdAccessor() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        runFilter(request);
        String traceId = TraceContextFilter.currentTraceId(request);
        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat(TraceContextFilter.currentTraceId(null)).isNull();
    }

    @Test
    @DisplayName("rejects traceparent version other than 00")
    void rejectsNonV00Version() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent",
                "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        MockHttpServletResponse response = runFilter(request);

        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER))
                .isNotEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }
}
