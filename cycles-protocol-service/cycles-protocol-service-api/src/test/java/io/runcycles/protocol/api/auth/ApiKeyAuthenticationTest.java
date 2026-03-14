package io.runcycles.protocol.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ApiKeyAuthentication")
class ApiKeyAuthenticationTest {

    @Test
    @DisplayName("should be authenticated after construction")
    void shouldBeAuthenticated() {
        ApiKeyAuthentication auth = new ApiKeyAuthentication(
                "cyc_live_key123", "tenant-acme", List.of("reservations:create"));

        assertThat(auth.isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("getCredentials should return the API key")
    void shouldReturnApiKeyAsCredentials() {
        ApiKeyAuthentication auth = new ApiKeyAuthentication(
                "cyc_live_key123", "tenant-acme", List.of("reservations:create"));

        assertThat(auth.getCredentials()).isEqualTo("cyc_live_key123");
    }

    @Test
    @DisplayName("getPrincipal should return the tenant ID")
    void shouldReturnTenantIdAsPrincipal() {
        ApiKeyAuthentication auth = new ApiKeyAuthentication(
                "cyc_live_key123", "tenant-acme", List.of("reservations:create"));

        assertThat(auth.getPrincipal()).isEqualTo("tenant-acme");
    }

    @Test
    @DisplayName("getTenantId should return the tenant ID")
    void shouldReturnTenantId() {
        ApiKeyAuthentication auth = new ApiKeyAuthentication(
                "cyc_live_key123", "tenant-acme", List.of("reservations:create"));

        assertThat(auth.getTenantId()).isEqualTo("tenant-acme");
    }

    @Test
    @DisplayName("getAuthorities should contain SimpleGrantedAuthority for each permission")
    void shouldMapPermissionsToAuthorities() {
        List<String> permissions = List.of("reservations:create", "reservations:read", "balances:read");
        ApiKeyAuthentication auth = new ApiKeyAuthentication(
                "cyc_live_key123", "tenant-acme", permissions);

        assertThat(auth.getAuthorities())
                .hasSize(3)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("reservations:create", "reservations:read", "balances:read");

        assertThat(auth.getAuthorities())
                .allSatisfy(a -> assertThat(a).isInstanceOf(SimpleGrantedAuthority.class));
    }

    @Test
    @DisplayName("should work with empty permissions list")
    void shouldHandleEmptyPermissions() {
        ApiKeyAuthentication auth = new ApiKeyAuthentication(
                "cyc_live_key123", "tenant-acme", List.of());

        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).isEmpty();
        assertThat(auth.getCredentials()).isEqualTo("cyc_live_key123");
        assertThat(auth.getPrincipal()).isEqualTo("tenant-acme");
        assertThat(auth.getTenantId()).isEqualTo("tenant-acme");
    }
}
