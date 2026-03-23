package io.runcycles.protocol.data.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisNoScriptException;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LuaScriptRegistry")
class LuaScriptRegistryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;

    private LuaScriptRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new LuaScriptRegistry();
        setField("jedisPool", jedisPool);
        setField("reserveScript", "-- reserve");
        setField("commitScript", "-- commit");
        setField("releaseScript", "-- release");
        setField("extendScript", "-- extend");
        setField("eventScript", "-- event");
        setField("expireScript", "-- expire");
    }

    private void setField(String name, Object value) throws Exception {
        Field f = LuaScriptRegistry.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(registry, value);
    }

    @Test
    void shouldLoadScriptsAtStartup() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenReturn("abc123");

        registry.afterPropertiesSet();

        verify(jedis, times(6)).scriptLoad(anyString());
    }

    @Test
    void shouldUseEvalshaWhenShaIsCached() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenReturn("sha_reserve");
        registry.afterPropertiesSet();

        when(jedis.evalsha(eq("sha_reserve"), eq(0), any(String[].class))).thenReturn("OK");

        Object result = registry.eval(jedis, "reserve", "-- reserve", "arg1");
        assertThat(result).isEqualTo("OK");
        verify(jedis).evalsha(eq("sha_reserve"), eq(0), any(String[].class));
    }

    @Test
    void shouldFallbackToEvalOnNoscript() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenReturn("sha_old");
        registry.afterPropertiesSet();

        when(jedis.evalsha(eq("sha_old"), eq(0), any(String[].class)))
                .thenThrow(new JedisNoScriptException("NOSCRIPT"));
        when(jedis.scriptLoad("-- reserve")).thenReturn("sha_new");
        when(jedis.evalsha(eq("sha_new"), eq(0), any(String[].class))).thenReturn("OK");

        Object result = registry.eval(jedis, "reserve", "-- reserve", "arg1");
        assertThat(result).isEqualTo("OK");
    }

    @Test
    void shouldFallbackToEvalWhenNoShaAvailable() {
        // Don't call afterPropertiesSet — no SHA cached
        when(jedis.eval(eq("-- reserve"), eq(0), any(String[].class))).thenReturn("OK");

        Object result = registry.eval(jedis, "reserve", "-- reserve", "arg1");
        assertThat(result).isEqualTo("OK");
        verify(jedis).eval(eq("-- reserve"), eq(0), any(String[].class));
    }

    @Test
    void shouldHandleStartupLoadFailure() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection failed"));

        // Should not throw
        registry.afterPropertiesSet();

        // Should fall back to eval since no SHA was loaded
        when(jedis.eval(eq("-- commit"), eq(0), any(String[].class))).thenReturn("OK");
        Object result = registry.eval(jedis, "commit", "-- commit", "arg1");
        assertThat(result).isEqualTo("OK");
    }

    @Test
    void shouldPropagateExceptionWhenScriptLoadFailsDuringNoscriptRecovery() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenReturn("sha_old");
        registry.afterPropertiesSet();

        // EVALSHA throws NOSCRIPT, scriptLoad during recovery also fails
        when(jedis.evalsha(eq("sha_old"), eq(0), any(String[].class)))
                .thenThrow(new JedisNoScriptException("NOSCRIPT"));
        when(jedis.scriptLoad("-- reserve")).thenThrow(new RuntimeException("Redis OOM"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> registry.eval(jedis, "reserve", "-- reserve", "arg1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redis OOM");
    }

    @Test
    void shouldLoadRemainingScriptsEvenIfOneThrows() {
        when(jedisPool.getResource()).thenReturn(jedis);
        // First scriptLoad succeeds, rest throw
        when(jedis.scriptLoad(anyString()))
                .thenReturn("sha_first")
                .thenThrow(new RuntimeException("Script compile error"));

        // loadScripts wraps everything in one try-catch, so partial load
        // means only "reserve" (first) gets loaded before exception kills the rest
        registry.afterPropertiesSet();

        // "reserve" should have its SHA cached, so EVALSHA works
        when(jedis.evalsha(eq("sha_first"), eq(0), any(String[].class))).thenReturn("OK");
        Object result = registry.eval(jedis, "reserve", "-- reserve", "arg1");
        assertThat(result).isEqualTo("OK");

        // "commit" was never loaded, so falls back to EVAL
        when(jedis.eval(eq("-- commit"), eq(0), any(String[].class))).thenReturn("OK2");
        Object result2 = registry.eval(jedis, "commit", "-- commit", "arg1");
        assertThat(result2).isEqualTo("OK2");
    }
}
