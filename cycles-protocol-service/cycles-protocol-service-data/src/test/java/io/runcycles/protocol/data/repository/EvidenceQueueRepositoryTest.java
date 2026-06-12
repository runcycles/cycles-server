package io.runcycles.protocol.data.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.lang.reflect.Field;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvidenceQueueRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;

    private EvidenceQueueRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        when(jedisPool.getResource()).thenReturn(jedis);
        repository = new EvidenceQueueRepository();
        setField(repository, "jedisPool", jedisPool);
        setField(repository, "pendingKey", "evidence:pending");
    }

    @Test
    void lpushesRecordOntoPendingQueue() {
        String record = "{\"artifact_type\":\"reserve\",\"issued_at_ms\":1810000000100}";
        repository.push(record);
        verify(jedis).lpush("evidence:pending", record);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
