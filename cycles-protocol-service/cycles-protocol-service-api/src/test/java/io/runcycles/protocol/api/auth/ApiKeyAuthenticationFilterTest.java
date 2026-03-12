package io.runcycles.protocol.api.auth;

import io.runcycles.protocol.data.service.ApiKeyValidationService;
import io.runcycles.protocol.model.auth.ApiKeyValidationResponse;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyAuthenticationFilter")
class ApiKeyAuthenticationFilterTest {

    @Mock private ApiKeyValidationService apiKeyValidationService;
    @Mock private FilterChain filterChain;
    @InjectMocks private ApiKeyAuthenticationFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() throws Exception {
        // Inject ObjectMapper via reflection (field injection)
        var field = ApiKeyAuthenticationFilter.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(filter, new ObjectMapper());

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReject401WhenApiKeyMissing() throws Exception {
        // No header set
        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(filterChain);
    }

    @Test
    void shouldReject401WhenApiKeyBlank() throws Exception {
        request.addHeader("X-Cycles-API-Key", "   ");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(filterChain);
    }

    @Test
    void shouldReject401WhenApiKeyInvalid() throws Exception {
        request.addHeader("X-Cycles-API-Key", "cyc_live_badkey123456789");
        when(apiKeyValidationService.isValid(anyString()))
                .thenReturn(ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("").reason("INVALID_KEY").build());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(filterChain);
    }

    @Test
    void shouldAuthenticateValidKey() throws Exception {
        request.addHeader("X-Cycles-API-Key", "cyc_live_validkey12345678901234567890");
        when(apiKeyValidationService.isValid(anyString()))
                .thenReturn(ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("acme-corp")
                        .permissions(List.of("reservations:create")).build());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("acme-corp");
    }

    @Test
    void shouldSetTenantResponseHeader() throws Exception {
        request.addHeader("X-Cycles-API-Key", "cyc_live_validkey12345678901234567890");
        when(apiKeyValidationService.isValid(anyString()))
                .thenReturn(ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("acme-corp")
                        .permissions(List.of("reservations:create")).build());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader("X-Cycles-Tenant")).isEqualTo("acme-corp");
    }

    @Test
    void shouldSkipPublicPaths() {
        request.setRequestURI("/swagger-ui.html");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        request.setRequestURI("/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        request.setRequestURI("/v3/api-docs/something");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotSkipApiPaths() {
        request.setRequestURI("/v1/reservations");
        assertThat(filter.shouldNotFilter(request)).isFalse();

        request.setRequestURI("/v1/balances");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }
}
