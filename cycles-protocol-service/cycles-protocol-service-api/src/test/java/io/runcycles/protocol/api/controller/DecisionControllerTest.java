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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = DecisionController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ApiKeyAuthenticationFilter.class, ReservationExpiryService.class})
)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ContractValidationConfig.class})
@DisplayName("DecisionController")
class DecisionControllerTest {

    private static final String TENANT = "acme-corp";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private RedisReservationRepository repository;
    @MockitoBean private io.runcycles.protocol.data.service.EventEmitterService eventEmitter;

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication("cyc_live_test", TENANT, "key-test-001", List.of("reservations:create")));
    }

    private String decideJson(String tenant) throws Exception {
        return objectMapper.writeValueAsString(DecisionRequest.builder()
                .idempotencyKey("idem-1")
                .subject(new Subject(tenant, null, null, null, null, null, null))
                .action(new Action("test", "test", null))
                .estimate(new Amount(Enums.UnitEnum.TOKENS, 1000L))
                .build());
    }

    @Test
    void shouldReturnDecision() throws Exception {
        DecisionResponse resp = DecisionResponse.builder()
                .decision(Enums.DecisionEnum.ALLOW)
                .affectedScopes(List.of("tenant:acme-corp"))
                .build();
        when(repository.decide(any(), eq(TENANT))).thenReturn(resp);

        mockMvc.perform(post("/v1/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decideJson(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"));
    }

    @Test
    void shouldRejectMissingSubject() throws Exception {
        String body = """
                {"idempotency_key":"k1","action":{"kind":"t","name":"t"},
                 "estimate":{"unit":"TOKENS","amount":100}}""";

        mockMvc.perform(post("/v1/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectTenantMismatch() throws Exception {
        mockMvc.perform(post("/v1/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decideJson("other-tenant")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }
}
