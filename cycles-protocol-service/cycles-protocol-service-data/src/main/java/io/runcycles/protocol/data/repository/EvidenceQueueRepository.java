package io.runcycles.protocol.data.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Pushes CyclesEvidence SOURCE records onto a dedicated Redis queue
 * ({@code evidence:pending}) for the event-tier evidence worker to
 * canonicalize, sign, store and serve.
 *
 * <p>Deliberately separate from the webhook dispatch queue: the full lifecycle
 * request/response an evidence envelope wraps is an evidence concern, not a
 * webhook payload, and evidence must be produced for every lifecycle call
 * regardless of webhook subscriptions.
 */
@Repository
public class EvidenceQueueRepository {

    @Autowired
    private JedisPool jedisPool;

    @Value("${cycles.evidence.queue.pending-key:evidence:pending}")
    private String pendingKey;

    /** LPUSH a serialized evidence-source record onto the pending queue. */
    public void push(String recordJson) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush(pendingKey, recordJson);
        }
    }
}
