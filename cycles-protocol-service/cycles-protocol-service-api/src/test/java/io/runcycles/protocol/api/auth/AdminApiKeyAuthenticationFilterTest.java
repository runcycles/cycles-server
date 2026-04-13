package io.runcycles.protocol.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link AdminApiKeyAuthenticationFilter}. Verifies the
 * dual-auth allowlist (exact + pattern), constant-time key compare,
 * fall-through behavior on non-allowlisted paths, and the
 * {@link AdminApiKeyAuthenticationFilter#ADMIN_AUTH_HANDLED_ATTR}
 * marker that short-circuits the downstream tenant-key filter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminApiKeyAuthenticationFilter")
class AdminApiKeyAuthenticationFilterTest {

    @InjectMocks private AdminApiKeyAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(filter, "adminApiKey", "admin-secret");
        ReflectionTestUtils.setField(filter, "objectMapper", new ObjectMapper());
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    // ─── Allowlist matching ───────────────────────────────────────

    @Test
    void allowlistedExactPath_withValidAdminKey_setsAdminAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/reservations");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
            .isInstanceOf(AdminApiKeyAuthentication.class);
        assertThat(request.getAttribute(AdminApiKeyAuthenticationFilter.ADMIN_AUTH_HANDLED_ATTR))
            .isEqualTo(Boolean.TRUE);
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void allowlistedPatternPath_getById_withValidAdminKey_setsAdminAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/reservations/res-abc-123");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
            .isInstanceOf(AdminApiKeyAuthentication.class);
    }

    @Test
    void allowlistedPatternPath_release_withValidAdminKey_setsAdminAuth() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/reservations/res-abc/release");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
            .isInstanceOf(AdminApiKeyAuthentication.class);
    }

    // The deliberately-not-exposed endpoints — commit and extend — must
    // NOT match the allowlist. Even with a valid admin key, they fall
    // through to the tenant-key filter (which will reject because the
    // request lacks X-Cycles-API-Key).
    @Test
    void commit_notInAllowlist_fallsThrough() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/reservations/res-abc/commit");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(AdminApiKeyAuthenticationFilter.ADMIN_AUTH_HANDLED_ATTR))
            .isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void extend_notInAllowlist_fallsThrough() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/reservations/res-abc/extend");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void create_notInAllowlist_fallsThrough() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/reservations");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ─── Header presence ──────────────────────────────────────────

    @Test
    void allowlistedPath_noAdminHeader_fallsThrough() throws Exception {
        // Tenants can still hit allowlisted endpoints with their own keys —
        // this is dual-auth, not admin-only. No admin header → no-op.
        request.setMethod("GET");
        request.setRequestURI("/v1/reservations");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void allowlistedPath_blankAdminHeader_fallsThrough() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/reservations");
        request.addHeader("X-Admin-API-Key", "");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ─── Key validation ───────────────────────────────────────────

    @Test
    void allowlistedPath_wrongAdminKey_returns401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/reservations");
        request.addHeader("X-Admin-API-Key", "wrong-key");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // Did NOT fall through — admin intent was clear, reject explicitly.
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void allowlistedPath_validHeaderButServerNotConfigured_returns500() throws Exception {
        ReflectionTestUtils.setField(filter, "adminApiKey", "");
        request.setMethod("GET");
        request.setRequestURI("/v1/reservations");
        request.addHeader("X-Admin-API-Key", "anything");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        verify(chain, never()).doFilter(request, response);
    }

    // ─── Path normalization ───────────────────────────────────────

    @Test
    void releasePath_withTrailingSlash_doesNotMatchPattern() throws Exception {
        // The regex requires no trailing characters after /release. A
        // trailing slash falls through to the tenant filter — fine
        // because POST /v1/reservations/x/release/ isn't a valid
        // server route either.
        request.setMethod("POST");
        request.setRequestURI("/v1/reservations/res-abc/release/");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unrelatedPath_isNoOp() throws Exception {
        // /v1/budgets isn't a runtime-plane endpoint at all; filter
        // should fall through.
        request.setMethod("GET");
        request.setRequestURI("/v1/budgets");
        request.addHeader("X-Admin-API-Key", "admin-secret");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }
}
