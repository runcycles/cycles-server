package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.BalanceQueryResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Cycles Protocol v0.1.23 - Balance Controller */
@RestController
@RequestMapping("/v1/balances")
@Tag(name = "Balances")
public class BalanceController extends BaseController{
    private static final Logger LOG = LoggerFactory.getLogger(BalanceController.class);
    
    @Autowired
    private RedisReservationRepository repository;
    
    @GetMapping
    @Operation(operationId = "queryBalances", summary = "Query budget balances")
    public ResponseEntity<BalanceQueryResponse> query(
            @RequestParam(required = true) String tenant) {
        LOG.info("GET /v1/balances - tenant: {}", tenant);
        authorizeTenant(tenant);
        return ResponseEntity.ok(new BalanceQueryResponse(repository.getBalances(tenant)));
    }
}
