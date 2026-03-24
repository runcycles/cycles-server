package io.runcycles.protocol.data.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

/**
 * Cycles Protocol v0.1.24 - Background job that marks expired reservations.
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
                } catch (Exception e) {
                    LOG.warn("Failed to expire reservation: {}", reservationId, e);
                }
            }
        } catch (Exception e) {
            LOG.error("Expiry sweep failed", e);
        }
    }
}
