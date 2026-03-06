package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.model.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Cycles Protocol v0.1.23 - Decision Controller */
@RestController
@RequestMapping("/v1/decide")
@Tag(name = "Decisions")
public class DecisionController extends BaseController {
    private static final Logger LOG = LoggerFactory.getLogger(DecisionController.class);

    @Autowired
    private RedisReservationRepository repository;

    @PostMapping
    @Operation(operationId = "decide", summary = "Evaluate budget decision without reserving")
    public ResponseEntity<DecisionResponse> decide(@Valid @RequestBody DecisionRequest request) {
        LOG.info("POST /v1/decide - tenant: {}", request.getSubject().getTenant());
        String tenant = request.getSubject().getTenant();
        authorizeTenant(tenant);
        DecisionResponse response = repository.decide(request, tenant);
        return ResponseEntity.ok(response);
    }
}
