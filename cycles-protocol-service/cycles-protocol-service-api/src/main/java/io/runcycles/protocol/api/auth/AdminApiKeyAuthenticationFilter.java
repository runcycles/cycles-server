package io.runcycles.protocol.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.model.Enums;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Admin-on-behalf-of authentication for the runtime plane. Runs BEFORE
 * the tenant-scoped {@link ApiKeyAuthenticationFilter} and only takes
 * effect when:
 *
 * <ol>
 *   <li>the request path matches the dual-auth allowlist (a small set
 *       of reservation endpoints — see {@link #ADMIN_ALLOWED_EXACT} and
 *       {@link #ADMIN_ALLOWED_PATTERNS}), AND</li>
 *   <li>the request carries an {@code X-Admin-API-Key} header whose
 *       value matches the configured {@code admin.api-key} via
 *       constant-time comparison.</li>
 * </ol>
 *
 * <p>When both conditions hold, this filter sets an
 * {@link AdminApiKeyAuthentication} on the SecurityContext and
 * short-circuits {@link ApiKeyAuthenticationFilter} via
 * {@code request.setAttribute(ADMIN_AUTH_HANDLED_ATTR, true)} which
 * the api-key filter reads in {@code shouldNotFilter}.
 *
 * <p>If the request isn't on the allowlist OR doesn't have the admin
 * header, this filter is a no-op and the api-key filter handles auth
 * as usual. If the request IS on the allowlist and DOES have the
 * admin header but the key is wrong, returns 401 immediately —
 * doesn't fall through to the api-key path (which would be confusing
 * to debug because the user clearly intended admin auth).
 *
 * <p>Why a separate filter rather than extending the existing one:
 * keeps tenant-key flow untouched (zero risk of regression on the
 * runtime plane's hot path) and makes the dual-auth boundary explicit.
 *
 * <p>Spec: cycles-protocol-v0 revision 2026-04-13.
 */
@Component
public class AdminApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AdminApiKeyAuthenticationFilter.class);
    public static final String ADMIN_AUTH_HANDLED_ATTR = "io.runcycles.adminAuthHandled";

    // Exact-match: GET /v1/reservations
    private static final Set<String> ADMIN_ALLOWED_EXACT = Set.of(
        "GET:/v1/reservations"
    );

    // Path-template match (regex). Used because the existing
    // governance-admin AuthInterceptor's prefix-only matcher would be
    // too permissive here — POST:/v1/reservations/ would also match
    // /commit and /extend, which are deliberately NOT exposed to admin.
    private static final List<Pattern> ADMIN_ALLOWED_PATTERNS = List.of(
        Pattern.compile("^GET:/v1/reservations/[^/]+$"),                 // getReservation
        Pattern.compile("^POST:/v1/reservations/[^/]+/release$")         // releaseReservation
    );

    @Value("${admin.api-key:}")
    private String adminApiKey;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();
        String key = method + ":" + path;

        // Not on the admin allowlist? No-op, let the api-key filter run.
        if (!ADMIN_ALLOWED_EXACT.contains(key) && !matchesAdminPattern(key)) {
            filterChain.doFilter(request, response);
            return;
        }

        String adminHeader = request.getHeader("X-Admin-API-Key");
        if (adminHeader == null || adminHeader.isBlank()) {
            // Allowlisted endpoint but no admin header — fall through to
            // tenant-key auth. Tenants can still use their own keys on
            // these endpoints (this is dual-auth, not admin-only).
            filterChain.doFilter(request, response);
            return;
        }

        // Admin header present. Validate it. Anything other than a clean
        // match is rejected here — we don't fall through to the api-key
        // path because the user clearly intended admin auth and a fall-
        // through would produce a confusing "Missing API key" error.
        if (adminApiKey == null || adminApiKey.isBlank()) {
            LOG.error("X-Admin-API-Key header sent but admin.api-key not configured on this server");
            sendErrorResponse(request, response, 500,
                Enums.ErrorCode.INTERNAL_ERROR, "Server misconfiguration: admin API key not set");
            return;
        }
        if (!MessageDigest.isEqual(
                adminApiKey.getBytes(StandardCharsets.UTF_8),
                adminHeader.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("Invalid X-Admin-API-Key on {} {}", method, path);
            sendErrorResponse(request, response, 401,
                Enums.ErrorCode.UNAUTHORIZED, "Invalid admin API key");
            return;
        }

        // Authenticated as admin. Mark the request so the api-key filter
        // skips it, set the admin authentication on the security context,
        // and proceed.
        request.setAttribute(ADMIN_AUTH_HANDLED_ATTR, Boolean.TRUE);
        SecurityContextHolder.getContext().setAuthentication(
            new AdminApiKeyAuthentication(adminHeader));
        filterChain.doFilter(request, response);
    }

    private static boolean matchesAdminPattern(String methodPathKey) {
        for (Pattern p : ADMIN_ALLOWED_PATTERNS) {
            if (p.matcher(methodPathKey).matches()) return true;
        }
        return false;
    }

    private void sendErrorResponse(HttpServletRequest request,
                                   HttpServletResponse response,
                                   int status,
                                   Enums.ErrorCode code,
                                   String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Object reqIdAttr = request.getAttribute(
            io.runcycles.protocol.api.filter.RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = reqIdAttr != null ? reqIdAttr.toString() : UUID.randomUUID().toString();
        objectMapper.writeValue(response.getWriter(), Map.of(
            "error", code,
            "message", message,
            "request_id", requestId));
    }
}
