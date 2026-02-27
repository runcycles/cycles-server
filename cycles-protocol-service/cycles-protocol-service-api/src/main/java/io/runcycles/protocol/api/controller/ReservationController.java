package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Cycles Protocol v0.1.23 - Reservation Controller */
@RestController
@RequestMapping("/v1/reservations")
@Tag(name = "Reservations")
public class ReservationController {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationController.class);
    
    @Autowired
    private RedisReservationRepository repository;
    
    @PostMapping
    @Operation(operationId = "createReservation", summary = "Create budget reservation")
    public ResponseEntity<ReservationCreateResponse> create(
            @RequestHeader("X-Cycles-API-Key") String apiKey,
            @Valid @RequestBody ReservationCreateRequest request) {
        LOG.info("POST /v1/reservations - tenant: {}", request.getSubject().getTenant());
        String tenant = request.getSubject().getTenant(); // In production, derive from API key
        ReservationCreateResponse response = repository.createReservation(request, tenant);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{reservation_id}/commit")
    @Operation(operationId = "commitReservation", summary = "Commit actual spend")
    public ResponseEntity<CommitResponse> commit(
            @PathVariable("reservation_id") String reservationId,
            @Valid @RequestBody CommitRequest request) {
        LOG.info("POST /v1/reservations/{}/commit", reservationId);
        CommitResponse response = repository.commitReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{reservation_id}/release")
    @Operation(operationId = "releaseReservation", summary = "Release reservation")
    public ResponseEntity<ReleaseResponse> release(
            @PathVariable("reservation_id") String reservationId,
            @Valid @RequestBody ReleaseRequest request) {
        LOG.info("POST /v1/reservations/{}/release", reservationId);
        ReleaseResponse response = repository.releaseReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{reservation_id}/extend")
    @Operation(operationId = "extendReservation", summary = "Extend reservation TTL")
    public ResponseEntity<ReservationExtendResponse> extend(
            @PathVariable("reservation_id") String reservationId,
            @Valid @RequestBody ReservationExtendRequest request) {
        LOG.info("POST /v1/reservations/{}/extend", reservationId);

        ReservationExtendResponse response = repository.extendReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping
    @Operation(operationId = "listReservations", summary = "List reservations")
    public ResponseEntity<ReservationListResponse> list(
            @RequestHeader("X-Cycles-API-Key") String apiKey,
            @RequestParam(required = false) String tenant,
            @RequestParam(defaultValue = "50") int limit) {
        LOG.info("GET /v1/reservations - tenant: {}", tenant);
        // In production, derive tenant from API key and validate
        return ResponseEntity.ok(new ReservationListResponse(
            repository.listReservations(tenant, limit), false));
    }
}
