package io.runcycles.protocol.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.repository.AuditRepository;
import io.runcycles.protocol.data.repository.RedisReservationRepository;
import io.runcycles.protocol.data.service.EventEmitterService;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.audit.AuditLogEntry;
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

    // v0.1.25.8: writes admin-driven release audit entries to the
    // shared Redis store. Same repo the governance plane reads from
    // via /v1/admin/audit/logs.
    @Autowired
    private AuditRepository auditRepository;

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
                                .reasonCode(response.getReasonCode() != null
                                        ? response.getReasonCode().name() : null)
                                .requestedAmount(request.getEstimate() != null
                                        ? request.getEstimate().getAmount() : null)
                                .remaining(remaining)
                                .action(actionMap)
                                .subject(subjectMap)
                                .build(),
                        null, null);
            }
            // Emit budget state events from post-operation balances (transition-based)
            eventEmitter.emitBalanceEvents(response.getBalances(), tenant, actor,
                    null, null, null,
                    response.getPreRemaining(), response.getPreIsOverLimit(),
                    null, null);
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
        CommitResponse response = repository.commitReservation(reservationId, request, tenant);
        try {
            Actor actor = buildActor(httpRequest);
            // Spec: emit commit_overage when committed actual > estimated amount
            // Use request.actual (not response.charged, which may be capped by ALLOW_IF_AVAILABLE)
            long requestActual = request.getActual().getAmount();
            if (response.getEstimateAmount() != null && requestActual > response.getEstimateAmount()) {
                long overage = requestActual - response.getEstimateAmount();
                eventEmitter.emit(EventType.RESERVATION_COMMIT_OVERAGE, tenant,
                        response.getScopePath(),
                        actor,
                        EventDataCommitOverage.builder()
                                .reservationId(reservationId)
                                .scope(response.getScopePath())
                                .unit(request.getActual().getUnit().name())
                                .estimatedAmount(response.getEstimateAmount())
                                .actualAmount(requestActual)
                                .overage(overage)
                                .overagePolicy(response.getOveragePolicy())
                                .debtIncurred(response.getDebtIncurred() != null
                                        ? response.getDebtIncurred() : 0L)
                                .build(),
                        null, null);
            }
            // Emit budget state events from post-operation balances (transition-based)
            eventEmitter.emitBalanceEvents(response.getBalances(), tenant, actor,
                    reservationId, response.getOveragePolicy(),
                    response.getScopeDebtIncurred(),
                    response.getPreRemaining(), response.getPreIsOverLimit(),
                    null, null);
        } catch (Exception e) { /* non-blocking */ }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reservation_id}/release")
    @Operation(operationId = "releaseReservation", summary = "Release reservation")
    public ResponseEntity<ReleaseResponse> release(
            @PathVariable("reservation_id") @Size(min = 1, max = 128) String reservationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyHeader,
            @Valid @RequestBody ReleaseRequest request,
            HttpServletRequest httpRequest) {
        LOG.info("POST /v1/reservations/{}/release", reservationId);
        validateIdempotencyHeader(idempotencyHeader, request.getIdempotencyKey());
        String tenant = repository.findReservationTenantById(reservationId);
        authorizeTenant(tenant);
        String actorType = isAdminAuth() ? "admin_on_behalf_of" : "tenant";
        ReleaseResponse response = repository.releaseReservation(reservationId, request, tenant, actorType);

        // v0.1.25.8: on admin-driven release, write an audit-log entry
        // to the shared Redis store. Entry surfaces in the governance
        // dashboard's Audit view via cycles-server-admin's existing
        // GET /v1/admin/audit/logs endpoint — both services point at
        // the same Redis and use the same audit:log:* key layout.
        //
        // Satisfies cycles-protocol revision 2026-04-13 NORMATIVE:
        //   "audit-log entry ... MUST record actor_type=admin_on_behalf_of"
        // Audit failure is non-fatal (repository swallows exceptions);
        // the release itself has already succeeded by the time we get
        // here, so a failed audit write doesn't affect the response.
        //
        // `reason` is user-controlled and stored in metadata. Strip
        // CR/LF before recording to prevent log-line forgery via
        // newline injection in any consumer that naively concatenates
        // audit entries (e.g. operator-facing grep output).
        if (isAdminAuth()) {
            String safeReason = request.getReason() != null
                ? request.getReason().replaceAll("[\\r\\n]", " ")
                : null;
            auditRepository.log(AuditLogEntry.builder()
                .tenantId(tenant)
                .operation("releaseReservation")
                .resourceType("reservation")
                .resourceId(reservationId)
                .status(200)
                .sourceIp(httpRequest != null ? httpRequest.getRemoteAddr() : null)
                .userAgent(httpRequest != null ? httpRequest.getHeader("User-Agent") : null)
                .requestId(httpRequest != null && httpRequest.getAttribute(
                    io.runcycles.protocol.api.filter.RequestIdFilter.REQUEST_ID_ATTRIBUTE) != null
                    ? httpRequest.getAttribute(
                        io.runcycles.protocol.api.filter.RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString()
                    : null)
                .metadata(java.util.Map.of(
                    "actor_type", "admin_on_behalf_of",
                    "reason", safeReason != null ? safeReason : ""))
                .build());
            // Keep the structured log too — ops teams watching
            // stdout (dev, incident response) see admin actions in
            // real time without having to query the audit endpoint.
            LOG.warn("[ADMIN_ON_BEHALF_OF] releaseReservation reservation_id={} tenant={} reason={}",
                reservationId, tenant, safeReason != null ? safeReason : "(none)");
        }
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
            @RequestParam(required = false) String cursor,
            @RequestParam(value = "sort_by", required = false) String sortBy,
            @RequestParam(value = "sort_dir", required = false) String sortDir) {
        // Validate status against ReservationStatus enum if provided
        if (status != null) {
            try {
                Enums.ReservationStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                    "Invalid status filter: " + status + ". Must be one of: ACTIVE, COMMITTED, RELEASED, EXPIRED", 400);
            }
        }
        // v0.1.25.12 (cycles-protocol revision 2026-04-16): validate
        // sort_by / sort_dir at the controller boundary so clients get
        // a clean 400 INVALID_REQUEST on typos before the repo runs.
        // v0.1.25.13: delegate parsing to Enums.*.fromWire (adds @JsonValue /
        // @JsonCreator round-trip), which is case-insensitive and throws
        // IllegalArgumentException on unknown values — caught and mapped to
        // the same 400 payload as before.
        if (sortBy != null) {
            try {
                Enums.ReservationSortBy.fromWire(sortBy);
            } catch (IllegalArgumentException e) {
                throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                    "Invalid sort_by: " + sortBy + ". Must be one of: reservation_id, tenant, scope_path, status, reserved, created_at_ms, expires_at_ms", 400);
            }
        }
        if (sortDir != null) {
            try {
                Enums.SortDirection.fromWire(sortDir);
            } catch (IllegalArgumentException e) {
                throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                    "Invalid sort_dir: " + sortDir + ". Must be one of: asc, desc", 400);
            }
        }
        // v0.1.25.8 (cycles-protocol revision 2026-04-13): tenant param
        // semantics differ by auth type. ApiKeyAuth: optional, falls
        // back to authenticated tenant, validation-only when present.
        // AdminKeyAuth: REQUIRED as a filter (admin has no effective
        // tenant), 400 INVALID_REQUEST when missing — same semantic as
        // listBudgets / listPolicies in the governance-admin spec.
        String effectiveTenant;
        if (isAdminAuth()) {
            if (tenant == null || tenant.isBlank()) {
                throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                    "tenant query parameter is required when using admin key authentication", 400);
            }
            effectiveTenant = tenant;
            // No authorizeTenant() — admin can read any tenant's
            // reservations.
        } else {
            // Spec: if tenant provided it must match auth; if omitted, use auth tenant
            effectiveTenant = tenant != null ? tenant : extractAuthTenantId();
            authorizeTenant(effectiveTenant);
        }
        LOG.info("GET /v1/reservations - tenant: {}, admin: {}, sort_by: {}, sort_dir: {}",
                effectiveTenant, isAdminAuth(), sortBy, sortDir);
        return ResponseEntity.ok(repository.listReservations(effectiveTenant, idempotencyKey,
                status, workspace, app, workflow, agent, toolset, limit, cursor, sortBy, sortDir));
    }
}
