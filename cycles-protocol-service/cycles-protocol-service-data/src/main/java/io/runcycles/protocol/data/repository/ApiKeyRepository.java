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
    private static final long CACHE_TTL_MS = 60_000L; // 60 seconds
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

    private final Cache<String, ApiKeyValidationResponse> validationCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(CACHE_TTL_MS))
            .maximumSize(5_000)
            .build();

    public ApiKeyValidationResponse validate(String keySecret) {
        // Check in-memory cache to avoid BCrypt on every request
        String cacheKey = hashKeyForCache(keySecret);
        if (cacheKey != null) {
            ApiKeyValidationResponse cached = validationCache.getIfPresent(cacheKey);
            if (cached != null) {
                LOG.debug("API key cache hit");
                return cached;
            }
        }

        ApiKeyValidationResponse result = validateFromRedis(keySecret);

        // Cache the result (both valid and invalid responses)
        if (cacheKey != null) {
            validationCache.put(cacheKey, result);
        }

        return result;
    }

    private ApiKeyValidationResponse validateFromRedis(String keySecret) {
        try (Jedis jedis = jedisPool.getResource()) {
            String prefix = extractPrefix(keySecret);
            String keyId = jedis.get("apikey:lookup:" + prefix);
            if (keyId == null) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_FOUND").build();
            }
            String data = jedis.get("apikey:" + keyId);
            if (data == null) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_FOUND").build();
            }
            ApiKey key = objectMapper.readValue(data, ApiKey.class);
            if (key.getStatus() != ApiKeyStatus.ACTIVE) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId() != null ? key.getTenantId() : "").reason("KEY_" + key.getStatus()).build();
            }
            if (key.getExpiresAt() != null && Instant.now().isAfter(key.getExpiresAt())) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId() != null ? key.getTenantId() : "").reason("KEY_EXPIRED").build();
            }
            if (!verifyKey(keySecret, key.getKeyHash())) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId() != null ? key.getTenantId() : "").reason("INVALID_KEY").build();
            }
            if (key.getTenantId() == null || key.getTenantId().isBlank()){
                return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_OWNED_BY_TENANT").build();
            }
            // Check tenant status: reject if SUSPENDED or CLOSED
            String tenantData = jedis.get("tenant:" + key.getTenantId());
            if (tenantData != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tenantMap = objectMapper.readValue(tenantData, Map.class);
                String tenantStatus = (String) tenantMap.get("status");
                if ("SUSPENDED".equals(tenantStatus)) {
                    return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId()).reason("TENANT_SUSPENDED").build();
                }
                if ("CLOSED".equals(tenantStatus)) {
                    return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId()).reason("TENANT_CLOSED").build();
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
        } catch (Exception e) {
            LOG.error("API key validation failed", e);
            return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("INTERNAL_ERROR").build();
        }
    }

    public boolean verifyKey(String keySecret, String hash) {
        try {
            return BCrypt.checkpw(keySecret, hash);
        } catch (Exception e) {
            LOG.warn("BCrypt verification failed", e);
            return false;
        }
    }

    private static final int PREFIX_LENGTH = 14; // "cyc_live_" (9) + 5 chars from random part
    public String extractPrefix(String keySecret) {
        return keySecret.substring(0, Math.min(PREFIX_LENGTH, keySecret.length()));
    }

    private String hashKeyForCache(String keySecret) {
        try {
            MessageDigest digest = SHA256_DIGEST.get();
            digest.reset();
            byte[] hash = digest.digest(keySecret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return null;
        }
    }

}
