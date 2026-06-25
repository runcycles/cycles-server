package io.runcycles.protocol.api.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Protocol;

import java.nio.charset.StandardCharsets;

/**
 * Makes /actuator/health reflect the ledger dependency used by every write path.
 */
@Component("redis")
@SuppressWarnings("deprecation") // Jedis 7.5 deprecates direct PING APIs; JedisPool is still the app client.
public class RedisHealthIndicator implements HealthIndicator {

    private final JedisPool jedisPool;

    public RedisHealthIndicator(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public Health health() {
        try (Jedis jedis = jedisPool.getResource()) {
            Object response = jedis.sendCommand(Protocol.Command.PING);
            if (isPong(response)) {
                return Health.up()
                        .withDetail("redis", "available")
                        .build();
            }
            return Health.down()
                    .withDetail("redis", "ping failed")
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withException(e)
                    .withDetail("redis", "unavailable")
                    .build();
        }
    }

    private static boolean isPong(Object response) {
        if (response instanceof String s) {
            return "PONG".equals(s);
        }
        if (response instanceof byte[] bytes) {
            return "PONG".equals(new String(bytes, StandardCharsets.UTF_8));
        }
        return false;
    }
}
