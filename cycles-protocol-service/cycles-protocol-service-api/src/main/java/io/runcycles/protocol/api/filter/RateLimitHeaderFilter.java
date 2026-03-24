package io.runcycles.protocol.api.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Cycles Protocol v0.1.24 - Adds X-RateLimit-Remaining and X-RateLimit-Reset
 * headers to all /v1/ API responses for spec compliance.
 *
 * Rate limiting is not enforced in v0; headers use sentinel values (-1 / 0)
 * to signal "unlimited" to callers.
 */
@Component
@Order(2)
public class RateLimitHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/v1/")) {
            response.setHeader("X-RateLimit-Remaining", "-1");
            response.setHeader("X-RateLimit-Reset", "0");
        }
        filterChain.doFilter(request, response);
    }
}
