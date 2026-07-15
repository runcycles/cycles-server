package io.runcycles.protocol.data.maintenance;

import io.runcycles.protocol.data.metrics.CyclesMetrics;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisMaintenanceRunnerBranchTest {

    @Test
    void constructorRejectsEveryInvalidLeaseBoundary() {
        JedisPool pool = mock(JedisPool.class);
        CyclesMetrics metrics = mock(CyclesMetrics.class);

        assertThatThrownBy(() -> new RedisMaintenanceRunner(pool, metrics, 0, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisMaintenanceRunner(pool, metrics, 100, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisMaintenanceRunner(pool, metrics, 100, 101))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameRunnerSkipsReentrantExecutionLocally() {
        JedisPool pool = successfulPool(1L);
        CyclesMetrics metrics = mock(CyclesMetrics.class);
        RedisMaintenanceRunner runner = new RedisMaintenanceRunner(pool, metrics, 1_000, 200);
        AtomicInteger executions = new AtomicInteger();
        try {
            runner.run(MaintenanceJob.AUDIT_RETENTION, () -> {
                executions.incrementAndGet();
                runner.run(MaintenanceJob.AUDIT_RETENTION, executions::incrementAndGet);
            });
        } finally {
            runner.close();
        }

        assertThat(executions).hasValue(1);
        verify(metrics).recordMaintenance(eq(MaintenanceJob.AUDIT_RETENTION),
            eq(MaintenanceOutcome.SKIPPED_LOCKED), anyLong());
    }

    @Test
    void nonNumericRenewalMarksLeaseLostAndSubsequentHeartbeatsShortCircuit() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(redis.clients.jedis.params.SetParams.class)))
            .thenReturn("OK");
        when(jedis.eval(eq(RedisMaintenanceRunner.RENEW_LUA), anyList(), anyList()))
            .thenReturn("not-a-number");
        when(jedis.eval(eq(RedisMaintenanceRunner.RELEASE_LUA), anyList(), anyList()))
            .thenReturn(1L);
        CyclesMetrics metrics = mock(CyclesMetrics.class);
        ScheduledExecutorService renewer = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> heartbeat = new AtomicReference<>();
        when(renewer.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
            .thenAnswer(invocation -> {
                heartbeat.set(invocation.getArgument(0));
                return future;
            });
        RedisMaintenanceRunner runner = new RedisMaintenanceRunner(pool, metrics, 1_000, 200, renewer);

        runner.run(MaintenanceJob.EVENT_RETENTION, () -> {
            heartbeat.get().run();
            heartbeat.get().run();
        });
        heartbeat.get().run();

        verify(metrics).recordMaintenance(eq(MaintenanceJob.EVENT_RETENTION),
            eq(MaintenanceOutcome.LEASE_LOST), anyLong());
        verify(jedis, times(1)).eval(eq(RedisMaintenanceRunner.RENEW_LUA), anyList(), anyList());
        verify(future).cancel(false);
    }

    @Test
    void schedulingAndReleaseFailuresRemainFailOpen() {
        JedisPool pool = successfulPool(1L);
        CyclesMetrics metrics = mock(CyclesMetrics.class);
        ScheduledExecutorService renewer = mock(ScheduledExecutorService.class);
        when(renewer.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
            .thenThrow(new IllegalStateException("scheduler stopped"));
        RedisMaintenanceRunner schedulingFailure =
            new RedisMaintenanceRunner(pool, metrics, 1_000, 200, renewer);

        assertThatCode(() -> schedulingFailure.run(MaintenanceJob.CREATED_AT_SWEEP, () -> {}))
            .doesNotThrowAnyException();

        JedisPool releaseFailurePool = mock(JedisPool.class);
        Jedis acquireJedis = mock(Jedis.class);
        when(releaseFailurePool.getResource()).thenReturn(acquireJedis)
            .thenThrow(new IllegalStateException("release unavailable"));
        when(acquireJedis.set(anyString(), anyString(), any(redis.clients.jedis.params.SetParams.class)))
            .thenReturn("OK");
        RedisMaintenanceRunner releaseFailure =
            new RedisMaintenanceRunner(releaseFailurePool, metrics, 1_000, 200);
        try {
            assertThatCode(() -> releaseFailure.run(MaintenanceJob.CREATED_AT_REPAIR, () -> {}))
                .doesNotThrowAnyException();
        } finally {
            releaseFailure.close();
        }

        verify(metrics).recordMaintenance(eq(MaintenanceJob.CREATED_AT_REPAIR),
            eq(MaintenanceOutcome.LEASE_ERROR), anyLong());
    }

    @Test
    void numericLeaseLossRenewalErrorAndNonNumericReleaseAreReported() {
        runCapturedHeartbeat(0L, 1L, MaintenanceOutcome.LEASE_LOST);
        runCapturedHeartbeat(new IllegalStateException("renew unavailable"), 1L,
            MaintenanceOutcome.LEASE_ERROR);

        JedisPool pool = successfulPool("not-a-number");
        CyclesMetrics metrics = mock(CyclesMetrics.class);
        RedisMaintenanceRunner runner = new RedisMaintenanceRunner(pool, metrics, 1_000, 200);
        try {
            runner.run(MaintenanceJob.AUDIT_RETENTION, () -> {});
        } finally {
            runner.close();
        }
        verify(metrics).recordMaintenance(eq(MaintenanceJob.AUDIT_RETENTION),
            eq(MaintenanceOutcome.LEASE_LOST), anyLong());
    }

    private static void runCapturedHeartbeat(Object renewalResult, Object releaseResult,
                                             MaintenanceOutcome expected) {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(redis.clients.jedis.params.SetParams.class)))
            .thenReturn("OK");
        if (renewalResult instanceof RuntimeException exception) {
            when(jedis.eval(eq(RedisMaintenanceRunner.RENEW_LUA), anyList(), anyList()))
                .thenThrow(exception);
        } else {
            when(jedis.eval(eq(RedisMaintenanceRunner.RENEW_LUA), anyList(), anyList()))
                .thenReturn(renewalResult);
        }
        when(jedis.eval(eq(RedisMaintenanceRunner.RELEASE_LUA), anyList(), anyList()))
            .thenReturn(releaseResult);
        CyclesMetrics metrics = mock(CyclesMetrics.class);
        ScheduledExecutorService renewer = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> heartbeat = new AtomicReference<>();
        when(renewer.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(),
                eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation -> {
                    heartbeat.set(invocation.getArgument(0));
                    return future;
                });
        RedisMaintenanceRunner runner = new RedisMaintenanceRunner(pool, metrics, 1_000, 200, renewer);

        runner.run(MaintenanceJob.CREATED_AT_SWEEP, () -> heartbeat.get().run());

        verify(metrics).recordMaintenance(eq(MaintenanceJob.CREATED_AT_SWEEP),
            eq(expected), anyLong());
    }

    private static JedisPool successfulPool(Object evalResult) {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(redis.clients.jedis.params.SetParams.class)))
            .thenReturn("OK");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(evalResult);
        return pool;
    }
}
