package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.api.auth.ApiKeyAuthentication;
import io.runcycles.protocol.api.auth.ApiKeyAuthenticationFilter;
import io.runcycles.protocol.api.exception.GlobalExceptionHandler;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.data.service.ReservationExpiryService;
import io.runcycles.protocol.model.BalanceResponse;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = BalanceController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ApiKeyAuthenticationFilter.class, ReservationExpiryService.class})
)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("BalanceController")
class BalanceControllerTest {

    private static final String TENANT = "acme-corp";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RedisReservationRepository repository;
    @MockitoBean private io.runcycles.protocol.data.service.EventEmitterService eventEmitter;

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication("cyc_live_test", TENANT, "key-test-001", List.of("balances:read")));
    }

    @Test
    void shouldReturnBalances() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList())
                .hasMore(false)
                .build();
        when(repository.getBalances(eq(TENANT), any(), any(), any(), any(), any(), anyBoolean(), anyInt(), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/v1/balances").param("tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances").isArray());
    }

    @Test
    void shouldRejectMissingSubjectFilter() throws Exception {
        mockMvc.perform(get("/v1/balances"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void shouldRejectTenantMismatch() throws Exception {
        mockMvc.perform(get("/v1/balances").param("tenant", "other-tenant"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void shouldDefaultTenantFromAuthWhenOmitted() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList()).hasMore(false).build();
        when(repository.getBalances(eq(TENANT), any(), any(), any(), any(), any(), anyBoolean(), anyInt(), any()))
                .thenReturn(resp);

        // workspace provided, no tenant — should use auth tenant
        mockMvc.perform(get("/v1/balances").param("workspace", "dev"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldQueryWithWorkspaceFilterOnly() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList()).hasMore(false).build();
        when(repository.getBalances(eq(TENANT), eq("ws1"), any(), any(), any(), any(), anyBoolean(), anyInt(), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/v1/balances").param("workspace", "ws1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances").isArray());
    }

    @Test
    void shouldQueryWithAppFilter() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList()).hasMore(false).build();
        when(repository.getBalances(eq(TENANT), any(), eq("my-app"), any(), any(), any(), anyBoolean(), anyInt(), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/v1/balances").param("app", "my-app"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldQueryWithWorkflowFilter() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList()).hasMore(false).build();
        when(repository.getBalances(eq(TENANT), any(), any(), eq("wf-1"), any(), any(), anyBoolean(), anyInt(), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/v1/balances").param("workflow", "wf-1"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldQueryWithAgentFilter() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList()).hasMore(false).build();
        when(repository.getBalances(eq(TENANT), any(), any(), any(), eq("agent-x"), any(), anyBoolean(), anyInt(), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/v1/balances").param("agent", "agent-x"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldQueryWithToolsetFilter() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList()).hasMore(false).build();
        when(repository.getBalances(eq(TENANT), any(), any(), any(), any(), eq("tools-1"), anyBoolean(), anyInt(), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/v1/balances").param("toolset", "tools-1"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldQueryWithIncludeChildrenTrue() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList()).hasMore(false).build();
        when(repository.getBalances(eq(TENANT), any(), any(), any(), any(), any(), eq(true), anyInt(), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/v1/balances")
                        .param("tenant", TENANT)
                        .param("include_children", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldQueryWithCustomLimit() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList()).hasMore(false).build();
        when(repository.getBalances(eq(TENANT), any(), any(), any(), any(), any(), anyBoolean(), eq(10), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/v1/balances")
                        .param("tenant", TENANT)
                        .param("limit", "10"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldQueryWithCursorParameter() throws Exception {
        BalanceResponse resp = BalanceResponse.builder()
                .balances(Collections.emptyList()).hasMore(false).build();
        when(repository.getBalances(eq(TENANT), any(), any(), any(), any(), any(), anyBoolean(), anyInt(), eq("cursor_abc")))
                .thenReturn(resp);

        mockMvc.perform(get("/v1/balances")
                        .param("tenant", TENANT)
                        .param("cursor", "cursor_abc"))
                .andExpect(status().isOk());
    }
}
