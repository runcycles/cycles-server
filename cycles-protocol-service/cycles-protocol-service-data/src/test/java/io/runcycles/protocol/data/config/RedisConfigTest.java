package io.runcycles.protocol.data.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RedisConfig")
class RedisConfigTest {

    private RedisConfig redisConfig;

    @BeforeEach
    void setUp() throws Exception {
        redisConfig = new RedisConfig();
        setField("host", "localhost");
        setField("port", 6379);
        setField("password", "");
    }

    private void setField(String name, Object value) throws Exception {
        Field field = RedisConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(redisConfig, value);
    }

    @Test
    @DisplayName("objectMapper should have JavaTimeModule registered")
    void shouldRegisterJavaTimeModule() {
        ObjectMapper mapper = redisConfig.objectMapper();

        assertThat(mapper.getRegisteredModuleIds())
                .anyMatch(id -> id.toString().contains("jackson-datatype-jsr310"));
    }

    @Test
    @DisplayName("objectMapper should disable WRITE_DATES_AS_TIMESTAMPS")
    void shouldDisableWriteDatesAsTimestamps() {
        ObjectMapper mapper = redisConfig.objectMapper();

        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
    }

    @Test
    @DisplayName("objectMapper should not be null")
    void shouldCreateObjectMapper() {
        ObjectMapper mapper = redisConfig.objectMapper();

        assertThat(mapper).isNotNull();
    }

    @Test
    @DisplayName("should load reserve Lua script from classpath")
    void shouldLoadReserveLuaScript() throws Exception {
        String script = redisConfig.reserveLuaScript();

        assertThat(script).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("should load commit Lua script from classpath")
    void shouldLoadCommitLuaScript() throws Exception {
        String script = redisConfig.commitLuaScript();

        assertThat(script).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("should load release Lua script from classpath")
    void shouldLoadReleaseLuaScript() throws Exception {
        String script = redisConfig.releaseLuaScript();

        assertThat(script).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("should load extend Lua script from classpath")
    void shouldLoadExtendLuaScript() throws Exception {
        String script = redisConfig.extendLuaScript();

        assertThat(script).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("should load event Lua script from classpath")
    void shouldLoadEventLuaScript() throws Exception {
        String script = redisConfig.eventLuaScript();

        assertThat(script).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("should load expire Lua script from classpath")
    void shouldLoadExpireLuaScript() throws Exception {
        String script = redisConfig.expireLuaScript();

        assertThat(script).isNotNull().isNotEmpty();
    }
}
