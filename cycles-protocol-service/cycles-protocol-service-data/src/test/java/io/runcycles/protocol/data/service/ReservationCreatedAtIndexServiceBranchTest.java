package io.runcycles.protocol.data.service;

import io.runcycles.protocol.data.maintenance.MaintenanceJob;
import io.runcycles.protocol.data.maintenance.RedisMaintenanceRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReservationCreatedAtIndexServiceBranchTest {

    private final LuaScriptRegistry luaScripts = mock(LuaScriptRegistry.class);
    private final Jedis jedis = mock(Jedis.class);
    private ReservationCreatedAtIndexService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ReservationCreatedAtIndexService();
        setField("luaScripts", luaScripts);
        setField("indexScript", "-- index");
        setField("enabled", true);
    }

    @Test
    void readinessAndMutationMethodsRejectMissingInputsWithoutRedisWork() {
        assertThat(service.isReady(jedis, null)).isFalse();
        assertThat(service.isReady(jedis, " ")).isFalse();
        service.invalidate(jedis, null);
        service.invalidate(jedis, " ");
        assertThat(service.publishEmptyReadiness(jedis, null)).isFalse();
        assertThat(service.publishEmptyReadiness(jedis, " ")).isFalse();
        service.removeStaleMembers(jedis, "tenant", null);
        service.removeStaleMembers(jedis, "tenant", List.of());

        verifyNoInteractions(luaScripts);
    }

    @Test
    void readinessRequestsRepairForInvalidAndExceptionalValidation() throws Exception {
        when(luaScripts.eval(eq(jedis), anyString(), anyString(), any(String[].class)))
            .thenReturn(2L, 0L)
            .thenThrow(new IllegalStateException("redis"));

        assertThat(service.isReady(jedis, "empty")).isFalse();
        assertThat(service.isReady(jedis, "invalid")).isFalse();
        assertThat(service.isReady(jedis, "failed")).isFalse();
        assertThat(repairRequested().get()).isTrue();
    }

    @Test
    void publishAndRemovalRequestRepairWhenLuaCannotMaintainReadiness() throws Exception {
        when(luaScripts.eval(eq(jedis), anyString(), anyString(), any(String[].class)))
            .thenReturn(0L, -1L, 1L);

        assertThat(service.publishEmptyReadiness(jedis, "tenant")).isFalse();
        service.removeStaleMembers(jedis, "tenant", List.of("r1"));
        assertThat(service.publishEmptyReadiness(jedis, "tenant")).isTrue();
        assertThat(repairRequested().get()).isTrue();
    }

    @Test
    void pageDecoderValidatesShapeAndBothCursorForms() {
        when(luaScripts.eval(eq(jedis), anyString(), anyString(), any(String[].class)))
            .thenReturn("wrong", List.of("id-only"), List.of("r1", "10"), List.of("r2", "20"));

        assertThatThrownBy(() -> service.readPage(jedis, "tenant", "asc", "-inf", "+inf",
            null, null, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.readPage(jedis, "tenant", "asc", "-inf", "+inf",
            null, null, 10)).isInstanceOf(IllegalStateException.class);
        assertThat(service.readPage(jedis, "tenant", "asc", "-inf", "+inf",
            null, null, 10)).containsExactly(new ReservationCreatedAtIndexService.IndexCandidate("r1", 10D));
        assertThat(service.readPage(jedis, "tenant", "desc", "-inf", "+inf",
            20L, "r2", 10)).containsExactly(new ReservationCreatedAtIndexService.IndexCandidate("r2", 20D));
    }

    @Test
    void scheduledEntryPointsPassEnabledAndDisabledState() throws Exception {
        RedisMaintenanceRunner runner = mock(RedisMaintenanceRunner.class);
        setField("maintenanceRunner", runner);

        service.scheduledRepairIfRequested();
        service.scheduledSweepStaleMembers();
        setField("enabled", false);
        service.scheduledRepairIfRequested();
        service.scheduledSweepStaleMembers();

        verify(runner, times(2)).runIf(eq(MaintenanceJob.CREATED_AT_REPAIR),
            anyBoolean(), any(Runnable.class));
        verify(runner, times(2)).runIf(eq(MaintenanceJob.CREATED_AT_SWEEP),
            anyBoolean(), any(Runnable.class));
    }

    @Test
    void privateKeyAndOverflowHelpersCoverEveryBoundary() throws Exception {
        Method tenantFromKey = ReservationCreatedAtIndexService.class
            .getDeclaredMethod("tenantFromIndexKey", String.class);
        tenantFromKey.setAccessible(true);
        assertThat(tenantFromKey.invoke(null, new Object[]{null})).isNull();
        assertThat(tenantFromKey.invoke(null, "wrong:tenant:created_at_ms")).isNull();
        assertThat(tenantFromKey.invoke(null, "reservation:idx:tenant:wrong")).isNull();
        assertThat(tenantFromKey.invoke(null, "reservation:idx::created_at_ms")).isNull();
        assertThat(tenantFromKey.invoke(null, "reservation:idx:acme:created_at_ms")).isEqualTo("acme");

        Method saturatingAdd = ReservationCreatedAtIndexService.class
            .getDeclaredMethod("saturatingAdd", long.class, long.class);
        saturatingAdd.setAccessible(true);
        assertThat(saturatingAdd.invoke(null, 10L, 20L)).isEqualTo(30L);
        assertThat(saturatingAdd.invoke(null, Long.MAX_VALUE - 2, 3L)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void disabledOperationsAndRepairStateMachineCoverEveryGate() throws Exception {
        setField("enabled", false);
        assertThat(service.publishEmptyReadiness(jedis, "tenant")).isFalse();
        service.removeStaleMembers(jedis, "tenant", List.of("r1"));
        assertThat(service.reconcileNow()).isEqualTo(
            new ReservationCreatedAtIndexService.ReconcileResult(0, 0, 0));
        invokePrivate("repairIfRequestedOnce");
        invokePrivate("sweepStaleMembersOnce");

        service = spy(service);
        setField("enabled", true);
        repairRequested().set(false);
        invokePrivate("repairIfRequestedOnce");
        repairRequested().set(true);
        nextRepairAtMs().set(Long.MAX_VALUE);
        invokePrivate("repairIfRequestedOnce");

        nextRepairAtMs().set(0L);
        doReturn(new ReservationCreatedAtIndexService.ReconcileResult(1, 0, 1))
            .when(service).reconcileNow();
        invokePrivate("repairIfRequestedOnce");
        assertThat(repairRequested().get()).isTrue();
        assertThat(nextRepairAtMs()).hasValueGreaterThan(0L);

        nextRepairAtMs().set(0L);
        doReturn(new ReservationCreatedAtIndexService.ReconcileResult(1, 1, 0))
            .when(service).reconcileNow();
        invokePrivate("repairIfRequestedOnce");
        assertThat(repairRequested().get()).isFalse();
        assertThat(nextRepairAtMs()).hasValue(0L);

        repairRequested().set(true);
        doThrow(new IllegalStateException("repair failed")).when(service).reconcileNow();
        assertThatThrownBy(() -> invokePrivate("repairIfRequestedOnce"))
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(repairRequested().get()).isTrue();
    }

    @Test
    void reconciliationCoversEmptyMalformedAndFinalizeFailureBatches() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        Pipeline pipeline = mock(Pipeline.class);
        setField("jedisPool", pool);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.pipelined()).thenReturn(pipeline);
        when(jedis.scan(anyString(), any(ScanParams.class)))
            .thenReturn(new ScanResult<>("0", List.of()));
        assertThat(service.reconcileNow().keysScanned()).isZero();

        @SuppressWarnings("unchecked")
        Response<List<String>> blankTenant = mock(Response.class);
        @SuppressWarnings("unchecked")
        Response<List<String>> nullId = mock(Response.class);
        @SuppressWarnings("unchecked")
        Response<List<String>> blankId = mock(Response.class);
        @SuppressWarnings("unchecked")
        Response<List<String>> valid = mock(Response.class);
        @SuppressWarnings("unchecked")
        Response<Long> write = mock(Response.class);
        when(blankTenant.get()).thenReturn(java.util.Arrays.asList("blank", " ", "10"));
        when(nullId.get()).thenReturn(java.util.Arrays.asList(null, "acme", "10"));
        when(blankId.get()).thenReturn(List.of("", "acme", "10"));
        when(valid.get()).thenReturn(List.of("valid", "other", "10"));
        when(pipeline.hmget(eq("reservation:res_blank"), any(String[].class))).thenReturn(blankTenant);
        when(pipeline.hmget(eq("reservation:res_null"), any(String[].class))).thenReturn(nullId);
        when(pipeline.hmget(eq("reservation:res_empty"), any(String[].class))).thenReturn(blankId);
        when(pipeline.hmget(eq("reservation:res_valid"), any(String[].class))).thenReturn(valid);
        when(pipeline.zadd(anyString(), anyDouble(), anyString())).thenReturn(write);
        when(write.get()).thenReturn(1L);
        when(jedis.scan(anyString(), any(ScanParams.class))).thenReturn(new ScanResult<>("0",
            List.of("reservation:res_blank", "reservation:res_null",
                "reservation:res_empty", "reservation:res_valid")));
        when(luaScripts.eval(eq(jedis), anyString(), anyString(), any(String[].class)))
            .thenAnswer(invocation -> "finalize".equals(invocation.getArgument(3)) ? 0L : 1L);

        ReservationCreatedAtIndexService.ReconcileResult result = service.reconcileNow();
        assertThat(result.keysScanned()).isEqualTo(4);
        assertThat(result.tenantsFailed()).isEqualTo(2);
    }

    @Test
    void sweepContinuesAcrossScanPagesAndSurvivesInvalidationFailure() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        setField("jedisPool", pool);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.scan(eq("0"), any(ScanParams.class))).thenReturn(
            new ScanResult<>("7".getBytes(), List.of("reservation:idx:acme:created_at_ms")));
        when(jedis.scan(eq("7"), any(ScanParams.class))).thenReturn(
            new ScanResult<>("0".getBytes(), List.of("invalid-index-key")));
        when(luaScripts.eval(eq(jedis), anyString(), anyString(), any(String[].class)))
            .thenAnswer(invocation -> { throw new IllegalStateException("invalid index"); });

        service.sweepStaleMembers();

        assertThat(repairRequested().get()).isTrue();
        verify(jedis).scan(eq("7"), any(ScanParams.class));
    }

    private AtomicBoolean repairRequested() throws Exception {
        Field field = ReservationCreatedAtIndexService.class.getDeclaredField("repairRequested");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(service);
    }

    private AtomicLong nextRepairAtMs() throws Exception {
        Field field = ReservationCreatedAtIndexService.class.getDeclaredField("nextRepairAtMs");
        field.setAccessible(true);
        return (AtomicLong) field.get(service);
    }

    private Object invokePrivate(String name) throws Exception {
        Method method = ReservationCreatedAtIndexService.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(service);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ReservationCreatedAtIndexService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
