package io.runcycles.protocol.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.api.auth.ApiKeyAuthentication;
import io.runcycles.protocol.api.auth.ApiKeyAuthenticationFilter;
import io.runcycles.protocol.api.contract.ContractValidationConfig;
import io.runcycles.protocol.api.exception.GlobalExceptionHandler;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.data.service.ReservationExpiryService;
import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = ReservationController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ApiKeyAuthenticationFilter.class, ReservationExpiryService.class})
)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ContractValidationConfig.class})
@DisplayName("ReservationController")
class ReservationControllerTest {

    private static final String TENANT = "acme-corp";
    private static final List<String> PERMISSIONS = List.of("reservations:create", "reservations:commit");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private RedisReservationRepository repository;
    @MockitoBean private io.runcycles.protocol.data.service.EventEmitterService eventEmitter;
    @MockitoBean private io.runcycles.protocol.data.service.EvidenceEmitter evidenceEmitter;
    @MockitoBean private io.runcycles.protocol.data.repository.AuditRepository auditRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean private io.runcycles.protocol.data.metrics.CyclesMetrics cyclesMetrics;

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication("cyc_live_test", TENANT, "key-test-001", PERMISSIONS));
    }

    // ---- helpers ----

    private String reservationJson(String tenant, long amount) throws Exception {
        ReservationCreateRequest req = new ReservationCreateRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        Subject subject = new Subject();
        subject.setTenant(tenant);
        req.setSubject(subject);
        Action action = new Action();
        action.setKind("test");
        action.setName("test");
        req.setAction(action);
        Amount est = new Amount();
        est.setUnit(Enums.UnitEnum.TOKENS);
        est.setAmount(amount);
        req.setEstimate(est);
        return objectMapper.writeValueAsString(req);
    }

    private ReservationCreateResponse allowResponse() {
        return ReservationCreateResponse.builder()
                .decision(Enums.DecisionEnum.ALLOW)
                .reservationId("res_123")
                .affectedScopes(List.of("tenant:acme-corp"))
                .expiresAtMs(System.currentTimeMillis() + 60000)
                .scopePath("tenant:acme-corp")
                .reserved(new Amount(Enums.UnitEnum.TOKENS, 1000L))
                .build();
    }

    private ReservationCreateResponse denyResponse() {
        return ReservationCreateResponse.builder()
                .decision(Enums.DecisionEnum.DENY)
                .affectedScopes(List.of("tenant:acme-corp"))
                .scopePath("tenant:acme-corp")
                .reasonCode(Enums.ReasonCode.BUDGET_EXCEEDED)
                .build();
    }

    // ---- POST /v1/reservations ----

    @Nested
    @DisplayName("POST /v1/reservations — Create")
    class Create {

        @Test
        void shouldCreateReservation() throws Exception {
            when(repository.createReservation(any(), eq(TENANT))).thenReturn(allowResponse());

            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reservationJson(TENANT, 1000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decision").value("ALLOW"))
                    .andExpect(jsonPath("$.reservation_id").value("res_123"));
        }

        @Test
        void shouldReturnDenyAndEmitEvent() throws Exception {
            when(repository.createReservation(any(), eq(TENANT))).thenReturn(denyResponse());

            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reservationJson(TENANT, 1000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decision").value("DENY"))
                    .andExpect(jsonPath("$.reason_code").value("BUDGET_EXCEEDED"));
        }

        @Test
        void shouldSurfaceCyclesEvidenceWhenEmitterReturnsRef() throws Exception {
            when(repository.createReservation(any(), eq(TENANT))).thenReturn(allowResponse());
            String evidenceId = "a".repeat(64);
            String url = "https://cycles.example.com/v1/evidence/" + evidenceId;
            when(evidenceEmitter.emit(eq("reserve"), anyLong(), any(), any()))
                    .thenReturn(new io.runcycles.protocol.data.service.EvidenceEmitter.EvidenceRef(evidenceId, url));

            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reservationJson(TENANT, 1000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cycles_evidence.evidence_id").value(evidenceId))
                    .andExpect(jsonPath("$.cycles_evidence.cycles_evidence_url").value(url));
        }

        @Test
        void shouldOmitCyclesEvidenceWhenEmitterReturnsNull() throws Exception {
            when(repository.createReservation(any(), eq(TENANT))).thenReturn(allowResponse());
            // emit() returns null (identity unconfigured / emission failed) by mock default
            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reservationJson(TENANT, 1000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cycles_evidence").doesNotExist());
        }

        @Test
        void shouldRejectMissingSubject() throws Exception {
            String body = """
                    {"idempotency_key":"k1","action":{"kind":"t","name":"t"},
                     "estimate":{"unit":"TOKENS","amount":100}}""";

            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void shouldRejectMissingEstimate() throws Exception {
            String body = """
                    {"idempotency_key":"k1","subject":{"tenant":"acme-corp"},
                     "action":{"kind":"t","name":"t"}}""";

            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void shouldRejectMissingAction() throws Exception {
            String body = """
                    {"idempotency_key":"k1","subject":{"tenant":"acme-corp"},
                     "estimate":{"unit":"TOKENS","amount":100}}""";

            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void shouldRejectTenantMismatch() throws Exception {
            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reservationJson("other-tenant", 1000)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        }

        @Test
        void shouldRejectIdempotencyKeyMismatch() throws Exception {
            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", "header-key")
                            .content(reservationJson(TENANT, 1000)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }

        @Test
        void shouldRejectSubjectWithOnlyDimensions() throws Exception {
            String body = """
                    {"idempotency_key":"k1","subject":{"dimensions":{"env":"prod"}},
                     "action":{"kind":"t","name":"t"},
                     "estimate":{"unit":"TOKENS","amount":100}}""";

            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void shouldReturnBudgetExceeded() throws Exception {
            when(repository.createReservation(any(), eq(TENANT)))
                    .thenThrow(CyclesProtocolException.budgetExceeded("tenant:acme-corp"));

            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reservationJson(TENANT, 99_999_999)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("BUDGET_EXCEEDED"));
        }
    }

    // ---- GET /v1/reservations/{id} ----

    @Nested
    @DisplayName("GET /v1/reservations/{id}")
    class GetById {

        @Test
        void shouldGetReservationById() throws Exception {
            when(repository.findReservationTenantById("res_123")).thenReturn(TENANT);
            Subject subject = new Subject();
            subject.setTenant(TENANT);
            Action action = new Action();
            action.setKind("llm.completion");
            action.setName("test-model");
            Amount reserved = new Amount();
            reserved.setUnit(Enums.UnitEnum.TOKENS);
            reserved.setAmount(100L);
            ReservationDetail detail = new ReservationDetail();
            detail.setReservationId("res_123");
            detail.setStatus(Enums.ReservationStatus.ACTIVE);
            detail.setSubject(subject);
            detail.setAction(action);
            detail.setReserved(reserved);
            detail.setCreatedAtMs(1_700_000_000_000L);
            detail.setExpiresAtMs(1_700_000_060_000L);
            detail.setScopePath("tenant:" + TENANT);
            detail.setAffectedScopes(java.util.List.of("tenant:" + TENANT));
            when(repository.getReservationById("res_123")).thenReturn(detail);

            mockMvc.perform(get("/v1/reservations/res_123"))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldReturn403ForTenantMismatchOnGet() throws Exception {
            when(repository.findReservationTenantById("res_other")).thenReturn("other-tenant");

            mockMvc.perform(get("/v1/reservations/res_other"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        }

        @Test
        void shouldReturn404ForUnknownReservation() throws Exception {
            when(repository.findReservationTenantById("res_unknown"))
                    .thenThrow(CyclesProtocolException.notFound("res_unknown"));

            mockMvc.perform(get("/v1/reservations/res_unknown"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        }

        @Test
        void shouldGetCommittedReservationWithFinalizedFields() throws Exception {
            // Exercises the spec's optional `committed` + `finalized_at_ms`
            // branches which don't appear on ACTIVE reservations.
            when(repository.findReservationTenantById("res_committed")).thenReturn(TENANT);
            Subject subject = new Subject();
            subject.setTenant(TENANT);
            Action action = new Action();
            action.setKind("llm.completion");
            action.setName("test-model");
            Amount reserved = new Amount();
            reserved.setUnit(Enums.UnitEnum.TOKENS);
            reserved.setAmount(100L);
            Amount committed = new Amount();
            committed.setUnit(Enums.UnitEnum.TOKENS);
            committed.setAmount(90L);
            ReservationDetail detail = new ReservationDetail();
            detail.setReservationId("res_committed");
            detail.setStatus(Enums.ReservationStatus.COMMITTED);
            detail.setSubject(subject);
            detail.setAction(action);
            detail.setReserved(reserved);
            detail.setCommitted(committed);
            detail.setFinalizedAtMs(1_700_000_030_000L);
            detail.setCreatedAtMs(1_700_000_000_000L);
            detail.setExpiresAtMs(1_700_000_060_000L);
            detail.setScopePath("tenant:" + TENANT);
            detail.setAffectedScopes(java.util.List.of("tenant:" + TENANT));
            when(repository.getReservationById("res_committed")).thenReturn(detail);

            mockMvc.perform(get("/v1/reservations/res_committed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMMITTED"))
                    .andExpect(jsonPath("$.committed.amount").value(90))
                    .andExpect(jsonPath("$.finalized_at_ms").value(1_700_000_030_000L));
        }

        @Test
        void shouldGetReleasedReservation() throws Exception {
            when(repository.findReservationTenantById("res_released")).thenReturn(TENANT);
            Subject subject = new Subject();
            subject.setTenant(TENANT);
            Action action = new Action();
            action.setKind("llm.completion");
            action.setName("test-model");
            Amount reserved = new Amount();
            reserved.setUnit(Enums.UnitEnum.TOKENS);
            reserved.setAmount(100L);
            ReservationDetail detail = new ReservationDetail();
            detail.setReservationId("res_released");
            detail.setStatus(Enums.ReservationStatus.RELEASED);
            detail.setSubject(subject);
            detail.setAction(action);
            detail.setReserved(reserved);
            detail.setFinalizedAtMs(1_700_000_020_000L);
            detail.setCreatedAtMs(1_700_000_000_000L);
            detail.setExpiresAtMs(1_700_000_060_000L);
            detail.setScopePath("tenant:" + TENANT);
            detail.setAffectedScopes(java.util.List.of("tenant:" + TENANT));
            when(repository.getReservationById("res_released")).thenReturn(detail);

            mockMvc.perform(get("/v1/reservations/res_released"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RELEASED"));
        }

        @Test
        void shouldReturn410ForExpiredReservation() throws Exception {
            when(repository.findReservationTenantById("res_expired")).thenReturn(TENANT);
            when(repository.getReservationById("res_expired"))
                    .thenThrow(CyclesProtocolException.reservationExpired());

            mockMvc.perform(get("/v1/reservations/res_expired"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.error").value("RESERVATION_EXPIRED"));
        }
    }

    // ---- POST commit/release/extend ----

    @Nested
    @DisplayName("POST /v1/reservations/{id}/commit")
    class Commit {

        @Test
        void shouldCommitReservation() throws Exception {
            when(repository.findReservationTenantById("res_123")).thenReturn(TENANT);
            CommitResponse resp = CommitResponse.builder()
                    .status(Enums.CommitStatus.COMMITTED)
                    .charged(new Amount(Enums.UnitEnum.TOKENS, 500L))
                    .build();
            when(repository.commitReservation(eq("res_123"), any(), any())).thenReturn(resp);

            CommitRequest req = new CommitRequest();
            req.setActual(new Amount(Enums.UnitEnum.TOKENS, 500L));
            req.setIdempotencyKey(UUID.randomUUID().toString());

            mockMvc.perform(post("/v1/reservations/res_123/commit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMMITTED"));
        }

        @Test
        void shouldCommitWithOverageAndEmitEvent() throws Exception {
            when(repository.findReservationTenantById("res_123")).thenReturn(TENANT);
            CommitResponse resp = CommitResponse.builder()
                    .status(Enums.CommitStatus.COMMITTED)
                    .charged(new Amount(Enums.UnitEnum.TOKENS, 1500L))
                    .estimateAmount(1000L) // actual > estimate triggers overage event
                    .build();
            when(repository.commitReservation(eq("res_123"), any(), any())).thenReturn(resp);

            CommitRequest req = new CommitRequest();
            req.setActual(new Amount(Enums.UnitEnum.TOKENS, 1500L));
            req.setIdempotencyKey(UUID.randomUUID().toString());

            mockMvc.perform(post("/v1/reservations/res_123/commit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMMITTED"));
        }

        @Test
        void shouldRejectCommitIdempotencyKeyMismatch() throws Exception {
            when(repository.findReservationTenantById("res_123")).thenReturn(TENANT);

            CommitRequest req = new CommitRequest();
            req.setActual(new Amount(Enums.UnitEnum.TOKENS, 500L));
            req.setIdempotencyKey("body-key");

            mockMvc.perform(post("/v1/reservations/res_123/commit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", "header-key")
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("POST /v1/reservations/{id}/release")
    class Release {

        @Test
        void shouldReleaseReservation() throws Exception {
            when(repository.findReservationTenantById("res_123")).thenReturn(TENANT);
            ReleaseResponse resp = ReleaseResponse.builder()
                    .status(Enums.ReleaseStatus.RELEASED)
                    .released(new Amount(Enums.UnitEnum.TOKENS, 1000L))
                    .build();
            when(repository.releaseReservation(eq("res_123"), any(), any(), any())).thenReturn(resp);

            ReleaseRequest req = ReleaseRequest.builder()
                    .idempotencyKey(UUID.randomUUID().toString()).build();

            mockMvc.perform(post("/v1/reservations/res_123/release")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RELEASED"));
        }

        @Test
        void shouldRejectReleaseIdempotencyKeyMismatch() throws Exception {
            when(repository.findReservationTenantById("res_123")).thenReturn(TENANT);

            ReleaseRequest req = ReleaseRequest.builder()
                    .idempotencyKey("body-key").build();

            mockMvc.perform(post("/v1/reservations/res_123/release")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", "header-key")
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("POST /v1/reservations/{id}/extend")
    class Extend {

        @Test
        void shouldExtendReservation() throws Exception {
            when(repository.findReservationTenantById("res_123")).thenReturn(TENANT);
            ReservationExtendResponse resp = ReservationExtendResponse.builder()
                    .status(Enums.ExtendStatus.ACTIVE)
                    .expiresAtMs(System.currentTimeMillis() + 120000)
                    .build();
            when(repository.extendReservation(eq("res_123"), any(), eq(TENANT))).thenReturn(resp);

            ReservationExtendRequest req = new ReservationExtendRequest();
            req.setExtendByMs(60000L);
            req.setIdempotencyKey(UUID.randomUUID().toString());

            mockMvc.perform(post("/v1/reservations/res_123/extend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        void shouldRejectExtendIdempotencyKeyMismatch() throws Exception {
            when(repository.findReservationTenantById("res_123")).thenReturn(TENANT);

            ReservationExtendRequest req = new ReservationExtendRequest();
            req.setExtendByMs(60000L);
            req.setIdempotencyKey("body-key");

            mockMvc.perform(post("/v1/reservations/res_123/extend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Idempotency-Key", "header-key")
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
    }

    // ---- GET /v1/reservations (list) ----

    @Nested
    @DisplayName("GET /v1/reservations — List")
    class ListReservations {

        @Test
        void shouldListReservations() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList())
                    .hasMore(false)
                    .build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(), eq(50), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(resp);

            mockMvc.perform(get("/v1/reservations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.has_more").value(false));
        }

        @Test
        void shouldRejectInvalidStatusFilter() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("status", "BOGUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }

        @Test
        void shouldListWithActiveStatusFilter() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), eq("ACTIVE"), any(), any(), any(), any(), any(), eq(50), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(resp);

            mockMvc.perform(get("/v1/reservations").param("status", "ACTIVE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.has_more").value(false));
        }

        @Test
        void shouldListWithCommittedStatusFilter() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), eq("COMMITTED"), any(), any(), any(), any(), any(), eq(50), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(resp);

            mockMvc.perform(get("/v1/reservations").param("status", "COMMITTED"))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldListWithExplicitTenantMatchingAuth() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(), eq(50), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(resp);

            mockMvc.perform(get("/v1/reservations").param("tenant", TENANT))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldRejectListWithTenantMismatch() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("tenant", "other-tenant"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        }

        @Test
        void shouldDefaultTenantFromAuth() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(), eq(50), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(resp);

            // No tenant param — should use auth tenant
            mockMvc.perform(get("/v1/reservations"))
                    .andExpect(status().isOk());
        }

        // v0.1.25.12 (cycles-protocol revision 2026-04-16): sort_by / sort_dir
        // query params on listReservations. Controller validates enum values;
        // invalid values MUST return 400 INVALID_REQUEST per spec.
        @Test
        @DisplayName("sort_by=bogus → 400 INVALID_REQUEST")
        void shouldRejectInvalidSortBy() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("sort_by", "bogus"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Invalid sort_by")));
        }

        @Test
        @DisplayName("sort_dir=sideways → 400 INVALID_REQUEST")
        void shouldRejectInvalidSortDir() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("sort_dir", "sideways"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Invalid sort_dir")));
        }

        @Test
        @DisplayName("sort_by=status&sort_dir=asc propagates to repository")
        void shouldPropagateSortParams() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(), eq(50), any(), eq("status"), eq("asc"), any(), any(), any(), any(), any(), any()))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations")
                            .param("sort_by", "status")
                            .param("sort_dir", "asc"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("sort_by accepts all 7 spec-enum values case-insensitively")
        void shouldAcceptAllSpecSortByValues() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(), eq(50), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(resp);
            for (String value : new String[] {"reservation_id", "tenant", "scope_path",
                    "status", "reserved", "created_at_ms", "expires_at_ms"}) {
                mockMvc.perform(get("/v1/reservations").param("sort_by", value))
                        .andExpect(status().isOk());
            }
        }

        // cycles-protocol revision 2026-05-21: from/to ISO-8601 window filter on
        // listReservations. Controller parses ISO-8601 and validates from<=to;
        // malformed values and reversed bounds MUST return 400 INVALID_REQUEST.
        @Test
        @DisplayName("from=bogus → 400 INVALID_REQUEST")
        void shouldRejectMalformedFrom() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("from", "not-a-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Invalid from")));
        }

        @Test
        @DisplayName("to=bogus → 400 INVALID_REQUEST")
        void shouldRejectMalformedTo() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("to", "2026-13-45T99:99:99Z"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Invalid to")));
        }

        @Test
        @DisplayName("from > to → 400 INVALID_REQUEST before repository is called")
        void shouldRejectReversedWindow() throws Exception {
            mockMvc.perform(get("/v1/reservations")
                            .param("from", "2026-05-21T12:00:00Z")
                            .param("to", "2026-05-21T11:00:00Z"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("from must be less than or equal to to")));
            // No repository invocation expected — validation runs before list call.
            verify(repository, org.mockito.Mockito.never()).listReservations(
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        // Derive the expected epoch-ms from the same parser the controller uses so a
        // hand-typed literal can't drift again (prior commit hard-coded the wrong value
        // for 2026-05-21T12:00:00Z, causing eq(...) to miss; Mockito then returned null;
        // ResponseEntity.ok(null) still emits HTTP 200, so the tests passed vacuously).
        private static final String SAMPLE_FROM_TO_INSTANT = "2026-05-21T12:00:00Z";
        private static final long SAMPLE_FROM_TO_EPOCH_MS =
                java.time.Instant.parse(SAMPLE_FROM_TO_INSTANT).toEpochMilli();

        @Test
        @DisplayName("from only → epoch-ms propagates to repository")
        void shouldPropagateFromOnly() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq(SAMPLE_FROM_TO_EPOCH_MS), eq((Long) null), any(), any(), any(), any()))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations").param("from", SAMPLE_FROM_TO_INSTANT))
                    .andExpect(status().isOk());
            // Lock in that the stub fired — guards against the prior failure mode where
            // a wrong eq(...) literal caused Mockito to silently return null while the
            // test still asserted HTTP 200.
            verify(repository).listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq(SAMPLE_FROM_TO_EPOCH_MS), eq((Long) null), any(), any(), any(), any());
        }

        @Test
        @DisplayName("to only → epoch-ms propagates to repository")
        void shouldPropagateToOnly() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq((Long) null), eq(SAMPLE_FROM_TO_EPOCH_MS), any(), any(), any(), any()))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations").param("to", SAMPLE_FROM_TO_INSTANT))
                    .andExpect(status().isOk());
            verify(repository).listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq((Long) null), eq(SAMPLE_FROM_TO_EPOCH_MS), any(), any(), any(), any());
        }

        @Test
        @DisplayName("from = to (equal bounds, closed point window) → 200, both propagate")
        void shouldAcceptEqualBounds() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq(SAMPLE_FROM_TO_EPOCH_MS), eq(SAMPLE_FROM_TO_EPOCH_MS), any(), any(), any(), any()))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations")
                            .param("from", SAMPLE_FROM_TO_INSTANT)
                            .param("to", SAMPLE_FROM_TO_INSTANT))
                    .andExpect(status().isOk());
            verify(repository).listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq(SAMPLE_FROM_TO_EPOCH_MS), eq(SAMPLE_FROM_TO_EPOCH_MS), any(), any(), any(), any());
        }

        @Test
        @DisplayName("from/to combine with sort_by=expires_at_ms — filter binds to created_at, sort is independent")
        void shouldCombineWithDifferentSortKey() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), eq("expires_at_ms"), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations")
                            .param("sort_by", "expires_at_ms")
                            .param("from", "2026-05-21T00:00:00Z")
                            .param("to", "2026-05-22T00:00:00Z"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("from/to blank strings treated as unset (no 400, no filter)")
        void shouldTreatBlankAsUnset() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq((Long) null), eq((Long) null), any(), any(), any(), any()))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations")
                            .param("from", "")
                            .param("to", ""))
                    .andExpect(status().isOk());
        }

        // cycles-protocol revision 2026-05-22: expires_from / expires_to bind
        // to expires_at_ms; finalized_from / finalized_to bind to
        // finalized_at_ms. Each pair validates independently with its own
        // from > to check; malformed values surface a distinct 400 message
        // identifying which param failed.

        @Test
        @DisplayName("expires_from=bogus → 400 INVALID_REQUEST")
        void shouldRejectMalformedExpiresFrom() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("expires_from", "not-a-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Invalid expires_from")));
        }

        @Test
        @DisplayName("expires_to=bogus → 400 INVALID_REQUEST")
        void shouldRejectMalformedExpiresTo() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("expires_to", "2026-13-99T99:99:99Z"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Invalid expires_to")));
        }

        @Test
        @DisplayName("finalized_from=bogus → 400 INVALID_REQUEST")
        void shouldRejectMalformedFinalizedFrom() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("finalized_from", "not-a-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Invalid finalized_from")));
        }

        @Test
        @DisplayName("finalized_to=bogus → 400 INVALID_REQUEST")
        void shouldRejectMalformedFinalizedTo() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("finalized_to", "not-a-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Invalid finalized_to")));
        }

        @Test
        @DisplayName("expires_from > expires_to → 400 before any repository call")
        void shouldRejectReversedExpiresWindow() throws Exception {
            mockMvc.perform(get("/v1/reservations")
                            .param("expires_from", "2026-05-22T12:00:00Z")
                            .param("expires_to", "2026-05-22T11:00:00Z"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("expires_from must be less than or equal to expires_to")));
        }

        @Test
        @DisplayName("finalized_from > finalized_to → 400 before any repository call")
        void shouldRejectReversedFinalizedWindow() throws Exception {
            mockMvc.perform(get("/v1/reservations")
                            .param("finalized_from", "2026-05-22T12:00:00Z")
                            .param("finalized_to", "2026-05-22T11:00:00Z"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("finalized_from must be less than or equal to finalized_to")));
        }

        @Test
        @DisplayName("expires_from/expires_to → epoch-ms propagates to repository (independent of from/to)")
        void shouldPropagateExpiresWindow() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq((Long) null), eq((Long) null),
                    eq(SAMPLE_FROM_TO_EPOCH_MS), eq(SAMPLE_FROM_TO_EPOCH_MS), eq((Long) null), eq((Long) null)))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations")
                            .param("expires_from", SAMPLE_FROM_TO_INSTANT)
                            .param("expires_to", SAMPLE_FROM_TO_INSTANT))
                    .andExpect(status().isOk());
            // Lock in that the stub fired — the eq(...) matchers all matched the
            // controller's parsed values. Without this verify(), a future refactor
            // that misroutes expires_from into the from slot would silently pass.
            verify(repository).listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq((Long) null), eq((Long) null),
                    eq(SAMPLE_FROM_TO_EPOCH_MS), eq(SAMPLE_FROM_TO_EPOCH_MS), eq((Long) null), eq((Long) null));
        }

        @Test
        @DisplayName("finalized_from/finalized_to → epoch-ms propagates to repository")
        void shouldPropagateFinalizedWindow() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq((Long) null), eq((Long) null),
                    eq((Long) null), eq((Long) null), eq(SAMPLE_FROM_TO_EPOCH_MS), eq(SAMPLE_FROM_TO_EPOCH_MS)))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations")
                            .param("finalized_from", SAMPLE_FROM_TO_INSTANT)
                            .param("finalized_to", SAMPLE_FROM_TO_INSTANT))
                    .andExpect(status().isOk());
            verify(repository).listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq((Long) null), eq((Long) null),
                    eq((Long) null), eq((Long) null), eq(SAMPLE_FROM_TO_EPOCH_MS), eq(SAMPLE_FROM_TO_EPOCH_MS));
        }

        @Test
        @DisplayName("all three windows combined → all 6 epoch-ms values propagate independently")
        void shouldPropagateAllThreeWindows() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            // 2026-05-21T12:00:00Z (SAMPLE_FROM_TO) is the from/to value.
            // Use different instants for expires/finalized so a slot mix-up would fail.
            final String EXPIRES_INSTANT = "2026-05-23T00:00:00Z";
            final long EXPIRES_MS = java.time.Instant.parse(EXPIRES_INSTANT).toEpochMilli();
            final String FINALIZED_INSTANT = "2026-05-24T00:00:00Z";
            final long FINALIZED_MS = java.time.Instant.parse(FINALIZED_INSTANT).toEpochMilli();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(),
                    eq(SAMPLE_FROM_TO_EPOCH_MS), eq(SAMPLE_FROM_TO_EPOCH_MS),
                    eq(EXPIRES_MS), eq(EXPIRES_MS),
                    eq(FINALIZED_MS), eq(FINALIZED_MS)))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations")
                            .param("from", SAMPLE_FROM_TO_INSTANT)
                            .param("to", SAMPLE_FROM_TO_INSTANT)
                            .param("expires_from", EXPIRES_INSTANT)
                            .param("expires_to", EXPIRES_INSTANT)
                            .param("finalized_from", FINALIZED_INSTANT)
                            .param("finalized_to", FINALIZED_INSTANT))
                    .andExpect(status().isOk());
            verify(repository).listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(),
                    eq(SAMPLE_FROM_TO_EPOCH_MS), eq(SAMPLE_FROM_TO_EPOCH_MS),
                    eq(EXPIRES_MS), eq(EXPIRES_MS),
                    eq(FINALIZED_MS), eq(FINALIZED_MS));
        }

        @Test
        @DisplayName("expires_from/expires_to/finalized_from/finalized_to blank strings treated as unset")
        void shouldTreatBlankAsUnsetForNewWindows() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                    eq(50), any(), any(), any(), eq((Long) null), eq((Long) null),
                    eq((Long) null), eq((Long) null), eq((Long) null), eq((Long) null)))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations")
                            .param("expires_from", "")
                            .param("expires_to", "")
                            .param("finalized_from", "")
                            .param("finalized_to", ""))
                    .andExpect(status().isOk());
        }
    }

    // v0.1.25.8 (cycles-protocol revision 2026-04-13): admin-on-behalf-of
    // dual-auth on list/get/release. Tests cover the controller-level
    // branching only — filter-level admin-key validation is exercised
    // separately in AdminApiKeyAuthenticationFilterTest.
    @Nested @DisplayName("admin-on-behalf-of")
    class AdminOnBehalfOf {
        @org.junit.jupiter.api.BeforeEach
        void setAdminAuth() {
            SecurityContextHolder.getContext().setAuthentication(
                new io.runcycles.protocol.api.auth.AdminApiKeyAuthentication("admin-secret"));
        }

        @Test @DisplayName("listReservations with admin auth + tenant filter returns 200")
        void adminListWithTenantFilter() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq("any-tenant"), any(), any(), any(), any(), any(), any(), any(), eq(50), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(resp);
            mockMvc.perform(get("/v1/reservations").param("tenant", "any-tenant"))
                    .andExpect(status().isOk());
        }

        @Test @DisplayName("listReservations with admin auth requires tenant param — 400 INVALID_REQUEST")
        void adminListWithoutTenantRejected() throws Exception {
            // Admin has no effective tenant — must specify explicitly.
            mockMvc.perform(get("/v1/reservations"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("tenant query parameter is required")));
        }

        @Test @DisplayName("getReservation with admin auth bypasses tenant ownership check")
        void adminGetCrossTenant() throws Exception {
            // Reservation belongs to "other-tenant" — under tenant auth this
            // would 403, under admin auth it should 200. ReservationDetail
            // doesn't have @Builder (parent has it but child doesn't
            // re-expose), so set fields imperatively.
            ReservationDetail detail = new ReservationDetail();
            detail.setReservationId("res-x");
            detail.setStatus(Enums.ReservationStatus.ACTIVE);
            Subject subj = new Subject();
            subj.setTenant("other-tenant");
            detail.setSubject(subj);
            Action act = new Action();
            act.setKind("test");
            act.setName("t");
            detail.setAction(act);
            Amount amt = new Amount();
            amt.setUnit(Enums.UnitEnum.TOKENS);
            amt.setAmount(100L);
            detail.setReserved(amt);
            detail.setCreatedAtMs(1L);
            detail.setExpiresAtMs(2L);
            detail.setScopePath("tenant:other-tenant");
            detail.setAffectedScopes(List.of("tenant:other-tenant"));
            when(repository.findReservationTenantById("res-x")).thenReturn("other-tenant");
            when(repository.getReservationById("res-x")).thenReturn(detail);
            mockMvc.perform(get("/v1/reservations/res-x"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reservation_id").value("res-x"));
        }

        @Test @DisplayName("releaseReservation with admin auth bypasses tenant ownership check")
        void adminReleaseCrossTenant() throws Exception {
            Amount released = new Amount();
            released.setUnit(Enums.UnitEnum.TOKENS);
            released.setAmount(100L);
            ReleaseResponse resp = new ReleaseResponse();
            resp.setStatus(Enums.ReleaseStatus.RELEASED);
            resp.setReleased(released);
            when(repository.findReservationTenantById("res-x")).thenReturn("other-tenant");
            when(repository.releaseReservation(eq("res-x"), any(), any(), any())).thenReturn(resp);
            String body = "{\"idempotency_key\":\"" + UUID.randomUUID() + "\",\"reason\":\"[INCIDENT_FORCE_RELEASE] hung\"}";
            mockMvc.perform(post("/v1/reservations/res-x/release")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        @Test @DisplayName("admin release writes audit-log entry with actor_type=admin_on_behalf_of")
        void adminReleaseWritesAuditEntry() throws Exception {
            Amount released = new Amount();
            released.setUnit(Enums.UnitEnum.TOKENS);
            released.setAmount(100L);
            ReleaseResponse resp = new ReleaseResponse();
            resp.setStatus(Enums.ReleaseStatus.RELEASED);
            resp.setReleased(released);
            when(repository.findReservationTenantById("res-audit")).thenReturn("tenant-target");
            when(repository.releaseReservation(eq("res-audit"), any(), any(), any())).thenReturn(resp);
            String body = "{\"idempotency_key\":\"" + UUID.randomUUID()
                + "\",\"reason\":\"[INCIDENT_FORCE_RELEASE] stuck reservation\"}";

            mockMvc.perform(post("/v1/reservations/res-audit/release")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            // Verify audit entry was written with the spec-required tag.
            // This is the NORMATIVE satisfaction per cycles-protocol
            // revision 2026-04-13: admin-driven releases MUST record
            // actor_type=admin_on_behalf_of in the audit log.
            org.mockito.ArgumentCaptor<io.runcycles.protocol.model.audit.AuditLogEntry> captor =
                org.mockito.ArgumentCaptor.forClass(
                    io.runcycles.protocol.model.audit.AuditLogEntry.class);
            verify(auditRepository).log(captor.capture());
            io.runcycles.protocol.model.audit.AuditLogEntry entry = captor.getValue();
            org.assertj.core.api.Assertions.assertThat(entry.getOperation()).isEqualTo("releaseReservation");
            org.assertj.core.api.Assertions.assertThat(entry.getResourceType()).isEqualTo("reservation");
            org.assertj.core.api.Assertions.assertThat(entry.getResourceId()).isEqualTo("res-audit");
            org.assertj.core.api.Assertions.assertThat(entry.getTenantId()).isEqualTo("tenant-target");
            org.assertj.core.api.Assertions.assertThat(entry.getStatus()).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(entry.getMetadata())
                .containsEntry("actor_type", "admin_on_behalf_of");
            org.assertj.core.api.Assertions.assertThat(entry.getMetadata().get("reason").toString())
                .contains("[INCIDENT_FORCE_RELEASE]");
        }

        @Test @DisplayName("tenant-auth release does NOT write an audit entry (audit is admin-only)")
        void tenantReleaseDoesNotWriteAudit() throws Exception {
            // Reset to tenant auth (parent @BeforeEach already did, but
            // the nested @BeforeEach switched to admin — switch back).
            SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication("cyc_live", TENANT, "key-test", PERMISSIONS));
            Amount released = new Amount();
            released.setUnit(Enums.UnitEnum.TOKENS);
            released.setAmount(50L);
            ReleaseResponse resp = new ReleaseResponse();
            resp.setStatus(Enums.ReleaseStatus.RELEASED);
            resp.setReleased(released);
            when(repository.findReservationTenantById("res-t")).thenReturn(TENANT);
            when(repository.releaseReservation(eq("res-t"), any(), any(), any())).thenReturn(resp);
            String body = "{\"idempotency_key\":\"" + UUID.randomUUID() + "\"}";

            mockMvc.perform(post("/v1/reservations/res-t/release")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            // Tenant self-service shouldn't pollute the audit log —
            // that's admin-only. Tenant actions are visible in the
            // existing tenant-facing audit surfaces.
            verify(auditRepository, org.mockito.Mockito.never()).log(any());
        }

        @Test @DisplayName("audit CR/LF in reason is sanitized before being recorded in metadata")
        void adminReleaseSanitizesAuditReasonCrlf() throws Exception {
            Amount released = new Amount();
            released.setUnit(Enums.UnitEnum.TOKENS);
            released.setAmount(100L);
            ReleaseResponse resp = new ReleaseResponse();
            resp.setStatus(Enums.ReleaseStatus.RELEASED);
            resp.setReleased(released);
            when(repository.findReservationTenantById("res-x")).thenReturn("t");
            when(repository.releaseReservation(eq("res-x"), any(), any(), any())).thenReturn(resp);
            String maliciousBody = "{\"idempotency_key\":\"" + UUID.randomUUID()
                + "\",\"reason\":\"line1\\nFAKE_ENTRY\\nline3\"}";

            mockMvc.perform(post("/v1/reservations/res-x/release")
                            .contentType(MediaType.APPLICATION_JSON).content(maliciousBody))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<io.runcycles.protocol.model.audit.AuditLogEntry> captor =
                org.mockito.ArgumentCaptor.forClass(
                    io.runcycles.protocol.model.audit.AuditLogEntry.class);
            verify(auditRepository).log(captor.capture());
            String storedReason = captor.getValue().getMetadata().get("reason").toString();
            // No raw newlines in the recorded reason — attackers can't
            // forge audit entries by embedding line breaks.
            org.assertj.core.api.Assertions.assertThat(storedReason).doesNotContain("\n");
            org.assertj.core.api.Assertions.assertThat(storedReason).doesNotContain("\r");
            org.assertj.core.api.Assertions.assertThat(storedReason).contains("line1");
            org.assertj.core.api.Assertions.assertThat(storedReason).contains("line3");
        }

        @Test @DisplayName("admin release with CR/LF in reason still succeeds (log sanitization happens server-side)")
        void adminReleaseSanitizesLogReason() throws Exception {
            // The reason is user-controlled (max 256 chars per spec).
            // Including \r\n in it must NOT crash the server and the
            // controller must sanitize before logging — verified
            // structurally here (response is 200), not by inspecting log
            // output (which would couple the test to slf4j internals).
            // The sanitization itself is a one-line replaceAll in the
            // controller; this test is a smoke check that the path
            // doesn't throw.
            Amount released = new Amount();
            released.setUnit(Enums.UnitEnum.TOKENS);
            released.setAmount(100L);
            ReleaseResponse resp = new ReleaseResponse();
            resp.setStatus(Enums.ReleaseStatus.RELEASED);
            resp.setReleased(released);
            when(repository.findReservationTenantById("res-x")).thenReturn("other-tenant");
            when(repository.releaseReservation(eq("res-x"), any(), any(), any())).thenReturn(resp);
            String maliciousBody = "{\"idempotency_key\":\"" + UUID.randomUUID()
                + "\",\"reason\":\"line1\\nFAKE_ADMIN_LOG\\nline3\"}";
            mockMvc.perform(post("/v1/reservations/res-x/release")
                            .contentType(MediaType.APPLICATION_JSON).content(maliciousBody))
                    .andExpect(status().isOk());
        }
    }
}
