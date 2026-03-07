package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.BalanceResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Cycles Protocol v0.1.23 - Balance Controller */
@RestController
@RequestMapping("/v1/balances")
@Tag(name = "Balances")
@Validated
public class BalanceController extends BaseController{
    private static final Logger LOG = LoggerFactory.getLogger(BalanceController.class);

    @Autowired
    private RedisReservationRepository repository;

    @GetMapping
    @Operation(operationId = "getBalances", summary = "Query budget balances")
    public ResponseEntity<BalanceResponse> query(
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String workspace,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String workflow,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String toolset,
            @RequestParam(value = "include_children", required = false, defaultValue = "false") boolean includeChildren,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        // Spec NORMATIVE: at least one subject filter must be provided
        if (tenant == null && workspace == null && app == null && workflow == null && agent == null && toolset == null) {
            throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                "At least one of tenant/workspace/app/workflow/agent/toolset must be provided", 400);
        }
        // If tenant provided, it must match auth context; if omitted, use auth tenant
        String effectiveTenant = tenant != null ? tenant : extractAuthTenantId();
        LOG.info("GET /v1/balances - tenant: {}", effectiveTenant);
        authorizeTenant(effectiveTenant);
        BalanceResponse response = repository.getBalances(effectiveTenant, workspace, app, workflow, agent, toolset, includeChildren, limit, cursor);
        return ResponseEntity.ok(response);
    }
}
