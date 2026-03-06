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

    public ReservationCreateResponse createReservation(ReservationCreateRequest request, String tenant) {
        LOG.info("Creating reservation for tenant: {}", tenant);

        try (Jedis jedis = jedisPool.getResource()) {
            String reservationId = UUID.randomUUID().toString();
            List<String> affectedScopes = request.getAffectedScopes() != null ?
                request.getAffectedScopes() : scopeService.deriveScopes(request.getSubject());
            LOG.info("Affected scopes:affectedScopes={},derivedScopes={}",affectedScopes,scopeService.deriveScopes(request.getSubject()));
            String scopePath = affectedScopes.get(affectedScopes.size() - 1);
            String overagePolicy = request.getOveragePolicy() != null ? request.getOveragePolicy().name() : "REJECT";

            // Build args for Lua script
            List<String> args = new ArrayList<>();
            args.add(reservationId);
            args.add(objectMapper.writeValueAsString(request.getSubject()));
            args.add(objectMapper.writeValueAsString(request.getAction()));
            args.add(String.valueOf(request.getEstimate().getAmount()));
            args.add(request.getEstimate().getUnit().name());
            args.add(String.valueOf(request.getTtlMs() != null ? request.getTtlMs() : 60000));
            args.add(String.valueOf(request.getGraceMs() != null ? request.getGraceMs() : 5000));
            args.add(request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "");
            args.add(scopePath);
            args.add(tenant);
            args.add(overagePolicy);
            args.addAll(affectedScopes);

            Object result = jedis.eval(reserveScript, 0, args.toArray(new String[0]));
            LOG.info("Direct Response from create:{}",result);
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.info("Response from create:{}",response);
            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            return ReservationCreateResponse.builder()
                .decision("ALLOW")
                .reservationId(reservationId)
                .affectedScopes(affectedScopes)
                .scopePath(scopePath)
                .reserved(request.getEstimate())
                .expiresAtMs((Long) response.get("expires_at"))
                .build();
        } catch (CyclesProtocolException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to create reservation", e);
            throw new RuntimeException(e);
        }
    }

    public CommitResponse commitReservation(String reservationId, CommitRequest request) {
        LOG.info("Committing reservation: {}", reservationId);

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> args = Arrays.asList(
                reservationId,
                String.valueOf(request.getActual().getAmount()),
                request.getActual().getUnit().name(),
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : ""
            );

            Object result = jedis.eval(commitScript, 0, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);

            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            long chargedAmount = ((Number) response.get("charged")).longValue();

            return CommitResponse.builder()
                .status("COMMITTED")
                .charged(new Amount(request.getActual().getUnit(), chargedAmount))
                .build();
        }
        catch (CyclesProtocolException e){
            LOG.error("Failed logic to commit reservation", e);
            throw e;
        }
        catch (Exception e) {
            LOG.error("Failed to commit reservation", e);
            throw new RuntimeException(e);
        }
    }

    public ReleaseResponse releaseReservation(String reservationId, ReleaseRequest request) {
        LOG.info("Releasing reservation: {}", reservationId);

        try (Jedis jedis = jedisPool.getResource()) {
            // Fetch estimate before releasing so we can report released amount
            String reservationKey = "reservation:res_" + reservationId;
            String estimateAmountStr = jedis.hget(reservationKey, "estimate_amount");
            String estimateUnitStr = jedis.hget(reservationKey, "estimate_unit");

            List<String> args = Arrays.asList(
                reservationId,
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : ""
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

            return ReleaseResponse.builder()
                .status("RELEASED")
                .released(releasedAmount)
                .build();
        } catch (CyclesProtocolException e){
            LOG.error("Failed logic to release reservation:reservationId={},req={}",reservationId,request,e);
            throw e;
        }
        catch (Exception e) {
            LOG.error("Failed to release reservation:reservationId={},req={}",reservationId,request,e);
            throw new RuntimeException(e);
        }
    }

    public ReservationExtendResponse extendReservation(String reservationId, ReservationExtendRequest request) {
        LOG.info("Extending reservation: {}", reservationId);

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> args = Arrays.asList(
                reservationId,
                String.valueOf(request.getExtendByMs()),
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : ""
            );

            Object result = jedis.eval(extendScript, 0, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.info("Response from extend script: response={}",response);
            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            return ReservationExtendResponse.builder()
                .status("ACTIVE")
                .expiresAtMs(((Number) response.get("expires_at_ms")).longValue())
                .build();
        } catch (CyclesProtocolException e){
            LOG.error("Failed logic to extend reservation:reservationId={},req={}",reservationId,request,e);
            throw e;
        }
        catch (Exception e) {
            LOG.error("Failed to extend reservation: reservationId={},request={},msg={}",reservationId,request,e.getMessage(),e);
            throw new RuntimeException(e);
        }
    }

    public List<ReservationSummary> listReservations(String tenant, int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("reservation:res_*").count(100);
            List<ReservationSummary> result = new ArrayList<>();
            String cursor = "0";

            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                List<String> keys = scan.getResult();

                if (keys.isEmpty()) {
                    cursor = scan.getCursor();
                    continue;
                }

                // Pipeline: Batch all HGETALL calls
                Pipeline pipeline = jedis.pipelined();
                Map<String, Response<Map<String, String>>> responses = new HashMap<>();

                for (String key : keys) {
                    responses.put(key, pipeline.hgetAll(key));
                }

                pipeline.sync();

                // Process results
                for (String key : keys) {
                    try {
                        Map<String, String> fields = responses.get(key).get();

                        if (fields.isEmpty()) continue;

                        String reservationTenant = fields.get("tenant");
                        if (!tenant.equals(reservationTenant)) continue;

                        String estimateUnitStr = fields.get("estimate_unit");
                        Enums.UnitEnum unit = Enums.UnitEnum.valueOf(estimateUnitStr);
                        long estimateAmount = Long.parseLong(fields.get("estimate_amount"));

                        String affectedScopesJson = fields.get("affected_scopes");
                        List<String> affectedScopes = affectedScopesJson != null
                            ? objectMapper.readValue(affectedScopesJson, List.class)
                            : Collections.emptyList();

                        result.add(ReservationSummary.builder()
                                .reservationId(fields.get("reservation_id"))
                                .status(Enums.ReservationState.valueOf(fields.get("state")))
                                .subject(objectMapper.readValue(fields.get("subject_json"), Subject.class))
                                .action(objectMapper.readValue(fields.get("action_json"), Action.class))
                                .reserved(new Amount(unit, estimateAmount))
                                .createdAtMs(Long.parseLong(fields.get("created_at")))
                                .expiresAtMs(Long.parseLong(fields.get("expires_at")))
                                .scopePath(fields.get("scope_path"))
                                .affectedScopes(affectedScopes)
                                .build());

                        if (result.size() >= limit) break;

                    } catch (Exception e) {
                        LOG.warn("Failed to parse reservation: {}", key, e);
                    }
                }

                cursor = scan.getCursor();
            } while (!"0".equals(cursor) && result.size() < limit);

            return result;
        }
    }

    public List<Balance> getBalances(String tenant) {
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("budget:*").count(100);
            List<Balance> balances = new ArrayList<>();
            String cursor = "0";

            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                for (String key : scan.getResult()) {
                    try {
                        LOG.info("Processing: key={}",key);
                        // Check key type before attempting to read
                        String keyType = jedis.type(key);

                        if ("none".equals(keyType)) {
                            LOG.debug("Key {} does not exist, skipping", key);
                            continue;
                        }

                        if (!"hash".equals(keyType)) {
                            LOG.warn("Expected hash type but found '{}' for key: {}", keyType, key);
                            continue;
                        }

                        // Parse key structure: budget:tenant:<scope>:<unit>
                        String[] keyParts = key.split(":", 4);
                        if (keyParts.length < 4) {
                            LOG.warn("Invalid budget key format: {}", key);
                            continue;
                        }

                        String scope = keyParts[2];

                        // Filter by tenant
                        if (tenant == null || !scope.contains(tenant)) {
                            continue;
                        }

                        // Read budget data as hash
                        Map<String, String> budget = jedis.hgetAll(key);
                        if (budget.isEmpty()) {
                            LOG.debug("Empty hash for key: {}", key);
                            continue;
                        }
                        String trueScope = budget.get("scope");
                        String trueUnitsStr = budget.get("unit");
                        Enums.UnitEnum unit = Enums.UnitEnum.valueOf(trueUnitsStr);

                        long allocatedVal = Long.parseLong(budget.getOrDefault("allocated", "0"));
                        long remainingVal = Long.parseLong(budget.getOrDefault("remaining", "0"));
                        long reservedVal = Long.parseLong(budget.getOrDefault("reserved", "0"));
                        long spentVal = Long.parseLong(budget.getOrDefault("spent", "0"));
                        long debtVal = Long.parseLong(budget.getOrDefault("debt", "0"));
                        long overdraftLimitVal = Long.parseLong(budget.getOrDefault("overdraft_limit", "0"));
                        boolean isOverLimit = "true".equals(budget.getOrDefault("is_over_limit", "false"));

                        // Build Balance object using Amount/SignedAmount per spec
                        balances.add(Balance.builder()
                            .scope(trueScope)
                            .remaining(new SignedAmount(unit, remainingVal))
                            .reserved(new Amount(unit, reservedVal))
                            .spent(new Amount(unit, spentVal))
                            .allocated(new Amount(unit, allocatedVal))
                            .debt(debtVal > 0 ? new Amount(unit, debtVal) : null)
                            .overdraftLimit(overdraftLimitVal > 0 ? new Amount(unit, overdraftLimitVal) : null)
                            .isOverLimit(isOverLimit ? true : null)
                            .build());

                    } catch (IllegalArgumentException e) {
                        LOG.warn("Invalid enum value in budget key: {}", key, e);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse budget: {}", key, e);
                    }
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));

            return balances;
        }
    }

    public String findReservationTenantById (String reservationId){
        try(Jedis jedis = jedisPool.getResource()){
            String key = "reservation:res_"+reservationId;
            String tenant =jedis.hget(key,"tenant");
            LOG.info("Resolved reservation tenant for: key={}, tenant={}",key,tenant);
            return tenant;
        }catch (Exception e){
            LOG.error("Failed to search for reservation by id: reservationId={}",reservationId,e);
            return null;
        }
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
