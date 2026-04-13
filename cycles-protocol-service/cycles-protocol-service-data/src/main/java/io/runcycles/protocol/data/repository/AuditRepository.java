package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.model.audit.AuditLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

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
 * <p>Key layout (MUST match {@code
 * io.runcycles.admin.data.repository.AuditRepository}):
 * <ul>
 *   <li>{@code audit:log:<log_id>} — JSON-serialized entry</li>
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

    // Same Lua script as cycles-server-admin's AuditRepository.
    // Identical key layout and atomicity guarantees.
    private static final String LOG_AUDIT_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
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
            jedis.eval(LOG_AUDIT_LUA,
                List.of("audit:log:" + logId,
                        "audit:logs:" + entry.getTenantId(),
                        "audit:logs:_all"),
                List.of(json, score, logId));
        } catch (Exception e) {
            // Audit log failure must NOT break the business operation.
            LOG.error("Failed to write audit log (non-fatal): operation={} resource_id={}",
                entry.getOperation(), entry.getResourceId(), e);
        }
    }
}
