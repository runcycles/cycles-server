package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.api.auth.ApiKeyAuthenticationFilter;
import io.runcycles.protocol.api.contract.ContractValidationConfig;
import io.runcycles.protocol.api.exception.GlobalExceptionHandler;
import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.data.repository.EvidenceStoreReader;
import io.runcycles.protocol.data.service.ReservationExpiryService;
import jakarta.servlet.http.HttpServletRequest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EvidenceController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ApiKeyAuthenticationFilter.class, ReservationExpiryService.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ContractValidationConfig.class})
class EvidenceControllerTest {

    private static final String VALID_ID = "a".repeat(64);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private EvidenceStoreReader store;
    // This project's @WebMvcTest loads all controllers (ContractValidationConfig),
    // so the other controllers' collaborators must be present as mocks too.
    @MockitoBean private io.runcycles.protocol.data.repository.RedisReservationRepository reservationRepository;
    @MockitoBean private io.runcycles.protocol.data.service.EventEmitterService eventEmitter;
    @MockitoBean private io.runcycles.protocol.data.repository.AuditRepository auditRepository;
    @MockitoBean private io.runcycles.protocol.data.metrics.CyclesMetrics cyclesMetrics;
    @MockitoBean private io.runcycles.protocol.data.service.EvidenceEmitter evidenceEmitter;

    @Test
    void returns200WithVerbatimEnvelopeAndImmutableCache() throws Exception {
        // A complete cycles-evidence/v0.1 envelope (all required fields) so the
        // contract-validating MockMvc accepts the 200 body against the schema.
        String envelope = "{"
                + "\"schema_version\":\"cycles-evidence/v0.1\","
                + "\"artifact_type\":\"reserve\","
                + "\"server_id\":\"https://cycles.example.com/v1\","
                + "\"signer_did\":\"" + "b".repeat(64) + "\","
                + "\"issued_at_ms\":1810000000100,"
                + "\"payload\":{\"reserve\":{}},"
                + "\"evidence_id\":\"" + VALID_ID + "\","
                + "\"signature\":\"" + "c".repeat(128) + "\"}";
        when(store.get(VALID_ID)).thenReturn(envelope);

        mockMvc.perform(get("/v1/evidence/" + VALID_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", Matchers.containsString("immutable")))
                .andExpect(jsonPath("$.evidence_id").value(VALID_ID))
                .andExpect(jsonPath("$.artifact_type").value("reserve"));
    }

    @Test
    void returns404WhenEnvelopeAbsent() throws Exception {
        when(store.get(VALID_ID)).thenReturn(null);

        mockMvc.perform(get("/v1/evidence/" + VALID_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void returns400OnMalformedEvidenceId() throws Exception {
        mockMvc.perform(get("/v1/evidence/not-a-valid-content-hash"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolvesRequestIdDefensively() {
        EvidenceController controller = new EvidenceController(store);
        HttpServletRequest requestWithoutId = mock(HttpServletRequest.class);
        HttpServletRequest requestWithId = mock(HttpServletRequest.class);
        when(requestWithId.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).thenReturn(1234L);

        String nullRequestId = ReflectionTestUtils.invokeMethod(
                controller, "resolveRequestId", (HttpServletRequest) null);
        String missingRequestId = ReflectionTestUtils.invokeMethod(
                controller, "resolveRequestId", requestWithoutId);
        String resolvedRequestId = ReflectionTestUtils.invokeMethod(
                controller, "resolveRequestId", requestWithId);

        assertThat(nullRequestId).isNull();
        assertThat(missingRequestId).isNull();
        assertThat(resolvedRequestId).isEqualTo("1234");
    }
}
