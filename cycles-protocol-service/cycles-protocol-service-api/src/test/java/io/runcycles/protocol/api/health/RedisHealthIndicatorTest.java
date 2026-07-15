package io.runcycles.protocol.api.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RedisHealthIndicator")
class RedisHealthIndicatorTest {

    @Test
    void shouldReportUpWhenRedisPongs() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.sendCommand(Protocol.Command.PING)).thenReturn("PONG");

        var health = new RedisHealthIndicator(pool).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("redis", "available");
        verify(jedis).close();
    }

    @Test
    void shouldReportDownWhenRedisPingFails() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.sendCommand(Protocol.Command.PING)).thenReturn("NOPE");

        var health = new RedisHealthIndicator(pool).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("redis", "ping failed");
        verify(jedis).close();
    }

    @Test
    void shouldReportDownWhenRedisConnectionFails() {
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new IllegalStateException("pool exhausted"));

        var health = new RedisHealthIndicator(pool).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("redis", "unavailable");
    }

    @Test
    void bytePongAndCommandFailureCoverBothRedisResponseAlternatives() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.sendCommand(Protocol.Command.PING)).thenReturn("PONG".getBytes())
            .thenThrow(new IllegalStateException("command failed"));

        assertThat(new RedisHealthIndicator(pool).health().getStatus()).isEqualTo(Status.UP);
        assertThat(new RedisHealthIndicator(pool).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void shouldRejectNonPongByteResponse() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.sendCommand(Protocol.Command.PING)).thenReturn("NOPE".getBytes());

        assertThat(new RedisHealthIndicator(pool).health().getStatus()).isEqualTo(Status.DOWN);
    }
}
