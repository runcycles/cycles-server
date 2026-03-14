package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
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
    @Mock private ScopeDerivationService scopeService;
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
            when(jedis.eval(eq("RESERVE_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            when(jedis.hgetAll(anyString())).thenReturn(budgetMap(10000, 5000, 0, 5000));

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

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "res-caps", "expires_at", 8888888L));
            when(jedis.eval(eq("RESERVE_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json"))
                    .thenReturn("{\"max_tokens\":100}");
            when(jedis.hgetAll(anyString())).thenReturn(budgetMap(10000, 5000, 0, 5000));

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
            when(jedis.eval(eq("RESERVE_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);

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
            when(jedis.eval(eq("RESERVE_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hgetAll("reservation:res_existing-res")).thenReturn(reservationFields("existing-res", "ACTIVE"));
            when(jedis.hgetAll(startsWith("budget:"))).thenReturn(budgetMap(10000, 5000, 0, 5000));

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
            when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(budgetMap(10000, 8000, 0, 2000));
            when(jedis.hgetAll("budget:tenant:acme/app:myapp:USD_MICROCENTS")).thenReturn(budgetMap(10000, 8000, 0, 2000));
            when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

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
            when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(lowBudget);

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
            when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(frozen);

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
            when(jedis.hgetAll(startsWith("budget:"))).thenReturn(Map.of());

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
            when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(debtBudget);

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
            when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(overlimit);

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
            when(jedis.hmget("reservation:res_res1", "estimate_amount", "estimate_unit", "affected_scopes"))
                    .thenReturn(Arrays.asList("5000", "USD_MICROCENTS", "[\"tenant:acme\"]"));

            String luaResponse = objectMapper.writeValueAsString(Map.of("charged", 3000));
            when(jedis.eval(eq("COMMIT_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hgetAll(anyString())).thenReturn(budgetMap(10000, 7000, 0, 3000));

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
            when(jedis.hmget("reservation:res_res2", "estimate_amount", "estimate_unit", "affected_scopes"))
                    .thenReturn(Arrays.asList("5000", "USD_MICROCENTS", "[\"tenant:acme\"]"));

            String luaResponse = objectMapper.writeValueAsString(Map.of("charged", 5000));
            when(jedis.eval(eq("COMMIT_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hgetAll(anyString())).thenReturn(budgetMap(10000, 5000, 0, 5000));

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
            when(jedis.hmget(anyString(), any(), any(), any()))
                    .thenReturn(Arrays.asList("5000", "USD_MICROCENTS", "[\"tenant:acme\"]"));

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "RESERVATION_FINALIZED", "message", "already committed"));
            when(jedis.eval(eq("COMMIT_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-err");

            assertThatThrownBy(() -> repository.commitReservation("res-done", request))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_FINALIZED);
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
            when(jedis.hmget("reservation:res_res3", "estimate_amount", "estimate_unit", "affected_scopes"))
                    .thenReturn(Arrays.asList("5000", "USD_MICROCENTS", "[\"tenant:acme\"]"));

            String luaResponse = objectMapper.writeValueAsString(Map.of("status", "RELEASED"));
            when(jedis.eval(eq("RELEASE_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hgetAll(anyString())).thenReturn(budgetMap(10000, 10000, 0, 0));

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
            when(jedis.hmget("reservation:res_res4", "estimate_amount", "estimate_unit", "affected_scopes"))
                    .thenReturn(Arrays.asList(null, null, null));

            String luaResponse = objectMapper.writeValueAsString(Map.of("status", "RELEASED"));
            when(jedis.eval(eq("RELEASE_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-2").build();

            ReleaseResponse response = repository.releaseReservation("res4", request);

            assertThat(response.getReleased().getAmount()).isEqualTo(0L);
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
        }

        @Test
        void shouldThrowOnReleaseScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hmget(anyString(), any(), any(), any()))
                    .thenReturn(Arrays.asList("5000", "USD_MICROCENTS", null));

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "NOT_FOUND", "message", "no such reservation"));
            when(jedis.eval(eq("RELEASE_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-err").build();

            assertThatThrownBy(() -> repository.releaseReservation("res-gone", request))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
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
            when(jedis.hmget("reservation:res_res5", "estimate_unit", "affected_scopes"))
                    .thenReturn(Arrays.asList("USD_MICROCENTS", "[\"tenant:acme\"]"));

            String luaResponse = objectMapper.writeValueAsString(Map.of("expires_at_ms", 1234567890L));
            when(jedis.eval(eq("EXTEND_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hgetAll(anyString())).thenReturn(budgetMap(10000, 5000, 5000, 0));

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-1");

            ReservationExtendResponse response = repository.extendReservation("res5", request, "acme");

            assertThat(response.getStatus()).isEqualTo(Enums.ExtendStatus.ACTIVE);
            assertThat(response.getExpiresAtMs()).isEqualTo(1234567890L);
        }

        @Test
        void shouldThrowOnExtendScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hmget(anyString(), any(), any()))
                    .thenReturn(Arrays.asList("USD_MICROCENTS", null));

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "RESERVATION_EXPIRED", "message", "expired"));
            when(jedis.eval(eq("EXTEND_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-err");

            assertThatThrownBy(() -> repository.extendReservation("res-exp", request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_EXPIRED);
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
            when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(budgetMap(10000, 8000, 0, 2000));
            when(jedis.hgetAll("budget:tenant:acme/app:myapp:USD_MICROCENTS")).thenReturn(budgetMap(10000, 8000, 0, 2000));
            when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

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
            when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(low);

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
            when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(closed);

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
            when(jedis.hgetAll(startsWith("budget:"))).thenReturn(Map.of());

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
            when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(budgetMap(10000, 8000, 0, 2000));
            when(jedis.hgetAll("budget:tenant:acme/app:myapp:USD_MICROCENTS")).thenReturn(budgetMap(10000, 8000, 0, 2000));
            when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json"))
                    .thenReturn("{\"max_tokens\":50}");

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

            String luaResponse = objectMapper.writeValueAsString(Map.of("status", "ok"));
            when(jedis.eval(eq("EVENT_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hgetAll(anyString())).thenReturn(budgetMap(10000, 7000, 0, 3000));

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
            when(jedis.eval(eq("EVENT_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);
            when(jedis.hgetAll(anyString())).thenReturn(budgetMap(10000, 7000, 0, 3000));

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
            when(jedis.eval(eq("EVENT_SCRIPT"), eq(0), any(String[].class))).thenReturn(luaResponse);

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
            when(jedis.hgetAll("budget:tenant:acme/app:myapp:USD_MICROCENTS")).thenReturn(budget);

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
            when(jedis.hgetAll("budget:tenant:other/app:myapp:USD_MICROCENTS")).thenReturn(budget);

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
}
