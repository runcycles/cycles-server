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

@DisplayName("RedisReservationRepository - CRUD")
class RedisReservationCrudTest extends BaseRedisReservationRepositoryTest {

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

    // ---- getReservationById additional cases ----

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

    // ---- getReservationById with extended fields ----

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

        @SuppressWarnings("unchecked")
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_EXCEEDED);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_FROZEN);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_NOT_FOUND);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.DEBT_OUTSTANDING);
        }

        @Test
        void shouldAllowDryRunWhenDebtWithinOverdraftLimit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> debtBudget = budgetMap(10000, 8000, 0, 2000);
            debtBudget.put("debt", "500");
            debtBudget.put("overdraft_limit", "1000");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", debtBudget);
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", debtBudget);
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-debt-within-limit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.OVERDRAFT_LIMIT_EXCEEDED);
        }
    }

    // ---- createReservation dry_run additional ----

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
            // Mock pipeline.get() for idempotency check (pipelined in evaluateDryRun)
            Response<String> cachedResp = mock(Response.class);
            when(cachedResp.get()).thenReturn(objectMapper.writeValueAsString(cached));
            when(pipeline.get("idem:acme:dry_run:dry-cached")).thenReturn(cachedResp);

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

            // Mock pipeline.get() for idempotency check (pipelined in evaluateDryRun)
            Response<String> cachedResp = mock(Response.class);
            when(cachedResp.get()).thenReturn("{\"decision\":\"ALLOW\"}");
            when(pipeline.get("idem:acme:dry_run:dry-mismatch")).thenReturn(cachedResp);
            Response<String> hashResp = mock(Response.class);
            when(hashResp.get()).thenReturn("stale-hash-value");
            when(pipeline.get("idem:acme:dry_run:dry-mismatch:hash")).thenReturn(hashResp);

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

            verify(pipeline).psetex(eq("idem:acme:dry_run:dry-store"), eq(86400000L), anyString());
        }
    }

    // ---- createReservation null defaults ----

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
            request.setOveragePolicy(null);  // null -> defaults to REJECT
            request.setTtlMs(null);          // null -> defaults to 60000
            request.setGracePeriodMs(null);   // null -> defaults to 5000
            request.setMetadata(null);        // null -> empty string

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

    // ---- createReservation idempotency edge cases ----

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

    // ---- createReservation ALLOW_WITH_CAPS ----

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
}
