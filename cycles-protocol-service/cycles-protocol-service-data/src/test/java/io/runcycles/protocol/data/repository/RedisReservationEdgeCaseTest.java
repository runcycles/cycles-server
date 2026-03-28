package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RedisReservationRepository - Edge Cases")
class RedisReservationEdgeCaseTest extends BaseRedisReservationRepositoryTest {

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

    // ---- findReservationTenantById exception wrapping ----

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

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-tenant-pol");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

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

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-ttl");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setTtlMs(null);  // null -> use tenant default 120000

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

    // ---- parseLuaBalances edge cases ----

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
            assertThat(b.getDebt()).isNull();  // debt=0 -> null per spec
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
            assertThat(response.getBalances().get(1).getIsOverLimit()).isNull();  // false -> null
            assertThat(response.getBalances().get(1).getDebt()).isNull();  // debt=0 -> null
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
            // estimate_amount missing -> released should be null
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
            // estimate_unit missing -> fallback to 0 USD_MICROCENTS
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
