package io.runcycles.protocol.api;

import redis.clients.jedis.Jedis;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Frozen Redis-shape transformations for rolling-upgrade compatibility tests.
 *
 * <p>Each method removes current-version fields and leaves only the keys a
 * named legacy writer would have persisted. Keeping these transformations in
 * one place makes the compatibility boundary explicit when storage evolves.</p>
 */
final class LegacyRedisFixtures {

    private LegacyRedisFixtures() {
    }

    static void lifecycleFastBodyOnly(Jedis jedis, String reservationId,
                                      String operation) {
        String reservationKey = reservationKey(reservationId);
        String bodyKey = lifecycleBodyKey(reservationId, operation);
        assertThat(jedis.get(bodyKey)).as("legacy %s fast body", operation).isNotBlank();
        assertThat(jedis.hdel(reservationKey,
                operation + "_response_json",
                operation + "_response_state"))
                .as("current %s response snapshot fields", operation)
                .isEqualTo(2);
        jedis.hdel(reservationKey,
                operation + "_evidence_id",
                operation + "_evidence_url");
    }

    static void lifecycleMappingWithoutOriginalBody(Jedis jedis, String reservationId,
                                                     String operation) {
        lifecycleFastBodyOnly(jedis, reservationId, operation);
        assertThat(jedis.del(lifecycleBodyKey(reservationId, operation))).isEqualTo(1);
    }

    static void eventFastBodyOnly(Jedis jedis, String tenant, String eventId,
                                  String idempotencyKey) {
        String eventKey = "event:evt_" + eventId;
        String mappingKey = "idem:" + tenant + ":event:" + idempotencyKey;
        String snapshot = jedis.hget(eventKey, "event_response_json");
        assertThat(snapshot).as("current event snapshot used as the frozen legacy body")
                .isNotBlank();
        assertThat(jedis.hdel(eventKey, "event_response_json")).isEqualTo(1);
        long mappingTtl = jedis.pttl(mappingKey);
        assertThat(mappingTtl).isPositive();
        jedis.psetex(mappingKey + ":response", mappingTtl, snapshot);
    }

    static void eventMappingWithoutOriginalBody(Jedis jedis, String tenant,
                                                String eventId, String idempotencyKey) {
        String mappingKey = "idem:" + tenant + ":event:" + idempotencyKey;
        assertThat(jedis.get(mappingKey)).isEqualTo(eventId);
        assertThat(jedis.hdel("event:evt_" + eventId, "event_response_json"))
                .isEqualTo(1);
        assertThat(jedis.del(mappingKey + ":response"))
                .as("current event writer does not persist the legacy fast body")
                .isZero();
    }

    static void moveDryRunToLegacyNamespace(Jedis jedis, String tenant,
                                            String idempotencyKey) {
        // Individual rows expire after 24 hours. The reader bridge itself has no
        // calendar removal date because a direct upgrade can occur arbitrarily
        // later; remove it only when direct upgrades from pre-.51 are unsupported.
        String sharedKey = "idem:" + tenant + ":reserve:" + idempotencyKey;
        String legacyKey = "idem:" + tenant + ":dry_run:" + idempotencyKey;
        jedis.rename(sharedKey, legacyKey);
        jedis.rename(sharedKey + ":hash", legacyKey + ":hash");
    }

    static void reservationBeforeCreatedAtIndex(Jedis jedis, String reservationId,
                                                String tenant, long createdAt) {
        jedis.hset(reservationKey(reservationId), Map.ofEntries(
                Map.entry("reservation_id", reservationId),
                Map.entry("tenant", tenant),
                Map.entry("state", "ACTIVE"),
                Map.entry("subject_json", "{\"tenant\":\"" + tenant + "\"}"),
                Map.entry("action_json", "{\"kind\":\"llm.completion\",\"name\":\"legacy\"}"),
                Map.entry("estimate_amount", "100"),
                Map.entry("estimate_unit", "TOKENS"),
                Map.entry("scope_path", "tenant:" + tenant),
                Map.entry("affected_scopes", "[\"tenant:" + tenant + "\"]"),
                Map.entry("created_at", String.valueOf(createdAt)),
                Map.entry("expires_at", String.valueOf(createdAt + 60_000L))));
    }

    private static String reservationKey(String reservationId) {
        return "reservation:res_" + reservationId;
    }

    private static String lifecycleBodyKey(String reservationId, String operation) {
        return operation + ":body:" + reservationId;
    }
}
