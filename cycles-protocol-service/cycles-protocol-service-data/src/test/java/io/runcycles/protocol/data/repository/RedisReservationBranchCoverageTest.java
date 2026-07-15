package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.service.EvidenceEmitter;
import io.runcycles.protocol.model.CyclesEvidenceRef;
import io.runcycles.protocol.model.CommitResponse;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ReleaseResponse;
import io.runcycles.protocol.model.ReservationCreateResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisReservationBranchCoverageTest extends BaseRedisReservationRepositoryTest {

    @Test
    void tenantStatusGateCoversAbsentMalformedAndEveryRecognizedStatus() throws Throwable {
        Method gate = method("evaluateTenantStatusGate", redis.clients.jedis.Jedis.class, String.class);
        assertThat(invoke(gate, null, jedis, null)).isNull();
        assertThat(invoke(gate, null, jedis, " ")).isNull();

        when(jedis.get("tenant:missing")).thenReturn(null);
        assertThat(invoke(gate, null, jedis, "missing")).isNull();

        for (Map.Entry<String, String> malformed : Map.of(
                "null-node", "null",
                "array", "[]",
                "missing-status", "{}",
                "null-status", "{\"status\":null}",
                "numeric-status", "{\"status\":1}",
                "unknown-status", "{\"status\":\"UNKNOWN\"}").entrySet()) {
            when(jedis.get("tenant:" + malformed.getKey())).thenReturn(malformed.getValue());
            assertThatThrownBy(() -> invoke(gate, null, jedis, malformed.getKey()))
                .isInstanceOf(CyclesProtocolException.class)
                .extracting("errorCode").isEqualTo(Enums.ErrorCode.INTERNAL_ERROR);
        }

        when(jedis.get("tenant:active")).thenReturn("{\"status\":\"ACTIVE\"}");
        when(jedis.get("tenant:suspended")).thenReturn("{\"status\":\"SUSPENDED\"}");
        when(jedis.get("tenant:closed")).thenReturn("{\"status\":\"CLOSED\"}");
        assertThat(invoke(gate, null, jedis, "active")).isNull();
        assertThat(invoke(gate, null, jedis, "suspended")).isNull();
        assertThat(invoke(gate, null, jedis, "closed")).isEqualTo(Enums.ReasonCode.TENANT_CLOSED);
    }

    @Test
    void cachedHashAndJsonShapeValidationCoverAllShortCircuits() throws Throwable {
        Method validate = method("validateCachedHash", String.class, String.class);
        invoke(validate, null, "stored", null);
        invoke(validate, null, "stored", "");
        invoke(validate, null, null, "hash");
        invoke(validate, null, "hash", "hash");
        assertThatThrownBy(() -> invoke(validate, null, "stored", "different"))
            .isInstanceOf(CyclesProtocolException.class);

        Method json = method("isJsonObject", String.class);
        assertThat(invoke(json, null, new Object[]{null})).isEqualTo(false);
        assertThat(invoke(json, null, "value")).isEqualTo(false);
        assertThat(invoke(json, null, "  {\"ok\":true}")).isEqualTo(true);
    }

    @Test
    void replayTtlAndDurableStateResolutionCoverEveryState() throws Throwable {
        Method ttl = method("replayCacheTtl", Object.class);
        assertThat((long) invoke(ttl, null, new Object[]{null})).isPositive();
        assertThat(invoke(ttl, null, -1L)).isEqualTo(86_400_000L);
        assertThat(invoke(ttl, null, 123L)).isEqualTo(123L);

        Method resolve = method("resolvedResponseState", replayStateClass());
        CyclesEvidenceRef evidence = CyclesEvidenceRef.builder()
            .evidenceId("e").cyclesEvidenceUrl("u").build();
        assertThat(invoke(resolve, null, replayState("snapshot", "PENDING", null))).isEqualTo("PENDING");
        assertThat(invoke(resolve, null, replayState("snapshot", "BASE", null))).isEqualTo("BASE");
        assertThat(invoke(resolve, null, replayState("snapshot", "EVIDENCE", evidence))).isEqualTo("EVIDENCE");
        assertThat(invoke(resolve, null, replayState("snapshot", null, null))).isEqualTo("BASE");
        assertThat(invoke(resolve, null, replayState("snapshot", null, evidence))).isEqualTo("EVIDENCE");
        assertThatThrownBy(() -> invoke(resolve, null, replayState("snapshot", "INVALID", null)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replayStateReaderUsesSuppliedAndStoredSnapshotsAndPartialEvidence() throws Throwable {
        Method read = method("readReplayState", String.class, String.class, String.class, Object.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.hmget(anyString(), any(String[].class))).thenReturn(
            List.of("stored", "BASE", "eid", "url"),
            java.util.Arrays.asList("fallback", null, "eid", null),
            java.util.Arrays.asList("fallback-2", null, null, "url"),
            List.of("too-short"));

        Object supplied = invoke(read, null, "r1", "commit", "snapshot", "supplied");
        assertThat(recordAccessor(supplied, "snapshotJson")).isEqualTo("supplied");
        assertThat(recordAccessor(supplied, "evidence")).isNotNull();
        Object empty = invoke(read, null, "r2", "commit", "snapshot", "");
        assertThat(recordAccessor(empty, "snapshotJson")).isEqualTo("fallback");
        assertThat(recordAccessor(empty, "evidence")).isNull();
        Object nonString = invoke(read, null, "r3", "commit", "snapshot", 7);
        assertThat(recordAccessor(nonString, "snapshotJson")).isEqualTo("fallback-2");
        assertThat(recordAccessor(nonString, "evidence")).isNull();
        assertThat(invoke(read, null, "r4", "commit", "snapshot", null)).isNull();
    }

    @Test
    void lifecycleEvidenceCacheHandlesEveryScriptResult() throws Throwable {
        Method cache = method("cacheLifecycleWithEvidence", String.class, String.class,
            Object.class, long.class, EvidenceEmitter.PreparedEvidence.class);
        EvidenceEmitter.PreparedEvidence prepared = preparedEvidence();
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(1L, 0L, "bad", 2L)
            .thenThrow(new IllegalStateException("redis"));

        assertThat(invoke(cache, null, "commit", "r1", Map.of("ok", true), 100L, prepared))
            .isEqualTo(true);
        assertThat(invoke(cache, null, "commit", "r2", Map.of("ok", true), 100L, prepared))
            .isEqualTo(false);
        assertThat(invoke(cache, null, "commit", "r3", Map.of("ok", true), 100L, prepared))
            .isEqualTo(false);
        assertThat(invoke(cache, null, "commit", "r4", Map.of("ok", true), 100L, prepared))
            .isEqualTo(false);
        assertThat(invoke(cache, null, "commit", "r5", Map.of("ok", true), 100L, prepared))
            .isEqualTo(false);
        verify(metrics, atLeast(3)).recordEvidenceEmitFailed("commit");
    }

    @Test
    void baseResponseFinalizerHandlesNumericAndFailureResults() throws Throwable {
        Method finalize = method("finalizeBaseResponse", String.class, String.class,
            Object.class, long.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(1L, 0L, -1L, "bad")
            .thenThrow(new IllegalStateException("redis"));

        assertThat(invoke(finalize, null, "release", "r1", Map.of(), 100L)).isEqualTo(true);
        assertThat(invoke(finalize, null, "release", "r2", Map.of(), 100L)).isEqualTo(false);
        assertThat(invoke(finalize, null, "release", "r3", Map.of(), 100L)).isEqualTo(false);
        assertThat(invoke(finalize, null, "release", "r4", Map.of(), 100L)).isEqualTo(false);
        assertThat(invoke(finalize, null, "release", "r5", Map.of(), 100L)).isEqualTo(false);
    }

    @Test
    void idempotentBodyCacheCoversNoKeyOwnershipAndTypeOutcomes() throws Throwable {
        Method cache = method("cacheIdempotentBody", String.class, String.class, String.class,
            String.class, Object.class, EvidenceEmitter.PreparedEvidence.class, String.class);
        assertThat(invoke(cache, null, "decide", "tenant", null, null, Map.of(), null, null))
            .isEqualTo(true);
        assertThat(invoke(cache, null, "decide", "tenant", "", null, Map.of(), null, null))
            .isEqualTo(true);

        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(1L, 0L, 2L, "bad")
            .thenThrow(new IllegalStateException("redis"));
        EvidenceEmitter.PreparedEvidence prepared = preparedEvidence();
        assertThat(invoke(cache, null, "decide", "tenant", "k1", null, Map.of(), null, null))
            .isEqualTo(true);
        assertThat(invoke(cache, null, "decide", "tenant", "k2", "hash", Map.of(), prepared, "marker"))
            .isEqualTo(false);
        assertThat(invoke(cache, null, "decide", "tenant", "k3", "hash", Map.of(), prepared, "marker"))
            .isEqualTo(false);
        assertThat(invoke(cache, null, "decide", "tenant", "k4", "hash", Map.of(), prepared, "marker"))
            .isEqualTo(false);
        assertThat(invoke(cache, null, "decide", "tenant", "k5", "hash", Map.of(), prepared, "marker"))
            .isEqualTo(false);
        verify(metrics, atLeast(3)).recordEvidenceEmitFailed("decide");
    }

    @Test
    void pendingMarkerHelpersCoverNullEmptyMismatchAndLegacyForms() throws Throwable {
        Method marker = method("pendingMarker", String.class, String.class);
        Method payload = method("pendingPayloadHash", String.class, String.class);
        Method validate = method("validatePendingMarker", String.class, String.class, String.class);
        String nullHashMarker = (String) invoke(marker, null, "decide", null);
        String hashMarker = (String) invoke(marker, null, "decide", "hash");
        assertThat(nullHashMarker).startsWith("__decide_pending__::");
        assertThat(invoke(payload, null, "decide", "__decide_pending__:legacy"))
            .isEqualTo("legacy");
        assertThat(invoke(payload, null, "decide", hashMarker)).isEqualTo("hash");
        invoke(validate, null, "decide", nullHashMarker, null);
        invoke(validate, null, "decide", hashMarker, "");
        invoke(validate, null, "decide", hashMarker, "hash");
        assertThatThrownBy(() -> invoke(validate, null, "decide", hashMarker, "different"))
            .isInstanceOf(CyclesProtocolException.class);
    }

    @Test
    void claimKeyParsingAndClearGuardsCoverMalformedKeys() throws Throwable {
        Method kind = method("idempotencyClaimKind", String.class);
        Method tenant = method("idempotencyClaimTenant", String.class);
        Method parts = method("idempotencyClaimParts", String.class);
        assertThat(invoke(kind, null, new Object[]{null})).isNull();
        assertThat(invoke(tenant, null, "not-idem")).isNull();
        assertThat((String[]) invoke(parts, null, "idem:tenant:decide:key"))
            .containsExactly("idem", "tenant", "decide", "key");
        assertThat(invoke(kind, null, "idem:tenant:decide:key")).isEqualTo("decide");
        assertThat(invoke(tenant, null, "idem:tenant:decide:key")).isEqualTo("tenant");

        Method clear = method("clearIdempotencyClaim", redis.clients.jedis.Jedis.class,
            String.class, String.class);
        invoke(clear, null, jedis, null, "marker");
        invoke(clear, null, jedis, "idem:tenant:decide:key", null);
        doThrow(new IllegalStateException("redis")).when(jedis)
            .eval(anyString(), anyList(), anyList());
        invoke(clear, null, jedis, "idem:tenant:decide:key", "marker");
    }

    @Test
    void retryLoopsStopCleanlyWhenInterrupted() throws Throwable {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("cache-key")).thenReturn(null);
        Method read = method("readCacheValueWithWait", String.class, int.class);
        Thread.currentThread().interrupt();
        try {
            assertThat(invoke(read, null, "cache-key", 2)).isNull();
        } finally {
            Thread.interrupted();
        }

        Method wait = method("waitForIdempotentReplayBody", String.class, String.class, String.class);
        when(jedis.get("idem-key")).thenReturn(null);
        Thread.currentThread().interrupt();
        try {
            assertThat(invoke(wait, null, "decide", "idem-key", "hash")).isNull();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void tenantDefaultsAlternateUnitsAndExtensionLimitsCoverEmptyValues() throws Throwable {
        Method policy = method("resolveOveragePolicy", Enums.CommitOveragePolicy.class, Map.class);
        assertThat(invoke(policy, null, null, null)).isEqualTo("ALLOW_IF_AVAILABLE");
        assertThat(invoke(policy, null, null, Map.of("default_commit_overage_policy", "")))
            .isEqualTo("ALLOW_IF_AVAILABLE");

        Method units = method("probeAlternateUnits", redis.clients.jedis.Jedis.class,
            String.class, String.class);
        when(jedis.exists("budget:tenant:acme:TOKENS")).thenReturn(true);
        @SuppressWarnings("unchecked")
        List<String> alternateUnits = (List<String>) invoke(
            units, null, jedis, "tenant:acme", "USD_MICROCENTS");
        assertThat(alternateUnits)
            .contains("TOKENS").doesNotContain("USD_MICROCENTS");

        Method extensions = method("resolveMaxExtensions", Map.class);
        assertThat(invoke(extensions, null, new Object[]{null})).isEqualTo(10);
        assertThat(invoke(extensions, null, Map.of())).isEqualTo(10);
        assertThat(invoke(extensions, null, Map.of("max_reservation_extensions", 3))).isEqualTo(3);
    }

    @Test
    void reserveReplayRestorationCoversMissingBaseEvidenceAndPendingStates() throws Throwable {
        Method restore = method("restoreReserveResponse", String.class, Object.class, long.class);
        when(jedisPool.getResource()).thenReturn(jedis);

        when(jedis.hmget(anyString(), any(String[].class))).thenReturn(List.of("too-short"));
        assertThat(invoke(restore, null, "missing", null, 100L)).isNull();

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(null, "BASE", null, null));
        assertThat(invoke(restore, null, "no-snapshot", null, 100L)).isNull();

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(validReserveSnapshot(), "EVIDENCE", null, null));
        assertThat(invoke(restore, null, "evidence-missing", null, 100L)).isNull();

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(List.of(validReserveSnapshot(), "EVIDENCE", "eid", "url"));
        ReservationCreateResponse evidence = (ReservationCreateResponse) invoke(
            restore, null, "evidence", null, 100L);
        assertThat(evidence.getCyclesEvidence()).isNotNull();

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(validReserveSnapshot(), "PENDING", null, null));
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        ReservationCreateResponse pending = (ReservationCreateResponse) invoke(
            restore, null, "pending", null, 100L);
        assertThat(pending.isIdempotentReplay()).isTrue();
    }

    @Test
    void commitReplayRestorationCoversAmountAndPendingCacheBranches() throws Throwable {
        Method restore = method("restoreCommitResponse", String.class, Object.class);
        when(jedisPool.getResource()).thenReturn(jedis);

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(commitSnapshot(5, 10), "BASE", null, null));
        CommitResponse released = (CommitResponse) invoke(restore, null, "base", null);
        assertThat(released.getReleased().getAmount()).isEqualTo(5L);

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(commitSnapshot(10, 10), "EVIDENCE", null, null));
        assertThat(invoke(restore, null, "missing-evidence", null)).isNull();

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(List.of(commitSnapshot(10, 10), "EVIDENCE", "eid", "url"));
        assertThat(((CommitResponse) invoke(restore, null, "evidence", null)).getCyclesEvidence())
            .isNotNull();

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(commitSnapshot(10, 10), "PENDING", null, null));
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        when(jedis.get(anyString())).thenReturn(null);
        assertThat(invoke(restore, null, "pending-empty", null)).isNull();

        String cached = objectMapper.writeValueAsString(CommitResponse.builder()
            .status(Enums.CommitStatus.COMMITTED).build());
        when(jedis.get(anyString())).thenReturn(cached);
        CommitResponse repaired = (CommitResponse) invoke(restore, null, "pending-cached", null);
        assertThat(repaired.isIdempotentReplay()).isTrue();
    }

    @Test
    void releaseReplayRestorationCoversEvidenceAndPendingCacheBranches() throws Throwable {
        Method restore = method("restoreReleaseResponse", String.class, Object.class);
        when(jedisPool.getResource()).thenReturn(jedis);

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(releaseSnapshot(), "EVIDENCE", null, null));
        assertThat(invoke(restore, null, "missing-evidence", null)).isNull();

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(List.of(releaseSnapshot(), "EVIDENCE", "eid", "url"));
        assertThat(((ReleaseResponse) invoke(restore, null, "evidence", null)).getCyclesEvidence())
            .isNotNull();

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(releaseSnapshot(), "PENDING", null, null));
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        when(jedis.get(anyString())).thenReturn(null);
        assertThat(invoke(restore, null, "pending-empty", null)).isNull();

        String cached = objectMapper.writeValueAsString(ReleaseResponse.builder()
            .status(Enums.ReleaseStatus.RELEASED).build());
        when(jedis.get(anyString())).thenReturn(cached);
        ReleaseResponse repaired = (ReleaseResponse) invoke(restore, null, "pending-cached", null);
        assertThat(repaired.isIdempotentReplay()).isTrue();
    }

    @Test
    void cachePollingAndResponseShapeHelpersCoverSuccessfulAlternatives() throws Throwable {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("cache-key")).thenReturn("cached");
        Method read = method("readCacheValueWithWait", String.class, int.class);
        assertThat(invoke(read, null, "cache-key", 0)).isEqualTo("cached");

        String marker = (String) invoke(method("pendingMarker", String.class, String.class),
            null, "decide", "hash");
        when(jedis.get("idem-key")).thenReturn(marker, "{\"decision\":\"ALLOW\"}");
        when(jedis.get("idem-key:hash")).thenReturn("hash");
        Method wait = method("waitForIdempotentReplayBody", String.class, String.class, String.class);
        assertThat(invoke(wait, null, "decide", "idem-key", "hash"))
            .isEqualTo("{\"decision\":\"ALLOW\"}");

        Method build = method("buildReserveResponse", Map.class);
        Map<String, Object> snapshot = objectMapper.readValue(validReserveSnapshot(), Map.class);
        snapshot.put("caps_json", 7);
        assertThat(((ReservationCreateResponse) invoke(build, null, snapshot)).getCaps()).isNull();
    }

    @Test
    void detailedUnitMismatchErrorsCoverOptionalResponseFields() throws Throwable {
        Method handle = method("handleScriptError", Map.class);
        for (Map<String, Object> response : List.of(
            Map.<String, Object>of("error", "UNIT_MISMATCH", "requested_unit", "TOKENS"),
            Map.<String, Object>of("error", "UNIT_MISMATCH", "expected_units", "TOKENS"),
            Map.<String, Object>of("error", "UNIT_MISMATCH", "scope", "tenant:acme",
                "requested_unit", "TOKENS", "expected_units", List.of("REQUESTS")))) {
            assertThatThrownBy(() -> invoke(handle, null, response))
                .isInstanceOf(CyclesProtocolException.class)
                .extracting("errorCode").isEqualTo(Enums.ErrorCode.UNIT_MISMATCH);
        }
    }

    @Test
    void replayAndClaimHelpersCoverBaseMissingSnapshotsAndLostClaimRace() throws Throwable {
        when(jedisPool.getResource()).thenReturn(jedis);
        Method reserve = method("restoreReserveResponse", String.class, Object.class, long.class);
        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(validReserveSnapshot(), "BASE", null, null));
        assertThat(invoke(reserve, null, "base", null, 100L)).isInstanceOf(ReservationCreateResponse.class);

        when(jedis.hmget(anyString(), any(String[].class)))
            .thenReturn(java.util.Arrays.asList(validReserveSnapshot(), "PENDING", null, null));
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        when(jedis.get(anyString())).thenReturn(null);
        assertThat(invoke(reserve, null, "pending-empty", null, 100L)).isNull();

        Method commit = method("restoreCommitResponse", String.class, Object.class);
        Method release = method("restoreReleaseResponse", String.class, Object.class);
        when(jedis.hmget(anyString(), any(String[].class))).thenReturn(List.of("too-short"));
        assertThat(invoke(commit, null, "missing", null)).isNull();
        assertThat(invoke(release, null, "missing", null)).isNull();

        Method acquire = method("acquireIdempotencyClaim", redis.clients.jedis.Jedis.class,
            String.class, String.class, String.class, String.class);
        Object noKey = invoke(acquire, null, jedis, "decide", "tenant", "", "hash");
        assertThat(recordAccessor(noKey, "claimKey")).isNull();
        assertThat(recordAccessor(noKey, "pending")).isEqualTo(false);
        when(jedis.set(anyString(), anyString(), any(redis.clients.jedis.params.SetParams.class)))
            .thenReturn("NOT_ACQUIRED");
        assertThat(recordAccessor(invoke(acquire, null, jedis, "decide", "tenant", "key", "hash"),
            "pending")).isEqualTo(true);
    }

    @Test
    void evidenceStampCapsAndTenantCacheCoverOptionalAlternatives() throws Throwable {
        Method stamp = method("stampAndEmitEvidence", ReservationCreateResponse.class,
            io.runcycles.protocol.model.ReservationCreateRequest.class, String.class);
        ReservationCreateResponse response = ReservationCreateResponse.builder().build();
        when(evidenceEmitter.emit(anyString(), anyLong(), any(), anyMap())).thenReturn(null,
            new EvidenceEmitter.EvidenceRef("eid", "url"));
        invoke(stamp, null, response, null, null);
        assertThat(response.getCyclesEvidence()).isNull();
        invoke(stamp, null, response, null, "trace");
        assertThat(response.getCyclesEvidence()).isNotNull();

        Method build = method("buildReserveResponse", Map.class);
        Map<String, Object> snapshot = objectMapper.readValue(validReserveSnapshot(), Map.class);
        snapshot.put("caps_json", "");
        assertThat(((ReservationCreateResponse) invoke(build, null, snapshot)).getCaps()).isNull();

        setField("tenantConfigCache", null);
        setField("tenantConfigCacheTtlMs", 1_000L);
        assertThat(invoke(method("getTenantConfigCache"), null)).isNotNull();
    }

    @Test
    void mutationRequestsSerializeOptionalArgumentsAndDebtResponses() throws Exception {
        when(jedisPool.getResource()).thenReturn(jedis);

        io.runcycles.protocol.model.CommitRequest commit = new io.runcycles.protocol.model.CommitRequest();
        commit.setActual(new io.runcycles.protocol.model.Amount(Enums.UnitEnum.TOKENS, 8L));
        commit.setMetrics(io.runcycles.protocol.model.StandardMetrics.builder().tokensInput(2).build());
        commit.setMetadata(Map.of("source", "branch-test"));
        when(luaScripts.eval(eq(jedis), eq("commit"), anyString(), any(String[].class)))
            .thenReturn(objectMapper.writeValueAsString(Map.of(
                "charged", 8, "estimate_amount", 8, "estimate_unit", "TOKENS",
                "scope_path", "tenant:acme", "overage_policy", "ALLOW_IF_AVAILABLE",
                "debt_incurred", 2)));

        CommitResponse committed = repository.commitReservation("r-commit", commit, "acme");
        assertThat(committed.getScopePath()).isEqualTo("tenant:acme");
        assertThat(committed.getDebtIncurred()).isEqualTo(2L);
        verify(metrics).recordOverdraftIncurred("acme");

        io.runcycles.protocol.model.ReservationExtendRequest extend =
            new io.runcycles.protocol.model.ReservationExtendRequest();
        extend.setExtendByMs(100L);
        extend.setMetadata(Map.of("reason", "retry"));
        when(luaScripts.eval(eq(jedis), eq("extend"), anyString(), any(String[].class)))
            .thenReturn("{\"expires_at_ms\":200}");
        assertThat(repository.extendReservation("r-extend", extend, null).getExpiresAtMs())
            .isEqualTo(200L);

        when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
        io.runcycles.protocol.model.EventCreateRequest event =
            io.runcycles.protocol.model.EventCreateRequest.builder()
                .subject(defaultSubject()).action(defaultAction())
                .actual(new io.runcycles.protocol.model.Amount(Enums.UnitEnum.TOKENS, 3L))
                .overagePolicy(Enums.CommitOveragePolicy.ALLOW_IF_AVAILABLE)
                .metrics(io.runcycles.protocol.model.StandardMetrics.builder().latencyMs(1).build())
                .clientTimeMs(10L).metadata(Map.of("source", "branch-test")).build();
        when(luaScripts.eval(eq(jedis), eq("event"), anyString(), any(String[].class)))
            .thenReturn("{\"charged\":3}");
        assertThat(repository.createEvent(event, "acme").getCharged().getAmount()).isEqualTo(3L);
    }

    @Test
    void pendingDecisionClaimReturnsWinnerBodyOrRetriableError() throws Exception {
        when(jedisPool.getResource()).thenReturn(jedis);
        @SuppressWarnings("unchecked")
        redis.clients.jedis.Response<String> pending = mock(redis.clients.jedis.Response.class);
        when(pending.get()).thenReturn("__decide_pending__::owner");
        when(pipeline.get("idem:acme:decide:key")).thenReturn(pending);
        when(pipeline.get("idem:acme:decide:timeout")).thenReturn(pending);
        when(jedis.get("idem:acme:decide:key"))
            .thenReturn("{\"decision\":\"ALLOW\"}");
        lenient().when(jedis.get("idem:acme:decide:key:hash")).thenReturn(null);

        io.runcycles.protocol.model.DecisionRequest winner =
            io.runcycles.protocol.model.DecisionRequest.builder()
                .idempotencyKey("key").subject(defaultSubject()).action(defaultAction())
                .estimate(defaultEstimate()).build();
        assertThat(repository.decide(winner, "acme").isIdempotentReplay()).isTrue();

        io.runcycles.protocol.model.DecisionRequest timeout =
            io.runcycles.protocol.model.DecisionRequest.builder()
                .idempotencyKey("timeout").subject(defaultSubject()).action(defaultAction())
                .estimate(defaultEstimate()).build();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> repository.decide(timeout, "acme"))
                .isInstanceOf(CyclesProtocolException.class)
                .extracting("errorCode").isEqualTo(Enums.ErrorCode.INTERNAL_ERROR);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void dryRunBudgetStatesCoverClosedAndChangingPipelineShapes() throws Throwable {
        Method evaluate = method("evaluateDryRun", redis.clients.jedis.Jedis.class,
            io.runcycles.protocol.model.ReservationCreateRequest.class, List.class,
            String.class, String.class);
        io.runcycles.protocol.model.ReservationCreateRequest request = dryRunRequest();

        Map<String, String> closedBudget = new java.util.HashMap<>(budgetMap(10, 10, 0, 0));
        closedBudget.put("status", "CLOSED");
        mockBudget("budget:tenant:closed:TOKENS", closedBudget);
        Object closed = invoke(evaluate, null, jedis, request,
            List.of("tenant:closed"), "tenant:closed", null);
        ReservationCreateResponse closedResponse =
            (ReservationCreateResponse) recordAccessor(closed, "response");
        assertThat(closedResponse.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_CLOSED);

        Map<String, String> active = new java.util.HashMap<>(budgetMap(10, 10, 0, 0));
        active.put("unit", "TOKENS");
        @SuppressWarnings("unchecked")
        redis.clients.jedis.Response<Map<String, String>> nullBalance =
            mock(redis.clients.jedis.Response.class);
        when(nullBalance.get()).thenReturn(active, (Map<String, String>) null);
        @SuppressWarnings("unchecked")
        redis.clients.jedis.Response<List<String>> nullCaps =
            mock(redis.clients.jedis.Response.class);
        when(nullCaps.get()).thenReturn(null);
        when(pipeline.hgetAll("budget:tenant:null-balance:TOKENS")).thenReturn(nullBalance);
        when(pipeline.hmget("budget:tenant:null-balance:TOKENS", "caps_json")).thenReturn(nullCaps);
        Object nullResult = invoke(evaluate, null, jedis, request,
            List.of("tenant:null-balance"), "tenant:null-balance", null);
        assertThat(recordAccessor(nullResult, "response")).isNotNull();

        @SuppressWarnings("unchecked")
        redis.clients.jedis.Response<Map<String, String>> emptyBalance =
            mock(redis.clients.jedis.Response.class);
        when(emptyBalance.get()).thenReturn(active, Map.of());
        @SuppressWarnings("unchecked")
        redis.clients.jedis.Response<List<String>> emptyCaps =
            mock(redis.clients.jedis.Response.class);
        when(emptyCaps.get()).thenReturn(List.of());
        when(pipeline.hgetAll("budget:tenant:empty-balance:TOKENS")).thenReturn(emptyBalance);
        when(pipeline.hmget("budget:tenant:empty-balance:TOKENS", "caps_json")).thenReturn(emptyCaps);
        Object emptyResult = invoke(evaluate, null, jedis, request,
            List.of("tenant:empty-balance"), "tenant:empty-balance", null);
        assertThat(recordAccessor(emptyResult, "response")).isNotNull();
    }

    private io.runcycles.protocol.model.ReservationCreateRequest dryRunRequest() {
        io.runcycles.protocol.model.ReservationCreateRequest request =
            new io.runcycles.protocol.model.ReservationCreateRequest();
        request.setSubject(defaultSubject());
        request.setAction(defaultAction());
        request.setEstimate(new io.runcycles.protocol.model.Amount(Enums.UnitEnum.TOKENS, 1L));
        request.setDryRun(true);
        return request;
    }

    private static String validReserveSnapshot() {
        return "{\"estimate_unit\":\"TOKENS\",\"reservation_id\":\"r1\","
            + "\"scope_path\":\"tenant:acme\",\"estimate_amount\":10,\"expires_at\":20,"
            + "\"affected_scopes\":[\"tenant:acme\"]}";
    }

    private static String commitSnapshot(long charged, long estimate) {
        return "{\"estimate_unit\":\"TOKENS\",\"charged\":" + charged
            + ",\"estimate_amount\":" + estimate + "}";
    }

    private static String releaseSnapshot() {
        return "{\"estimate_unit\":\"TOKENS\",\"estimate_amount\":10}";
    }

    private EvidenceEmitter.PreparedEvidence preparedEvidence() {
        return new EvidenceEmitter.PreparedEvidence(
            new EvidenceEmitter.EvidenceRef("evidence-id", "https://example/evidence"), "{}");
    }

    private static Class<?> replayStateClass() throws ClassNotFoundException {
        return Class.forName(RedisReservationRepository.class.getName() + "$ReplayState");
    }

    private static Object replayState(String snapshot, String state, CyclesEvidenceRef evidence)
            throws Exception {
        Constructor<?> constructor = replayStateClass()
            .getDeclaredConstructor(String.class, String.class, CyclesEvidenceRef.class);
        constructor.setAccessible(true);
        return constructor.newInstance(snapshot, state, evidence);
    }

    private static Object recordAccessor(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private static Method method(String name, Class<?>... types) throws Exception {
        Method method = RedisReservationRepository.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private Object invoke(Method method, Object ignoredTarget, Object... args) throws Throwable {
        try {
            return method.invoke(java.lang.reflect.Modifier.isStatic(method.getModifiers()) ? null : repository, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
