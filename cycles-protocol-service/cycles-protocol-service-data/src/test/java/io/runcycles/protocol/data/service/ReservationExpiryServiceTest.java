package io.runcycles.protocol.data.service;

import io.runcycles.protocol.model.event.EventType;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationExpiryService")
class ReservationExpiryServiceTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Mock private LuaScriptRegistry luaScripts;
    @Mock private EventEmitterService eventEmitter;
    @Mock private io.runcycles.protocol.data.metrics.CyclesMetrics metrics;

    private ReservationExpiryService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ReservationExpiryService();
        // Inject mocks via reflection (field injection)
        setField(service, "jedisPool", jedisPool);
        setField(service, "luaScripts", luaScripts);
        setField(service, "expireScript", "-- expire lua script");
        setField(service, "eventEmitter", eventEmitter);
        setField(service, "metrics", metrics);
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
                .thenReturn(List.of("id1", "id2"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), anyString())).thenReturn("OK");

        service.expireReservations();

        verify(luaScripts).eval(eq(jedis), eq("expire"), anyString(), eq("id1"));
        verify(luaScripts).eval(eq(jedis), eq("expire"), anyString(), eq("id2"));
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
                .thenReturn(List.of("id1", "id2", "res_3"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), eq("id1"))).thenReturn("OK");
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), eq("id2")))
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

    // --- reservation.expired event emission tests ---

    @Test
    void shouldEmitExpiredEventOnSuccessfulExpiry() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000)))
                .thenReturn(List.of("id1"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), eq("id1")))
                .thenReturn("{\"status\":\"EXPIRED\"}");
        when(jedis.hgetAll("reservation:res_id1")).thenReturn(Map.of(
                "tenant", "t1",
                "scope_path", "tenant:t1/agent:bot",
                "estimate_unit", "USD_MICROCENTS",
                "estimate_amount", "5000",
                "created_at_ms", "1710768000000",
                "expires_at_ms", "1710768060000",
                "extension_count", "2"
        ));

        service.expireReservations();

        verify(eventEmitter).emit(
                eq(EventType.RESERVATION_EXPIRED),
                eq("t1"),
                eq("tenant:t1/agent:bot"),
                argThat(a -> a.getType() == io.runcycles.protocol.model.event.ActorType.SYSTEM),
                argThat(d -> d instanceof io.runcycles.protocol.model.event.EventDataReservationExpired
                        && ((io.runcycles.protocol.model.event.EventDataReservationExpired) d).getReservationId().equals("id1")
                        && ((io.runcycles.protocol.model.event.EventDataReservationExpired) d).getExtensionsUsed() == 2),
                isNull(), isNull());
    }

    @Test
    void shouldNotEmitEventForNonExpiredResult() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000)))
                .thenReturn(List.of("id1"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), eq("id1")))
                .thenReturn("{\"status\":\"SKIP\",\"reason\":\"in_grace_period\"}");

        service.expireReservations();

        verify(eventEmitter, never()).emit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotEmitEventWhenHashMissingTenant() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000)))
                .thenReturn(List.of("id1"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), eq("id1")))
                .thenReturn("{\"status\":\"EXPIRED\"}");
        when(jedis.hgetAll("reservation:res_id1")).thenReturn(Map.of(
                "scope_path", "tenant:t1"
        ));

        service.expireReservations();

        verify(eventEmitter, never()).emit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotEmitEventWhenHashEmpty() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000)))
                .thenReturn(List.of("id1"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), eq("id1")))
                .thenReturn("{\"status\":\"EXPIRED\"}");
        when(jedis.hgetAll("reservation:res_id1")).thenReturn(Collections.emptyMap());

        service.expireReservations();

        verify(eventEmitter, never()).emit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldContinueProcessingIfEmitFails() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble(), eq(0), eq(1000)))
                .thenReturn(List.of("id1", "id2"));
        when(luaScripts.eval(eq(jedis), eq("expire"), anyString(), anyString()))
                .thenReturn("{\"status\":\"EXPIRED\"}");
        // res_1 hash throws, res_2 hash succeeds
        when(jedis.hgetAll("reservation:res_id1")).thenThrow(new RuntimeException("Redis error"));
        when(jedis.hgetAll("reservation:res_id2")).thenReturn(Map.of(
                "tenant", "t2", "scope_path", "tenant:t2"
        ));

        service.expireReservations();

        // res_2 should still emit despite res_1 failure
        verify(eventEmitter).emit(eq(EventType.RESERVATION_EXPIRED), eq("t2"),
                any(), any(), any(), any(), any());
    }
}
