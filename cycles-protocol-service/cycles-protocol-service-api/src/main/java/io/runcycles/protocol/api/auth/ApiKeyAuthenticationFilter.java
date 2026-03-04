package io.runcycles.protocol.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
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
        LOG.info("Authorization filter request got: apiKey={}",apiKey);

        if (apiKey == null || apiKey.isBlank()) {
            LOG.error("Missing API key");
            sendErrorResponse(response, "Missing API key");
            return;
        }
        ApiKeyValidationResponse result = apiKeyValidationService.isValid(apiKey);

        if (!result.isValid()) {
            LOG.error("API key validation failed: result={},apiKey={}",result,apiKey);
            sendErrorResponse(response, result.getReason());
            return;
        }

        ApiKeyAuthentication authentication =
                new ApiKeyAuthentication(
                        apiKey,
                        result.getTenantId(),
                        result.getPermissions());

        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }
    private void sendErrorResponse(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = Map.of(
                "error", Enums.ErrorCode.UNAUTHORIZED,
                "message", reason,
                "requestId", UUID.randomUUID().toString()
        );

        objectMapper.writeValue(response.getWriter(), body);
    }
}
