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
import java.lang.reflect.Method;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void retentionSweepFailureIsNonFatal() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("redis unavailable"));

        repository.sweepStaleIndexEntries();

        verify(jedisPool).getResource();
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
    void emit_nullEventIsContainedByFailOpenBoundary() {
        repository.emit(null);

        verifyNoInteractions(pipeline);
    }

    @Test
    void emit_nullEventTypeLeavesCategoryUnset() {
        stubPipelineForSubscriptions(Collections.emptySet(), Collections.emptySet(), Collections.emptyMap());
        Event event = Event.builder().eventId("evt_existing").tenantId("t1")
            .source("cycles-server").timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline).set(eq("event:evt_existing"), anyString());
    }

    @Test
    void emit_handlesNullSubscriptionSets() {
        stubPipelineForSubscriptions(null, null, Collections.emptyMap());
        Event event = Event.builder().eventId("evt_null_sets")
            .eventType(EventType.RESERVATION_DENIED).tenantId("t1")
            .source("cycles-server").timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void emit_skipsDeletedSubscriptionPayload() {
        Map<String, String> deleted = new HashMap<>();
        deleted.put("whsub_deleted", null);
        stubPipelineForSubscriptions(Set.of("whsub_deleted"), Collections.emptySet(), deleted);
        Event event = Event.builder().eventType(EventType.RESERVATION_DENIED).tenantId("t1")
            .source("cycles-server").timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline, never()).lpush(eq("dispatch:pending"), anyString());
    }

    @Test
    void retentionSweep_prunesEventAndDeliveryIndexes() {
        ScanResult<String> first = new ScanResult<>(ScanParams.SCAN_POINTER_START.getBytes(),
                List.of("events:t1", "events:_all"));
        ScanResult<String> second = new ScanResult<>(ScanParams.SCAN_POINTER_START.getBytes(),
                List.of("deliveries:whsub_1"));
        when(jedis.scan(eq(ScanParams.SCAN_POINTER_START), any(ScanParams.class)))
                .thenReturn(first, second);
        when(jedis.zremrangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(1L);

        repository.sweepStaleIndexEntries();

        verify(jedis).zremrangeByScore(eq("events:t1"), eq(Double.NEGATIVE_INFINITY), anyDouble());
        verify(jedis).zremrangeByScore(eq("events:_all"), eq(Double.NEGATIVE_INFINITY), anyDouble());
        verify(jedis).zremrangeByScore(eq("deliveries:whsub_1"), eq(Double.NEGATIVE_INFINITY), anyDouble());
    }

    @Test
    void retentionSweep_skipsCorrelationSetAndContinuesToDeliveries() {
        ScanResult<String> first = new ScanResult<>(ScanParams.SCAN_POINTER_START.getBytes(),
                List.of("events:correlation:req-1", "events:t1"));
        ScanResult<String> second = new ScanResult<>(ScanParams.SCAN_POINTER_START.getBytes(),
                List.of("deliveries:whsub_1"));
        when(jedis.scan(eq(ScanParams.SCAN_POINTER_START), any(ScanParams.class)))
                .thenReturn(first, second);
        when(jedis.type("events:correlation:req-1")).thenReturn("set");
        when(jedis.type("events:t1")).thenReturn("zset");
        when(jedis.type("deliveries:whsub_1")).thenReturn("zset");

        repository.sweepStaleIndexEntries();

        verify(jedis, never()).zremrangeByScore(eq("events:correlation:req-1"), anyDouble(), anyDouble());
        verify(jedis).zremrangeByScore(eq("events:t1"), eq(Double.NEGATIVE_INFINITY), anyDouble());
        verify(jedis).zremrangeByScore(eq("deliveries:whsub_1"), eq(Double.NEGATIVE_INFINITY), anyDouble());
    }

    @Test
    void retentionSweep_continuesWhenKeyChangesTypeAfterTypeCheck() {
        ScanResult<String> first = new ScanResult<>(ScanParams.SCAN_POINTER_START.getBytes(),
                List.of("events:t1"));
        ScanResult<String> second = new ScanResult<>(ScanParams.SCAN_POINTER_START.getBytes(),
                List.of("deliveries:whsub_1"));
        when(jedis.scan(eq(ScanParams.SCAN_POINTER_START), any(ScanParams.class)))
                .thenReturn(first, second);
        when(jedis.type(anyString())).thenReturn("zset");
        when(jedis.zremrangeByScore(eq("events:t1"), anyDouble(), anyDouble()))
                .thenThrow(new redis.clients.jedis.exceptions.JedisDataException("WRONGTYPE"));

        repository.sweepStaleIndexEntries();

        verify(jedis).zremrangeByScore(eq("deliveries:whsub_1"), eq(Double.NEGATIVE_INFINITY), anyDouble());
    }

    @Test
    void retentionSweep_followsMultiBatchScanCursor() {
        ScanResult<String> eventFirst = new ScanResult<>("7".getBytes(), List.of("events:t1"));
        ScanResult<String> eventLast = new ScanResult<>(ScanParams.SCAN_POINTER_START.getBytes(), List.of());
        ScanResult<String> deliveryLast = new ScanResult<>(ScanParams.SCAN_POINTER_START.getBytes(), List.of());
        when(jedis.scan(eq(ScanParams.SCAN_POINTER_START), any(ScanParams.class)))
            .thenReturn(eventFirst, deliveryLast);
        when(jedis.scan(eq("7"), any(ScanParams.class))).thenReturn(eventLast);

        repository.sweepStaleIndexEntries();

        verify(jedis).scan(eq("7"), any(ScanParams.class));
    }

    @Test
    void eventTypeMatcherCoversEmptyAndMismatchedFilterCombinations() throws Exception {
        assertTrue(matchesEventType(WebhookSubscription.builder().build(), EventType.RESERVATION_DENIED));
        assertTrue(matchesEventType(WebhookSubscription.builder()
            .eventTypes(Collections.emptyList()).eventCategories(Collections.emptyList()).build(),
            EventType.RESERVATION_DENIED));
        assertFalse(matchesEventType(WebhookSubscription.builder()
            .eventTypes(Collections.emptyList()).eventCategories(List.of(EventCategory.BUDGET)).build(),
            EventType.RESERVATION_DENIED));
        assertFalse(matchesEventType(WebhookSubscription.builder()
            .eventTypes(List.of(EventType.BUDGET_CREATED)).eventCategories(Collections.emptyList()).build(),
            EventType.RESERVATION_DENIED));
    }

    private boolean matchesEventType(WebhookSubscription subscription, EventType eventType) throws Exception {
        Method matcher = EventEmitterRepository.class.getDeclaredMethod(
            "matchesEventType", WebhookSubscription.class, EventType.class);
        matcher.setAccessible(true);
        return (boolean) matcher.invoke(repository, subscription, eventType);
    }

    // ---- matchesScope (spec scope_filter wildcard semantics) ----
    // Spec authority (admin OpenAPI, WebhookCreateRequest.scope_filter):
    // "Optional scope pattern to narrow event matching. Supports wildcards:
    //  \"tenant:acme-corp/*\" matches all scopes under acme-corp. If omitted,
    //  matches all scopes within the tenant."
    // Ported 1:1 from cycles-server-admin WebhookRepositoryTest (PR #206) so
    // the runtime dispatch matcher and the admin dispatch/replay matcher are
    // pinned to the same table of (filter, scope, expected) cases.

    private static WebhookSubscription withFilter(String scopeFilter) {
        return WebhookSubscription.builder().scopeFilter(scopeFilter).build();
    }

    @Test
    void matchesScope_nullFilter_matchesScopedAndNullScope() {
        assertTrue(EventEmitterRepository.matchesScope(withFilter(null), "tenant:a/workspace:b"));
        assertTrue(EventEmitterRepository.matchesScope(withFilter(null), null));
    }

    @Test
    void matchesScope_blankFilter_matchesScopedAndNullScope() {
        assertTrue(EventEmitterRepository.matchesScope(withFilter("   "), "tenant:a/workspace:b"));
        assertTrue(EventEmitterRepository.matchesScope(withFilter(""), null));
    }

    @Test
    void matchesScope_bareWildcard_matchesAnyScopedEvent() {
        assertTrue(EventEmitterRepository.matchesScope(withFilter("*"), "tenant:a"));
        assertTrue(EventEmitterRepository.matchesScope(withFilter("*"), "tenant:a/workspace:b/agent:c"));
    }

    @Test
    void matchesScope_bareWildcard_excludesNullScope() {
        assertFalse(EventEmitterRepository.matchesScope(withFilter("*"), null));
    }

    @Test
    void matchesScope_bareWildcard_excludesBlankScope() {
        // A blank scope is treated as unscoped — even the bare "*" wildcard
        // (which requires the event to HAVE a scope) must not match it.
        assertFalse(EventEmitterRepository.matchesScope(withFilter("*"), ""));
        assertFalse(EventEmitterRepository.matchesScope(withFilter("*"), "   "));
    }

    @Test
    void matchesScope_trailingWildcard_matchesChildScopes() {
        assertTrue(EventEmitterRepository.matchesScope(withFilter("tenant:a/*"), "tenant:a/workspace:b"));
        assertTrue(EventEmitterRepository.matchesScope(withFilter("tenant:a/*"), "tenant:a/workspace:b/agent:c"));
    }

    @Test
    void matchesScope_trailingWildcard_excludesExactBaseScope() {
        // Spec: "tenant:acme-corp/*" matches all scopes UNDER acme-corp —
        // children only; the bare base scope itself does not match.
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a/*"), "tenant:a"));
    }

    @Test
    void matchesScope_trailingWildcard_excludesSiblingWithSharedPrefix() {
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a/*"), "tenant:aX"));
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a/*"), "tenant:aX/workspace:b"));
    }

    @Test
    void matchesScope_trailingWildcard_excludesNullScope() {
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a/*"), null));
    }

    @Test
    void matchesScope_trailingWildcard_excludesEmptyChildSegment() {
        // "tenant:a/*" means children UNDER tenant:a — the degenerate scope
        // "tenant:a/" (prefix with nothing after it) is not a child.
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a/*"), "tenant:a/"));
    }

    @Test
    void matchesScope_slashStarFilter_requiresNonEmptyRemainder() {
        assertFalse(EventEmitterRepository.matchesScope(withFilter("/*"), "/"));
        assertTrue(EventEmitterRepository.matchesScope(withFilter("/*"), "/x"));
    }

    @Test
    void matchesScope_exactFilter_matchesOnlyExactScope() {
        assertTrue(EventEmitterRepository.matchesScope(
            withFilter("tenant:a/workspace:b"), "tenant:a/workspace:b"));
        // No wildcard = exact match: child scopes do NOT match.
        assertFalse(EventEmitterRepository.matchesScope(
            withFilter("tenant:a/workspace:b"), "tenant:a/workspace:b/agent:c"));
        assertFalse(EventEmitterRepository.matchesScope(
            withFilter("tenant:a/workspace:b"), "tenant:a/workspace:bX"));
    }

    @Test
    void matchesScope_exactFilter_excludesNullScope() {
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a"), null));
    }

    @Test
    void matchesScope_nonBlankFilter_excludesBlankScope() {
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a"), ""));
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a/*"), ""));
    }

    @Test
    void matchesScope_midStringWildcard_isLiteralNotWildcard() {
        // "*" is only meaningful at the end of the filter; elsewhere it is a
        // literal character in an exact-match comparison.
        assertFalse(EventEmitterRepository.matchesScope(
            withFilter("tenant:*/workspace:b"), "tenant:a/workspace:b"));
        assertTrue(EventEmitterRepository.matchesScope(
            withFilter("tenant:*/workspace:b"), "tenant:*/workspace:b"));
        // Mid-string "*" stays literal even when the filter also ends in "*".
        assertTrue(EventEmitterRepository.matchesScope(
            withFilter("tenant:*/ws:*"), "tenant:*/ws:prod"));
        assertFalse(EventEmitterRepository.matchesScope(
            withFilter("tenant:*/ws:*"), "tenant:a/ws:prod"));
    }

    @Test
    void matchesScope_trailingSlashNoStar_isExactMatchOnly() {
        assertTrue(EventEmitterRepository.matchesScope(withFilter("tenant:a/"), "tenant:a/"));
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a/"), "tenant:a/workspace:b"));
        assertFalse(EventEmitterRepository.matchesScope(withFilter("tenant:a/"), "tenant:a"));
    }

    @Test
    void matchesScope_isCaseSensitive() {
        assertFalse(EventEmitterRepository.matchesScope(
            withFilter("tenant:A/*"), "tenant:a/workspace:b"));
        assertFalse(EventEmitterRepository.matchesScope(
            withFilter("tenant:a"), "Tenant:a"));
    }

    // Dispatch-level pin of the blank-scope refinement: a bare "*" filter must
    // not deliver an event whose scope is blank (previously it did — the
    // empty-prefix startsWith matched "").
    @Test
    void emit_bareWildcardFilter_blankScope_skipped() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1")
                .eventTypes(List.of(EventType.RESERVATION_DENIED))
                .scopeFilter("*")
                .status(WebhookStatus.ACTIVE).build();
        stubPipelineForSubscriptions(Set.of("whsub_1"), Collections.emptySet(),
                Map.of("whsub_1", objectMapper.writeValueAsString(sub)));

        Event event = Event.builder()
                .eventType(EventType.RESERVATION_DENIED)
                .tenantId("t1").scope("")
                .source("cycles-server").timestamp(Instant.now()).build();

        repository.emit(event);

        verify(pipeline, never()).lpush(eq("dispatch:pending"), anyString());
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
