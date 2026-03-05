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
    public ResponseEntity<ReservationCreateResponse> create(@Valid @RequestBody ReservationCreateRequest request) {
        LOG.info("POST /v1/reservations - tenant: {}", request.getSubject().getTenant());
        String tenant = request.getSubject().getTenant();
        authorizeTenant(tenant);
        ReservationCreateResponse response = repository.createReservation(request, tenant);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{reservation_id}/commit")
    @Operation(operationId = "commitReservation", summary = "Commit actual spend")
    public ResponseEntity<CommitResponse> commit(
            @PathVariable("reservation_id") String reservationId,
            @Valid @RequestBody CommitRequest request) {
        LOG.info("POST /v1/reservations/{}/commit", reservationId);
        String tenant = repository.findReservationTenantById(reservationId) ;
        authorizeTenant(tenant);
        CommitResponse response = repository.commitReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{reservation_id}/release")
    @Operation(operationId = "releaseReservation", summary = "Release reservation")
    public ResponseEntity<ReleaseResponse> release(
            @PathVariable("reservation_id") String reservationId,
            @Valid @RequestBody ReleaseRequest request) {
        LOG.info("POST /v1/reservations/{}/release", reservationId);
        String tenant = repository.findReservationTenantById(reservationId) ;
        authorizeTenant(tenant);
        ReleaseResponse response = repository.releaseReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{reservation_id}/extend")
    @Operation(operationId = "extendReservation", summary = "Extend reservation TTL")
    public ResponseEntity<ReservationExtendResponse> extend(
            @PathVariable("reservation_id") String reservationId,
            @Valid @RequestBody ReservationExtendRequest request) {
        LOG.info("POST /v1/reservations/{}/extend", reservationId);
        String tenant = repository.findReservationTenantById(reservationId) ;
        authorizeTenant(tenant);
        ReservationExtendResponse response = repository.extendReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping
    @Operation(operationId = "listReservations", summary = "List reservations")
    public ResponseEntity<ReservationListResponse> list(
            @RequestParam(required = true) String tenant,
            @RequestParam(defaultValue = "50") int limit) {
        LOG.info("GET /v1/reservations - tenant: {}", tenant);
        authorizeTenant(tenant);
        return ResponseEntity.ok(new ReservationListResponse(
            repository.listReservations(tenant, limit), false));
    }
}
