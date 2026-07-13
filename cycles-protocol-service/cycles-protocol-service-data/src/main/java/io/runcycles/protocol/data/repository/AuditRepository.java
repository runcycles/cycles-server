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
import java.util.Objects;
import java.util.UUID;

/**
 * Owns audit-entry preparation and retention cleanup for the shared Redis store. The governance
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
 * <p>The admin-release mutation consumes {@link PreparedAudit} inside
 * {@code release.lua}, keeping the audit row and ledger transition atomic.
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

    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    /**
     * Applies the single canonical log-id, timestamp, JSON, score, and retention
     * policy used by atomic audit writers. Serialization failures propagate so a
     * normative admin mutation cannot succeed without its audit row.
     */
    public PreparedAudit prepare(AuditLogEntry entry) throws Exception {
        Objects.requireNonNull(entry, "entry");
        if (entry.getTenantId() == null || entry.getTenantId().isBlank()) {
            throw new IllegalArgumentException("audit tenantId must not be null or blank");
        }
        if (entry.getLogId() == null || entry.getLogId().isBlank()) {
            entry.setLogId("log_" + UUID.randomUUID().toString().substring(0, 16));
        }
        if (entry.getTimestamp() == null) {
            entry.setTimestamp(Instant.now());
        }
        long ttlSeconds = retentionDays > 0 ? (long) retentionDays * 86_400L : 0L;
        return new PreparedAudit(
            objectMapper.writeValueAsString(entry), entry.getLogId(), entry.getTenantId(),
            ttlSeconds, entry.getTimestamp().toEpochMilli());
    }

    public record PreparedAudit(String json, String logId, String tenantId,
                                long ttlSeconds, long score) {}

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
