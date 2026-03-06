package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.model.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Cycles Protocol v0.1.23 - Event Controller */
@RestController
@RequestMapping("/v1/events")
@Tag(name = "Events")
public class EventController extends BaseController {
    private static final Logger LOG = LoggerFactory.getLogger(EventController.class);

    @Autowired
    private RedisReservationRepository repository;

    @PostMapping
    @Operation(operationId = "createEvent", summary = "Record a direct debit event without reservation")
    public ResponseEntity<EventCreateResponse> create(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody EventCreateRequest request) {
        LOG.info("POST /v1/events - tenant: {}", request.getSubject().getTenant());
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        String tenant = request.getSubject().getTenant();
        authorizeTenant(tenant);
        EventCreateResponse response = repository.createEvent(request, tenant);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
