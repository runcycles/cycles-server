package io.runcycles.protocol.api;

import io.runcycles.protocol.data.service.ReservationExpiryService;
import io.runcycles.protocol.model.auth.ApiKey;
import io.runcycles.protocol.model.auth.ApiKeyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tenant Isolation Integration Tests")
class TenantIsolationIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("Tenant Isolation")
    class TenantIsolation {

        @Test
        void shouldRejectCrossTenantReservationCreate() {
            // Tenant B trying to create reservation for tenant A
            ResponseEntity<Map> resp = post("/v1/reservations", API_KEY_SECRET_B,
                    reservationBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantCommit() {
            // Create reservation as tenant A
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            // Try to commit as tenant B
            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_B, commitBody(800));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantGet() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = get(
                    "/v1/reservations/" + reservationId, API_KEY_SECRET_B);

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantBalanceQuery() {
            ResponseEntity<Map> resp = get(
                    "/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_B);

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectMissingApiKey() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl() + "/v1/reservations",
                    HttpMethod.POST,
                    new HttpEntity<>(reservationBody(TENANT_A, 100), headers),
                    Map.class
            );

            assertThat(resp.getStatusCode().value()).isEqualTo(401);
        }

        @Test
        void shouldRejectCrossTenantRelease() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/release",
                    API_KEY_SECRET_B, releaseBody());

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantExtend() {
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1000);

            ResponseEntity<Map> resp = post(
                    "/v1/reservations/" + reservationId + "/extend",
                    API_KEY_SECRET_B, extendBody(30000));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantEvent() {
            ResponseEntity<Map> resp = post("/v1/events", API_KEY_SECRET_B,
                    eventBody(TENANT_A, 500));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantDecision() {
            ResponseEntity<Map> resp = post("/v1/decide", API_KEY_SECRET_B,
                    decisionBody(TENANT_A, 1000));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectCrossTenantListReservations() {
            createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);

            ResponseEntity<Map> resp = get(
                    "/v1/reservations?tenant=" + TENANT_A, API_KEY_SECRET_B);

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        void shouldRejectInvalidApiKey() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Cycles-API-Key", "cyc_invalid_key_12345");

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl() + "/v1/reservations",
                    HttpMethod.POST,
                    new HttpEntity<>(reservationBody(TENANT_A, 100), headers),
                    Map.class
            );

            assertThat(resp.getStatusCode().value()).isEqualTo(401);
        }

        @Test
        void shouldRejectRevokedApiKey() throws Exception {
            try (Jedis jedis = jedisPool.getResource()) {
                String revokedSecret = "cyc_revoked123456789abc";
                String hash = BCrypt.hashpw(revokedSecret, BCrypt.gensalt());
                String prefix = revokedSecret.substring(0, Math.min(14, revokedSecret.length()));

                ApiKey apiKey = ApiKey.builder()
                        .keyId("key-revoked")
                        .tenantId(TENANT_A)
                        .keyPrefix(prefix)
                        .keyHash(hash)
                        .name("Revoked key")
                        .status(ApiKeyStatus.REVOKED)
                        .permissions(Collections.emptyList())
                        .createdAt(Instant.now())
                        .build();

                jedis.set("apikey:lookup:" + prefix, "key-revoked");
                jedis.set("apikey:key-revoked", objectMapper.writeValueAsString(apiKey));

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-Cycles-API-Key", revokedSecret);

                ResponseEntity<Map> resp = restTemplate.exchange(
                        baseUrl() + "/v1/reservations",
                        HttpMethod.POST,
                        new HttpEntity<>(reservationBody(TENANT_A, 100), headers),
                        Map.class
                );

                assertThat(resp.getStatusCode().value()).isEqualTo(401);
            }
        }
    }
}
