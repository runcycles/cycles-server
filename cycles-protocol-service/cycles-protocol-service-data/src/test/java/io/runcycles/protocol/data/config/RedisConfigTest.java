package io.runcycles.protocol.data.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.data.repository.RedisReservationQueryRepository;
import io.runcycles.protocol.data.service.ReservationCreatedAtIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import redis.clients.jedis.JedisPool;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        Properties props = new Properties();
        props.setProperty("version", "0.1.24.3-test");
        setField("buildProperties", new BuildProperties(props));
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

    @Test
    @DisplayName("should load reservation created-at index Lua script from classpath")
    void shouldLoadReservationCreatedAtIndexLuaScript() throws Exception {
        String script = redisConfig.reservationCreatedAtIndexLuaScript();

        assertThat(script)
            .contains("operation == 'validate'")
            .contains("operation == 'finalize'")
            .contains("operation == 'remove'");
    }

    @Test
    @DisplayName("should prepend one shared int64 helper library to every ledger script")
    void shouldComposeLedgerScriptsWithSharedInt64Helpers() throws Exception {
        List<String> scripts = List.of(
            redisConfig.reserveLuaScript(), redisConfig.commitLuaScript(),
            redisConfig.releaseLuaScript(), redisConfig.extendLuaScript(),
            redisConfig.eventLuaScript(), redisConfig.expireLuaScript());

        assertThat(scripts).allSatisfy(script -> {
            assertThat(script).contains("local function normalize_int(value)");
            assertThat(script.indexOf("local function normalize_int(value)"))
                .isEqualTo(script.lastIndexOf("local function normalize_int(value)"));
        });
    }

    @Test
    @DisplayName("should prepend one separate uncovered-scope ledger helper")
    void shouldComposeLedgerScriptsWithSeparateUncoveredScopeHelper() throws Exception {
        List<String> scripts = List.of(
            redisConfig.reserveLuaScript(), redisConfig.commitLuaScript(),
            redisConfig.releaseLuaScript(), redisConfig.extendLuaScript(),
            redisConfig.eventLuaScript(), redisConfig.expireLuaScript());

        assertThat(scripts).allSatisfy(script -> {
            String definition = "local function mark_uncovered_scopes(";
            assertThat(script).contains(definition);
            assertThat(script.indexOf(definition)).isEqualTo(script.lastIndexOf(definition));
        });
        assertThat(redisConfig.commitLuaScript())
            .contains("mark_uncovered_scopes(affected_scopes, pre_budget_state")
            .contains("mark_uncovered_scopes(affected_scopes, scope_budget_cache");
        assertThat(redisConfig.eventLuaScript())
            .contains("mark_uncovered_scopes(budgeted_scopes, scope_budget_cache");
    }

    @Test
    @DisplayName("int64 helper resource should remain side-effect free")
    void shouldKeepInt64HelperResourceSideEffectFree() throws Exception {
        try (InputStream int64 = getClass().getClassLoader()
                .getResourceAsStream("lua/int64-helpers.lua");
             InputStream ledger = getClass().getClassLoader()
                .getResourceAsStream("lua/ledger-helpers.lua")) {
            assertThat(int64).isNotNull();
            assertThat(ledger).isNotNull();
            String int64Source = new String(int64.readAllBytes(), StandardCharsets.UTF_8);
            String ledgerSource = new String(ledger.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(int64Source).doesNotContain("redis.call");
            assertThat(ledgerSource)
                .contains("local function mark_uncovered_scopes(")
                .contains("redis.call('HSET', budget_key, 'is_over_limit', 'true')");
        }
    }

    @Test
    @DisplayName("reserve pending-marker prefix should match the Java claim writer")
    void reservePendingPrefixMatchesJavaClaimWriter() throws Exception {
        Method pendingPrefix = RedisReservationRepository.class
                .getDeclaredMethod("pendingPrefix", String.class);
        pendingPrefix.setAccessible(true);
        String javaPrefix = (String) pendingPrefix.invoke(null, "reserve");

        assertThat(redisConfig.reserveLuaScript())
                .contains("local pending_prefix = \"" + javaPrefix + "\"");
    }

    @Test
    @DisplayName("reservation index key grammar and page bounds should match Java")
    void reservationIndexLuaContractMatchesJava() throws Exception {
        String tenant = "contract-tenant";
        String indexExpression = luaKeyExpression(
            ReservationCreatedAtIndexService.indexKey(tenant), tenant);
        String metadataExpression = luaKeyExpression(
            ReservationCreatedAtIndexService.metadataKey(tenant), tenant);
        String reserve = redisConfig.reserveLuaScript();
        String index = redisConfig.reservationCreatedAtIndexLuaScript();

        assertThat(reserve).contains(indexExpression).contains(metadataExpression);
        assertThat(index).contains(indexExpression).contains(metadataExpression);

        Field javaBatch = RedisReservationQueryRepository.class
            .getDeclaredField("SORTED_INDEX_BATCH_SIZE");
        javaBatch.setAccessible(true);
        int batchSize = javaBatch.getInt(null);
        Matcher luaCap = Pattern.compile("page_size > (\\d+)").matcher(index);
        assertThat(luaCap.find()).isTrue();
        assertThat(batchSize).isLessThanOrEqualTo(Integer.parseInt(luaCap.group(1)));
    }

    private static String luaKeyExpression(String key, String tenant) {
        int tenantOffset = key.indexOf(tenant);
        assertThat(tenantOffset).isGreaterThanOrEqualTo(0);
        return "'" + key.substring(0, tenantOffset) + "' .. tenant .. '"
            + key.substring(tenantOffset + tenant.length()) + "'";
    }

    @Test
    @DisplayName("should create JedisPool with password when password is non-empty")
    void shouldCreateJedisPoolWithPassword() throws Exception {
        setField("password", "secret123");
        JedisPool pool = redisConfig.jedisPool();
        assertThat(pool).isNotNull();
        pool.close();
    }

    @Test
    @DisplayName("should create JedisPool without password when password is empty")
    void shouldCreateJedisPoolWithoutPassword() throws Exception {
        JedisPool pool = redisConfig.jedisPool();
        assertThat(pool).isNotNull();
        pool.close();
    }
}
