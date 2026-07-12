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
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
        setField(repository, "objectMapper", objectMapper);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = ApiKeyRepository.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ---- extractPrefix ----
    // Logic: first 14 characters (PREFIX_LENGTH), matching admin's KeyService

    @Nested
    @DisplayName("extractPrefix")
    class ExtractPrefix {

        @Test
        void shouldExtractFirst14CharsAsPrefix() {
            // "cyc_live_valid" = first 14 chars
            assertThat(repository.extractPrefix("cyc_live_validkey12345678901234567890"))
                    .isEqualTo("cyc_live_valid");
        }

        @Test
        void shouldExtract14CharsRegardlessOfUnderscores() {
            assertThat(repository.extractPrefix("shortkey123456789"))
                    .isEqualTo("shortkey123456");
        }

        @Test
        void shouldHandleShortKeyGracefully() {
            assertThat(repository.extractPrefix("k_abc"))
                    .isEqualTo("k_abc");
        }

        @Test
        void shouldHandleVeryShortKey() {
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
        // extractPrefix: first 14 chars → "cyc_live_testk"
        private final String prefix = "cyc_live_testk";
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
            // Lookup connection is released before BCrypt, then a short-lived
            // second connection performs the tenant-status read.
            verify(jedis, times(2)).close();
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

        @Test
        void shouldReturnInternalErrorOnRedisConnectionException() {
            when(jedisPool.getResource()).thenThrow(new JedisConnectionException("Redis down"));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("INTERNAL_ERROR");
        }

        @Test
        void shouldReturnEmptyTenantIdWhenRevokedKeyHasNullTenant() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId(null)
                    .keyHash(hash).status(ApiKeyStatus.REVOKED).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getTenantId()).isEmpty();
            assertThat(result.getReason()).isEqualTo("KEY_REVOKED");
        }

        @Test
        void shouldReturnEmptyTenantIdWhenExpiredKeyHasNullTenant() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId(null)
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .expiresAt(Instant.now().minusSeconds(3600)).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getTenantId()).isEmpty();
            assertThat(result.getReason()).isEqualTo("KEY_EXPIRED");
        }

        @Test
        void shouldReturnEmptyTenantIdWhenInvalidKeyHasNullTenant() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            String differentHash = BCrypt.hashpw("different_secret", BCrypt.gensalt());
            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId(null)
                    .keyHash(differentHash).status(ApiKeyStatus.ACTIVE).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getTenantId()).isEmpty();
            assertThat(result.getReason()).isEqualTo("INVALID_KEY");
        }

        @Test
        void shouldReturnInvalidWhenTenantNull() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId(null)
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getReason()).isEqualTo("KEY_NOT_OWNED_BY_TENANT");
        }

        @Test
        void shouldReturnEmptyPermissionsWhenNull() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .permissions(null) // null permissions
                    .build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));
            when(jedis.get("tenant:acme-corp")).thenReturn(null);

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getPermissions()).isEmpty();
        }

        @Test
        void shouldRevalidateStatusOnSecondCallAfterBcryptCacheHit() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey activeKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .permissions(List.of("read")).build();
            ApiKey revokedKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.REVOKED)
                    .permissions(List.of("read")).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(
                    objectMapper.writeValueAsString(activeKey),
                    objectMapper.writeValueAsString(revokedKey));
            when(jedis.get("tenant:acme-corp")).thenReturn(null);

            // First call — hits Redis
            ApiKeyValidationResponse result1 = repository.validate(secret);
            assertThat(result1.isValid()).isTrue();

            // Second call still reads Redis status, so revocation is not stale.
            ApiKeyValidationResponse result2 = repository.validate(secret);
            assertThat(result2.isValid()).isFalse();
            assertThat(result2.getReason()).isEqualTo("KEY_REVOKED");

            verify(jedisPool, times(3)).getResource();
        }

        @Test
        void shouldReuseSuccessfulBcryptVerificationWhileStillReadingRedis() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .permissions(List.of("read")).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));
            when(jedis.get("tenant:acme-corp")).thenReturn(null);

            ApiKeyValidationResponse result1 = repository.validate(secret);
            ApiKeyValidationResponse result2 = repository.validate(secret);

            assertThat(result1.isValid()).isTrue();
            assertThat(result2.isValid()).isTrue();
            verify(jedisPool, times(4)).getResource();
            verify(jedis, times(2)).get("apikey:" + keyId);
        }

        @Test
        void shouldReuseFailedBcryptVerificationUntilStoredHashChanges() throws Exception {
            ApiKeyRepository spyRepository = spy(new ApiKeyRepository());
            setField(spyRepository, "objectMapper", objectMapper);
            setField(spyRepository, "jedisPool", jedisPool);

            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            String staleHash = "stored-hash-v1";
            String rotatedHash = "stored-hash-v2";
            ApiKey staleKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(staleHash).status(ApiKeyStatus.ACTIVE)
                    .permissions(List.of("read")).build();
            ApiKey rotatedKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(rotatedHash).status(ApiKeyStatus.ACTIVE)
                    .permissions(List.of("read")).build();
            when(jedis.get("apikey:" + keyId)).thenReturn(
                    objectMapper.writeValueAsString(staleKey),
                    objectMapper.writeValueAsString(staleKey),
                    objectMapper.writeValueAsString(rotatedKey));
            when(jedis.get("tenant:acme-corp")).thenReturn(null);
            doReturn(false).when(spyRepository).verifyKey(secret, staleHash);
            doReturn(true).when(spyRepository).verifyKey(secret, rotatedHash);

            ApiKeyValidationResponse result1 = spyRepository.validate(secret);
            ApiKeyValidationResponse result2 = spyRepository.validate(secret);
            ApiKeyValidationResponse result3 = spyRepository.validate(secret);

            assertThat(result1.isValid()).isFalse();
            assertThat(result1.getReason()).isEqualTo("INVALID_KEY");
            assertThat(result2.isValid()).isFalse();
            assertThat(result2.getReason()).isEqualTo("INVALID_KEY");
            assertThat(result3.isValid()).isTrue();
            verify(spyRepository, times(1)).verifyKey(secret, staleHash);
            verify(spyRepository, times(1)).verifyKey(secret, rotatedHash);
            verify(jedisPool, times(4)).getResource();
            verify(jedis, times(3)).get("apikey:" + keyId);
        }

        @Test
        void shouldReturnValidForKeyWithNonNullNonExpiredExpiresAt() throws Exception {
            stubJedis();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(keyId);

            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .expiresAt(Instant.now().plusSeconds(3600)) // not expired
                    .permissions(List.of("read"))
                    .build();
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));
            when(jedis.get("tenant:acme-corp")).thenReturn(null);

            ApiKeyValidationResponse result = repository.validate(secret);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        void shouldNotCacheKeyNotFoundResponse() throws Exception {
            stubJedis();
            ApiKey apiKey = ApiKey.builder()
                    .keyId(keyId).tenantId("acme-corp")
                    .keyHash(hash).status(ApiKeyStatus.ACTIVE)
                    .permissions(List.of("read"))
                    .build();
            when(jedis.get("apikey:lookup:" + prefix)).thenReturn(null, keyId);
            when(jedis.get("apikey:" + keyId)).thenReturn(objectMapper.writeValueAsString(apiKey));
            when(jedis.get("tenant:acme-corp")).thenReturn(null);

            // First call — key not found.
            ApiKeyValidationResponse result1 = repository.validate(secret);
            assertThat(result1.isValid()).isFalse();
            assertThat(result1.getReason()).isEqualTo("KEY_NOT_FOUND");

            // Second call sees the newly-created key instead of replaying a cached miss.
            ApiKeyValidationResponse result2 = repository.validate(secret);
            assertThat(result2.isValid()).isTrue();

            verify(jedisPool, times(3)).getResource();
        }
    }
}
