package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Response;
import redis.clients.jedis.params.SetParams;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RedisReservationRepository - CRUD")
class RedisReservationCrudTest extends BaseRedisReservationRepositoryTest {

    // ---- findReservationTenantById ----

    @Nested
    @DisplayName("findReservationTenantById")
    class FindReservationTenantById {

        @Test
        void shouldReturnTenantForExistingReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hget("reservation:res_abc123", "tenant")).thenReturn("acme-corp");

            String tenant = repository.findReservationTenantById("abc123");

            assertThat(tenant).isEqualTo("acme-corp");
        }

        @Test
        void shouldThrowNotFoundWhenTenantNull() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hget("reservation:res_unknown", "tenant")).thenReturn(null);

            assertThatThrownBy(() -> repository.findReservationTenantById("unknown"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldWrapRedisExceptionAsRuntime() {
            when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection failed"));

            assertThatThrownBy(() -> repository.findReservationTenantById("any"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to resolve reservation tenant");
        }
    }

    // ---- getReservationById ----

    @Nested
    @DisplayName("getReservationById")
    class GetReservationById {

        @Test
        void shouldThrowNotFoundWhenEmpty() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hgetAll("reservation:res_missing")).thenReturn(Map.of());

            assertThatThrownBy(() -> repository.getReservationById("missing"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldThrowNotFoundWhenNull() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(jedis.hgetAll("reservation:res_gone")).thenReturn(null);

            assertThatThrownBy(() -> repository.getReservationById("gone"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.NOT_FOUND);
        }

        @Test
        void shouldReturnDetailForValidReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res123", "ACTIVE");
            when(jedis.hgetAll("reservation:res_res123")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res123");

            assertThat(detail.getReservationId()).isEqualTo("res123");
            assertThat(detail.getStatus()).isEqualTo(Enums.ReservationStatus.ACTIVE);
            assertThat(detail.getReserved().getAmount()).isEqualTo(5000L);
        }
    }

    // ---- getReservationById additional cases ----

    @Nested
    @DisplayName("getReservationById additional cases")
    class GetReservationByIdAdditional {

        @Test
        void shouldParseCommittedReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res-committed", "COMMITTED");
            fields.put("charged_amount", "3000");
            fields.put("committed_at", "1700001000000");
            when(jedis.hgetAll("reservation:res_res-committed")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res-committed");

            assertThat(detail.getStatus()).isEqualTo(Enums.ReservationStatus.COMMITTED);
            assertThat(detail.getCommitted()).isNotNull();
            assertThat(detail.getCommitted().getAmount()).isEqualTo(3000L);
            assertThat(detail.getFinalizedAtMs()).isEqualTo(1700001000000L);
        }

        @Test
        void shouldParseReleasedReservation() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res-released", "RELEASED");
            fields.put("released_at", "1700002000000");
            when(jedis.hgetAll("reservation:res_res-released")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res-released");

            assertThat(detail.getStatus()).isEqualTo(Enums.ReservationStatus.RELEASED);
            assertThat(detail.getFinalizedAtMs()).isEqualTo(1700002000000L);
        }

        @Test
        void shouldParseMetadata() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res-meta", "ACTIVE");
            fields.put("metadata_json", "{\"model\":\"gpt-4\",\"temperature\":0.7}");
            when(jedis.hgetAll("reservation:res_res-meta")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res-meta");

            assertThat(detail.getMetadata()).isNotNull();
            assertThat(detail.getMetadata()).containsEntry("model", "gpt-4");
        }

        @Test
        void shouldParseCommittedMetadata() {
            // commit.lua persists the COMMIT request's metadata as
            // committed_metadata_json; getReservation must surface it as
            // committed_metadata (cycles-server#197), distinct from reserve metadata.
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res-cmeta", "COMMITTED");
            fields.put("metadata_json", "{\"phase\":\"reserve\"}");
            fields.put("committed_metadata_json", "{\"request_id\":\"req-abc-123\"}");
            when(jedis.hgetAll("reservation:res_res-cmeta")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res-cmeta");

            assertThat(detail.getCommittedMetadata()).isNotNull();
            assertThat(detail.getCommittedMetadata()).containsEntry("request_id", "req-abc-123");
            // reserve-time metadata stays distinct
            assertThat(detail.getMetadata()).containsEntry("phase", "reserve");
        }

        @Test
        void shouldOmitCommittedMetadataWhenAbsent() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("res-nocmeta", "ACTIVE");
            when(jedis.hgetAll("reservation:res_res-nocmeta")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("res-nocmeta");

            assertThat(detail.getCommittedMetadata()).isNull();
        }

        @Test
        void shouldThrowOnCorruptedData() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = new HashMap<>();
            fields.put("reservation_id", "res-corrupt");
            fields.put("state", "ACTIVE");
            // Missing required fields: estimate_unit, estimate_amount, subject_json, etc.
            when(jedis.hgetAll("reservation:res_res-corrupt")).thenReturn(fields);

            assertThatThrownBy(() -> repository.getReservationById("res-corrupt"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // ---- getReservationById with extended fields ----

    @Nested
    @DisplayName("getReservationById with extended fields")
    class GetReservationByIdExtendedFields {

        @Test
        void shouldParseChargedAmountAndCommittedAt() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("committed-res", "COMMITTED");
            fields.put("charged_amount", "3000");
            fields.put("committed_at", "1700050000000");
            when(jedis.hgetAll("reservation:res_committed-res")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("committed-res");

            assertThat(detail.getCommitted()).isNotNull();
            assertThat(detail.getCommitted().getAmount()).isEqualTo(3000L);
            assertThat(detail.getFinalizedAtMs()).isEqualTo(1700050000000L);
        }

        @Test
        void shouldParseReleasedAt() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("released-res", "RELEASED");
            fields.put("released_at", "1700055000000");
            when(jedis.hgetAll("reservation:res_released-res")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("released-res");

            assertThat(detail.getFinalizedAtMs()).isEqualTo(1700055000000L);
        }

        @Test
        void shouldParseMetadataJson() {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            Map<String, String> fields = reservationFields("meta-res", "ACTIVE");
            fields.put("metadata_json", "{\"env\":\"production\",\"team\":\"backend\"}");
            when(jedis.hgetAll("reservation:res_meta-res")).thenReturn(fields);

            ReservationDetail detail = repository.getReservationById("meta-res");

            assertThat(detail.getMetadata()).containsEntry("env", "production");
            assertThat(detail.getMetadata()).containsEntry("team", "backend");
        }
    }

    // ---- createReservation ----

    @Nested
    @DisplayName("createReservation")
    class CreateReservation {

        @Test
        void shouldCreateReservationSuccessfully() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "new-res-id", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-1");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            assertThat(response.getReservationId()).isNotNull();
            assertThat(response.getExpiresAtMs()).isEqualTo(9999999L);
            assertThat(response.getAffectedScopes()).isEqualTo(defaultScopes());
        }

        @Test
        void shouldReturnAllowWithCapsWhenCapsPresent() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-caps");
            luaMap.put("expires_at", 8888888L);
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme/app:myapp", "remaining", 5000, "reserved", 0, "spent", 5000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json"))
                    .thenReturn("{\"max_tokens\":100}");

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-caps");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW_WITH_CAPS);
            assertThat(response.getCaps()).isNotNull();
            assertThat(response.getCaps().getMaxTokens()).isEqualTo(100);
        }

        @Test
        void shouldThrowOnScriptError() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("error", "BUDGET_EXCEEDED", "message", "over limit"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-err");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.BUDGET_EXCEEDED);
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldHandleIdempotencyHit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            // Idempotency hit: response has reservation_id but no expires_at
            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "existing-res"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            ReservationCreateResponse original = ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .reservationId("existing-res")
                    .affectedScopes(List.of("tenant:acme"))
                    .reserved(defaultEstimate())
                    .balances(List.of())
                    .build();
            when(jedis.get("reserve:body:existing-res"))
                    .thenReturn(objectMapper.writeValueAsString(original));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-dup");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            assertThat(response.getReservationId()).isEqualTo("existing-res");
        }

        @Test
        void shouldReplayCachedOriginalResponseVerbatimOnIdempotencyHit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            // Lua signals an idempotency hit (reservation_id, no expires_at)
            String luaResponse = objectMapper.writeValueAsString(Map.of("reservation_id", "orig-res"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                    .thenReturn(luaResponse);
            // The FULL original response was cached at first create — return it verbatim.
            ReservationCreateResponse original = ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .reservationId("orig-res")
                    .affectedScopes(List.of("tenant:acme"))
                    .reserved(new Amount(Enums.UnitEnum.TOKENS, 1000L))
                    .balances(List.of()) // ORIGINAL (snapshot) balances
                    .cyclesEvidence(CyclesEvidenceRef.builder()
                            .evidenceId("c".repeat(64))
                            .cyclesEvidenceUrl("https://cycles.example.com/v1/evidence/" + "c".repeat(64))
                            .build())
                    .build();
            // body is cached keyed by RESERVATION_ID (the Lua replay returns orig-res)
            when(jedis.get("reserve:body:orig-res"))
                    .thenReturn(objectMapper.writeValueAsString(original));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-hit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            // returned the ORIGINAL body verbatim (including the original cycles_evidence)
            assertThat(response.getReservationId()).isEqualTo("orig-res");
            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo("c".repeat(64));
            // did NOT rebuild from the reservation hash (cache hit short-circuits)
            verify(jedis, never()).hgetAll("reservation:res_orig-res");
        }

        @Test
        void shouldWaitForCachedOriginalResponseOnIdempotencyHit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(Map.of("reservation_id", "orig-res"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                    .thenReturn(luaResponse);

            ReservationCreateResponse original = ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .reservationId("orig-res")
                    .affectedScopes(List.of("tenant:acme"))
                    .reserved(new Amount(Enums.UnitEnum.TOKENS, 1000L))
                    .cyclesEvidence(CyclesEvidenceRef.builder()
                            .evidenceId("c".repeat(64))
                            .cyclesEvidenceUrl("https://cycles.example.com/v1/evidence/" + "c".repeat(64))
                            .build())
                    .build();
            when(jedis.get("reserve:body:orig-res"))
                    .thenReturn(null, objectMapper.writeValueAsString(original));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-hit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getReservationId()).isEqualTo("orig-res");
            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo("c".repeat(64));
            verify(jedis, times(2)).get("reserve:body:orig-res");
            org.mockito.InOrder order = inOrder(jedis);
            order.verify(jedis).close();
            order.verify(jedis).get("reserve:body:orig-res");
            verify(jedis, never()).hgetAll("reservation:res_orig-res");
        }

        @Test
        void shouldFailRetriablyWhenOriginalReserveResponseIsUnavailable() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(Map.of("reservation_id", "orig-res")));
            when(jedis.get("reserve:body:orig-res")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-hit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                    .hasMessageContaining("Original idempotency response");
            verify(jedis, never()).hgetAll("reservation:res_orig-res");
        }

        @Test
        void reserveReplayRepairsMissingBodyCacheFromMutationSnapshot() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("reservation_id", "orig-res");
            snapshot.put("expires_at", 9_999_999L);
            snapshot.put("estimate_amount", 1000L);
            snapshot.put("estimate_unit", "TOKENS");
            snapshot.put("scope_path", "tenant:acme");
            snapshot.put("affected_scopes", List.of("tenant:acme"));
            snapshot.put("balances", List.of());
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(Map.of(
                        "reservation_id", "orig-res", "response_snapshot", snapshotJson,
                        "response_cache_ttl_ms", 172_805_000L)));
            when(jedis.get("reserve:body:orig-res")).thenReturn(null);
            when(jedis.hmget("reservation:res_orig-res", "reserve_response_json",
                    "reserve_response_state", "reserve_evidence_id", "reserve_evidence_url"))
                    .thenReturn(List.of(snapshotJson, "EVIDENCE", "e".repeat(64),
                        "https://cycles.example.com/v1/evidence/" + "e".repeat(64)));
            when(jedis.set(eq("reserve:body:orig-res"), anyString(), any(SetParams.class)))
                .thenThrow(new RuntimeException("repair cache unavailable"));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-hit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getReserved().getAmount()).isEqualTo(1000L);
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo("e".repeat(64));
            verify(jedis).set(eq("reserve:body:orig-res"), anyString(),
                eq(SetParams.setParams().nx().px(172_805_000L)));
        }

        @Test
        void invalidReserveResponseStateFailsReplayClosed() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("reservation_id", "bad-snapshot");
            snapshot.put("expires_at", 9_999_999L);
            snapshot.put("estimate_amount", 1000L);
            snapshot.put("estimate_unit", "TOKENS");
            snapshot.put("scope_path", "tenant:acme");
            snapshot.put("affected_scopes", List.of("tenant:acme"));
            snapshot.put("balances", List.of());
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                .thenReturn(objectMapper.writeValueAsString(Map.of(
                    "reservation_id", "bad-snapshot", "response_snapshot", snapshotJson)));
            when(jedis.hmget("reservation:res_bad-snapshot", "reserve_response_json",
                "reserve_response_state", "reserve_evidence_id", "reserve_evidence_url"))
                .thenReturn(Arrays.asList(snapshotJson, "CORRUPT", null, null));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("bad-snapshot-key");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasMessageContaining("Original idempotency response");
        }

        @Test
        void reserveReplayFinalizesPendingSnapshotAsBase() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("reservation_id", "pending-res");
            snapshot.put("expires_at", 9_999_999L);
            snapshot.put("estimate_amount", "100000000000001");
            snapshot.put("estimate_unit", "TOKENS");
            snapshot.put("scope_path", "tenant:acme");
            snapshot.put("affected_scopes", List.of("tenant:acme"));
            snapshot.put("balances", List.of());
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                .thenReturn(objectMapper.writeValueAsString(Map.of(
                    "reservation_id", "pending-res", "response_snapshot", snapshotJson)));
            when(jedis.hmget("reservation:res_pending-res", "reserve_response_json",
                "reserve_response_state", "reserve_evidence_id", "reserve_evidence_url"))
                .thenReturn(Arrays.asList(snapshotJson, "PENDING", null, null));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("pending-key");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getReserved().getAmount()).isEqualTo(100_000_000_000_001L);
            assertThat(response.getCyclesEvidence()).isNull();
            verify(jedis).eval(contains("'BASE'"),
                eq(List.of("reserve:body:pending-res", "reservation:res_pending-res")), anyList());
        }

        @Test
        void freshReserveStampsEvidenceAndCachesBodyWithIdempotencyTtl() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            String luaResponse = objectMapper.writeValueAsString(
                    Map.of("reservation_id", "fresh-res", "expires_at", 9999999L));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                    .thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);
            // identity configured → prepared evidence is cached/linked/queued atomically
            String evId = "d".repeat(64);
            String url = "https://cycles.example.com/v1/evidence/" + evId;
            when(evidenceEmitter.prepare(eq("reserve"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                        new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(evId, url), "{\"evidence_id\":\"" + evId + "\"}"));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-fresh");
            request.setTtlMs(30000L); // ttl + 5s grace < 24h → TTL floors at 24h
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme", "trace-1");

            // evidence stamped on the response
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo(evId);
            assertThat(response.getCyclesEvidence().getCyclesEvidenceUrl()).isEqualTo(url);
            // full body cached keyed by reservation_id (a generated UUID), TTL >= 24h floor
            verify(jedis).eval(contains("LPUSH"),
                    eq(List.of("reserve:body:fresh-res", "reservation:res_fresh-res", "evidence:pending")),
                    argThat(args -> args.stream().anyMatch(v -> v.contains("\"evidence_id\":\"" + evId + "\""))));
            // pool-nesting guard: the Lua connection is released before evidence emit
            org.mockito.InOrder ordered = inOrder(jedis, evidenceEmitter);
            ordered.verify(jedis).close();
            ordered.verify(evidenceEmitter).prepare(eq("reserve"), anyLong(), any(), any());
        }

        @Test
        void freshReserveReturnsUnstampedSnapshotResponseWhenAtomicEvidenceWriteIsRejected() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(
                        Map.of("reservation_id", "fresh-res", "expires_at", 9_999_999L)));
            String evId = "5".repeat(64);
            when(evidenceEmitter.prepare(eq("reserve"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                        new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(
                            evId, "https://cycles.example.com/v1/evidence/" + evId), "{}"));
            when(jedis.eval(contains("local rt"), anyList(), anyList())).thenReturn(-1L);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-atomic-fail");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme", "trace-r");

            assertThat(response.getCyclesEvidence()).isNull();
            verify(metrics).recordEvidenceEmitFailed("reserve");
        }

        @Test
        void replayBaseFinalizationCanWinWithoutReportingEvidenceFailure() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                .thenReturn(objectMapper.writeValueAsString(
                    Map.of("reservation_id", "fresh-res", "expires_at", 9_999_999L)));
            String evId = "6".repeat(64);
            when(evidenceEmitter.prepare(eq("reserve"), anyLong(), any(), any()))
                .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                    new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(
                        evId, "https://cycles.example.com/v1/evidence/" + evId), "{}"));
            // A concurrent replay repaired PENDING as BASE before evidence finalized.
            when(jedis.eval(contains("local rt"), anyList(), anyList())).thenReturn(0L);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-base-won");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme", "trace-r");

            assertThat(response.getCyclesEvidence()).isNull();
            verify(metrics, never()).recordEvidenceEmitFailed("reserve");
            verify(jedis).eval(contains("HGET"),
                eq(List.of("reserve:body:fresh-res", "reservation:res_fresh-res")), anyList());
        }

        @Test
        void freshBaseResponseSurvivesFinalizationWriteFailure() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                .thenReturn(objectMapper.writeValueAsString(
                    Map.of("reservation_id", "fresh-base", "expires_at", 9_999_999L)));
            when(jedis.eval(contains("HGET"), anyList(), anyList()))
                .thenThrow(new RuntimeException("finalization unavailable"));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("base-write-fail");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getReservationId()).isEqualTo("fresh-base");
            assertThat(response.getCyclesEvidence()).isNull();
        }

        @Test
        void freshReserveCachesBodyWithExtendedTtlForLongLivedReservation() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            // tenant permits a 48h reservation (default max is only 1h)
            when(jedis.get("tenant:acme-long"))
                    .thenReturn("{\"max_reservation_ttl_ms\":172800000}");
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class)))
                    .thenReturn(objectMapper.writeValueAsString(
                            Map.of("reservation_id", "long-res", "expires_at", 9999999L)));
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-long");
            request.setTtlMs(172800000L);       // 48h reservation
            request.setGracePeriodMs(5000L);
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            repository.createReservation(request, "acme-long", null);

            // body TTL must match the Lua mapping max(ttl+grace, 24h) = 48h+5s, NOT a fixed 24h
            // (the bug finding 1 fixed: previously hard-coded 86400000)
            verify(jedis).eval(contains("HGET"),
                eq(List.of("reserve:body:long-res", "reservation:res_long-res")),
                argThat(args -> args.get(0).equals(String.valueOf(172800000L + 5000L))));
        }
    }

    // ---- createReservation dry_run ----

    @Nested
    @DisplayName("createReservation dry_run")
    class CreateReservationDryRun {

        @Test
        void shouldAllowDryRunWhenBudgetSufficient() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-1");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            assertThat(response.getReservationId()).isNull();
            assertThat(response.getBalances()).isNotEmpty();
        }

        @Test
        void shouldDenyDryRunWhenBudgetExceeded() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> lowBudget = budgetMap(10000, 100, 0, 9900);
            mockBudget("budget:tenant:acme:USD_MICROCENTS", lowBudget);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-exceed");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_EXCEEDED);
        }

        @Test
        void shouldDenyDryRunWhenBudgetFrozen() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> frozen = budgetMap(10000, 8000, 0, 2000);
            frozen.put("status", "FROZEN");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", frozen);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-frozen");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_FROZEN);
        }

        @Test
        void shouldDenyDryRunWhenNoBudgetFound() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            // Pipeline default already returns empty map — no budget mocking needed

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-none");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.BUDGET_NOT_FOUND);
        }

        @Test
        void shouldDenyDryRunWhenDebtOutstanding() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> debtBudget = budgetMap(10000, 8000, 0, 2000);
            debtBudget.put("debt", "500");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", debtBudget);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-debt");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.DEBT_OUTSTANDING);
        }

        @Test
        void shouldAllowDryRunWhenDebtWithinOverdraftLimit() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> debtBudget = budgetMap(10000, 8000, 0, 2000);
            debtBudget.put("debt", "500");
            debtBudget.put("overdraft_limit", "1000");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", debtBudget);
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", debtBudget);
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-debt-within-limit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }

        @Test
        void shouldDenyDryRunWhenOverdraftLimitExceeded() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> overlimit = budgetMap(10000, 8000, 0, 2000);
            overlimit.put("is_over_limit", "true");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", overlimit);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-overlimit");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.OVERDRAFT_LIMIT_EXCEEDED);
        }
    }

    // ---- createReservation dry_run additional ----

    @Nested
    @DisplayName("createReservation dry_run additional")
    class CreateReservationDryRunAdditional {

        @Test
        void shouldReplayIdempotencyCacheOnDryRun() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            ReservationCreateResponse cached = ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .affectedScopes(defaultScopes())
                    .scopePath("tenant:acme/app:myapp")
                    .build();
            // Mock pipeline.get() for idempotency check (pipelined in evaluateDryRun)
            Response<String> cachedResp = mock(Response.class);
            when(cachedResp.get()).thenReturn(objectMapper.writeValueAsString(cached));
            when(pipeline.get("idem:acme:dry_run:dry-cached")).thenReturn(cachedResp);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-cached");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            // Should not scan budgets since cached
            verify(jedis, never()).hgetAll(startsWith("budget:"));
        }

        @Test
        void shouldThrowIdempotencyMismatchOnDryRunCacheReplay() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            // Mock pipeline.get() for idempotency check (pipelined in evaluateDryRun)
            Response<String> cachedResp = mock(Response.class);
            when(cachedResp.get()).thenReturn("{\"decision\":\"ALLOW\"}");
            when(pipeline.get("idem:acme:dry_run:dry-mismatch")).thenReturn(cachedResp);
            Response<String> hashResp = mock(Response.class);
            when(hashResp.get()).thenReturn("stale-hash-value");
            when(pipeline.get("idem:acme:dry_run:dry-mismatch:hash")).thenReturn(hashResp);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-mismatch");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.IDEMPOTENCY_MISMATCH);
        }

        @Test
        void shouldWaitForPendingDryRunIdempotencyResultAndReplayWithoutReemitting() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-pending");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);
            String payloadHash = invokeComputePayloadHash(request);

            ReservationCreateResponse cached = ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .affectedScopes(defaultScopes())
                    .scopePath("tenant:acme/app:myapp")
                    .cyclesEvidence(CyclesEvidenceRef.builder()
                            .evidenceId("e".repeat(64))
                            .cyclesEvidenceUrl("https://cycles.example.com/v1/evidence/" + "e".repeat(64))
                            .build())
                    .build();

            Response<String> pendingResp = mock(Response.class);
            when(pendingResp.get()).thenReturn("__dry_run_pending__:" + payloadHash);
            when(pipeline.get("idem:acme:dry_run:dry-pending")).thenReturn(pendingResp);
            when(jedis.get("idem:acme:dry_run:dry-pending"))
                    .thenReturn(objectMapper.writeValueAsString(cached));
            when(jedis.get("idem:acme:dry_run:dry-pending:hash")).thenReturn(payloadHash);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.isIdempotentReplay()).isTrue();
            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo("e".repeat(64));
            org.mockito.InOrder order = inOrder(jedis);
            order.verify(jedis).close();
            order.verify(jedis).get("idem:acme:dry_run:dry-pending");
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
            verify(pipeline, never()).hgetAll(startsWith("budget:"));
            verify(pipeline, never()).psetex(eq("idem:acme:dry_run:dry-pending"), eq(86400000L), anyString());
        }

        @Test
        void shouldReturnInternalErrorWhenPendingDryRunDoesNotPublishInShortWait() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-still-pending");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);
            String payloadHash = invokeComputePayloadHash(request);

            Response<String> pendingResp = mock(Response.class);
            when(pendingResp.get()).thenReturn("__dry_run_pending__:" + payloadHash);
            when(pipeline.get("idem:acme:dry_run:dry-still-pending")).thenReturn(pendingResp);
            when(jedis.get("idem:acme:dry_run:dry-still-pending"))
                    .thenReturn("__dry_run_pending__:" + payloadHash);

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                    .hasFieldOrPropertyWithValue("httpStatus", 500);
        }

        @Test
        void shouldReturnAllowWithCapsOnDryRun() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockCaps("budget:tenant:acme/app:myapp:USD_MICROCENTS", "{\"max_tokens\":200,\"max_steps_remaining\":5}");

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-caps");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW_WITH_CAPS);
            assertThat(response.getCaps().getMaxTokens()).isEqualTo(200);
            assertThat(response.getCaps().getMaxStepsRemaining()).isEqualTo(5);
        }

        @Test
        void shouldCacheIdempotencyResultOnDryRun() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-store");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            repository.createReservation(request, "acme");

            verify(jedis).set(eq("idem:acme:dry_run:dry-store"), startsWith("__dry_run_pending__:"),
                    any(SetParams.class));
            verify(jedis).eval(contains("PSETEX"),
                    eq(List.of("idem:acme:dry_run:dry-store", "idem:acme:dry_run:dry-store:hash", "evidence:pending")),
                    anyList());
        }

        @Test
        void dryRunWithoutIdempotencyKeyEmitsDirectlyWithoutCaching() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            String evId = "7".repeat(64);
            when(evidenceEmitter.emit(eq("reserve"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(
                        evId, "https://cycles.example.com/v1/evidence/" + evId));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            ReservationCreateResponse response = repository.createReservation(request, "acme", "trace-r");

            assertThat(response.getCyclesEvidence().getEvidenceId()).isEqualTo(evId);
            verify(evidenceEmitter, never()).prepare(anyString(), anyLong(), any(), any());
            verify(jedis, never()).eval(contains("PSETEX"), anyList(), anyList());
        }

        @Test
        void shouldClearDryRunPendingClaimWhenCacheWriteFails() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);
            when(evidenceEmitter.prepare(eq("reserve"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.PreparedEvidence(
                        new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(
                            "a".repeat(64), "https://cycles.example.com/v1/evidence/" + "a".repeat(64)), "{}"));
            when(jedis.eval(contains("PSETEX"), anyList(), anyList()))
                    .thenThrow(new RuntimeException("cache down"));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-cache-fail");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                    .hasMessageContaining("persist idempotency response");

            org.mockito.ArgumentCaptor<String> markerCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(jedis).set(eq("idem:acme:dry_run:dry-cache-fail"), markerCaptor.capture(),
                    any(SetParams.class));
            verify(jedis).eval(contains("redis.call('GET'"),
                    eq(List.of("idem:acme:dry_run:dry-cache-fail")),
                    eq(List.of(markerCaptor.getValue())));
            verify(evidenceEmitter, times(1)).prepare(eq("reserve"), anyLong(), any(), any());
            verify(evidenceEmitter, never()).emit(anyString(), anyLong(), any(), any());
            verify(metrics).recordEvidenceEmitFailed("reserve");
        }

        @Test
        void shouldClearDryRunPendingClaimWhenEvaluationFails() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> invalidBudget = budgetMap(10000, 8000, 0, 2000);
            invalidBudget.put("remaining", "not-a-number");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", invalidBudget);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-eval-fail");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(RuntimeException.class)
                    .hasRootCauseInstanceOf(NumberFormatException.class);

            org.mockito.ArgumentCaptor<String> markerCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(jedis).set(eq("idem:acme:dry_run:dry-eval-fail"), markerCaptor.capture(),
                    any(SetParams.class));
            verify(jedis).eval(contains("redis.call('GET'"),
                    eq(List.of("idem:acme:dry_run:dry-eval-fail")),
                    eq(List.of(markerCaptor.getValue())));
            // Pool-nesting guard: the eval-failure clear runs on the already-held evaluation
            // connection, so a failing dry-run checks out exactly ONE pooled connection. If the
            // clear self-acquired a second connection while the eval one is still held, this would
            // be 2 — the starvation pattern this PR avoids elsewhere.
            verify(jedisPool, times(1)).getResource();
        }

        @Test
        void dryRunEvalFailureClearSwallowsClearErrorAndPropagatesOriginal() throws Exception {
            // Fail-open: if releasing the claim ALSO fails (the compare-and-delete eval throws),
            // the clear error is swallowed and the ORIGINAL evaluation error still propagates —
            // a leaked claim self-expires via its PX TTL.
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            Map<String, String> invalidBudget = budgetMap(10000, 8000, 0, 2000);
            invalidBudget.put("remaining", "not-a-number");
            mockBudget("budget:tenant:acme:USD_MICROCENTS", invalidBudget);
            when(jedis.eval(contains("redis.call('GET'"), anyList(), anyList()))
                    .thenThrow(new RuntimeException("clear failed"));

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-eval-and-clear-fail");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(RuntimeException.class)
                    .hasRootCauseInstanceOf(NumberFormatException.class);
            verify(jedis).eval(contains("redis.call('GET'"), anyList(), anyList());
        }

        @Test
        void dryRunCacheFailureReturnsRetriableErrorWhenPoolUnavailable() throws Exception {
            // A computed response that cannot be persisted cannot satisfy the original-response
            // replay contract. Claim cleanup remains fail-open, but the request itself is retriable.
            when(jedisPool.getResource()).thenReturn(jedis).thenThrow(new RuntimeException("pool down"));
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());
            mockBudget("budget:tenant:acme:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            mockBudget("budget:tenant:acme/app:myapp:USD_MICROCENTS", budgetMap(10000, 8000, 0, 2000));
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("dry-pool-down");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setDryRun(true);

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                    .hasMessageContaining("persist idempotency response");
        }
    }

    // ---- createReservation null defaults ----

    @Nested
    @DisplayName("createReservation null defaults")
    class CreateReservationNullDefaults {

        @Test
        void shouldUseDefaultOveragePolicyWhenNull() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-def");
            luaMap.put("expires_at", 9999999L);
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 5000, "reserved", 0, "spent", 5000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-def");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setOveragePolicy(null);  // null -> defaults to REJECT
            request.setTtlMs(null);          // null -> defaults to 60000
            request.setGracePeriodMs(null);   // null -> defaults to 5000
            request.setMetadata(null);        // null -> empty string

            ReservationCreateResponse response = repository.createReservation(request, "acme");
            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }

        @Test
        void shouldUseExplicitOveragePolicyWhenProvided() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> luaMap = new LinkedHashMap<>();
            luaMap.put("reservation_id", "res-pol");
            luaMap.put("expires_at", 9999999L);
            luaMap.put("balances", List.of(Map.of("scope", "tenant:acme", "remaining", 5000, "reserved", 0, "spent", 5000, "allocated", 10000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)));
            String luaResponse = objectMapper.writeValueAsString(luaMap);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            lenient().when(jedis.hget(anyString(), eq("caps_json"))).thenReturn(null);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-pol");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());
            request.setOveragePolicy(Enums.CommitOveragePolicy.ALLOW_WITH_OVERDRAFT);
            request.setTtlMs(30000L);
            request.setGracePeriodMs(10000L);
            request.setMetadata(Map.of("key", "value"));

            ReservationCreateResponse response = repository.createReservation(request, "acme");
            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
        }
    }

    // ---- createReservation idempotency edge cases ----

    @Nested
    @DisplayName("createReservation idempotency edge cases")
    class CreateReservationIdempotencyEdgeCases {

        @Test
        void shouldNotReconstructReplayWhenOriginalBodyIsMissingAndHashIsEmpty() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            // Idempotency hit: response has reservation_id but no expires_at
            String luaResponse = objectMapper.writeValueAsString(
                Map.of("reservation_id", "vanished-res"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-vanished");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                .hasMessageContaining("Original idempotency response");
            verify(jedis, never()).hgetAll("reservation:res_vanished-res");
        }

        @Test
        void shouldNotReconstructReplayWhenOriginalBodyIsMissingAndHashIsNull() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            String luaResponse = objectMapper.writeValueAsString(
                Map.of("reservation_id", "null-res"));
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);
            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setIdempotencyKey("idem-null");
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            assertThatThrownBy(() -> repository.createReservation(request, "acme"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                .hasMessageContaining("Original idempotency response");
            verify(jedis, never()).hgetAll("reservation:res_null-res");
        }
    }

    // ---- createReservation ALLOW_WITH_CAPS ----

    @Nested
    @DisplayName("createReservation ALLOW_WITH_CAPS")
    class CreateReservationWithCaps {

        @Test
        void shouldReturnAllowWithCapsWhenCapsJsonPresent() throws Exception {
            when(jedisPool.getResource()).thenReturn(jedis);
            doNothing().when(jedis).close();
            when(scopeService.deriveScopes(any())).thenReturn(defaultScopes());

            Map<String, Object> luaResult = new LinkedHashMap<>();
            luaResult.put("reservation_id", "res-caps");
            luaResult.put("expires_at", 1700060000000L);
            luaResult.put("balances", List.of(
                Map.of("scope", "tenant:acme", "remaining", 80000, "reserved", 10000, "spent", 10000, "allocated", 100000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false),
                Map.of("scope", "tenant:acme/app:myapp", "remaining", 40000, "reserved", 5000, "spent", 5000, "allocated", 50000, "debt", 0, "overdraft_limit", 0, "is_over_limit", false)
            ));
            String luaResponse = objectMapper.writeValueAsString(luaResult);
            when(luaScripts.eval(eq(jedis), eq("reserve"), eq("RESERVE_SCRIPT"), any(String[].class))).thenReturn(luaResponse);

            // Budget with caps_json
            String capsJson = "{\"max_tokens\":1000}";
            lenient().when(jedis.hget("budget:tenant:acme/app:myapp:USD_MICROCENTS", "caps_json")).thenReturn(capsJson);

            ReservationCreateRequest request = new ReservationCreateRequest();
            request.setSubject(defaultSubject());
            request.setAction(defaultAction());
            request.setEstimate(defaultEstimate());

            ReservationCreateResponse response = repository.createReservation(request, "acme");

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW_WITH_CAPS);
            assertThat(response.getCaps()).isNotNull();
        }
    }
}
