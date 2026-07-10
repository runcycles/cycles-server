package io.runcycles.protocol.api;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.model.Action;
import io.runcycles.protocol.model.Amount;
import io.runcycles.protocol.model.CommitRequest;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ReleaseRequest;
import io.runcycles.protocol.model.ReservationCreateRequest;
import io.runcycles.protocol.model.ReservationCreateResponse;
import io.runcycles.protocol.model.ReservationExtendRequest;
import io.runcycles.protocol.model.Subject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import redis.clients.jedis.Jedis;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Governance CASCADE SEMANTICS Rule 2 — terminal-owner mutation guard on the
 * runtime plane (cycles-governance-admin-v0.1.25; runtime spec revision
 * v0.1.25.13, runcycles/cycles-protocol#125): once the owning tenant's CLOSED flip is durable in
 * the shared Redis, reservation create/commit/release/extend MUST be rejected
 * with HTTP 409 {@code TENANT_CLOSED} (Mode B invariant (a): a mutation
 * observed after the flip MUST NOT succeed, even before the close cascade
 * touches the child or revokes keys).
 *
 * <p>How a closed tenant surfaces on this plane, layer by layer:
 * <ul>
 *   <li><b>Tenant-key HTTP requests</b> — {@code ApiKeyRepository.validate}
 *       already reads {@code tenant:<id>} per request and rejects
 *       SUSPENDED/CLOSED tenants with 401 at the auth filter (the pending
 *       spec revision's "usually surfaces as 401"). Pinned below.</li>
 *   <li><b>Admin-key HTTP requests</b> — admin auth carries no tenant-status
 *       check; before this change, {@code POST .../release} with a valid
 *       {@code X-Admin-API-Key} SUCCEEDED on a closed tenant. The in-script
 *       guard now returns 409 {@code TENANT_CLOSED}. Reads (GET/list) stay
 *       available for post-close audit.</li>
 *   <li><b>The auth-check→script race</b> — auth reads tenant status at
 *       filter time; the flip can land between that read and the Lua
 *       execution. The guard runs INSIDE the scripts (atomic with the budget
 *       mutations), so a request that passed auth pre-flip still cannot
 *       mutate post-flip. Exercised at the repository layer below (a
 *       repository call is exactly "a request already past auth").</li>
 * </ul>
 *
 * <p><b>Contract-validation note:</b> 409 {@code TENANT_CLOSED} HTTP calls go
 * through {@link #rawClient} (no ContractValidatingRestTemplateInterceptor)
 * because the live spec at cycles-protocol@main does not yet list
 * TENANT_CLOSED in the runtime ErrorCode enum — that is spec revision
 * v0.1.25.13 (runcycles/cycles-protocol#125). The ErrorResponse shape is asserted explicitly;
 * these calls can move to the validating client once the revision merges.
 */
@DisplayName("Tenant CLOSED guard (governance Rule 2) — reservation mutations")
@TestPropertySource(properties = "admin.api-key=tenant-closed-guard-admin-key")
class TenantClosedGuardIntegrationTest extends BaseIntegrationTest {

    private static final String ADMIN_KEY = "tenant-closed-guard-admin-key";

    @Autowired
    private RedisReservationRepository reservationRepository;

    /** Plain client without the contract-validating interceptor — see class javadoc. */
    private final TestRestTemplate rawClient = new TestRestTemplate();

    // ---- helpers ----

    private ResponseEntity<Map> postRaw(String path, String apiKey, Map<String, Object> body) {
        return rawClient.exchange(baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(body, headersForTenant(apiKey)), Map.class);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-API-Key", ADMIN_KEY);
        return headers;
    }

    /** Seed the admin-plane tenant record ("tenant:<id>" JSON) with a given status. */
    private void seedTenantWithStatus(String tenantId, String status) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, Object> tenant = new HashMap<>();
            tenant.put("tenant_id", tenantId);
            tenant.put("name", tenantId);
            tenant.put("status", status);
            tenant.put("created_at", Instant.now().toString());
            if ("CLOSED".equals(status)) {
                tenant.put("closed_at", Instant.now().toString());
            }
            jedis.set("tenant:" + tenantId, objectMapper.writeValueAsString(tenant));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReservationCreateRequest createRequest(long amount) {
        Subject subject = new Subject();
        subject.setTenant(TENANT_A);
        Action action = new Action();
        action.setKind("llm.completion");
        action.setName("test-model");
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setIdempotencyKey(UUID.randomUUID().toString());
        request.setSubject(subject);
        request.setAction(action);
        request.setEstimate(new Amount(Enums.UnitEnum.TOKENS, amount));
        request.setTtlMs(60000L);
        request.setOveragePolicy(Enums.CommitOveragePolicy.REJECT);
        return request;
    }

    private String reserveViaRepository(long amount) {
        ReservationCreateResponse response =
                reservationRepository.createReservation(createRequest(amount), TENANT_A);
        assertThat(response.getReservationId()).isNotBlank();
        return response.getReservationId();
    }

    private static void assertTenantClosedException(Throwable thrown) {
        assertThat(thrown).isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.TENANT_CLOSED)
                .hasFieldOrPropertyWithValue("httpStatus", 409)
                .hasMessageContaining(TENANT_A)
                .hasMessageContaining("closed");
    }

    // ---- repository layer: the post-auth race window, all four ops ----

    @Nested
    @DisplayName("post-auth mutation on CLOSED tenant → 409 TENANT_CLOSED (in-script guard)")
    class InScriptGuardRejectsAllFourOps {

        @Test
        void reserveOnClosedTenant_rejected_noBudgetMutation() {
            seedTenantWithStatus(TENANT_A, "CLOSED");
            Map<String, String> budgetBefore = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");

            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.createReservation(createRequest(100), TENANT_A)));

            // Guard runs inside reserve.lua BEFORE any budget mutation — a
            // post-flip request can never partially succeed.
            Map<String, String> budgetAfter = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
            assertThat(budgetAfter.get("reserved")).isEqualTo(budgetBefore.get("reserved"));
            assertThat(budgetAfter.get("remaining")).isEqualTo(budgetBefore.get("remaining"));
            assertThat(scanReservationKeys()).isEmpty();
        }

        @Test
        void commitOnClosedTenant_rejected_reservationAndBudgetUntouched() {
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            String reservationId = reserveViaRepository(100);
            seedTenantWithStatus(TENANT_A, "CLOSED"); // durable flip AFTER reserve

            CommitRequest commit = new CommitRequest();
            commit.setActual(new Amount(Enums.UnitEnum.TOKENS, 80L));
            commit.setIdempotencyKey(UUID.randomUUID().toString());

            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.commitReservation(reservationId, commit, TENANT_A)));

            Map<String, String> reservation = getReservationStateFromRedis(reservationId);
            assertThat(reservation.get("state")).isEqualTo("ACTIVE");
            Map<String, String> budget = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
            assertThat(budget.get("reserved")).isEqualTo("100"); // still held, not spent
            assertThat(budget.get("spent")).isEqualTo("0");
        }

        @Test
        void releaseOnClosedTenant_rejected_reservationStaysActive() {
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            String reservationId = reserveViaRepository(100);
            seedTenantWithStatus(TENANT_A, "CLOSED");

            ReleaseRequest release = ReleaseRequest.builder()
                    .idempotencyKey(UUID.randomUUID().toString())
                    .reason("post-flip release attempt")
                    .build();

            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.releaseReservation(reservationId, release, TENANT_A, "tenant")));

            Map<String, String> reservation = getReservationStateFromRedis(reservationId);
            assertThat(reservation.get("state")).isEqualTo("ACTIVE");
            Map<String, String> budget = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
            assertThat(budget.get("reserved")).isEqualTo("100"); // not drained by the request
        }

        @Test
        void extendOnClosedTenant_rejected_expiryUnchanged() {
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            String reservationId = reserveViaRepository(100);
            String expiresBefore = getReservationStateFromRedis(reservationId).get("expires_at");
            seedTenantWithStatus(TENANT_A, "CLOSED");

            ReservationExtendRequest extend = new ReservationExtendRequest();
            extend.setExtendByMs(30000L);
            extend.setIdempotencyKey(UUID.randomUUID().toString());

            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.extendReservation(reservationId, extend, TENANT_A)));

            Map<String, String> reservation = getReservationStateFromRedis(reservationId);
            assertThat(reservation.get("expires_at")).isEqualTo(expiresBefore);
            assertThat(reservation.get("extension_count")).isEqualTo("0");
        }
    }

    // ---- HTTP surface ----

    @Nested
    @DisplayName("HTTP surface on a CLOSED tenant")
    class HttpSurface {

        @Test
        void adminRelease_onClosedTenant_now409TenantClosed() {
            // The reachable HTTP hole this guard closes: admin keys carry no
            // tenant-status check, so before this change an admin release on
            // a closed tenant SUCCEEDED, mutating budgets post-flip.
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
            seedTenantWithStatus(TENANT_A, "CLOSED");

            ResponseEntity<Map> response = rawClient.exchange(
                    baseUrl() + "/v1/reservations/" + reservationId + "/release",
                    HttpMethod.POST, new HttpEntity<>(releaseBody(), adminHeaders()), Map.class);

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            // Error response shape matches every other 409 (ErrorResponse envelope).
            assertThat(body.get("error")).isEqualTo("TENANT_CLOSED");
            assertThat((String) body.get("message")).contains(TENANT_A).contains("closed");
            assertThat((String) body.get("request_id")).isNotBlank();
            assertThat((String) body.get("trace_id")).isNotBlank();

            assertThat(getReservationStateFromRedis(reservationId).get("state")).isEqualTo("ACTIVE");
            assertThat(getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS").get("reserved")).isEqualTo("100");
        }

        @Test
        void tenantKeyMutation_onClosedTenant_is401AtAuth() {
            // Existing (pre-guard) behavior, pinned: the tenant-key auth
            // filter reads tenant:<id> per request and rejects CLOSED tenants
            // with 401 before the controller runs — the spec revision's
            // "a closed tenant usually surfaces on this plane as 401".
            // The in-script 409 guard sits behind it for everything auth
            // cannot see (admin keys, the filter→script race).
            seedTenantWithStatus(TENANT_A, "CLOSED");

            ResponseEntity<Map> response = postRaw("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 100));

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody().get("error")).isEqualTo("UNAUTHORIZED");
        }

        @Test
        void adminReads_onClosedTenant_stillWork() {
            // Spec Rule 2: non-mutating reads MUST remain available on owned
            // objects of a CLOSED tenant (post-close audit).
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
            seedTenantWithStatus(TENANT_A, "CLOSED");

            ResponseEntity<Map> getOne = restTemplate.exchange(
                    baseUrl() + "/v1/reservations/" + reservationId,
                    HttpMethod.GET, new HttpEntity<>(adminHeaders()), Map.class);
            assertThat(getOne.getStatusCode().value()).isEqualTo(200);
            assertThat(getOne.getBody().get("reservation_id")).isEqualTo(reservationId);
            assertThat(getOne.getBody().get("status")).isEqualTo("ACTIVE");

            ResponseEntity<Map> list = restTemplate.exchange(
                    baseUrl() + "/v1/reservations?tenant=" + TENANT_A + "&limit=10",
                    HttpMethod.GET, new HttpEntity<>(adminHeaders()), Map.class);
            assertThat(list.getStatusCode().value()).isEqualTo(200);
            assertThat((java.util.List<?>) list.getBody().get("reservations")).hasSize(1);
        }
    }

    // ---- guard does not fire ----

    @Nested
    @DisplayName("guard does not fire without a CLOSED tenant record")
    class GuardDoesNotFire {

        @Test
        void tenantRecordAbsent_opsProceedNormally() {
            // Runtime-only deployment: no governance plane ever wrote a tenant
            // record. Absence of the record means no restriction (spec:
            // "servers deployed WITHOUT a governance plane ... the rule is
            // not applicable to them").
            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);

            ResponseEntity<Map> extend = post(
                    "/v1/reservations/" + reservationId + "/extend", API_KEY_SECRET_A, extendBody(30000));
            assertThat(extend.getStatusCode().value()).isEqualTo(200);

            ResponseEntity<Map> commit = post(
                    "/v1/reservations/" + reservationId + "/commit", API_KEY_SECRET_A, commitBody(80));
            assertThat(commit.getStatusCode().value()).isEqualTo(200);
            assertThat(getReservationStateFromRedis(reservationId).get("state")).isEqualTo("COMMITTED");
        }

        @Test
        void activeTenant_opsProceedNormally() {
            seedTenantWithStatus(TENANT_A, "ACTIVE");

            String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 100);
            ResponseEntity<Map> release = post(
                    "/v1/reservations/" + reservationId + "/release", API_KEY_SECRET_A, releaseBody());

            assertThat(release.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        void suspendedTenant_notGuarded_atMutationLayer() {
            // Governance Rule 2 and runtime spec v0.1.25.13 cover CLOSED
            // only. SUSPENDED already has runtime semantics at the AUTH
            // layer (401 for tenant keys — pre-existing, pinned below);
            // the mutation-layer guard must NOT add anything for SUSPENDED.
            seedTenantWithStatus(TENANT_A, "SUSPENDED");

            String reservationId = reserveViaRepository(100);
            CommitRequest commit = new CommitRequest();
            commit.setActual(new Amount(Enums.UnitEnum.TOKENS, 100L));
            commit.setIdempotencyKey(UUID.randomUUID().toString());
            assertThat(reservationRepository.commitReservation(reservationId, commit, TENANT_A)
                    .getStatus()).isEqualTo(Enums.CommitStatus.COMMITTED);
            assertThat(getReservationStateFromRedis(reservationId).get("state")).isEqualTo("COMMITTED");

            // Pre-existing auth-layer behavior for SUSPENDED, unchanged.
            ResponseEntity<Map> http = postRaw("/v1/reservations", API_KEY_SECRET_A,
                    reservationBody(TENANT_A, 100));
            assertThat(http.getStatusCode().value()).isEqualTo(401);
        }

        @Test
        void closedTenant_doesNotAffectOtherTenants() {
            seedTenantWithStatus(TENANT_A, "CLOSED");

            // TENANT_B has no tenant record and its own budget — unaffected.
            ResponseEntity<Map> response = post("/v1/reservations", API_KEY_SECRET_B,
                    reservationBody(TENANT_B, 100));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }

    // ---- idempotent replay semantics across the flip ----

    @Nested
    @DisplayName("idempotent replay semantics across the flip")
    class ReplayAcrossFlip {

        @Test
        void reserveReplayAfterClose_returnsOriginalResponse() {
            // A replay re-observes a mutation that succeeded BEFORE the flip —
            // it is not a new mutation, so it returns the original response
            // (mirrors how the frozen/closed-budget guards sit after replay
            // handling in reserve.lua). A NEW key is still rejected.
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            ReservationCreateRequest request = createRequest(100);
            ReservationCreateResponse original =
                    reservationRepository.createReservation(request, TENANT_A);

            seedTenantWithStatus(TENANT_A, "CLOSED");

            ReservationCreateResponse replay =
                    reservationRepository.createReservation(request, TENANT_A);
            assertThat(replay.getReservationId()).isEqualTo(original.getReservationId());

            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.createReservation(createRequest(100), TENANT_A)));
        }
    }
}
