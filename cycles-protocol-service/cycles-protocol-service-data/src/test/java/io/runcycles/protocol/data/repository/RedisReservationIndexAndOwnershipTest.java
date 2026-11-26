package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.repository.support.BudgetIndexCursor;
import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RedisReservationRepository - budget index listing + in-script ownership")
class RedisReservationIndexAndOwnershipTest extends BaseRedisReservationRepositoryTest {

    private static final String K_TENANT = "budget:tenant:acme:USD_MICROCENTS";
    private static final String K_APP_A = "budget:tenant:acme/app:a:USD_MICROCENTS";
    private static final String K_APP_B = "budget:tenant:acme/app:b:USD_MICROCENTS";

    private void stubConnection() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
    }

    private void indexBudget(String key, String scope) {
        Map<String, String> budget = budgetMap(10000, 5000, 2000, 3000);
        budget.put("scope", scope);
        mockBudget(key, budget);
    }

    private void seedThreeIndexedBudgets() {
        // HashSet: deliberately unordered, the repository must sort
        when(jedis.smembers("budgets:acme")).thenReturn(new HashSet<>(List.of(K_APP_B, K_TENANT, K_APP_A)));
        indexBudget(K_TENANT, "tenant:acme");
        indexBudget(K_APP_A, "tenant:acme/app:a");
        indexBudget(K_APP_B, "tenant:acme/app:b");
    }

    @Nested
    @DisplayName("getBalances via budgets:<tenant> index")
    class IndexedBalances {

        @BeforeEach
        void enableGovernanceIndex() throws Exception {
            setField("useGovernanceBudgetIndex", true);
        }

        @Test
        void usesIndexInsteadOfKeyspaceScan() {
            stubConnection();
            seedThreeIndexedBudgets();

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, true, 100, null);

            // lexicographic by budget key: '/' sorts before ':'
            assertThat(response.getBalances()).extracting(Balance::getScopePath)
                .containsExactly("tenant:acme/app:a", "tenant:acme/app:b", "tenant:acme");
            assertThat(response.getHasMore()).isFalse();
            assertThat(response.getNextCursor()).isNull();
            verify(jedis, never()).scan(anyString(), any(ScanParams.class));
        }

        @Test
        void paginatesWithKeysetCursor() {
            stubConnection();
            seedThreeIndexedBudgets();

            BalanceResponse page1 = repository.getBalances("acme", null, null, null, null, null, true, 2, null);
            assertThat(page1.getBalances()).extracting(Balance::getScopePath)
                .containsExactly("tenant:acme/app:a", "tenant:acme/app:b");
            assertThat(page1.getHasMore()).isTrue();
            assertThat(BudgetIndexCursor.decode(page1.getNextCursor())).isEqualTo(K_APP_B);

            BalanceResponse page2 = repository.getBalances("acme", null, null, null, null, null, true, 2,
                page1.getNextCursor());
            assertThat(page2.getBalances()).extracting(Balance::getScopePath)
                .containsExactly("tenant:acme");
            assertThat(page2.getHasMore()).isFalse();
            assertThat(page2.getNextCursor()).isNull();
        }

        @Test
        void pageEndingOnLastKeyHasNoCursor() {
            stubConnection();
            seedThreeIndexedBudgets();

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, true, 3, null);

            assertThat(response.getBalances()).hasSize(3);
            assertThat(response.getHasMore()).isFalse();
            assertThat(response.getNextCursor()).isNull();
        }

        @Test
        void appliesSubjectFiltersAndSkipsUnreadableRows() {
            stubConnection();
            String empty = "budget:tenant:acme/app:gone:USD_MICROCENTS";
            String noScope = "budget:tenant:acme/app:noscope:USD_MICROCENTS";
            String badUnit = "budget:tenant:acme/app:badunit:USD_MICROCENTS";
            String badNumber = "budget:tenant:acme/app:badnum:USD_MICROCENTS";
            when(jedis.smembers("budgets:acme")).thenReturn(
                new HashSet<>(List.of(K_TENANT, K_APP_A, K_APP_B, empty, noScope, badUnit, badNumber)));
            indexBudget(K_TENANT, "tenant:acme");
            indexBudget(K_APP_A, "tenant:acme/app:a");
            indexBudget(K_APP_B, "TENANT:ACME/APP:B"); // legacy mixed-case scope
            mockBudget(empty, new HashMap<>());
            Map<String, String> withoutScope = budgetMap(1, 1, 0, 0);
            withoutScope.remove("scope");
            mockBudget(noScope, withoutScope);
            Map<String, String> wrongUnit = budgetMap(1, 1, 0, 0);
            wrongUnit.put("scope", "tenant:acme/app:b");
            wrongUnit.put("unit", "NOT_A_UNIT");
            mockBudget(badUnit, wrongUnit);
            Map<String, String> wrongNumber = budgetMap(1, 1, 0, 0);
            wrongNumber.put("scope", "tenant:acme/app:b");
            wrongNumber.put("remaining", "not-a-number");
            mockBudget(badNumber, wrongNumber);

            BalanceResponse response = repository.getBalances("acme", null, "b", null, null, null, true, 100, null);

            assertThat(response.getBalances()).extracting(Balance::getScopePath)
                .containsExactly("tenant:acme/app:b");
        }

        @Test
        void pipelinesLargeIndexesInBatches() {
            stubConnection();
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < 150; i++) {
                String scope = String.format("tenant:acme/app:a%03d", i);
                String key = "budget:" + scope + ":USD_MICROCENTS";
                keys.add(key);
                indexBudget(key, scope);
            }
            when(jedis.smembers("budgets:acme")).thenReturn(keys);

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, true, 200, null);

            assertThat(response.getBalances()).hasSize(150);
            assertThat(response.getHasMore()).isFalse();
            verify(pipeline, times(2)).sync();
        }

        @Test
        void indexCursorWithVanishedIndexReturnsEmptyPage() {
            stubConnection();
            when(jedis.smembers("budgets:acme")).thenReturn(Collections.emptySet());

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, true, 100,
                BudgetIndexCursor.encode(K_APP_A));

            assertThat(response.getBalances()).isEmpty();
            assertThat(response.getHasMore()).isFalse();
            verify(jedis, never()).scan(anyString(), any(ScanParams.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void legacyScanCursorKeepsUsingScan() {
            stubConnection();
            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of(K_TENANT));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("17"), any(ScanParams.class))).thenReturn(scanResult);
            indexBudget(K_TENANT, "tenant:acme");

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, true, 100, "17");

            assertThat(response.getBalances()).hasSize(1);
            verify(jedis, never()).smembers(anyString());
        }

        @SuppressWarnings("unchecked")
        @Test
        void missingIndexFallsBackToScan() {
            stubConnection();
            when(jedis.smembers("budgets:acme")).thenReturn(null);
            ScanResult<String> scanResult = mock(ScanResult.class);
            when(scanResult.getResult()).thenReturn(List.of(K_TENANT));
            when(scanResult.getCursor()).thenReturn("0");
            when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(scanResult);
            indexBudget(K_TENANT, "tenant:acme");

            BalanceResponse response = repository.getBalances("acme", null, null, null, null, null, true, 100, " ");

            assertThat(response.getBalances()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("reservation ownership")
    class Ownership {

        @Test
        void getReservationByIdRejectsForeignTenantBeforeRevealingState() {
            stubConnection();
            // EXPIRED would normally answer 410 - a foreign caller must get 403 instead
            mockReservationHash("reservation:res_r1", reservationFields("r1", "EXPIRED"));

            assertThatThrownBy(() -> repository.getReservationById("r1", "intruder"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.FORBIDDEN)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
        }

        @Test
        void getReservationByIdAllowsOwnerAndAdmin() {
            stubConnection();
            mockReservationHash("reservation:res_r1", reservationFields("r1", "ACTIVE"));

            assertThat(repository.getReservationById("r1", "acme").getReservationId()).isEqualTo("r1");
            assertThat(repository.getReservationById("r1", null).getReservationId()).isEqualTo("r1");
        }

        @Test
        void commitPassesExpectedOwnerAndMapsScriptForbiddenTo403() throws Exception {
            stubConnection();
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                .thenReturn(objectMapper.writeValueAsString(Map.of("error", "FORBIDDEN")));
            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("idem-own");

            assertThatThrownBy(() -> repository.commitReservation("r1", request, "intruder"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.FORBIDDEN)
                .hasFieldOrPropertyWithValue("httpStatus", 403);

            ArgumentCaptor<String[]> args = ArgumentCaptor.forClass(String[].class);
            verify(luaScripts).eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), args.capture());
            assertThat(args.getValue()).hasSize(8);
            assertThat(args.getValue()[7]).isEqualTo("intruder");
        }

        @Test
        void scriptTenantSuspendedMapsTo403Forbidden() throws Exception {
            stubConnection();
            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("idem-susp");

            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                .thenReturn(objectMapper.writeValueAsString(Map.of("error", "TENANT_SUSPENDED", "tenant", "acme")));
            assertThatThrownBy(() -> repository.commitReservation("r1", request, "acme"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.FORBIDDEN)
                .hasFieldOrPropertyWithValue("httpStatus", 403)
                .hasMessageContaining("acme")
                .hasMessageContaining("suspended");

            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                .thenReturn(objectMapper.writeValueAsString(Map.of("error", "TENANT_SUSPENDED")));
            assertThatThrownBy(() -> repository.commitReservation("r1", request, "acme"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasMessageContaining("unknown");
        }
    }
}
