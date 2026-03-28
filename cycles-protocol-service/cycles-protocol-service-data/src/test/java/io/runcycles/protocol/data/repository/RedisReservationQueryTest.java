package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RedisReservationRepository - Query")
class RedisReservationQueryTest extends BaseRedisReservationRepositoryTest {

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

    // ---- getBalances additional filters ----

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

    // ---- getBalances error handling ----

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

    // ---- listReservations error handling ----

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
            // Missing all other fields -> will throw during buildReservationSummary

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
}
