package io.runcycles.protocol.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecurityConfig")
class SecurityConfigTest {

    @Test
    void publicPathsShouldContainExpectedEntries() {
        assertThat(SecurityConfig.PUBLIC_PATHS).containsExactlyInAnyOrder(
                "/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/swagger-resources/**",
                "/v3/api-docs/**",
                "/webjars/**",
                "/favicon.ico",
                "/.well-known/**",
                "/actuator/health"
        );
    }

    @Test
    void publicPathsShouldIncludeSwaggerUi() {
        assertThat(SecurityConfig.PUBLIC_PATHS).anyMatch(p -> p.contains("swagger-ui"));
    }

    @Test
    void publicPathsShouldIncludeActuatorHealth() {
        assertThat(SecurityConfig.PUBLIC_PATHS).contains("/actuator/health");
    }

    @Test
    void publicPathsShouldIncludeApiDocs() {
        assertThat(SecurityConfig.PUBLIC_PATHS).anyMatch(p -> p.contains("api-docs"));
    }

    @Test
    void publicPathsShouldNotBeEmpty() {
        assertThat(SecurityConfig.PUBLIC_PATHS).isNotEmpty();
    }
}
