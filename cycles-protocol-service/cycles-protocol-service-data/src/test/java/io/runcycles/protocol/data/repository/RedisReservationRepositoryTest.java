package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.service.ScopeDerivationService;
import io.runcycles.protocol.model.Enums;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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
            when(jedis.hget("reservation:res_abc123", "tenant")).thenReturn("acme-corp");

            String tenant = repository.findReservationTenantById("abc123");

            assertThat(tenant).isEqualTo("acme-corp");
        }

        @Test
        void shouldThrowNotFoundWhenTenantNull() {
            when(jedisPool.getResource()).thenReturn(jedis);
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
            when(jedis.hgetAll("reservation:res_missing")).thenReturn(Map.of());

            assertThatThrownBy(() -> repository.getReservationById("missing"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldThrowNotFoundWhenNull() {
            when(jedisPool.getResource()).thenReturn(jedis);
            when(jedis.hgetAll("reservation:res_gone")).thenReturn(null);

            assertThatThrownBy(() -> repository.getReservationById("gone"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }
    }
}
