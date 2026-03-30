package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Response;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RedisReservationRepository - Decide/Event")
class RedisReservationDecideEventTest extends BaseRedisReservationRepositoryTest {

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
            // Mock pipeline.get() for idempotency check (pipelined in decide)
            Response<String> cachedResp = mock(Response.class);
            when(cachedResp.get()).thenReturn(objectMapper.writeValueAsString(cached));
            when(pipeline.get("idem:acme:decide:cached-key")).thenReturn(cachedResp);

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
        void shouldAllowDecideWhenDebtWithinOverdraftLimit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> debtBudget = budgetMap(10000, 8000, 0, 2000);
            debtBudget.put("debt", "500");
            debtBudget.put("overdraft_limit", "1000");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", debtBudget);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-debt-within-limit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }

        @Test
        void shouldThrowIdempotencyMismatchOnHashMismatch() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String cachedJson = "{\"decision\":\"ALLOW\",\"affected_scopes\":[\"tenant:acme\"]}";
            // Mock pipeline.get() for idempotency check (pipelined in decide)
            Response<String> cachedResp = mock(Response.class);
            when(cachedResp.get()).thenReturn(cachedJson);
            when(pipeline.get("idem:acme:decide:decide-mismatch")).thenReturn(cachedResp);
            Response<String> hashResp = mock(Response.class);
            when(hashResp.get()).thenReturn("different-hash-value");
            when(pipeline.get("idem:acme:decide:decide-mismatch:hash")).thenReturn(hashResp);

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

            // Verify idempotency key was stored (pipelined write)
            verify(pipeline).psetex(eq("idem:acme:decide:decide-store"), eq(86400000L), anyString());
        }
    }

    // ---- decide exception wrapping ----

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
}
