package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.repository.support.ReservationListQuery;
import io.runcycles.protocol.data.repository.support.ScanPageCursor;
import io.runcycles.protocol.data.service.EvidenceEmitter;
import io.runcycles.protocol.data.service.LuaScriptRegistry;
import io.runcycles.protocol.data.service.ReservationCreatedAtIndexService;
import io.runcycles.protocol.data.service.ScopeDerivationService;
import io.runcycles.protocol.data.util.HashProjections;
import io.runcycles.protocol.data.util.LogSanitizer;
import io.runcycles.protocol.data.util.ScopePathMatcher;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.audit.AuditLogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
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
    @Autowired private AuditRepository auditRepository;
    @Autowired private ReservationCreatedAtIndexService reservationCreatedAtIndex;
    @Autowired private RedisReservationQueryRepository reservationQueryRepository;
    @Autowired private ReservationHashMapper reservationHashMapper;
    // CyclesEvidence (cycles-evidence-v0.1): computing + emitting + stamping +
    // caching the `reserve` evidence lives HERE, inside the reservation-creation
    // flow, so it is part of the same idempotent unit — replay returns the cached
    // ORIGINAL response (with its evidence) and the body cache shares the Lua
    // idempotency TTL. Covers dry_run too (its ALLOW/DENY is `reserve` evidence).
    @Autowired private EvidenceEmitter evidenceEmitter;
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

    private static final long IDEMPOTENCY_CACHE_TTL_MS = 86_400_000L;
    // commit/release are terminal: reserve/commit/release.lua set a 30-day TTL on the
    // finalized reservation hash (audit trail), so the commit/release idempotent replay is
    // valid for 30 days. The evidence body cache must match so it never expires before the
    // reservation hash that drives the Lua replay.
    private static final long TERMINAL_BODY_CACHE_TTL_MS = 2_592_000_000L;
    private static final int RESERVE_BODY_CACHE_WAIT_ATTEMPTS = 4;
    // Shared by the non-persisting idempotent-evaluation path (reserve dry_run + decide): a fresh
    // evaluator atomically claims `idem:<tenant>:<kind>:<key>` with a pending marker, others
    // wait for the cached result. Marker prefix is per-kind (`__<kind>_pending__:`).
    private static final int IDEMPOTENT_EVAL_WAIT_ATTEMPTS = 4;
    private static final long IDEMPOTENCY_CACHE_WAIT_SLEEP_MS = 25L;
    private static final long IDEMPOTENT_EVAL_PENDING_TTL_MS = 60_000L;
    private static final String COMPARE_AND_DELETE_SCRIPT =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "return redis.call('DEL', KEYS[1]) else return 0 end";
    private static final String CACHE_IDEMPOTENT_WITH_EVIDENCE_SCRIPT =
        "if ARGV[5] == '' then return 0 end " +
        "local current = redis.call('GET', KEYS[1]) " +
        "if current and current ~= ARGV[5] then return 0 end " +
        "if ARGV[4] ~= '' then local qt = redis.call('TYPE', KEYS[3]).ok " +
        "if qt ~= 'none' and qt ~= 'list' then return -1 end end " +
        "if ARGV[1] ~= '' then redis.call('PSETEX', KEYS[2], ARGV[2], ARGV[1]) end " +
        "redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[3]) " +
        "if ARGV[4] ~= '' then redis.call('LPUSH', KEYS[3], ARGV[4]) end return 1";
    private static final String CACHE_LIFECYCLE_WITH_EVIDENCE_SCRIPT =
        "local rt = redis.call('TYPE', KEYS[2]).ok " +
        "local qt = redis.call('TYPE', KEYS[3]).ok " +
        "if rt ~= 'hash' or (qt ~= 'none' and qt ~= 'list') then return -1 end " +
        "if redis.call('HGET', KEYS[2], ARGV[8]) ~= 'PENDING' then return 0 end " +
        "redis.call('PSETEX', KEYS[1], ARGV[1], ARGV[2]) " +
        "redis.call('HSET', KEYS[2], ARGV[3], ARGV[4], ARGV[5], ARGV[6], " +
            "ARGV[8], 'EVIDENCE') " +
        "redis.call('LPUSH', KEYS[3], ARGV[7]) return 1";
    private static final String FINALIZE_LIFECYCLE_BASE_SCRIPT =
        "if redis.call('TYPE', KEYS[2]).ok ~= 'hash' then return -1 end " +
        "if redis.call('HGET', KEYS[2], ARGV[3]) ~= 'PENDING' then return 0 end " +
        "redis.call('PSETEX', KEYS[1], ARGV[1], ARGV[2]) " +
        "redis.call('HSET', KEYS[2], ARGV[3], 'BASE') return 1";
    private static final String RESPONSE_STATE_PENDING = "PENDING";
    private static final String RESPONSE_STATE_BASE = "BASE";
    private static final String RESPONSE_STATE_EVIDENCE = "EVIDENCE";

    @Value("${cycles.evidence.queue.pending-key:evidence:pending}")
    private String evidencePendingKey = "evidence:pending";

    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    });

    /** Back-compat overload (no trace context) — used by tests and any non-HTTP caller. */
    public ReservationCreateResponse createReservation(ReservationCreateRequest request, String tenant) {
        return createReservation(request, tenant, null);
    }

    /**
     * @param traceId W3C trace correlation id for the CyclesEvidence envelope, or
     *                {@code null}/blank to omit. Threaded from the HTTP layer.
     */
    public ReservationCreateResponse createReservation(ReservationCreateRequest request, String tenant, String traceId) {
        LOG.debug("Creating reservation for tenant: {}", tenant);
        String overagePolicyTag = request.getOveragePolicy() != null ? request.getOveragePolicy().name() : "DEFAULT";

        try {
            // dry_run: evaluate budget without persisting a reservation. Its ALLOW/DENY
            // outcome IS captured as `reserve` evidence (cycles-evidence-v0.1) — a dry_run
            // DENY is the canonical signed "would this be allowed?" attestation. On a fresh
            // evaluation, stamp evidence + cache the full body in the reserve endpoint's shared
            // idempotency namespace, so changing dry_run with the same key is a mismatch.
            // so a replay returns the ORIGINAL stamped payload; a replay is returned verbatim.
            if (Boolean.TRUE.equals(request.getDryRun())) {
                List<String> affectedScopes = scopeService.deriveScopes(request.getSubject());
                String scopePath = affectedScopes.get(affectedScopes.size() - 1);
                return createDryRunReservation(request, tenant, traceId, affectedScopes, scopePath);
            }

            Jedis jedis = null;
            try {
                jedis = jedisPool.getResource();
                Map<String, Object> tenantConfig = getTenantConfig(jedis, tenant);
                List<String> affectedScopes = scopeService.deriveScopes(request.getSubject());
                String scopePath = affectedScopes.get(affectedScopes.size() - 1);
                String overagePolicy = resolveOveragePolicy(request.getOveragePolicy(), tenantConfig);
                long effectiveTtl = resolveReservationTtl(request.getTtlMs(), tenantConfig);
                int maxExtensions = resolveMaxExtensions(tenantConfig);

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
                if (!response.containsKey("expires_at")) {
                    String existingId = (String) response.get("reservation_id");
                    metrics.recordReserve(tenant, Enums.DecisionEnum.ALLOW.name(), "IDEMPOTENT_REPLAY", overagePolicyTag);
                    jedis.close();
                    jedis = null;

                    // Spec (NORMATIVE): replay MUST return the ORIGINAL successful response
                    // payload. Prefer the full original response cached at first create —
                    // byte-identical body (original balances + original cycles_evidence), so
                    // it matches the CyclesEvidence envelope the evidence_id points to.
                    ReservationCreateResponse original = readCachedReserveResponse(existingId);
                    if (original != null) {
                        return original;
                    }
                    ReservationCreateResponse restored = restoreReserveResponse(
                        existingId, response.get("response_snapshot"),
                        replayCacheTtl(response.get("response_cache_ttl_ms")));
                    if (restored != null) {
                        return restored;
                    }
                    throw idempotencyReplayUnavailable();
                }

                response.putIfAbsent("estimate_amount", request.getEstimate().getAmount());
                response.putIfAbsent("estimate_unit", request.getEstimate().getUnit().name());
                response.putIfAbsent("scope_path", scopePath);
                response.putIfAbsent("affected_scopes", affectedScopes);
                if (!response.containsKey("caps_json")) {
                    String deepestBudgetKey = "budget:" + scopePath + ":" + request.getEstimate().getUnit().name();
                    response.put("caps_json", jedis.hget(deepestBudgetKey, "caps_json"));
                }
                ReservationCreateResponse created = buildReserveResponse(response);

                metrics.recordReserve(tenant, created.getDecision().name(), "OK", overagePolicyTag);
                // Fresh reserve: emit + stamp evidence (over the pre-evidence body), then cache
                // the WHOLE response so an idempotent replay returns it verbatim. Cache for the
                // SAME TTL as reserve.lua's idempotency mapping (max(ttl_ms+grace_ms, 24h)) so the
                // body never expires before the idempotency key.
                // Release the Lua connection FIRST: evidence emit (push) and the body cache each
                // acquire their own short-lived connection, so we never hold two pool connections
                // at once (which would starve the pool under concurrent fresh ops).
                long graceMs = request.getGracePeriodMs() != null ? request.getGracePeriodMs() : 5000;
                long bodyTtlMs = Math.max(effectiveTtl + graceMs, 86400000L);
                jedis.close();
                jedis = null;
                persistReserveResponse(created, request, traceId, bodyTtlMs);
                return created;
            } finally {
                if (jedis != null) {
                    jedis.close();
                }
            }
        } catch (CyclesProtocolException e) {
            metrics.recordReserve(tenant, "DENY", e.getErrorCode().name(), overagePolicyTag);
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to create reservation: tenant={} dry_run={} idempotency_key_present={} overage_policy={} trace_id={}",
                    LogSanitizer.sanitize(tenant), request != null ? request.getDryRun() : null,
                    request != null && request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank(),
                    overagePolicyTag, traceId, e);
            metrics.recordReserve(tenant, "DENY", "INTERNAL_ERROR", overagePolicyTag);
            throw new RuntimeException(e);
        }
    }

    /** Cache key for the full original reserve response body. Keyed by RESERVATION_ID
     *  (not idempotency_key) so it stays consistent with reserve.lua's idempotency
     *  mapping: the Lua replay returns the CURRENT reservation_id and we read THAT
     *  reservation's body. Keying by idempotency_key would go stale across an
     *  idempotency-key expiry + re-reserve (the cache would still hold the prior
     *  reservation's body). Distinct namespace from reserve.lua's {@code idem:*} keys. */
    private static String reserveResponseCacheKey(String reservationId) {
        return "reserve:body:" + reservationId;
    }

    // ---- Shared non-persisting idempotent-evaluation machinery (dry_run + decide) ----

    /**
     * Tenant-status gate for the shared non-persisting evaluations (dry_run +
     * decide) - the Java-side twin of the in-script Lua guard on the four
     * mutation paths. Rule 2's 409 scopes MUTATIONS, so a closed tenant does
     * not 409 here; the evaluation answers truthfully instead: the canonical
     * signed "would this be allowed?" attestation for a closed tenant is
     * decision=DENY with reason_code=TENANT_CLOSED (runtime spec revision
     * v0.1.25.13, runcycles/cycles-protocol#125, which adds TENANT_CLOSED to
     * the documented DecisionReasonCode values). Without this gate a post-flip
     * dry_run could stamp a SIGNED ALLOW attestation for a request whose live
     * execution MUST fail with 409 TENANT_CLOSED.
     *
     * <p>Reads {@code tenant:<id>} fresh on the caller's connection (never the
     * 60s tenant-config cache). Absent record: {@code null} (runtime-only
     * deployment, no gate). Present but malformed - undecodable JSON,
     * non-object, missing/non-string status, or a status outside the closed
     * TenantStatus enum (ACTIVE|SUSPENDED|CLOSED) - fails closed with 500
     * INTERNAL_ERROR before any evidence is stamped: the server cannot attest
     * against corrupt governance state. Same whitelist as the Lua guards.
     *
     * @return {@link Enums.ReasonCode#TENANT_CLOSED} when the owning tenant is
     *         CLOSED (caller builds the 200-DENY), else {@code null} (proceed)
     */
    private Enums.ReasonCode evaluateTenantStatusGate(Jedis jedis, String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return null;
        }
        String tenantJson = jedis.get("tenant:" + tenant);
        if (tenantJson == null) {
            return null;
        }
        String status = null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(tenantJson);
            if (node != null && node.isObject() && node.hasNonNull("status")
                    && node.get("status").isTextual()) {
                status = node.get("status").asText();
            }
        } catch (Exception e) {
            // fall through with status == null -> fail closed below
        }
        if ("CLOSED".equals(status)) {
            return Enums.ReasonCode.TENANT_CLOSED;
        }
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR,
                "Malformed tenant record: tenant:" + tenant, 500);
        }
        return null;
    }

    private static String idempotencyCacheKey(String kind, String tenant, String idempotencyKey) {
        return "idem:" + tenant + ":" + kind + ":" + idempotencyKey;
    }

    private static String pendingPrefix(String kind) {
        return "__" + kind + "_pending__:";
    }

    /** Outcome of trying to claim a fresh evaluation slot for an idempotency_key. */
    private record IdemClaim(String cachedBody, String claimKey, String claimMarker, boolean pending) {
        static IdemClaim replay(String cachedBody) { return new IdemClaim(cachedBody, null, null, false); }
        static IdemClaim claimed(String claimKey, String marker) { return new IdemClaim(null, claimKey, marker, false); }
        static IdemClaim waiting() { return new IdemClaim(null, null, null, true); }
        static IdemClaim noKey() { return new IdemClaim(null, null, null, false); }
    }

    /**
     * Atomically resolve the idempotency state for a non-persisting evaluation (dry_run/decide):
     * a cached non-pending body → {@code replay}; a pending marker → {@code pending} (caller waits);
     * otherwise {@code SET NX} a pending claim → {@code claimed} (caller evaluates) or, if another
     * request won the race, {@code pending}. No {@code idempotency_key} → {@code noKey} (fresh, no claim).
     */
    private IdemClaim acquireIdempotencyClaim(Jedis jedis, String kind, String tenant,
                                              String idempotencyKey, String payloadHash) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return IdemClaim.noKey();
        }
        String idemKey = idempotencyCacheKey(kind, tenant, idempotencyKey);
        Pipeline idemPipe = jedis.pipelined();
        Response<String> cachedResp = idemPipe.get(idemKey);
        Response<String> hashResp = idemPipe.get(idemKey + ":hash");
        String legacyDryRunKey = "reserve".equals(kind)
            ? idempotencyCacheKey("dry_run", tenant, idempotencyKey)
            : null;
        Response<String> legacyDryRunResp = legacyDryRunKey != null
            ? idemPipe.get(legacyDryRunKey)
            : null;
        Response<String> legacyDryRunHashResp = legacyDryRunKey != null
            ? idemPipe.get(legacyDryRunKey + ":hash")
            : null;
        idemPipe.sync();
        String cached = cachedResp.get();
        if (cached != null) {
            if (isPending(kind, cached)) {
                validatePendingMarker(kind, cached, payloadHash);
                return IdemClaim.waiting();
            }
            validateCachedHash(hashResp.get(), payloadHash);
            // The shared reserve key has two durable value shapes: live requests store a
            // reservation UUID while dry-runs store the canonical JSON response. Reaching the
            // Java dry-run reader with a non-JSON value proves the same endpoint key was first
            // used by a live request, even if the independently-expiring :hash companion was
            // lost. Fail as a payload mismatch instead of repeatedly trying to parse the UUID.
            if ("reserve".equals(kind) && !isJsonObject(cached)) {
                throw CyclesProtocolException.idempotencyMismatch();
            }
            return IdemClaim.replay(cached);
        }
        // Rolling-upgrade bridge for pre-v0.1.25.51 dry-run entries. Those rows live for at
        // most 24 hours; reading them in the same pipeline preserves their original response
        // and mismatch semantics without adding a Redis round trip. Live reserve performs the
        // symmetric legacy check inside reserve.lua.
        String legacyDryRunBody = legacyDryRunResp != null ? legacyDryRunResp.get() : null;
        if (legacyDryRunBody != null) {
            validateCachedHash(legacyDryRunHashResp.get(), payloadHash);
            return IdemClaim.replay(legacyDryRunBody);
        }
        String marker = pendingMarker(kind, payloadHash);
        String claim = jedis.set(idemKey, marker,
            SetParams.setParams().nx().px(IDEMPOTENT_EVAL_PENDING_TTL_MS));
        return "OK".equalsIgnoreCase(claim) ? IdemClaim.claimed(idemKey, marker) : IdemClaim.waiting();
    }

    private void validateCachedHash(String storedHash, String payloadHash) {
        if (payloadHash != null && !payloadHash.isEmpty()
                && storedHash != null && !storedHash.equals(payloadHash)) {
            throw CyclesProtocolException.idempotencyMismatch();
        }
    }

    private static boolean isJsonObject(String value) {
        return value != null && value.stripLeading().startsWith("{");
    }

    private ReservationCreateResponse createDryRunReservation(ReservationCreateRequest request,
                                                              String tenant,
                                                              String traceId,
                                                              List<String> affectedScopes,
                                                              String scopePath) throws Exception {
        DryRunResult dryRun;
        try (Jedis jedis = jedisPool.getResource()) {
            dryRun = evaluateDryRun(jedis, request, affectedScopes, scopePath, tenant);
        }

        if (dryRun.pending()) {
            String body = waitForIdempotentReplayBody("reserve", dryRun.claimKey(), dryRun.payloadHash());
            if (body != null) {
                ReservationCreateResponse replay = objectMapper.readValue(body, ReservationCreateResponse.class);
                replay.setIdempotentReplay(true);
                return replay;
            }
            throw idempotencyStillPending();
        }

        ReservationCreateResponse dryRunResponse = dryRun.response();
        if (!dryRunResponse.isIdempotentReplay()) {
            if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isEmpty()) {
                stampAndEmitEvidence(dryRunResponse, request, traceId);
                return dryRunResponse;
            }
            EvidenceEmitter.PreparedEvidence prepared =
                prepareReserveEvidence(dryRunResponse, request, traceId);
            if (prepared != null) {
                dryRunResponse.setCyclesEvidence(toCyclesEvidenceRef(prepared.ref()));
            }
            boolean cached = cacheIdempotentBody("reserve", tenant, request.getIdempotencyKey(),
                computePayloadHash(request), dryRunResponse, prepared, dryRun.claimMarker());
            if (!cached) {
                dryRunResponse.setCyclesEvidence(null);
                clearIdempotencyClaim(dryRun.claimKey(), dryRun.claimMarker());
                throw idempotencyResponsePersistenceFailed();
            }
        }
        return dryRunResponse;
    }

    /**
     * Emit a {@code reserve} CyclesEvidence source record over {@code {request, response}}
     * (the response AS-IS, before {@code cycles_evidence} is stamped, so the attested
     * payload never references its own id) and stamp the returned ref onto the response.
     * No-op (no field stamped) when the server identity is unconfigured. Covers dry_run too
     * — its ALLOW/DENY outcome is captured as {@code reserve} evidence (cycles-evidence-v0.1).
     */
    private void stampAndEmitEvidence(ReservationCreateResponse response,
                                      ReservationCreateRequest request, String traceId) {
        Map<String, Object> evidenceBody = new LinkedHashMap<>();
        evidenceBody.put("request", request);
        evidenceBody.put("response", response);
        EvidenceEmitter.EvidenceRef ref = evidenceEmitter.emit("reserve",
                System.currentTimeMillis(), traceId, evidenceBody);
        if (ref != null) {
            response.setCyclesEvidence(CyclesEvidenceRef.builder()
                    .evidenceId(ref.evidenceId())
                    .cyclesEvidenceUrl(ref.cyclesEvidenceUrl())
                    .build());
        }
    }

    private ReservationCreateResponse readCachedReserveResponse(String reservationId) throws Exception {
        String cachedOriginal = readCacheValueWithWait(
            reserveResponseCacheKey(reservationId), RESERVE_BODY_CACHE_WAIT_ATTEMPTS);
        if (cachedOriginal == null) {
            return null;
        }
        ReservationCreateResponse original =
            objectMapper.readValue(cachedOriginal, ReservationCreateResponse.class);
        original.setIdempotentReplay(true);
        return original;
    }

    private ReservationCreateResponse restoreReserveResponse(String reservationId,
                                                              Object suppliedSnapshot,
                                                              long ttlMs) {
        try {
            ReplayState replayState = readReplayState(
                reservationId, "reserve", "reserve_response_json", suppliedSnapshot);
            if (replayState == null || replayState.snapshotJson() == null) {
                return null;
            }
            Map<String, Object> snapshot = objectMapper.readValue(replayState.snapshotJson(), Map.class);
            ReservationCreateResponse restored = buildReserveResponse(snapshot);
            String state = resolvedResponseState(replayState);
            if (RESPONSE_STATE_PENDING.equals(state)) {
                if (!finalizeBaseResponse("reserve", reservationId, restored, ttlMs)) {
                    return readCachedReserveResponse(reservationId);
                }
                restored.setIdempotentReplay(true);
                return restored;
            } else if (RESPONSE_STATE_EVIDENCE.equals(state)) {
                if (replayState.evidence() == null) {
                    return null;
                }
                restored.setCyclesEvidence(replayState.evidence());
            }
            restored.setIdempotentReplay(true);
            cacheRestoredResponseIfAbsent(
                reserveResponseCacheKey(reservationId), restored, ttlMs);
            return restored;
        } catch (Exception e) {
            LOG.warn("Failed to restore reserve response snapshot: reservation_id={} error={}",
                LogSanitizer.sanitize(reservationId), LogSanitizer.sanitize(e.toString()), e);
            return null;
        }
    }

    private EvidenceEmitter.PreparedEvidence prepareReserveEvidence(
            ReservationCreateResponse response, ReservationCreateRequest request, String traceId) {
        Map<String, Object> evidenceBody = new LinkedHashMap<>();
        evidenceBody.put("request", request);
        evidenceBody.put("response", response);
        return evidenceEmitter.prepare("reserve", System.currentTimeMillis(), traceId, evidenceBody);
    }

    private void persistReserveResponse(ReservationCreateResponse response,
                                        ReservationCreateRequest request,
                                        String traceId, long ttlMs) {
        EvidenceEmitter.PreparedEvidence prepared = prepareReserveEvidence(response, request, traceId);
        if (prepared == null) {
            finalizeBaseResponse("reserve", response.getReservationId(), response, ttlMs);
            return;
        }
        response.setCyclesEvidence(toCyclesEvidenceRef(prepared.ref()));
        if (!cacheLifecycleWithEvidence("reserve", response.getReservationId(), response,
                ttlMs, prepared)) {
            response.setCyclesEvidence(null);
            finalizeBaseResponse("reserve", response.getReservationId(), response, ttlMs);
        }
    }

    private ReservationCreateResponse buildReserveResponse(Map<String, Object> response) throws Exception {
        Enums.UnitEnum unit = Enums.UnitEnum.valueOf(response.get("estimate_unit").toString());
        Caps caps = null;
        Object capsJson = response.get("caps_json");
        if (capsJson instanceof String value && !value.isEmpty()) {
            caps = objectMapper.readValue(value, Caps.class);
        }
        List<String> affectedScopes = ((List<?>) response.getOrDefault("affected_scopes", List.of()))
            .stream().map(Object::toString).collect(Collectors.toList());
        return ReservationCreateResponse.builder()
            .decision(caps == null ? Enums.DecisionEnum.ALLOW : Enums.DecisionEnum.ALLOW_WITH_CAPS)
            .reservationId(response.get("reservation_id").toString())
            .affectedScopes(affectedScopes)
            .scopePath(response.get("scope_path").toString())
            .reserved(new Amount(unit, parseLong(response.get("estimate_amount"))))
            .expiresAtMs(parseLong(response.get("expires_at")))
            .caps(caps)
            .balances(parseLuaBalances(response, unit))
            .preRemaining(parsePreRemaining(response))
            .preIsOverLimit(parsePreIsOverLimit(response))
            .build();
    }

    private static long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static Long parseNullableLong(Object value) {
        return value == null ? null : parseLong(value);
    }

    private static long replayCacheTtl(Object value) {
        if (value == null) {
            return IDEMPOTENCY_CACHE_TTL_MS;
        }
        long ttlMs = parseLong(value);
        return ttlMs > 0 ? ttlMs : IDEMPOTENCY_CACHE_TTL_MS;
    }

    // ---- commit / release CyclesEvidence (mirrors the reserve evidence flow) ----

    private static String lifecycleBodyCacheKey(String artifactType, String reservationId) {
        return artifactType + ":body:" + reservationId;
    }

    /**
     * Emit a lifecycle CyclesEvidence source record and return the ref (or {@code null}
     * when the server identity is unconfigured). The {@code payloadBody} is the
     * artifact-specific body (commit/release carry {@code {reservation_id, request, response}});
     * it is built BEFORE {@code cycles_evidence} is stamped onto the response, so the attested
     * payload never references its own id.
     */
    private EvidenceEmitter.PreparedEvidence prepareLifecycleEvidence(String artifactType, String traceId,
                                                                      Object payloadBody) {
        return evidenceEmitter.prepare(artifactType, System.currentTimeMillis(), traceId, payloadBody);
    }

    private static CyclesEvidenceRef toCyclesEvidenceRef(EvidenceEmitter.EvidenceRef ref) {
        return CyclesEvidenceRef.builder()
            .evidenceId(ref.evidenceId())
            .cyclesEvidenceUrl(ref.cyclesEvidenceUrl())
            .build();
    }

    private boolean cacheLifecycleWithEvidence(String artifactType, String reservationId,
                                               Object response, long ttlMs,
                                               EvidenceEmitter.PreparedEvidence prepared) {
        try (Jedis jedis = jedisPool.getResource()) {
            EvidenceEmitter.EvidenceRef ref = prepared.ref();
            Object result = jedis.eval(CACHE_LIFECYCLE_WITH_EVIDENCE_SCRIPT,
                List.of(lifecycleBodyCacheKey(artifactType, reservationId),
                    "reservation:res_" + reservationId, evidencePendingKey),
                List.of(String.valueOf(ttlMs), objectMapper.writeValueAsString(response),
                    artifactType + "_evidence_id", ref.evidenceId(),
                    artifactType + "_evidence_url", ref.cyclesEvidenceUrl(),
                    prepared.recordJson(), responseStateField(artifactType)));
            if (!(result instanceof Number number)) {
                throw new IllegalStateException("lifecycle evidence keys have incompatible Redis types");
            }
            if (number.longValue() == 1L) {
                return true;
            }
            if (number.longValue() == 0L) {
                return false;
            }
            throw new IllegalStateException("lifecycle evidence keys have incompatible Redis types");
        } catch (Exception e) {
            LOG.warn("Failed to atomically persist lifecycle evidence: artifact_type={} reservation_id={} ttl_ms={} error={}",
                artifactType, LogSanitizer.sanitize(reservationId), ttlMs,
                LogSanitizer.sanitize(e.toString()), e);
            metrics.recordEvidenceEmitFailed(artifactType);
            return false;
        }
    }

    /**
     * Finalize a pending durable response without evidence. The state compare-and-set
     * makes this mutually exclusive with evidence finalization, so a slow replay repair
     * can never overwrite a freshly stamped response (and vice versa).
     */
    private boolean finalizeBaseResponse(String artifactType, String reservationId,
                                         Object response, long ttlMs) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(FINALIZE_LIFECYCLE_BASE_SCRIPT,
                List.of(lifecycleBodyCacheKey(artifactType, reservationId),
                    "reservation:res_" + reservationId),
                List.of(String.valueOf(ttlMs), objectMapper.writeValueAsString(response),
                    responseStateField(artifactType)));
            if (!(result instanceof Number number) || number.longValue() < 0L) {
                throw new IllegalStateException("lifecycle response keys have incompatible Redis types");
            }
            return number.longValue() == 1L;
        } catch (Exception e) {
            LOG.warn("Failed to finalize base lifecycle response: artifact_type={} reservation_id={} ttl_ms={} error={}",
                artifactType, LogSanitizer.sanitize(reservationId), ttlMs,
                LogSanitizer.sanitize(e.toString()), e);
            return false;
        }
    }

    private static String responseStateField(String artifactType) {
        return artifactType + "_response_state";
    }

    private static String resolvedResponseState(ReplayState replayState) {
        if (replayState.responseState() != null) {
            String state = replayState.responseState();
            if (RESPONSE_STATE_PENDING.equals(state) || RESPONSE_STATE_BASE.equals(state)
                    || RESPONSE_STATE_EVIDENCE.equals(state)) {
                return state;
            }
            throw new IllegalStateException("invalid durable response state: " + state);
        }
        // Compatibility for snapshots written by the immediately preceding draft:
        // evidence fields were written only after the body became canonical.
        return replayState.evidence() == null ? RESPONSE_STATE_BASE : RESPONSE_STATE_EVIDENCE;
    }

    private void cacheRestoredResponseIfAbsent(String cacheKey, Object response,
                                               long ttlMs) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(cacheKey, objectMapper.writeValueAsString(response),
                SetParams.setParams().nx().px(ttlMs));
        } catch (Exception e) {
            LOG.warn("Failed to repair replay body cache: cache_key={} ttl_ms={} error={}",
                LogSanitizer.sanitize(cacheKey), ttlMs,
                LogSanitizer.sanitize(e.toString()), e);
        }
    }

    private ReplayState readReplayState(String reservationId, String artifactType,
                                        String snapshotField, Object suppliedSnapshot) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> values = jedis.hmget("reservation:res_" + reservationId,
                snapshotField, responseStateField(artifactType),
                artifactType + "_evidence_id", artifactType + "_evidence_url");
            String snapshot = suppliedSnapshot instanceof String value && !value.isEmpty()
                ? value : values.get(0);
            CyclesEvidenceRef evidence = null;
            if (values.get(2) != null && values.get(3) != null) {
                evidence = CyclesEvidenceRef.builder()
                    .evidenceId(values.get(2)).cyclesEvidenceUrl(values.get(3)).build();
            }
            return new ReplayState(snapshot, values.get(1), evidence);
        } catch (Exception e) {
            LOG.warn("Failed to read replay snapshot: artifact_type={} reservation_id={} error={}",
                artifactType, LogSanitizer.sanitize(reservationId),
                LogSanitizer.sanitize(e.toString()), e);
            return null;
        }
    }

    private record ReplayState(String snapshotJson, String responseState,
                               CyclesEvidenceRef evidence) {}

    /** Read a cached lifecycle response body (per-poll connections, bounded wait), or
     *  {@code null} if absent. */
    private <T> T readCachedLifecycleBody(String artifactType, String reservationId, Class<T> type)
            throws Exception {
        String cached = readCacheValueWithWait(
            lifecycleBodyCacheKey(artifactType, reservationId), RESERVE_BODY_CACHE_WAIT_ATTEMPTS);
        return cached == null ? null : objectMapper.readValue(cached, type);
    }

    private CommitResponse restoreCommitResponse(String reservationId, Object suppliedSnapshot) {
        try {
            ReplayState replayState = readReplayState(
                reservationId, "commit", "commit_response_json", suppliedSnapshot);
            if (replayState == null || replayState.snapshotJson() == null) {
                return null;
            }
            Map<String, Object> snapshot = objectMapper.readValue(replayState.snapshotJson(), Map.class);
            Enums.UnitEnum unit = Enums.UnitEnum.valueOf(snapshot.get("estimate_unit").toString());
            long chargedAmount = parseLong(snapshot.get("charged"));
            long estimateAmount = parseLong(snapshot.get("estimate_amount"));
            Amount released = chargedAmount < estimateAmount
                ? new Amount(unit, estimateAmount - chargedAmount) : null;
            Long debt = parseNullableLong(snapshot.get("debt_incurred"));
            CommitResponse restored = CommitResponse.builder()
                .status(Enums.CommitStatus.COMMITTED)
                .charged(new Amount(unit, chargedAmount))
                .released(released)
                .balances(parseLuaBalances(snapshot, unit))
                .estimateAmount(estimateAmount)
                .scopePath(Objects.toString(snapshot.get("scope_path"), null))
                .overagePolicy(Objects.toString(snapshot.get("overage_policy"), null))
                .debtIncurred(debt)
                .scopeDebtIncurred(parseScopeDebtIncurred(snapshot))
                .preRemaining(parsePreRemaining(snapshot))
                .preIsOverLimit(parsePreIsOverLimit(snapshot))
                .build();
            String state = resolvedResponseState(replayState);
            if (RESPONSE_STATE_PENDING.equals(state)) {
                if (!finalizeBaseResponse("commit", reservationId, restored,
                        TERMINAL_BODY_CACHE_TTL_MS)) {
                    CommitResponse cached = readCachedLifecycleBody(
                        "commit", reservationId, CommitResponse.class);
                    if (cached != null) {
                        cached.setIdempotentReplay(true);
                    }
                    return cached;
                }
                restored.setIdempotentReplay(true);
                return restored;
            } else if (RESPONSE_STATE_EVIDENCE.equals(state)) {
                if (replayState.evidence() == null) {
                    return null;
                }
                restored.setCyclesEvidence(replayState.evidence());
            }
            restored.setIdempotentReplay(true);
            cacheRestoredResponseIfAbsent(
                lifecycleBodyCacheKey("commit", reservationId), restored,
                TERMINAL_BODY_CACHE_TTL_MS);
            return restored;
        } catch (Exception e) {
            LOG.warn("Failed to restore commit response snapshot: reservation_id={} error={}",
                LogSanitizer.sanitize(reservationId), LogSanitizer.sanitize(e.toString()), e);
            return null;
        }
    }

    private ReleaseResponse restoreReleaseResponse(String reservationId, Object suppliedSnapshot) {
        try {
            ReplayState replayState = readReplayState(
                reservationId, "release", "release_response_json", suppliedSnapshot);
            if (replayState == null || replayState.snapshotJson() == null) {
                return null;
            }
            Map<String, Object> snapshot = objectMapper.readValue(replayState.snapshotJson(), Map.class);
            Enums.UnitEnum unit = Enums.UnitEnum.valueOf(snapshot.get("estimate_unit").toString());
            ReleaseResponse restored = ReleaseResponse.builder()
                .status(Enums.ReleaseStatus.RELEASED)
                .released(new Amount(unit, parseLong(snapshot.get("estimate_amount"))))
                .balances(parseLuaBalances(snapshot, unit))
                .build();
            String state = resolvedResponseState(replayState);
            if (RESPONSE_STATE_PENDING.equals(state)) {
                if (!finalizeBaseResponse("release", reservationId, restored,
                        TERMINAL_BODY_CACHE_TTL_MS)) {
                    ReleaseResponse cached = readCachedLifecycleBody(
                        "release", reservationId, ReleaseResponse.class);
                    if (cached != null) {
                        cached.setIdempotentReplay(true);
                    }
                    return cached;
                }
                restored.setIdempotentReplay(true);
                return restored;
            } else if (RESPONSE_STATE_EVIDENCE.equals(state)) {
                if (replayState.evidence() == null) {
                    return null;
                }
                restored.setCyclesEvidence(replayState.evidence());
            }
            restored.setIdempotentReplay(true);
            cacheRestoredResponseIfAbsent(
                lifecycleBodyCacheKey("release", reservationId), restored,
                TERMINAL_BODY_CACHE_TTL_MS);
            return restored;
        } catch (Exception e) {
            LOG.warn("Failed to restore release response snapshot: reservation_id={} error={}",
                LogSanitizer.sanitize(reservationId), LogSanitizer.sanitize(e.toString()), e);
            return null;
        }
    }

    private DryRunResult evaluateDryRun(Jedis jedis, ReservationCreateRequest request,
                                        List<String> affectedScopes, String scopePath,
                                        String tenant) throws Exception {
        long estimateAmount = request.getEstimate().getAmount();
        String unit = request.getEstimate().getUnit().name();

        // Idempotency: replay cached result for same (tenant, key) within 24 h
        String idempotencyKey = request.getIdempotencyKey();
        String payloadHash = computePayloadHash(request);
        String dryRunClaimKey = null;
        String dryRunClaimMarker = null;
        try {
            // dry_run is a representation of POST /v1/reservations, not a separate endpoint.
            // Share the live reserve namespace so changing dry_run with the same key is rejected.
            IdemClaim claim = acquireIdempotencyClaim(jedis, "reserve", tenant, idempotencyKey, payloadHash);
            if (claim.cachedBody() != null) {
                ReservationCreateResponse replay =
                    objectMapper.readValue(claim.cachedBody(), ReservationCreateResponse.class);
                replay.setIdempotentReplay(true);
                return DryRunResult.replay(replay);
            }
            if (claim.pending()) {
                return DryRunResult.pending(
                    idempotencyCacheKey("reserve", tenant, idempotencyKey), payloadHash);
            }
            dryRunClaimKey = claim.claimKey();
            dryRunClaimMarker = claim.claimMarker();

            // Governance tenant gate (see evaluateTenantStatusGate): a CLOSED
            // owning tenant turns the FRESH evaluation into DENY/TENANT_CLOSED
            // before any budget reads; a malformed record throws 500 (the
            // catch below clears the claim). Cached pre-close replays returned
            // above keep their original payload - replay precedence unchanged.
            Enums.ReasonCode tenantGate = evaluateTenantStatusGate(jedis, tenant);
            if (tenantGate != null) {
                return DryRunResult.fresh(ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode(tenantGate)
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build(), dryRunClaimKey, dryRunClaimMarker);
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
                    return DryRunResult.fresh(ReservationCreateResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode("FROZEN".equals(budgetStatus)
                            ? Enums.ReasonCode.BUDGET_FROZEN
                            : Enums.ReasonCode.BUDGET_CLOSED)
                        .affectedScopes(affectedScopes)
                        .scopePath(scopePath)
                        .build(), dryRunClaimKey, dryRunClaimMarker);
                }
                if ("true".equals(budget.getOrDefault("is_over_limit", "false"))) {
                    return DryRunResult.fresh(ReservationCreateResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode(Enums.ReasonCode.OVERDRAFT_LIMIT_EXCEEDED)
                        .affectedScopes(affectedScopes)
                        .scopePath(scopePath)
                        .build(), dryRunClaimKey, dryRunClaimMarker);
                }
                long debt = Long.parseLong(budget.getOrDefault("debt", "0"));
                long overdraftLimit = Long.parseLong(budget.getOrDefault("overdraft_limit", "0"));
                if (debt > 0 && overdraftLimit == 0) {
                    return DryRunResult.fresh(ReservationCreateResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode(Enums.ReasonCode.DEBT_OUTSTANDING)
                        .affectedScopes(affectedScopes)
                        .scopePath(scopePath)
                        .build(), dryRunClaimKey, dryRunClaimMarker);
                }
                long remaining = Long.parseLong(budget.getOrDefault("remaining", "0"));
                if (remaining < estimateAmount) {
                    return DryRunResult.fresh(ReservationCreateResponse.builder()
                        .decision(Enums.DecisionEnum.DENY)
                        .reasonCode(Enums.ReasonCode.BUDGET_EXCEEDED)
                        .affectedScopes(affectedScopes)
                        .scopePath(scopePath)
                        .build(), dryRunClaimKey, dryRunClaimMarker);
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
                return DryRunResult.fresh(ReservationCreateResponse.builder()
                    .decision(Enums.DecisionEnum.DENY)
                    .reasonCode(Enums.ReasonCode.BUDGET_NOT_FOUND)
                    .affectedScopes(affectedScopes)
                    .scopePath(scopePath)
                    .build(), dryRunClaimKey, dryRunClaimMarker);
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

            // Caching + evidence for ALL fresh dry_run outcomes (ALLOW and the early DENYs) is
            // handled uniformly by the caller (createDryRunReservation) via cacheIdempotentBody,
            // so the cached body carries the stamped cycles_evidence and every outcome is idempotent.
            return DryRunResult.fresh(ReservationCreateResponse.builder()
                .decision(dryRunDecision)
                .affectedScopes(affectedScopes)
                .scopePath(scopePath)
                .reserved(request.getEstimate())
                .caps(dryRunCaps)
                .balances(balances)
                .build(), dryRunClaimKey, dryRunClaimMarker);
        } catch (Exception e) {
            // Clear the claim on the connection we already hold — `jedis` is owned by the caller's
            // try-with-resources and is still open here, so the self-acquiring overload would nest
            // a second pool checkout (peak 2/req) under concurrent failing dry-runs.
            clearIdempotencyClaim(jedis, dryRunClaimKey, dryRunClaimMarker);
            throw e;
        }
    }

    /** Cache a fresh non-persisting evaluation body (evidence already stamped) + payload hash
     *  under {@code idem:<tenant>:<kind>:<key>} (24h). Acquires its own short-lived connection
     *  and is fully fail-open: returns {@code false} on ANY failure (incl. pool exhaustion), so
     *  the caller can clear the pending claim. No-op (true) without an idempotency_key. */
    private boolean cacheIdempotentBody(String kind, String tenant, String idempotencyKey,
                                        String payloadHash, Object response,
                                        EvidenceEmitter.PreparedEvidence prepared,
                                        String claimMarker) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return true;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String idemKey = idempotencyCacheKey(kind, tenant, idempotencyKey);
            Object result = jedis.eval(CACHE_IDEMPOTENT_WITH_EVIDENCE_SCRIPT,
                List.of(idemKey, idemKey + ":hash", evidencePendingKey),
                List.of(payloadHash == null ? "" : payloadHash,
                    String.valueOf(IDEMPOTENCY_CACHE_TTL_MS),
                    objectMapper.writeValueAsString(response),
                    prepared == null ? "" : prepared.recordJson(),
                    claimMarker == null ? "" : claimMarker));
            if (!(result instanceof Number number)) {
                throw new IllegalStateException("idempotency cache script returned a non-numeric result");
            }
            if (number.longValue() == 0L) {
                LOG.warn("Idempotency claim ownership was lost before response publication: "
                                + "kind={} tenant={} idempotency_key_present={}",
                        kind, LogSanitizer.sanitize(tenant), true);
                return false;
            }
            if (number.longValue() != 1L) {
                throw new IllegalStateException("evidence queue has an incompatible Redis type");
            }
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to cache idempotent response: kind={} tenant={} idempotency_key_present={} ttl_ms={} error={}",
                    kind, LogSanitizer.sanitize(tenant), true, IDEMPOTENCY_CACHE_TTL_MS, LogSanitizer.sanitize(e.toString()), e);
            if (prepared != null) {
                metrics.recordEvidenceEmitFailed(kind);
            }
            return false;
        }
    }

    /** Poll (per-poll connection, bounded wait) for the winning evaluator's cached body —
     *  returns the non-pending cached JSON (caller deserializes) or {@code null} on timeout. */
    private String waitForIdempotentReplayBody(String kind, String idemKey, String payloadHash)
            throws Exception {
        for (int attempt = 0; attempt <= IDEMPOTENT_EVAL_WAIT_ATTEMPTS; attempt++) {
            if (attempt > 0 && !sleepBeforeIdempotencyRetry()) {
                return null;
            }
            try (Jedis jedis = jedisPool.getResource()) {
                String cached = jedis.get(idemKey);
                if (cached == null) {
                    continue;
                }
                if (isPending(kind, cached)) {
                    validatePendingMarker(kind, cached, payloadHash);
                    continue;
                }
                validateCachedHash(jedis.get(idemKey + ":hash"), payloadHash);
                return cached;
            }
        }
        return null;
    }

    private boolean isPending(String kind, String cached) {
        return cached.startsWith(pendingPrefix(kind));
    }

    private String pendingMarker(String kind, String payloadHash) {
        String hash = payloadHash != null ? payloadHash : "";
        return pendingPrefix(kind) + hash + ":" + UUID.randomUUID();
    }

    private void validatePendingMarker(String kind, String cached, String payloadHash) {
        String pendingHash = pendingPayloadHash(kind, cached);
        if (!pendingHash.isEmpty() && payloadHash != null && !payloadHash.isEmpty()
                && !pendingHash.equals(payloadHash)) {
            throw CyclesProtocolException.idempotencyMismatch();
        }
    }

    private String pendingPayloadHash(String kind, String cached) {
        String markerBody = cached.substring(pendingPrefix(kind).length());
        int ownerSeparator = markerBody.indexOf(':');
        return ownerSeparator >= 0 ? markerBody.substring(0, ownerSeparator) : markerBody;
    }

    /** Best-effort compare-and-delete of a pending claim. Acquires its own connection and is
     *  fully fail-open (incl. pool exhaustion) — releasing the claim is never allowed to fail
     *  the request; a leaked claim self-expires via its PX TTL. Use this overload only when NO
     *  evaluation connection is currently held (e.g. after the eval try-with-resources closed);
     *  callers still inside their connection's scope must use the {@code Jedis}-passing overload
     *  to avoid nested pool acquisition. */
    private void clearIdempotencyClaim(String idemKey, String marker) {
        // The null-key guard lives in the Jedis-passing overload (which this delegates to); a
        // fresh-path caller always holds a non-null claim, so we never check out a connection here
        // for a no-op clear.
        try (Jedis jedis = jedisPool.getResource()) {
            clearIdempotencyClaim(jedis, idemKey, marker);
        } catch (Exception clearError) {
            LOG.warn("Failed to clear pending idempotency claim: kind={} tenant={} claim_key_present={} error={}",
                idempotencyClaimKind(idemKey), LogSanitizer.sanitize(idempotencyClaimTenant(idemKey)), idemKey != null,
                LogSanitizer.sanitize(clearError.toString()), clearError);
        }
    }

    /** Compare-and-delete a pending claim on an ALREADY-HELD connection. For callers that are
     *  still inside their evaluation connection's scope (e.g. {@link #evaluateDryRun}'s failure
     *  catch) and MUST NOT check out a second pooled connection — checking one out here while the
     *  eval connection is still held would recreate the nested-acquisition pool-starvation pattern
     *  this class avoids elsewhere. Fail-open: a clear failure is logged, never propagated; a
     *  leaked claim self-expires via its PX TTL. */
    private void clearIdempotencyClaim(Jedis jedis, String idemKey, String marker) {
        if (idemKey == null || marker == null) {
            return;
        }
        try {
            jedis.eval(COMPARE_AND_DELETE_SCRIPT, List.of(idemKey), List.of(marker));
        } catch (Exception clearError) {
            LOG.warn("Failed to clear pending idempotency claim: kind={} tenant={} claim_key_present={} error={}",
                idempotencyClaimKind(idemKey), LogSanitizer.sanitize(idempotencyClaimTenant(idemKey)), idemKey != null,
                LogSanitizer.sanitize(clearError.toString()), clearError);
        }
    }

    private String idempotencyClaimKind(String idemKey) {
        String[] parts = idempotencyClaimParts(idemKey);
        return parts.length >= 4 ? parts[2] : null;
    }

    private String idempotencyClaimTenant(String idemKey) {
        String[] parts = idempotencyClaimParts(idemKey);
        return parts.length >= 4 ? parts[1] : null;
    }

    private String[] idempotencyClaimParts(String idemKey) {
        if (idemKey == null || !idemKey.startsWith("idem:")) {
            return new String[0];
        }
        return idemKey.split(":", 5);
    }

    private CyclesProtocolException idempotencyStillPending() {
        return new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR,
            "Idempotency result is still being prepared; retry with the same idempotency_key", 500);
    }

    private CyclesProtocolException idempotencyReplayUnavailable() {
        return new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR,
            "Original idempotency response is temporarily unavailable; retry with the same idempotency_key", 500);
    }

    private CyclesProtocolException idempotencyResponsePersistenceFailed() {
        return new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR,
            "Unable to persist idempotency response; retry with the same idempotency_key", 500);
    }

    private String readCacheValueWithWait(String key, int attempts) {
        for (int attempt = 0; attempt <= attempts; attempt++) {
            if (attempt > 0 && !sleepBeforeIdempotencyRetry()) {
                return null;
            }
            try (Jedis jedis = jedisPool.getResource()) {
                String cached = jedis.get(key);
                if (cached != null) {
                    return cached;
                }
            }
        }
        return null;
    }

    private boolean sleepBeforeIdempotencyRetry() {
        try {
            Thread.sleep(IDEMPOTENCY_CACHE_WAIT_SLEEP_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record DryRunResult(ReservationCreateResponse response, String claimKey, String claimMarker,
                                String payloadHash, boolean pending) {
        static DryRunResult replay(ReservationCreateResponse response) {
            return new DryRunResult(response, null, null, null, false);
        }

        static DryRunResult fresh(ReservationCreateResponse response, String claimKey, String claimMarker) {
            return new DryRunResult(response, claimKey, claimMarker, null, false);
        }

        static DryRunResult pending(String claimKey, String payloadHash) {
            return new DryRunResult(null, claimKey, null, payloadHash, true);
        }
    }

    /** Back-compat overload (no trace context) — used by tests and any non-HTTP caller. */
    public CommitResponse commitReservation(String reservationId, CommitRequest request, String tenant) {
        return commitReservation(reservationId, request, tenant, null);
    }

    public CommitResponse commitReservation(String reservationId, CommitRequest request, String tenant,
                                            String traceId) {
        LOG.debug("Committing reservation: {}", reservationId);

        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
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

            // Replay responses intentionally carry only the durable snapshot pointer.
            // Resolve them before parsing fresh-operation fields so pre-snapshot rows
            // fail retriably instead of depending on obsolete reconstructed values.
            if (Boolean.TRUE.equals(response.get("replay"))) {
                jedis.close();
                jedis = null;
                CommitResponse replayed = readCachedLifecycleBody(
                    "commit", reservationId, CommitResponse.class);
                if (replayed != null) {
                    replayed.setIdempotentReplay(true);
                } else {
                    replayed = restoreCommitResponse(
                        reservationId, response.get("response_snapshot"));
                }
                if (replayed == null) {
                    throw idempotencyReplayUnavailable();
                }
                metrics.recordCommit(tenant, "COMMITTED", "IDEMPOTENT_REPLAY", "UNKNOWN");
                return replayed;
            }

            long chargedAmount = parseLong(response.get("charged"));

            // Lua now returns estimate_amount — use it for released calculation
            Amount released = null;
            Long luaEstimate = parseNullableLong(response.get("estimate_amount"));
            if (luaEstimate != null) {
                long est = luaEstimate;
                if (chargedAmount < est) {
                    released = new Amount(request.getActual().getUnit(), est - chargedAmount);
                }
            }

            // Parse balances from Lua response (read atomically with mutation)
            List<Balance> balances = parseLuaBalances(response, request.getActual().getUnit());

            // Internal fields from Lua for event emission (not serialized to client)
            String scopePath = response.get("scope_path") != null ? response.get("scope_path").toString() : null;
            String overagePolicy = response.get("overage_policy") != null ? response.get("overage_policy").toString() : null;
            Long luaDebt = parseNullableLong(response.get("debt_incurred"));
            Map<String, Long> scopeDebtIncurred = parseScopeDebtIncurred(response);

            long debtIncurredAmount = luaDebt != null ? luaDebt : 0L;
            metrics.recordCommit(tenant, "COMMITTED", "OK",
                overagePolicy != null ? overagePolicy : "DEFAULT");
            if (debtIncurredAmount > 0) {
                metrics.recordOverdraftIncurred(tenant);
            }
            CommitResponse committed = CommitResponse.builder()
                .status(Enums.CommitStatus.COMMITTED)
                .charged(new Amount(request.getActual().getUnit(), chargedAmount))
                .released(released)
                .balances(balances)
                .estimateAmount(luaEstimate)
                .scopePath(scopePath)
                .overagePolicy(overagePolicy)
                .debtIncurred(luaDebt)
                .scopeDebtIncurred(scopeDebtIncurred)
                .preRemaining(parsePreRemaining(response))
                .preIsOverLimit(parsePreIsOverLimit(response))
                .build();

            // Fresh commit: emit `commit` evidence over {reservation_id, request, response}
            // (response AS-IS, before cycles_evidence is stamped), stamp the ref, then cache
            // the full body for verbatim replay (30-day TTL, matching the terminal hash).
            // Release the Lua connection first so emit (push) and the body cache each use their
            // own short-lived connection — never two pool connections held at once.
            jedis.close();
            jedis = null;
            Map<String, Object> evidenceBody = new LinkedHashMap<>();
            evidenceBody.put("reservation_id", reservationId);
            evidenceBody.put("request", request);
            evidenceBody.put("response", committed);
            EvidenceEmitter.PreparedEvidence prepared =
                prepareLifecycleEvidence("commit", traceId, evidenceBody);
            if (prepared == null) {
                finalizeBaseResponse("commit", reservationId, committed,
                    TERMINAL_BODY_CACHE_TTL_MS);
            } else {
                committed.setCyclesEvidence(toCyclesEvidenceRef(prepared.ref()));
                if (!cacheLifecycleWithEvidence("commit", reservationId, committed,
                        TERMINAL_BODY_CACHE_TTL_MS, prepared)) {
                    committed.setCyclesEvidence(null);
                    finalizeBaseResponse("commit", reservationId, committed,
                        TERMINAL_BODY_CACHE_TTL_MS);
                }
            }
            return committed;
        } catch (CyclesProtocolException e){
            LOG.debug("Commit reservation denied: reservation_id={} tenant={} error={}",
                    reservationId, tenant, e.getErrorCode());
            metrics.recordCommit(tenant, "DENY", e.getErrorCode().name(), "UNKNOWN");
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to commit reservation: reservation_id={} tenant={} idempotency_key_present={} trace_id={}",
                    LogSanitizer.sanitize(reservationId), LogSanitizer.sanitize(tenant),
                    request != null && request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank(),
                    traceId, e);
            metrics.recordCommit(tenant, "DENY", "INTERNAL_ERROR", "UNKNOWN");
            throw new RuntimeException(e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /** Back-compat overload (no trace context) — used by tests and any non-HTTP caller. */
    public ReleaseResponse releaseReservation(String reservationId, ReleaseRequest request,
                                               String tenant, String actorType) {
        return releaseReservation(reservationId, request, tenant, actorType, null);
    }

    public ReleaseResponse releaseReservation(String reservationId, ReleaseRequest request,
                                               String tenant, String actorType, String traceId) {
        return releaseReservation(reservationId, request, tenant, actorType, traceId, null);
    }

    /**
     * Release with an optional admin audit entry. The entry is passed into release.lua so
     * the ledger transition and required admin audit record share one Redis atomic unit.
     */
    public ReleaseResponse releaseReservation(String reservationId, ReleaseRequest request,
                                               String tenant, String actorType, String traceId,
                                               AuditLogEntry auditEntry) {
        LOG.debug("Releasing reservation: {}", reservationId);

        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            AuditRepository.PreparedAudit preparedAudit = auditEntry != null
                ? auditRepository.prepare(auditEntry) : null;
            List<String> args = Arrays.asList(
                reservationId,
                request.getIdempotencyKey() != null ? request.getIdempotencyKey() : "",
                computePayloadHash(request),
                preparedAudit != null ? preparedAudit.json() : "",
                preparedAudit != null ? preparedAudit.logId() : "",
                preparedAudit != null ? preparedAudit.tenantId() : "",
                preparedAudit != null ? String.valueOf(preparedAudit.ttlSeconds()) : "0",
                preparedAudit != null ? String.valueOf(preparedAudit.score()) : "0"
            );

            Object result = luaScripts.eval(jedis, "release", releaseScript, args.toArray(new String[0]));
            Map<String, Object> response = objectMapper.readValue(result.toString(), Map.class);

            if (response.containsKey("error")) {
                handleScriptError(response);
            }

            if (Boolean.TRUE.equals(response.get("replay"))) {
                jedis.close();
                jedis = null;
                ReleaseResponse replayed = readCachedLifecycleBody(
                    "release", reservationId, ReleaseResponse.class);
                if (replayed != null) {
                    replayed.setIdempotentReplay(true);
                } else {
                    replayed = restoreReleaseResponse(
                        reservationId, response.get("response_snapshot"));
                }
                if (replayed == null) {
                    throw idempotencyReplayUnavailable();
                }
                metrics.recordRelease(tenant, actorType, "RELEASED", "IDEMPOTENT_REPLAY");
                return replayed;
            }

            // Lua now returns estimate_amount and estimate_unit — use them directly
            Amount releasedAmount;
            Long luaEstimate = parseNullableLong(response.get("estimate_amount"));
            String luaUnit = (String) response.get("estimate_unit");
            if (luaEstimate != null && luaUnit != null) {
                releasedAmount = new Amount(
                    Enums.UnitEnum.valueOf(luaUnit),
                    luaEstimate
                );
            } else {
                // Spec requires released to be present; fall back to zero with the request unit context
                LOG.warn("Reservation {} missing estimate data, defaulting released to zero", LogSanitizer.sanitize(reservationId));
                releasedAmount = new Amount(Enums.UnitEnum.USD_MICROCENTS, 0L);
            }

            // Parse balances from Lua response (read atomically with mutation)
            Enums.UnitEnum unitForBalances = luaUnit != null ? Enums.UnitEnum.valueOf(luaUnit) : Enums.UnitEnum.USD_MICROCENTS;
            List<Balance> balances = parseLuaBalances(response, unitForBalances);

            metrics.recordRelease(tenant, actorType, "RELEASED", "OK");
            ReleaseResponse releasedResponse = ReleaseResponse.builder()
                .status(Enums.ReleaseStatus.RELEASED)
                .released(releasedAmount)
                .balances(balances)
                .build();

            // Fresh release: emit `release` evidence over {reservation_id, request, response}
            // (before cycles_evidence is stamped), stamp the ref, cache the body for replay.
            // Release the Lua connection first (see commit) to avoid nested pool acquisition.
            jedis.close();
            jedis = null;
            Map<String, Object> evidenceBody = new LinkedHashMap<>();
            evidenceBody.put("reservation_id", reservationId);
            evidenceBody.put("request", request);
            evidenceBody.put("response", releasedResponse);
            EvidenceEmitter.PreparedEvidence prepared =
                prepareLifecycleEvidence("release", traceId, evidenceBody);
            if (prepared == null) {
                finalizeBaseResponse("release", reservationId, releasedResponse,
                    TERMINAL_BODY_CACHE_TTL_MS);
            } else {
                releasedResponse.setCyclesEvidence(toCyclesEvidenceRef(prepared.ref()));
                if (!cacheLifecycleWithEvidence("release", reservationId, releasedResponse,
                        TERMINAL_BODY_CACHE_TTL_MS, prepared)) {
                    releasedResponse.setCyclesEvidence(null);
                    finalizeBaseResponse("release", reservationId, releasedResponse,
                        TERMINAL_BODY_CACHE_TTL_MS);
                }
            }
            return releasedResponse;
        } catch (CyclesProtocolException e){
            LOG.debug("Release reservation denied: reservation_id={} tenant={} actor_type={} error={}",
                    reservationId, tenant, actorType, e.getErrorCode());
            metrics.recordRelease(tenant, actorType, "DENY", e.getErrorCode().name());
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to release reservation: reservation_id={} tenant={} actor_type={} idempotency_key_present={} trace_id={}",
                    LogSanitizer.sanitize(reservationId), LogSanitizer.sanitize(tenant), actorType,
                    request != null && request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank(),
                    traceId, e);
            metrics.recordRelease(tenant, actorType, "DENY", "INTERNAL_ERROR");
            throw new RuntimeException(e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
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
            LOG.debug("Extend reservation denied: reservation_id={} tenant={} error={}",
                    reservationId, tenant, e.getErrorCode());
            metrics.recordExtend(tenant, "DENY", e.getErrorCode().name());
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to extend reservation: reservation_id={} tenant={} extend_by_ms={} idempotency_key_present={}",
                    LogSanitizer.sanitize(reservationId), LogSanitizer.sanitize(tenant), request != null ? request.getExtendByMs() : null,
                    request != null && request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank(),
                    e);
            metrics.recordExtend(tenant, "DENY", "INTERNAL_ERROR");
            throw new RuntimeException(e);
        }
    }

    public ReservationDetail getReservationById(String reservationId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "reservation:res_" + reservationId;
            Map<String, String> fields = HashProjections.mapHashFields(
                reservationHashMapper.detailFields(),
                jedis.hmget(key, reservationHashMapper.detailFieldArray()));
            if (fields == null || fields.isEmpty()) {
                throw CyclesProtocolException.notFound(reservationId);
            }
            ReservationDetail detail = reservationHashMapper.buildDetail(fields);
            // Spec normative (line 52): "Expired reservations MUST return HTTP 410
            // with error=RESERVATION_EXPIRED." GET endpoint lists 410 (line 1212).
            if (detail.getStatus() == Enums.ReservationStatus.EXPIRED) {
                throw CyclesProtocolException.reservationExpired();
            }
            return detail;
        } catch (CyclesProtocolException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to get reservation by id: {}", LogSanitizer.sanitize(reservationId), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * List reservations with optional server-side sorting.
     *
     * <p>Spec: cycles-protocol-v0.yaml revision 2026-04-16. When {@code sortBy} or
     * {@code sortDir} is provided, results are returned in requested order and the
     * returned cursor encodes the sort state. When both are
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
                                                    String sortBy, String sortDir,
                                                    Long fromMs, Long toMs,
                                                    Long expiresFromMs, Long expiresToMs,
                                                    Long finalizedFromMs, Long finalizedToMs) {
        return listReservations(tenant, idempotencyKey, status, workspace, app, workflow,
            agent, toolset, limit, startCursor, sortBy, sortDir, fromMs, toMs,
            expiresFromMs, expiresToMs, finalizedFromMs, finalizedToMs,
            EnumSet.noneOf(ReservationInclude.class));
    }

    /**
     * include-aware overload (cycles-protocol revision 2026-06-19,
     * cycles-server#201). {@code include} is PROJECTION-ONLY: it selects which
     * optional metadata maps are serialized onto each summary row and is
     * deliberately NOT part of the filter/cursor binding, so changing {@code include}
     * across pages never invalidates a cursor. {@code committed} is always
     * projected regardless of {@code include}.
     */
    public ReservationListResponse listReservations(String tenant, String idempotencyKey,
                                                    String status, String workspace, String app,
                                                    String workflow, String agent, String toolset,
                                                    int limit, String startCursor,
                                                    String sortBy, String sortDir,
                                                    Long fromMs, Long toMs,
                                                    Long expiresFromMs, Long expiresToMs,
                                                    Long finalizedFromMs, Long finalizedToMs,
                                                    Set<ReservationInclude> include) {
        ReservationListQuery query = ReservationListQuery.builder(tenant, limit)
            .idempotencyKey(idempotencyKey)
            .status(status)
            .scopes(workspace, app, workflow, agent, toolset)
            .cursor(startCursor)
            .sort(sortBy, sortDir)
            .createdAt(fromMs, toMs)
            .expiresAt(expiresFromMs, expiresToMs)
            .finalizedAt(finalizedFromMs, finalizedToMs)
            .include(include)
            .build();
        return reservationQueryRepository.list(query, reservationCreatedAtIndex);
    }

    public BalanceResponse getBalances(String tenant, String workspace, String app,
                                            String workflow, String agent, String toolset,
                                            boolean includeChildren, int limit, String startCursor) {
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("budget:*").count(100);
            List<Balance> balances = new ArrayList<>();
            ScanPageCursor pageCursor = ScanPageCursor.decode(startCursor);
            String cursor = pageCursor.redisCursor();
            int batchOffset = pageCursor.offset();

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

                int startIndex = Math.min(batchOffset, keys.size());
                batchOffset = 0;
                for (int i = startIndex; i < keys.size(); i++) {
                    String key = keys.get(i);
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
                        if (!ScopePathMatcher.hasExactSegment(trueScope, tenantSegment)) continue;
                        if (workspaceSegment != null
                                && !ScopePathMatcher.hasExactSegment(trueScope, workspaceSegment)) continue;
                        if (appSegment != null
                                && !ScopePathMatcher.hasExactSegment(trueScope, appSegment)) continue;
                        if (workflowSegment != null
                                && !ScopePathMatcher.hasExactSegment(trueScope, workflowSegment)) continue;
                        if (agentSegment != null
                                && !ScopePathMatcher.hasExactSegment(trueScope, agentSegment)) continue;
                        if (toolsetSegment != null
                                && !ScopePathMatcher.hasExactSegment(trueScope, toolsetSegment)) continue;

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
                            String nextCursor = ScanPageCursor.nextCursor(
                                cursor, i + 1, keys.size(), scan.getCursor());
                            // Spec: has_more is true only when next_cursor is present
                            return BalanceResponse.builder()
                                .balances(balances)
                                .hasMore(nextCursor != null)
                                .nextCursor(nextCursor)
                                .build();
                        }

                    } catch (IllegalArgumentException e) {
                        LOG.warn("Invalid enum value in budget key: {}", LogSanitizer.sanitize(key), e);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse budget: {}", LogSanitizer.sanitize(key), e);
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

    /** Back-compat overload (no trace context) — used by tests and any non-HTTP caller. */
    public DecisionResponse decide(DecisionRequest request, String tenant) {
        return decide(request, tenant, null);
    }

    public DecisionResponse decide(DecisionRequest request, String tenant, String traceId) {
        LOG.debug("Evaluating decision for tenant: {}", tenant);
        String idempotencyKey = request.getIdempotencyKey();
        String payloadHash = computePayloadHash(request);

        // Idempotency: atomic claim (shared machinery with dry_run) so concurrent same-key decides
        // converge to ONE evaluation + ONE `decide` evidence envelope.
        try {
            IdemClaim claim;
            try (Jedis jedis = jedisPool.getResource()) {
                claim = acquireIdempotencyClaim(jedis, "decide", tenant, idempotencyKey, payloadHash);
            }
            if (claim.cachedBody() != null) {
                DecisionResponse replay = objectMapper.readValue(claim.cachedBody(), DecisionResponse.class);
                replay.setIdempotentReplay(true);
                return replay;
            }
            if (claim.pending()) {
                // Another request holds the claim: wait briefly for its result, else 500-retry.
                // Same bounded-wait contract as dry_run (concurrent same-key callers converge to
                // one evaluation); a loser that times out retries with the same idempotency_key.
                String body = waitForIdempotentReplayBody("decide",
                    idempotencyCacheKey("decide", tenant, idempotencyKey), payloadHash);
                if (body != null) {
                    DecisionResponse replay = objectMapper.readValue(body, DecisionResponse.class);
                    replay.setIdempotentReplay(true);
                    return replay;
                }
                throw idempotencyStillPending();
            }

            // Fresh decision: evaluate in its own connection; clear the claim if evaluation fails.
            DecisionResponse response;
            try (Jedis jedis = jedisPool.getResource()) {
                response = evaluateDecisionBudget(jedis, request, tenant);
            } catch (Exception evalError) {
                clearIdempotencyClaim(claim.claimKey(), claim.claimMarker());
                throw evalError;
            }

            // `decide` CyclesEvidence over {request, response} (response AS-IS, before cycles_evidence
            // is stamped). Emit + cache use their own short-lived connections (no pool nesting), and
            // the cache/clear helpers self-acquire fail-open connections.
            Map<String, Object> evidenceBody = new LinkedHashMap<>();
            evidenceBody.put("request", request);
            evidenceBody.put("response", response);
            if (idempotencyKey == null || idempotencyKey.isEmpty()) {
                EvidenceEmitter.EvidenceRef ref = evidenceEmitter.emit("decide",
                    System.currentTimeMillis(), traceId, evidenceBody);
                if (ref != null) {
                    response.setCyclesEvidence(toCyclesEvidenceRef(ref));
                }
                return response;
            }
            EvidenceEmitter.PreparedEvidence prepared = evidenceEmitter.prepare("decide",
                System.currentTimeMillis(), traceId, evidenceBody);
            if (prepared != null) {
                response.setCyclesEvidence(toCyclesEvidenceRef(prepared.ref()));
            }
            if (!cacheIdempotentBody("decide", tenant, idempotencyKey, payloadHash,
                    response, prepared, claim.claimMarker())) {
                response.setCyclesEvidence(null);
                clearIdempotencyClaim(claim.claimKey(), claim.claimMarker());
                throw idempotencyResponsePersistenceFailed();
            }
            return response;
        } catch (CyclesProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;   // already unchecked (incl. the eval-catch rethrow) — don't double-wrap
        } catch (Exception e) {
            LOG.error("Failed to evaluate decision: tenant={} idempotency_key_present={} trace_id={}",
                    LogSanitizer.sanitize(tenant), idempotencyKey != null && !idempotencyKey.isBlank(), traceId, e);
            throw new RuntimeException(e);
        }
    }

    private DecisionResponse evaluateDecisionBudget(Jedis jedis, DecisionRequest request,
                                                    String tenant) throws Exception {
        List<String> affectedScopes = scopeService.deriveScopes(request.getSubject());
        long estimateAmount = request.getEstimate().getAmount();
        String unit = request.getEstimate().getUnit().name();

        // Governance tenant gate (see evaluateTenantStatusGate): shared with the
        // dry_run path so both non-persisting surfaces answer a closed tenant
        // the same way - 200 DENY/TENANT_CLOSED, never a signed ALLOW; a
        // malformed record throws 500 (decide()'s eval-catch clears the claim).
        Enums.ReasonCode tenantGate = evaluateTenantStatusGate(jedis, tenant);
        if (tenantGate != null) {
            return DecisionResponse.builder()
                .decision(Enums.DecisionEnum.DENY)
                .reasonCode(tenantGate)
                .affectedScopes(affectedScopes)
                .build();
        }

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

        return response;
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
            boolean idempotentReplay = !eventId.equals(responseEventId);

            // Parse balances returned atomically from Lua (no extra round-trips)
            List<Balance> balances = parseLuaBalances(response, request.getActual().getUnit());

            // Lua returns charged amount (may be less than requested with ALLOW_IF_AVAILABLE capping)
            Amount charged = null;
            Long chargedAmount = parseNullableLong(response.get("charged"));
            if (chargedAmount != null) {
                charged = new Amount(request.getActual().getUnit(), chargedAmount);
            }

            Map<String, Long> scopeDebtIncurred = parseScopeDebtIncurred(response);

            metrics.recordEvent(tenant, "APPLIED",
                    idempotentReplay ? "IDEMPOTENT_REPLAY" : "OK",
                    overagePolicyTag);
            // Any scope that actually accrued debt counts as overdraft incurred
            // (event path creates debt the same way commit does).
            boolean anyDebt = scopeDebtIncurred != null
                && scopeDebtIncurred.values().stream().anyMatch(v -> v != null && v > 0);
            if (!idempotentReplay && anyDebt) {
                metrics.recordOverdraftIncurred(tenant);
            }
            return EventCreateResponse.builder()
                .status(Enums.EventStatus.APPLIED)
                .eventId(responseEventId)
                .charged(charged)
                .balances(balances)
                .idempotentReplay(idempotentReplay)
                .scopeDebtIncurred(scopeDebtIncurred)
                .preRemaining(parsePreRemaining(response))
                .preIsOverLimit(parsePreIsOverLimit(response))
                .build();
        } catch (CyclesProtocolException e) {
            metrics.recordEvent(tenant, "DENY", e.getErrorCode().name(), overagePolicyTag);
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to create event: tenant={} idempotency_key_present={} overage_policy={}",
                    LogSanitizer.sanitize(tenant),
                    request != null && request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank(),
                    overagePolicyTag, e);
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
            LOG.error("Failed to search for reservation by id: reservationId={}",LogSanitizer.sanitize(reservationId),e);
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
            long preRemaining = parseLong(lb.getOrDefault("pre_remaining", "0"));
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
            long debtIncurred = parseLong(lb.getOrDefault("debt_incurred", "0"));
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
            long debtVal = parseLong(lb.getOrDefault("debt", "0"));
            long overdraftLimitVal = parseLong(lb.getOrDefault("overdraft_limit", "0"));
            boolean isOverLimit = Boolean.TRUE.equals(lb.get("is_over_limit"));
            balances.add(Balance.builder()
                .scope(leafScope(scope))
                .scopePath(scope)
                .remaining(new SignedAmount(unit, parseLong(lb.getOrDefault("remaining", "0"))))
                .reserved(new Amount(unit, parseLong(lb.getOrDefault("reserved", "0"))))
                .spent(new Amount(unit, parseLong(lb.getOrDefault("spent", "0"))))
                .allocated(new Amount(unit, parseLong(lb.getOrDefault("allocated", "0"))))
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
            LOG.warn("Failed to read tenant config for tenant: {}", LogSanitizer.sanitize(tenant), e);
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
                try {
                    return Enums.CommitOveragePolicy.valueOf(defaultPolicy.toString()).name();
                } catch (IllegalArgumentException e) {
                    throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                        "Invalid tenant default_commit_overage_policy: " + defaultPolicy, 400);
                }
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
            case "TENANT_CLOSED":
                // Governance Rule 2 terminal-owner guard: the owning tenant's
                // CLOSED flip is durable — reservation mutations are rejected
                // (reserve/commit/release/extend .lua all return this token).
                String closedTenant = response.containsKey("tenant") ? response.get("tenant").toString() : "unknown";
                throw CyclesProtocolException.tenantClosed(closedTenant);
            case "IDEMPOTENCY_MISMATCH":
                throw CyclesProtocolException.idempotencyMismatch();
            case "IDEMPOTENCY_PENDING":
                throw idempotencyStillPending();
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
            case "INVALID_REQUEST":
                throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST, message, 400);
            case "INTERNAL_ERROR":
                // Explicit mapping so script-side INTERNAL_ERROR tokens (e.g. the
                // fail-closed malformed-tenant-record guard, corrupted reservation
                // data) keep their diagnostic message instead of the generic
                // "Script error: INTERNAL_ERROR" default below.
                throw new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR, message, 500);
            default:
                throw new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR, "Script error: " + error, 500);
        }
    }
}
