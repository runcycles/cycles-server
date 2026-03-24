package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.service.LuaScriptRegistry;
import io.runcycles.protocol.data.service.ScopeDerivationService;
import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisReservationRepository")
class RedisReservationRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Mock private Pipeline pipeline;
    @Mock private ScopeDerivationService scopeService;
    @Mock private LuaScriptRegistry luaScripts;
    @InjectMocks private RedisReservationRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        // Default jedis.time() mock — returns a time BEFORE test reservation expiry (1700060000000ms)
        // so ACTIVE reservations are not treated as expired in getReservationById().
        lenient().when(jedis.time()).thenReturn(List.of("1700000000", "0"));

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
    }

    /** Mock a budget key so it is visible via both jedis.hgetAll and pipeline.hgetAll */
    @SuppressWarnings("unchecked")
    private void mockBudget(String key, Map<String, String> data) {
        lenient().when(jedis.hgetAll(key)).thenReturn(data);
        Response<Map<String, String>> resp = mock(Response.class);
        lenient().when(resp.get()).thenReturn(data);
        lenient().when(pipeline.hgetAll(key)).thenReturn(resp);
    }

    /** Mock caps_json via pipeline.hmget for evaluateDryRun/decide which use pipeline */
    @SuppressWarnings("unchecked")
    private void mockCaps(String budgetKey, String capsJson) {
        lenient().when(jedis.hget(budgetKey, "caps_json")).thenReturn(capsJson);
        Response<List<String>> resp = mock(Response.class);
        lenient().when(resp.get()).thenReturn(Collections.singletonList(capsJson));
        lenient().when(pipeline.hmget(eq(budgetKey), eq("caps_json"))).thenReturn(resp);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = RedisReservationRepository.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(repository, value);
    }

    // ---- Common test fixtures ----

    private Subject defaultSubject() {
        Subject s = new Subject();
        s.setTenant("acme");
        s.setApp("myapp");
        return s;
    }

    private Action defaultAction() {
        Action a = new Action();
        a.setKind("llm");
        a.setName("chat");
        return a;
    }

    private Amount defaultEstimate() {
        return new Amount(Enums.UnitEnum.USD_MICROCENTS, 5000L);
    }

    private List<String> defaultScopes() {
        return List.of("tenant:acme", "tenant:acme/app:myapp");
    }

    private Map<String, String> budgetMap(long allocated, long remaining, long reserved, long spent) {
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

    private String invokeLeafScope(String scopePath) throws Exception {
        Method m = RedisReservationRepository.class.getDeclaredMethod("leafScope", String.class);
        m.setAccessible(true);
        return (String) m.invoke(repository, scopePath);
    }

    private boolean invokeScopeHasSegment(String scopePath, String segment) throws Exception {
        Method m = RedisReservationRepository.class.getDeclaredMethod("scopeHasSegment", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(repository, scopePath, segment);
    }

    private String invokeComputePayloadHash(Object request) throws Exception {
        Method m = RedisReservationRepository.class.getDeclaredMethod("computePayloadHash", Object.class);
        m.setAccessible(true);
        return (String) m.invoke(repository, request);
    }

    @SuppressWarnings("unchecked")
    private void invokeHandleScriptError(Map<String, Object> response) throws Throwable {
        Method m = RedisReservationRepository.class.getDeclaredMethod("handleScriptError", Map.class);
        m.setAccessible(true);
        try {
            m.invoke(repository, response);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ---- leafScope ----

    @Nested
    @DisplayName("leafScope")
    class LeafScope {

        @Test
        void shouldExtractLeafFromMultiLevelPath() throws Exception {
            assertThat(invokeLeafScope("tenant:acme/workspace:dev/app:myapp"))
                    .isEqualTo("app:myapp");
        }

        @Test
        void shouldReturnWholeStringForSingleLevel() throws Exception {
            assertThat(invokeLeafScope("tenant:acme"))
                    .isEqualTo("tenant:acme");
        }

        @Test
        void shouldHandleDeeplyNestedPath() throws Exception {
            assertThat(invokeLeafScope("tenant:acme/workspace:dev/app:web/agent:summarizer"))
                    .isEqualTo("agent:summarizer");
        }
    }

    // ---- scopeHasSegment ----

    @Nested
    @DisplayName("scopeHasSegment")
    class ScopeHasSegment {

        @Test
        void shouldMatchExactSegmentAtStart() throws Exception {
            assertThat(invokeScopeHasSegment("tenant:acme/workspace:dev", "tenant:acme")).isTrue();
        }

        @Test
        void shouldMatchExactSegmentInMiddle() throws Exception {
            assertThat(invokeScopeHasSegment("tenant:acme/workspace:dev/app:web", "workspace:dev")).isTrue();
        }

        @Test
        void shouldMatchExactSegmentAtEnd() throws Exception {
            assertThat(invokeScopeHasSegment("tenant:acme/workspace:dev", "workspace:dev")).isTrue();
        }

        @Test
        void shouldRejectPrefixFalsePositive() throws Exception {
            // "tenant:acme" must NOT match "tenant:acme-corp"
            assertThat(invokeScopeHasSegment("tenant:acme-corp/workspace:dev", "tenant:acme")).isFalse();
        }

        @Test
        void shouldRejectPartialMatch() throws Exception {
            assertThat(invokeScopeHasSegment("tenant:acme/workspace:dev", "tenant:ac")).isFalse();
        }

        @Test
        void shouldRejectMissingSegment() throws Exception {
            assertThat(invokeScopeHasSegment("tenant:acme/workspace:dev", "app:web")).isFalse();
        }

        @Test
        void shouldMatchSingleSegmentPath() throws Exception {
            assertThat(invokeScopeHasSegment("tenant:acme", "tenant:acme")).isTrue();
        }
    }

    // ---- computePayloadHash ----

    @Nested
    @DisplayName("computePayloadHash")
    class ComputePayloadHash {

        @Test
        void shouldReturnConsistentHash() throws Exception {
            Map<String, String> payload = Map.of("key", "value");
            String hash1 = invokeComputePayloadHash(payload);
            String hash2 = invokeComputePayloadHash(payload);
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        void shouldReturnDifferentHashForDifferentPayloads() throws Exception {
            String hash1 = invokeComputePayloadHash(Map.of("key", "value1"));
            String hash2 = invokeComputePayloadHash(Map.of("key", "value2"));
            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        void shouldReturnHexString() throws Exception {
            String hash = invokeComputePayloadHash(Map.of("test", "data"));
            assertThat(hash).matches("[0-9a-f]{64}"); // SHA-256 hex = 64 chars
        }
    }

    // ---- handleScriptError ----

    @Nested
    @DisplayName("handleScriptError")
    class HandleScriptError {

        @Test
        void shouldThrowBudgetExceeded() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "BUDGET_EXCEEDED", "message", "tenant:acme")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_EXCEEDED);
        }

        @Test
        void shouldThrowOverdraftLimitExceeded() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "OVERDRAFT_LIMIT_EXCEEDED", "message", "scope")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED);
        }

        @Test
        void shouldThrowDebtOutstanding() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "DEBT_OUTSTANDING", "message", "scope")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.DEBT_OUTSTANDING);
        }

        @Test
        void shouldThrowNotFound() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "NOT_FOUND", "message", "res_123")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldThrowReservationFinalized() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "RESERVATION_FINALIZED", "message", "already done")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_FINALIZED);
        }

        @Test
        void shouldThrowBudgetNotFound() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "BUDGET_NOT_FOUND", "scope", "tenant:acme")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldThrowIdempotencyMismatch() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "IDEMPOTENCY_MISMATCH")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.IDEMPOTENCY_MISMATCH);
        }

        @Test
        void shouldThrowUnitMismatch() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "UNIT_MISMATCH")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.UNIT_MISMATCH);
        }

        @Test
        void shouldThrowReservationExpired() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "RESERVATION_EXPIRED")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_EXPIRED);
        }

        @Test
        void shouldThrowInternalErrorForUnknownError() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "SOMETHING_UNKNOWN")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR);
        }
    }

    // ---- findReservationTenantById ----

    @Nested
    @DisplayName("findReservationTenantById")
    class FindReservationTenantById {

        @Test
        void shouldReturnTenantForExistingReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hget("reservation:res_abc123", "tenant")).thenReturn("acme-corp");

            String tenant = repository.findReservationTenantById("abc123");

            assertThat(tenant).isEqualTo("acme-corp");
        }

        @Test
        void shouldThrowNotFoundWhenTenantNull() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hget("reservation:res_unknown", "tenant")).thenReturn(null);

            assertThatThrownBy(() -> repository.findReservationTenantById("unknown"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldWrapRedisExceptionAsRuntime() {
            when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection failed"));

            assertThatThrownBy(() -> repository.findReservationTenantById("any"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to resolve reservation tenant");
        }
    }

    // ---- getReservationById ----

    @Nested
    @DisplayName("getReservationById")
    class GetReservationById {

        @Test
        void shouldThrowNotFoundWhenEmpty() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hgetAll("reservation:res_missing")).thenReturn(Map.of());

            assertThatThrownBy(() -> repository.getReservationById("missing"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldThrowNotFoundWhenNull() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hgetAll("reservation:res_gone")).thenReturn(null);

            assertThatThrownBy(() -> repository.getReservationById("gone"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldReturnDetailForValidReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res123", "ACTIVE");
            when(jedis.hgetAll("reservation:res_res123")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res123");

            assertThat(detail.getReservationId()).isEqualTo("res123");
            assertThat(detail.getStatus()).isEqualTo(Enums.ReservationStatus.ACTIVE);
            assertThat(detail.getReserved().getAmount()).isEqualTo(5000L);
        }
    }

    // ---- createReservation ----

    @Nested
    @DisplayName("createReservation")
    class CreateReservation {

        @Test
        void shouldCreateReservationSuccessfully() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "new-res-id", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // balances now come from Lua response (no separate hgetAll needed)

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-1");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            assertThat(response.getReservationId()).isNotNull();
            assertThat(response.getExpiresAtMs()).isEqualTo(9999999L);
            assertThat(response.getAffectedScopes()).isEqualTo(defaultScopes());
        }

        @Test
        void shouldReturnAllowWithCapsWhenCapsPresent() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-caps");
            luaMap.put("expires_at", 8888888L);
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme/app:myapp", "remaining", 5000, "reserved", 0, "spent", 5000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json"))
                    .thenReturn("{\"max_tokens\":100}");

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-caps");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW_WITH_CAPS);
            assertThat(response.getCaps()).isNotNull();
            assertThat(response.getCaps().getMaxTokens()).isEqualTo(100);
        }

        @Test
        void shouldThrowOnScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "BUDGET_EXCEEDED", "message", "over limit"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-err");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_EXCEEDED);
        }

        @Test
        void shouldHandleIdempotencyHit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            // Idempotency hit: response has reservation_id but no expires_at
            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "existing-res"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hgetAll("reservation:res_existing-res")).thenReturn(reservationFields("existing-res", "ACTIVE"));
            Response<Map<String, String>> budgetResp = mock(Response.class);
            when(budgetResp.get()).thenReturn(budgetMap(10000, 5000, 0, 5000));
            when(pipeline.hgetAll(anyString())).thenReturn(budgetResp);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-dup");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            assertThat(response.getReservationId()).isEqualTo("existing-res");
        }
    }

    // ---- createReservation dry_run ----

    @Nested
    @DisplayName("createReservation dry_run")
    class CreateReservationDryRun {

        @Test
        void shouldAllowDryRunWhenBudgetSufficient() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-1");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            assertThat(response.getReservationId()).isNull();
            assertThat(response.getBalances()).isNotEmpty();
        }

        @Test
        void shouldDenyDryRunWhenBudgetExceeded() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> lowBudget = budgetMap(10000, 100, 0, 9900);
            mockBudget("budget:tenant:acme:USD_MICROCENTS", lowBudget);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-exceed");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("BUDGET_EXCEEDED");
        }

        @Test
        void shouldDenyDryRunWhenBudgetFrozen() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> frozen = budgetMap(10000, 8000, 0, 2000);
            frozen.put("status", "FROZEN");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", frozen);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-frozen");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("BUDGET_FROZEN");
        }

        @Test
        void shouldDenyDryRunWhenNoBudgetFound() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            // Pipeline default already returns empty map — no budget mocking needed

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-none");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("BUDGET_NOT_FOUND");
        }

        @Test
        void shouldDenyDryRunWhenDebtOutstanding() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> debtBudget = budgetMap(10000, 8000, 0, 2000);
            debtBudget.put("debt", "500");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", debtBudget);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-debt");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("DEBT_OUTSTANDING");
        }

        @Test
        void shouldDenyDryRunWhenOverdraftLimitExceeded() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> overlimit = budgetMap(10000, 8000, 0, 2000);
            overlimit.put("is_over_limit", "true");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", overlimit);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-overlimit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        }
    }

    // ---- commitReservation ----

    @Nested
    @DisplayName("commitReservation")
    class CommitReservationTest {

        @Test
        void shouldCommitSuccessfully() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 7000, "reserved", 0, "spent", 3000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-1");

            CommitResponse response = repository.commitReservation("res1", request);

            assertThat(response.getStatus()).isEqualTo(Enums.CommitStatus.COMMITTED);
            assertThat(response.getCharged().getAmount()).isEqualTo(3000L);
            assertThat(response.getReleased()).isNotNull();
            assertThat(response.getReleased().getAmount()).isEqualTo(2000L);
        }

        @Test
        void shouldCommitWithNoReleasedWhenActualEqualsEstimate() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 5000);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 5000, "reserved", 0, "spent", 5000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 5000L));
            request.setIdempotencyKey("commit-2");

            CommitResponse response = repository.commitReservation("res2", request);

            assertThat(response.getReleased()).isNull();
        }

        @Test
        void shouldThrowOnCommitScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "RESERVATION_FINALIZED", "message", "already committed"));
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-err");

            assertThatThrownBy(() -> repository.commitReservation("res-done", request))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_FINALIZED);
        }

        @Test
        void shouldHandleMissingEstimateDataOnCommit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(Map.of("charged", 3000));
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-noest");

            CommitResponse response = repository.commitReservation("resNoEst", request);

            assertThat(response.getStatus()).isEqualTo(Enums.CommitStatus.COMMITTED);
            assertThat(response.getCharged().getAmount()).isEqualTo(3000L);
            assertThat(response.getReleased()).isNull();
            assertThat(response.getBalances()).isEmpty();
        }

        @Test
        void shouldThrowOnCommitNotFoundError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "NOT_FOUND", "message", "res-notfound"));
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-notfound");

            assertThatThrownBy(() -> repository.commitReservation("res-notfound", request))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldReturnReleasedAmountOnIdempotentCommitReplay() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua idempotency hit returns estimate_amount/estimate_unit (no balances)
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-idem");
            luaMap.put("state", "COMMITTED");
            luaMap.put("charged", 3000);
            luaMap.put("debt_incurred", 0);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-replay");

            CommitResponse response = repository.commitReservation("res-idem", request);

            assertThat(response.getStatus()).isEqualTo(Enums.CommitStatus.COMMITTED);
            assertThat(response.getCharged().getAmount()).isEqualTo(3000L);
            assertThat(response.getReleased()).isNotNull();
            assertThat(response.getReleased().getAmount()).isEqualTo(2000L);
        }
    }

    // ---- releaseReservation ----

    @Nested
    @DisplayName("releaseReservation")
    class ReleaseReservationTest {

        @Test
        void shouldReleaseSuccessfully() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res3");
            luaMap.put("state", "RELEASED");
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 10000, "reserved", 0, "spent", 0, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-1").build();

            ReleaseResponse response = repository.releaseReservation("res3", request);

            assertThat(response.getStatus()).isEqualTo(Enums.ReleaseStatus.RELEASED);
            assertThat(response.getReleased().getAmount()).isEqualTo(5000L);
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
        }

        @Test
        void shouldFallbackToZeroWhenEstimateMissing() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res4");
            luaMap.put("state", "RELEASED");
            // No estimate_amount or estimate_unit — fallback path
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-2").build();

            ReleaseResponse response = repository.releaseReservation("res4", request);

            assertThat(response.getReleased().getAmount()).isEqualTo(0L);
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
        }

        @Test
        void shouldThrowOnReleaseScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "NOT_FOUND", "message", "no such reservation"));
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-err").build();

            assertThatThrownBy(() -> repository.releaseReservation("res-gone", request))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldReturnReleasedAmountOnIdempotentReleaseReplay() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua idempotency hit returns estimate_amount/estimate_unit
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-idem-rel");
            luaMap.put("state", "RELEASED");
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-replay").build();

            ReleaseResponse response = repository.releaseReservation("res-idem-rel", request);

            assertThat(response.getStatus()).isEqualTo(Enums.ReleaseStatus.RELEASED);
            assertThat(response.getReleased().getAmount()).isEqualTo(5000L);
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
        }
    }

    // ---- extendReservation ----

    @Nested
    @DisplayName("extendReservation")
    class ExtendReservationTest {

        @Test
        void shouldExtendSuccessfully() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new HashMap<>();
            luaMap.put("expires_at_ms", 1234567890L);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme",
                    "remaining", 5000, "reserved", 5000, "spent", 0, "allocated", 10000,
                    "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("extend"), eq("EXTEND_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-1");

            ReservationExtendResponse response = repository.extendReservation("res5", request, "acme");

            assertThat(response.getStatus()).isEqualTo(Enums.ExtendStatus.ACTIVE);
            assertThat(response.getExpiresAtMs()).isEqualTo(1234567890L);
            assertThat(response.getBalances()).isNotEmpty();
        }

        @Test
        void shouldThrowOnExtendScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "RESERVATION_EXPIRED", "message", "expired"));
            when(luaScripts.eval(eq(jedis), eq("extend"), eq("EXTEND_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-err");

            assertThatThrownBy(() -> repository.extendReservation("res-exp", request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_EXPIRED);
        }

        @Test
        void shouldHandleMissingBalancesOnExtend() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua response without balances (e.g., reservation with no scopes)
            String luaResponse = objectMapper.writeValueAsString(Map.of("expires_at_ms", 1700000090000L));
            when(luaScripts.eval(eq(jedis), eq("extend"), eq("EXTEND_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-nopre");

            ReservationExtendResponse response = repository.extendReservation("extNoPre", request, "acme");

            assertThat(response.getStatus()).isEqualTo(Enums.ExtendStatus.ACTIVE);
            assertThat(response.getExpiresAtMs()).isEqualTo(1700000090000L);
            assertThat(response.getBalances()).isEmpty();
        }

        @Test
        void shouldThrowOnExtendNotFoundError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "NOT_FOUND", "message", "res-notfound"));
            when(luaScripts.eval(eq(jedis), eq("extend"), eq("EXTEND_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-notfound");

            assertThatThrownBy(() -> repository.extendReservation("res-notfound", request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }
    }

    // ---- decide ----

    @Nested
    @DisplayName("decide")
    class DecideTest {

        @Test
        void shouldAllowWhenBudgetSufficient() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-1");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            assertThat(response.getAffectedScopes()).isEqualTo(defaultScopes());
        }

        @Test
        void shouldDenyWhenBudgetExceeded() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> low = budgetMap(10000, 100, 0, 9900);
            mockBudget("budget:tenant:acme:USD_MICROCENTS", low);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-deny");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("BUDGET_EXCEEDED");
        }

        @Test
        void shouldDenyWhenBudgetClosed() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> closed = budgetMap(10000, 8000, 0, 2000);
            closed.put("status", "CLOSED");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", closed);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-closed");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("BUDGET_CLOSED");
        }

        @Test
        void shouldDenyWhenNoBudgetFound() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            // Pipeline default already returns empty map — no budget mocking needed

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-nobudget");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("BUDGET_NOT_FOUND");
        }

        @Test
        void shouldAllowWithCapsWhenCapsConfigured() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockCaps("budget:tenant:acme/app:myapp:USD_MICROCENTS", "{\"max_tokens\":50}");

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-caps");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW_WITH_CAPS);
            assertThat(response.getCaps().getMaxTokens()).isEqualTo(50);
        }

        @Test
        void shouldReplayCachedIdempotencyResult() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            DecisionResponse cached = DecisionResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .affectedScopes(defaultScopes())
                    .build();
            when(jedis.get("idem:acme:decide:cached-key")).thenReturn(objectMapper.writeValueAsString(cached));

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("cached-key");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            // Should not call deriveScopes since cached
            verify(scopeService, never()).deriveScopes(any());
        }

        @Test
        void shouldDenyWhenBudgetFrozen() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> frozen = budgetMap(10000, 8000, 0, 2000);
            frozen.put("status", "FROZEN");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", frozen);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-frozen");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("BUDGET_FROZEN");
        }

        @Test
        void shouldDenyWhenOverdraftLimitExceeded() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> overLimit = budgetMap(10000, 8000, 0, 2000);
            overLimit.put("is_over_limit", "true");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", overLimit);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-overlimit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        }

        @Test
        void shouldDenyWhenDebtOutstanding() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> debtBudget = budgetMap(10000, 8000, 0, 2000);
            debtBudget.put("debt", "1000");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", debtBudget);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-debt");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo("DEBT_OUTSTANDING");
        }

        @Test
        void shouldThrowIdempotencyMismatchOnHashMismatch() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String cachedJson = "{\"decision\":\"ALLOW\",\"affected_scopes\":[\"tenant:acme\"]}";
            when(jedis.get("idem:acme:decide:decide-mismatch")).thenReturn(cachedJson);
            when(jedis.get("idem:acme:decide:decide-mismatch:hash")).thenReturn("different-hash-value");

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-mismatch");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.decide(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.IDEMPOTENCY_MISMATCH);
        }

        @Test
        void shouldSkipScopesWithoutBudgets() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            // First scope has no budget, second has sufficient budget
            mockBudget("budget:tenant:acme:USD_MICROCENTS", Map.of());
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-skip");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }

        @Test
        void shouldStoreIdempotencyResultAfterDecision() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-store");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            repository.decide(request, "acme");

            // Verify idempotency key was stored
            verify(jedis).psetex(eq("idem:acme:decide:decide-store"), eq(86400000L), anyString());
        }
    }

    // ---- createEvent ----

    @Nested
    @DisplayName("createEvent")
    class CreateEventTest {

        @Test
        void shouldCreateEventSuccessfully() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> luaMap = new HashMap<>();
            luaMap.put("status", "ok");
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme",
                    "remaining", 7000, "reserved", 0, "spent", 3000, "allocated", 10000,
                    "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("event"), eq("EVENT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            EventCreateRequest request = EventCreateRequest.builder()
                    .idempotencyKey("event-1")
                    .subject(defaultSubject())
                    .action(defaultAction())
                    .actual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L))
                    .build();

            EventCreateResponse response = repository.createEvent(request, "acme");

            assertThat(response.getStatus()).isEqualTo(Enums.EventStatus.APPLIED);
            assertThat(response.getEventId()).isNotNull();
            assertThat(response.getBalances()).isNotEmpty();
        }

        @Test
        void shouldUseExistingEventIdOnIdempotencyHit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(Map.of("event_id", "existing-event-123"));
            when(luaScripts.eval(eq(jedis), eq("event"), eq("EVENT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            EventCreateRequest request = EventCreateRequest.builder()
                    .idempotencyKey("event-dup")
                    .subject(defaultSubject())
                    .action(defaultAction())
                    .actual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L))
                    .build();

            EventCreateResponse response = repository.createEvent(request, "acme");

            assertThat(response.getEventId()).isEqualTo("existing-event-123");
        }

        @Test
        void shouldThrowOnEventScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "BUDGET_EXCEEDED", "message", "no budget"));
            when(luaScripts.eval(eq(jedis), eq("event"), eq("EVENT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            EventCreateRequest request = EventCreateRequest.builder()
                    .idempotencyKey("event-err")
                    .subject(defaultSubject())
                    .action(defaultAction())
                    .actual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L))
                    .build();

            assertThatThrownBy(() -> repository.createEvent(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_EXCEEDED);
        }

        @Test
        void shouldThrowOnEventBudgetNotFound() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "BUDGET_NOT_FOUND", "scope", "tenant:acme"));
            when(luaScripts.eval(eq(jedis), eq("event"), eq("EVENT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            EventCreateRequest request = EventCreateRequest.builder()
                    .idempotencyKey("event-nobudget")
                    .subject(defaultSubject())
                    .action(defaultAction())
                    .actual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L))
                    .build();

            assertThatThrownBy(() -> repository.createEvent(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldThrowOnEventIdempotencyMismatch() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "IDEMPOTENCY_MISMATCH"));
            when(luaScripts.eval(eq(jedis), eq("event"), eq("EVENT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            EventCreateRequest request = EventCreateRequest.builder()
                    .idempotencyKey("event-mismatch")
                    .subject(defaultSubject())
                    .action(defaultAction())
                    .actual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L))
                    .build();

            assertThatThrownBy(() -> repository.createEvent(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.IDEMPOTENCY_MISMATCH);
        }
    }

    // ---- getBalances ----

    @Nested
    @DisplayName("getBalances")
    class GetBalancesTest {

        @SuppressWarnings("unchecked")
        @Test
        void shouldReturnBalancesForTenant() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> budget = budgetMap(10000, 5000, 2000, 3000);
            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme/app:myapp:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budget);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, false, 100, null);

            assertThat(response.getBalances()).hasSize(1);
            Balance b = response.getBalances().get(0);
            assertThat(b.getScope()).isEqualTo("app:myapp");
            assertThat(b.getRemaining().getAmount()).isEqualTo(5000L);
            assertThat(b.getReserved().getAmount()).isEqualTo(2000L);
            assertThat(b.getSpent().getAmount()).isEqualTo(3000L);
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByTenant() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> budget = budgetMap(10000, 5000, 0, 5000);
            budget.put("scope", "tenant:other/app:myapp");
            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:other/app:myapp:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            mockBudget("budget:tenant:other/app:myapp:USD_MICROCENTS", budget);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, false, 100, null);

            assertThat(response.getBalances()).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldReturnEmptyWhenNoBudgets() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of());
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, false, 100, null);

            assertThat(response.getBalances()).isEmpty();
            assertThat(response.getHasMore()).isFalse();
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByAppScope() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> myappBudget = budgetMap(50000, 45000, 5000, 0);
            myappBudget.put("scope", "tenant:acme/app:myapp");
            Map<String, String> otherappBudget = budgetMap(30000, 25000, 5000, 0);
            otherappBudget.put("scope", "tenant:acme/app:otherapp");

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of(
                    "budget:tenant:acme/app:myapp:USD_MICROCENTS",
                    "budget:tenant:acme/app:otherapp:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);

            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", myappBudget);
            mockBudget("budget:tenant:acme/app:otherapp:USD_MICROCENTS", otherappBudget);

            BalanceResponse response = repository.getBalances("acme", null, "myapp", null, null, null, false, 100, null);

            assertThat(response.getBalances()).hasSize(1);
            assertThat(response.getBalances().get(0).getScopePath()).isEqualTo("tenant:acme/app:myapp");
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldIncludeDebtAndOverdraftWhenPresent() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> budget = budgetMap(100000, 95000, 5000, 0);
            budget.put("debt", "500");
            budget.put("overdraft_limit", "10000");
            budget.put("is_over_limit", "true");

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme/app:myapp:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budget);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, false, 100, null);

            assertThat(response.getBalances()).hasSize(1);
            Balance b = response.getBalances().get(0);
            assertThat(b.getDebt()).isNotNull();
            assertThat(b.getDebt().getAmount()).isEqualTo(500L);
            assertThat(b.getOverdraftLimit()).isNotNull();
            assertThat(b.getOverdraftLimit().getAmount()).isEqualTo(10000L);
            assertThat(b.getIsOverLimit()).isTrue();
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldRespectLimitOnBalances() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> b1 = budgetMap(100000, 95000, 5000, 0);
            b1.put("scope", "tenant:acme");
            Map<String, String> b2 = budgetMap(50000, 45000, 5000, 0);
            b2.put("scope", "tenant:acme/app:myapp");

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of(
                    "budget:tenant:acme:USD_MICROCENTS",
                    "budget:tenant:acme/app:myapp:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("55");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);

            mockBudget("budget:tenant:acme:USD_MICROCENTS", b1);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, false, 1, null);

            assertThat(response.getBalances()).hasSize(1);
            assertThat(response.getHasMore()).isTrue();
            assertThat(response.getNextCursor()).isEqualTo("55");
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldSkipEmptyBudgetEntries() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            mockBudget("budget:tenant:acme:USD_MICROCENTS", Map.of());

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, false, 100, null);

            assertThat(response.getBalances()).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldOmitDebtAndOverdraftWhenZero() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> budget = budgetMap(100000, 95000, 5000, 0);
            // debt=0, overdraft_limit=0, is_over_limit=false (defaults from budgetMap)

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme/app:myapp:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budget);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, false, 100, null);

            assertThat(response.getBalances()).hasSize(1);
            Balance b = response.getBalances().get(0);
            assertThat(b.getDebt()).isNull();
            assertThat(b.getOverdraftLimit()).isNull();
            assertThat(b.getIsOverLimit()).isNull();
        }
    }

    // ---- listReservations ----

    @Nested
    @DisplayName("listReservations")
    class ListReservationsTest {

        @SuppressWarnings("unchecked")
        @Test
        void shouldReturnEmptyListWhenNoReservations() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of());
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);

            ReservationListResponse response = repository.listReservations(
                    "acme", null, null, null, null, null, null, null, 100, null);

            assertThat(response.getReservations()).isEmpty();
            assertThat(response.getHasMore()).isFalse();
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByStatus() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> fields = reservationFields("r1", "COMMITTED");
            Pipeline pipeline = mock(Pipeline.class);
            Response<Map<String, String>> resp = mock(Response.class);
            when(resp.get()).thenReturn(fields);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("reservation:res_r1"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp);

            // Filter for ACTIVE but reservation is COMMITTED
            ReservationListResponse response = repository.listReservations(
                    "acme", null, "ACTIVE", null, null, null, null, null, 100, null);

            assertThat(response.getReservations()).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByTenantExcludingOtherTenants() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> acmeFields = reservationFields("r1", "ACTIVE");
            Map<String, String> otherFields = reservationFields("r2", "ACTIVE");
            otherFields.put("tenant", "other-corp");

            Pipeline pipeline = mock(Pipeline.class);
            Response<Map<String, String>> resp1 = mock(Response.class);
            Response<Map<String, String>> resp2 = mock(Response.class);
            when(resp1.get()).thenReturn(acmeFields);
            when(resp2.get()).thenReturn(otherFields);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("reservation:res_r1", "reservation:res_r2"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp1);
            when(pipeline.hgetAll("reservation:res_r2")).thenReturn(resp2);

            ReservationListResponse response = repository.listReservations(
                    "acme", null, null, null, null, null, null, null, 100, null);

            assertThat(response.getReservations()).hasSize(1);
            assertThat(response.getReservations().get(0).getReservationId()).isEqualTo("r1");
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByWorkspaceSubjectField() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> devFields = reservationFields("r1", "ACTIVE");
            devFields.put("scope_path", "tenant:acme/workspace:dev/app:myapp");
            Map<String, String> prodFields = reservationFields("r2", "ACTIVE");
            prodFields.put("scope_path", "tenant:acme/workspace:prod/app:myapp");

            Pipeline pipeline = mock(Pipeline.class);
            Response<Map<String, String>> resp1 = mock(Response.class);
            Response<Map<String, String>> resp2 = mock(Response.class);
            when(resp1.get()).thenReturn(devFields);
            when(resp2.get()).thenReturn(prodFields);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("reservation:res_r1", "reservation:res_r2"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp1);
            when(pipeline.hgetAll("reservation:res_r2")).thenReturn(resp2);

            ReservationListResponse response = repository.listReservations(
                    "acme", null, null, "dev", null, null, null, null, 100, null);

            assertThat(response.getReservations()).hasSize(1);
            assertThat(response.getReservations().get(0).getReservationId()).isEqualTo("r1");
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByAppSubjectField() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> myappFields = reservationFields("r1", "ACTIVE");
            myappFields.put("scope_path", "tenant:acme/app:myapp");
            Map<String, String> otherappFields = reservationFields("r2", "ACTIVE");
            otherappFields.put("scope_path", "tenant:acme/app:otherapp");

            Pipeline pipeline = mock(Pipeline.class);
            Response<Map<String, String>> resp1 = mock(Response.class);
            Response<Map<String, String>> resp2 = mock(Response.class);
            when(resp1.get()).thenReturn(myappFields);
            when(resp2.get()).thenReturn(otherappFields);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("reservation:res_r1", "reservation:res_r2"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp1);
            when(pipeline.hgetAll("reservation:res_r2")).thenReturn(resp2);

            ReservationListResponse response = repository.listReservations(
                    "acme", null, null, null, "myapp", null, null, null, 100, null);

            assertThat(response.getReservations()).hasSize(1);
            assertThat(response.getReservations().get(0).getReservationId()).isEqualTo("r1");
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldRespectLimitAndReturnHasMore() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> r1Fields = reservationFields("r1", "ACTIVE");
            Map<String, String> r2Fields = reservationFields("r2", "ACTIVE");

            Pipeline pipeline = mock(Pipeline.class);
            Response<Map<String, String>> resp1 = mock(Response.class);
            Response<Map<String, String>> resp2 = mock(Response.class);
            when(resp1.get()).thenReturn(r1Fields);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("reservation:res_r1", "reservation:res_r2"));
            when(scanResult.getCursor()).thenReturn("42");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp1);
            when(pipeline.hgetAll("reservation:res_r2")).thenReturn(resp1);

            ReservationListResponse response = repository.listReservations(
                    "acme", null, null, null, null, null, null, null, 1, null);

            assertThat(response.getReservations()).hasSize(1);
            assertThat(response.getHasMore()).isTrue();
            assertThat(response.getNextCursor()).isEqualTo("42");
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldReturnMatchingStatusFilter() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> committedFields = reservationFields("r1", "COMMITTED");

            Pipeline pipeline = mock(Pipeline.class);
            Response<Map<String, String>> resp = mock(Response.class);
            when(resp.get()).thenReturn(committedFields);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("reservation:res_r1"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp);

            // Filter for COMMITTED and reservation IS COMMITTED
            ReservationListResponse response = repository.listReservations(
                    "acme", null, "COMMITTED", null, null, null, null, null, 100, null);

            assertThat(response.getReservations()).hasSize(1);
            assertThat(response.getReservations().get(0).getReservationId()).isEqualTo("r1");
        }
    }

    // ---- handleScriptError additional cases ----

    @Nested
    @DisplayName("handleScriptError additional cases")
    class HandleScriptErrorAdditional {

        @Test
        void shouldThrowBudgetFrozen() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "BUDGET_FROZEN", "scope", "tenant:acme")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_FROZEN);
        }

        @Test
        void shouldThrowBudgetClosed() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "BUDGET_CLOSED", "scope", "tenant:acme")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_CLOSED);
        }

        @Test
        void shouldThrowReservationExpirationNotFound() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "RESERVATION_EXPIRATION_NOT_FOUND", "message", "no ttl")))
                    .isInstanceOf(CyclesProtocolException.class);
        }

        @Test
        void shouldUseScopeFromResponseForBudgetNotFound() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "BUDGET_NOT_FOUND")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldUseScopeFromResponseForBudgetFrozen() {
            // Without scope key — fallback to "unknown"
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "BUDGET_FROZEN")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_FROZEN);
        }

        @Test
        void shouldUseScopeFromResponseForBudgetClosed() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "BUDGET_CLOSED")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_CLOSED);
        }
    }

    // ---- evaluateDryRun additional cases ----

    @Nested
    @DisplayName("createReservation dry_run additional")
    class CreateReservationDryRunAdditional {

        @Test
        void shouldReplayIdempotencyCacheOnDryRun() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            ReservationCreateResponse cached = ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .affectedScopes(defaultScopes())
                    .scopePath("tenant:acme/app:myapp")
                    .build();
            when(jedis.get("idem:acme:dry_run:dry-cached")).thenReturn(objectMapper.writeValueAsString(cached));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-cached");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            // Should not scan budgets since cached
            verify(jedis, never()).hgetAll(startsWith("budget:"));
        }

        @Test
        void shouldThrowIdempotencyMismatchOnDryRunCacheReplay() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            when(jedis.get("idem:acme:dry_run:dry-mismatch")).thenReturn("{\"decision\":\"ALLOW\"}");
            when(jedis.get("idem:acme:dry_run:dry-mismatch:hash")).thenReturn("stale-hash-value");

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-mismatch");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.IDEMPOTENCY_MISMATCH);
        }

        @Test
        void shouldReturnAllowWithCapsOnDryRun() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockCaps("budget:tenant:acme/app:myapp:USD_MICROCENTS", "{\"max_tokens\":200,\"max_steps_remaining\":5}");

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-caps");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW_WITH_CAPS);
            assertThat(response.getCaps().getMaxTokens()).isEqualTo(200);
            assertThat(response.getCaps().getMaxStepsRemaining()).isEqualTo(5);
        }

        @Test
        void shouldCacheIdempotencyResultOnDryRun() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-store");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            repository.createReservation(request, "acme");

            verify(jedis).psetex(eq("idem:acme:dry_run:dry-store"), eq(86400000L), anyString());
        }
    }

    // ---- buildReservationSummary additional cases ----

    @Nested
    @DisplayName("getReservationById additional cases")
    class GetReservationByIdAdditional {

        @Test
        void shouldParseCommittedReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res-committed", "COMMITTED");
            fields.put("charged_amount", "3000");
            fields.put("committed_at", "1700001000000");
            when(jedis.hgetAll("reservation:res_res-committed")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res-committed");

            assertThat(detail.getStatus()).isEqualTo(Enums.ReservationStatus.COMMITTED);
            assertThat(detail.getCommitted()).isNotNull();
            assertThat(detail.getCommitted().getAmount()).isEqualTo(3000L);
            assertThat(detail.getFinalizedAtMs()).isEqualTo(1700001000000L);
        }

        @Test
        void shouldParseReleasedReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res-released", "RELEASED");
            fields.put("released_at", "1700002000000");
            when(jedis.hgetAll("reservation:res_res-released")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res-released");

            assertThat(detail.getStatus()).isEqualTo(Enums.ReservationStatus.RELEASED);
            assertThat(detail.getFinalizedAtMs()).isEqualTo(1700002000000L);
        }

        @Test
        void shouldParseMetadata() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res-meta", "ACTIVE");
            fields.put("metadata_json", "{\"model\":\"gpt-4\",\"temperature\":0.7}");
            when(jedis.hgetAll("reservation:res_res-meta")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res-meta");

            assertThat(detail.getMetadata()).isNotNull();
            assertThat(detail.getMetadata()).containsEntry("model", "gpt-4");
        }

        @Test
        void shouldThrowOnCorruptedData() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = new HashMap<>();
            fields.put("reservation_id", "res-corrupt");
            fields.put("state", "ACTIVE");
            // Missing required fields: estimate_unit, estimate_amount, subject_json, etc.
            when(jedis.hgetAll("reservation:res_res-corrupt")).thenReturn(fields);

            assertThatThrownBy(() -> repository.getReservationById("res-corrupt"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // ---- getBalances additional filter cases ----

    @Nested
    @DisplayName("getBalances additional filters")
    class GetBalancesAdditionalFilters {

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByWorkspace() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> budget = budgetMap(10000, 5000, 2000, 3000);
            budget.put("scope", "tenant:acme/workspace:prod/app:myapp");
            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme/workspace:prod/app:myapp:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            Response<Map<String, String>> budgetResp = mock(Response.class);
            when(budgetResp.get()).thenReturn(budget);
            when(pipeline.hgetAll("budget:tenant:acme/workspace:prod/app:myapp:USD_MICROCENTS")).thenReturn(budgetResp);

            BalanceResponse response = repository.getBalances("acme", "prod", null, null, null, null, false, 100, null);
            assertThat(response.getBalances()).hasSize(1);

            // Wrong workspace should filter out
            BalanceResponse filtered = repository.getBalances("acme", "staging", null, null, null, null, false, 100, null);
            assertThat(filtered.getBalances()).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByWorkflow() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> budget = budgetMap(10000, 5000, 2000, 3000);
            budget.put("scope", "tenant:acme/workflow:onboarding");
            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme/workflow:onboarding:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            Response<Map<String, String>> budgetResp = mock(Response.class);
            when(budgetResp.get()).thenReturn(budget);
            when(pipeline.hgetAll("budget:tenant:acme/workflow:onboarding:USD_MICROCENTS")).thenReturn(budgetResp);

            BalanceResponse response = repository.getBalances("acme", null, null, "onboarding", null, null, false, 100, null);
            assertThat(response.getBalances()).hasSize(1);
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByAgent() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> budget = budgetMap(10000, 5000, 2000, 3000);
            budget.put("scope", "tenant:acme/agent:summarizer");
            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme/agent:summarizer:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            Response<Map<String, String>> budgetResp = mock(Response.class);
            when(budgetResp.get()).thenReturn(budget);
            when(pipeline.hgetAll("budget:tenant:acme/agent:summarizer:USD_MICROCENTS")).thenReturn(budgetResp);

            BalanceResponse response = repository.getBalances("acme", null, null, null, "summarizer", null, false, 100, null);
            assertThat(response.getBalances()).hasSize(1);
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByToolset() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> budget = budgetMap(10000, 5000, 2000, 3000);
            budget.put("scope", "tenant:acme/toolset:search");
            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme/toolset:search:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            Response<Map<String, String>> budgetResp = mock(Response.class);
            when(budgetResp.get()).thenReturn(budget);
            when(pipeline.hgetAll("budget:tenant:acme/toolset:search:USD_MICROCENTS")).thenReturn(budgetResp);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, "search", false, 100, null);
            assertThat(response.getBalances()).hasSize(1);
        }
    }

    // ---- Generic exception wrapping ----

    @Nested
    @DisplayName("generic exception wrapping")
    class GenericExceptionWrapping {

        @Test
        void shouldWrapGenericExceptionInCreateReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                .thenThrow(new IllegalStateException("Unexpected error"));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldWrapGenericExceptionInCommitReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                .thenThrow(new IllegalStateException("Unexpected error"));

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-wrap");

            assertThatThrownBy(() -> repository.commitReservation("res-wrap", request))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldWrapGenericExceptionInReleaseReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class)))
                .thenThrow(new IllegalStateException("Unexpected error"));

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-wrap").build();

            assertThatThrownBy(() -> repository.releaseReservation("res-wrap", request))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldWrapGenericExceptionInExtendReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(luaScripts.eval(eq(jedis), eq("extend"), eq("EXTEND_SCRIPT"), any(String[].class)))
                .thenThrow(new IllegalStateException("Unexpected error"));

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-wrap");

            assertThatThrownBy(() -> repository.extendReservation("res-wrap", request, "acme"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldWrapGenericExceptionInCreateEvent() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(luaScripts.eval(eq(jedis), eq("event"), eq("EVENT_SCRIPT"), any(String[].class)))
                .thenThrow(new IllegalStateException("Unexpected error"));

            EventCreateRequest request = EventCreateRequest.builder()
                .idempotencyKey("event-wrap")
                .subject(defaultSubject())
                .action(defaultAction())
                .actual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L))
                .build();

            assertThatThrownBy(() -> repository.createEvent(request, "acme"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldWrapGenericExceptionInGetReservationById() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hgetAll("reservation:res_wrap-id"))
                .thenThrow(new IllegalStateException("Unexpected error"));

            assertThatThrownBy(() -> repository.getReservationById("wrap-id"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    // ---- createReservation idempotency with empty existing fields ----

    @Nested
    @DisplayName("createReservation idempotency edge cases")
    class CreateReservationIdempotencyEdgeCases {

        @Test
        void shouldThrowNotFoundWhenIdempotencyHitWithEmptyExistingFields() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            // Idempotency hit: response has reservation_id but no expires_at
            String luaResponse = objectMapper.writeValueAsString(
                Map.of("reservation_id", "vanished-res"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            // Existing reservation fields are empty (expired/evicted)
            when(jedis.hgetAll("reservation:res_vanished-res")).thenReturn(Map.of());

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-vanished");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldThrowNotFoundWhenIdempotencyHitWithNullExistingFields() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(
                Map.of("reservation_id", "null-res"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hgetAll("reservation:res_null-res")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-null");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }
    }

    // ---- dry-run idempotency edge cases ----

    @Nested
    @DisplayName("createReservation dry_run idempotency edge cases")
    class DryRunIdempotencyEdgeCases {

        @Test
        void shouldReplayCacheWhenStoredHashIsNull() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            ReservationCreateResponse cached = ReservationCreateResponse.builder()
                .decision(Enums.DecisionEnum.ALLOW)
                .affectedScopes(defaultScopes())
                .scopePath("tenant:acme/app:myapp")
                .build();
            when(jedis.get("idem:acme:dry_run:dry-no-hash")).thenReturn(objectMapper.writeValueAsString(cached));
            // storedHash is null — no hash was stored
            when(jedis.get("idem:acme:dry_run:dry-no-hash:hash")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-no-hash");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }
    }

    // ---- getBalances with includeChildren ----

    @Nested
    @DisplayName("getBalances with includeChildren")
    class GetBalancesIncludeChildren {

        @SuppressWarnings("unchecked")
        @Test
        void shouldReturnBalancesWithIncludeChildrenTrue() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> parentBudget = budgetMap(100000, 80000, 10000, 10000);
            parentBudget.put("scope", "tenant:acme");
            Map<String, String> childBudget = budgetMap(50000, 40000, 5000, 5000);
            childBudget.put("scope", "tenant:acme/app:myapp");

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of(
                "budget:tenant:acme:USD_MICROCENTS",
                "budget:tenant:acme/app:myapp:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);

            Response<Map<String, String>> parentResp = mock(Response.class);
            when(parentResp.get()).thenReturn(parentBudget);
            Response<Map<String, String>> childResp = mock(Response.class);
            when(childResp.get()).thenReturn(childBudget);
            when(pipeline.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(parentResp);
            when(pipeline.hgetAll("budget:tenant:acme/app:myapp:USD_MICROCENTS")).thenReturn(childResp);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, true, 100, null);

            assertThat(response.getBalances()).hasSize(2);
        }
    }

    // ---- listReservations with idempotency_key filter ----

    @Nested
    @DisplayName("listReservations with idempotency_key filter")
    class ListReservationsIdempotencyFilter {

        @SuppressWarnings("unchecked")
        @Test
        void shouldFilterByIdempotencyKey() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> r1Fields = reservationFields("r1", "ACTIVE");
            r1Fields.put("idempotency_key", "idem-abc");
            Map<String, String> r2Fields = reservationFields("r2", "ACTIVE");
            r2Fields.put("idempotency_key", "idem-xyz");

            Pipeline pipeline = mock(Pipeline.class);
            Response<Map<String, String>> resp1 = mock(Response.class);
            Response<Map<String, String>> resp2 = mock(Response.class);
            when(resp1.get()).thenReturn(r1Fields);
            when(resp2.get()).thenReturn(r2Fields);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("reservation:res_r1", "reservation:res_r2"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp1);
            when(pipeline.hgetAll("reservation:res_r2")).thenReturn(resp2);

            ReservationListResponse response = repository.listReservations(
                "acme", "idem-abc", null, null, null, null, null, null, 100, null);

            assertThat(response.getReservations()).hasSize(1);
            assertThat(response.getReservations().get(0).getReservationId()).isEqualTo("r1");
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldReturnEmptyWhenIdempotencyKeyDoesNotMatch() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> fields = reservationFields("r1", "ACTIVE");
            fields.put("idempotency_key", "idem-abc");

            Pipeline pipeline = mock(Pipeline.class);
            Response<Map<String, String>> resp = mock(Response.class);
            when(resp.get()).thenReturn(fields);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("reservation:res_r1"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp);

            ReservationListResponse response = repository.listReservations(
                "acme", "idem-nonexistent", null, null, null, null, null, null, 100, null);

            assertThat(response.getReservations()).isEmpty();
        }
    }

    // ---- createReservation with capsJson (ALLOW_WITH_CAPS) ----

    @Nested
    @DisplayName("createReservation ALLOW_WITH_CAPS")
    class CreateReservationWithCaps {

        @Test
        void shouldReturnAllowWithCapsWhenCapsJsonPresent() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> luaResult = new LinkedHashMap<>();
            luaResult.put("reservation_id", "res-caps");
            luaResult.put("expires_at", 1700060000000L);
            luaResult.put("balances", List.of(
                Map.of("scope", "tenant:acme", "remaining", 80000, "reserved", 10000, "spent", 10000, "allocated", 100000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false),
                Map.of("scope", "tenant:acme/app:myapp", "remaining", 40000, "reserved", 5000, "spent", 5000, "allocated", 50000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)
            ));
            String luaResponse = objectMapper.writeValueAsString(luaResult);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            // Budget with caps_json
            String capsJson = "{\"max_tokens\":1000}";
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(capsJson);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW_WITH_CAPS);
            assertThat(response.getCaps()).isNotNull();
        }
    }

    // ---- releaseReservation with null estimate data ----

    @Nested
    @DisplayName("releaseReservation edge cases")
    class ReleaseReservationEdgeCases {

        @Test
        void shouldFallbackToZeroWhenEstimateDataMissing() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua response without estimate_amount/estimate_unit — fallback to zero
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-null-est");
            luaMap.put("state", "RELEASED");
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("rel-null").build();
            ReleaseResponse response = repository.releaseReservation("res-null-est", request);

            assertThat(response.getReleased().getAmount()).isZero();
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
        }
    }

    // ---- decide with generic exception wrapping ----

    @Nested
    @DisplayName("decide exception wrapping")
    class DecideExceptionWrapping {

        @Test
        void shouldWrapGenericExceptionInDecide() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenThrow(new IllegalStateException("Unexpected"));

            DecisionRequest request = DecisionRequest.builder()
                .idempotencyKey("decide-wrap")
                .subject(defaultSubject())
                .action(defaultAction())
                .estimate(defaultEstimate())
                .build();

            assertThatThrownBy(() -> repository.decide(request, "acme"))
                .isInstanceOf(RuntimeException.class);
        }
    }

    // ---- findReservationTenantById with generic exception wrapping ----

    @Nested
    @DisplayName("findReservationTenantById exception wrapping")
    class FindTenantExceptionWrapping {

        @Test
        void shouldWrapGenericExceptionInFindTenant() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hget(anyString(), eq("tenant")))
                .thenThrow(new IllegalStateException("Unexpected"));

            assertThatThrownBy(() -> repository.findReservationTenantById("err-id"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to resolve reservation tenant");
        }
    }

    // ---- getReservationById with committed/released/metadata fields ----

    @Nested
    @DisplayName("getReservationById with extended fields")
    class GetReservationByIdExtendedFields {

        @Test
        void shouldParseChargedAmountAndCommittedAt() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("committed-res", "COMMITTED");
            fields.put("charged_amount", "3000");
            fields.put("committed_at", "1700050000000");
            when(jedis.hgetAll("reservation:res_committed-res")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("committed-res");

            assertThat(detail.getCommitted()).isNotNull();
            assertThat(detail.getCommitted().getAmount()).isEqualTo(3000L);
            assertThat(detail.getFinalizedAtMs()).isEqualTo(1700050000000L);
        }

        @Test
        void shouldParseReleasedAt() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("released-res", "RELEASED");
            fields.put("released_at", "1700055000000");
            when(jedis.hgetAll("reservation:res_released-res")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("released-res");

            assertThat(detail.getFinalizedAtMs()).isEqualTo(1700055000000L);
        }

        @Test
        void shouldParseMetadataJson() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("meta-res", "ACTIVE");
            fields.put("metadata_json", "{\"env\":\"production\",\"team\":\"backend\"}");
            when(jedis.hgetAll("reservation:res_meta-res")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("meta-res");

            assertThat(detail.getMetadata()).containsEntry("env", "production");
            assertThat(detail.getMetadata()).containsEntry("team", "backend");
        }
    }

    // ---- handleScriptError RESERVATION_EXPIRATION_NOT_FOUND ----

    @Nested
    @DisplayName("handleScriptError additional cases")
    class HandleScriptErrorMore {

        @Test
        void shouldThrowForReservationExpirationNotFound() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "RESERVATION_EXPIRATION_NOT_FOUND")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                    .hasFieldOrPropertyWithValue("httpStatus", 500);
        }

        @Test
        void shouldIncludeScopeInBudgetFrozenError() {
            Map<String, Object> resp = new HashMap<>();
            resp.put("error", "BUDGET_FROZEN");
            resp.put("scope", "tenant:acme/app:myapp");
            assertThatThrownBy(() -> invokeHandleScriptError(resp))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_FROZEN)
                    .hasMessageContaining("tenant:acme/app:myapp");
        }

        @Test
        void shouldIncludeScopeInBudgetClosedError() {
            Map<String, Object> resp = new HashMap<>();
            resp.put("error", "BUDGET_CLOSED");
            resp.put("scope", "tenant:acme");
            assertThatThrownBy(() -> invokeHandleScriptError(resp))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_CLOSED)
                    .hasMessageContaining("tenant:acme");
        }

        @Test
        void shouldUseFallbackScopeWhenScopeFieldMissing() {
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "BUDGET_FROZEN")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasMessageContaining("unknown");
        }
    }

    // ---- Branch coverage tests: null defaults, catch blocks, ternary edges ----

    @Nested
    @DisplayName("createReservation null defaults")
    class CreateReservationNullDefaults {

        @Test
        void shouldUseDefaultOveragePolicyWhenNull() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-def");
            luaMap.put("expires_at", 9999999L);
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 5000, "reserved", 0, "spent", 5000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-def");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setOveragePolicy(null);  // null → defaults to REJECT
            request.setTtlMs(null);          // null → defaults to 60000
            request.setGracePeriodMs(null);   // null → defaults to 5000
            request.setMetadata(null);        // null → empty string

            ReservationCreateResponse response = repository.createReservation(request, "acme");
            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }

        @Test
        void shouldUseExplicitOveragePolicyWhenProvided() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-pol");
            luaMap.put("expires_at", 9999999L);
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 5000, "reserved", 0, "spent", 5000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-pol");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setOveragePolicy(Enums.CommitOveragePolicy.ALLOW_WITH_OVERDRAFT);
            request.setTtlMs(30000L);
            request.setGracePeriodMs(10000L);
            request.setMetadata(Map.of("key", "value"));

            ReservationCreateResponse response = repository.createReservation(request, "acme");
            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }
    }

    @Nested
    @DisplayName("listReservations error handling")
    class ListReservationsErrorHandling {

        @SuppressWarnings("unchecked")
        @Test
        void shouldSkipMalformedReservationInList() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // One valid reservation, one that will cause a parse error (missing required fields)
            Map<String, String> validFields = reservationFields("r1", "ACTIVE");
            Map<String, String> brokenFields = new HashMap<>();
            brokenFields.put("reservation_id", "r2");
            // Missing all other fields → will throw during buildReservationSummary

            Pipeline pipeline = mock(Pipeline.class);
            Response<Map<String, String>> resp1 = mock(Response.class);
            Response<Map<String, String>> resp2 = mock(Response.class);
            when(resp1.get()).thenReturn(brokenFields);
            when(resp2.get()).thenReturn(validFields);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("reservation:res_r2", "reservation:res_r1"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r2")).thenReturn(resp1);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp2);

            ReservationListResponse response = repository.listReservations(
                    "acme", null, null, null, null, null, null, null, 100, null);

            // Broken reservation skipped, valid one returned
            assertThat(response.getReservations()).hasSize(1);
            assertThat(response.getReservations().get(0).getReservationId()).isEqualTo("r1");
        }
    }

    @Nested
    @DisplayName("getBalances error handling")
    class GetBalancesErrorHandling {

        @SuppressWarnings("unchecked")
        @Test
        void shouldSkipBudgetWithInvalidUnitEnum() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> invalidBudget = new HashMap<>(budgetMap(10000, 5000, 0, 5000));
            invalidBudget.put("unit", "INVALID_UNIT");

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme/app:myapp:INVALID_UNIT"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            Response<Map<String, String>> budgetResp = mock(Response.class);
            when(budgetResp.get()).thenReturn(invalidBudget);
            when(pipeline.hgetAll("budget:tenant:acme/app:myapp:INVALID_UNIT")).thenReturn(budgetResp);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, false, 100, null);

            // Should skip the invalid entry
            assertThat(response.getBalances()).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldSkipBudgetWithMalformedNumericData() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> malformedBudget = new HashMap<>(budgetMap(10000, 5000, 0, 5000));
            malformedBudget.put("allocated", "not-a-number");

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of("budget:tenant:acme/app:myapp:USD_MICROCENTS"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            Response<Map<String, String>> budgetResp = mock(Response.class);
            when(budgetResp.get()).thenReturn(malformedBudget);
            when(pipeline.hgetAll("budget:tenant:acme/app:myapp:USD_MICROCENTS")).thenReturn(budgetResp);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, false, 100, null);

            // Should skip the malformed entry
            assertThat(response.getBalances()).isEmpty();
        }
    }

    // ---- Tenant default resolution ----

    @Nested
    @DisplayName("Tenant default resolution")
    class TenantDefaultResolution {

        private String tenantJson(String policy, Long defaultTtl, Long maxTtl, Integer maxExt) throws Exception {
            Map<String, Object> tenant = new HashMap<>();
            tenant.put("tenant_id", "acme");
            tenant.put("name", "Acme");
            tenant.put("status", "ACTIVE");
            if (policy != null) tenant.put("default_commit_overage_policy", policy);
            if (defaultTtl != null) tenant.put("default_reservation_ttl_ms", defaultTtl);
            if (maxTtl != null) tenant.put("max_reservation_ttl_ms", maxTtl);
            if (maxExt != null) tenant.put("max_reservation_extensions", maxExt);
            return objectMapper.writeValueAsString(tenant);
        }

        private String[] captureReserveArgs() throws Exception {
            var captor = org.mockito.ArgumentCaptor.forClass(String[].class);
            verify(luaScripts).eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), captor.capture());
            return captor.getValue();
        }

        private String[] captureEventArgs() throws Exception {
            var captor = org.mockito.ArgumentCaptor.forClass(String[].class);
            verify(luaScripts).eval(eq(jedis), eq("event"), eq("EVENT_SCRIPT"), captor.capture());
            return captor.getValue();
        }

        @Test
        void shouldUseTenantsDefaultOveragePolicyWhenRequestOmits() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn(
                    tenantJson("ALLOW_IF_AVAILABLE", null, null, null));

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "res-tenant-pol", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // balances now come from Lua response (no separate hgetAll needed)

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-tenant-pol");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            // overagePolicy is null (not set) → should fall back to tenant default

            repository.createReservation(request, "acme");

            // ARGV[11] = args[10] = overage_policy
            String[] args = captureReserveArgs();
            assertThat(args[10]).isEqualTo("ALLOW_IF_AVAILABLE");
        }

        @Test
        void shouldPreferExplicitOveragePolicyOverTenantDefault() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn(
                    tenantJson("ALLOW_IF_AVAILABLE", null, null, null));

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "res-explicit-pol", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // balances now come from Lua response (no separate hgetAll needed)

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-explicit-pol");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setOveragePolicy(Enums.CommitOveragePolicy.REJECT);

            repository.createReservation(request, "acme");

            String[] args = captureReserveArgs();
            assertThat(args[10]).isEqualTo("REJECT");
        }

        @Test
        void shouldFallBackToAllowIfAvailableWhenNoTenantRecord() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn(null);  // no tenant record

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "res-no-tenant", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // balances now come from Lua response (no separate hgetAll needed)

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-no-tenant");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            repository.createReservation(request, "acme");

            String[] args = captureReserveArgs();
            assertThat(args[10]).isEqualTo("ALLOW_IF_AVAILABLE");
        }

        @Test
        void shouldUseTenantsDefaultTtlWhenRequestOmits() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn(
                    tenantJson(null, 120000L, null, null));

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "res-ttl", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // balances now come from Lua response (no separate hgetAll needed)

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-ttl");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setTtlMs(null);  // null → use tenant default 120000

            repository.createReservation(request, "acme");

            // ARGV[6] = args[5] = ttl_ms
            String[] args = captureReserveArgs();
            assertThat(args[5]).isEqualTo("120000");
        }

        @Test
        void shouldCapTtlToTenantMaxTtl() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn(
                    tenantJson(null, null, 30000L, null));

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "res-cap-ttl", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // balances now come from Lua response (no separate hgetAll needed)

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-cap-ttl");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setTtlMs(86400000L);  // request wants 24h, tenant max is 30s

            repository.createReservation(request, "acme");

            String[] args = captureReserveArgs();
            assertThat(args[5]).isEqualTo("30000");
        }

        @Test
        void shouldPassMaxExtensionsToReserveScript() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn(
                    tenantJson(null, null, null, 5));

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "res-maxext", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // balances now come from Lua response (no separate hgetAll needed)

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-maxext");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            repository.createReservation(request, "acme");

            // ARGV[14] = args[13] = max_extensions
            String[] args = captureReserveArgs();
            assertThat(args[13]).isEqualTo("5");
        }

        @Test
        void shouldUseTenantsDefaultOveragePolicyForEvents() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn(
                    tenantJson("ALLOW_WITH_OVERDRAFT", null, null, null));

            String luaResponse = objectMapper.writeValueAsString(Map.of("status", "ok"));
            when(luaScripts.eval(eq(jedis), eq("event"), eq("EVENT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            EventCreateRequest request = EventCreateRequest.builder()
                    .idempotencyKey("event-tenant-pol")
                    .subject(defaultSubject())
                    .action(defaultAction())
                    .actual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L))
                    .build();

            repository.createEvent(request, "acme");

            // ARGV[9] = args[8] = overage_policy for events
            String[] args = captureEventArgs();
            assertThat(args[8]).isEqualTo("ALLOW_WITH_OVERDRAFT");
        }

        @Test
        void shouldHandleMaxExtensionsExceededError() throws Throwable {
            Map<String, Object> response = new HashMap<>();
            response.put("error", "MAX_EXTENSIONS_EXCEEDED");
            response.put("message", "Maximum reservation extensions (5) reached");

            assertThatThrownBy(() -> invokeHandleScriptError(response))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.MAX_EXTENSIONS_EXCEEDED);
        }

        @Test
        void shouldGracefullyHandleMalformedTenantJson() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn("{invalid json!!!");

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "res-bad-tenant", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // balances now come from Lua response (no separate hgetAll needed)

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-bad-tenant");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            // Should not throw — falls back to ALLOW_IF_AVAILABLE
            ReservationCreateResponse response = repository.createReservation(request, "acme");
            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);

            // Verify fallback to ALLOW_IF_AVAILABLE
            String[] args = captureReserveArgs();
            assertThat(args[10]).isEqualTo("ALLOW_IF_AVAILABLE");
        }

        @Test
        void shouldPreferExplicitTtlOverTenantDefault() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn(
                    tenantJson(null, 120000L, 300000L, null));

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "res-ttl-explicit", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // balances now come from Lua response (no separate hgetAll needed)

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-ttl-explicit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setTtlMs(45000L);  // explicit 45s, tenant default is 120s

            repository.createReservation(request, "acme");

            // Explicit request TTL takes precedence over tenant default
            String[] args = captureReserveArgs();
            assertThat(args[5]).isEqualTo("45000");
        }
    }

    // ---- Helper to build reservation fields ----

    private Map<String, String> reservationFields(String id, String state) {
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

    // ---- parseLuaBalances edge case tests ----

    @Nested
    @DisplayName("parseLuaBalances edge cases")
    class ParseLuaBalancesEdgeCases {

        @Test
        void shouldReturnEmptyListWhenBalancesFieldMissing() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua response has no "balances" key
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("no-bal");

            CommitResponse response = repository.commitReservation("res-no-bal", request);
            assertThat(response.getBalances()).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenBalancesIsNotAList() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua response has "balances" as a string, not a list
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", "not-a-list");
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("bad-bal");

            CommitResponse response = repository.commitReservation("res-bad-bal", request);
            assertThat(response.getBalances()).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenBalancesArrayIsEmpty() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of());
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("empty-bal");

            CommitResponse response = repository.commitReservation("res-empty-bal", request);
            assertThat(response.getBalances()).isEmpty();
        }

        @Test
        void shouldDefaultMissingNumericFieldsToZero() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Balance entry missing most numeric fields
            Map<String, Object> balEntry = new LinkedHashMap<>();
            balEntry.put("scope", "tenant:acme");
            balEntry.put("remaining", 100);
            // debt, overdraft_limit, reserved, spent, allocated all missing

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(balEntry));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("partial-bal");

            CommitResponse response = repository.commitReservation("res-partial", request);
            assertThat(response.getBalances()).hasSize(1);
            Balance b = response.getBalances().get(0);
            assertThat(b.getRemaining().getAmount()).isEqualTo(100L);
            assertThat(b.getReserved().getAmount()).isZero();
            assertThat(b.getSpent().getAmount()).isZero();
            assertThat(b.getDebt()).isNull();  // debt=0 → null per spec
        }

        @Test
        void shouldHandleIsOverLimitBooleanCorrectly() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(
                Map.of("scope", "tenant:acme", "remaining", 100, "reserved", 0, "spent", 0,
                       "allocated", 1000, "debt", 500, "overdraft_limit", 200, "is_over_limit", true),
                Map.of("scope", "tenant:acme/app:myapp", "remaining", 50, "reserved", 0, "spent", 0,
                       "allocated", 500, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)
            ));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("over-limit");

            CommitResponse response = repository.commitReservation("res-overlimit", request);
            assertThat(response.getBalances()).hasSize(2);
            assertThat(response.getBalances().get(0).getIsOverLimit()).isTrue();
            assertThat(response.getBalances().get(0).getDebt().getAmount()).isEqualTo(500L);
            assertThat(response.getBalances().get(1).getIsOverLimit()).isNull();  // false → null
            assertThat(response.getBalances().get(1).getDebt()).isNull();  // debt=0 → null
        }
    }

    // ---- Lua response format edge cases ----

    @Nested
    @DisplayName("Lua response format edge cases")
    class LuaResponseFormatEdgeCases {

        @Test
        void shouldHandleCommitWithEstimateUnitButNoAmount() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            // estimate_amount missing → released should be null
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("no-est-amt");

            CommitResponse response = repository.commitReservation("res-noamt", request);
            assertThat(response.getCharged().getAmount()).isEqualTo(3000L);
            assertThat(response.getReleased()).isNull();
        }

        @Test
        void shouldHandleReleaseWithEstimateAmountButNoUnit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-nounit");
            luaMap.put("state", "RELEASED");
            luaMap.put("estimate_amount", 5000);
            // estimate_unit missing → fallback to 0 USD_MICROCENTS
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("no-unit").build();
            ReleaseResponse response = repository.releaseReservation("res-nounit", request);

            assertThat(response.getReleased().getAmount()).isZero();
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
        }
    }

    // ---- getTenantConfig cache edge cases ----

    @Nested
    @DisplayName("getTenantConfig cache edge cases")
    class TenantConfigCacheEdgeCases {

        @Test
        void shouldReturnNullWhenTenantNotInRedis() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:unknown")).thenReturn(null);

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-notenant");
            luaMap.put("expires_at", 9999999L);
            luaMap.put("balances", List.of());
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "unknown");
            // Should succeed with defaults (REJECT policy, 60s TTL)
            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }

        @Test
        void shouldHandleMalformedTenantJsonGracefully() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(jedis.get("tenant:acme")).thenReturn("{invalid json!!!");

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-badjson");
            luaMap.put("expires_at", 9999999L);
            luaMap.put("balances", List.of());
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            // Should succeed with defaults despite JSON parse failure
            ReservationCreateResponse response = repository.createReservation(request, "acme");
            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }
    }
}
