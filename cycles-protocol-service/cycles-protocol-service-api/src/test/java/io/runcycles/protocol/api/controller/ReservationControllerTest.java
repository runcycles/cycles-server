package io.runcycles.protocol.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.api.auth.ApiKeyAuthentication;
import io.runcycles.protocol.api.auth.ApiKeyAuthenticationFilter;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = ReservationController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ApiKeyAuthenticationFilter.class, ReservationExpiryService.class})
)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("ReservationController")
class ReservationControllerTest {

    private static final String TENANT = "acme-corp";
    private static final List<String> PERMISSIONS = List.of("reservations:create", "reservations:commit");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private RedisReservationRepository repository;
    @MockitoBean private io.runcycles.protocol.data.service.EventEmitterService eventEmitter;

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
            ReservationDetail detail = new ReservationDetail();
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
            when(repository.commitReservation(eq("res_123"), any())).thenReturn(resp);

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
            when(repository.commitReservation(eq("res_123"), any())).thenReturn(resp);

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
            when(repository.releaseReservation(eq("res_123"), any())).thenReturn(resp);

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
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(), eq(50), any()))
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
            when(repository.listReservations(eq(TENANT), any(), eq("ACTIVE"), any(), any(), any(), any(), any(), eq(50), any()))
                    .thenReturn(resp);

            mockMvc.perform(get("/v1/reservations").param("status", "ACTIVE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.has_more").value(false));
        }

        @Test
        void shouldListWithCommittedStatusFilter() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), eq("COMMITTED"), any(), any(), any(), any(), any(), eq(50), any()))
                    .thenReturn(resp);

            mockMvc.perform(get("/v1/reservations").param("status", "COMMITTED"))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldListWithExplicitTenantMatchingAuth() throws Exception {
            ReservationListResponse resp = ReservationListResponse.builder()
                    .reservations(Collections.emptyList()).hasMore(false).build();
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(), eq(50), any()))
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
            when(repository.listReservations(eq(TENANT), any(), any(), any(), any(), any(), any(), any(), eq(50), any()))
                    .thenReturn(resp);

            // No tenant param — should use auth tenant
            mockMvc.perform(get("/v1/reservations"))
                    .andExpect(status().isOk());
        }
    }
}
