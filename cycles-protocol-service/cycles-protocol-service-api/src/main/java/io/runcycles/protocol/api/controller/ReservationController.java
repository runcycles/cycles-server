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

/** Cycles Protocol v0.1.23 - Reservation Controller */
@RestController
@RequestMapping("/v1/reservations")
@Tag(name = "Reservations")
public class ReservationController extends BaseController{
    private static final Logger LOG = LoggerFactory.getLogger(ReservationController.class);

    @Autowired
    private RedisReservationRepository repository;

    @PostMapping
    @Operation(operationId = "createReservation", summary = "Create budget reservation")
    public ResponseEntity<ReservationCreateResponse> create(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody ReservationCreateRequest request) {
        LOG.info("POST /v1/reservations - tenant: {}", request.getSubject().getTenant());
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        String tenant = request.getSubject().getTenant();
        authorizeTenant(tenant);
        ReservationCreateResponse response = repository.createReservation(request, tenant);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{reservation_id}")
    @Operation(operationId = "getReservation", summary = "Get reservation by ID")
    public ResponseEntity<ReservationSummary> get(
            @PathVariable("reservation_id") String reservationId) {
        LOG.info("GET /v1/reservations/{}", reservationId);
        ReservationSummary summary = repository.getReservationById(reservationId);
        authorizeTenant(summary.getSubject().getTenant());
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/{reservation_id}/commit")
    @Operation(operationId = "commitReservation", summary = "Commit actual spend")
    public ResponseEntity<CommitResponse> commit(
            @PathVariable("reservation_id") String reservationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody CommitRequest request) {
        LOG.info("POST /v1/reservations/{}/commit", reservationId);
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        String tenant = repository.findReservationTenantById(reservationId);
        authorizeTenant(tenant);
        CommitResponse response = repository.commitReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reservation_id}/release")
    @Operation(operationId = "releaseReservation", summary = "Release reservation")
    public ResponseEntity<ReleaseResponse> release(
            @PathVariable("reservation_id") String reservationId,
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
            @PathVariable("reservation_id") String reservationId,
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
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        // Spec: if tenant provided it must match auth; if omitted, use auth tenant
        String effectiveTenant = tenant != null ? tenant : extractAuthTenantId();
        LOG.info("GET /v1/reservations - tenant: {}", effectiveTenant);
        authorizeTenant(effectiveTenant);
        return ResponseEntity.ok(repository.listReservations(effectiveTenant, limit, cursor));
    }
}
