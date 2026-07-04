package io.runcycles.protocol.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.util.TraceIdGenerator;
import io.runcycles.protocol.model.Enums;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Fixed-window per-client-IP rate limiter for the PUBLIC (unauthenticated)
 * endpoints: {@code GET /v1/evidence/*} and
 * {@code GET /v1/.well-known/cycles-jwks.json}. Implements the spec's
 * SHOULD-level throttling for these operations (cycles-protocol-v0
 * declares 429 with Retry-After + X-RateLimit-Reset on both; revision
 * 2026-07-04 binds 429 to error=LIMIT_EXCEEDED).
 *
 * <p>Authenticated /v1 endpoints are NOT covered — abuse there is
 * attributable to an API key and handled by operator action; the public
 * endpoints are the only surface an anonymous caller can hammer.
 *
 * <p>Window state is in-process (per instance) — a deliberate v0 choice:
 * the goal is abuse damping, not a distributed quota. Behind a
 * load-balancer the effective limit is {@code limit × instances}, and
 * client IP is the socket peer ({@code getRemoteAddr()}) — deployments
 * that terminate TLS upstream should rate-limit at the ingress instead,
 * or accept per-proxy granularity. Both caveats documented in README.
 */
@Component
@Order(3)
public class PublicEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(PublicEndpointRateLimitFilter.class);
    private static final String JWKS_PATH = "/v1/.well-known/cycles-jwks.json";
    private static final String EVIDENCE_PREFIX = "/v1/evidence/";
    /** Bound on tracked client entries; exceeded → drop stale windows. */
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int requestsPerMinute;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public PublicEndpointRateLimitFilter(
            ObjectMapper objectMapper,
            @Value("${cycles.public-rate-limit.enabled:true}") boolean enabled,
            @Value("${cycles.public-rate-limit.requests-per-minute:300}") int requestsPerMinute) {
        this(objectMapper, enabled, requestsPerMinute, System::currentTimeMillis);
    }

    PublicEndpointRateLimitFilter(ObjectMapper objectMapper, boolean enabled,
                                  int requestsPerMinute, LongSupplier clock) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
        this.clock = clock;
    }

    private record Window(long windowStartMs, AtomicInteger count) {}

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled || !isPublicThrottledPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = clock.getAsLong();
        long windowStart = now - (now % 60_000L);
        String clientKey = request.getRemoteAddr();

        Window window = windows.compute(clientKey == null ? "unknown" : clientKey, (k, w) ->
                (w == null || w.windowStartMs() != windowStart)
                        ? new Window(windowStart, new AtomicInteger(0))
                        : w);
        int count = window.count().incrementAndGet();

        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.entrySet().removeIf(e -> e.getValue().windowStartMs() != windowStart);
        }

        if (count > requestsPerMinute) {
            long windowEndMs = windowStart + 60_000L;
            long retryAfterSeconds = Math.max(1L, (windowEndMs - now + 999) / 1000);
            sendRateLimited(request, response, retryAfterSeconds, windowEndMs / 1000);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isPublicThrottledPath(String path) {
        return path != null && (path.startsWith(EVIDENCE_PREFIX) || JWKS_PATH.equals(path));
    }

    private void sendRateLimited(HttpServletRequest request, HttpServletResponse response,
                                 long retryAfterSeconds, long resetEpochSeconds) throws IOException {
        String requestId = resolveRequestId(request);
        String traceId = resolveTraceId(request);
        LOG.warn("Public endpoint rate limited: path={} client={} limit_per_minute={} request_id={} trace_id={}",
                sanitize(request.getRequestURI()), sanitize(request.getRemoteAddr()),
                requestsPerMinute, requestId, traceId);

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        // Overrides RateLimitHeaderFilter's "unlimited" sentinels on this response
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("X-RateLimit-Reset", Long.toString(resetEpochSeconds));
        if (!response.containsHeader(RequestIdFilter.REQUEST_ID_HEADER)) {
            response.setHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        }
        if (!response.containsHeader(TraceContextFilter.TRACE_ID_HEADER)) {
            response.setHeader(TraceContextFilter.TRACE_ID_HEADER, traceId);
        }

        Map<String, Object> body = Map.of(
                "error", Enums.ErrorCode.LIMIT_EXCEEDED,
                "message", "Rate limit exceeded for public endpoint; retry after " + retryAfterSeconds + "s",
                "request_id", requestId,
                "trace_id", traceId
        );
        objectMapper.writeValue(response.getWriter(), body);
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return attr != null ? attr.toString() : UUID.randomUUID().toString();
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = TraceContextFilter.currentTraceId(request);
        return traceId != null && !traceId.isBlank() ? traceId : TraceIdGenerator.generate();
    }

    private static String sanitize(String value) {
        return value == null ? null : value.replace('\r', ' ').replace('\n', ' ');
    }
}
