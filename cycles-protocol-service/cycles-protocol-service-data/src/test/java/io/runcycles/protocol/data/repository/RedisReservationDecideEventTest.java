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

    // ---- tenant-status gate (shared by decide + dry_run) ----

    /**
     * Java-side twin of the Lua guards for the NON-PERSISTING evaluations
     * (spec revision v0.1.25.13, runcycles/cycles-protocol#125): a FRESH
     * dry_run/decide on a CLOSED tenant returns 200 decision=DENY with
     * reason_code=TENANT_CLOSED (never a signed ALLOW); a present-but-
     * malformed tenant record (undecodable / non-object / missing or
     * non-string status / status outside the closed ACTIVE|SUSPENDED|CLOSED
     * TenantStatus enum) fails closed with 500 INTERNAL_ERROR; absent record
     * and ACTIVE/SUSPENDED proceed unchanged.
     */
    @Nested
    @DisplayName("tenant-status gate (dry_run + decide)")
    class TenantStatusGateTest {

        private DecisionRequest decideRequest(String idemKey) {
            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey(idemKey);
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            return request;
        }

        private ReservationCreateRequest dryRunRequest(String idemKey) {
            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey(idemKey);
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);
            return request;
        }

        private void baseStubs() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
        }

        @Test
        void decideOnClosedTenant_deniesWithTenantClosed() throws Exception {
            baseStubs();
            when(jedis.get("tenant:acme"))
                    .thenReturn("{\"tenant_id\":\"acme\",\"status\":\"CLOSED\"}");

            DecisionResponse response = repository.decide(decideRequest("gate-decide-closed"), "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.TENANT_CLOSED);
            assertThat(response.getAffectedScopes()).isEqualTo(defaultScopes());
        }

        @Test
        void dryRunOnClosedTenant_deniesWithTenantClosed() throws Exception {
            baseStubs();
            when(jedis.get("tenant:acme"))
                    .thenReturn("{\"tenant_id\":\"acme\",\"status\":\"CLOSED\"}");

            ReservationCreateResponse response =
                    repository.createReservation(dryRunRequest("gate-dry-closed"), "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.TENANT_CLOSED);
            assertThat(response.getReservationId()).isNull();
        }

        @Test
        void malformedTenantRecords_failClosedWith500_onBothSurfaces() {
            baseStubs();
            String[] badRecords = {
                    "{not-json",                                   // undecodable
                    "\"CLOSED\"",                                  // non-object (string)
                    "42",                                          // non-object (number)
                    "{\"tenant_id\":\"acme\"}",                    // missing status
                    "{\"tenant_id\":\"acme\",\"status\":\"CLOZED\"}", // unknown status
                    "{\"tenant_id\":\"acme\",\"status\":\"closed\"}"  // case-sensitivity pin
            };
            int i = 0;
            for (String bad : badRecords) {
                when(jedis.get("tenant:acme")).thenReturn(bad);
                String suffix = String.valueOf(i++);

                assertThatThrownBy(() -> repository.decide(decideRequest("gate-mal-d" + suffix), "acme"))
                        .as("decide: " + bad)
                        .isInstanceOf(CyclesProtocolException.class)
                        .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                        .hasFieldOrPropertyWithValue("httpStatus", 500)
                        .hasMessageContaining("Malformed tenant record");

                assertThatThrownBy(() -> repository.createReservation(dryRunRequest("gate-mal-r" + suffix), "acme"))
                        .as("dry_run: " + bad)
                        .isInstanceOf(CyclesProtocolException.class)
                        .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                        .hasFieldOrPropertyWithValue("httpStatus", 500)
                        .hasMessageContaining("Malformed tenant record");
            }
        }

        @Test
        void activeAndSuspendedTenants_proceedToNormalEvaluation() throws Exception {
            baseStubs();
            // No budgets mocked: passing the gate lands in DENY/BUDGET_NOT_FOUND,
            // proving the gate did not short-circuit the evaluation.
            when(jedis.get("tenant:acme"))
                    .thenReturn("{\"tenant_id\":\"acme\",\"status\":\"ACTIVE\"}");
            assertThat(repository.decide(decideRequest("gate-active"), "acme").getReasonCode())
                    .isEqualTo(Enums.ReasonCode.BUDGET_NOT_FOUND);

            when(jedis.get("tenant:acme"))
                    .thenReturn("{\"tenant_id\":\"acme\",\"status\":\"SUSPENDED\"}");
            assertThat(repository.decide(decideRequest("gate-susp"), "acme").getReasonCode())
                    .isEqualTo(Enums.ReasonCode.BUDGET_NOT_FOUND);
        }

        @Test
        void absentTenantRecord_proceedsToNormalEvaluation() throws Exception {
            baseStubs();
            when(jedis.get("tenant:acme")).thenReturn(null);

            DecisionResponse response = repository.decide(decideRequest("gate-absent"), "acme");

            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_NOT_FOUND);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_EXCEEDED);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_CLOSED);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_NOT_FOUND);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_FROZEN);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.OVERDRAFT_LIMIT_EXCEEDED);
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
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.DEBT_OUTSTANDING);
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

            verify(jedis).eval(contains("PSETEX"),
                    eq(List.of("idem:acme:decide:decide-store", "idem:acme:decide:decide-store:hash", "evidence:pending")),
                    anyList());
        }

        @Test
        void decideWithoutIdempotencyKeyEmitsDirectlyWithoutCaching() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            String evId = "6".repeat(64);
            when(evidenceEmitter.emit(eq("decide"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(
                        evId, "https://cycles.example.com/v1/evidence/" + evId));
            DecisionRequest request = DecisionRequest.builder()
                    .subject(defaultSubject()).action(defaultAction()).estimate(defaultEstimate()).build();

            DecisionResponse response = repository.decide(request, "acme", "trace-d");

            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo(evId);
            verify(evidenceEmitter, never()).prepare(anyString(), anyLong(), any(), any());
            verify(jedis, never()).eval(contains("PSETEX"), anyList(), anyList());
        }

        @Test
        void cacheFailureClearsDecideClaimAndReturnsRetriableError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);
            when(evidenceEmitter.prepare(eq("decide"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                        new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(
                            "b".repeat(64), "https://cycles.example.com/v1/evidence/" + "b".repeat(64)), "{}"));
            when(jedis.eval(contains("PSETEX"), anyList(), anyList()))
                    .thenThrow(new RuntimeException("cache down"));

            DecisionRequest request = DecisionRequest.builder()
                    .idempotencyKey("decide-cache-fail")
                    .subject(defaultSubject())
                    .action(defaultAction())
                    .estimate(defaultEstimate())
                    .build();

            assertThatThrownBy(() -> repository.decide(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                    .hasMessageContaining("persist idempotency response");
            verify(jedis).eval(contains("redis.call('GET'"),
                    eq(List.of("idem:acme:decide:decide-cache-fail")), anyList());
            verify(evidenceEmitter, times(1)).prepare(eq("decide"), anyLong(), any(), any());
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
            verify(metrics).recordEvidenceEmitFailed("decide");
        }

        @Test
        void freshDecideStampsEvidenceAndCachesBody() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);
            String evId = "a".repeat(64);
            String url = "https://cycles.example.com/v1/evidence/" + evId;
            when(evidenceEmitter.prepare(eq("decide"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                        new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(evId, url), "{\"evidence_id\":\"" + evId + "\"}"));

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-ev");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme", "trace-d");

            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo(evId);
            // body cached (with evidence) under the decide idempotency key, 24h TTL
            verify(jedis).eval(contains("PSETEX"),
                    eq(List.of("idem:acme:decide:decide-ev", "idem:acme:decide:decide-ev:hash", "evidence:pending")),
                    argThat(args -> args.stream().anyMatch(v -> v.contains("\"evidence_id\":\"" + evId + "\""))));
        }

        @Test
        void decideReplayReturnsCachedBodyVerbatimWithoutReemitting() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            // replay short-circuits before budget evaluation — no deriveScopes call
            // cached original decide body (with evidence) present under the decide idem key
            DecisionResponse original = DecisionResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .affectedScopes(defaultScopes())
                    .cyclesEvidence(CyclesEvidenceRef.builder()
                            .evidenceId("c".repeat(64))
                            .cyclesEvidenceUrl("https://cycles.example.com/v1/evidence/" + "c".repeat(64))
                            .build())
                    .build();
            Response<String> cachedResp = mock(Response.class);
            when(cachedResp.get()).thenReturn(objectMapper.writeValueAsString(original));
            when(pipeline.get("idem:acme:decide:decide-replay")).thenReturn(cachedResp);

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-replay");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            DecisionResponse response = repository.decide(request, "acme", "trace-d");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo("c".repeat(64));
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
            verify(evidenceEmitter, never()).prepare(anyString(), anyLong(), any(), any());
        }

        @Test
        void freshDecideReleasesConnectionBeforeEmittingEvidence() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);
            String evId = "a".repeat(64);
            when(evidenceEmitter.prepare(eq("decide"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                        new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(
                            evId, "https://cycles.example.com/v1/evidence/" + evId), "{}"));

            DecisionRequest request = new DecisionRequest();
            request.setIdempotencyKey("decide-order");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            repository.decide(request, "acme", "trace-d");

            // pool-nesting guard: the eval connection is released before evidence preparation
            org.mockito.InOrder ordered = inOrder(jedis, evidenceEmitter);
            ordered.verify(jedis).close();
            ordered.verify(evidenceEmitter).prepare(eq("decide"), anyLong(), any(), any());
        }

        @Test
        void freshDecideClearsClaimWhenEvaluationFails() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            // claim succeeds (SET NX → OK via base mock), then evaluation blows up
            when(scopeService.deriveScopes(any())).thenThrow(new IllegalStateException("boom"));

            DecisionRequest request = DecisionRequest.builder()
                    .idempotencyKey("decide-evalfail")
                    .subject(defaultSubject())
                    .action(defaultAction())
                    .estimate(defaultEstimate())
                    .build();

            assertThatThrownBy(() -> repository.decide(request, "acme", "trace-d"))
                    .isInstanceOf(RuntimeException.class);
            // the pending claim is released via the compare-and-delete Lua, never re-emits
            verify(jedis).eval(contains("redis.call('GET'"), anyList(), anyList());
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
        }
    }

    // ---- decide exception wrapping ----

    @Nested
    @DisplayName("decide exception wrapping")
    class DecideExceptionWrapping {

        @Test
        void shouldPropagateRuntimeExceptionUnwrappedInDecide() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            IllegalStateException boom = new IllegalStateException("Unexpected");
            when(scopeService.deriveScopes(any())).thenThrow(boom);

            DecisionRequest request = DecisionRequest.builder()
                .idempotencyKey("decide-wrap")
                .subject(defaultSubject())
                .action(defaultAction())
                .estimate(defaultEstimate())
                .build();

            // The decide orchestrator rethrows RuntimeExceptions unchanged (no double-wrap):
            // the caller sees the original IllegalStateException instance, not a wrapping RuntimeException.
            assertThatThrownBy(() -> repository.decide(request, "acme"))
                .isSameAs(boom);
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
            luaMap.put("charged", 2500);
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme",
                    "remaining", 7000, "reserved", 0, "spent", 3000, "allocated", 10000,
                    "debt", 500, "debt_incurred", 500, "overdraft_limit", 1000,
                    "is_over_limit", true)));
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
            assertThat(response.getCharged().getAmount()).isEqualTo(2500);
            assertThat(response.getScopeDebtIncurred()).containsEntry("tenant:acme", 500L);
            assertThat(response.getBalances()).isNotEmpty();
            verify(metrics).recordEvent(eq("acme"), eq("APPLIED"), eq("OK"), anyString());
            verify(metrics).recordOverdraftIncurred("acme");
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
            assertThat(response.isIdempotentReplay()).isTrue();
            verify(metrics).recordEvent(
                    "acme", "APPLIED", "IDEMPOTENT_REPLAY", "DEFAULT");
            verify(metrics, never()).recordOverdraftIncurred(anyString());
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
