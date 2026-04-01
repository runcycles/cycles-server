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

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventEmitterRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
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
        repository = new EventEmitterRepository();
        setField(repository, "jedisPool", jedisPool);
        setField(repository, "objectMapper", objectMapper);
        setField(repository, "cryptoService", new CryptoService(""));
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
    void emit_savesEventAndDispatches() throws Exception {
        // Setup matching subscription
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .url("https://example.com/hook")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .status(WebhookStatus.ACTIVE)
                .consecutiveFailures(0).disableAfterFailures(10).build();
        when(jedis.smembers("webhooks:t1")).thenReturn(Set.of("whsub_1"));
        when(jedis.smembers("webhooks:__system__")).thenReturn(Collections.emptySet());
        when(jedis.get("webhook:whsub_1")).thenReturn(objectMapper.writeValueAsString(sub));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .category(EventCategory.RESERVATION)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        // Event saved
        verify(jedis).set(startsWith("event:evt_"), anyString());
        verify(jedis).zadd(eq("events:t1"), anyDouble(), anyString());
        verify(jedis).zadd(eq("events:_all"), anyDouble(), anyString());
        // Delivery created + dispatched
        verify(jedis).set(startsWith("delivery:del_"), anyString());
        verify(jedis).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_noMatchingSubscription_noDelivery() {
        when(jedis.smembers("webhooks:t1")).thenReturn(Collections.emptySet());
        when(jedis.smembers("webhooks:__system__")).thenReturn(Collections.emptySet());

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(jedis).set(startsWith("event:"), anyString());
        verify(jedis, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_inactiveSubscription_skipped() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .status(WebhookStatus.DISABLED).build();
        when(jedis.smembers("webhooks:t1")).thenReturn(Set.of("whsub_1"));
        when(jedis.smembers("webhooks:__system__")).thenReturn(Collections.emptySet());
        when(jedis.get("webhook:whsub_1")).thenReturn(objectMapper.writeValueAsString(sub));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(jedis, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_eventTypeMismatch_skipped() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .status(WebhookStatus.ACTIVE).build();
        when(jedis.smembers("webhooks:t1")).thenReturn(Set.of("whsub_1"));
        when(jedis.smembers("webhooks:__system__")).thenReturn(Collections.emptySet());
        when(jedis.get("webhook:whsub_1")).thenReturn(objectMapper.writeValueAsString(sub));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(jedis, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_categoryMatch_dispatches() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventCategories(List.of(EventCategory.RESERVATION))
                .status(WebhookStatus.ACTIVE).build();
        when(jedis.smembers("webhooks:t1")).thenReturn(Set.of("whsub_1"));
        when(jedis.smembers("webhooks:__system__")).thenReturn(Collections.emptySet());
        when(jedis.get("webhook:whsub_1")).thenReturn(objectMapper.writeValueAsString(sub));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .category(EventCategory.RESERVATION)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(jedis).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_scopeFilterMatch() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .scopeFilter("tenant:acme/*")
                .status(WebhookStatus.ACTIVE).build();
        when(jedis.smembers("webhooks:t1")).thenReturn(Set.of("whsub_1"));
        when(jedis.smembers("webhooks:__system__")).thenReturn(Collections.emptySet());
        when(jedis.get("webhook:whsub_1")).thenReturn(objectMapper.writeValueAsString(sub));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").scope("tenant:acme/agent:bot")
                .source("cycles-server").timestamp(Instant.now()).build();

        repository.emit(event);

        verify(jedis).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_scopeFilterMismatch_skipped() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .scopeFilter("tenant:other/*")
                .status(WebhookStatus.ACTIVE).build();
        when(jedis.smembers("webhooks:t1")).thenReturn(Set.of("whsub_1"));
        when(jedis.smembers("webhooks:__system__")).thenReturn(Collections.emptySet());
        when(jedis.get("webhook:whsub_1")).thenReturn(objectMapper.writeValueAsString(sub));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").scope("tenant:acme/agent:bot")
                .source("cycles-server").timestamp(Instant.now()).build();

        repository.emit(event);

        verify(jedis, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_generatesEventIdIfMissing() {
        when(jedis.smembers(anyString())).thenReturn(Collections.emptySet());

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server").build();

        repository.emit(event);

        verify(jedis).set(matches("event:evt_[a-f0-9]+"), anyString());
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
        when(jedis.smembers("webhooks:t1")).thenReturn(Collections.emptySet());
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of("whsub_sys"));
        when(jedis.get("webhook:whsub_sys")).thenReturn(objectMapper.writeValueAsString(sub));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").source("cycles-server")
                .timestamp(Instant.now()).build();

        repository.emit(event);

        verify(jedis).lpush(eq("dispatch:pending"), anyString());
    }
}
