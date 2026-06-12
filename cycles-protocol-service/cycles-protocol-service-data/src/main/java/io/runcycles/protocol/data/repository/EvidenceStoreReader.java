package io.runcycles.protocol.data.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Reads stored CyclesEvidence envelopes by content id from the shared Redis,
 * for {@code GET /v1/evidence/{evidence_id}}.
 *
 * <p>The key convention MUST match the event-tier writer
 * (cycles-server-events {@code RedisEvidenceStore}): the same
 * {@code cycles.evidence.store.key-prefix} and the same Redis instance. The
 * envelope is returned verbatim — content-addressed and signed — so callers
 * verify it offline.
 */
@Repository
public class EvidenceStoreReader {

    @Autowired
    private JedisPool jedisPool;

    @Value("${cycles.evidence.store.key-prefix:evidence:envelope:}")
    private String keyPrefix;

    /** The stored envelope JSON for {@code evidenceId}, or {@code null} if absent. */
    public String get(String evidenceId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(keyPrefix + evidenceId);
        }
    }
}
