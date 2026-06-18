package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.api.auth.ApiKeyAuthenticationFilter;
import io.runcycles.protocol.api.contract.ContractValidationConfig;
import io.runcycles.protocol.api.exception.GlobalExceptionHandler;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.service.ReservationExpiryService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JwksController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ApiKeyAuthenticationFilter.class, ReservationExpiryService.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ContractValidationConfig.class})
@TestPropertySource(properties = {
        "cycles.evidence.signing.signer-did=" + JwksControllerTest.SIGNER_DID,
        "cycles.evidence.signing.kid=2026-06",
        "cycles.evidence.signing.nbf-ms=1810000000000"
})
class JwksControllerTest {

    static final String SIGNER_DID =
            "207a067892821e25d770f1fba0c47c11ff4b813e54162ece9eb839e076231ab6";

    @Autowired private MockMvc mockMvc;
    // This project's @WebMvcTest loads all controllers (ContractValidationConfig),
    // so the other controllers' collaborators must be present as mocks too.
    @MockitoBean private io.runcycles.protocol.data.repository.EvidenceStoreReader store;
    @MockitoBean private io.runcycles.protocol.data.repository.RedisReservationRepository reservationRepository;
    @MockitoBean private io.runcycles.protocol.data.service.EventEmitterService eventEmitter;
    @MockitoBean private io.runcycles.protocol.data.repository.AuditRepository auditRepository;
    @MockitoBean private io.runcycles.protocol.data.metrics.CyclesMetrics cyclesMetrics;
    @MockitoBean private io.runcycles.protocol.data.service.EvidenceEmitter evidenceEmitter;

    @Test
    void returns200WithJwkSetAndShortPublicCache() throws Exception {
        mockMvc.perform(get("/v1/.well-known/cycles-jwks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // a key set MUST be cacheable but NOT immutable (it rotates)
                .andExpect(header().string("Cache-Control", Matchers.containsString("max-age")))
                .andExpect(header().string("Cache-Control", Matchers.containsString("public")))
                .andExpect(header().string("Cache-Control", Matchers.not(Matchers.containsString("immutable"))))
                .andExpect(jsonPath("$.keys[0].kty").value("OKP"))
                .andExpect(jsonPath("$.keys[0].crv").value("Ed25519"))
                .andExpect(jsonPath("$.keys[0].alg").value("EdDSA"))
                .andExpect(jsonPath("$.keys[0].x").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].kid").value("2026-06"))
                .andExpect(jsonPath("$.keys[0].cycles_nbf_ms").value(1810000000000L))
                .andExpect(jsonPath("$.keys[0].status").value("active"));
    }

    @Test
    void unconfiguredSigner_throwsNotFound() {
        // Direct construction (no key configured) — the endpoint 404s via the
        // standard NOT_FOUND ErrorResponse path; a server not doing signer-key
        // resolution publishes nothing.
        JwksController controller = new JwksController("", "", 0L, "");
        assertThatThrownBy(controller::getEvidenceJwks)
                .isInstanceOf(CyclesProtocolException.class);
    }

    @Test
    void didCyclesSigner_throwsNotFound() {
        JwksController controller = new JwksController(
                "did:cycles:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08#k", "", 0L, "");
        assertThatThrownBy(controller::getEvidenceJwks)
                .isInstanceOf(CyclesProtocolException.class);
    }

    @Test
    void configuredSigner_returnsBodyDirectly() {
        JwksController controller = new JwksController(SIGNER_DID, "k1", 5L, "");
        assertThat(controller.getEvidenceJwks().getStatusCode().value()).isEqualTo(200);
        assertThat(controller.getEvidenceJwks().getBody()).containsKey("keys");
    }

    @Test
    @SuppressWarnings("unchecked")
    void retiredKeysJson_publishesActivePlusRetiredWithWindows() {
        String retired = "[{\"signer_did\":\"" + "ab".repeat(32) + "\",\"kid\":\"2025-h2\","
                + "\"nbf_ms\":0,\"exp_ms\":1700000000000}]";
        JwksController controller = new JwksController(SIGNER_DID, "2026-06", 1700000000000L, retired);
        Map<String, Object> body = controller.getEvidenceJwks().getBody();
        List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).containsEntry("kid", "2026-06").containsEntry("status", "active");
        assertThat(keys.get(0)).doesNotContainKey("cycles_exp_ms"); // active = open-ended
        assertThat(keys.get(1)).containsEntry("kid", "2025-h2").containsEntry("status", "retired")
                .containsEntry("cycles_exp_ms", 1700000000000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedRetiredKeysJson_stillPublishesActiveKey() {
        // A bad retired-keys config must never break publication of the active key.
        JwksController controller = new JwksController(SIGNER_DID, "2026-06", 0L, "{not valid json");
        Map<String, Object> body = controller.getEvidenceJwks().getBody();
        assertThat((List<Map<String, Object>>) body.get("keys")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retiredKeyWithMissingNbf_isSkipped() {
        // A missing/non-integral nbf_ms must NOT be coerced to epoch 0 (which would
        // silently widen the window) — the retired entry is dropped, active still publishes.
        String retired = "[{\"signer_did\":\"" + "ab".repeat(32) + "\",\"kid\":\"no-nbf\",\"exp_ms\":100}]";
        JwksController controller = new JwksController(SIGNER_DID, "2026-06", 0L, retired);
        Map<String, Object> body = controller.getEvidenceJwks().getBody();
        assertThat((List<Map<String, Object>>) body.get("keys")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonArrayRetiredKeysJson_isIgnored() {
        // Valid JSON but not an array → ignored, active key still published.
        JwksController controller = new JwksController(SIGNER_DID, "2026-06", 0L, "{\"oops\":true}");
        Map<String, Object> body = controller.getEvidenceJwks().getBody();
        assertThat((List<Map<String, Object>>) body.get("keys")).hasSize(1);
    }
}
