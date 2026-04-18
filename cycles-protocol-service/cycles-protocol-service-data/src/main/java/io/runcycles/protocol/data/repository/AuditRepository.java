package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.model.audit.AuditLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes audit-log entries to the shared Redis store. The governance
 * plane's admin dashboard reads these via {@code GET /v1/admin/audit/logs}
 * on {@code cycles-server-admin} — both services point at the same
 * Redis instance in every deployed topology (docker-compose, k8s), so
 * entries written here surface in the dashboard's Audit view without
 * any cross-service plumbing.
 *
 * <p>v0.1.25.8: introduced to satisfy cycles-protocol revision
 * 2026-04-13's NORMATIVE requirement that admin-driven releases
 * record an audit-log entry with {@code actor_type=admin_on_behalf_of}.
 *
 * <p>v0.1.25.15: audit entries now respect a configurable TTL
 * (default 400 days — SOC2 Type II 12-month lookback + buffer).
 * Matches the authenticated-tier retention admin's
 * {@code io.runcycles.admin.data.repository.AuditRepository} applies to
 * real-tenant rows. Runtime never writes the admin-plane sentinels
 * ({@code __admin__}, {@code __unauth__}) so a single tier suffices.
 * A daily sweep prunes stale index pointers whose target string key
 * has TTL-expired.
 *
 * <p>Key layout (MUST match {@code
 * io.runcycles.admin.data.repository.AuditRepository}):
 * <ul>
 *   <li>{@code audit:log:<log_id>} — JSON-serialized entry, EX = ttl</li>
 *   <li>{@code audit:logs:<tenant_id>} — per-tenant ZSET index,
 *       score = timestamp millis</li>
 *   <li>{@code audit:logs:_all} — global ZSET index</li>
 * </ul>
 *
 * <p>Atomic write via Lua (SET + 2x ZADD in one call) — prevents
 * orphaned log entries if the server crashes between operations.
 *
 * <p>Failure mode: audit-log failures MUST NOT break the business
 * operation that triggered them. A noisy ERROR log is emitted; the
 * triggering request proceeds normally. Matches the governance
 * plane's repository behavior.
 */
@Repository
public class AuditRepository {
    private static final Logger LOG = LoggerFactory.getLogger(AuditRepository.class);

    /**
     * Retention for runtime-written audit entries. Default 400 days =
     * SOC2 Type II 12-month lookback + 1-month buffer for post-period
     * auditor lag. Set to {@code 0} for indefinite retention (legal
     * hold, HIPAA-adjacent deployments, or environments that offload
     * to an archive store).
     *
     * <p>Matches {@code audit.retention.authenticated.days} on the
     * admin plane — runtime only writes real-tenant rows, so a single
     * tier is sufficient.
     *
     * @since 0.1.25.15
     */
    @Value("${audit.retention.days:400}")
    private int retentionDays;

    /**
     * Lua script for atomic audit-log creation with optional TTL via
     * ARGV[4] (seconds; 0 or negative = no expiry). Identical shape to
     * admin's script — keeps the wire-compatible storage layout the
     * admin dashboard reads against.
     * <pre>
     *   SET audit:log:&#123;logId&#125; &lt;json&gt; [EX ttlSeconds]
     *   ZADD audit:logs:&#123;tenantId&#125; &lt;score&gt; &lt;logId&gt;
     *   ZADD audit:logs:_all         &lt;score&gt; &lt;logId&gt;
     * </pre>
     */
    private static final String LOG_AUDIT_LUA =
        "local ttl = tonumber(ARGV[4])\n" +
        "if ttl and ttl > 0 then\n" +
        "    redis.call('SET', KEYS[1], ARGV[1], 'EX', ttl)\n" +
        "else\n" +
        "    redis.call('SET', KEYS[1], ARGV[1])\n" +
        "end\n" +
        "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])\n" +
        "redis.call('ZADD', KEYS[3], ARGV[2], ARGV[3])\n" +
        "return 1\n";

    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    public void log(AuditLogEntry entry) {
        try (Jedis jedis = jedisPool.getResource()) {
            String logId = "log_" + UUID.randomUUID().toString().substring(0, 16);
            entry.setLogId(logId);
            entry.setTimestamp(Instant.now());
            String json = objectMapper.writeValueAsString(entry);
            String score = String.valueOf(entry.getTimestamp().toEpochMilli());
            long ttlSeconds = retentionDays > 0 ? (long) retentionDays * 86400L : 0L;
            jedis.eval(LOG_AUDIT_LUA,
                List.of("audit:log:" + logId,
                        "audit:logs:" + entry.getTenantId(),
                        "audit:logs:_all"),
                List.of(json, score, logId, String.valueOf(ttlSeconds)));
        } catch (Exception e) {
            // Audit log failure must NOT break the business operation.
            LOG.error("Failed to write audit log (non-fatal): operation={} resource_id={}",
                entry.getOperation(), entry.getResourceId(), e);
        }
    }

    /**
     * Daily sweep of the audit sorted-set indexes to remove pointers whose
     * target {@code audit:log:{id}} key has already expired. Without this
     * sweep the {@code audit:logs:_all} and per-tenant sorted sets would
     * grow unbounded even though the underlying log records are gone —
     * stale pointers still cost memory and lengthen any read-side scan.
     *
     * <p>Mirrors admin's {@code sweepStaleIndexEntries()} so runtime's
     * cleanup is self-contained (does not depend on admin being deployed
     * against the same Redis). Both sweeps scanning the shared index
     * is idempotent and safe — {@code ZREMRANGEBYSCORE} is a no-op on
     * already-swept ranges.
     *
     * <p>Runs at 03:00 server time by default (configurable via
     * {@code audit.sweep.cron}). Deployments with
     * {@code audit.retention.days=0} skip the sweep.
     *
     * <p>Best-effort: any exception is logged at ERROR but never
     * propagates. Skipped on the current tick if Redis is unavailable —
     * the next tick retries.
     *
     * @since 0.1.25.15
     */
    @Scheduled(cron = "${audit.sweep.cron:0 0 3 * * *}")
    public void sweepStaleIndexEntries() {
        if (retentionDays <= 0) {
            LOG.debug("Audit index sweep skipped — retention is indefinite");
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            long cutoffMillis = Instant.now().toEpochMilli()
                    - ((long) retentionDays * 86400L * 1000L);
            long removedGlobal = jedis.zremrangeByScore("audit:logs:_all",
                    Double.NEGATIVE_INFINITY, cutoffMillis);
            long removedTenants = 0;
            ScanParams params = new ScanParams().match("audit:logs:*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                for (String indexKey : scan.getResult()) {
                    if ("audit:logs:_all".equals(indexKey)) {
                        continue;
                    }
                    removedTenants += jedis.zremrangeByScore(indexKey,
                            Double.NEGATIVE_INFINITY, cutoffMillis);
                }
                cursor = scan.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
            LOG.info("Audit index sweep completed — removed {} global + {} per-tenant stale pointers "
                            + "older than {} ms",
                    removedGlobal, removedTenants, cutoffMillis);
        } catch (Exception e) {
            LOG.error("Audit index sweep failed (non-fatal — next tick will retry)", e);
        }
    }
}
