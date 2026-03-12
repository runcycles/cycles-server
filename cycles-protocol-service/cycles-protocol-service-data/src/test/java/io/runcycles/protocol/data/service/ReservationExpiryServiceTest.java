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

    private ReservationExpiryService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ReservationExpiryService();
        // Inject mocks via reflection (field injection)
        setField(service, "jedisPool", jedisPool);
        setField(service, "expireScript", "-- expire lua script");
        when(jedisPool.getResource()).thenReturn(jedis);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void shouldExpireCandidatesFromSortedSet() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble()))
                .thenReturn(List.of("res_1", "res_2"));
        when(jedis.eval(anyString(), eq(0), anyString())).thenReturn("OK");

        service.expireReservations();

        verify(jedis).eval(anyString(), eq(0), eq("res_1"));
        verify(jedis).eval(anyString(), eq(0), eq("res_2"));
    }

    @Test
    void shouldSkipWhenNoCandidates() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble()))
                .thenReturn(Collections.emptyList());

        service.expireReservations();

        verify(jedis, never()).eval(anyString(), anyInt(), any(String[].class));
    }

    @Test
    void shouldContinueOnPerReservationFailure() {
        when(jedis.zrangeByScore(eq("reservation:ttl"), eq((double) 0), anyDouble()))
                .thenReturn(List.of("res_1", "res_2", "res_3"));
        when(jedis.eval(anyString(), eq(0), eq("res_1"))).thenReturn("OK");
        when(jedis.eval(anyString(), eq(0), eq("res_2")))
                .thenThrow(new RuntimeException("Lua error"));
        when(jedis.eval(anyString(), eq(0), eq("res_3"))).thenReturn("OK");

        service.expireReservations();

        // res_3 should still be processed despite res_2 failure
        verify(jedis).eval(anyString(), eq(0), eq("res_3"));
    }

    @Test
    void shouldHandleRedisConnectionFailure() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection refused"));

        // Should not throw — exception is caught internally
        service.expireReservations();
    }
}
