package io.runcycles.protocol.data.service;

import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Cycles Protocol v0.1.25 - Background job that marks expired reservations.
 *
 * Scans the reservation:ttl sorted set for candidates whose score (expires_at_ms)
 * is in the past, then invokes expire.lua per reservation, which:
 *   1. Checks the reservation is still ACTIVE and past expires_at + grace_ms.
 *   2. Releases reserved budget back to all affected scopes.
 *   3. Sets state = EXPIRED and removes the entry from reservation:ttl.
 */
@Service
public class ReservationExpiryService {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationExpiryService.class);

    @Autowired private JedisPool jedisPool;
    @Autowired private LuaScriptRegistry luaScripts;
    @Autowired @Qualifier("expireLuaScript") private String expireScript;
    @Autowired private EventEmitterService eventEmitter;
    @Autowired private CyclesMetrics metrics;

    /** Max candidates per sweep to avoid OOM after prolonged outages. */
    private static final int SWEEP_BATCH_SIZE = 1000;

    @Scheduled(fixedDelayString = "${cycles.expiry.interval-ms:5000}",
               initialDelayString = "${cycles.expiry.initial-delay-ms:5000}")
    public void expireReservations() {
        try (Jedis jedis = jedisPool.getResource()) {
            // Use Redis TIME (not System.currentTimeMillis) so the candidate query is
            // consistent with the Lua scripts, which all use Redis TIME internally.
            List<String> timeResult = jedis.time();
            long now = Long.parseLong(timeResult.get(0)) * 1000
                     + Long.parseLong(timeResult.get(1)) / 1000;

            // Fetch up to SWEEP_BATCH_SIZE reservation IDs whose score (expires_at_ms) <= now.
            // Any that are still in their grace period will be skipped by the Lua script.
            List<String> candidates = jedis.zrangeByScore("reservation:ttl", 0, now, 0, SWEEP_BATCH_SIZE);
            if (candidates.isEmpty()) return;

            LOG.debug("Expiry sweep: {} candidate(s)", candidates.size());

            for (String reservationId : candidates) {
                try {
                    // expire.lua now uses Redis TIME internally for consistent time-source
                    // with reserve/commit/release/extend scripts. We still pass reservation_id only.
                    Object result = luaScripts.eval(jedis, "expire", expireScript, reservationId);
                    LOG.debug("Expire result: reservationId={} result={}", reservationId, result);
                    // Emit reservation.expired event if the Lua script actually expired this reservation
                    emitExpiredEvent(jedis, reservationId, result);
                } catch (Exception e) {
                    LOG.warn("Failed to expire reservation: {}", reservationId, e);
                }
            }
        } catch (Exception e) {
            LOG.error("Expiry sweep failed", e);
        }
    }

    private void emitExpiredEvent(Jedis jedis, String reservationId, Object luaResult) {
        try {
            // Only emit if the Lua script actually expired this reservation (status == "EXPIRED")
            String resultStr = luaResult != null ? luaResult.toString() : "";
            if (!resultStr.contains("EXPIRED")) return;

            // Fetch reservation hash for event payload — one HGETALL per expired reservation.
            // Hash key has the "res_" prefix (consistent with expire.lua and all other scripts);
            // the TTL zset stores plain ids, so we must re-add the prefix here. This was
            // previously wrong (missing prefix) — dormant because hgetAll on a non-existent key
            // returns an empty map rather than throwing, so the method just silently no-op'd.
            // Surfaced by the new cycles.reservations.expired counter test in v0.1.25.10.
            Map<String, String> hash = jedis.hgetAll("reservation:res_" + reservationId);
            if (hash == null || hash.isEmpty()) return;

            String tenantId = hash.get("tenant");
            if (tenantId == null) return;

            // Counter is bumped once per actual EXPIRED transition (SKIP results from
            // still-in-grace or already-finalised reservations are filtered out above).
            metrics.recordExpired(tenantId);

            String scopePath = hash.get("scope_path");
            String unit = hash.get("estimate_unit");
            Long estimateAmount = parseLong(hash.get("estimate_amount"));
            Long createdAtMs = parseLong(hash.get("created_at_ms"));
            Long expiresAtMs = parseLong(hash.get("expires_at_ms"));
            Integer extensionCount = parseInt(hash.get("extension_count"));
            Integer ttlMs = (createdAtMs != null && expiresAtMs != null)
                    ? (int) (expiresAtMs - createdAtMs) : null;

            eventEmitter.emit(EventType.RESERVATION_EXPIRED, tenantId, scopePath,
                    Actor.builder().type(ActorType.SYSTEM).build(),
                    EventDataReservationExpired.builder()
                            .reservationId(reservationId)
                            .scope(scopePath)
                            .unit(unit)
                            .estimatedAmount(estimateAmount)
                            .createdAt(createdAtMs != null ? Instant.ofEpochMilli(createdAtMs) : null)
                            .expiredAt(expiresAtMs != null ? Instant.ofEpochMilli(expiresAtMs) : null)
                            .ttlMs(ttlMs)
                            .extensionsUsed(extensionCount)
                            .build(),
                    null, null);
        } catch (Exception e) {
            LOG.debug("Failed to emit reservation.expired event for {}: {}", reservationId, e.getMessage());
        }
    }

    private static Long parseLong(String value) {
        if (value == null) return null;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return null; }
    }

    private static Integer parseInt(String value) {
        if (value == null) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
    }
}
