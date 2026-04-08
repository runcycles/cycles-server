package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.data.service.EventEmitterService;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.event.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Cycles Protocol v0.1.25 - Event Controller */
@RestController
@RequestMapping("/v1/events")
@Tag(name = "Events")
@Validated
public class EventController extends BaseController {
    private static final Logger LOG = LoggerFactory.getLogger(EventController.class);

    @Autowired
    private RedisReservationRepository repository;

    @Autowired
    private EventEmitterService eventEmitter;

    @PostMapping
    @Operation(operationId = "createEvent", summary = "Record a direct debit event without reservation")
    public ResponseEntity<EventCreateResponse> create(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody EventCreateRequest request,
            HttpServletRequest httpRequest) {
        LOG.info("POST /v1/events - tenant: {}", request.getSubject().getTenant());
        validateSubject(request.getSubject());
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        // Spec: validate subject.tenant against auth, but effective tenant always comes from auth context
        authorizeTenant(request.getSubject().getTenant());
        String tenant = extractAuthTenantId();
        EventCreateResponse response = repository.createEvent(request, tenant);
        try {
            Actor actor = buildActor(httpRequest);
            String policy = request.getOveragePolicy() != null
                    ? request.getOveragePolicy().name() : "ALLOW_IF_AVAILABLE";
            eventEmitter.emitBalanceEvents(response.getBalances(), tenant, actor,
                    null, policy, response.getScopeDebtIncurred(), null, null);
        } catch (Exception e) { /* non-blocking */ }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
