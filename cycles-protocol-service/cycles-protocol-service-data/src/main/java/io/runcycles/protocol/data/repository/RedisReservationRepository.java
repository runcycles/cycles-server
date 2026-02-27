package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.service.ScopeDerivationService;
import io.runcycles.protocol.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.model.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Instant;
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
            LOG.info("Affectes scopes:affectedScopes={},derivedScopes={}",affectedScopes,scopeService.deriveScopes(request.getSubject()));
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
            args.add(affectedScopes.get(affectedScopes.size() - 1)); // scope_path
            args.add(tenant);
            //args.add(affectedScopes.get(affectedScopes.size() - 1));
            args.addAll(affectedScopes); //all affected scopes are added, according to taxonomy provided in subject, but that means that appropriate budgets must be created as well
            //Example: [tenant:ecosystem-saulius-1, tenant:ecosystem-saulius-1/workspace:development, tenant:ecosystem-saulius-1/workspace:development/app:scalerx
            //If such taxonomy support is not needed then add only last one i.e. the dedicated which was provided in the request then no hierarchy support

            Object result = jedis.eval(reserveScript, 0, args.toArray(new String[0]));
            LOG.info("Direct Response from create:{}",result);
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.info("Response from create:{}",response);
            if (response.containsKey("error")) {
                handleScriptError(response);
            }
            
            return ReservationCreateResponse.builder()
                .reservationId(reservationId)
                .state(Enums.ReservationState.RESERVED)
                .subject(request.getSubject())
                .action(request.getAction())
                .estimate(request.getEstimate())
                .affectedScopes(affectedScopes)
                .expiresAt(Instant.ofEpochMilli((Long) response.get("expires_at")))
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
                request.getOveragePolicy() != null ? request.getOveragePolicy().name() : "REJECT",
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : ""
            );
            
            Object result = jedis.eval(commitScript, 0, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            
            if (response.containsKey("error")) {
                handleScriptError(response);
            }
            
            return CommitResponse.builder()
                .reservationId(reservationId)
                .state(Enums.ReservationState.COMMITTED)
                .actual(request.getActual())
                .charged(new Amount(request.getActual().getUnit(), ((Number) response.get("charged")).longValue()))
                .debtIncurred(((Number) response.get("debt_incurred")).longValue())
                .committedAt(Instant.now())
                .build();
        }
        catch (CyclesProtocolException e){
            LOG.error("Failed logic to commit reservation", e);
            throw e ;
        }
        catch (Exception e) {
            LOG.error("Failed to commit reservation", e);
            throw new RuntimeException(e);
        }
    }
    
    public ReleaseResponse releaseReservation(String reservationId, ReleaseRequest request) {
        LOG.info("Releasing reservation: {}", reservationId);
        
        try (Jedis jedis = jedisPool.getResource()) {
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
            
            return ReleaseResponse.builder()
                .reservationId(reservationId)
                .state(Enums.ReservationState.RELEASED)
                .releasedAt(Instant.now())
                .build();
        }catch (CyclesProtocolException e){
            LOG.error("Failed logic to release reservation:reservationId={},req={}",reservationId,request,e);
            throw e ;
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
                String.valueOf(request.getAdditionalTtlMs()),
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : ""
            );
            
            Object result = jedis.eval(extendScript, 0, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);
            LOG.info("Response from extend script: response={}",response);
            if (response.containsKey("error")) {
                handleScriptError(response);
            }
            
            return ReservationExtendResponse.builder()
                .reservationId(reservationId)
                .newExpiresAt(Instant.ofEpochMilli(((Number) response.get("new_expires_at")).longValue()))
                .extendedAt(Instant.ofEpochMilli(((Number) response.get("extended_at")).longValue()))
                .build();
        }catch (CyclesProtocolException e){
            LOG.error("Failed logic to extend reservation:reservationId={},req={}",reservationId,request,e);
            throw e ;
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
                for (String key : scan.getResult()) {
                    try {
                        LOG.info("Reservation: key={},jedis.type(key)={}",key,jedis.type(key));
                        String reservationTenant = jedis.hget(key, "tenant");
                        LOG.info("Reservation: tenant={}",tenant);
                        if (!"hash".equals(jedis.type(key))) continue;
                        //String reservationTenant = jedis.hget(key, "tenant");

                        if (tenant.equals(reservationTenant)) {
                            String reservationId = jedis.hget(key, "reservation_id");
                            String state = jedis.hget(key, "state");
                            String subjectJson = jedis.hget(key, "subject_json");
                            String actionJson = jedis.hget(key, "action_json");
                            long estimateAmount = Long.parseLong(jedis.hget(key, "estimate_amount"));
                            String estimateUnit = jedis.hget(key, "estimate_unit");
                            long createdAt = Long.parseLong(jedis.hget(key, "created_at"));
                            long expiresAt = Long.parseLong(jedis.hget(key, "expires_at"));
                            
                            result.add(ReservationSummary.builder()
                                .reservationId(reservationId)
                                .state(Enums.ReservationState.valueOf(state))
                                .subject(objectMapper.readValue(subjectJson, Subject.class))
                                .action(objectMapper.readValue(actionJson, Action.class))
                                .estimate(new Amount(Enums.UnitEnum.valueOf(estimateUnit), estimateAmount))
                                .createdAt(Instant.ofEpochMilli(createdAt))
                                .expiresAt(Instant.ofEpochMilli(expiresAt))
                                .build());
                            
                            if (result.size() >= limit) break;
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to parse reservation: {}", key, e);
                    }
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor) && result.size() < limit);
            
            return result;
        } catch (Exception e) {
            LOG.error("Failed to acquire reservations:tenant={}",tenant,e);
            throw new RuntimeException(e);
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
                        if (!scope.contains(tenant)) {
                            continue;
                        }
                        
                        // Read budget data as hash
                        Map<String, String> budget = jedis.hgetAll(key);
                        if (budget.isEmpty()) {
                            LOG.debug("Empty hash for key: {}", key);
                            continue;
                        }
                        String trueScope = budget.get("scope") ;
                        String trueUnitsStr = budget.get("unit") ;

                        // Build Balance object
                        balances.add(Balance.builder()
                            .scope(trueScope)
                            .unit(Enums.UnitEnum.valueOf(trueUnitsStr))
                            .allocated(Long.parseLong(budget.getOrDefault("allocated", "0")))
                            .remaining(Long.parseLong(budget.getOrDefault("remaining", "0")))
                            .reserved(Long.parseLong(budget.getOrDefault("reserved", "0")))
                            .spent(Long.parseLong(budget.getOrDefault("spent", "0")))
                            .debt(Long.parseLong(budget.getOrDefault("debt", "0")))
                            .overdraftLimit(Long.parseLong(budget.getOrDefault("overdraft_limit", "0")))
                            .isOverLimit("true".equals(budget.getOrDefault("is_over_limit", "false")))
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
            default:
                throw new RuntimeException("Script error: " + error);
        }
    }
}
