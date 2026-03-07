package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.service.ScopeDerivationService;
import io.runcycles.protocol.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Cycles Protocol v0.1.23 - Repository with Lua script execution */
@Repository
public class RedisReservationRepository {
    private static final Logger LOG = LoggerFactory.getLogger(RedisReservationRepository.class);

    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ScopeDerivationService scopeService;
    @Autowired @Qualifier("reserveLuaScript") private String reserveScript;
    @Autowired @Qualifier("commitLuaScript") private String commitScript;
    @Autowired @Qualifier("releaseLuaScript") private String releaseScript;
    @Autowired @Qualifier("extendLuaScript") private String extendScript;
    @Autowired @Qualifier("eventLuaScript") private String eventScript;

    public ReservationCreateResponse createReservation(ReservationCreateRequest request, String tenant) {
        LOG.info("Creating reservation for tenant: {}", tenant);

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> affectedScopes = scopeService.deriveScopes(request.getSubject());
            String scopePath = affectedScopes.get(affectedScopes.size() - 1);
            String overagePolicy = request.getOveragePolicy() != null ? request.getOveragePolicy().name() : "REJECT";

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
            args.add(String.valueOf(request.getTtlMs() != null ? request.getTtlMs() : 60000));
            args.add(String.valueOf(request.getGracePeriodMs() != null ? request.getGracePeriodMs() : 5000));
            args.add(request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "");
            args.add(scopePath);
            args.add(tenant);
            args.add(overagePolicy);
            // ARGV[12] = metadata_json, ARGV[13] = payload_hash; scopes start at ARGV[14]
            args.add(request.getMetadata() != null
                ? objectMapper.writeValueAsString(request.getMetadata()) : "");
            args.add(computePayloadHash(request));
            args.addAll(affectedScopes);

            Object result = jedis.eval(reserveScript, 0, args.toArray(new String[0]));
            LOG.info("Direct Response from create:{}",result);
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.info("Response from create:{}",response);
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
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.ALLOW)
                    .reservationId(existingId)
                    .affectedScopes(existing.getAffectedScopes())
                    .scopePath(existing.getScopePath())
                    .reserved(existing.getReserved())
                    .expiresAtMs(existing.getExpiresAtMs())
                    .build();
            }

            return ReservationCreateResponse.builder()
                .decision(Enums.DecisionEnum.ALLOW)
                .reservationId(reservationId)
                .affectedScopes(affectedScopes)
                .scopePath(scopePath)
                .reserved(request.getEstimate())
                .expiresAtMs(((Number) response.get("expires_at")).longValue())
                .build();
        } catch (CyclesProtocolException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to create reservation", e);
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
            String cached = jedis.get(idemKey);
            if (cached != null) {
                // Spec MUST: detect payload mismatch on idempotent replay
                if (!payloadHash.isEmpty()) {
                    String storedHash = jedis.get(idemKey + ":hash");
                    if (storedHash != null && !storedHash.equals(payloadHash)) {
                        throw CyclesProtocolException.idempotencyMismatch();
                    }
                }
                return objectMapper.readValue(cached, ReservationCreateResponse.class);
            }
        }

        for (String scope : affectedScopes) {
            String budgetKey = "budget:" + scope + ":" + unit;
            Map<String, String> budget = jedis.hgetAll(budgetKey);

            if (budget == null || budget.isEmpty()) {
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode("BUDGET_NOT_FOUND")
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build();
            }
            if ("true".equals(budget.getOrDefault("is_over_limit", "false"))) {
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode("OVERDRAFT_LIMIT_EXCEEDED")
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build();
            }
            long debt = Long.parseLong(budget.getOrDefault("debt", "0"));
            if (debt > 0) {
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode("DEBT_OUTSTANDING")
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build();
            }
            long remaining = Long.parseLong(budget.getOrDefault("remaining", "0"));
            if (remaining < estimateAmount) {
                return ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode("BUDGET_EXCEEDED")
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build();
            }
        }

        // Collect current balances for all affected scopes
        List<Balance> balances = new ArrayList<>();
        for (String scope : affectedScopes) {
            String budgetKey = "budget:" + scope + ":" + unit;
            Map<String, String> budget = jedis.hgetAll(budgetKey);
            if (budget != null && !budget.isEmpty()) {
                Enums.UnitEnum unitEnum = request.getEstimate().getUnit();
                balances.add(Balance.builder()
                    .scope(scope)
                    .scopePath(scope)
                    .remaining(new SignedAmount(unitEnum, Long.parseLong(budget.getOrDefault("remaining", "0"))))
                    .reserved(new Amount(unitEnum, Long.parseLong(budget.getOrDefault("reserved", "0"))))
                    .spent(new Amount(unitEnum, Long.parseLong(budget.getOrDefault("spent", "0"))))
                    .allocated(new Amount(unitEnum, Long.parseLong(budget.getOrDefault("allocated", "0"))))
                    .build());
            }
        }

        ReservationCreateResponse dryRunResponse = ReservationCreateResponse.builder()
            .decision(Enums.DecisionEnum.ALLOW)
            .affectedScopes(affectedScopes)
            .scopePath(scopePath)
            .reserved(request.getEstimate())
            .balances(balances)
            .build();

        // Cache dry_run result (24 h TTL)
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            String idemKey = "idem:" + tenant + ":dry_run:" + idempotencyKey;
            jedis.set(idemKey, objectMapper.writeValueAsString(dryRunResponse));
            jedis.pexpire(idemKey, 86400000L);
            if (!payloadHash.isEmpty()) {
                jedis.set(idemKey + ":hash", payloadHash);
                jedis.pexpire(idemKey + ":hash", 86400000L);
            }
        }

        return dryRunResponse;
    }

    public CommitResponse commitReservation(String reservationId, CommitRequest request) {
        LOG.info("Committing reservation: {}", reservationId);

        try (Jedis jedis = jedisPool.getResource()) {
            // Pre-fetch estimate and affected scopes for released amount and balances
            String reservationKey = "reservation:res_" + reservationId;
            List<String> prefetch = jedis.hmget(reservationKey, "estimate_amount", "estimate_unit", "affected_scopes");
            String estimateAmountStr = prefetch.get(0);
            String estimateUnitStr = prefetch.get(1);
            String affectedScopesJson = prefetch.get(2);

            List<String> args = Arrays.asList(
                reservationId,
                String.valueOf(request.getActual().getAmount()),
                request.getActual().getUnit().name(),
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "",
                computePayloadHash(request),
                request.getMetrics() != null ? objectMapper.writeValueAsString(request.getMetrics()) : "",
                request.getMetadata() != null ? objectMapper.writeValueAsString(request.getMetadata()) : ""
            );

            Object result = jedis.eval(commitScript, 0, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);

            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            long chargedAmount = ((Number) response.get("charged")).longValue();

            Amount released = null;
            if (estimateAmountStr != null) {
                long est = Long.parseLong(estimateAmountStr);
                if (chargedAmount < est) {
                    released = new Amount(request.getActual().getUnit(), est - chargedAmount);
                }
            }

            // Populate balances snapshot for operator visibility
            List<Balance> balances = null;
            if (affectedScopesJson != null && estimateUnitStr != null) {
                List<String> scopes = objectMapper.readValue(affectedScopesJson, List.class);
                balances = fetchBalancesForScopes(jedis, scopes, Enums.UnitEnum.valueOf(estimateUnitStr));
            }

            return CommitResponse.builder()
                .status("COMMITTED")
                .charged(new Amount(request.getActual().getUnit(), chargedAmount))
                .released(released)
                .balances(balances)
                .build();
        } catch (CyclesProtocolException e){
            LOG.error("Failed logic to commit reservation", e);
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to commit reservation", e);
            throw new RuntimeException(e);
        }
    }

    public ReleaseResponse releaseReservation(String reservationId, ReleaseRequest request) {
        LOG.info("Releasing reservation: {}", reservationId);

        try (Jedis jedis = jedisPool.getResource()) {
            String reservationKey = "reservation:res_" + reservationId;
            List<String> prefetch = jedis.hmget(reservationKey, "estimate_amount", "estimate_unit", "affected_scopes");
            String estimateAmountStr = prefetch.get(0);
            String estimateUnitStr = prefetch.get(1);
            String affectedScopesJson = prefetch.get(2);

            List<String> args = Arrays.asList(
                reservationId,
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "",
                computePayloadHash(request)
            );

            Object result = jedis.eval(releaseScript, 0, args.toArray(new String[0]));
            LOG.info("Release result:{}",result);
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);

            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            Amount releasedAmount = null;
            if (estimateAmountStr != null && estimateUnitStr != null) {
                releasedAmount = new Amount(
                    Enums.UnitEnum.valueOf(estimateUnitStr),
                    Long.parseLong(estimateAmountStr)
                );
            }

            // Populate balances snapshot for operator visibility
            List<Balance> balances = null;
            if (affectedScopesJson != null && estimateUnitStr != null) {
                List<String> scopes = objectMapper.readValue(affectedScopesJson, List.class);
                balances = fetchBalancesForScopes(jedis, scopes, Enums.UnitEnum.valueOf(estimateUnitStr));
            }

            return ReleaseResponse.builder()
                .status("RELEASED")
                .released(releasedAmount)
                .balances(balances)
                .build();
        } catch (CyclesProtocolException e){
            LOG.error("Failed logic to release reservation:reservationId={},req={}",reservationId,request,e);
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to release reservation:reservationId={},req={}",reservationId,request,e);
            throw new RuntimeException(e);
        }
    }

    public ReservationExtendResponse extendReservation(String reservationId, ReservationExtendRequest request, String tenant) {
        LOG.info("Extending reservation: {}", reservationId);

        try (Jedis jedis = jedisPool.getResource()) {
            // Pre-fetch affected scopes and unit for balances
            String reservationKey = "reservation:res_" + reservationId;
            List<String> prefetch = jedis.hmget(reservationKey, "estimate_unit", "affected_scopes");
            String estimateUnitStr = prefetch.get(0);
            String affectedScopesJson = prefetch.get(1);

            List<String> args = Arrays.asList(
                reservationId,
                String.valueOf(request.getExtendByMs()),
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "",
                tenant != null ? tenant : "",
                computePayloadHash(request),
                request.getMetadata() != null ? objectMapper.writeValueAsString(request.getMetadata()) : ""
            );

            Object result = jedis.eval(extendScript, 0, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.info("Response from extend script: response={}",response);
            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            // Populate balances snapshot for operator visibility
            List<Balance> balances = null;
            if (affectedScopesJson != null && estimateUnitStr != null) {
                List<String> scopes = objectMapper.readValue(affectedScopesJson, List.class);
                balances = fetchBalancesForScopes(jedis, scopes, Enums.UnitEnum.valueOf(estimateUnitStr));
            }

            return ReservationExtendResponse.builder()
                .status("ACTIVE")
                .expiresAtMs(((Number) response.get("expires_at_ms")).longValue())
                .balances(balances)
                .build();
        } catch (CyclesProtocolException e){
            LOG.error("Failed logic to extend reservation:reservationId={},req={}",reservationId,request,e);
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to extend reservation: reservationId={},request={},msg={}",reservationId,request,e.getMessage(),e);
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

    public ReservationListResponse listReservations(String tenant, String idempotencyKey,
                                                    String status, String workspace, String app,
                                                    String workflow, String agent, String toolset,
                                                    int limit, String startCursor) {
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("reservation:res_*").count(100);
            List<ReservationSummary> result = new ArrayList<>();
            String cursor = (startCursor != null && !startCursor.isBlank()) ? startCursor : "0";

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
                            String scopePath = fields.getOrDefault("scope_path", "");
                            if (workspace != null && !scopeHasSegment(scopePath, "workspace:" + workspace.toLowerCase())) continue;
                            if (app != null && !scopeHasSegment(scopePath, "app:" + app.toLowerCase())) continue;
                            if (workflow != null && !scopeHasSegment(scopePath, "workflow:" + workflow.toLowerCase())) continue;
                            if (agent != null && !scopeHasSegment(scopePath, "agent:" + agent.toLowerCase())) continue;
                            if (toolset != null && !scopeHasSegment(scopePath, "toolset:" + toolset.toLowerCase())) continue;

                            result.add(toSummary(buildReservationSummary(fields)));

                            if (result.size() >= limit) {
                                String nextCursor = scan.getCursor();
                                if ("0".equals(nextCursor)) nextCursor = null;
                                // hasMore is always true here: we stopped early due to limit,
                                // so there may be unprocessed keys in this or future scan pages.
                                return ReservationListResponse.builder()
                                    .reservations(result)
                                    .hasMore(true)
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

    public BalanceResponse getBalances(String tenant, String workspace, String app,
                                            String workflow, String agent, String toolset,
                                            boolean includeChildren, int limit, String startCursor) {
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("budget:*").count(100);
            List<Balance> balances = new ArrayList<>();
            String cursor = (startCursor != null && !startCursor.isBlank()) ? startCursor : "0";

            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                for (String key : scan.getResult()) {
                    try {
                        String keyType = jedis.type(key);
                        if (!"hash".equals(keyType)) continue;

                        Map<String, String> budget = jedis.hgetAll(key);
                        if (budget.isEmpty()) continue;

                        String trueScope = budget.get("scope");
                        if (trueScope == null) continue;

                        // Filter by tenant and optional subject fields.
                        // Use exact segment boundary checks to avoid prefix false-positives
                        // (e.g. tenant "acme" must not match "tenant:acme-corp").
                        // Scope paths are lowercased; filter values are lowercased for comparison.
                        if (!scopeHasSegment(trueScope, "tenant:" + tenant.toLowerCase())) continue;
                        if (workspace != null && !scopeHasSegment(trueScope, "workspace:" + workspace.toLowerCase())) continue;
                        if (app != null && !scopeHasSegment(trueScope, "app:" + app.toLowerCase())) continue;
                        if (workflow != null && !scopeHasSegment(trueScope, "workflow:" + workflow.toLowerCase())) continue;
                        if (agent != null && !scopeHasSegment(trueScope, "agent:" + agent.toLowerCase())) continue;
                        if (toolset != null && !scopeHasSegment(trueScope, "toolset:" + toolset.toLowerCase())) continue;

                        String trueUnitsStr = budget.get("unit");
                        Enums.UnitEnum unit = Enums.UnitEnum.valueOf(trueUnitsStr);

                        long allocatedVal = Long.parseLong(budget.getOrDefault("allocated", "0"));
                        long remainingVal = Long.parseLong(budget.getOrDefault("remaining", "0"));
                        long reservedVal = Long.parseLong(budget.getOrDefault("reserved", "0"));
                        long spentVal = Long.parseLong(budget.getOrDefault("spent", "0"));
                        long debtVal = Long.parseLong(budget.getOrDefault("debt", "0"));
                        long overdraftLimitVal = Long.parseLong(budget.getOrDefault("overdraft_limit", "0"));
                        boolean isOverLimit = "true".equals(budget.getOrDefault("is_over_limit", "false"));

                        // spec requires both scope and scope_path; trueScope is the full canonical path
                        balances.add(Balance.builder()
                            .scope(trueScope)
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
                            // hasMore is always true here: we stopped early due to limit,
                            // so there may be unprocessed keys in this or future scan pages.
                            return BalanceResponse.builder()
                                .balances(balances)
                                .hasMore(true)
                                .nextCursor(nextCursor)
                                .build();
                        }

                    } catch (IllegalArgumentException e) {
                        LOG.warn("Invalid enum value in budget key: {}", key, e);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse budget: {}", key, e);
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
        LOG.info("Evaluating decision for tenant: {}", tenant);

        try (Jedis jedis = jedisPool.getResource()) {
            // Idempotency: replay if same (tenant, key) seen before
            String idempotencyKey = request.getIdempotencyKey();
            String payloadHash = computePayloadHash(request);
            if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                String idemKey = "idem:" + tenant + ":decide:" + idempotencyKey;
                String cached = jedis.get(idemKey);
                if (cached != null) {
                    // Spec MUST: detect payload mismatch on idempotent replay
                    if (!payloadHash.isEmpty()) {
                        String storedHash = jedis.get(idemKey + ":hash");
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
            for (String scope : affectedScopes) {
                String budgetKey = "budget:" + scope + ":" + unit;
                Map<String, String> budget = jedis.hgetAll(budgetKey);

                if (budget == null || budget.isEmpty()) {
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode("BUDGET_NOT_FOUND")
                        .affectedScopes(affectedScopes)
                        .build();
                    break;
                }
                if ("true".equals(budget.getOrDefault("is_over_limit", "false"))) {
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode("OVERDRAFT_LIMIT_EXCEEDED")
                        .affectedScopes(affectedScopes)
                        .build();
                    break;
                }
                long debt = Long.parseLong(budget.getOrDefault("debt", "0"));
                if (debt > 0) {
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode("DEBT_OUTSTANDING")
                        .affectedScopes(affectedScopes)
                        .build();
                    break;
                }
                long remaining = Long.parseLong(budget.getOrDefault("remaining", "0"));
                if (remaining < estimateAmount) {
                    response = DecisionResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode("BUDGET_EXCEEDED")
                        .affectedScopes(affectedScopes)
                        .build();
                    break;
                }
            }

            if (response == null) {
                // Check deepest scope for ALLOW_WITH_CAPS (operator-configured caps)
                String deepestBudgetKey = "budget:" + deepestScope + ":" + unit;
                String capsJson = jedis.hget(deepestBudgetKey, "caps_json");
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

            // Store idempotency result (24 h TTL)
            if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                String idemKey = "idem:" + tenant + ":decide:" + idempotencyKey;
                jedis.set(idemKey, objectMapper.writeValueAsString(response));
                jedis.pexpire(idemKey, 86400000L);
                if (!payloadHash.isEmpty()) {
                    jedis.set(idemKey + ":hash", payloadHash);
                    jedis.pexpire(idemKey + ":hash", 86400000L);
                }
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
        LOG.info("Creating event for tenant: {}", tenant);

        try (Jedis jedis = jedisPool.getResource()) {
            String eventId = UUID.randomUUID().toString();
            List<String> affectedScopes = scopeService.deriveScopes(request.getSubject());
            String scopePath = affectedScopes.get(affectedScopes.size() - 1);

            String eventOveragePolicy = request.getOveragePolicy() != null ? request.getOveragePolicy().name() : "REJECT";

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
            // ARGV[10] = metrics_json, ARGV[11] = client_time_ms, ARGV[12] = payload_hash, ARGV[13] = metadata_json; scopes start at ARGV[14]
            args.add(request.getMetrics() != null
                ? objectMapper.writeValueAsString(request.getMetrics()) : "");
            args.add(request.getClientTimeMs() != null
                ? String.valueOf(request.getClientTimeMs()) : "");
            args.add(computePayloadHash(request));
            args.add(request.getMetadata() != null
                ? objectMapper.writeValueAsString(request.getMetadata()) : "");
            args.addAll(affectedScopes);

            Object result = jedis.eval(eventScript, 0, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.info("Response from event script: {}", response);

            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            // On idempotency hit, Lua returns existing event_id
            String responseEventId = response.containsKey("event_id") ?
                (String) response.get("event_id") : eventId;

            // Populate balances snapshot for operator visibility
            List<Balance> balances = fetchBalancesForScopes(jedis, affectedScopes, request.getActual().getUnit());

            return EventCreateResponse.builder()
                .status("APPLIED")
                .eventId(responseEventId)
                .balances(balances)
                .build();
        } catch (CyclesProtocolException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to create event", e);
            throw new RuntimeException(e);
        }
    }

    public String findReservationTenantById(String reservationId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "reservation:res_" + reservationId;
            String tenant = jedis.hget(key, "tenant");
            LOG.info("Resolved reservation tenant for: key={}, tenant={}",key,tenant);
            return tenant;
        } catch (Exception e) {
            LOG.error("Failed to search for reservation by id: reservationId={}",reservationId,e);
            throw new RuntimeException("Failed to resolve reservation tenant", e);
        }
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
        List<Balance> balances = new ArrayList<>();
        for (String scope : scopes) {
            String budgetKey = "budget:" + scope + ":" + unit.name();
            Map<String, String> budget = jedis.hgetAll(budgetKey);
            if (budget != null && !budget.isEmpty()) {
                long debtVal = Long.parseLong(budget.getOrDefault("debt", "0"));
                long overdraftLimitVal = Long.parseLong(budget.getOrDefault("overdraft_limit", "0"));
                boolean isOverLimit = "true".equals(budget.getOrDefault("is_over_limit", "false"));
                balances.add(Balance.builder()
                    .scope(scope)
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
                throw CyclesProtocolException.budgetNotFound("Incorrect budget owner scope");
            case "IDEMPOTENCY_MISMATCH":
                throw CyclesProtocolException.idempotencyMismatch();
            case "UNIT_MISMATCH":
                throw CyclesProtocolException.unitMismatch();
            case "RESERVATION_EXPIRED":
                throw CyclesProtocolException.reservationExpired();
            case "RESERVATION_EXPIRATION_NOT_FOUND":
                throw CyclesProtocolException.reservationExpirationNotFound();
            default:
                throw new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR, "Script error: " + error, 500);
        }
    }
}
