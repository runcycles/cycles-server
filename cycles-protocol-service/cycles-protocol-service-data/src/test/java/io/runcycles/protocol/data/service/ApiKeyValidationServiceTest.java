package io.runcycles.protocol.data.service;

import io.runcycles.protocol.data.repository.ApiKeyRepository;
import io.runcycles.protocol.model.auth.ApiKeyValidationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyValidationService")
class ApiKeyValidationServiceTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private Logger logger;
    private ApiKeyValidationService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyValidationService(apiKeyRepository, logger);
    }

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

    @Test
    void shouldHandleShortToken() {
        String key = "short";
        when(apiKeyRepository.validate(key)).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("").reason("KEY_NOT_FOUND").build());

        ApiKeyValidationResponse result = service.isValid(key);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).isEqualTo("KEY_NOT_FOUND");
    }

    @Test
    void shouldHandleNullToken() {
        ApiKeyValidationResponse response = ApiKeyValidationResponse.builder()
            .valid(false).tenantId("").reason("KEY_NOT_FOUND").build();
        when(apiKeyRepository.validate(null)).thenReturn(response);
        when(logger.isDebugEnabled()).thenReturn(true);

        ApiKeyValidationResponse result = service.isValid(null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).isEqualTo("KEY_NOT_FOUND");
        verify(logger).debug(
            "API key validation: api_key_present={} api_key_length={} valid={} tenant={} key_id={} reason={}",
            false, null, false, "", null, "KEY_NOT_FOUND");
    }

    @Test
    void shouldHandleBlankToken() {
        when(apiKeyRepository.validate(" ")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("").reason("KEY_NOT_FOUND").build());
        when(logger.isDebugEnabled()).thenReturn(true);

        assertThat(service.isValid(" ").isValid()).isFalse();
        verify(logger).debug(
            "API key validation: api_key_present={} api_key_length={} valid={} tenant={} key_id={} reason={}",
            false, 1, false, "", null, "KEY_NOT_FOUND");
    }

    @Test
    void safeLogValueFlattensLineBreaks() {
        assertThat(ApiKeyValidationService.safeLogValue("tenant\r\nfake=1"))
                .isEqualTo("tenant  fake=1");
        assertThat(ApiKeyValidationService.safeLogValue(null)).isNull();
    }

    @Test
    void validationWorksWithDebugLoggingDisabledAndEnabled() {
        String key = "cyc_live_loggingkey1234567890123456";
        ApiKeyValidationResponse response = ApiKeyValidationResponse.builder()
            .valid(true).tenantId("tenant\r\nfake=1").keyId("key\r\n1")
            .reason("ok\nx").build();
        when(apiKeyRepository.validate(key)).thenReturn(response);
        when(logger.isDebugEnabled()).thenReturn(false, true);

        assertThat(service.isValid(key)).isSameAs(response);
        assertThat(service.isValid(key)).isSameAs(response);

        verify(logger, times(2)).isDebugEnabled();
        verify(logger).debug(
            "API key validation: api_key_present={} api_key_length={} valid={} tenant={} key_id={} reason={}",
            true, key.length(), true, "tenant  fake=1", "key  1", "ok x");
    }
}
