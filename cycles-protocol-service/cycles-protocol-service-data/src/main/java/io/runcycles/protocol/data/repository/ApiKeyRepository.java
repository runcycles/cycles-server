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

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

@Repository
public class ApiKeyRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyRepository.class);

    @Autowired
    private JedisPool jedisPool;
    @Autowired
    private ObjectMapper objectMapper;

    public ApiKeyValidationResponse validate(String keySecret) {
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
            return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("INTERNAL_ERROR").build();
        }
    }
    public boolean verifyKey(String keySecret, String hash) {
        try {
            return BCrypt.checkpw(keySecret, hash);
        } catch (Exception e) {
            return false;
        }
    }
    private static final int PREFIX_LENGTH = 14; // "cyc_live_" (9) + 5 chars from random part
    public String extractPrefix(String keySecret) {
        return keySecret.substring(0, Math.min(PREFIX_LENGTH, keySecret.length()));
    }
}
