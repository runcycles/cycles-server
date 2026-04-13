package io.runcycles.protocol.api.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Authentication marker for an admin operator acting against the runtime
 * plane via the X-Admin-API-Key header. Used by the dual-auth allowance
 * on a small set of reservation endpoints (list / get / release) added
 * in cycles-protocol revision 2026-04-13.
 *
 * <p>Distinct from {@link ApiKeyAuthentication} so controllers can ask
 * "am I being called as a tenant or as an admin?" via {@code instanceof}
 * (or via {@link io.runcycles.protocol.api.controller.BaseController#isAdminAuth()})
 * without overloading the meaning of {@code tenantId == null}.
 *
 * <p>Has no tenantId because admin operators have no effective tenant —
 * they specify the tenant explicitly via query param (listReservations)
 * or implicitly via reservation_id (getReservation, releaseReservation).
 *
 * <p>Authority list is a single {@code "admin:reservations:rw"} marker so
 * Spring Security's {@code .authenticated()} matcher accepts the request.
 * The actual authorization decision (which endpoints the admin can hit)
 * is enforced earlier in the filter via the path-pattern allowlist.
 */
public class AdminApiKeyAuthentication extends AbstractAuthenticationToken {
    private final String adminKey;

    public AdminApiKeyAuthentication(String adminKey) {
        super(List.of(new SimpleGrantedAuthority("admin:reservations:rw")));
        this.adminKey = adminKey;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return adminKey;
    }

    @Override
    public Object getPrincipal() {
        return "admin";
    }
}
