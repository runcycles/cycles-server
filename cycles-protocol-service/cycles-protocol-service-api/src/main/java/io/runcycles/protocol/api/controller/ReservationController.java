package io.runcycles.protocol.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.data.service.EventEmitterService;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.event.*;

import java.util.Map;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Cycles Protocol v0.1.25 - Reservation Controller */
@RestController
@RequestMapping("/v1/reservations")
@Tag(name = "Reservations")
@Validated
public class ReservationController extends BaseController{
    private static final Logger LOG = LoggerFactory.getLogger(ReservationController.class);

    @Autowired
    private RedisReservationRepository repository;

    @Autowired
    private EventEmitterService eventEmitter;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping
    @Operation(operationId = "createReservation", summary = "Create budget reservation")
    public ResponseEntity<ReservationCreateResponse> create(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody ReservationCreateRequest request,
            HttpServletRequest httpRequest) {
        LOG.info("POST /v1/reservations - tenant: {}", request.getSubject().getTenant());
        validateSubject(request.getSubject());
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        // Spec: validate subject.tenant against auth, but effective tenant always comes from auth context
        authorizeTenant(request.getSubject().getTenant());
        String tenant = extractAuthTenantId();
        ReservationCreateResponse response = repository.createReservation(request, tenant);
        try {
            Actor actor = buildActor(httpRequest);
            if (response.getDecision() == Enums.DecisionEnum.DENY) {
                // Derive remaining from balances if available
                Long remaining = null;
                if (response.getBalances() != null && !response.getBalances().isEmpty()) {
                    remaining = response.getBalances().stream()
                            .filter(b -> b.getRemaining() != null && b.getRemaining().getAmount() != null)
                            .mapToLong(b -> b.getRemaining().getAmount())
                            .min().orElse(0L);
                }
                Map<String, Object> actionMap = objectMapper.convertValue(request.getAction(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                Map<String, Object> subjectMap = objectMapper.convertValue(request.getSubject(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                eventEmitter.emit(EventType.RESERVATION_DENIED, tenant, response.getScopePath(),
                        actor,
                        EventDataReservationDenied.builder()
                                .scope(response.getScopePath())
                                .unit(request.getEstimate() != null
                                        ? request.getEstimate().getUnit().name() : null)
                                .reasonCode(response.getReasonCode())
                                .requestedAmount(request.getEstimate() != null
                                        ? request.getEstimate().getAmount() : null)
                                .remaining(remaining)
                                .action(actionMap)
                                .subject(subjectMap)
                                .build(),
                        null, null);
            }
            // Emit budget state events from post-operation balances
            eventEmitter.emitBalanceEvents(response.getBalances(), tenant, actor, null, null);
        } catch (Exception e) { /* non-blocking */ }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{reservation_id}")
    @Operation(operationId = "getReservation", summary = "Get reservation by ID")
    public ResponseEntity<ReservationDetail> get(
            @PathVariable("reservation_id") @Size(min = 1, max = 128) String reservationId) {
        LOG.info("GET /v1/reservations/{}", reservationId);
        String tenant = repository.findReservationTenantById(reservationId);
        authorizeTenant(tenant);
        ReservationDetail detail = repository.getReservationById(reservationId);
        return ResponseEntity.ok(detail);
    }

    @PostMapping("/{reservation_id}/commit")
    @Operation(operationId = "commitReservation", summary = "Commit actual spend")
    public ResponseEntity<CommitResponse> commit(
            @PathVariable("reservation_id") @Size(min = 1, max = 128) String reservationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody CommitRequest request,
            HttpServletRequest httpRequest) {
        LOG.info("POST /v1/reservations/{}/commit", reservationId);
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        String tenant = repository.findReservationTenantById(reservationId);
        authorizeTenant(tenant);
        CommitResponse response = repository.commitReservation(reservationId, request);
        try {
            Actor actor = buildActor(httpRequest);
            // Emit commit_overage only when actual charge exceeds the original reservation estimate
            if (response.getEstimateAmount() != null && response.getCharged() != null
                    && response.getCharged().getAmount() != null
                    && response.getCharged().getAmount() > response.getEstimateAmount()) {
                long overage = response.getCharged().getAmount() - response.getEstimateAmount();
                eventEmitter.emit(EventType.RESERVATION_COMMIT_OVERAGE, tenant,
                        response.getScopePath(),
                        actor,
                        EventDataCommitOverage.builder()
                                .reservationId(reservationId)
                                .scope(response.getScopePath())
                                .unit(response.getCharged().getUnit().name())
                                .estimatedAmount(response.getEstimateAmount())
                                .actualAmount(response.getCharged().getAmount())
                                .overage(overage)
                                .overagePolicy(response.getOveragePolicy())
                                .debtIncurred(response.getDebtIncurred() != null
                                        ? response.getDebtIncurred() : 0L)
                                .build(),
                        null, null);
            }
            // Emit budget state events from post-operation balances
            eventEmitter.emitBalanceEvents(response.getBalances(), tenant, actor,
                    reservationId, response.getOveragePolicy(), null, null);
        } catch (Exception e) { /* non-blocking */ }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reservation_id}/release")
    @Operation(operationId = "releaseReservation", summary = "Release reservation")
    public ResponseEntity<ReleaseResponse> release(
            @PathVariable("reservation_id") @Size(min = 1, max = 128) String reservationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody ReleaseRequest request) {
        LOG.info("POST /v1/reservations/{}/release", reservationId);
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        String tenant = repository.findReservationTenantById(reservationId);
        authorizeTenant(tenant);
        ReleaseResponse response = repository.releaseReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reservation_id}/extend")
    @Operation(operationId = "extendReservation", summary = "Extend reservation TTL")
    public ResponseEntity<ReservationExtendResponse> extend(
            @PathVariable("reservation_id") @Size(min = 1, max = 128) String reservationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody ReservationExtendRequest request) {
        LOG.info("POST /v1/reservations/{}/extend", reservationId);
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        String tenant = repository.findReservationTenantById(reservationId);
        authorizeTenant(tenant);
        ReservationExtendResponse response = repository.extendReservation(reservationId, request, tenant);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(operationId = "listReservations", summary = "List reservations")
    public ResponseEntity<ReservationListResponse> list(
            @RequestParam(required = false) String tenant,
            @RequestParam(value = "idempotency_key", required = false) String idempotencyKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String workspace,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String workflow,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String toolset,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        // Validate status against ReservationStatus enum if provided
        if (status != null) {
            try {
                Enums.ReservationStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                    "Invalid status filter: " + status + ". Must be one of: ACTIVE, COMMITTED, RELEASED, EXPIRED", 400);
            }
        }
        // Spec: if tenant provided it must match auth; if omitted, use auth tenant
        String effectiveTenant = tenant != null ? tenant : extractAuthTenantId();
        LOG.info("GET /v1/reservations - tenant: {}", effectiveTenant);
        authorizeTenant(effectiveTenant);
        return ResponseEntity.ok(repository.listReservations(effectiveTenant, idempotencyKey,
                status, workspace, app, workflow, agent, toolset, limit, cursor));
    }
}
