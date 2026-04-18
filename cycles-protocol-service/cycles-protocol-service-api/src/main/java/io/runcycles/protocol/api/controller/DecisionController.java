package io.runcycles.protocol.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.data.service.EventEmitterService;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.event.*;

import java.util.Map;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Cycles Protocol v0.1.25 - Decision Controller */
@RestController
@RequestMapping("/v1/decide")
@Tag(name = "Decisions")
@Validated
public class DecisionController extends BaseController {
    private static final Logger LOG = LoggerFactory.getLogger(DecisionController.class);

    @Autowired
    private RedisReservationRepository repository;

    @Autowired
    private EventEmitterService eventEmitter;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping
    @Operation(operationId = "decide", summary = "Evaluate budget decision without reserving")
    public ResponseEntity<DecisionResponse> decide(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody DecisionRequest request,
            HttpServletRequest httpRequest) {
        LOG.info("POST /v1/decide - tenant: {}", request.getSubject().getTenant());
        validateSubject(request.getSubject());
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        // Spec: validate subject.tenant against auth, but effective tenant always comes from auth context
        authorizeTenant(request.getSubject().getTenant());
        String tenant = extractAuthTenantId();
        DecisionResponse response = repository.decide(request, tenant);
        try {
            if (response.getDecision() == Enums.DecisionEnum.DENY) {
                String scope = response.getAffectedScopes() != null && !response.getAffectedScopes().isEmpty()
                        ? response.getAffectedScopes().get(0) : null;
                Actor actor = buildActor(httpRequest);
                Map<String, Object> actionMap = objectMapper.convertValue(request.getAction(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                Map<String, Object> subjectMap = objectMapper.convertValue(request.getSubject(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                eventEmitter.emit(EventType.RESERVATION_DENIED, tenant, scope,
                        actor,
                        EventDataReservationDenied.builder()
                                .scope(scope)
                                .unit(request.getEstimate() != null
                                        ? request.getEstimate().getUnit().name() : null)
                                .reasonCode(response.getReasonCode() != null
                                        ? response.getReasonCode().name() : null)
                                .requestedAmount(request.getEstimate() != null
                                        ? request.getEstimate().getAmount() : null)
                                .remaining(null)
                                .action(actionMap)
                                .subject(subjectMap)
                                .build(),
                        null,
                        resolveRequestId(httpRequest),
                        resolveTraceContext(httpRequest));
            }
        } catch (Exception e) { /* non-blocking */ }
        return ResponseEntity.ok(response);
    }
}
