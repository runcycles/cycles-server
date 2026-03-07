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
    public ResponseEntity<DecisionResponse> decide(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody DecisionRequest request) {
        LOG.info("POST /v1/decide - tenant: {}", request.getSubject().getTenant());
        validateSubject(request.getSubject());
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        String tenant = request.getSubject().getTenant();
        authorizeTenant(tenant);
        DecisionResponse response = repository.decide(request, tenant);
        // Spec: /decide 200 response includes X-RateLimit-Remaining and X-RateLimit-Reset (optional in v0)
        return ResponseEntity.ok()
            .header("X-RateLimit-Remaining", "-1")
            .header("X-RateLimit-Reset", "0")
            .body(response);
    }
}
