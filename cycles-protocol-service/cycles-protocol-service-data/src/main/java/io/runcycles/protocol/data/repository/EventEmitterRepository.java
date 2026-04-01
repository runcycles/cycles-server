package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.service.CryptoService;
import io.runcycles.protocol.model.event.Event;
import io.runcycles.protocol.model.event.EventType;
import io.runcycles.protocol.model.webhook.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

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
                // 1. Save event (same keys as admin)
                String eventJson = objectMapper.writeValueAsString(event);
                jedis.set("event:" + event.getEventId(), eventJson);
                long score = event.getTimestamp().toEpochMilli();
                jedis.zadd("events:" + event.getTenantId(), score, event.getEventId());
                jedis.zadd("events:_all", score, event.getEventId());

                // 2. Find matching subscriptions
                List<WebhookSubscription> subs = findMatchingSubscriptions(jedis, event);

                // 3. Create delivery for each matching subscription + LPUSH
                for (WebhookSubscription sub : subs) {
                    try {
                        createDelivery(jedis, event, sub);
                    } catch (Exception e) {
                        LOG.error("Failed to create delivery for sub {}: {}", sub.getSubscriptionId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to emit event {}: {}", event.getEventType(), e.getMessage());
        }
    }

    private List<WebhookSubscription> findMatchingSubscriptions(Jedis jedis, Event event) {
        List<WebhookSubscription> matching = new ArrayList<>();
        Set<String> allIds = new HashSet<>();

        // Check tenant-specific subscriptions
        Set<String> tenantIds = jedis.smembers("webhooks:" + event.getTenantId());
        if (tenantIds != null) allIds.addAll(tenantIds);

        // Check system-wide subscriptions
        Set<String> systemIds = jedis.smembers("webhooks:__system__");
        if (systemIds != null) allIds.addAll(systemIds);

        for (String id : allIds) {
            try {
                String data = jedis.get("webhook:" + id);
                if (data == null) continue;
                WebhookSubscription sub = objectMapper.readValue(data, WebhookSubscription.class);
                if (sub.getStatus() != WebhookStatus.ACTIVE) continue;
                if (!matchesEventType(sub, event.getEventType())) continue;
                if (!matchesScope(sub, event.getScope())) continue;
                matching.add(sub);
            } catch (Exception e) {
                LOG.warn("Failed to parse webhook subscription: {}", id, e);
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

    private boolean matchesScope(WebhookSubscription sub, String scope) {
        if (sub.getScopeFilter() == null || sub.getScopeFilter().isBlank()) return true;
        if (scope == null) return false;
        String filter = sub.getScopeFilter();
        if (filter.endsWith("*")) {
            return scope.startsWith(filter.substring(0, filter.length() - 1));
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
                .build();
        String json = objectMapper.writeValueAsString(delivery);
        jedis.set("delivery:" + deliveryId, json);
        long score = delivery.getAttemptedAt().toEpochMilli();
        jedis.zadd("deliveries:" + sub.getSubscriptionId(), score, deliveryId);
        jedis.lpush("dispatch:pending", deliveryId);
    }
}
