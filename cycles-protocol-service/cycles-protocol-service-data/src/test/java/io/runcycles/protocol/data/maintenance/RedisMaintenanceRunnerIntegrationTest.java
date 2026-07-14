package io.runcycles.protocol.data.maintenance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class RedisMaintenanceRunnerIntegrationTest {

    private static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static JedisPool jedisPool;
    private final List<RedisMaintenanceRunner> runners = new ArrayList<>();
    private SimpleMeterRegistry registry;
    private CyclesMetrics metrics;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        if (jedisPool != null) jedisPool.close();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
        }
        registry = new SimpleMeterRegistry();
        metrics = new CyclesMetrics(registry, false);
    }

    @AfterEach
    void closeRunners() {
        runners.forEach(RedisMaintenanceRunner::close);
    }

    @Test
    void simultaneousRunnersExecuteTheJobExactlyOnce() throws Exception {
        RedisMaintenanceRunner first = runner(1_000, 200);
        RedisMaintenanceRunner second = runner(1_000, 200);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Runnable action = () -> {
                executions.incrementAndGet();
                entered.countDown();
                await(release);
            };
            Future<?> one = executor.submit(() ->
                first.run(MaintenanceJob.AUDIT_RETENTION, action));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            second.run(MaintenanceJob.AUDIT_RETENTION, action);
            release.countDown();
            one.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(executions).hasValue(1);
        assertThat(runCount(MaintenanceJob.AUDIT_RETENTION,
            MaintenanceOutcome.SUCCESS)).isEqualTo(1.0);
        assertThat(runCount(MaintenanceJob.AUDIT_RETENTION,
            MaintenanceOutcome.SKIPPED_LOCKED)).isEqualTo(1.0);
    }

    @Test
    void heartbeatKeepsLongRunExclusiveBeyondOriginalTtl() throws Exception {
        RedisMaintenanceRunner first = runner(400, 75);
        RedisMaintenanceRunner second = runner(400, 75);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> active = executor.submit(() -> first.run(
                MaintenanceJob.EVENT_RETENTION, () -> {
                    entered.countDown();
                    await(release);
                }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(700L);

            second.run(MaintenanceJob.EVENT_RETENTION,
                () -> secondEntered.set(true));

            assertThat(secondEntered).isFalse();
            release.countDown();
            active.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void oldOwnerCannotDeleteSuccessorLease() {
        RedisMaintenanceRunner runner = runner(1_000, 200);
        String key = RedisMaintenanceRunner.leaseKey(MaintenanceJob.CREATED_AT_SWEEP);

        runner.run(MaintenanceJob.CREATED_AT_SWEEP, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.set(key, "successor");
                jedis.pexpire(key, 5_000L);
            }
        });

        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.get(key)).isEqualTo("successor");
        }
        assertThat(runCount(MaintenanceJob.CREATED_AT_SWEEP,
            MaintenanceOutcome.LEASE_LOST)).isEqualTo(1.0);
    }

    @Test
    void abandonedLeaseBecomesEligibleAfterTtl() throws Exception {
        RedisMaintenanceRunner runner = runner(1_000, 200);
        String key = RedisMaintenanceRunner.leaseKey(MaintenanceJob.CREATED_AT_REPAIR);
        AtomicInteger executions = new AtomicInteger();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.psetex(key, 200L, "dead-owner");
        }

        runner.run(MaintenanceJob.CREATED_AT_REPAIR, executions::incrementAndGet);
        assertThat(executions).hasValue(0);
        Thread.sleep(300L);
        runner.run(MaintenanceJob.CREATED_AT_REPAIR, executions::incrementAndGet);

        assertThat(executions).hasValue(1);
    }

    @Test
    void leaseAcquisitionFailureIsNonFatalAndObservable() {
        JedisPool brokenPool = mock(JedisPool.class);
        when(brokenPool.getResource()).thenThrow(new IllegalStateException("redis unavailable"));
        RedisMaintenanceRunner runner = new RedisMaintenanceRunner(
            brokenPool, metrics, 1_000L, 200L);
        runners.add(runner);
        AtomicBoolean executed = new AtomicBoolean();

        assertThatCode(() -> runner.run(MaintenanceJob.RESERVATION_EXPIRY,
            () -> executed.set(true))).doesNotThrowAnyException();

        assertThat(executed).isFalse();
        assertThat(runCount(MaintenanceJob.RESERVATION_EXPIRY,
            MaintenanceOutcome.LEASE_ERROR)).isEqualTo(1.0);
    }

    @Test
    void renewalFailureIsNonFatalAndObservable() {
        RedisMaintenanceRunner runner = runner(800, 100);
        String key = RedisMaintenanceRunner.leaseKey(MaintenanceJob.EVENT_RETENTION);

        assertThatCode(() -> runner.run(MaintenanceJob.EVENT_RETENTION, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(key);
                jedis.zadd(key, 1.0, "wrong-type");
            }
            sleep(250L);
        })).doesNotThrowAnyException();

        assertThat(runCount(MaintenanceJob.EVENT_RETENTION,
            MaintenanceOutcome.LEASE_ERROR)).isEqualTo(1.0);
    }

    @Test
    void actionFailureIsNonFatalReleasesLeaseAndRecordsFailure() {
        RedisMaintenanceRunner runner = runner(1_000, 200);
        String key = RedisMaintenanceRunner.leaseKey(MaintenanceJob.AUDIT_RETENTION);

        assertThatCode(() -> runner.run(MaintenanceJob.AUDIT_RETENTION,
            () -> { throw new IllegalStateException("boom"); }))
            .doesNotThrowAnyException();

        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(key)).isFalse();
        }
        assertThat(runCount(MaintenanceJob.AUDIT_RETENTION,
            MaintenanceOutcome.FAILED)).isEqualTo(1.0);
    }

    @Test
    void disabledJobSkipsRedisAndConfigurationRejectsUnsafeIntervals() {
        RedisMaintenanceRunner runner = runner(1_000, 200);
        AtomicBoolean executed = new AtomicBoolean();

        runner.runIf(MaintenanceJob.CREATED_AT_SWEEP, false,
            () -> executed.set(true));

        assertThat(executed).isFalse();
        assertThat(runCount(MaintenanceJob.CREATED_AT_SWEEP,
            MaintenanceOutcome.SKIPPED_DISABLED)).isEqualTo(1.0);
        assertThatThrownBy(() -> new RedisMaintenanceRunner(
            jedisPool, metrics, 100L, 100L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void narrowButValidRenewalMarginRemainsAllowedWithStartupWarning() {
        assertThatCode(() -> runner(300, 150)).doesNotThrowAnyException();
    }

    @Test
    void metricsFailureCannotBreakMaintenanceOrLeakTheLease() {
        CyclesMetrics brokenMetrics = mock(CyclesMetrics.class);
        doThrow(new IllegalStateException("registry unavailable"))
            .when(brokenMetrics).recordMaintenance(
                eq(MaintenanceJob.RESERVATION_EXPIRY),
                eq(MaintenanceOutcome.SUCCESS),
                org.mockito.ArgumentMatchers.anyLong());
        RedisMaintenanceRunner runner = new RedisMaintenanceRunner(
            jedisPool, brokenMetrics, 1_000L, 200L);
        runners.add(runner);
        AtomicBoolean executed = new AtomicBoolean();

        assertThatCode(() -> runner.run(MaintenanceJob.RESERVATION_EXPIRY,
            () -> executed.set(true))).doesNotThrowAnyException();

        assertThat(executed).isTrue();
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.exists(RedisMaintenanceRunner.leaseKey(
                MaintenanceJob.RESERVATION_EXPIRY))).isFalse();
        }
    }

    private RedisMaintenanceRunner runner(long ttlMs, long renewMs) {
        RedisMaintenanceRunner runner = new RedisMaintenanceRunner(
            jedisPool, metrics, ttlMs, renewMs);
        runners.add(runner);
        return runner;
    }

    private double runCount(MaintenanceJob job, MaintenanceOutcome outcome) {
        return registry.find("cycles.maintenance.runs")
            .tag("job", job.tag())
            .tag("outcome", outcome.tag())
            .counters().stream()
            .mapToDouble(Counter::count)
            .sum();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
