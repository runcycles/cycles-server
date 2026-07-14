package io.runcycles.protocol.data.maintenance;

import io.runcycles.protocol.data.metrics.CyclesMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes scheduled Redis maintenance under a per-job, owner-safe lease.
 *
 * <p>The lease is defensive coordination rather than a correctness primitive:
 * every underlying job remains idempotent. A bounded heartbeat renews long
 * runs, while compare-and-delete prevents an old owner from deleting a
 * successor's lease after expiry or failover.
 */
@Component
public class RedisMaintenanceRunner implements AutoCloseable, DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(RedisMaintenanceRunner.class);

    static final String LEASE_PREFIX = "cycles:maintenance:lease:";
    static final String RENEW_LUA = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          return redis.call('PEXPIRE', KEYS[1], ARGV[2])
        end
        return 0
        """;
    static final String RELEASE_LUA = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          return redis.call('DEL', KEYS[1])
        end
        return 0
        """;

    private final JedisPool jedisPool;
    private final CyclesMetrics metrics;
    private final long leaseTtlMs;
    private final long renewIntervalMs;
    private final String instanceId = UUID.randomUUID().toString();
    private final ScheduledExecutorService renewer;
    private final Map<MaintenanceJob, AtomicBoolean> localRuns =
        new EnumMap<>(MaintenanceJob.class);

    @Autowired
    public RedisMaintenanceRunner(
            JedisPool jedisPool,
            CyclesMetrics metrics,
            @Value("${cycles.maintenance.lease-ttl-ms:30000}") long leaseTtlMs,
            @Value("${cycles.maintenance.renew-interval-ms:10000}") long renewIntervalMs) {
        this(jedisPool, metrics, leaseTtlMs, renewIntervalMs,
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cycles-maintenance-lease-renewer");
                thread.setDaemon(true);
                return thread;
            }));
    }

    RedisMaintenanceRunner(JedisPool jedisPool, CyclesMetrics metrics,
                           long leaseTtlMs, long renewIntervalMs,
                           ScheduledExecutorService renewer) {
        if (leaseTtlMs <= 0) {
            throw new IllegalArgumentException("cycles.maintenance.lease-ttl-ms must be positive");
        }
        if (renewIntervalMs <= 0 || renewIntervalMs >= leaseTtlMs) {
            throw new IllegalArgumentException(
                "cycles.maintenance.renew-interval-ms must be positive and less than the lease TTL");
        }
        this.jedisPool = jedisPool;
        this.metrics = metrics;
        this.leaseTtlMs = leaseTtlMs;
        this.renewIntervalMs = renewIntervalMs;
        this.renewer = renewer;
        for (MaintenanceJob job : MaintenanceJob.values()) {
            localRuns.put(job, new AtomicBoolean());
        }
    }

    public void run(MaintenanceJob job, Runnable action) {
        runIf(job, true, action);
    }

    public void runIf(MaintenanceJob job, boolean enabled, Runnable action) {
        long startedAt = System.nanoTime();
        if (!enabled) {
            record(job, MaintenanceOutcome.SKIPPED_DISABLED, startedAt);
            return;
        }

        AtomicBoolean localRun = localRuns.get(job);
        if (!localRun.compareAndSet(false, true)) {
            record(job, MaintenanceOutcome.SKIPPED_LOCKED, startedAt);
            return;
        }

        MaintenanceOutcome outcome = MaintenanceOutcome.LEASE_ERROR;
        LeaseState lease = null;
        ScheduledFuture<?> heartbeat = null;
        try {
            lease = acquire(job);
            if (lease == null) {
                outcome = MaintenanceOutcome.SKIPPED_LOCKED;
                return;
            }
            LeaseState acquiredLease = lease;
            heartbeat = renewer.scheduleAtFixedRate(
                () -> renew(acquiredLease), renewIntervalMs, renewIntervalMs,
                TimeUnit.MILLISECONDS);
            action.run();
            outcome = lease.lost.get()
                ? MaintenanceOutcome.LEASE_LOST
                : (lease.error.get() ? MaintenanceOutcome.LEASE_ERROR : MaintenanceOutcome.SUCCESS);
        } catch (Exception e) {
            outcome = lease == null
                ? MaintenanceOutcome.LEASE_ERROR : MaintenanceOutcome.FAILED;
            LOG.error("Scheduled Redis maintenance failed: job={}", job.tag(), e);
        } finally {
            if (lease != null) {
                closeHeartbeat(lease, heartbeat);
                MaintenanceOutcome releaseOutcome = release(lease);
                if (outcome == MaintenanceOutcome.SUCCESS
                        && releaseOutcome != MaintenanceOutcome.SUCCESS) {
                    outcome = releaseOutcome;
                }
            }
            localRun.set(false);
            record(job, outcome, startedAt);
        }
    }

    private LeaseState acquire(MaintenanceJob job) {
        String key = leaseKey(job);
        String token = instanceId + ":" + UUID.randomUUID();
        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.set(key, token,
                SetParams.setParams().nx().px(leaseTtlMs));
            return "OK".equals(result) ? new LeaseState(key, token) : null;
        }
    }

    private void renew(LeaseState lease) {
        synchronized (lease) {
            if (lease.closed || lease.lost.get()) return;
            try (Jedis jedis = jedisPool.getResource()) {
                Object result = jedis.eval(RENEW_LUA,
                    List.of(lease.key), List.of(lease.token, String.valueOf(leaseTtlMs)));
                if (!(result instanceof Number number) || number.longValue() != 1L) {
                    if (lease.lost.compareAndSet(false, true)) {
                        LOG.warn("Scheduled Redis maintenance lease was lost: key={}", lease.key);
                    }
                }
            } catch (Exception e) {
                lease.error.set(true);
                LOG.warn("Unable to renew scheduled Redis maintenance lease: key={}", lease.key, e);
            }
        }
    }

    private void closeHeartbeat(LeaseState lease, ScheduledFuture<?> heartbeat) {
        synchronized (lease) {
            lease.closed = true;
        }
        if (heartbeat != null) heartbeat.cancel(false);
    }

    private MaintenanceOutcome release(LeaseState lease) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(RELEASE_LUA,
                List.of(lease.key), List.of(lease.token));
            if (result instanceof Number number && number.longValue() == 1L) {
                return lease.lost.get()
                    ? MaintenanceOutcome.LEASE_LOST
                    : (lease.error.get()
                        ? MaintenanceOutcome.LEASE_ERROR : MaintenanceOutcome.SUCCESS);
            }
            return MaintenanceOutcome.LEASE_LOST;
        } catch (Exception e) {
            LOG.warn("Unable to release scheduled Redis maintenance lease: key={}", lease.key, e);
            return MaintenanceOutcome.LEASE_ERROR;
        }
    }

    private void record(MaintenanceJob job, MaintenanceOutcome outcome, long startedAt) {
        try {
            metrics.recordMaintenance(job, outcome, System.nanoTime() - startedAt);
        } catch (Exception e) {
            LOG.warn("Unable to record scheduled Redis maintenance metrics: job={}, outcome={}",
                job.tag(), outcome.tag(), e);
        }
    }

    static String leaseKey(MaintenanceJob job) {
        return LEASE_PREFIX + job.tag();
    }

    @Override
    public void close() {
        renewer.shutdownNow();
    }

    @Override
    public void destroy() {
        close();
    }

    private static final class LeaseState {
        private final String key;
        private final String token;
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicBoolean error = new AtomicBoolean();
        private boolean closed;

        private LeaseState(String key, String token) {
            this.key = key;
            this.token = token;
        }
    }
}
