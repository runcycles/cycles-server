package io.runcycles.protocol.api.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    public static final String[] PUBLIC_PATHS = {
            "/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/swagger-resources/**",
            "/v3/api-docs/**",
            "/webjars/**",
            "/favicon.ico",
            "/.well-known/**",
            "/actuator/health",
            "/actuator/prometheus"
    };
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AdminApiKeyAuthenticationFilter adminFilter,
            ApiKeyAuthenticationFilter apiKeyFilter) throws Exception {

        // Filter order matters: AdminApiKeyAuthenticationFilter runs FIRST.
        // On dual-auth-allowlisted paths with X-Admin-API-Key, it sets the
        // admin authentication and marks the request so apiKeyFilter
        // skips it. On every other request it's a no-op and apiKeyFilter
        // takes over (existing tenant-key behavior unchanged).
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(adminFilter, ApiKeyAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
