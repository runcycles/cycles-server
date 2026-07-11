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
@TestPropertySource(properties = {
        "admin.api-key=tenant-closed-guard-admin-key",
        // Evidence identity configured so the non-persisting-evaluation tests can
        // assert that a DENY/TENANT_CLOSED outcome IS stamped as signed evidence
        // (and that a malformed-record 500 is NOT).
        "cycles.evidence.server-id=https://cycles.test.local/v1",
        "cycles.evidence.signing.signer-did=did:key:test-tenant-closed-guard"
})
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

    private io.runcycles.protocol.model.EventCreateRequest eventRequest(long amount,
            Enums.CommitOveragePolicy overagePolicy) {
        Subject subject = new Subject();
        subject.setTenant(TENANT_A);
        Action action = new Action();
        action.setKind("llm.completion");
        action.setName("test-model");
        return io.runcycles.protocol.model.EventCreateRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .subject(subject)
                .action(action)
                .actual(new Amount(Enums.UnitEnum.TOKENS, amount))
                .overagePolicy(overagePolicy)
                .build();
    }

    private io.runcycles.protocol.model.EventCreateRequest eventRequest(long amount) {
        return eventRequest(amount, null);
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
            // Extend is NOT in EVIDENCE_ENDPOINTS: a TENANT_CLOSED denial on
            // extend carries no error evidence, same as every other denial
            // code there (pinned at the handler level in
            // GlobalExceptionHandlerTest.tenantClosedOnExtendRouteDoesNotEmit).
            assertThat(evidenceRecords("error")).isEmpty();
        }
    }

    // ---- POST /v1/events debit guard (runtime spec v0.1.25.14, pending) ----

    /**
     * /v1/events is a persisting budget debit, so a CLOSED owning tenant is
     * rejected 409 TENANT_CLOSED, mirroring the reservation guards. Exposure
     * is narrower than reservations: /v1/events has no admin-key path (not in
     * the admin allowlist) and the tenant-key auth filter 401s a durably-closed
     * tenant before the controller, so the only residual is the post-flip race
     * (a request past auth just before the flip). These repository-level tests
     * exercise exactly that "already past auth" window against real Lua.
     * /v1/events is outside EVIDENCE_ENDPOINTS entirely — no denial there stamps
     * error-evidence — so TENANT_CLOSED emits none either (consistent).
     */
    @Nested
    @DisplayName("POST /v1/events debit on a CLOSED tenant → 409 TENANT_CLOSED")
    class EventDebitGuard {

        @Test
        void freshEventOnClosedTenant_rejected_noDebit() {
            seedTenantWithStatus(TENANT_A, "CLOSED");
            Map<String, String> before = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");

            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.createEvent(eventRequest(100), TENANT_A)));

            // Guard runs inside event.lua BEFORE any debit — no partial mutation.
            Map<String, String> after = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
            assertThat(after.get("remaining")).isEqualTo(before.get("remaining"));
            assertThat(after.get("spent")).isEqualTo(before.get("spent"));
            assertThat(after.get("debt")).isEqualTo(before.get("debt"));
            // /v1/events is not an evidence endpoint — no error-evidence row.
            assertThat(evidenceRecords("error")).isEmpty();
        }

        @Test
        void absentTenantRecord_eventAppliesNormally() {
            // Runtime-only deployment, no governance plane: no restriction.
            io.runcycles.protocol.model.EventCreateResponse response =
                    reservationRepository.createEvent(eventRequest(100), TENANT_A);

            assertThat(response.getStatus()).isEqualTo(Enums.EventStatus.APPLIED);
            assertThat(getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS").get("spent")).isEqualTo("100");
        }

        @Test
        void eventReplayAfterClose_returnsOriginalResponse() {
            // Mode B invariant (b): a pre-close idempotent replay still returns
            // its stored response; only a FRESH event on a closed tenant is
            // rejected.
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            io.runcycles.protocol.model.EventCreateRequest request = eventRequest(100);
            io.runcycles.protocol.model.EventCreateResponse original =
                    reservationRepository.createEvent(request, TENANT_A);
            assertThat(original.getStatus()).isEqualTo(Enums.EventStatus.APPLIED);
            String spentAfterApply = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS").get("spent");

            seedTenantWithStatus(TENANT_A, "CLOSED");

            io.runcycles.protocol.model.EventCreateResponse replay =
                    reservationRepository.createEvent(request, TENANT_A);
            assertThat(replay.isIdempotentReplay()).isTrue();
            assertThat(replay.getEventId()).isEqualTo(original.getEventId());
            // Replay does not re-debit.
            assertThat(getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS").get("spent")).isEqualTo(spentAfterApply);

            // A NEW event (different idempotency key) is still rejected.
            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.createEvent(eventRequest(100), TENANT_A)));
        }

        @Test
        void tenantClosedPrecedesBudgetExceeded() {
            // Amount far exceeds remaining with REJECT policy — on an OPEN tenant
            // this is BUDGET_EXCEEDED; on a CLOSED tenant the guard wins.
            seedTenantWithStatus(TENANT_A, "CLOSED");

            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.createEvent(
                            eventRequest(99_999_999L, Enums.CommitOveragePolicy.REJECT), TENANT_A)));

            Map<String, String> after = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
            assertThat(after.get("spent")).isEqualTo("0");
        }

        @Test
        void tenantClosedPrecedesBudgetClosed() {
            // Budget CLOSED + tenant CLOSED: the tenant guard (owner-level) runs
            // before the per-scope BUDGET_CLOSED check.
            try (Jedis jedis = jedisPool.getResource()) {
                seedBudgetWithStatus(jedis, TENANT_A, "TOKENS", 1_000_000, "CLOSED");
            }
            seedTenantWithStatus(TENANT_A, "CLOSED");

            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.createEvent(eventRequest(100), TENANT_A)));
        }

        @Test
        void activeAndSuspendedTenants_eventApplies() {
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            assertThat(reservationRepository.createEvent(eventRequest(100), TENANT_A).getStatus())
                    .isEqualTo(Enums.EventStatus.APPLIED);

            // SUSPENDED is not gated at the debit layer (mutation guard is
            // CLOSED-only; SUSPENDED semantics live in tenant-key auth).
            seedTenantWithStatus(TENANT_A, "SUSPENDED");
            assertThat(reservationRepository.createEvent(eventRequest(100), TENANT_A).getStatus())
                    .isEqualTo(Enums.EventStatus.APPLIED);
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

            // Round 5: TENANT_CLOSED is in EVIDENCE_DENIAL_CODES (evidence
            // ErrorResponseMirror, cycles-evidence-v0.2.yaml 0.2.1, spec PR
            // runcycles/cycles-protocol#125) — the signed denial receipt is
            // exactly what a closed-tenant enforcement event should produce.
            // Wire proof: the 409 body is stamped and EXACTLY ONE error-
            // evidence record is queued (the reserve record from the setup
            // create is a separate artifact_type), carrying the TENANT_CLOSED
            // code and the hoisted reservation_id.
            assertThat(body.get("cycles_evidence")).isNotNull();
            java.util.List<String> errorRecords = evidenceRecords("error");
            assertThat(errorRecords).hasSize(1);
            assertThat(errorRecords.get(0))
                    .contains("\"TENANT_CLOSED\"")
                    .contains("\"reservation_id\":\"" + reservationId + "\"")
                    .contains("/release");
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

    /**
     * Tenant records that must FAIL CLOSED on every guard surface (the four
     * Lua mutation guards AND the shared dry_run/decide evaluation gate):
     * malformed JSON, non-object JSON, object missing status, and an unknown
     * status string (TenantStatus is a closed enum - ACTIVE|SUSPENDED|CLOSED -
     * so "CLOZED" or lowercase "closed" is corruption, not a future value).
     */
    private static final String[] BAD_RECORDS = {
            "{not-json",                       // malformed JSON
            "\"CLOSED\"",                      // valid JSON, but not an object
            "42",                              // valid JSON, but not an object
            "{\"tenant_id\":\"tenant-a\"}",    // object, but no status field
            "{\"tenant_id\":\"tenant-a\",\"status\":\"CLOZED\"}",  // unknown status
            "{\"tenant_id\":\"tenant-a\",\"status\":\"closed\"}"   // case-sensitivity pin
    };

    // ---- fail-closed on malformed tenant records ----

    /** Write a RAW (possibly invalid) value at the admin-plane tenant key. */
    private void seedRawTenantRecord(String tenantId, String rawValue) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("tenant:" + tenantId, rawValue);
        }
    }

    private static void assertMalformedTenantFailsClosed(Throwable thrown) {
        assertThat(thrown).isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.INTERNAL_ERROR)
                .hasFieldOrPropertyWithValue("httpStatus", 500)
                .hasMessageContaining("Malformed tenant record");
    }

    /**
     * A PRESENT {@code tenant:<id>} row that cannot be decoded into an object
     * with a string status must FAIL CLOSED (500 INTERNAL_ERROR, no mutation)
     * — matching the admin plane's TenantRepository, which propagates parse
     * failures instead of treating a corrupt governance record as an open
     * tenant. Absent key stays no-guard (covered elsewhere). Shapes per op:
     * malformed JSON, non-object JSON (bare string / number), object missing
     * status, and object with an unknown status string (TenantStatus is a
     * closed enum, so "CLOZED" or lowercase "closed" is corruption, not a
     * future value).
     */
    @Nested
    @DisplayName("malformed tenant record fails closed (500, no mutation)")
    class MalformedTenantRecordFailsClosed {

        @Test
        void reserve_failsClosed_noBudgetMutation() {
            Map<String, String> budgetBefore = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
            for (String bad : BAD_RECORDS) {
                seedRawTenantRecord(TENANT_A, bad);

                assertMalformedTenantFailsClosed(catchThrowable(
                        () -> reservationRepository.createReservation(createRequest(100), TENANT_A)));

                Map<String, String> budgetAfter = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
                assertThat(budgetAfter.get("reserved")).as(bad).isEqualTo(budgetBefore.get("reserved"));
                assertThat(budgetAfter.get("remaining")).as(bad).isEqualTo(budgetBefore.get("remaining"));
                assertThat(scanReservationKeys()).as(bad).isEmpty();
            }
        }

        @Test
        void commit_failsClosed_reservationAndBudgetUntouched() {
            String reservationId = reserveViaRepository(100);
            for (String bad : BAD_RECORDS) {
                seedRawTenantRecord(TENANT_A, bad);

                CommitRequest commit = new CommitRequest();
                commit.setActual(new Amount(Enums.UnitEnum.TOKENS, 80L));
                commit.setIdempotencyKey(UUID.randomUUID().toString());
                assertMalformedTenantFailsClosed(catchThrowable(
                        () -> reservationRepository.commitReservation(reservationId, commit, TENANT_A)));

                assertThat(getReservationStateFromRedis(reservationId).get("state")).as(bad).isEqualTo("ACTIVE");
                Map<String, String> budget = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
                assertThat(budget.get("reserved")).as(bad).isEqualTo("100");
                assertThat(budget.get("spent")).as(bad).isEqualTo("0");
            }
        }

        @Test
        void release_failsClosed_reservationStaysActive() {
            String reservationId = reserveViaRepository(100);
            for (String bad : BAD_RECORDS) {
                seedRawTenantRecord(TENANT_A, bad);

                ReleaseRequest release = ReleaseRequest.builder()
                        .idempotencyKey(UUID.randomUUID().toString()).build();
                assertMalformedTenantFailsClosed(catchThrowable(
                        () -> reservationRepository.releaseReservation(reservationId, release, TENANT_A, "tenant")));

                assertThat(getReservationStateFromRedis(reservationId).get("state")).as(bad).isEqualTo("ACTIVE");
                assertThat(getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS").get("reserved")).as(bad).isEqualTo("100");
            }
        }

        @Test
        void extend_failsClosed_expiryUnchanged() {
            String reservationId = reserveViaRepository(100);
            String expiresBefore = getReservationStateFromRedis(reservationId).get("expires_at");
            for (String bad : BAD_RECORDS) {
                seedRawTenantRecord(TENANT_A, bad);

                ReservationExtendRequest extend = new ReservationExtendRequest();
                extend.setExtendByMs(30000L);
                extend.setIdempotencyKey(UUID.randomUUID().toString());
                assertMalformedTenantFailsClosed(catchThrowable(
                        () -> reservationRepository.extendReservation(reservationId, extend, TENANT_A)));

                Map<String, String> reservation = getReservationStateFromRedis(reservationId);
                assertThat(reservation.get("expires_at")).as(bad).isEqualTo(expiresBefore);
                assertThat(reservation.get("extension_count")).as(bad).isEqualTo("0");
            }
        }

        @Test
        void event_failsClosed_noDebit() {
            Map<String, String> before = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
            for (String bad : BAD_RECORDS) {
                seedRawTenantRecord(TENANT_A, bad);

                assertMalformedTenantFailsClosed(catchThrowable(
                        () -> reservationRepository.createEvent(eventRequest(100), TENANT_A)));

                Map<String, String> after = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
                assertThat(after.get("remaining")).as(bad).isEqualTo(before.get("remaining"));
                assertThat(after.get("spent")).as(bad).isEqualTo(before.get("spent"));
            }
        }
    }

    // ---- TENANT_CLOSED precedence over reservation-state errors ----

    /**
     * Rule 2: closed-owner mutations reject with TENANT_CLOSED "regardless of
     * that child's own current status" (spec PR runcycles/cycles-protocol#125
     * ERROR SEMANTICS: the closed-tenant rejection takes precedence over
     * reservation-state errors). Same-key idempotent replays keep their
     * original response (Rule 2(b) idempotency); everything else on a closed
     * tenant — including a different-key attempt on a FINALIZED reservation —
     * is TENANT_CLOSED, not RESERVATION_FINALIZED.
     */
    @Nested
    @DisplayName("TENANT_CLOSED precedence over RESERVATION_FINALIZED")
    class TenantClosedPrecedence {

        @Test
        void commit_differentKeyOnCommitted_closedTenant_isTenantClosed() {
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            String reservationId = reserveViaRepository(100);
            CommitRequest first = new CommitRequest();
            first.setActual(new Amount(Enums.UnitEnum.TOKENS, 80L));
            first.setIdempotencyKey(UUID.randomUUID().toString());
            reservationRepository.commitReservation(reservationId, first, TENANT_A);

            seedTenantWithStatus(TENANT_A, "CLOSED");

            // Different key on a finalized reservation of a CLOSED tenant:
            // Rule 2 wins over RESERVATION_FINALIZED.
            CommitRequest second = new CommitRequest();
            second.setActual(new Amount(Enums.UnitEnum.TOKENS, 80L));
            second.setIdempotencyKey(UUID.randomUUID().toString());
            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.commitReservation(reservationId, second, TENANT_A)));

            // Same key = idempotent replay: still returns the original response.
            assertThat(reservationRepository.commitReservation(reservationId, first, TENANT_A)
                    .isIdempotentReplay()).isTrue();
        }

        @Test
        void release_differentKeyOnReleased_closedTenant_isTenantClosed() {
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            String reservationId = reserveViaRepository(100);
            ReleaseRequest first = ReleaseRequest.builder()
                    .idempotencyKey(UUID.randomUUID().toString()).build();
            reservationRepository.releaseReservation(reservationId, first, TENANT_A, "tenant");

            seedTenantWithStatus(TENANT_A, "CLOSED");

            ReleaseRequest second = ReleaseRequest.builder()
                    .idempotencyKey(UUID.randomUUID().toString()).build();
            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.releaseReservation(reservationId, second, TENANT_A, "tenant")));

            assertThat(reservationRepository.releaseReservation(reservationId, first, TENANT_A, "tenant")
                    .isIdempotentReplay()).isTrue();
        }

        @Test
        void extend_onFinalizedReservation_closedTenant_isTenantClosed() {
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            String reservationId = reserveViaRepository(100);
            ReleaseRequest release = ReleaseRequest.builder()
                    .idempotencyKey(UUID.randomUUID().toString()).build();
            reservationRepository.releaseReservation(reservationId, release, TENANT_A, "tenant");

            seedTenantWithStatus(TENANT_A, "CLOSED");

            ReservationExtendRequest extend = new ReservationExtendRequest();
            extend.setExtendByMs(30000L);
            extend.setIdempotencyKey(UUID.randomUUID().toString());
            assertTenantClosedException(catchThrowable(
                    () -> reservationRepository.extendReservation(reservationId, extend, TENANT_A)));
        }

        @Test
        void differentKeyOnFinalized_openTenant_staysReservationFinalized() {
            // No-regression pin for the reorder: with the tenant OPEN, a
            // different-key attempt on a finalized reservation still returns
            // RESERVATION_FINALIZED exactly as before.
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            String reservationId = reserveViaRepository(100);
            CommitRequest first = new CommitRequest();
            first.setActual(new Amount(Enums.UnitEnum.TOKENS, 80L));
            first.setIdempotencyKey(UUID.randomUUID().toString());
            reservationRepository.commitReservation(reservationId, first, TENANT_A);

            CommitRequest second = new CommitRequest();
            second.setActual(new Amount(Enums.UnitEnum.TOKENS, 80L));
            second.setIdempotencyKey(UUID.randomUUID().toString());
            assertThat(catchThrowable(
                    () -> reservationRepository.commitReservation(reservationId, second, TENANT_A)))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_FINALIZED)
                    .hasFieldOrPropertyWithValue("httpStatus", 409);

            ReleaseRequest releaseAttempt = ReleaseRequest.builder()
                    .idempotencyKey(UUID.randomUUID().toString()).build();
            assertThat(catchThrowable(
                    () -> reservationRepository.releaseReservation(reservationId, releaseAttempt, TENANT_A, "tenant")))
                    .isInstanceOf(CyclesProtocolException.class)
                    .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.RESERVATION_FINALIZED);
        }
    }

    // ---- non-persisting evaluations: dry_run + /v1/decide ----

    private ReservationCreateRequest dryRunRequest(long amount) {
        ReservationCreateRequest request = createRequest(amount);
        request.setDryRun(true);
        return request;
    }

    private io.runcycles.protocol.model.DecisionRequest decisionRequest(long amount) {
        Subject subject = new Subject();
        subject.setTenant(TENANT_A);
        Action action = new Action();
        action.setKind("llm.completion");
        action.setName("test-model");
        return io.runcycles.protocol.model.DecisionRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .subject(subject)
                .action(action)
                .estimate(new Amount(Enums.UnitEnum.TOKENS, amount))
                .build();
    }

    private long evidenceQueueLength() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.llen("evidence:pending");
        }
    }

    /** All queued evidence-source records of the given artifact_type. */
    private java.util.List<String> evidenceRecords(String artifactType) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.lrange("evidence:pending", 0, -1).stream()
                    .filter(r -> r.contains("\"artifact_type\":\"" + artifactType + "\""))
                    .toList();
        }
    }

    /**
     * Reviewer finding (round 4): dry_run bypassed the tenant guard entirely,
     * so a closed tenant's dry_run could stamp a SIGNED ALLOW attestation for
     * a request whose live execution MUST fail with 409 TENANT_CLOSED.
     * Resolution (spec PR runcycles/cycles-protocol#125, amended): the
     * non-persisting evaluations - POST /v1/reservations dry_run=true and
     * POST /v1/decide - do NOT 409; a FRESH evaluation on a CLOSED tenant
     * returns 200 decision=DENY, reason_code=TENANT_CLOSED (non-mutating, and
     * the DENY is the truthful signed attestation). Malformed records fail
     * closed with 500 BEFORE any evidence is stamped (the server cannot attest
     * against corrupt governance state); absent records evaluate normally;
     * cached pre-close replays keep their original payload.
     */
    @Nested
    @DisplayName("non-persisting evaluations (dry_run + decide) on a CLOSED tenant")
    class NonPersistingEvaluations {

        @Test
        void freshDryRunOnClosedTenant_denies_withTenantClosed_andStampsEvidence() {
            seedTenantWithStatus(TENANT_A, "CLOSED");
            Map<String, String> budgetBefore = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");

            ReservationCreateResponse response =
                    reservationRepository.createReservation(dryRunRequest(100), TENANT_A);

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.TENANT_CLOSED);
            assertThat(response.getReservationId()).isNull();
            // Non-mutating, as always for dry_run.
            Map<String, String> budgetAfter = getBudgetFromRedis("tenant:" + TENANT_A, "TOKENS");
            assertThat(budgetAfter.get("reserved")).isEqualTo(budgetBefore.get("reserved"));
            assertThat(budgetAfter.get("remaining")).isEqualTo(budgetBefore.get("remaining"));
            assertThat(scanReservationKeys()).isEmpty();
            // The DENY outcome IS the signed attestation - evidence stamped + queued.
            assertThat(response.getCyclesEvidence()).isNotNull();
            assertThat(response.getCyclesEvidence().getEvidenceId()).isNotBlank();
            assertThat(evidenceQueueLength()).isEqualTo(1);
        }

        @Test
        void freshDecideOnClosedTenant_denies_withTenantClosed_andStampsEvidence() {
            seedTenantWithStatus(TENANT_A, "CLOSED");

            io.runcycles.protocol.model.DecisionResponse response =
                    reservationRepository.decide(decisionRequest(100), TENANT_A);

            assertThat(response.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(response.getReasonCode()).isEqualTo(Enums.ReasonCode.TENANT_CLOSED);
            assertThat(response.getCyclesEvidence()).isNotNull();
            assertThat(evidenceQueueLength()).isEqualTo(1);
        }

        @Test
        void dryRunReplayAcrossFlip_returnsOriginalAllow_freshEvaluationDenies() {
            // Replay precedence unchanged: a cached PRE-close dry_run replays its
            // original (ALLOW) payload after the flip; only FRESH evaluations DENY.
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            ReservationCreateRequest request = dryRunRequest(100);
            ReservationCreateResponse original =
                    reservationRepository.createReservation(request, TENANT_A);
            assertThat(original.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);

            seedTenantWithStatus(TENANT_A, "CLOSED");

            ReservationCreateResponse replay =
                    reservationRepository.createReservation(request, TENANT_A);
            assertThat(replay.isIdempotentReplay()).isTrue();
            assertThat(replay.getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);

            ReservationCreateResponse fresh =
                    reservationRepository.createReservation(dryRunRequest(100), TENANT_A);
            assertThat(fresh.getDecision()).isEqualTo(Enums.DecisionEnum.DENY);
            assertThat(fresh.getReasonCode()).isEqualTo(Enums.ReasonCode.TENANT_CLOSED);
        }

        @Test
        void malformedTenantRecord_dryRunAndDecide_fail500_beforeEvidence() {
            // Fail-closed BEFORE evidence stamping: the server must not attest
            // against corrupt governance state. Convention followed: evidence is
            // emitted only for decisions actually reached (same reason
            // INTERNAL_ERROR is not in GlobalExceptionHandler's
            // EVIDENCE_DENIAL_CODES) - so no reserve/decide evidence row and no
            // error-evidence row is written for the failed evaluation.
            for (String bad : BAD_RECORDS) {
                seedRawTenantRecord(TENANT_A, bad);

                assertMalformedTenantFailsClosed(catchThrowable(
                        () -> reservationRepository.createReservation(dryRunRequest(100), TENANT_A)));
                assertMalformedTenantFailsClosed(catchThrowable(
                        () -> reservationRepository.decide(decisionRequest(100), TENANT_A)));
            }
            assertThat(evidenceQueueLength()).as("no evidence for failed evaluations").isZero();
            assertThat(scanReservationKeys()).isEmpty();
        }

        @Test
        void absentTenantRecord_dryRunEvaluatesNormally_viaHttp() {
            // No governance plane: evaluation is unchanged. HTTP + validating
            // client on purpose - an ALLOW body carries no TENANT_CLOSED value.
            Map<String, Object> body = reservationBody(TENANT_A, 100);
            body.put("dry_run", true);

            ResponseEntity<Map> response = post("/v1/reservations", API_KEY_SECRET_A, body);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("decision")).isEqualTo("ALLOW");
            assertThat(response.getBody().get("reservation_id")).isNull();
        }

        @Test
        void activeAndSuspendedTenants_evaluationsUnchanged() {
            seedTenantWithStatus(TENANT_A, "ACTIVE");
            assertThat(reservationRepository.createReservation(dryRunRequest(100), TENANT_A)
                    .getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);

            // SUSPENDED is not gated at the evaluation layer (spec covers CLOSED
            // only; SUSPENDED semantics live in tenant-key auth - pinned elsewhere).
            seedTenantWithStatus(TENANT_A, "SUSPENDED");
            assertThat(reservationRepository.createReservation(dryRunRequest(100), TENANT_A)
                    .getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
            assertThat(reservationRepository.decide(decisionRequest(100), TENANT_A)
                    .getDecision()).isEqualTo(Enums.DecisionEnum.ALLOW);
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
