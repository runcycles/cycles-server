package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.audit.AuditLogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RedisReservationRepository - Commit/Release/Extend")
class RedisReservationCommitReleaseTest extends BaseRedisReservationRepositoryTest {

    // ---- commitReservation ----

    @Nested
    @DisplayName("commitReservation")
    class CommitReservationTest {

        @Test
        void shouldCommitSuccessfully() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 7000, "reserved", 0, "spent", 3000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-1");

            CommitResponse response = repository.commitReservation("res1", request, "tenant-a");

            assertThat(response.getStatus()).isEqualTo(Enums.CommitStatus.COMMITTED);
            assertThat(response.getCharged().getAmount()).isEqualTo(3000L);
            assertThat(response.getReleased()).isNotNull();
            assertThat(response.getReleased().getAmount()).isEqualTo(2000L);
        }

        @Test
        void shouldCommitWithNoReleasedWhenActualEqualsEstimate() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 5000);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 5000, "reserved", 0, "spent", 5000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 5000L));
            request.setIdempotencyKey("commit-2");

            CommitResponse response = repository.commitReservation("res2", request, "tenant-a");

            assertThat(response.getReleased()).isNull();
        }

        @Test
        void shouldThrowOnCommitScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "RESERVATION_FINALIZED", "message", "already committed"));
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-err");

            assertThatThrownBy(() -> repository.commitReservation("res-done", request, "tenant-a"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_FINALIZED);
        }

        @Test
        void shouldHandleMissingEstimateDataOnCommit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(Map.of("charged", 3000));
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-noest");

            CommitResponse response = repository.commitReservation("resNoEst", request, "tenant-a");

            assertThat(response.getStatus()).isEqualTo(Enums.CommitStatus.COMMITTED);
            assertThat(response.getCharged().getAmount()).isEqualTo(3000L);
            assertThat(response.getReleased()).isNull();
            assertThat(response.getBalances()).isEmpty();
        }

        @Test
        void shouldThrowOnCommitNotFoundError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "NOT_FOUND", "message", "res-notfound"));
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-notfound");

            assertThatThrownBy(() -> repository.commitReservation("res-notfound", request, "tenant-a"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldReturnReleasedAmountOnIdempotentCommitReplay() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua idempotency hit returns estimate_amount/estimate_unit (no balances)
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-idem");
            luaMap.put("state", "COMMITTED");
            luaMap.put("charged", 3000);
            luaMap.put("debt_incurred", 0);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-replay");

            CommitResponse response = repository.commitReservation("res-idem", request, "tenant-a");

            assertThat(response.getStatus()).isEqualTo(Enums.CommitStatus.COMMITTED);
            assertThat(response.getCharged().getAmount()).isEqualTo(3000L);
            assertThat(response.getReleased()).isNotNull();
            assertThat(response.getReleased().getAmount()).isEqualTo(2000L);
        }

        @Test
        void freshCommitStampsEvidenceAndCachesBody() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 3000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));
            String evId = "a".repeat(64);
            String url = "https://cycles.example.com/v1/evidence/" + evId;
            when(evidenceEmitter.prepare(eq("commit"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                        new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(evId, url), "{\"evidence_id\":\"" + evId + "\"}"));

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-fresh");

            CommitResponse response = repository.commitReservation("res-c", request, "tenant-a", "trace-c");

            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo(evId);
            assertThat(response.getCyclesEvidence().getCyclesEvidenceUrl()).isEqualTo(url);
            // body cached by reservation_id, 30-day terminal TTL
            verify(jedis).eval(contains("LPUSH"),
                    eq(List.of("commit:body:res-c", "reservation:res_res-c", "evidence:pending")),
                    argThat(args -> args.stream().anyMatch(v -> v.contains("\"evidence_id\":\"" + evId + "\""))));
        }

        @Test
        void freshCommitReleasesLuaConnectionBeforeEmittingEvidence() throws Exception {
            // Guards the pool-nesting fix: the Lua connection MUST be closed before
            // evidence preparation and atomic persistence, so we never hold
            // two pool connections at once.
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 3000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-order");

            repository.commitReservation("res-co", request, "tenant-a", "trace-c");

            org.mockito.InOrder ordered = inOrder(jedis, evidenceEmitter);
            ordered.verify(jedis).close();
            ordered.verify(evidenceEmitter).prepare(eq("commit"), anyLong(), any(), any());
        }

        @Test
        void freshCommitReturnsUnstampedSnapshotResponseWhenAtomicEvidenceWriteIsRejected() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 3000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));
            String evId = "9".repeat(64);
            when(evidenceEmitter.prepare(eq("commit"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                        new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(
                            evId, "https://cycles.example.com/v1/evidence/" + evId), "{}"));
            when(jedis.eval(contains("local rt"), anyList(), anyList())).thenReturn(-1L);

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-atomic-fail");

            CommitResponse response = repository.commitReservation("res-cf", request, "tenant-a", "trace-c");

            assertThat(response.getCyclesEvidence()).isNull();
            verify(metrics).recordEvidenceEmitFailed("commit");
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
        }

        @Test
        void commitReplayReturnsCachedBodyVerbatimWithoutReemitting() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-cr");
            luaMap.put("state", "COMMITTED");
            luaMap.put("replay", true);
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 3000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));
            // the original committed body was cached at first commit
            CommitResponse original = CommitResponse.builder()
                    .status(Enums.CommitStatus.COMMITTED)
                    .charged(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L))
                    .cyclesEvidence(CyclesEvidenceRef.builder()
                            .evidenceId("c".repeat(64))
                            .cyclesEvidenceUrl("https://cycles.example.com/v1/evidence/" + "c".repeat(64))
                            .build())
                    .build();
            when(jedis.get("commit:body:res-cr")).thenReturn(objectMapper.writeValueAsString(original));

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-replay");

            CommitResponse response = repository.commitReservation("res-cr", request, "tenant-a", "trace-c");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo("c".repeat(64));
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
            verify(jedis, never()).psetex(eq("commit:body:res-cr"), anyLong(), anyString());
        }

        @Test
        void commitReplayFailsRetriablyWhenOriginalBodyIsUnavailable() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-cr");
            luaMap.put("state", "COMMITTED");
            luaMap.put("replay", true);
            luaMap.put("charged", 3000);
            luaMap.put("estimate_amount", 3000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-replay");

            assertThatThrownBy(() -> repository.commitReservation("res-cr", request, "tenant-a", "trace-c"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                    .hasMessageContaining("Original idempotency response");
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
        }

        @Test
        void commitReplayRepairsMissingBodyCacheFromMutationSnapshot() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("reservation_id", "res-cr");
            snapshot.put("charged", 3000L);
            snapshot.put("estimate_amount", 5000L);
            snapshot.put("estimate_unit", "USD_MICROCENTS");
            snapshot.put("debt_incurred", 0L);
            snapshot.put("scope_path", "tenant:tenant-a");
            snapshot.put("overage_policy", "ALLOW_IF_AVAILABLE");
            snapshot.put("balances", List.of());
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(Map.of(
                        "replay", true, "charged", 3000L, "estimate_amount", 5000L,
                        "estimate_unit", "USD_MICROCENTS", "response_snapshot", snapshotJson)));
            when(jedis.get("commit:body:res-cr")).thenReturn(null);
            when(jedis.hmget("reservation:res_res-cr", "commit_response_json",
                    "commit_response_state", "commit_evidence_id", "commit_evidence_url"))
                    .thenReturn(List.of(snapshotJson, "EVIDENCE", "f".repeat(64),
                        "https://cycles.example.com/v1/evidence/" + "f".repeat(64)));

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-replay");

            CommitResponse response = repository.commitReservation("res-cr", request, "tenant-a", "trace-c");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getReleased().getAmount()).isEqualTo(2000L);
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo("f".repeat(64));
            verify(jedis).set(eq("commit:body:res-cr"), anyString(), any());
        }

        @Test
        void commitReplayFinalizesPendingSnapshotAsBase() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            String snapshotJson = objectMapper.writeValueAsString(Map.of(
                "reservation_id", "res-cp", "charged", 3000L,
                "estimate_amount", 5000L, "estimate_unit", "USD_MICROCENTS",
                "balances", List.of()));
            when(luaScripts.eval(eq(jedis), eq("commit"), eq("COMMIT_SCRIPT"), any(String[].class)))
                .thenReturn(objectMapper.writeValueAsString(Map.of(
                    "replay", true, "charged", 3000L, "estimate_amount", 5000L,
                    "estimate_unit", "USD_MICROCENTS", "response_snapshot", snapshotJson)));
            when(jedis.hmget("reservation:res_res-cp", "commit_response_json",
                "commit_response_state", "commit_evidence_id", "commit_evidence_url"))
                .thenReturn(Arrays.asList(snapshotJson, "PENDING", null, null));

            CommitRequest request = new CommitRequest();
            request.setActual(new Amount(Enums.UnitEnum.USD_MICROCENTS, 3000L));
            request.setIdempotencyKey("commit-pending");
            CommitResponse response = repository.commitReservation(
                "res-cp", request, "tenant-a", "trace-c");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getCyclesEvidence()).isNull();
            verify(jedis).eval(contains("'BASE'"),
                eq(List.of("commit:body:res-cp", "reservation:res_res-cp")), anyList());
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

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res3");
            luaMap.put("state", "RELEASED");
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 10000, "reserved", 0, "spent", 0, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-1").build();

            ReleaseResponse response = repository.releaseReservation("res3", request, "tenant-a", "tenant");

            assertThat(response.getStatus()).isEqualTo(Enums.ReleaseStatus.RELEASED);
            assertThat(response.getReleased().getAmount()).isEqualTo(5000L);
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
        }

        @Test
        void shouldFallbackToZeroWhenEstimateMissing() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res4");
            luaMap.put("state", "RELEASED");
            // No estimate_amount or estimate_unit — fallback path
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-2").build();

            ReleaseResponse response = repository.releaseReservation("res4", request, "tenant-a", "tenant");

            assertThat(response.getReleased().getAmount()).isEqualTo(0L);
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
        }

        @Test
        void shouldThrowOnReleaseScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "NOT_FOUND", "message", "no such reservation"));
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-err").build();

            assertThatThrownBy(() -> repository.releaseReservation("res-gone", request, "tenant-a", "tenant"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldReturnReleasedAmountOnIdempotentReleaseReplay() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua idempotency hit returns estimate_amount/estimate_unit
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-idem-rel");
            luaMap.put("state", "RELEASED");
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("release-replay").build();

            ReleaseResponse response = repository.releaseReservation("res-idem-rel", request, "tenant-a", "tenant");

            assertThat(response.getStatus()).isEqualTo(Enums.ReleaseStatus.RELEASED);
            assertThat(response.getReleased().getAmount()).isEqualTo(5000L);
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
        }

        @Test
        void freshReleaseStampsEvidenceAndCachesBody() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));
            String evId = "b".repeat(64);
            String url = "https://cycles.example.com/v1/evidence/" + evId;
            when(evidenceEmitter.prepare(eq("release"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                        new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(evId, url), "{\"evidence_id\":\"" + evId + "\"}"));

            ReleaseResponse response = repository.releaseReservation("res-r",
                    ReleaseRequest.builder().idempotencyKey("rel-fresh").build(), "tenant-a", "tenant", "trace-r");

            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo(evId);
            verify(jedis).eval(contains("LPUSH"),
                    eq(List.of("release:body:res-r", "reservation:res_res-r", "evidence:pending")),
                    argThat(args -> args.stream().anyMatch(v -> v.contains("\"evidence_id\":\"" + evId + "\""))));
        }

        @Test
        void freshReleaseReleasesLuaConnectionBeforeEmittingEvidence() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));

            repository.releaseReservation("res-ro",
                    ReleaseRequest.builder().idempotencyKey("rel-order").build(), "tenant-a", "tenant", "trace-r");

            org.mockito.InOrder ordered = inOrder(jedis, evidenceEmitter);
            ordered.verify(jedis).close();
            ordered.verify(evidenceEmitter).prepare(eq("release"), anyLong(), any(), any());
        }

        @Test
        void releaseReplayReturnsCachedBodyVerbatimWithoutReemitting() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-rr");
            luaMap.put("state", "RELEASED");
            luaMap.put("replay", true);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));
            ReleaseResponse original = ReleaseResponse.builder()
                    .status(Enums.ReleaseStatus.RELEASED)
                    .released(new Amount(Enums.UnitEnum.USD_MICROCENTS, 5000L))
                    .cyclesEvidence(CyclesEvidenceRef.builder()
                            .evidenceId("d".repeat(64))
                            .cyclesEvidenceUrl("https://cycles.example.com/v1/evidence/" + "d".repeat(64))
                            .build())
                    .build();
            when(jedis.get("release:body:res-rr")).thenReturn(objectMapper.writeValueAsString(original));

            ReleaseResponse response = repository.releaseReservation("res-rr",
                    ReleaseRequest.builder().idempotencyKey("rel-replay").build(), "tenant-a", "tenant", "trace-r");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo("d".repeat(64));
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
            verify(jedis, never()).psetex(eq("release:body:res-rr"), anyLong(), anyString());
        }

        @Test
        void releaseReplayFailsRetriablyWhenOriginalBodyIsUnavailable() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-rr");
            luaMap.put("state", "RELEASED");
            luaMap.put("replay", true);
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));

            assertThatThrownBy(() -> repository.releaseReservation("res-rr",
                    ReleaseRequest.builder().idempotencyKey("rel-replay").build(),
                    "tenant-a", "tenant", "trace-r"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                    .hasMessageContaining("Original idempotency response");
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
        }

        @Test
        void releaseReplayRepairsMissingBodyCacheFromMutationSnapshot() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("reservation_id", "res-rr");
            snapshot.put("estimate_amount", 5000L);
            snapshot.put("estimate_unit", "USD_MICROCENTS");
            snapshot.put("balances", List.of());
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(Map.of(
                        "replay", true, "estimate_amount", 5000L,
                        "estimate_unit", "USD_MICROCENTS", "response_snapshot", snapshotJson)));
            when(jedis.get("release:body:res-rr")).thenReturn(null);
            when(jedis.hmget("reservation:res_res-rr", "release_response_json",
                    "release_response_state", "release_evidence_id", "release_evidence_url"))
                    .thenReturn(List.of(snapshotJson, "EVIDENCE", "8".repeat(64),
                        "https://cycles.example.com/v1/evidence/" + "8".repeat(64)));

            ReleaseResponse response = repository.releaseReservation("res-rr",
                    ReleaseRequest.builder().idempotencyKey("rel-replay").build(),
                    "tenant-a", "tenant", "trace-r");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getReleased().getAmount()).isEqualTo(5000L);
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo("8".repeat(64));
            verify(jedis).set(eq("release:body:res-rr"), anyString(), any());
        }

        @Test
        void releaseReplayFinalizesPendingSnapshotAsBase() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            String snapshotJson = objectMapper.writeValueAsString(Map.of(
                "reservation_id", "res-rp", "estimate_amount", 5000L,
                "estimate_unit", "USD_MICROCENTS", "balances", List.of()));
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class)))
                .thenReturn(objectMapper.writeValueAsString(Map.of(
                    "replay", true, "estimate_amount", 5000L,
                    "estimate_unit", "USD_MICROCENTS", "response_snapshot", snapshotJson)));
            when(jedis.hmget("reservation:res_res-rp", "release_response_json",
                "release_response_state", "release_evidence_id", "release_evidence_url"))
                .thenReturn(Arrays.asList(snapshotJson, "PENDING", null, null));

            ReleaseResponse response = repository.releaseReservation("res-rp",
                ReleaseRequest.builder().idempotencyKey("release-pending").build(),
                "tenant-a", "tenant", "trace-r");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getCyclesEvidence()).isNull();
            verify(jedis).eval(contains("'BASE'"),
                eq(List.of("release:body:res-rp", "reservation:res_res-rp")), anyList());
        }

        @Test
        void adminAuditPayloadIsPassedToReleaseScriptAtomically() throws Exception {
            objectMapper.findAndRegisterModules();
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("estimate_amount", 5000);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(luaMap));

            Instant timestamp = Instant.parse("2026-07-12T12:00:00Z");
            AuditLogEntry audit = AuditLogEntry.builder()
                    .logId("log_atomic")
                    .timestamp(timestamp)
                    .tenantId("tenant-a")
                    .operation("reservation.release")
                    .resourceType("reservation")
                    .resourceId("res-audit")
                    .status(200)
                    .build();
            when(auditRepository.prepare(audit)).thenReturn(new AuditRepository.PreparedAudit(
                objectMapper.writeValueAsString(audit), "log_atomic", "tenant-a",
                400L * 86_400L, timestamp.toEpochMilli()));

            repository.releaseReservation("res-audit",
                    ReleaseRequest.builder().idempotencyKey("rel-audit").build(),
                    "tenant-a", "admin_on_behalf_of", "trace-a", audit);

            org.mockito.ArgumentCaptor<String[]> args = org.mockito.ArgumentCaptor.forClass(String[].class);
            verify(luaScripts).eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), args.capture());
            assertThat(args.getValue()).hasSize(8);
            assertThat(args.getValue()[3]).contains("\"log_id\":\"log_atomic\"")
                    .contains("\"operation\":\"reservation.release\"");
            assertThat(args.getValue()[4]).isEqualTo("log_atomic");
            assertThat(args.getValue()[5]).isEqualTo("tenant-a");
            assertThat(args.getValue()[6]).isEqualTo(String.valueOf(400L * 86_400L));
            assertThat(args.getValue()[7]).isEqualTo(String.valueOf(timestamp.toEpochMilli()));
        }

        @Test
        void preparedAuditValuesAreDelegatedToReleaseScript() throws Exception {
            objectMapper.findAndRegisterModules();
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(Map.of(
                            "estimate_amount", 5000,
                            "estimate_unit", "USD_MICROCENTS")));
            AuditLogEntry audit = AuditLogEntry.builder()
                    .tenantId("tenant-a")
                    .operation("releaseReservation")
                    .status(200)
                    .build();
            Instant timestamp = Instant.parse("2026-07-13T12:00:00Z");
            when(auditRepository.prepare(audit)).thenReturn(new AuditRepository.PreparedAudit(
                "{\"log_id\":\"log_prepared\"}", "log_prepared", "tenant-a", 0L,
                timestamp.toEpochMilli()));

            repository.releaseReservation("res-audit-defaults",
                    ReleaseRequest.builder().build(), "tenant-a",
                    "admin_on_behalf_of", null, audit);

            org.mockito.ArgumentCaptor<String[]> args = org.mockito.ArgumentCaptor.forClass(String[].class);
            verify(luaScripts).eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), args.capture());
            verify(auditRepository).prepare(audit);
            assertThat(args.getValue()[4]).isEqualTo("log_prepared");
            assertThat(args.getValue()[6]).isEqualTo("0");
            assertThat(args.getValue()[7]).isEqualTo(String.valueOf(timestamp.toEpochMilli()));
        }
    }

    // ---- releaseReservation edge cases ----

    @Nested
    @DisplayName("releaseReservation edge cases")
    class ReleaseReservationEdgeCases {

        @Test
        void shouldFallbackToZeroWhenEstimateDataMissing() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua response without estimate_amount/estimate_unit — fallback to zero
            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-null-est");
            luaMap.put("state", "RELEASED");
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("release"), eq("RELEASE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReleaseRequest request = ReleaseRequest.builder().idempotencyKey("rel-null").build();
            ReleaseResponse response = repository.releaseReservation("res-null-est", request, "tenant-a", "tenant");

            assertThat(response.getReleased().getAmount()).isZero();
            assertThat(response.getReleased().getUnit()).isEqualTo(Enums.UnitEnum.USD_MICROCENTS);
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

            Map<String, Object> luaMap = new HashMap<>();
            luaMap.put("expires_at_ms", 1234567890L);
            luaMap.put("estimate_unit", "USD_MICROCENTS");
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme",
                    "remaining", 5000, "reserved", 5000, "spent", 0, "allocated", 10000,
                    "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("extend"), eq("EXTEND_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-1");

            ReservationExtendResponse response = repository.extendReservation("res5", request, "acme");

            assertThat(response.getStatus()).isEqualTo(Enums.ExtendStatus.ACTIVE);
            assertThat(response.getExpiresAtMs()).isEqualTo(1234567890L);
            assertThat(response.getBalances()).isNotEmpty();
        }

        @Test
        void shouldThrowOnExtendScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "RESERVATION_EXPIRED", "message", "expired"));
            when(luaScripts.eval(eq(jedis), eq("extend"), eq("EXTEND_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-err");

            assertThatThrownBy(() -> repository.extendReservation("res-exp", request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_EXPIRED);
        }

        @Test
        void shouldHandleMissingBalancesOnExtend() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            // Lua response without balances (e.g., reservation with no scopes)
            String luaResponse = objectMapper.writeValueAsString(Map.of("expires_at_ms", 1700000090000L));
            when(luaScripts.eval(eq(jedis), eq("extend"), eq("EXTEND_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-nopre");

            ReservationExtendResponse response = repository.extendReservation("extNoPre", request, "acme");

            assertThat(response.getStatus()).isEqualTo(Enums.ExtendStatus.ACTIVE);
            assertThat(response.getExpiresAtMs()).isEqualTo(1700000090000L);
            assertThat(response.getBalances()).isEmpty();
        }

        @Test
        void shouldThrowOnExtendNotFoundError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "NOT_FOUND", "message", "res-notfound"));
            when(luaScripts.eval(eq(jedis), eq("extend"), eq("EXTEND_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationExtendRequest request = new ReservationExtendRequest();
            request.setExtendByMs(30000L);
            request.setIdempotencyKey("extend-notfound");

            assertThatThrownBy(() -> repository.extendReservation("res-notfound", request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }
    }
}
