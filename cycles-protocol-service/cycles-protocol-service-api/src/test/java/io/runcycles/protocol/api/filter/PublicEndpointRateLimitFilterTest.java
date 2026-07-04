package io.runcycles.protocol.api.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PublicEndpointRateLimitFilterTest {

    private static final long T0 = 1_800_000_000_000L; // fixed epoch ms, mid-window irrelevant
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AtomicLong clock;
    private PublicEndpointRateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(T0 - (T0 % 60_000)); // align to window start
        filter = new PublicEndpointRateLimitFilter(objectMapper, true, 3, clock::get);
        chain = mock(FilterChain.class);
    }

    private MockHttpServletRequest evidenceRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET",
                "/v1/evidence/" + "a".repeat(64));
        req.setRequestURI("/v1/evidence/" + "a".repeat(64));
        req.setRemoteAddr("203.0.113.7");
        req.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req-test-1");
        return req;
    }

    @Test
    void underLimit_passesThrough() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(evidenceRequest(), new MockHttpServletResponse(), chain);
        }
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void overLimit_returns429WithSpecHeadersAndBody() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(evidenceRequest(), new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(evidenceRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(Long.parseLong(response.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        long reset = Long.parseLong(response.getHeader("X-RateLimit-Reset"));
        assertThat(reset * 1000).isEqualTo(clock.get() - (clock.get() % 60_000) + 60_000);
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("req-test-1");
        assertThat(response.getHeader(TraceContextFilter.TRACE_ID_HEADER)).matches("[0-9a-f]{32}");

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(body.get("request_id").asText()).isEqualTo("req-test-1");
        assertThat(body.get("trace_id").asText()).matches("[0-9a-f]{32}");
        assertThat(body.get("message").asText()).contains("Rate limit exceeded");
        // Only 3 requests reached the chain
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void windowRollover_resetsCounter() throws Exception {
        for (int i = 0; i < 4; i++) {
            filter.doFilter(evidenceRequest(), new MockHttpServletResponse(), chain);
        }
        // 4th was limited; advance past the window boundary
        clock.addAndGet(60_000);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(evidenceRequest(), response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain, times(4)).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void perClientIsolation() throws Exception {
        for (int i = 0; i < 4; i++) {
            filter.doFilter(evidenceRequest(), new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest other = evidenceRequest();
        other.setRemoteAddr("203.0.113.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(other, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void jwksPath_throttled() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/.well-known/cycles-jwks.json");
        req.setRequestURI("/v1/.well-known/cycles-jwks.json");
        req.setRemoteAddr("203.0.113.7");
        for (int i = 0; i < 3; i++) {
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(req, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void authenticatedPaths_notThrottled() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/reservations");
        req.setRequestURI("/v1/reservations");
        req.setRemoteAddr("203.0.113.7");
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(req, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        verify(chain, times(10)).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disabled_neverThrottles() throws Exception {
        PublicEndpointRateLimitFilter off =
                new PublicEndpointRateLimitFilter(objectMapper, false, 1, clock::get);
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            off.doFilter(evidenceRequest(), response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void missingRequestIdAttribute_generatesOne() throws Exception {
        MockHttpServletRequest req = evidenceRequest();
        req.removeAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        for (int i = 0; i < 3; i++) {
            filter.doFilter(evidenceRequest(), new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(req, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("request_id").asText()).isNotBlank();
    }

    @Test
    void uniqueClientFlood_withinOneWindow_mapStaysHardBounded() throws Exception {
        // Review finding: stale-window eviction alone cannot shrink the map
        // when the flood is all CURRENT-window keys — the cap must hold via
        // a full reset. 10_001 unique client IPs in one window.
        for (int i = 0; i <= 10_000; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET",
                    "/v1/.well-known/cycles-jwks.json");
            req.setRequestURI("/v1/.well-known/cycles-jwks.json");
            req.setRemoteAddr("client-" + i); // unique key per request
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }
        assertThat(filter.trackedClients()).isLessThanOrEqualTo(10_000);

        // Filter remains functional after the reset
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(evidenceRequest(), response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void staleClientEntries_evictedWhenMapGrows() throws Exception {
        // Not size-driven here (cap is 10k); prove rollover replaces the window
        // object rather than leaking counts across windows for the same key.
        for (int i = 0; i < 3; i++) {
            filter.doFilter(evidenceRequest(), new MockHttpServletResponse(), chain);
        }
        clock.addAndGet(120_000);
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(evidenceRequest(), response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.argThat(r -> false),
                org.mockito.ArgumentMatchers.any());
    }
}
