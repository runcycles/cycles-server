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
                return ApiKeyValidationResponse.builder().valid(false).reason("KEY_NOT_FOUND").build();
            }
            String data = jedis.get("apikey:" + keyId);
            ApiKey key = objectMapper.readValue(data, ApiKey.class);
            if (key.getStatus() != ApiKeyStatus.ACTIVE) {
                return ApiKeyValidationResponse.builder().valid(false).reason("KEY_" + key.getStatus()).build();
            }
            if (key.getExpiresAt() != null && Instant.now().isAfter(key.getExpiresAt())) {
                return ApiKeyValidationResponse.builder().valid(false).reason("KEY_EXPIRED").build();
            }
            if (!verifyKey(keySecret, key.getKeyHash())) {
                return ApiKeyValidationResponse.builder().valid(false).reason("INVALID_KEY").build();
            }
            key.setLastUsedAt(Instant.now());
            jedis.set("apikey:" + keyId, objectMapper.writeValueAsString(key));
            return ApiKeyValidationResponse.builder()
                    .valid(true)
                    .tenantId(key.getTenantId())
                    .keyId(key.getKeyId())
                    .permissions(key.getPermissions())
                    .scopeFilter(key.getScopeFilter())
                    .expiresAt(key.getExpiresAt())
                    .build();
        } catch (Exception e) {
            return ApiKeyValidationResponse.builder().valid(false).reason("INTERNAL_ERROR").build();
        }
    }
    public boolean verifyKey(String keySecret, String hash) {
        try {
            return BCrypt.checkpw(keySecret, hash);
        } catch (Exception e) {
            return false;
        }
    }
    public String extractPrefix(String keySecret) {
        int idx = keySecret.indexOf('_');
        return idx > 0 ? keySecret.substring(0, Math.min(idx + 6, keySecret.length())) : keySecret.substring(0, Math.min(10, keySecret.length()));
    }
}
