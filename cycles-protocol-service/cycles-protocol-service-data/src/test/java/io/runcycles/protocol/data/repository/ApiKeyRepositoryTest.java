package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.protocol.model.auth.ApiKey;
import io.runcycles.protocol.model.auth.ApiKeyStatus;
import io.runcycles.protocol.model.auth.ApiKeyValidationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyRepository")
class ApiKeyRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @InjectMocks private ApiKeyRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() throws Exception {
        var field = ApiKeyRepository.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(repository, objectMapper);
    }

    // ---- extractPrefix ----
    // Logic: indexOf('_') → substring(0, min(idx+6, len)); no underscore → substring(0, min(10, len))

    @Nested
    @DisplayName("extractPrefix")
    class ExtractPrefix {

        @Test
        void shouldExtractPrefixWithUnderscore() {
            // idx=3, idx+6=9 → "cyc_live_"
            assertThat(repository.extractPrefix("cyc_live_validkey12345678901234567890"))
                    .isEqualTo("cyc_live_");
        }

        @Test
        void shouldExtractPrefixWithoutUnderscore() {
            // no underscore → substring(0, min(10, 11)) = "shortkey12"
            assertThat(repository.extractPrefix("shortkey123"))
                    .isEqualTo("shortkey12");
        }

        @Test
        void shouldHandleShortKeyWithUnderscore() {
            // idx=1, idx+6=7 → min(7, 5) = 5 → "k_abc"
            assertThat(repository.extractPrefix("k_abc"))
                    .isEqualTo("k_abc");
        }

        @Test
        void shouldHandleVeryShortKeyWithoutUnderscore() {
            // no underscore → substring(0, min(10, 3)) = "abc"
            assertThat(repository.extractPrefix("abc"))
                    .isEqualTo("abc");
        }
    }

    // ---- verifyKey ----

    @Nested
    @DisplayName("verifyKey")
    class VerifyKey {

        @Test
        void shouldReturnTrueForMatchingKey() {
            String secret = "cyc_live_test123456";
            String hash = BCrypt.hashpw(secret, BCrypt.gensalt());
            assertThat(repository.verifyKey(secret, hash)).isTrue();
        }

        @Test
        void shouldReturnFalseForMismatchedKey() {
            String hash = BCrypt.hashpw("correct_secret", BCrypt.gensalt());
            assertThat(repository.verifyKey("wrong_secret", hash)).isFalse();
        }

        @Test
        void shouldReturnFalseForInvalidHash() {
            assertThat(repository.verifyKey("any_key", "not-a-bcrypt-hash")).isFalse();
        }

        @Test
        void shouldReturnFalseForNullHash() {
            assertThat(repository.verifyKey("any_key", null)).isFalse();
        }
    }

    // ---- validate ----

    @Nested
    @DisplayName("validate")
    class Validate {

        private final String secret = "cyc_live_testkey1234567890";
        // extractPrefix: idx=3 (first '_'), idx+6=9 → "cyc_live_"
        private final String prefix = "cyc_live_";
        private final String keyId = "key-001";
        private final String hash = BCrypt.hashpw(secret, BCrypt.gensalt());

        private void stubJedis() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
        }

        @Test
        void shouldReturnValidForActiveKey() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .permissions(List.of("reservations:create"))
                    .build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));
            when(jedis.get("tenant:acme-corp")).thenReturn(null);

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getTenantId()).isEqualTo("acme-corp");
            assertThat(result.getKeyId()).isEqualTo(keyId);
            assertThat(result.getPermissions()).containsExactly("reservations:create");
        }

        @Test
        void shouldReturnInvalidWhenPrefixNotFound() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(null);

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("KEY_NOT_FOUND");
        }

        @Test
        void shouldReturnInvalidWhenKeyDataNotFound() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);
            when(jedis.get("apikey:" + keyId)).thenReturn(null);

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("KEY_NOT_FOUND");
        }

        @Test
        void shouldReturnInvalidForRevokedKey() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.REVOKED).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("KEY_REVOKED");
        }

        @Test
        void shouldReturnInvalidForExpiredKey() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .expiresAt(Instant.now().minusSeconds(3600)).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("KEY_EXPIRED");
        }

        @Test
        void shouldReturnInvalidForWrongSecret() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            String differentHash = BCrypt.hashpw("different_secret", BCrypt.gensalt());
            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(differentHash).status(ApiKeyStatus.ACTIVE).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("INVALID_KEY");
        }

        @Test
        void shouldReturnInvalidWhenTenantBlank() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("KEY_NOT_OWNED_BY_TENANT");
        }

        @Test
        void shouldReturnInvalidForSuspendedTenant() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .permissions(Collections.emptyList()).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));
            when(jedis.get("tenant:acme-corp")).thenReturn("{\"status\":\"SUSPENDED\"}");

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("TENANT_SUSPENDED");
        }

        @Test
        void shouldReturnInvalidForClosedTenant() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .permissions(Collections.emptyList()).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));
            when(jedis.get("tenant:acme-corp")).thenReturn("{\"status\":\"CLOSED\"}");

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("TENANT_CLOSED");
        }

        @Test
        void shouldReturnInternalErrorOnException() {
            when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis down"));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("INTERNAL_ERROR");
        }
    }
}
