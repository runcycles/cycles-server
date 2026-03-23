package io.runcycles.protocol.data.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationExpiryService")
class ReservationExpiryServiceTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Mock private LuaScriptRegistry luaScripts;

    private ReservationExpiryService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ReservationExpiryService();
        // Inject mocks via reflection (field injection)
        setField(service, "jedisPool", jedisPool);
        setField(service, "luaScripts", luaScripts);
        setField(service, "expireScript", "-- expire lua script");
        when(jedisPool.getResource()).thenReturn(jedis);
        // Mock Redis TIME — returns [seconds, microseconds].
        // Use lenient() because some tests override getResource() to throw before time() is called.
        lenient().when(jedis.time()).thenReturn(List.of("1710768000", "123456"));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void shouldExpireCandidatesFromSortedSet() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000)))
                .thenReturn(List.of("res_1", "res_2"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), anyString())).thenReturn("OK");

        service.expireReservations();

        verify(luaScripts).eval(eq(jedis), eq("expire"), anyString(), eq("res_1"));
        verify(luaScripts).eval(eq(jedis), eq("expire"), anyString(), eq("res_2"));
    }

    @Test
    void shouldSkipWhenNoCandidates() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000)))
                .thenReturn(Collections.emptyList());

        service.expireReservations();

        verify(luaScripts, never()).eval(any(Jedis.class), anyString(), anyString(), any(String[].class));
    }

    @Test
    void shouldContinueOnPerReservationFailure() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000)))
                .thenReturn(List.of("res_1", "res_2", "res_3"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), eq("res_1"))).thenReturn("OK");
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), eq("res_2")))
                .thenThrow(new RuntimeException("Lua error"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), eq("res_3"))).thenReturn("OK");

        service.expireReservations();

        // res_3 should still be processed despite res_2 failure
        verify(luaScripts).eval(eq(jedis), eq("expire"), anyString(), eq("res_3"));
    }

    @Test
    void shouldHandleRedisConnectionFailure() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection refused"));

        // Should not throw — exception is caught internally
        service.expireReservations();
    }

    @Test
    void shouldUseRedisTimeForCandidateQuery() {
        // Redis TIME returns [seconds, microseconds] → expected millis = 1710768000*1000 + 123456/1000 = 1710768000123
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), eq(1710768000123.0), eq(0), eq(1000)))
                .thenReturn(Collections.emptyList());

        service.expireReservations();

        // Verify that Redis TIME was called and the computed millis were passed to zrangeByScore
        verify(jedis).time();
        verify(jedis).zrangeByScore("reservation:ttl", 0, 1710768000123.0, 0, 1000);
    }

    @Test
    void shouldLimitCandidateBatchSize() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000)))
                .thenReturn(Collections.emptyList());

        service.expireReservations();

        // Verify the LIMIT parameters (offset=0, count=1000) are passed
        verify(jedis).zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000));
    }

    @Test
    void shouldHandleRedisTimeFailure() {
        when(jedis.time()).thenThrow(new RuntimeException("Redis TIME failed"));

        // Should not throw — exception is caught by outer handler
        service.expireReservations();

        // Should never reach zrangeByScore
        verify(jedis, never()).zrangeByScore(anyString(), anyDouble(), anyDouble(), anyInt(), anyInt());
    }
}
