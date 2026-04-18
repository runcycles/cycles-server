package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.protocol.model.audit.AuditLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    private ObjectMapper objectMapper = createMapper();
    private AuditRepository repository;

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        repository = new AuditRepository();
        setField(repository, "jedisPool", jedisPool);
        setField(repository, "objectMapper", objectMapper);
        setField(repository, "retentionDays", 400);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void log_passesTtlAsArgv4() {
        AuditLogEntry entry = AuditLogEntry.builder()
            .tenantId("tenant_test")
            .operation("test.op")
            .status(200)
            .build();

        repository.log(entry);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argvCaptor = ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), any(List.class), argvCaptor.capture());
        List<String> argv = argvCaptor.getValue();
        assertThat(argv).hasSize(4);
        // ARGV[4] = ttl in seconds. 400 days * 86400.
        assertThat(argv.get(3)).isEqualTo(String.valueOf(400L * 86400L));
    }

    @Test
    void log_whenRetentionZero_passesZeroTtl() {
        setField(repository, "retentionDays", 0);
        AuditLogEntry entry = AuditLogEntry.builder()
            .tenantId("tenant_test").operation("test.op").status(200).build();

        repository.log(entry);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argvCaptor = ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), any(List.class), argvCaptor.capture());
        assertThat(argvCaptor.getValue().get(3)).isEqualTo("0");
    }

    @Test
    void log_setsLogIdAndTimestamp() {
        AuditLogEntry entry = AuditLogEntry.builder()
            .tenantId("tenant_test").operation("test.op").status(200).build();

        repository.log(entry);

        assertThat(entry.getLogId()).startsWith("log_");
        assertThat(entry.getTimestamp()).isNotNull();
    }

    @Test
    void log_redisFailureIsNonFatal() {
        when(jedis.eval(anyString(), any(List.class), any(List.class)))
            .thenThrow(new RuntimeException("redis down"));
        AuditLogEntry entry = AuditLogEntry.builder()
            .tenantId("tenant_test").operation("test.op").status(200).build();

        // Must not throw.
        repository.log(entry);
    }

    @Test
    void sweepStaleIndexEntries_removesGlobalAndPerTenantPointers() {
        when(jedis.zremrangeByScore(eq("audit:logs:_all"), anyDouble(), anyDouble())).thenReturn(5L);
        ScanResult<String> scan = new ScanResult<>(
            ScanParams.SCAN_POINTER_START.getBytes(),
            List.of("audit:logs:tenant_a", "audit:logs:_all", "audit:logs:tenant_b"));
        when(jedis.scan(eq(ScanParams.SCAN_POINTER_START), any(ScanParams.class))).thenReturn(scan);
        when(jedis.zremrangeByScore(eq("audit:logs:tenant_a"), anyDouble(), anyDouble())).thenReturn(2L);
        when(jedis.zremrangeByScore(eq("audit:logs:tenant_b"), anyDouble(), anyDouble())).thenReturn(3L);

        repository.sweepStaleIndexEntries();

        verify(jedis).zremrangeByScore(eq("audit:logs:_all"), anyDouble(), anyDouble());
        verify(jedis).zremrangeByScore(eq("audit:logs:tenant_a"), anyDouble(), anyDouble());
        verify(jedis).zremrangeByScore(eq("audit:logs:tenant_b"), anyDouble(), anyDouble());
        // _all is scanned-up but skipped inside the loop (already handled above).
        verify(jedis, times(1)).zremrangeByScore(eq("audit:logs:_all"), anyDouble(), anyDouble());
    }

    @Test
    void sweepStaleIndexEntries_whenRetentionZero_isNoOp() {
        setField(repository, "retentionDays", 0);
        repository.sweepStaleIndexEntries();
        verify(jedis, never()).zremrangeByScore(anyString(), anyDouble(), anyDouble());
        verify(jedis, never()).scan(anyString(), any(ScanParams.class));
    }

    @Test
    void sweepStaleIndexEntries_redisFailureIsNonFatal() {
        when(jedis.zremrangeByScore(eq("audit:logs:_all"), anyDouble(), anyDouble()))
            .thenThrow(new RuntimeException("redis down"));
        // Must not throw.
        repository.sweepStaleIndexEntries();
    }
}
