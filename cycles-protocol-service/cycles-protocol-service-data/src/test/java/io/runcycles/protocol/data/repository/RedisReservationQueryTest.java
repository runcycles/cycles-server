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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                    "acme", null, null, null, null, null, null, null, 100, null, null, null);

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
                    "acme", null, "ACTIVE", null, null, null, null, null, 100, null, null, null);

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
                    "acme", null, null, null, null, null, null, null, 100, null, null, null);

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
                    "acme", null, null, "dev", null, null, null, null, 100, null, null, null);

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
                    "acme", null, null, null, "myapp", null, null, null, 100, null, null, null);

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
                    "acme", null, null, null, null, null, null, null, 1, null, null, null);

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
                    "acme", null, "COMMITTED", null, null, null, null, null, 100, null, null, null);

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
                "acme", "idem-abc", null, null, null, null, null, null, 100, null, null, null);

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
                "acme", "idem-nonexistent", null, null, null, null, null, null, 100, null, null, null);

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
                    "acme", null, null, null, null, null, null, null, 100, null, null, null);

            // Broken reservation skipped, valid one returned
            assertThat(response.getReservations()).hasSize(1);
            assertThat(response.getReservations().get(0).getReservationId()).isEqualTo("r1");
        }
    }

    // v0.1.25.12 (cycles-protocol revision 2026-04-16): sorted-path pagination.
    // When sort_by or sort_dir is supplied, repository takes the sorted path,
    // loads all matching rows, in-memory sorts, and returns an opaque cursor
    // encoding the sort state so subsequent pages stay in order.
    @Nested
    @DisplayName("listReservations — sorted path")
    class SortedListReservationsTest {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("sort_by=created_at_ms asc paginates in ascending timestamp order")
        void sortsByCreatedAtAsc() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> f1 = reservationFields("r1", "ACTIVE");
            f1.put("created_at", "300");
            Map<String, String> f2 = reservationFields("r2", "ACTIVE");
            f2.put("created_at", "100");
            Map<String, String> f3 = reservationFields("r3", "ACTIVE");
            f3.put("created_at", "200");

            Response<Map<String, String>> resp1 = mock(Response.class);
            Response<Map<String, String>> resp2 = mock(Response.class);
            Response<Map<String, String>> resp3 = mock(Response.class);
            when(resp1.get()).thenReturn(f1);
            when(resp2.get()).thenReturn(f2);
            when(resp3.get()).thenReturn(f3);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(
                List.of("reservation:res_r1", "reservation:res_r2", "reservation:res_r3"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp1);
            when(pipeline.hgetAll("reservation:res_r2")).thenReturn(resp2);
            when(pipeline.hgetAll("reservation:res_r3")).thenReturn(resp3);

            ReservationListResponse response = repository.listReservations(
                "acme", null, null, null, null, null, null, null, 100, null,
                "created_at_ms", "asc");

            assertThat(response.getReservations()).extracting(ReservationSummary::getReservationId)
                .containsExactly("r2", "r3", "r1");
            assertThat(response.getHasMore()).isFalse();
            assertThat(response.getNextCursor()).isNull();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("paginates via cursor across two pages preserving sort order")
        void paginatesAcrossPages() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> f1 = reservationFields("r1", "ACTIVE"); f1.put("created_at", "100");
            Map<String, String> f2 = reservationFields("r2", "ACTIVE"); f2.put("created_at", "200");
            Map<String, String> f3 = reservationFields("r3", "ACTIVE"); f3.put("created_at", "300");
            Map<String, String> f4 = reservationFields("r4", "ACTIVE"); f4.put("created_at", "400");

            Response<Map<String, String>> resp1 = mock(Response.class);
            Response<Map<String, String>> resp2 = mock(Response.class);
            Response<Map<String, String>> resp3 = mock(Response.class);
            Response<Map<String, String>> resp4 = mock(Response.class);
            when(resp1.get()).thenReturn(f1);
            when(resp2.get()).thenReturn(f2);
            when(resp3.get()).thenReturn(f3);
            when(resp4.get()).thenReturn(f4);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(
                List.of("reservation:res_r1", "reservation:res_r2",
                        "reservation:res_r3", "reservation:res_r4"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp1);
            when(pipeline.hgetAll("reservation:res_r2")).thenReturn(resp2);
            when(pipeline.hgetAll("reservation:res_r3")).thenReturn(resp3);
            when(pipeline.hgetAll("reservation:res_r4")).thenReturn(resp4);

            ReservationListResponse page1 = repository.listReservations(
                "acme", null, null, null, null, null, null, null, 2, null,
                "created_at_ms", "asc");

            assertThat(page1.getReservations()).extracting(ReservationSummary::getReservationId)
                .containsExactly("r1", "r2");
            assertThat(page1.getHasMore()).isTrue();
            assertThat(page1.getNextCursor()).isNotNull();

            ReservationListResponse page2 = repository.listReservations(
                "acme", null, null, null, null, null, null, null, 2, page1.getNextCursor(),
                "created_at_ms", "asc");

            assertThat(page2.getReservations()).extracting(ReservationSummary::getReservationId)
                .containsExactly("r3", "r4");
            assertThat(page2.getHasMore()).isFalse();
            assertThat(page2.getNextCursor()).isNull();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("cursor reused with different sort_by → 400 INVALID_REQUEST")
        void cursorMismatchRejected() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, String> f1 = reservationFields("r1", "ACTIVE");
            Map<String, String> f2 = reservationFields("r2", "ACTIVE");
            Response<Map<String, String>> resp1 = mock(Response.class);
            Response<Map<String, String>> resp2 = mock(Response.class);
            when(resp1.get()).thenReturn(f1);
            when(resp2.get()).thenReturn(f2);

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(
                List.of("reservation:res_r1", "reservation:res_r2"));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);
            when(pipeline.hgetAll("reservation:res_r1")).thenReturn(resp1);
            when(pipeline.hgetAll("reservation:res_r2")).thenReturn(resp2);

            ReservationListResponse page1 = repository.listReservations(
                "acme", null, null, null, null, null, null, null, 1, null,
                "created_at_ms", "asc");

            String cursor = page1.getNextCursor();
            assertThat(cursor).isNotNull();

            // Re-use cursor under a different sort_by — MUST 400 per spec.
            assertThatThrownBy(() -> repository.listReservations(
                "acme", null, null, null, null, null, null, null, 1, cursor,
                "status", "asc"))
                .isInstanceOf(io.runcycles.protocol.data.exception.CyclesProtocolException.class)
                .hasMessageContaining("cursor is not valid");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("hydration stops at SORTED_HYDRATE_CAP; page still fills from capped slice")
        void sortedHydrationStopsAtCap() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            int cap = RedisReservationRepository.SORTED_HYDRATE_CAP;
            int total = cap + 10;

            // Return ALL keys from a single SCAN page. The hydration guard sits
            // inside the per-key loop, so the break exits after exactly `cap`
            // rows are added — remaining keys are never consulted even though
            // they're still in the scan result.
            List<String> keys = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                keys.add(String.format("reservation:res_r%05d", i));
            }

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(keys);
            // getCursor() is never read once the hydration cap breaks out of the
            // labeled scanLoop; stub leniently so strict-mode doesn't flag it.
            lenient().when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            when(jedis.pipelined()).thenReturn(pipeline);

            // Default pipeline.hgetAll stub from Base returns empty map; override
            // for the first `cap` keys so they pass the tenant filter and land
            // in the matching list. Keys beyond index `cap` are stubbed too, so
            // if the guard is ever removed the test fails loud (page fills past
            // the cap and the pipeline verifier trips).
            for (int i = 0; i < total; i++) {
                String id = String.format("r%05d", i);
                Map<String, String> f = reservationFields(id, "ACTIVE");
                f.put("created_at", String.valueOf(1_700_000_000_000L + i));
                Response<Map<String, String>> resp = mock(Response.class);
                lenient().when(resp.get()).thenReturn(f);
                lenient().when(pipeline.hgetAll("reservation:res_" + id)).thenReturn(resp);
            }

            ReservationListResponse response = repository.listReservations(
                "acme", null, null, null, null, null, null, null, 5, null,
                "created_at_ms", "asc");

            assertThat(response.getReservations()).hasSize(5);
            assertThat(response.getReservations())
                .extracting(ReservationSummary::getReservationId)
                .containsExactly("r00000", "r00001", "r00002", "r00003", "r00004");
            // has_more must be true — the capped slice still has rows beyond the page.
            assertThat(response.getHasMore()).isTrue();
            assertThat(response.getNextCursor()).isNotNull();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("legacy numeric cursor with no sort params routes to legacy SCAN path")
        void legacyCursorPreserved() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of());
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("42"), any(ScanParams.class))).thenReturn(scanResult);

            // "42" is a legacy SCAN cursor. With no sort params, repo must honour it and
            // call jedis.scan with that exact cursor value — not route to sorted path.
            ReservationListResponse response = repository.listReservations(
                "acme", null, null, null, null, null, null, null, 100, "42", null, null);

            assertThat(response.getReservations()).isEmpty();
            verify(jedis).scan(eq("42"), any(ScanParams.class));
        }
    }
}
