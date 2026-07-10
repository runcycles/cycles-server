package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.service.CryptoService;
import io.runcycles.protocol.data.util.LogSanitizer;
import io.runcycles.protocol.model.event.Event;
import io.runcycles.protocol.model.event.EventType;
import io.runcycles.protocol.model.webhook.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import java.time.Instant;
import java.util.*;

/**
 * Combined event persistence + webhook dispatch for the runtime server.
 * Saves events to the same Redis keys as the admin server, finds matching
 * webhook subscriptions, creates PENDING deliveries, and LPUSH to dispatch:pending
 * for cycles-server-events to pick up.
 */
@Repository
public class EventEmitterRepository {

    private static final Logger LOG = LoggerFactory.getLogger(EventEmitterRepository.class);

    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CryptoService cryptoService;

    @org.springframework.beans.factory.annotation.Value("${events.retention.event-ttl-days:90}")
    private int eventTtlDays;

    @org.springframework.beans.factory.annotation.Value("${events.retention.delivery-ttl-days:14}")
    private int deliveryTtlDays;

    /**
     * Save an event and dispatch to matching webhook subscriptions.
     * Non-blocking: all failures are logged, never thrown.
     */
    public void emit(Event event) {
        try {
            if (event.getEventId() == null) {
                event.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            }
            if (event.getTimestamp() == null) {
                event.setTimestamp(Instant.now());
            }
            if (event.getCategory() == null && event.getEventType() != null) {
                event.setCategory(event.getEventType().getCategory());
            }

            try (Jedis jedis = jedisPool.getResource()) {
                String eventJson = objectMapper.writeValueAsString(event);
                long score = event.getTimestamp().toEpochMilli();
                String eventKey = "event:" + event.getEventId();

                // Pipeline: save event + fetch subscription IDs in 1 round-trip
                Pipeline pipe = jedis.pipelined();
                pipe.set(eventKey, eventJson);
                pipe.expire(eventKey, eventTtlDays * 86400L);
                pipe.zadd("events:" + event.getTenantId(), score, event.getEventId());
                pipe.zadd("events:_all", score, event.getEventId());
                var tenantIdsResp = pipe.smembers("webhooks:" + event.getTenantId());
                var systemIdsResp = pipe.smembers("webhooks:__system__");
                pipe.sync();

                // 2. Find matching subscriptions (only if any exist)
                Set<String> allIds = new HashSet<>();
                Set<String> tenantIds = tenantIdsResp.get();
                if (tenantIds != null) allIds.addAll(tenantIds);
                Set<String> systemIds = systemIdsResp.get();
                if (systemIds != null) allIds.addAll(systemIds);

                if (!allIds.isEmpty()) {
                    List<WebhookSubscription> subs = resolveMatchingSubscriptions(jedis, allIds, event);

                    // 3. Create delivery for each matching subscription + LPUSH
                    for (WebhookSubscription sub : subs) {
                        try {
                            createDelivery(jedis, event, sub);
                        } catch (Exception e) {
                            LOG.error("Failed to create webhook delivery: subscription_id={} event_id={} event_type={} tenant={} scope={} request_id={} trace_id={} error={}",
                                    sub.getSubscriptionId(), event.getEventId(), event.getEventType(),
                                    LogSanitizer.sanitize(event.getTenantId()), LogSanitizer.sanitize(event.getScope()), event.getRequestId(),
                                    event.getTraceId(), LogSanitizer.sanitize(e.toString()), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to persist or dispatch event: event_id={} event_type={} tenant={} scope={} request_id={} trace_id={} error={}",
                    event != null ? event.getEventId() : null,
                    event != null ? event.getEventType() : null,
                    event != null ? LogSanitizer.sanitize(event.getTenantId()) : null,
                    event != null ? LogSanitizer.sanitize(event.getScope()) : null,
                    event != null ? event.getRequestId() : null,
                    event != null ? event.getTraceId() : null,
                    LogSanitizer.sanitize(e.toString()), e);
        }
    }

    private List<WebhookSubscription> resolveMatchingSubscriptions(Jedis jedis, Set<String> subIds, Event event) {
        // Pipeline all subscription GETs in 1 round-trip
        List<String> idList = new ArrayList<>(subIds);
        Pipeline pipe = jedis.pipelined();
        List<redis.clients.jedis.Response<String>> responses = new ArrayList<>(idList.size());
        for (String id : idList) {
            responses.add(pipe.get("webhook:" + id));
        }
        pipe.sync();

        List<WebhookSubscription> matching = new ArrayList<>();
        for (int i = 0; i < idList.size(); i++) {
            try {
                String data = responses.get(i).get();
                if (data == null) continue;
                WebhookSubscription sub = objectMapper.readValue(data, WebhookSubscription.class);
                if (sub.getStatus() != WebhookStatus.ACTIVE) continue;
                if (!matchesEventType(sub, event.getEventType())) continue;
                if (!matchesScope(sub, event.getScope())) continue;
                matching.add(sub);
            } catch (Exception e) {
                LOG.warn("Failed to parse webhook subscription: {}", idList.get(i), e);
            }
        }
        return matching;
    }

    private boolean matchesEventType(WebhookSubscription sub, EventType eventType) {
        if (sub.getEventTypes() != null && !sub.getEventTypes().isEmpty()) {
            if (sub.getEventTypes().contains(eventType)) return true;
        }
        if (sub.getEventCategories() != null && !sub.getEventCategories().isEmpty()) {
            if (sub.getEventCategories().contains(eventType.getCategory())) return true;
        }
        return (sub.getEventTypes() == null || sub.getEventTypes().isEmpty())
                && (sub.getEventCategories() == null || sub.getEventCategories().isEmpty());
    }

    /**
     * Spec-conformant scope_filter matcher. The admin OpenAPI description
     * (WebhookCreateRequest/WebhookUpdateRequest {@code scope_filter}) is the
     * authority: <i>"Optional scope pattern to narrow event matching. Supports
     * wildcards: "tenant:acme-corp/*" matches all scopes under acme-corp. If
     * omitted, matches all scopes within the tenant."</i>
     *
     * <p>Semantics (byte-identical to the admin plane's
     * {@code WebhookRepository.matchesScope}, cycles-server-admin PR #206 —
     * the two matchers are pinned to the same table of cases so live runtime
     * dispatch and admin-plane dispatch/replay cannot drift):
     * <ul>
     *   <li>{@code null}/blank filter — matches every event, including events
     *       with a null scope (no restriction).</li>
     *   <li>Bare {@code "*"} — matches every event that <b>has</b> a scope;
     *       null-scope and blank-scope events are excluded.</li>
     *   <li>Filter ending in {@code "*"} (e.g. {@code "tenant:acme-corp/*"}) —
     *       prefix match on the filter minus the trailing {@code "*"}: the
     *       event scope must start with {@code "tenant:acme-corp/"} <b>and</b>
     *       carry a non-empty remainder after the prefix. The bare base scope
     *       {@code "tenant:acme-corp"} does <b>not</b> match, nor does the
     *       degenerate {@code "tenant:acme-corp/"} (empty child segment) — the
     *       spec says "all scopes <b>under</b> acme-corp" (children only).</li>
     *   <li>Filter without a trailing {@code "*"} — <b>exact</b> match only.
     *       Child scopes do not match. Any non-trailing {@code "*"} is a
     *       literal character. Matching is case-sensitive.</li>
     *   <li>Non-blank filter + null or blank event scope — no match: unscoped
     *       events are not delivered to scope-filtered subscriptions (a blank
     *       scope is treated as unscoped).</li>
     * </ul>
     *
     * <p><b>BEHAVIOR CHANGE</b> from the previous runtime implementation,
     * which (a) matched a blank event scope against a bare {@code "*"} filter
     * (empty-prefix startsWith), and (b) matched the degenerate empty-child
     * scope {@code "tenant:a/"} against {@code "tenant:a/*"}. Both now
     * follow the spec's children-only / blank-is-unscoped semantics.
     *
     * <p>Public and static: single scope_filter matcher for the runtime
     * dispatch path, directly pinned by table tests against the admin
     * matcher's cases.
     *
     * @param sub   the subscription whose {@code scope_filter} applies
     * @param scope the event's scope path, may be null/blank (= unscoped)
     */
    public static boolean matchesScope(WebhookSubscription sub, String scope) {
        String filter = sub.getScopeFilter();
        if (filter == null || filter.isBlank()) return true;
        if (scope == null || scope.isBlank()) return false;
        if (filter.equals("*")) return true;
        if (filter.endsWith("*")) {
            String prefix = filter.substring(0, filter.length() - 1);
            return scope.length() > prefix.length() && scope.startsWith(prefix);
        }
        return scope.equals(filter);
    }

    private void createDelivery(Jedis jedis, Event event, WebhookSubscription sub) throws Exception {
        String deliveryId = "del_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        WebhookDelivery delivery = WebhookDelivery.builder()
                .deliveryId(deliveryId)
                .subscriptionId(sub.getSubscriptionId())
                .eventId(event.getEventId())
                .eventType(event.getEventType())
                .status(DeliveryStatus.PENDING)
                .attemptedAt(Instant.now())
                .attempts(0)
                .traceId(event.getTraceId())
                .traceFlags(event.getTraceFlags())
                .traceparentInboundValid(event.getTraceparentInboundValid())
                .build();
        String json = objectMapper.writeValueAsString(delivery);
        String deliveryKey = "delivery:" + deliveryId;
        long score = delivery.getAttemptedAt().toEpochMilli();

        // Pipeline: save delivery + index + dispatch in 1 round-trip
        Pipeline pipe = jedis.pipelined();
        pipe.set(deliveryKey, json);
        pipe.expire(deliveryKey, deliveryTtlDays * 86400L);
        pipe.zadd("deliveries:" + sub.getSubscriptionId(), score, deliveryId);
        pipe.lpush("dispatch:pending", deliveryId);
        pipe.sync();
    }
}
