package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.Enums;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RedisReservationRepository - Core Ops")
class RedisReservationCoreOpsTest extends BaseRedisReservationRepositoryTest {

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
            // Legacy commit.lua path: no details in response → fall back to no-detail factory.
            assertThatThrownBy(() -> invokeHandleScriptError(
                    Map.of("error", "UNIT_MISMATCH")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.UNIT_MISMATCH)
                    .hasFieldOrPropertyWithValue("details", null);
        }

        @Test
        void shouldThrowUnitMismatchWithDetailsFromReserve() {
            // reserve.lua / event.lua populate scope + requested_unit + available_units so the
            // client can self-correct.
            Map<String, Object> resp = new HashMap<>();
            resp.put("error", "UNIT_MISMATCH");
            resp.put("scope", "tenant:rider");
            resp.put("requested_unit", "TOKENS");
            resp.put("available_units", List.of("USD_MICROCENTS"));

            assertThatThrownBy(() -> invokeHandleScriptError(resp))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.UNIT_MISMATCH)
                    .hasFieldOrPropertyWithValue("httpStatus", 400)
                    .hasMessageContaining("tenant:rider")
                    .hasMessageContaining("TOKENS")
                    .hasMessageContaining("USD_MICROCENTS")
                    .satisfies(ex -> {
                        CyclesProtocolException cex = (CyclesProtocolException) ex;
                        assertThat(cex.getDetails()).containsEntry("scope", "tenant:rider");
                        assertThat(cex.getDetails()).containsEntry("requested_unit", "TOKENS");
                        assertThat(cex.getDetails()).containsEntry("available_units",
                                List.of("USD_MICROCENTS"));
                    });
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
}
