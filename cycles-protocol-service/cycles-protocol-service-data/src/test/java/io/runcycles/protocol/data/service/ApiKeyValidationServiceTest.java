package io.runcycles.protocol.data.service;

import io.runcycles.protocol.data.repository.ApiKeyRepository;
import io.runcycles.protocol.model.auth.ApiKeyValidationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyValidationService")
class ApiKeyValidationServiceTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @InjectMocks private ApiKeyValidationService service;

    @Test
    void shouldReturnValidForActiveKey() {
        String key = "cyc_live_validkey12345678901234567890";
        when(apiKeyRepository.validate(key)).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("acme-corp")
                        .permissions(List.of("reservations:create")).build());

        ApiKeyValidationResponse result = service.isValid(key);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getTenantId()).isEqualTo("acme-corp");
    }

    @Test
    void shouldReturnInvalidForRevokedKey() {
        String key = "cyc_live_revokedkey1234567890123456";
        when(apiKeyRepository.validate(key)).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("acme-corp").reason("KEY_REVOKED").build());

        ApiKeyValidationResponse result = service.isValid(key);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).isEqualTo("KEY_REVOKED");
    }

    @Test
    void shouldReturnInvalidForExpiredKey() {
        String key = "cyc_live_expiredkey1234567890123456";
        when(apiKeyRepository.validate(key)).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("acme-corp").reason("KEY_EXPIRED").build());

        ApiKeyValidationResponse result = service.isValid(key);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).isEqualTo("KEY_EXPIRED");
    }

    @Test
    void shouldReturnInvalidForNotFoundKey() {
        String key = "cyc_live_unknownkey1234567890123456";
        when(apiKeyRepository.validate(key)).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("").reason("KEY_NOT_FOUND").build());

        ApiKeyValidationResponse result = service.isValid(key);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).isEqualTo("KEY_NOT_FOUND");
    }

    @Test
    void shouldReturnInvalidForSuspendedTenant() {
        String key = "cyc_live_suspendedtenant123456789012";
        when(apiKeyRepository.validate(key)).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("acme-corp").reason("TENANT_SUSPENDED").build());

        ApiKeyValidationResponse result = service.isValid(key);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).isEqualTo("TENANT_SUSPENDED");
    }
}
