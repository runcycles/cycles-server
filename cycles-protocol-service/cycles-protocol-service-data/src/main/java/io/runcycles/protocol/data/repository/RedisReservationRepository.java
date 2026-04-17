package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.repository.support.FilterHasher;
import io.runcycles.protocol.data.repository.support.ReservationComparators;
import io.runcycles.protocol.data.repository.support.SortedListCursor;
import io.runcycles.protocol.data.service.LuaScriptRegistry;
import io.runcycles.protocol.data.service.ScopeDerivationService;
import io.runcycles.protocol.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/** Cycles Protocol v0.1.25 - Repository with Lua script execution */
@Repository
public class RedisReservationRepository {
    private static final Logger LOG = LoggerFactory.getLogger(RedisReservationRepository.class);

    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ScopeDerivationService scopeService;
    @Autowired private LuaScriptRegistry luaScripts;
    @Autowired private CyclesMetrics metrics;
    @Autowired @Qualifier("reserveLuaScript") private String reserveScript;
    @Autowired @Qualifier("commitLuaScript") private String commitScript;
    @Autowired @Qualifier("releaseLuaScript") private String releaseScript;
    @Autowired @Qualifier("extendLuaScript") private String extendScript;
    @Autowired @Qualifier("eventLuaScript") private String eventScript;

    /** CSV of all known Unit enum values, passed to reserve.lua/event.lua so the scripts can
     *  probe alternate units on the BUDGET_NOT_FOUND error path and distinguish "wrong unit"
     *  from "truly missing". Derived once from the enum to avoid drift. */
    private static final String UNIT_CSV = Arrays.stream(Enums.UnitEnum.values())
        .map(Enums.UnitEnum::name)
        .collect(Collectors.joining(","));

    /**
     * v0.1.25.13 hydration cap for the sorted listReservations path. The sorted
     * path must full-SCAN every `reservation:res_*` key across all tenants
     * before the in-memory sort, which is unbounded in the naive case. At
     * runtime scale (10k reservations per active tenant) this can exhaust
     * heap before the cursor even runs. Cap at 2000 rows per request; when
     * the cap is hit we break out of hydration, sort the capped slice, and
     * serve the cursor from that slice. Operators that need to see beyond
     * the cap should narrow filters (status, scope segments, tenant) — the
     * deferred-optimization ADR (docs/deferred-optimizations/
     * sorted-list-zset-indices.md) tracks the longer-term per-tenant ZSET
     * index work that will retire this cap.
     */
    static final int SORTED_HYDRATE_CAP = 2000;

    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    });

    public ReservationCreateResponse createReservation(ReservationCreateRequest request, String tenant) {
        LOG.debug("Creating reservation for tenant: {}", tenant);
        String overagePolicyTag = request.getOveragePolicy() != null ? request.getOveragePolicy().name() : "DEFAULT";

        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, Object> tenantConfig = getTenantConfig(jedis, tenant);
            List<String> affectedScopes = scopeService.deriveScopes(request.getSubject());
            String scopePath = affectedScopes.get(affectedScopes.size() - 1);
            String overagePolicy = resolveOveragePolicy(request.getOveragePolicy(), tenantConfig);
            long effectiveTtl = resolveReservationTtl(request.getTtlMs(), tenantConfig);
            int maxExtensions = resolveMaxExtensions(tenantConfig);

            // dry_run: evaluate budget without persisting a reservation
            if (Boolean.TRUE.equals(request.getDryRun())) {
                return evaluateDryRun(jedis, request, affectedScopes, scopePath, tenant);
            }

            String reservationId = UUID.randomUUID().toString();

            List<String> args = new ArrayList<>();
            args.add(reservationId);
            args.add(objectMapper.writeValueAsString(request.getSubject()));
            args.add(objectMapper.writeValueAsString(request.getAction()));
            args.add(String.valueOf(request.getEstimate().getAmount()));
            args.add(request.getEstimate().getUnit().name());
            args.add(String.valueOf(effectiveTtl));
            args.add(String.valueOf(request.getGracePeriodMs() != null ? request.getGracePeriodMs() : 5000));
            args.add(request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "");
            args.add(scopePath);
            args.add(tenant);
            args.add(overagePolicy);
            // ARGV[12] = metadata_json, ARGV[13] = payload_hash, ARGV[14] = max_extensions,
            // ARGV[15] = units_csv; scopes start at ARGV[16]
            args.add(request.getMetadata() != null
                ? objectMapper.writeValueAsString(request.getMetadata()) : "");
            args.add(computePayloadHash(request));
            args.add(String.valueOf(maxExtensions));
            args.add(UNIT_CSV);
            args.addAll(affectedScopes);

            Object result = luaScripts.eval(jedis, "reserve", reserveScript, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.debug("Reserve response: {}", response);
            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            // Idempotency hit: Lua returns existing reservation_id without expires_at.
            // Fetch the stored reservation to build a complete, accurate response.
            // Use hgetAll directly to avoid the 410 check in getReservationById.
            if (!response.containsKey("expires_at")) {
                String existingId = (String) response.get("reservation_id");
                Map<String, String> existingFields = jedis.hgetAll("reservation:res_" + existingId);
                if (existingFields == null || existingFields.isEmpty()) {
                    throw CyclesProtocolException.notFound(existingId);
                }
                ReservationSummary existing = buildReservationSummary(existingFields);
                List<Balance> idempotencyBalances = fetchBalancesForScopes(jedis, existing.getAffectedScopes(), existing.getReserved().getUnit());
                metrics.recordReserve(tenant, Enums.DecisionEnum.ALLOW.name(), "IDEMPOTENT_REPLAY", overagePolicyTag);
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .reservationId(existingId)
                    .affectedScopes(existing.getAffectedScopes())
                    .scopePath(existing.getScopePath())
                    .reserved(existing.getReserved())
                    .expiresAtMs(existing.getExpiresAtMs())
                    .balances(idempotencyBalances)
                    .build();
            }

            // Parse balances from Lua response (read atomically with mutation)
            List<Balance> balances = parseLuaBalances(response, request.getEstimate().getUnit());

            // Check deepest scope for ALLOW_WITH_CAPS (operator-configured caps)
            Enums.DecisionEnum decision = Enums.DecisionEnum.ALLOW;
            Caps caps = null;
            String deepestBudgetKey = "budget:" + scopePath + ":" + request.getEstimate().getUnit().name();
            String capsJson = jedis.hget(deepestBudgetKey, "caps_json");
            if (capsJson != null && !capsJson.isEmpty()) {
                caps = objectMapper.readValue(capsJson, Caps.class);
                decision = Enums.DecisionEnum.ALLOW_WITH_CAPS;
            }

            metrics.recordReserve(tenant, decision.name(), "OK", overagePolicyTag);
            return ReservationCreateResponse.builder()
                .decision(decision)
                .reservationId(reservationId)
                .affectedScopes(affectedScopes)
                .scopePath(scopePath)
                .reserved(request.getEstimate())
                .expiresAtMs(((Number) response.get("expires_at")).longValue())
                .caps(caps)
                .balances(balances)
                .preRemaining(parsePreRemaining(response))
                .preIsOverLimit(parsePreIsOverLimit(response))
                .build();
        } catch (CyclesProtocolException e) {
            metrics.recordReserve(tenant, "DENY", e.getErrorCode().name(), overagePolicyTag);
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to create reservation", e);
            metrics.recordReserve(tenant, "DENY", "INTERNAL_ERROR", overagePolicyTag);
            throw new RuntimeException(e);
        }
    }

    private ReservationCreateResponse evaluateDryRun(Jedis jedis, ReservationCreateRequest request,
                                                      List<String> affectedScopes, String scopePath,
                                                      String tenant) throws Exception {
        long estimateAmount = request.getEstimate().getAmount();
        String unit = request.getEstimate().getUnit().name();

        // Idempotency: replay cached result for same (tenant, key) within 24 h
        String idempotencyKey = request.getIdempotencyKey();
        String payloadHash = computePayloadHash(request);
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            String idemKey = "idem:" + tenant + ":dry_run:" + idempotencyKey;
            // Pipeline both GETs in a single round-trip
            Pipeline idemPipe = jedis.pipelined();
            Response<String> cachedResp = idemPipe.get(idemKey);
            Response<String> hashResp = idemPipe.get(idemKey + ":hash");
            idemPipe.sync();
            String cached = cachedResp.get();
            if (cached != null) {
                // Spec MUST: detect payload mismatch on idempotent replay
                if (!payloadHash.isEmpty()) {
                    String storedHash = hashResp.get();
                    if (storedHash != null && !storedHash.equals(payloadHash)) {
                        throw CyclesProtocolException.idempotencyMismatch();
                    }
                }
                return objectMapper.readValue(cached, ReservationCreateResponse.class);
            }
        }

        // Pipeline all budget fetches (validation + balance collection + caps) in one round-trip
        Pipeline pipeline = jedis.pipelined();
        Map<String, Response<Map<String, String>>> budgetResponses = new LinkedHashMap<>();
        for (String scope : affectedScopes) {
            budgetResponses.put(scope, pipeline.hgetAll("budget:" + scope + ":" + unit));
        }
        Response<List<String>> capsResponse = pipeline.hmget("budget:" + scopePath + ":" + unit, "caps_json");
        pipeline.sync();

        // Skip scopes without budgets — operators may only define budgets at certain levels.
        // At least one scope must have a budget (consistent with reserve.lua).
        boolean foundBudget = false;
        for (Map.Entry<String, Response<Map<String, String>>> entry : budgetResponses.entrySet()) {
            Map<String, String> budget = entry.getValue().get();

            if (budget == null || budget.isEmpty()) {
                continue; // Skip scopes without budgets
            }
            foundBudget = true;

            // Check budget status (consistent with admin FUND_LUA and reserve.lua)
            String budgetStatus = budget.getOrDefault("status", "ACTIVE");
            if ("FROZEN".equals(budgetStatus) || "CLOSED".equals(budgetStatus)) {
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode("FROZEN".equals(budgetStatus)
                        ? Enums.ReasonCode.BUDGET_FROZEN
                        : Enums.ReasonCode.BUDGET_CLOSED)
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build();
            }
            if ("true".equals(budget.getOrDefault("is_over_limit", "false"))) {
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode(Enums.ReasonCode.OVERDRAFT_LIMIT_EXCEEDED)
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build();
            }
            long debt = Long.parseLong(budget.getOrDefault("debt", "0"));
            long overdraftLimit = Long.parseLong(budget.getOrDefault("overdraft_limit", "0"));
            if (debt > 0 && overdraftLimit == 0) {
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode(Enums.ReasonCode.DEBT_OUTSTANDING)
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build();
            }
            long remaining = Long.parseLong(budget.getOrDefault("remaining", "0"));
            if (remaining < estimateAmount) {
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode(Enums.ReasonCode.BUDGET_EXCEEDED)
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build();
            }
        }

        if (!foundBudget) {
            // Distinguish "wrong unit" from "truly missing" — symmetric with reserve.lua probe.
            // If any affected scope has a budget in a different unit, throw UNIT_MISMATCH (400)
            // so the client can self-correct. Otherwise fall through to the DENY + BUDGET_NOT_FOUND
            // dry_run response.
            for (String scope : affectedScopes) {
                List<String> expectedUnits = probeAlternateUnits(jedis, scope, unit);
                if (!expectedUnits.isEmpty()) {
                    throw CyclesProtocolException.unitMismatch(scope, unit, expectedUnits);
                }
            }
            return ReservationCreateResponse.builder()
                .decision(Enums.DecisionEnum.DENY)
                .reasonCode(Enums.ReasonCode.BUDGET_NOT_FOUND)
                .affectedScopes(affectedScopes)
                .scopePath(scopePath)
                .build();
        }

        // Collect current balances from the already-fetched pipeline results
        Enums.UnitEnum unitEnum = request.getEstimate().getUnit();
        List<Balance> balances = new ArrayList<>();
        for (Map.Entry<String, Response<Map<String, String>>> entry : budgetResponses.entrySet()) {
            Map<String, String> budget = entry.getValue().get();
            if (budget != null && !budget.isEmpty()) {
                long debtVal = Long.parseLong(budget.getOrDefault("debt", "0"));
                long overdraftLimitVal = Long.parseLong(budget.getOrDefault("overdraft_limit", "0"));
                boolean isOverLimit = "true".equals(budget.getOrDefault("is_over_limit", "false"));
                balances.add(Balance.builder()
                    .scope(leafScope(entry.getKey()))
                    .scopePath(entry.getKey())
                    .remaining(new SignedAmount(unitEnum, Long.parseLong(budget.getOrDefault("remaining", "0"))))
                    .reserved(new Amount(unitEnum, Long.parseLong(budget.getOrDefault("reserved", "0"))))
                    .spent(new Amount(unitEnum, Long.parseLong(budget.getOrDefault("spent", "0"))))
                    .allocated(new Amount(unitEnum, Long.parseLong(budget.getOrDefault("allocated", "0"))))
                    .debt(debtVal > 0 ? new Amount(unitEnum, debtVal) : null)
                    .overdraftLimit(overdraftLimitVal > 0 ? new Amount(unitEnum, overdraftLimitVal) : null)
                    .isOverLimit(isOverLimit ? true : null)
                    .build());
            }
        }

        // Check deepest scope for ALLOW_WITH_CAPS (operator-configured caps)
        Enums.DecisionEnum dryRunDecision = Enums.DecisionEnum.ALLOW;
        Caps dryRunCaps = null;
        List<String> capsList = capsResponse.get();
        String capsJson = (capsList != null && !capsList.isEmpty()) ? capsList.get(0) : null;
        if (capsJson != null && !capsJson.isEmpty()) {
            dryRunCaps = objectMapper.readValue(capsJson, Caps.class);
            dryRunDecision = Enums.DecisionEnum.ALLOW_WITH_CAPS;
        }

        ReservationCreateResponse dryRunResponse = ReservationCreateResponse.builder()
            .decision(dryRunDecision)
            .affectedScopes(affectedScopes)
            .scopePath(scopePath)
            .reserved(request.getEstimate())
            .caps(dryRunCaps)
            .balances(balances)
            .build();

        // Cache dry_run result (24 h TTL) - pipeline both writes
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            String idemKey = "idem:" + tenant + ":dry_run:" + idempotencyKey;
            Pipeline idemWritePipe = jedis.pipelined();
            idemWritePipe.psetex(idemKey, 86400000L, objectMapper.writeValueAsString(dryRunResponse));
            if (!payloadHash.isEmpty()) {
                idemWritePipe.psetex(idemKey + ":hash", 86400000L, payloadHash);
            }
            idemWritePipe.sync();
        }

        return dryRunResponse;
    }

    public CommitResponse commitReservation(String reservationId, CommitRequest request, String tenant) {
        LOG.debug("Committing reservation: {}", reservationId);

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> args = Arrays.asList(
                reservationId,
                String.valueOf(request.getActual().getAmount()),
                request.getActual().getUnit().name(),
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "",
                computePayloadHash(request),
                request.getMetrics() != null ? objectMapper.writeValueAsString(request.getMetrics()) : "",
                request.getMetadata() != null ? objectMapper.writeValueAsString(request.getMetadata()) : ""
            );

            Object result = luaScripts.eval(jedis, "commit", commitScript, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);

            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            long chargedAmount = ((Number) response.get("charged")).longValue();

            // Lua now returns estimate_amount — use it for released calculation
            Amount released = null;
            Number luaEstimate = (Number) response.get("estimate_amount");
            if (luaEstimate != null) {
                long est = luaEstimate.longValue();
                if (chargedAmount < est) {
                    released = new Amount(request.getActual().getUnit(), est - chargedAmount);
                }
            }

            // Parse balances from Lua response (read atomically with mutation)
            List<Balance> balances = parseLuaBalances(response, request.getActual().getUnit());

            // Internal fields from Lua for event emission (not serialized to client)
            String scopePath = response.get("scope_path") != null ? response.get("scope_path").toString() : null;
            String overagePolicy = response.get("overage_policy") != null ? response.get("overage_policy").toString() : null;
            Number luaDebt = (Number) response.get("debt_incurred");
            Map<String, Long> scopeDebtIncurred = parseScopeDebtIncurred(response);

            long debtIncurredAmount = luaDebt != null ? luaDebt.longValue() : 0L;
            metrics.recordCommit(tenant, "COMMITTED", "OK",
                overagePolicy != null ? overagePolicy : "DEFAULT");
            if (debtIncurredAmount > 0) {
                metrics.recordOverdraftIncurred(tenant);
            }
            return CommitResponse.builder()
                .status(Enums.CommitStatus.COMMITTED)
                .charged(new Amount(request.getActual().getUnit(), chargedAmount))
                .released(released)
                .balances(balances)
                .estimateAmount(luaEstimate != null ? luaEstimate.longValue() : null)
                .scopePath(scopePath)
                .overagePolicy(overagePolicy)
                .debtIncurred(luaDebt != null ? luaDebt.longValue() : null)
                .scopeDebtIncurred(scopeDebtIncurred)
                .preRemaining(parsePreRemaining(response))
                .preIsOverLimit(parsePreIsOverLimit(response))
                .build();
        } catch (CyclesProtocolException e){
            LOG.error("Failed logic to commit reservation", e);
            metrics.recordCommit(tenant, "DENY", e.getErrorCode().name(), "UNKNOWN");
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to commit reservation", e);
            metrics.recordCommit(tenant, "DENY", "INTERNAL_ERROR", "UNKNOWN");
            throw new RuntimeException(e);
        }
    }

    public ReleaseResponse releaseReservation(String reservationId, ReleaseRequest request,
                                               String tenant, String actorType) {
        LOG.debug("Releasing reservation: {}", reservationId);

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> args = Arrays.asList(
                reservationId,
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "",
                computePayloadHash(request)
            );

            Object result = luaScripts.eval(jedis, "release", releaseScript, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);

            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            // Lua now returns estimate_amount and estimate_unit — use them directly
            Amount releasedAmount;
            Number luaEstimate = (Number) response.get("estimate_amount");
            String luaUnit = (String) response.get("estimate_unit");
            if (luaEstimate != null && luaUnit != null) {
                releasedAmount = new Amount(
                    Enums.UnitEnum.valueOf(luaUnit),
                    luaEstimate.longValue()
                );
            } else {
                // Spec requires released to be present; fall back to zero with the request unit context
                LOG.warn("Reservation {} missing estimate data, defaulting released to zero", reservationId);
                releasedAmount = new Amount(Enums.UnitEnum.USD_MICROCENTS, 0L);
            }

            // Parse balances from Lua response (read atomically with mutation)
            Enums.UnitEnum unitForBalances = luaUnit != null ? Enums.UnitEnum.valueOf(luaUnit) : Enums.UnitEnum.USD_MICROCENTS;
            List<Balance> balances = parseLuaBalances(response, unitForBalances);

            metrics.recordRelease(tenant, actorType, "RELEASED", "OK");
            return ReleaseResponse.builder()
                .status(Enums.ReleaseStatus.RELEASED)
                .released(releasedAmount)
                .balances(balances)
                .build();
        } catch (CyclesProtocolException e){
            LOG.error("Failed logic to release reservation:reservationId={},req={}",reservationId,request,e);
            metrics.recordRelease(tenant, actorType, "DENY", e.getErrorCode().name());
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to release reservation:reservationId={},req={}",reservationId,request,e);
            metrics.recordRelease(tenant, actorType, "DENY", "INTERNAL_ERROR");
            throw new RuntimeException(e);
        }
    }

    public ReservationExtendResponse extendReservation(String reservationId, ReservationExtendRequest request, String tenant) {
        LOG.debug("Extending reservation: {}", reservationId);

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> args = Arrays.asList(
                reservationId,
                String.valueOf(request.getExtendByMs()),
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "",
                tenant != null ? tenant : "",
                computePayloadHash(request),
                request.getMetadata() != null ? objectMapper.writeValueAsString(request.getMetadata()) : ""
            );

            Object result = luaScripts.eval(jedis, "extend", extendScript, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.debug("Extend response: {}", response);
            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            // Parse balances returned atomically from Lua (no extra round-trips)
            String estimateUnitStr = (String) response.get("estimate_unit");
            Enums.UnitEnum unitForBalances = Enums.UnitEnum.USD_MICROCENTS;
            if (estimateUnitStr != null) {
                try { unitForBalances = Enums.UnitEnum.valueOf(estimateUnitStr); }
                catch (IllegalArgumentException ignored) { /* corrupted Redis data, use default */ }
            }
            List<Balance> balances = parseLuaBalances(response, unitForBalances);

            metrics.recordExtend(tenant, "ACTIVE", "OK");
            return ReservationExtendResponse.builder()
                .status(Enums.ExtendStatus.ACTIVE)
                .expiresAtMs(((Number) response.get("expires_at_ms")).longValue())
                .balances(balances)
                .build();
        } catch (CyclesProtocolException e){
            LOG.error("Failed logic to extend reservation:reservationId={},req={}",reservationId,request,e);
            metrics.recordExtend(tenant, "DENY", e.getErrorCode().name());
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to extend reservation: reservationId={},request={},msg={}",reservationId,request,e.getMessage(),e);
            metrics.recordExtend(tenant, "DENY", "INTERNAL_ERROR");
            throw new RuntimeException(e);
        }
    }

    public ReservationDetail getReservationById(String reservationId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "reservation:res_" + reservationId;
            Map<String, String> fields = jedis.hgetAll(key);
            if (fields == null || fields.isEmpty()) {
                throw CyclesProtocolException.notFound(reservationId);
            }
            ReservationDetail detail = buildReservationSummary(fields);
            // Spec normative (line 52): "Expired reservations MUST return HTTP 410
            // with error=RESERVATION_EXPIRED." GET endpoint lists 410 (line 1212).
            if (detail.getStatus() == Enums.ReservationStatus.EXPIRED) {
                throw CyclesProtocolException.reservationExpired();
            }
            return detail;
        } catch (CyclesProtocolException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to get reservation by id: {}", reservationId, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * List reservations with optional server-side sorting.
     *
     * <p>Spec: cycles-protocol-v0.yaml revision 2026-04-16. When {@code sortBy} or
     * {@code sortDir} is provided, results are returned in requested order and the
     * returned cursor encodes the sort state ({@link SortedListCursor}). When both are
     * null AND the caller supplies either no cursor or a legacy numeric SCAN cursor,
     * the original SCAN-cursor pagination path is used — preserves wire-compat for
     * clients predating revision 2026-04-16.
     *
     * <p>Cursors from the sorted path encode the {@code (sort_by, sort_dir, filters)}
     * tuple; re-using a cursor under a different tuple returns HTTP 400 INVALID_REQUEST
     * per spec.
     */
    public ReservationListResponse listReservations(String tenant, String idempotencyKey,
                                                    String status, String workspace, String app,
                                                    String workflow, String agent, String toolset,
                                                    int limit, String startCursor,
                                                    String sortBy, String sortDir) {
        boolean sortRequested = sortBy != null || sortDir != null;
        Optional<SortedListCursor> parsedCursor = SortedListCursor.decode(startCursor);

        // Route to sorted path when sort params are provided OR when the incoming cursor
        // is a sorted-path cursor (client may omit sort params on the follow-up request,
        // expecting the cursor to carry the sort state — we honour that).
        if (sortRequested || parsedCursor.isPresent()) {
            return listReservationsSorted(tenant, idempotencyKey, status, workspace, app,
                workflow, agent, toolset, limit, parsedCursor.orElse(null), sortBy, sortDir);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("reservation:res_*").count(100);
            List<ReservationSummary> result = new ArrayList<>();
            String cursor = (startCursor != null && !startCursor.isBlank()) ? startCursor : "0";

            // Pre-lowercase filter params once (scope paths are already lowercased at creation)
            String workspaceSegment = workspace != null ? "workspace:" + workspace.toLowerCase() : null;
            String appSegment = app != null ? "app:" + app.toLowerCase() : null;
            String workflowSegment = workflow != null ? "workflow:" + workflow.toLowerCase() : null;
            String agentSegment = agent != null ? "agent:" + agent.toLowerCase() : null;
            String toolsetSegment = toolset != null ? "toolset:" + toolset.toLowerCase() : null;

            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                List<String> keys = scan.getResult();

                if (!keys.isEmpty()) {
                    Pipeline pipeline = jedis.pipelined();
                    Map<String, Response<Map<String, String>>> responses = new HashMap<>();
                    for (String key : keys) {
                        responses.put(key, pipeline.hgetAll(key));
                    }
                    pipeline.sync();

                    for (String key : keys) {
                        try {
                            Map<String, String> fields = responses.get(key).get();
                            if (fields.isEmpty()) continue;
                            if (!tenant.equals(fields.get("tenant"))) continue;
                            if (status != null && !status.equals(fields.get("state"))) continue;
                            if (idempotencyKey != null && !idempotencyKey.equals(fields.get("idempotency_key"))) continue;
                            String scopePath = fields.getOrDefault("scope_path", "").toLowerCase();
                            if (workspaceSegment != null && !scopeHasSegment(scopePath, workspaceSegment)) continue;
                            if (appSegment != null && !scopeHasSegment(scopePath, appSegment)) continue;
                            if (workflowSegment != null && !scopeHasSegment(scopePath, workflowSegment)) continue;
                            if (agentSegment != null && !scopeHasSegment(scopePath, agentSegment)) continue;
                            if (toolsetSegment != null && !scopeHasSegment(scopePath, toolsetSegment)) continue;

                            result.add(toSummary(buildReservationSummary(fields)));

                            if (result.size() >= limit) {
                                String nextCursor = scan.getCursor();
                                if ("0".equals(nextCursor)) nextCursor = null;
                                // Spec: has_more is true only when next_cursor is present
                                return ReservationListResponse.builder()
                                    .reservations(result)
                                    .hasMore(nextCursor != null)
                                    .nextCursor(nextCursor)
                                    .build();
                            }
                        } catch (Exception e) {
                            LOG.warn("Failed to parse reservation: {}", key, e);
                        }
                    }
                }

                cursor = scan.getCursor();
            } while (!"0".equals(cursor));

            return ReservationListResponse.builder()
                .reservations(result)
                .hasMore(false)
                .nextCursor(null)
                .build();
        }
    }

    /**
     * Sorted-path implementation for listReservations. Loads all matching rows via a full
     * SCAN pass, sorts in-memory using {@link ReservationComparators}, then slices by the
     * decoded cursor position. See javadoc on the public overload for spec context.
     */
    private ReservationListResponse listReservationsSorted(
            String tenant, String idempotencyKey, String status,
            String workspace, String app, String workflow, String agent, String toolset,
            int limit, SortedListCursor resumeCursor, String sortBy, String sortDir) {

        // Normalize for cursor storage + comparator use. Null sort_dir with a non-null
        // sort_by defaults to DESC per spec; null sort_by with a non-null sort_dir defaults
        // to CREATED_AT_MS. Resume-from-cursor honours the tuple encoded in the cursor.
        String effectiveSortBy = sortBy != null ? sortBy.toLowerCase()
            : (resumeCursor != null ? resumeCursor.getSortBy() : "created_at_ms");
        String effectiveSortDir = sortDir != null ? sortDir.toLowerCase()
            : (resumeCursor != null ? resumeCursor.getSortDir() : "desc");

        String filterHash = FilterHasher.hash(tenant, idempotencyKey, status,
            workspace, app, workflow, agent, toolset);

        // Spec: cursor is valid only for the same (sort_by, sort_dir, filters) tuple.
        if (resumeCursor != null) {
            if (!effectiveSortBy.equalsIgnoreCase(resumeCursor.getSortBy())
                || !effectiveSortDir.equalsIgnoreCase(resumeCursor.getSortDir())
                || !filterHash.equals(resumeCursor.getFilterHash())) {
                throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                    "cursor is not valid for the requested sort_by / sort_dir / filters; reset cursor when any of these change",
                    400);
            }
        }

        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("reservation:res_*").count(500);
            List<ReservationSummary> matching = new ArrayList<>();

            String workspaceSegment = workspace != null ? "workspace:" + workspace.toLowerCase() : null;
            String appSegment = app != null ? "app:" + app.toLowerCase() : null;
            String workflowSegment = workflow != null ? "workflow:" + workflow.toLowerCase() : null;
            String agentSegment = agent != null ? "agent:" + agent.toLowerCase() : null;
            String toolsetSegment = toolset != null ? "toolset:" + toolset.toLowerCase() : null;

            String scanCursor = "0";
            boolean capped = false;
            scanLoop:
            do {
                ScanResult<String> scan = jedis.scan(scanCursor, params);
                List<String> keys = scan.getResult();
                if (!keys.isEmpty()) {
                    Pipeline pipeline = jedis.pipelined();
                    Map<String, Response<Map<String, String>>> responses = new HashMap<>();
                    for (String key : keys) {
                        responses.put(key, pipeline.hgetAll(key));
                    }
                    pipeline.sync();

                    for (String key : keys) {
                        if (matching.size() >= SORTED_HYDRATE_CAP) { capped = true; break scanLoop; }
                        try {
                            Map<String, String> fields = responses.get(key).get();
                            if (fields.isEmpty()) continue;
                            if (!tenant.equals(fields.get("tenant"))) continue;
                            if (status != null && !status.equals(fields.get("state"))) continue;
                            if (idempotencyKey != null && !idempotencyKey.equals(fields.get("idempotency_key"))) continue;
                            String scopePath = fields.getOrDefault("scope_path", "").toLowerCase();
                            if (workspaceSegment != null && !scopeHasSegment(scopePath, workspaceSegment)) continue;
                            if (appSegment != null && !scopeHasSegment(scopePath, appSegment)) continue;
                            if (workflowSegment != null && !scopeHasSegment(scopePath, workflowSegment)) continue;
                            if (agentSegment != null && !scopeHasSegment(scopePath, agentSegment)) continue;
                            if (toolsetSegment != null && !scopeHasSegment(scopePath, toolsetSegment)) continue;

                            matching.add(toSummary(buildReservationSummary(fields)));
                        } catch (Exception e) {
                            LOG.warn("Failed to parse reservation: {}", key, e);
                        }
                    }
                }
                scanCursor = scan.getCursor();
            } while (!"0".equals(scanCursor));

            if (capped) {
                LOG.warn("listReservationsSorted hydration capped at {} rows for tenant={} sort_by={} sort_dir={}; narrow filters to see beyond the cap",
                    SORTED_HYDRATE_CAP, tenant, effectiveSortBy, effectiveSortDir);
            }

            // Full in-memory sort. Acceptable at runtime-plane scale; metric below lets us
            // spot tenants that outgrow this path and need per-key ZSET indices.
            matching.sort(ReservationComparators.of(effectiveSortBy, effectiveSortDir));
            LOG.debug("listReservationsSorted: tenant={} matched={} sort_by={} sort_dir={}",
                tenant, matching.size(), effectiveSortBy, effectiveSortDir);

            // Locate slice start from the cursor. The cursor's (lastSortValue, lastReservationId)
            // tuple defines a strict lower bound; walk the sorted list until we find the first
            // row strictly greater than that tuple under the comparator.
            int start = 0;
            if (resumeCursor != null) {
                start = findSliceStart(matching, effectiveSortBy, effectiveSortDir, resumeCursor);
            }
            int end = Math.min(start + limit, matching.size());
            List<ReservationSummary> page = new ArrayList<>(matching.subList(start, end));

            String nextCursor = null;
            if (end < matching.size() && !page.isEmpty()) {
                ReservationSummary last = page.get(page.size() - 1);
                SortedListCursor next = new SortedListCursor(
                    1,
                    effectiveSortBy,
                    effectiveSortDir,
                    filterHash,
                    ReservationComparators.extractSortValue(last, effectiveSortBy),
                    last.getReservationId());
                nextCursor = next.encode();
            }

            return ReservationListResponse.builder()
                .reservations(page)
                .hasMore(nextCursor != null)
                .nextCursor(nextCursor)
                .build();
        }
    }

    private static int findSliceStart(List<ReservationSummary> sorted, String sortBy,
                                       String sortDir, SortedListCursor cursor) {
        // Walk the sorted list looking for the first row strictly greater than the cursor's
        // (lastSortValue, lastReservationId) tuple under the effective sort direction.
        // Tiebreaker on reservation_id ASC matches the comparator's stable secondary ordering.
        for (int i = 0; i < sorted.size(); i++) {
            ReservationSummary r = sorted.get(i);
            int keyCmp = compareAtBoundary(r, sortBy, sortDir, cursor.getLastSortValue());
            if (keyCmp > 0) return i;
            if (keyCmp == 0) {
                String rid = r.getReservationId() == null ? "" : r.getReservationId();
                String last = cursor.getLastReservationId() == null ? "" : cursor.getLastReservationId();
                if (rid.compareTo(last) > 0) return i;
            }
        }
        return sorted.size();
    }

    private static int compareAtBoundary(ReservationSummary row, String sortBy, String sortDir,
                                          String lastSortValueStr) {
        String rowValue = ReservationComparators.extractSortValue(row, sortBy);
        // Numeric keys sort numerically; everything else lexicographically.
        boolean numeric = "reserved".equalsIgnoreCase(sortBy)
            || "created_at_ms".equalsIgnoreCase(sortBy)
            || "expires_at_ms".equalsIgnoreCase(sortBy);
        int raw;
        if (numeric) {
            long rowN = rowValue.isEmpty() ? Long.MIN_VALUE : Long.parseLong(rowValue);
            long lastN = lastSortValueStr == null || lastSortValueStr.isEmpty()
                ? Long.MIN_VALUE : Long.parseLong(lastSortValueStr);
            raw = Long.compare(rowN, lastN);
        } else {
            String a = rowValue == null ? "" : rowValue;
            String b = lastSortValueStr == null ? "" : lastSortValueStr;
            raw = a.compareTo(b);
        }
        return "desc".equalsIgnoreCase(sortDir) ? -raw : raw;
    }

    public BalanceResponse getBalances(String tenant, String workspace, String app,
                                            String workflow, String agent, String toolset,
                                            boolean includeChildren, int limit, String startCursor) {
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("budget:*").count(100);
            List<Balance> balances = new ArrayList<>();
            String cursor = (startCursor != null && !startCursor.isBlank()) ? startCursor : "0";

            // Pre-lowercase filter params once (scope paths are already lowercased at creation)
            String tenantSegment = "tenant:" + tenant.toLowerCase();
            String workspaceSegment = workspace != null ? "workspace:" + workspace.toLowerCase() : null;
            String appSegment = app != null ? "app:" + app.toLowerCase() : null;
            String workflowSegment = workflow != null ? "workflow:" + workflow.toLowerCase() : null;
            String agentSegment = agent != null ? "agent:" + agent.toLowerCase() : null;
            String toolsetSegment = toolset != null ? "toolset:" + toolset.toLowerCase() : null;

            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                List<String> keys = scan.getResult();

                if (!keys.isEmpty()) {
                // Pipeline all hgetAll calls for this SCAN batch
                Pipeline pipeline = jedis.pipelined();
                Map<String, Response<Map<String, String>>> responses = new LinkedHashMap<>();
                for (String key : keys) {
                    responses.put(key, pipeline.hgetAll(key));
                }
                pipeline.sync();

                for (String key : keys) {
                    try {
                        Map<String, String> budget = responses.get(key).get();
                        if (budget.isEmpty()) continue;

                        String trueScope = budget.get("scope");
                        if (trueScope == null) continue;
                        // Normalize stored scope to lowercase for case-insensitive matching.
                        // Admin API may have stored mixed-case values before normalization was added.
                        trueScope = trueScope.toLowerCase();

                        // Filter by tenant and optional subject fields.
                        // Use exact segment boundary checks to avoid prefix false-positives
                        // (e.g. tenant "acme" must not match "tenant:acme-corp").
                        if (!scopeHasSegment(trueScope, tenantSegment)) continue;
                        if (workspaceSegment != null && !scopeHasSegment(trueScope, workspaceSegment)) continue;
                        if (appSegment != null && !scopeHasSegment(trueScope, appSegment)) continue;
                        if (workflowSegment != null && !scopeHasSegment(trueScope, workflowSegment)) continue;
                        if (agentSegment != null && !scopeHasSegment(trueScope, agentSegment)) continue;
                        if (toolsetSegment != null && !scopeHasSegment(trueScope, toolsetSegment)) continue;

                        String trueUnitsStr = budget.get("unit");
                        Enums.UnitEnum unit = Enums.UnitEnum.valueOf(trueUnitsStr);

                        long allocatedVal = Long.parseLong(budget.getOrDefault("allocated", "0"));
                        long remainingVal = Long.parseLong(budget.getOrDefault("remaining", "0"));
                        long reservedVal = Long.parseLong(budget.getOrDefault("reserved", "0"));
                        long spentVal = Long.parseLong(budget.getOrDefault("spent", "0"));
                        long debtVal = Long.parseLong(budget.getOrDefault("debt", "0"));
                        long overdraftLimitVal = Long.parseLong(budget.getOrDefault("overdraft_limit", "0"));
                        boolean isOverLimit = "true".equals(budget.getOrDefault("is_over_limit", "false"));

                        // spec: scope is the leaf identifier, scope_path is the full canonical path
                        balances.add(Balance.builder()
                            .scope(leafScope(trueScope))
                            .scopePath(trueScope)
                            .remaining(new SignedAmount(unit, remainingVal))
                            .reserved(new Amount(unit, reservedVal))
                            .spent(new Amount(unit, spentVal))
                            .allocated(new Amount(unit, allocatedVal))
                            .debt(debtVal > 0 ? new Amount(unit, debtVal) : null)
                            .overdraftLimit(overdraftLimitVal > 0 ? new Amount(unit, overdraftLimitVal) : null)
                            .isOverLimit(isOverLimit ? true : null)
                            .build());

                        if (balances.size() >= limit) {
                            String nextCursor = scan.getCursor();
                            if ("0".equals(nextCursor)) nextCursor = null;
                            // Spec: has_more is true only when next_cursor is present
                            return BalanceResponse.builder()
                                .balances(balances)
                                .hasMore(nextCursor != null)
                                .nextCursor(nextCursor)
                                .build();
                        }

                    } catch (IllegalArgumentException e) {
                        LOG.warn("Invalid enum value in budget key: {}", key, e);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse budget: {}", key, e);
                    }
                }
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));

            return BalanceResponse.builder()
                .balances(balances)
                .hasMore(false)
                .nextCursor(null)
                .build();
        }
    }

    public DecisionResponse decide(DecisionRequest request, String tenant) {
        LOG.debug("Evaluating decision for tenant: {}", tenant);

        try (Jedis jedis = jedisPool.getResource()) {
            // Idempotency: replay if same (tenant, key) seen before
            String idempotencyKey = request.getIdempotencyKey();
            String payloadHash = computePayloadHash(request);
            if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                String idemKey = "idem:" + tenant + ":decide:" + idempotencyKey;
                // Pipeline both GETs in a single round-trip
                Pipeline idemPipe = jedis.pipelined();
                Response<String> cachedResp = idemPipe.get(idemKey);
                Response<String> hashResp = idemPipe.get(idemKey + ":hash");
                idemPipe.sync();
                String cached = cachedResp.get();
                if (cached != null) {
                    // Spec MUST: detect payload mismatch on idempotent replay
                    if (!payloadHash.isEmpty()) {
                        String storedHash = hashResp.get();
                        if (storedHash != null && !storedHash.equals(payloadHash)) {
                            throw CyclesProtocolException.idempotencyMismatch();
                        }
                    }
                    return objectMapper.readValue(cached, DecisionResponse.class);
                }
            }

            List<String> affectedScopes = scopeService.deriveScopes(request.getSubject());
            long estimateAmount = request.getEstimate().getAmount();
            String unit = request.getEstimate().getUnit().name();

            DecisionResponse response = null;
            String deepestScope = affectedScopes.get(affectedScopes.size() - 1);

            // Pipeline all budget fetches + caps in one round-trip
            Pipeline pipeline = jedis.pipelined();
            Map<String, Response<Map<String, String>>> budgetResponses = new LinkedHashMap<>();
            for (String scope : affectedScopes) {
                budgetResponses.put(scope, pipeline.hgetAll("budget:" + scope + ":" + unit));
            }
            Response<List<String>> capsResponse = pipeline.hmget("budget:" + deepestScope + ":" + unit, "caps_json");
            pipeline.sync();

            // Skip scopes without budgets — operators may only define budgets at certain levels.
            // At least one scope must have a budget (consistent with reserve.lua).
            boolean foundBudgetDecide = false;
            for (Map.Entry<String, Response<Map<String, String>>> entry : budgetResponses.entrySet()) {
                Map<String, String> budget = entry.getValue().get();

                if (budget == null || budget.isEmpty()) {
                    continue; // Skip scopes without budgets
                }
                foundBudgetDecide = true;

                // Check budget status (consistent with admin FUND_LUA and reserve.lua)
                String budgetStatus = budget.getOrDefault("status", "ACTIVE");
                if ("FROZEN".equals(budgetStatus) || "CLOSED".equals(budgetStatus)) {
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode("FROZEN".equals(budgetStatus)
                            ? Enums.ReasonCode.BUDGET_FROZEN
                            : Enums.ReasonCode.BUDGET_CLOSED)
                        .affectedScopes(affectedScopes)
                        .build();
                    break;
                }
                if ("true".equals(budget.getOrDefault("is_over_limit", "false"))) {
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode(Enums.ReasonCode.OVERDRAFT_LIMIT_EXCEEDED)
                        .affectedScopes(affectedScopes)
                        .build();
                    break;
                }
                long debt = Long.parseLong(budget.getOrDefault("debt", "0"));
                long overdraftLimit = Long.parseLong(budget.getOrDefault("overdraft_limit", "0"));
                if (debt > 0 && overdraftLimit == 0) {
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode(Enums.ReasonCode.DEBT_OUTSTANDING)
                        .affectedScopes(affectedScopes)
                        .build();
                    break;
                }
                long remaining = Long.parseLong(budget.getOrDefault("remaining", "0"));
                if (remaining < estimateAmount) {
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode(Enums.ReasonCode.BUDGET_EXCEEDED)
                        .affectedScopes(affectedScopes)
                        .build();
                    break;
                }
            }

            if (!foundBudgetDecide && response == null) {
                // Distinguish "wrong unit" from "truly missing" — symmetric with reserve.lua/event.lua
                // probes. If any affected scope has a budget in a different unit, throw UNIT_MISMATCH
                // (400) so the client can self-correct. The decide endpoint's "MUST NOT return 409"
                // rule (spec line 1131-1134) is specific to debt/overdraft conditions; 400 UNIT_MISMATCH
                // is a request-validity error and is permitted — consistent with reserve/event paths.
                for (String scope : affectedScopes) {
                    List<String> expectedUnits = probeAlternateUnits(jedis, scope, unit);
                    if (!expectedUnits.isEmpty()) {
                        throw CyclesProtocolException.unitMismatch(scope, unit, expectedUnits);
                    }
                }
                response = DecisionResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode(Enums.ReasonCode.BUDGET_NOT_FOUND)
                    .affectedScopes(affectedScopes)
                    .build();
            }

            if (response == null) {
                // Check deepest scope for ALLOW_WITH_CAPS (operator-configured caps)
                List<String> capsList = capsResponse.get();
                String capsJson = (capsList != null && !capsList.isEmpty()) ? capsList.get(0) : null;
                if (capsJson != null && !capsJson.isEmpty()) {
                    Caps caps = objectMapper.readValue(capsJson, Caps.class);
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.ALLOW_WITH_CAPS)
                        .affectedScopes(affectedScopes)
                        .caps(caps)
                        .build();
                } else {
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.ALLOW)
                        .affectedScopes(affectedScopes)
                        .build();
                }
            }

            // Store idempotency result (24 h TTL) - pipeline both writes
            if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                String idemKey = "idem:" + tenant + ":decide:" + idempotencyKey;
                Pipeline idemWritePipe = jedis.pipelined();
                idemWritePipe.psetex(idemKey, 86400000L, objectMapper.writeValueAsString(response));
                if (!payloadHash.isEmpty()) {
                    idemWritePipe.psetex(idemKey + ":hash", 86400000L, payloadHash);
                }
                idemWritePipe.sync();
            }

            return response;
        } catch (CyclesProtocolException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to evaluate decision", e);
            throw new RuntimeException(e);
        }
    }

    public EventCreateResponse createEvent(EventCreateRequest request, String tenant) {
        LOG.debug("Creating event for tenant: {}", tenant);
        String overagePolicyTag = request.getOveragePolicy() != null ? request.getOveragePolicy().name() : "DEFAULT";

        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, Object> tenantConfig = getTenantConfig(jedis, tenant);
            String eventId = UUID.randomUUID().toString();
            List<String> affectedScopes = scopeService.deriveScopes(request.getSubject());
            String scopePath = affectedScopes.get(affectedScopes.size() - 1);

            String eventOveragePolicy = resolveOveragePolicy(request.getOveragePolicy(), tenantConfig);

            List<String> args = new ArrayList<>();
            args.add(eventId);
            args.add(objectMapper.writeValueAsString(request.getSubject()));
            args.add(objectMapper.writeValueAsString(request.getAction()));
            args.add(String.valueOf(request.getActual().getAmount()));
            args.add(request.getActual().getUnit().name());
            args.add(request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "");
            args.add(scopePath);
            args.add(tenant);
            args.add(eventOveragePolicy);
            // ARGV[10] = metrics_json, ARGV[11] = client_time_ms, ARGV[12] = payload_hash,
            // ARGV[13] = metadata_json, ARGV[14] = units_csv; scopes start at ARGV[15]
            args.add(request.getMetrics() != null
                ? objectMapper.writeValueAsString(request.getMetrics()) : "");
            args.add(request.getClientTimeMs() != null
                ? String.valueOf(request.getClientTimeMs()) : "");
            args.add(computePayloadHash(request));
            args.add(request.getMetadata() != null
                ? objectMapper.writeValueAsString(request.getMetadata()) : "");
            args.add(UNIT_CSV);
            args.addAll(affectedScopes);

            Object result = luaScripts.eval(jedis, "event", eventScript, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.debug("Event response: {}", response);

            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            // On idempotency hit, Lua returns existing event_id
            String responseEventId = response.containsKey("event_id") ?
                (String) response.get("event_id") : eventId;

            // Parse balances returned atomically from Lua (no extra round-trips)
            List<Balance> balances = parseLuaBalances(response, request.getActual().getUnit());

            // Lua returns charged amount (may be less than requested with ALLOW_IF_AVAILABLE capping)
            Amount charged = null;
            Number chargedNum = (Number) response.get("charged");
            if (chargedNum != null) {
                charged = new Amount(request.getActual().getUnit(), chargedNum.longValue());
            }

            Map<String, Long> scopeDebtIncurred = parseScopeDebtIncurred(response);

            metrics.recordEvent(tenant, "APPLIED", "OK", overagePolicyTag);
            // Any scope that actually accrued debt counts as overdraft incurred
            // (event path creates debt the same way commit does).
            boolean anyDebt = scopeDebtIncurred != null
                && scopeDebtIncurred.values().stream().anyMatch(v -> v != null && v > 0);
            if (anyDebt) {
                metrics.recordOverdraftIncurred(tenant);
            }
            return EventCreateResponse.builder()
                .status(Enums.EventStatus.APPLIED)
                .eventId(responseEventId)
                .charged(charged)
                .balances(balances)
                .scopeDebtIncurred(scopeDebtIncurred)
                .preRemaining(parsePreRemaining(response))
                .preIsOverLimit(parsePreIsOverLimit(response))
                .build();
        } catch (CyclesProtocolException e) {
            metrics.recordEvent(tenant, "DENY", e.getErrorCode().name(), overagePolicyTag);
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to create event", e);
            metrics.recordEvent(tenant, "DENY", "INTERNAL_ERROR", overagePolicyTag);
            throw new RuntimeException(e);
        }
    }

    public String findReservationTenantById(String reservationId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "reservation:res_" + reservationId;
            String tenant = jedis.hget(key, "tenant");
            LOG.debug("Resolved reservation tenant for: key={}, tenant={}", key, tenant);
            if (tenant == null) {
                throw CyclesProtocolException.notFound(reservationId);
            }
            return tenant;
        } catch (CyclesProtocolException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to search for reservation by id: reservationId={}",reservationId,e);
            throw new RuntimeException("Failed to resolve reservation tenant", e);
        }
    }

    /**
     * Extracts the leaf (deepest) segment from a canonical scope path.
     * e.g. "tenant:acme/workspace:dev/app:myapp" → "app:myapp"
     */
    private String leafScope(String scopePath) {
        int lastSlash = scopePath.lastIndexOf('/');
        return lastSlash >= 0 ? scopePath.substring(lastSlash + 1) : scopePath;
    }

    /**
     * Returns true when the scope path contains the given segment at an exact boundary.
     * Segment boundaries are "/" or start/end of string, preventing prefix false-positives
     * (e.g. "tenant:acme" must not match "tenant:acme-corp").
     */
    private boolean scopeHasSegment(String scopePath, String segment) {
        int idx = scopePath.indexOf(segment);
        if (idx < 0) return false;
        int end = idx + segment.length();
        boolean startOk = idx == 0 || scopePath.charAt(idx - 1) == '/';
        boolean endOk = end == scopePath.length() || scopePath.charAt(end) == '/';
        return startOk && endOk;
    }

    private ReservationSummary toSummary(ReservationDetail detail) {
        return ReservationSummary.builder()
            .reservationId(detail.getReservationId())
            .status(detail.getStatus())
            .idempotencyKey(detail.getIdempotencyKey())
            .subject(detail.getSubject())
            .action(detail.getAction())
            .reserved(detail.getReserved())
            .createdAtMs(detail.getCreatedAtMs())
            .expiresAtMs(detail.getExpiresAtMs())
            .scopePath(detail.getScopePath())
            .affectedScopes(detail.getAffectedScopes())
            .build();
    }

    private ReservationDetail buildReservationSummary(Map<String, String> fields) throws Exception {
        String estimateUnitStr = fields.get("estimate_unit");
        String estimateAmountStr = fields.get("estimate_amount");
        String stateStr = fields.get("state");
        String subjectJson = fields.get("subject_json");
        String actionJson = fields.get("action_json");
        String createdAtStr = fields.get("created_at");
        String expiresAtStr = fields.get("expires_at");
        if (estimateUnitStr == null || estimateAmountStr == null || stateStr == null
                || subjectJson == null || actionJson == null
                || createdAtStr == null || expiresAtStr == null) {
            throw new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR,
                "Reservation data is corrupted: reservationId=" + fields.get("reservation_id"), 500);
        }
        Enums.UnitEnum unit = Enums.UnitEnum.valueOf(estimateUnitStr);
        long estimateAmount = Long.parseLong(estimateAmountStr);

        String affectedScopesJson = fields.get("affected_scopes");
        List<String> affectedScopes = affectedScopesJson != null
            ? objectMapper.readValue(affectedScopesJson, List.class)
            : Collections.emptyList();

        // Build committed amount if reservation was finalized
        Amount committed = null;
        Long finalizedAtMs = null;
        String chargedAmountStr = fields.get("charged_amount");
        if (chargedAmountStr != null) {
            committed = new Amount(unit, Long.parseLong(chargedAmountStr));
        }
        String committedAtStr = fields.get("committed_at");
        if (committedAtStr != null) {
            finalizedAtMs = Long.parseLong(committedAtStr);
        }
        String releasedAtStr = fields.get("released_at");
        if (releasedAtStr != null) {
            finalizedAtMs = Long.parseLong(releasedAtStr);
        }

        // Parse metadata if present
        Map<String, Object> metadata = null;
        String metadataJson = fields.get("metadata_json");
        if (metadataJson != null && !metadataJson.isEmpty()) {
            metadata = objectMapper.readValue(metadataJson, Map.class);
        }

        ReservationDetail detail = new ReservationDetail(committed, finalizedAtMs, metadata);
        detail.setReservationId(fields.get("reservation_id"));
        detail.setStatus(Enums.ReservationStatus.valueOf(stateStr));
        detail.setIdempotencyKey(fields.get("idempotency_key"));
        detail.setSubject(objectMapper.readValue(subjectJson, Subject.class));
        detail.setAction(objectMapper.readValue(actionJson, Action.class));
        detail.setReserved(new Amount(unit, estimateAmount));
        detail.setCreatedAtMs(Long.parseLong(createdAtStr));
        detail.setExpiresAtMs(Long.parseLong(expiresAtStr));
        detail.setScopePath(fields.get("scope_path"));
        detail.setAffectedScopes(affectedScopes);
        return detail;
    }

    /**
     * Compute a SHA-256 hex digest of the canonical JSON serialization of a request object.
     * Used for idempotency payload mismatch detection (spec MUST).
     */
    private String computePayloadHash(Object request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            MessageDigest digest = SHA256_DIGEST.get();
            digest.reset();
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            LOG.warn("Failed to compute payload hash, skipping mismatch detection", e);
            return "";
        }
    }

    /**
     * Fetch current balances for a list of scopes and unit.
     * Used to populate the optional balances field in mutation responses.
     */
    private List<Balance> fetchBalancesForScopes(Jedis jedis, List<String> scopes, Enums.UnitEnum unit) {
        if (scopes == null || scopes.isEmpty()) return Collections.emptyList();

        // Pipeline all HGETALL calls into a single round-trip
        Pipeline pipeline = jedis.pipelined();
        Map<String, Response<Map<String, String>>> responses = new LinkedHashMap<>();
        for (String scope : scopes) {
            String budgetKey = "budget:" + scope + ":" + unit.name();
            responses.put(scope, pipeline.hgetAll(budgetKey));
        }
        pipeline.sync();

        List<Balance> balances = new ArrayList<>();
        for (Map.Entry<String, Response<Map<String, String>>> entry : responses.entrySet()) {
            String scope = entry.getKey();
            Map<String, String> budget = entry.getValue().get();
            if (budget != null && !budget.isEmpty()) {
                long debtVal = Long.parseLong(budget.getOrDefault("debt", "0"));
                long overdraftLimitVal = Long.parseLong(budget.getOrDefault("overdraft_limit", "0"));
                boolean isOverLimit = "true".equals(budget.getOrDefault("is_over_limit", "false"));
                balances.add(Balance.builder()
                    .scope(leafScope(scope))
                    .scopePath(scope)
                    .remaining(new SignedAmount(unit, Long.parseLong(budget.getOrDefault("remaining", "0"))))
                    .reserved(new Amount(unit, Long.parseLong(budget.getOrDefault("reserved", "0"))))
                    .spent(new Amount(unit, Long.parseLong(budget.getOrDefault("spent", "0"))))
                    .allocated(new Amount(unit, Long.parseLong(budget.getOrDefault("allocated", "0"))))
                    .debt(debtVal > 0 ? new Amount(unit, debtVal) : null)
                    .overdraftLimit(overdraftLimitVal > 0 ? new Amount(unit, overdraftLimitVal) : null)
                    .isOverLimit(isOverLimit ? true : null)
                    .build());
            }
        }
        return balances;
    }

    /**
     * Parse balance snapshots returned by Lua scripts (reserve/commit/release).
     * Falls back to empty list if balances are not present in the response.
     */
    @SuppressWarnings("unchecked")
    /**
     * Extract per-scope debt_incurred from Lua balance entries.
     * Returns a map of scope → debt incurred during this operation.
     */
    /**
     * Extract per-scope pre-mutation remaining from Lua balance entries.
     * Used for transition detection: emit budget.exhausted only when pre_remaining > 0 && remaining == 0.
     */
    private Map<String, Long> parsePreRemaining(Map<String, Object> response) {
        Object balancesObj = response.get("balances");
        if (balancesObj == null || !(balancesObj instanceof List)) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> luaBalances = (List<Map<String, Object>>) balancesObj;
        Map<String, Long> result = new java.util.HashMap<>();
        for (Map<String, Object> lb : luaBalances) {
            String scope = (String) lb.get("scope");
            long preRemaining = ((Number) lb.getOrDefault("pre_remaining", 0)).longValue();
            result.put(scope, preRemaining);
        }
        return result;
    }

    /**
     * Extract per-scope pre-mutation is_over_limit from Lua balance entries.
     * Used for transition detection: emit budget.over_limit_entered only when pre=false && post=true.
     */
    private Map<String, Boolean> parsePreIsOverLimit(Map<String, Object> response) {
        Object balancesObj = response.get("balances");
        if (balancesObj == null || !(balancesObj instanceof List)) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> luaBalances = (List<Map<String, Object>>) balancesObj;
        Map<String, Boolean> result = new java.util.HashMap<>();
        for (Map<String, Object> lb : luaBalances) {
            String scope = (String) lb.get("scope");
            boolean preOverLimit = Boolean.TRUE.equals(lb.get("pre_is_over_limit"));
            result.put(scope, preOverLimit);
        }
        return result;
    }

    private Map<String, Long> parseScopeDebtIncurred(Map<String, Object> response) {
        Object balancesObj = response.get("balances");
        if (balancesObj == null || !(balancesObj instanceof List)) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> luaBalances = (List<Map<String, Object>>) balancesObj;
        Map<String, Long> result = new java.util.HashMap<>();
        for (Map<String, Object> lb : luaBalances) {
            String scope = (String) lb.get("scope");
            long debtIncurred = ((Number) lb.getOrDefault("debt_incurred", 0)).longValue();
            if (debtIncurred > 0) {
                result.put(scope, debtIncurred);
            }
        }
        return result;
    }

    private List<Balance> parseLuaBalances(Map<String, Object> response, Enums.UnitEnum unit) {
        Object balancesObj = response.get("balances");
        if (balancesObj == null || !(balancesObj instanceof List)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> luaBalances = (List<Map<String, Object>>) balancesObj;
        List<Balance> balances = new ArrayList<>();
        for (Map<String, Object> lb : luaBalances) {
            String scope = (String) lb.get("scope");
            long debtVal = ((Number) lb.getOrDefault("debt", 0)).longValue();
            long overdraftLimitVal = ((Number) lb.getOrDefault("overdraft_limit", 0)).longValue();
            boolean isOverLimit = Boolean.TRUE.equals(lb.get("is_over_limit"));
            balances.add(Balance.builder()
                .scope(leafScope(scope))
                .scopePath(scope)
                .remaining(new SignedAmount(unit, ((Number) lb.getOrDefault("remaining", 0)).longValue()))
                .reserved(new Amount(unit, ((Number) lb.getOrDefault("reserved", 0)).longValue()))
                .spent(new Amount(unit, ((Number) lb.getOrDefault("spent", 0)).longValue()))
                .allocated(new Amount(unit, ((Number) lb.getOrDefault("allocated", 0)).longValue()))
                .debt(debtVal > 0 ? new Amount(unit, debtVal) : null)
                .overdraftLimit(overdraftLimitVal > 0 ? new Amount(unit, overdraftLimitVal) : null)
                .isOverLimit(isOverLimit ? true : null)
                .build());
        }
        return balances;
    }

    /**
     * Fetches tenant configuration from Redis with in-memory cache.
     * Returns null if tenant record is not found (caller uses hardcoded defaults).
     */
    @Value("${cycles.tenant-config.cache-ttl-ms:60000}")
    private long tenantConfigCacheTtlMs;

    private volatile com.github.benmanes.caffeine.cache.Cache<String, Optional<Map<String, Object>>> tenantConfigCache;

    private com.github.benmanes.caffeine.cache.Cache<String, Optional<Map<String, Object>>> getTenantConfigCache() {
        if (tenantConfigCache == null) {
            synchronized (this) {
                if (tenantConfigCache == null) {
                    var builder = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                            .maximumSize(1_000);
                    if (tenantConfigCacheTtlMs > 0) {
                        builder.expireAfterWrite(java.time.Duration.ofMillis(tenantConfigCacheTtlMs));
                    }
                    tenantConfigCache = builder.build();
                }
            }
        }
        return tenantConfigCache;
    }

    private Map<String, Object> getTenantConfig(Jedis jedis, String tenant) {
        if (tenantConfigCacheTtlMs > 0) {
            Optional<Map<String, Object>> cached = getTenantConfigCache().getIfPresent(tenant);
            if (cached != null) {
                return cached.orElse(null);
            }
        }
        try {
            String tenantJson = jedis.get("tenant:" + tenant);
            Map<String, Object> config = null;
            if (tenantJson != null) {
                config = objectMapper.readValue(tenantJson, Map.class);
            }
            if (tenantConfigCacheTtlMs > 0) {
                getTenantConfigCache().put(tenant, Optional.ofNullable(config));
            }
            return config;
        } catch (Exception e) {
            LOG.warn("Failed to read tenant config for tenant: {}", tenant, e);
        }
        return null;
    }

    /**
     * Resolve the effective overage policy: use the request-level policy if provided,
     * otherwise fall back to the tenant's default_commit_overage_policy, then ALLOW_IF_AVAILABLE.
     */
    private String resolveOveragePolicy(Enums.CommitOveragePolicy requestPolicy, Map<String, Object> tenantConfig) {
        if (requestPolicy != null) {
            return requestPolicy.name();
        }
        if (tenantConfig != null) {
            Object defaultPolicy = tenantConfig.get("default_commit_overage_policy");
            if (defaultPolicy != null && !defaultPolicy.toString().isEmpty()) {
                return defaultPolicy.toString();
            }
        }
        return "ALLOW_IF_AVAILABLE";
    }

    /**
     * Resolve effective TTL: use request value if provided, else tenant default, else 60000ms.
     * Then cap to tenant's max_reservation_ttl_ms (default 3600000ms).
     */
    private long resolveReservationTtl(Long requestTtlMs, Map<String, Object> tenantConfig) {
        long defaultTtl = 60000L;
        long maxTtl = 3600000L;
        if (tenantConfig != null) {
            Object tenantDefault = tenantConfig.get("default_reservation_ttl_ms");
            if (tenantDefault != null) {
                defaultTtl = ((Number) tenantDefault).longValue();
            }
            Object tenantMax = tenantConfig.get("max_reservation_ttl_ms");
            if (tenantMax != null) {
                maxTtl = ((Number) tenantMax).longValue();
            }
        }
        long ttl = requestTtlMs != null ? requestTtlMs : defaultTtl;
        return Math.min(ttl, maxTtl);
    }

    /**
     * For the "no budgeted scope found at the requested unit" error path: probe the fixed
     * UnitEnum set to discover whether any other unit has a budget at this scope. Used by
     * the Java-side paths ({@code evaluateDryRun}, {@code decide}) to mirror the probe in
     * reserve.lua / event.lua so all four entry points speak the same UNIT_MISMATCH dialect.
     *
     * @return list of unit names that have a budget stored at {@code scope} and are not
     *         equal to {@code requestedUnit}. Empty if the scope has no budget in any unit.
     */
    private List<String> probeAlternateUnits(Jedis jedis, String scope, String requestedUnit) {
        List<String> expectedUnits = new ArrayList<>();
        for (Enums.UnitEnum u : Enums.UnitEnum.values()) {
            if (u.name().equals(requestedUnit)) continue;
            if (jedis.exists("budget:" + scope + ":" + u.name())) {
                expectedUnits.add(u.name());
            }
        }
        return expectedUnits;
    }

    /**
     * Resolve max_reservation_extensions from tenant config (default 10).
     */
    private int resolveMaxExtensions(Map<String, Object> tenantConfig) {
        if (tenantConfig != null) {
            Object maxExt = tenantConfig.get("max_reservation_extensions");
            if (maxExt != null) {
                return ((Number) maxExt).intValue();
            }
        }
        return 10;
    }

    private void handleScriptError(Map<String, Object> response) {
        String error = (String) response.get("error");
        String message = response.getOrDefault("message", error).toString();

        switch (error) {
            case "BUDGET_EXCEEDED":
                throw CyclesProtocolException.budgetExceeded(message);
            case "OVERDRAFT_LIMIT_EXCEEDED":
                throw CyclesProtocolException.overdraftLimitExceeded(message);
            case "DEBT_OUTSTANDING":
                throw CyclesProtocolException.debtOutstanding(message);
            case "NOT_FOUND":
                throw CyclesProtocolException.notFound(message);
            case "RESERVATION_FINALIZED":
                throw CyclesProtocolException.reservationFinalized("Reservation already finalized");
            case "BUDGET_NOT_FOUND":
                String budgetScope = response.containsKey("scope") ? response.get("scope").toString() : "unknown";
                throw CyclesProtocolException.budgetNotFound(budgetScope);
            case "BUDGET_FROZEN":
                String frozenScope = response.containsKey("scope") ? response.get("scope").toString() : "unknown";
                throw CyclesProtocolException.budgetFrozen(frozenScope);
            case "BUDGET_CLOSED":
                String closedScope = response.containsKey("scope") ? response.get("scope").toString() : "unknown";
                throw CyclesProtocolException.budgetClosed(closedScope);
            case "IDEMPOTENCY_MISMATCH":
                throw CyclesProtocolException.idempotencyMismatch();
            case "UNIT_MISMATCH": {
                // reserve.lua and event.lua populate scope/requested_unit/expected_units so the
                // client can self-correct. commit.lua does not (legacy), so fall back to the
                // no-detail factory when those fields are absent.
                if (response.containsKey("requested_unit") || response.containsKey("expected_units")) {
                    String mismatchScope = response.containsKey("scope") ? response.get("scope").toString() : null;
                    String requestedUnit = response.containsKey("requested_unit") ? response.get("requested_unit").toString() : null;
                    @SuppressWarnings("unchecked")
                    List<String> expectedUnits = response.get("expected_units") instanceof List
                        ? (List<String>) response.get("expected_units") : null;
                    throw CyclesProtocolException.unitMismatch(mismatchScope, requestedUnit, expectedUnits);
                }
                throw CyclesProtocolException.unitMismatch();
            }
            case "RESERVATION_EXPIRED":
                throw CyclesProtocolException.reservationExpired();
            case "RESERVATION_EXPIRATION_NOT_FOUND":
                throw CyclesProtocolException.reservationExpirationNotFound();
            case "MAX_EXTENSIONS_EXCEEDED":
                throw new CyclesProtocolException(Enums.ErrorCode.MAX_EXTENSIONS_EXCEEDED, message, 409);
            default:
                throw new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR, "Script error: " + error, 500);
        }
    }
}
