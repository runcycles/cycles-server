package io.runcycles.protocol.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.api.filter.TraceContextFilter;
import io.runcycles.protocol.data.util.TraceIdGenerator;
import io.runcycles.protocol.data.service.ApiKeyValidationService;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.auth.ApiKeyValidationResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    @Autowired
    private ApiKeyValidationService apiKeyValidationService;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String apiKey = request.getHeader("X-Cycles-API-Key");
        LOG.debug("Authorization filter: apiKey={}",
                apiKey != null && apiKey.length() > 8 ? apiKey.substring(0, 8) + "***" : "***");

        if (apiKey == null || apiKey.isBlank()) {
            String requestId = resolveRequestId(request);
            String traceId = resolveTraceId(request);
            LOG.warn("API key authentication failed: reason=missing_api_key method={} path={} request_id={} trace_id={}",
                    request.getMethod(), request.getRequestURI(), requestId, traceId);
            sendErrorResponse(response, "Missing API key", requestId, traceId);
            return;
        }
        ApiKeyValidationResponse result = apiKeyValidationService.isValid(apiKey);

        if (!result.isValid()) {
            String requestId = resolveRequestId(request);
            String traceId = resolveTraceId(request);
            LOG.warn("API key authentication failed: reason={} tenant={} key_id={} method={} path={} request_id={} trace_id={}",
                    result.getReason(), result.getTenantId(), result.getKeyId(),
                    request.getMethod(), request.getRequestURI(), requestId, traceId);
            sendErrorResponse(response, result.getReason(), requestId, traceId);
            return;
        }

        ApiKeyAuthentication authentication =
                new ApiKeyAuthentication(
                        apiKey,
                        result.getTenantId(),
                        result.getKeyId(),
                        result.getPermissions());

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Set tenant header before the response is committed
        response.setHeader("X-Cycles-Tenant", result.getTenantId());

        filterChain.doFilter(request, response);
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // v0.1.25.8 (cycles-protocol revision 2026-04-13): when the
        // AdminApiKeyAuthenticationFilter has already authenticated this
        // request via X-Admin-API-Key on a dual-auth-allowlisted path,
        // skip the tenant-key validation entirely. Without this skip,
        // the tenant-key filter would reject the request with 401
        // "Missing API key" because admin callers don't (and shouldn't)
        // send X-Cycles-API-Key.
        if (Boolean.TRUE.equals(request.getAttribute(
                AdminApiKeyAuthenticationFilter.ADMIN_AUTH_HANDLED_ATTR))) {
            return true;
        }
        String path = request.getRequestURI();
        for (String pattern : SecurityConfig.PUBLIC_PATHS) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object requestIdAttr = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestIdAttr != null ? requestIdAttr.toString() : UUID.randomUUID().toString();
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = TraceContextFilter.currentTraceId(request);
        return traceId != null && !traceId.isBlank() ? traceId : TraceIdGenerator.generate();
    }

    private void sendErrorResponse(HttpServletResponse response, String reason,
                                   String requestId, String traceId) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        if (!response.containsHeader(TraceContextFilter.TRACE_ID_HEADER)) {
            response.setHeader(TraceContextFilter.TRACE_ID_HEADER, traceId);
        }

        Map<String, Object> body = Map.of(
                "error", Enums.ErrorCode.UNAUTHORIZED,
                "message", reason,
                "request_id", requestId,
                "trace_id", traceId
        );

        objectMapper.writeValue(response.getWriter(), body);
    }
}
