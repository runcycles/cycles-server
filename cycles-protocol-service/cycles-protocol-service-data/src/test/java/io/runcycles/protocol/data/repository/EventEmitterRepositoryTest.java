package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.protocol.data.service.CryptoService;
import io.runcycles.protocol.model.event.*;
import io.runcycles.protocol.model.webhook.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.lang.reflect.Field;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventEmitterRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Mock private Pipeline pipeline;
    private ObjectMapper objectMapper = createMapper();
    private EventEmitterRepository repository;

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        lenient().when(jedis.pipelined()).thenReturn(pipeline);
        repository = new EventEmitterRepository();
        setField(repository, "jedisPool", jedisPool);
        setField(repository, "objectMapper", objectMapper);
        setField(repository, "cryptoService", new CryptoService(""));
        setField(repository, "eventTtlDays", 90);
        setField(repository, "deliveryTtlDays", 14);
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

    /**
     * Setup pipeline stubs for subscription lookup.
     * Pre-creates all mocked Response objects before stubbing to avoid
     * Mockito "unfinished stubbing" errors from nested mock creation.
     */
    private void stubPipelineForSubscriptions(Set<String> tenantSubIds, Set<String> systemSubIds,
                                               Map<String, String> subJsonById) {
        // Pre-create all Response mocks first (before any stubbing)
        @SuppressWarnings("unchecked")
        Response<Set<String>> tenantResp = mock(Response.class);
        @SuppressWarnings("unchecked")
        Response<Set<String>> systemResp = mock(Response.class);

        Map<String, Response<String>> getResponses = new HashMap<>();
        for (Map.Entry<String, String> entry : subJsonById.entrySet()) {
            @SuppressWarnings("unchecked")
            Response<String> r = mock(Response.class);
            getResponses.put(entry.getKey(), r);
        }

        // Now set up when/thenReturn (safe — no nested mock creation)
        when(tenantResp.get()).thenReturn(tenantSubIds);
        when(systemResp.get()).thenReturn(systemSubIds);
        for (Map.Entry<String, String> entry : subJsonById.entrySet()) {
            when(getResponses.get(entry.getKey()).get()).thenReturn(entry.getValue());
        }

        // Stub pipeline.smembers — use String-typed argThat to avoid ambiguity with byte[] overload
        lenient().when(pipeline.smembers(argThat((String k) -> k != null && !k.equals("webhooks:__system__"))))
                .thenReturn(tenantResp);
        lenient().when(pipeline.smembers("webhooks:__system__")).thenReturn(systemResp);

        // Stub pipeline.get for subscription lookups
        for (Map.Entry<String, Response<String>> entry : getResponses.entrySet()) {
            lenient().when(pipeline.get("webhook:" + entry.getKey())).thenReturn(entry.getValue());
        }
    }

    @Test
    void emit_savesEventAndDispatches() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .url("https://example.com/hook")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .status(WebhookStatus.ACTIVE)
                .consecutiveFailures(0).disableAfterFailures(10).build();
        stubPipelineForSubscriptions(Set.of("whsub_1"), Collections.emptySet(),
                Map.of("whsub_1", objectMapper.writeValueAsString(sub)));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .category(EventCategory.RESERVATION)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        // Event saved via pipeline
        verify(pipeline).set(startsWith("event:evt_"), anyString());
        verify(pipeline).zadd(eq("events:t1"), anyDouble(), anyString());
        verify(pipeline).zadd(eq("events:_all"), anyDouble(), anyString());
        // Delivery created via pipeline
        verify(pipeline).set(startsWith("delivery:del_"), anyString());
        verify(pipeline).lpush(eq("dispatch:pending"), anyString());
        verify(pipeline, atLeast(3)).sync();
    }

    @Test
    void emit_noMatchingSubscription_noDelivery() {
        stubPipelineForSubscriptions(Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline).set(startsWith("event:"), anyString());
        verify(pipeline, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_inactiveSubscription_skipped() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .status(WebhookStatus.DISABLED).build();
        stubPipelineForSubscriptions(Set.of("whsub_1"), Collections.emptySet(),
                Map.of("whsub_1", objectMapper.writeValueAsString(sub)));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_eventTypeMismatch_skipped() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .status(WebhookStatus.ACTIVE).build();
        stubPipelineForSubscriptions(Set.of("whsub_1"), Collections.emptySet(),
                Map.of("whsub_1", objectMapper.writeValueAsString(sub)));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_categoryMatch_dispatches() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventCategories(List.of(EventCategory.RESERVATION))
                .status(WebhookStatus.ACTIVE).build();
        stubPipelineForSubscriptions(Set.of("whsub_1"), Collections.emptySet(),
                Map.of("whsub_1", objectMapper.writeValueAsString(sub)));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .category(EventCategory.RESERVATION)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_scopeFilterMatch() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .scopeFilter("tenant:acme/*")
                .status(WebhookStatus.ACTIVE).build();
        stubPipelineForSubscriptions(Set.of("whsub_1"), Collections.emptySet(),
                Map.of("whsub_1", objectMapper.writeValueAsString(sub)));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").scope("tenant:acme/agent:bot")
                .source("cycles-server").timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_scopeFilterMismatch_skipped() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .scopeFilter("tenant:other/*")
                .status(WebhookStatus.ACTIVE).build();
        stubPipelineForSubscriptions(Set.of("whsub_1"), Collections.emptySet(),
                Map.of("whsub_1", objectMapper.writeValueAsString(sub)));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").scope("tenant:acme/agent:bot")
                .source("cycles-server").timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_generatesEventIdIfMissing() {
        stubPipelineForSubscriptions(Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server").build();

        repository.emit(event);

        verify(pipeline).set(matches("event:evt_[a-f0-9]+"), anyString());
    }

    @Test
    void emit_redisError_doesNotThrow() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis down"));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server").build();

        // Should not throw
        repository.emit(event);
    }

    @Test
    void emit_systemSubscription_matches() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_sys").tenantId("__system__")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .status(WebhookStatus.ACTIVE).build();
        stubPipelineForSubscriptions(Collections.emptySet(), Set.of("whsub_sys"),
                Map.of("whsub_sys", objectMapper.writeValueAsString(sub)));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline).lpush(eq("dispatch:pending"), anyString());
    }
}
