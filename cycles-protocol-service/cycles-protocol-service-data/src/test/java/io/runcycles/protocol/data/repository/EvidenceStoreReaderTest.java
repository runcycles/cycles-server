package io.runcycles.protocol.data.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvidenceStoreReaderTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;

    private EvidenceStoreReader reader;

    @BeforeEach
    void setUp() throws Exception {
        when(jedisPool.getResource()).thenReturn(jedis);
        reader = new EvidenceStoreReader();
        setField(reader, "jedisPool", jedisPool);
        setField(reader, "keyPrefix", "evidence:envelope:");
    }

    @Test
    void getsEnvelopeByContentAddressedKey() {
        when(jedis.get("evidence:envelope:abc123")).thenReturn("{\"evidence_id\":\"abc123\"}");
        assertThat(reader.get("abc123")).isEqualTo("{\"evidence_id\":\"abc123\"}");
    }

    @Test
    void returnsNullWhenAbsent() {
        when(jedis.get("evidence:envelope:missing")).thenReturn(null);
        assertThat(reader.get("missing")).isNull();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
