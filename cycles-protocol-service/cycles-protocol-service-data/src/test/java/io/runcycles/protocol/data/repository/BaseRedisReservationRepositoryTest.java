package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.service.LuaScriptRegistry;
import io.runcycles.protocol.data.service.ReservationCreatedAtIndexService;
import io.runcycles.protocol.data.service.ScopeDerivationService;
import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.SetParams;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
abstract class BaseRedisReservationRepositoryTest {

    @Mock protected JedisPool jedisPool;
    @Mock protected Jedis jedis;
    @Mock protected Pipeline pipeline;
    @Mock protected ScopeDerivationService scopeService;
    @Mock protected LuaScriptRegistry luaScripts;
    @Mock protected CyclesMetrics metrics;
    @Mock protected AuditRepository auditRepository;
    @Mock protected ReservationCreatedAtIndexService reservationCreatedAtIndex;
    // Unconfigured by default: prepare() returns null → no cycles_evidence stamped, so
    // existing reservation tests are unaffected. Evidence-specific tests stub it.
    @Mock protected io.runcycles.protocol.data.service.EvidenceEmitter evidenceEmitter;
    @InjectMocks protected RedisReservationRepository repository;

    protected final ObjectMapper objectMapper = new ObjectMapper();
    @BeforeEach
    void setUp() throws Exception {
        var omField = RedisReservationRepository.class.getDeclaredField("objectMapper");
        omField.setAccessible(true);
        omField.set(repository, objectMapper);

        setField("reserveScript", "RESERVE_SCRIPT");
        setField("commitScript", "COMMIT_SCRIPT");
        setField("releaseScript", "RELEASE_SCRIPT");
        setField("extendScript", "EXTEND_SCRIPT");
        setField("eventScript", "EVENT_SCRIPT");

        // Default pipeline mock for pipelined HGETALL and HMGET calls.
        // Returns a Response that yields an empty map/list by default.
        // Tests that need specific budget data should override pipeline.hgetAll(key) explicitly.
        lenient().when(jedis.pipelined()).thenReturn(pipeline);
        Response<Map<String, String>> defaultBudgetResp = mock(Response.class);
        lenient().when(defaultBudgetResp.get()).thenReturn(Map.of());
        lenient().when(pipeline.hgetAll(anyString())).thenReturn(defaultBudgetResp);
        Response<List<String>> defaultHmgetResp = mock(Response.class);
        lenient().when(defaultHmgetResp.get()).thenReturn(Collections.singletonList(null));
        lenient().when(pipeline.hmget(anyString(), any(String[].class))).thenReturn(defaultHmgetResp);
        // Default pipeline.get() returns null (no cached idempotency key)
        Response<String> defaultGetResp = mock(Response.class);
        lenient().when(defaultGetResp.get()).thenReturn(null);
        lenient().when(pipeline.get(anyString())).thenReturn(defaultGetResp);
        lenient().when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        lenient().when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
    }

    /** Mock a budget key so it is visible via both jedis.hgetAll and pipeline.hgetAll */
    @SuppressWarnings("unchecked")
    protected void mockBudget(String key, Map<String, String> data) {
        lenient().when(jedis.hgetAll(key)).thenReturn(data);
        Response<Map<String, String>> resp = mock(Response.class);
        lenient().when(resp.get()).thenReturn(data);
        lenient().when(pipeline.hgetAll(key)).thenReturn(resp);
    }

    /** Mock a direct reservation-hash HMGET projection. */
    protected void mockReservationHash(String key, Map<String, String> data) {
        lenient().doAnswer(invocation -> projectHash(data, invocation.getArguments()))
            .when(jedis).hmget(eq(key), any(String[].class));
    }

    /** Mock a pipelined reservation-hash HMGET projection from an existing map response. */
    @SuppressWarnings("unchecked")
    protected void mockReservationHash(String key, Response<Map<String, String>> source) {
        Pipeline target = jedis.pipelined();
        lenient().when(target.hmget(eq(key), any(String[].class))).thenAnswer(invocation -> {
            Response<List<String>> projected = mock(Response.class);
            lenient().doAnswer(ignored -> projectHash(source.get(), invocation.getArguments()))
                .when(projected).get();
            return projected;
        });
    }

    private static List<String> projectHash(Map<String, String> data, Object[] arguments) {
        if (arguments == null) {
            return Collections.emptyList();
        }
        Object[] names = arguments.length == 2 && arguments[1] instanceof String[] array
            ? array
            : Arrays.copyOfRange(arguments, 1, arguments.length);
        return Arrays.stream(names)
            .map(String.class::cast)
            .map(name -> data != null ? data.get(name) : null)
            .toList();
    }

    /** Assert that a Redis mock was projected with HMGET and never requested a response snapshot. */
    protected void assertNoResponseSnapshotHmget(Object redisMock) {
        boolean sawHmget = false;
        for (var invocation : mockingDetails(redisMock).getInvocations()) {
            if (!"hmget".equals(invocation.getMethod().getName())) {
                continue;
            }
            sawHmget = true;
            for (Object argument : invocation.getArguments()) {
                if (argument instanceof String name) {
                    assertThat(name).doesNotEndWith("_response_json");
                } else if (argument instanceof String[] names) {
                    assertThat(names).noneMatch(name -> name.endsWith("_response_json"));
                }
            }
        }
        assertThat(sawHmget).isTrue();
    }

    /** Mock caps_json via pipeline.hmget for evaluateDryRun/decide which use pipeline */
    @SuppressWarnings("unchecked")
    protected void mockCaps(String budgetKey, String capsJson) {
        lenient().when(jedis.hget(budgetKey, "caps_json")).thenReturn(capsJson);
        Response<List<String>> resp = mock(Response.class);
        lenient().when(resp.get()).thenReturn(Collections.singletonList(capsJson));
        lenient().when(pipeline.hmget(eq(budgetKey), eq("caps_json"))).thenReturn(resp);
    }

    protected void setField(String name, Object value) throws Exception {
        Field f = RedisReservationRepository.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(repository, value);
    }

    // ---- Common test fixtures ----

    protected Subject defaultSubject() {
        Subject s = new Subject();
        s.setTenant("acme");
        s.setApp("myapp");
        return s;
    }

    protected Action defaultAction() {
        Action a = new Action();
        a.setKind("llm");
        a.setName("chat");
        return a;
    }

    protected Amount defaultEstimate() {
        return new Amount(Enums.UnitEnum.USD_MICROCENTS, 5000L);
    }

    protected List<String> defaultScopes() {
        return List.of("tenant:acme", "tenant:acme/app:myapp");
    }

    protected Map<String, String> budgetMap(long allocated, long remaining, long reserved, long spent) {
        Map<String, String> m = new HashMap<>();
        m.put("allocated", String.valueOf(allocated));
        m.put("remaining", String.valueOf(remaining));
        m.put("reserved", String.valueOf(reserved));
        m.put("spent", String.valueOf(spent));
        m.put("debt", "0");
        m.put("overdraft_limit", "0");
        m.put("is_over_limit", "false");
        m.put("scope", "tenant:acme/app:myapp");
        m.put("unit", "USD_MICROCENTS");
        m.put("status", "ACTIVE");
        return m;
    }

    // ---- Helper to invoke private methods ----

    protected String invokeLeafScope(String scopePath) throws Exception {
        Method m = RedisReservationRepository.class.getDeclaredMethod("leafScope", String.class);
        m.setAccessible(true);
        return (String) m.invoke(repository, scopePath);
    }

    protected boolean invokeScopeHasSegment(String scopePath, String segment) throws Exception {
        Method m = RedisReservationRepository.class.getDeclaredMethod("scopeHasSegment", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(repository, scopePath, segment);
    }

    protected String invokeComputePayloadHash(Object request) throws Exception {
        Method m = RedisReservationRepository.class.getDeclaredMethod("computePayloadHash", Object.class);
        m.setAccessible(true);
        return (String) m.invoke(repository, request);
    }

    @SuppressWarnings("unchecked")
    protected void invokeHandleScriptError(Map<String, Object> response) throws Throwable {
        Method m = RedisReservationRepository.class.getDeclaredMethod("handleScriptError", Map.class);
        m.setAccessible(true);
        try {
            m.invoke(repository, response);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ---- Helper to build reservation fields ----

    protected Map<String, String> reservationFields(String id, String state) {
        Map<String, String> fields = new HashMap<>();
        fields.put("reservation_id", id);
        fields.put("state", state);
        fields.put("tenant", "acme");
        fields.put("estimate_amount", "5000");
        fields.put("estimate_unit", "USD_MICROCENTS");
        fields.put("subject_json", "{\"tenant\":\"acme\",\"app\":\"myapp\"}");
        fields.put("action_json", "{\"kind\":\"llm\",\"name\":\"chat\"}");
        fields.put("created_at", "1700000000000");
        fields.put("expires_at", "1700060000000");
        fields.put("scope_path", "tenant:acme/app:myapp");
        fields.put("affected_scopes", "[\"tenant:acme\",\"tenant:acme/app:myapp\"]");
        fields.put("idempotency_key", "idem-" + id);
        return fields;
    }
}
