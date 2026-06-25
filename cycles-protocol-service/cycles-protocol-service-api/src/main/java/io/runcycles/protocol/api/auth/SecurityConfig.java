package io.runcycles.protocol.api.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/prometheus",
            // CyclesEvidence retrieval is public by design (cycles-protocol-v0
            // getEvidence, security: []): the evidence_id is an unguessable
            // content-hash capability and the envelope is content-addressed + signed.
            "/v1/evidence/**",
            // The signer JWK Set is public (cycles-protocol-v0 getEvidenceJwks,
            // security: []): public keys only — the private signing key is never
            // served — and the set is itself the trust anchor consumers resolve.
            // API-base-relative (under /v1), per the spec's authority-scope rule.
            "/v1/.well-known/**"
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
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(adminFilter, ApiKeyAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
