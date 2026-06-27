package io.runcycles.protocol.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.api.filter.TraceContextFilter;
import io.runcycles.protocol.data.util.TraceIdGenerator;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Protects operational endpoints that are intentionally outside tenant API-key
 * auth. Liveness/readiness remain public for orchestrators; aggregate actuator,
 * Prometheus, and docs require the configured admin key.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class OperationalEndpointAuthFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(OperationalEndpointAuthFilter.class);
    private static final String ADMIN_KEY_HEADER = "X-Admin-API-Key";

    @Value("${admin.api-key:}")
    private String adminApiKey;

    private final ObjectMapper objectMapper;

    public OperationalEndpointAuthFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!requiresProtection(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestId = resolveRequestId(request);
        String traceId = resolveTraceId(request);
        String method = safeLogValue(request.getMethod());
        String path = safeLogValue(request.getRequestURI());

        if (adminApiKey == null || adminApiKey.isBlank()) {
            LOG.error("Operational endpoint auth failed: reason=admin_key_not_configured method={} path={} request_id={} trace_id={}",
                    method, path, requestId, traceId);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    Enums.ErrorCode.INTERNAL_ERROR,
                    "Server misconfiguration: admin API key not set",
                    requestId, traceId);
            return;
        }

        String presented = request.getHeader(ADMIN_KEY_HEADER);
        if (presented == null || presented.isBlank()) {
            LOG.warn("Operational endpoint auth failed: reason=missing_admin_key method={} path={} request_id={} trace_id={}",
                    method, path, requestId, traceId);
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Enums.ErrorCode.UNAUTHORIZED,
                    "Missing " + ADMIN_KEY_HEADER + " header",
                    requestId, traceId);
            return;
        }

        if (!adminKeysMatch(presented)) {
            LOG.warn("Operational endpoint auth failed: reason=invalid_admin_key method={} path={} request_id={} trace_id={}",
                    method, path, requestId, traceId);
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Enums.ErrorCode.UNAUTHORIZED,
                    "Invalid admin API key",
                    requestId, traceId);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresProtection(String path) {
        if (path == null) {
            return false;
        }
        if ("/actuator/health/liveness".equals(path) || "/actuator/health/readiness".equals(path)) {
            return false;
        }
        return path.startsWith("/actuator")
                || path.startsWith("/api-docs")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger")
                || path.startsWith("/webjars");
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (attr != null) {
            return attr.toString();
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, generated);
        return generated;
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = TraceContextFilter.currentTraceId(request);
        return traceId != null && !traceId.isBlank() ? traceId : TraceIdGenerator.generate();
    }

    private void writeError(HttpServletResponse response, int status, Enums.ErrorCode code,
                            String message, String requestId, String traceId) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (!response.containsHeader(RequestIdFilter.REQUEST_ID_HEADER)) {
            response.setHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        }
        if (!response.containsHeader(TraceContextFilter.TRACE_ID_HEADER)) {
            response.setHeader(TraceContextFilter.TRACE_ID_HEADER, traceId);
        }
        objectMapper.writeValue(response.getWriter(), ErrorResponse.builder()
                .error(code)
                .message(message)
                .requestId(requestId)
                .traceId(traceId)
                .build());
    }

    private boolean adminKeysMatch(String presented) {
        return MessageDigest.isEqual(sha256(adminApiKey), sha256(presented));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static String safeLogValue(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().replace('\r', ' ').replace('\n', ' ');
    }
}
