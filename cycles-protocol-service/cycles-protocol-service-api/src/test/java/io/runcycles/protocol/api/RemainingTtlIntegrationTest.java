package io.runcycles.protocol.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * remaining_ttl_ms (spec v0.1.25.16, cycles-protocol#148): server-authoritative
 * remaining reservation lifetime on reserve and extend responses, measured on
 * the same Redis TIME snapshot that computes expires_at. Clients schedule
 * heartbeat extensions from this value, so extend replays MUST recompute it
 * fresh (a same-key retry schedules from the replayed body), while reserve
 * replays return the original body verbatim (evidence-envelope integrity) and
 * therefore carry the ORIGINAL value.
 */
@DisplayName("remaining_ttl_ms Integration Tests")
class RemainingTtlIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("Reserve responses")
    class ReserveResponses {

        @Test
        void freshReserveCarriesRemainingEqualToGrantedTtl() {
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            // reserve.lua sets expires_at = now + ttl on the same TIME snapshot,
            // so the fresh-path remaining is exactly the granted ttl.
            assertThat(((Number) resp.getBody().get("remaining_ttl_ms")).longValue())
                    .isEqualTo(60_000L);
        }

        @Test
        void remainingReflectsTenantCapNotRequestedTtl() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                seedTenant(jedis, TENANT_A, null, null, 10_000L, null);
            }
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("ttl_ms", 86_400_000L); // 24h request, tenant caps at 10s

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            // The silently-capped lease is exactly what remaining_ttl_ms must
            // expose — the case that motivated the field (a delayed first
            // heartbeat outlives the real lease).
            assertThat(((Number) resp.getBody().get("remaining_ttl_ms")).longValue())
                    .isEqualTo(10_000L);
        }

        @Test
        void dryRunResponseOmitsRemaining() {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            body.put("dry_run", true);

            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody()).doesNotContainKey("remaining_ttl_ms");
            assertThat(resp.getBody()).doesNotContainKey("reservation_id");
        }

        @Test
        void idempotentReplayReturnsOriginalRemainingVerbatim() throws Exception {
            Map<String, Object> body = reservationBody(TENANT_A, 1000);
            ResponseEntity<Map> first = post("/v1/reservations", API_KEY_SECRET_A, body);
            assertThat(first.getStatusCode().value()).isEqualTo(200);
            long original = ((Number) first.getBody().get("remaining_ttl_ms")).longValue();

            Thread.sleep(1_200);
            ResponseEntity<Map> replay = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            assertThat(replay.getBody().get("reservation_id"))
                    .isEqualTo(first.getBody().get("reservation_id"));
            // Reserve replays are the ORIGINAL body verbatim (the evidence
            // envelope references it), so remaining reflects the original
            // evaluation — documented in the spec field description.
            assertThat(((Number) replay.getBody().get("remaining_ttl_ms")).longValue())
                    .isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("Extend responses")
    class ExtendResponses {

        @Test
        void freshExtendCarriesRemainingOfNewLease() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_A, extendBody(30_000));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            long remaining = ((Number) resp.getBody().get("remaining_ttl_ms")).longValue();
            // New lease = leftover of the initial 60s + the 30s extension; the
            // request round-trip consumes a little of the initial lease.
            assertThat(remaining).isGreaterThan(80_000L).isLessThanOrEqualTo(90_000L);
        }

        @Test
        void idempotentReplayRecomputesRemainingFresh() throws Exception {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);
            Map<String, Object> body = new HashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("extend_by_ms", 30_000L);

            ResponseEntity<Map> first = post(
                    "/v1/reservations/" + reservationId + "/extend", API_KEY_SECRET_A, body);
            assertThat(first.getStatusCode().value()).isEqualTo(200);
            long expiresAt = ((Number) first.getBody().get("expires_at_ms")).longValue();
            long remaining1 = ((Number) first.getBody().get("remaining_ttl_ms")).longValue();

            Thread.sleep(1_500);
            ResponseEntity<Map> replay = post(
                    "/v1/reservations/" + reservationId + "/extend", API_KEY_SECRET_A, body);

            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            // Replay, not a double-extend: same expiry, extension_count still 1.
            assertThat(((Number) replay.getBody().get("expires_at_ms")).longValue())
                    .isEqualTo(expiresAt);
            try (Jedis jedis = jedisPool.getResource()) {
                assertThat(jedis.hget("reservation:res_" + reservationId, "extension_count"))
                        .isEqualTo("1");
            }
            // But remaining is FRESH: a heartbeat retrying a lost extend with
            // the same key schedules from this value; the cached one is stale
            // by the retry delay and would overshoot the real lease.
            long remaining2 = ((Number) replay.getBody().get("remaining_ttl_ms")).longValue();
            assertThat(remaining2).isLessThanOrEqualTo(remaining1 - 1_000L);
            assertThat(remaining2).isGreaterThan(0L);
        }
    }
}
