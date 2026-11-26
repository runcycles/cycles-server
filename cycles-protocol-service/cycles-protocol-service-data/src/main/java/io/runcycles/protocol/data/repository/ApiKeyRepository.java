package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.model.auth.ApiKey;
import io.runcycles.protocol.model.auth.ApiKeyStatus;
import io.runcycles.protocol.model.auth.ApiKeyValidationResponse;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Repository
public class ApiKeyRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyRepository.class);
    private static final long BCRYPT_CACHE_TTL_MS = 60_000L; // 60 seconds
    /** Validation could not be performed (store unavailable / unreadable record) - a server fault, not a bad key. */
    public static final String REASON_INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    });

    @Autowired
    private JedisPool jedisPool;
    @Autowired
    private ObjectMapper objectMapper;

    private final Cache<String, Boolean> bcryptVerificationCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(BCRYPT_CACHE_TTL_MS))
            .maximumSize(5_000)
            .build();

    public ApiKeyValidationResponse validate(String keySecret) {
        String prefix = null;
        try {
            prefix = extractPrefix(keySecret);
            // One pooled connection for every read (lookup, key record, tenant status), and it
            // is released BEFORE the BCrypt verification so a slow hash never pins the pool.
            ApiKey key;
            String tenantData = null;
            try (Jedis jedis = jedisPool.getResource()) {
                String keyId = jedis.get("apikey:lookup:" + prefix);
                if (keyId == null) {
                    return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_FOUND").build();
                }
                String data = jedis.get("apikey:" + keyId);
                if (data == null) {
                    return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_FOUND").build();
                }
                key = objectMapper.readValue(data, ApiKey.class);
                if (key.getTenantId() != null && !key.getTenantId().isBlank()) {
                    tenantData = jedis.get("tenant:" + key.getTenantId());
                }
            }

            if (key.getStatus() != ApiKeyStatus.ACTIVE) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId() != null ? key.getTenantId() : "").reason("KEY_" + key.getStatus()).build();
            }
            if (key.getExpiresAt() != null && Instant.now().isAfter(key.getExpiresAt())) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId() != null ? key.getTenantId() : "").reason("KEY_EXPIRED").build();
            }
            if (!verifyKeyWithCache(keySecret, key.getKeyHash())) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId() != null ? key.getTenantId() : "").reason("INVALID_KEY").build();
            }
            if (key.getTenantId() == null || key.getTenantId().isBlank()){
                return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_OWNED_BY_TENANT").build();
            }
            // Tenant status. CLOSED is terminal: reject at auth. SUSPENDED is NOT rejected
            // here - the governance spec makes a suspended tenant read-only with "no new
            // reservations", while "existing active reservations can still commit/release".
            // Rejecting the key would also block those commits/releases (and all reads), so
            // the suspended restriction is enforced where the operation is known:
            // reserve, event and extend refuse fresh mutations; dry_run and decide answer DENY.
            if (tenantData != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tenantMap = objectMapper.readValue(tenantData, Map.class);
                Object tenantStatus = tenantMap.get("status");
                if ("CLOSED".equals(tenantStatus)) {
                    return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId()).reason("TENANT_CLOSED").build();
                }
                if (!"ACTIVE".equals(tenantStatus) && !"SUSPENDED".equals(tenantStatus)) {
                    LOG.error("API key validation failed: invalid tenant status");
                    return internalError();
                }
            }
            return ApiKeyValidationResponse.builder()
                    .valid(true)
                    .tenantId(key.getTenantId())
                    .keyId(key.getKeyId())
                    .permissions(key.getPermissions() != null ? key.getPermissions() : Collections.emptyList())
                    .scopeFilter(key.getScopeFilter())
                    .expiresAt(key.getExpiresAt())
                    .build();
        } catch (JedisConnectionException e) {
            LOG.warn("API key validation failed: reason=redis_unavailable key_prefix_present={} key_prefix_length={} error_type={} error={}",
                    prefix != null, prefix != null ? prefix.length() : null,
                    e.getClass().getSimpleName(), e.getMessage());
            return internalError();
        } catch (Exception e) {
            LOG.error("API key validation failed: key_prefix_present={} key_prefix_length={} error={}",
                    prefix != null, prefix != null ? prefix.length() : null, e.toString(), e);
            return internalError();
        }
    }

    private boolean verifyKeyWithCache(String keySecret, String hash) {
        String cacheKey = hashKeyForCache(keySecret, hash);
        if (cacheKey != null) {
            Boolean cached = bcryptVerificationCache.getIfPresent(cacheKey);
            if (cached != null) {
                LOG.debug("API key bcrypt cache hit");
                return cached;
            }
        }
        boolean verified = verifyKey(keySecret, hash);
        if (cacheKey != null) {
            bcryptVerificationCache.put(cacheKey, verified);
        }
        return verified;
    }

    public boolean verifyKey(String keySecret, String hash) {
        try {
            return BCrypt.checkpw(keySecret, hash);
        } catch (Exception e) {
            LOG.warn("BCrypt verification failed: stored_hash_present={} stored_hash_length={} error={}",
                    hash != null, hash != null ? hash.length() : null, e.toString(), e);
            return false;
        }
    }

    private static final int PREFIX_LENGTH = 14; // "cyc_live_" (9) + 5 chars from random part
    public String extractPrefix(String keySecret) {
        return keySecret.substring(0, Math.min(PREFIX_LENGTH, keySecret.length()));
    }

    private String hashKeyForCache(String keySecret, String storedHash) {
        try {
            MessageDigest digest = SHA256_DIGEST.get();
            digest.reset();
            digest.update(keySecret.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            if (storedHash != null) {
                digest.update(storedHash.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return null;
        }
    }

    private static ApiKeyValidationResponse internalError() {
        return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason(REASON_INTERNAL_ERROR).build();
    }

}
