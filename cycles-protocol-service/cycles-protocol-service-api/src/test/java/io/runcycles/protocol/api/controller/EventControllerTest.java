package io.runcycles.protocol.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.api.auth.ApiKeyAuthentication;
import io.runcycles.protocol.api.auth.ApiKeyAuthenticationFilter;
import io.runcycles.protocol.api.contract.ContractValidationConfig;
import io.runcycles.protocol.api.exception.GlobalExceptionHandler;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.data.service.ReservationExpiryService;
import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = EventController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ApiKeyAuthenticationFilter.class, ReservationExpiryService.class})
)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ContractValidationConfig.class})
@DisplayName("EventController")
class EventControllerTest {

    private static final String TENANT = "acme-corp";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private RedisReservationRepository repository;
    @MockitoBean private io.runcycles.protocol.data.service.EventEmitterService eventEmitter;
    @org.springframework.test.context.bean.override.mockito.MockitoBean private io.runcycles.protocol.data.metrics.CyclesMetrics cyclesMetrics;
    @org.springframework.test.context.bean.override.mockito.MockitoBean private io.runcycles.protocol.data.repository.EvidenceStoreReader evidenceStoreReader;
    @org.springframework.test.context.bean.override.mockito.MockitoBean private io.runcycles.protocol.data.service.EvidenceEmitter evidenceEmitter;

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication("cyc_live_test", TENANT, "key-test-001", List.of("reservations:create")));
    }

    private String eventJson(String tenant) throws Exception {
        return objectMapper.writeValueAsString(EventCreateRequest.builder()
                .idempotencyKey("ev-1")
                .subject(new Subject(tenant, null, null, null, null, null, null))
                .action(new Action("tool", "search", null))
                .actual(new Amount(Enums.UnitEnum.TOKENS, 500L))
                .build());
    }

    @Test
    void shouldCreateEvent() throws Exception {
        EventCreateResponse resp = EventCreateResponse.builder()
                .status(Enums.EventStatus.APPLIED)
                .eventId("evt_123")
                .build();
        when(repository.createEvent(any(), eq(TENANT))).thenReturn(resp);

        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(TENANT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.event_id").value("evt_123"));
    }

    @Test
    void shouldRejectMissingFields() throws Exception {
        String body = """
                {"idempotency_key":"k1","subject":{"tenant":"acme-corp"}}""";

        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn409TenantClosed() throws Exception {
        // Governance Rule 2 / runtime spec v0.1.25.14 (pending): a fresh
        // /v1/events debit on a CLOSED owning tenant is rejected 409
        // TENANT_CLOSED. On the wire this is only reachable via the post-flip
        // race (the tenant-key auth filter 401s a durably-closed tenant first,
        // and /v1/events has no admin-key path); this pins the controller ->
        // GlobalExceptionHandler -> 409 mapping for that residual case AND the
        // evidence-endpoint-gate exclusion: this goes through the REAL
        // GlobalExceptionHandler (which sees the matched /v1/events route), so
        // maybeEmitErrorEvidence passes the denial-code check (TENANT_CLOSED is
        // in EVIDENCE_DENIAL_CODES) then returns early at the endpoint gate
        // (/v1/events not in EVIDENCE_ENDPOINTS) — no evidence stamped. A
        // repository-level test can't prove this; the handler must be entered.
        when(repository.createEvent(any(), eq(TENANT)))
                .thenThrow(io.runcycles.protocol.data.exception.CyclesProtocolException.tenantClosed(TENANT));

        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(TENANT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TENANT_CLOSED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(TENANT)))
                .andExpect(jsonPath("$.request_id").exists())
                // /v1/events is outside EVIDENCE_ENDPOINTS: no cycles_evidence ref.
                .andExpect(jsonPath("$.cycles_evidence").doesNotExist());

        // Proves the endpoint-gate exclusion through the real handler path:
        // the evidence emitter is never invoked for a /v1/events denial.
        verifyNoInteractions(evidenceEmitter);
    }

    @Test
    void shouldRejectTenantMismatch() throws Exception {
        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("other-tenant")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void shouldIncludeChargedWhenCapped() throws Exception {
        EventCreateResponse resp = EventCreateResponse.builder()
                .status(Enums.EventStatus.APPLIED)
                .eventId("evt_456")
                .charged(new Amount(Enums.UnitEnum.TOKENS, 200L))
                .build();
        when(repository.createEvent(any(), eq(TENANT))).thenReturn(resp);

        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(TENANT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.charged.amount").value(200))
                .andExpect(jsonPath("$.charged.unit").value("TOKENS"));
    }

    @Test
    void shouldOmitChargedWhenNotCapped() throws Exception {
        EventCreateResponse resp = EventCreateResponse.builder()
                .status(Enums.EventStatus.APPLIED)
                .eventId("evt_789")
                .build();
        when(repository.createEvent(any(), eq(TENANT))).thenReturn(resp);

        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(TENANT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.charged").doesNotExist());
    }

    @Test
    void shouldNotEmitBalanceEventsOnIdempotentReplay() throws Exception {
        EventCreateResponse resp = EventCreateResponse.builder()
                .status(Enums.EventStatus.APPLIED)
                .eventId("evt_replay")
                .idempotentReplay(true)
                .build();
        when(repository.createEvent(any(), eq(TENANT))).thenReturn(resp);

        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(TENANT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.event_id").value("evt_replay"));

        verifyNoInteractions(eventEmitter);
    }
}
