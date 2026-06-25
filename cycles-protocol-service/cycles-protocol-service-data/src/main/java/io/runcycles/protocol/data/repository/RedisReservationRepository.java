package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.metrics.CyclesMetrics;
import io.runcycles.protocol.data.repository.support.FilterHasher;
import io.runcycles.protocol.data.repository.support.ReservationComparators;
import io.runcycles.protocol.data.repository.support.ScanPageCursor;
import io.runcycles.protocol.data.repository.support.SortedListCursor;
import io.runcycles.protocol.data.service.EvidenceEmitter;
import io.runcycles.protocol.data.service.LuaScriptRegistry;
import io.runcycles.protocol.data.service.ScopeDerivationService;
import io.runcycles.protocol.data.util.LogSanitizer;
import io.runcycles.protocol.model.*;
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

    /** Warn when the naive sorted list path hydrates a large result set. */
    static final int SORTED_HYDRATE_WARN_THRESHOLD = 2000;
    private static final long IDEMPOTENCY_CACHE_TTL_MS = 86_400_000L;
    // commit/release are terminal: reserve/commit/release.lua set a 30-day TTL on the
    // finalized reservation hash (audit trail), so the commit/release idempotent replay is
    // valid for 30 days. The evidence body cache must match so it never expires before the
    // reservation hash that drives the Lua replay.
    private static final long TERMINAL_BODY_CACHE_TTL_MS = 2_592_000_000L;
    private static final int RESERVE_BODY_CACHE_WAIT_ATTEMPTS = 4;
    // Shared by the non-persisting idempotent-evaluation path (dry_run + decide): a fresh
    // evaluator atomically claims `idem:<tenant>:<kind>:<key>` with a pending marker, others
    // wait for the cached result. Marker prefix is per-kind (`__<kind>_pending__:`).
    private static final int IDEMPOTENT_EVAL_WAIT_ATTEMPTS = 4;
    private static final long IDEMPOTENCY_CACHE_WAIT_SLEEP_MS = 25L;
    private static final long IDEMPOTENT_EVAL_PENDING_TTL_MS = 60_000L;
    private static final String COMPARE_AND_DELETE_SCRIPT =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "return redis.call('DEL', KEYS[1]) else return 0 end";

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
            // evaluation, stamp evidence + cache the full body (keyed by the dry_run idem key)
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
                    // Fallback (cache expired/absent, e.g. a reservation created before the
                    // full-response cache existed): rebuild from the stored reservation. Balances
                    // reflect current state and no cycles_evidence is replayed — a bounded
                    // degradation only when the original body is no longer available.
                    return rebuildReserveReplay(existingId);
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
                ReservationCreateResponse created = ReservationCreateResponse.builder()
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
                stampAndEmitEvidence(created, request, traceId);
                cacheReserveResponse(reservationId, created, bodyTtlMs);
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
        idemPipe.sync();
        String cached = cachedResp.get();
        if (cached != null) {
            if (isPending(kind, cached)) {
                validatePendingMarker(kind, cached, payloadHash);
                return IdemClaim.waiting();
            }
            validateCachedHash(hashResp.get(), payloadHash);
            return IdemClaim.replay(cached);
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
            String body = waitForIdempotentReplayBody("dry_run", dryRun.claimKey(), dryRun.payloadHash());
            if (body != null) {
                ReservationCreateResponse replay = objectMapper.readValue(body, ReservationCreateResponse.class);
                replay.setIdempotentReplay(true);
                return replay;
            }
            throw idempotencyStillPending();
        }

        ReservationCreateResponse dryRunResponse = dryRun.response();
        if (!dryRunResponse.isIdempotentReplay()) {
            stampAndEmitEvidence(dryRunResponse, request, traceId);
            // cacheIdempotentBody/clearIdempotencyClaim self-acquire fail-open connections,
            // so a pool failure here can't fail the response or leak the claim past its TTL.
            boolean cached = cacheIdempotentBody("dry_run", tenant, request.getIdempotencyKey(),
                computePayloadHash(request), dryRunResponse);
            if (!cached) {
                clearIdempotencyClaim(dryRun.claimKey(), dryRun.claimMarker());
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
            // Record the ref on the reservation so it is linkable later via
            // include=evidence. Null reservation_id (dry_run) is a no-op.
            persistEvidenceRef(response.getReservationId(), "reserve", ref);
        }
    }

    /**
     * Cache the FULL original reserve response (with {@code cycles_evidence} stamped),
     * keyed by {@code reservation_id}, so an idempotent replay returns the byte-identical
     * original payload (original balances + original evidence, matching the envelope the
     * {@code evidence_id} points to). The {@code ttlMs} MUST be ≥ reserve.lua's idempotency
     * TTL ({@code max(ttl_ms+grace_ms, 24h)}) so the body never expires before the
     * idempotency key (else a replay in the gap falls back to rebuilt current balances).
     *
     * <p>Fail-open: a write failure is logged, never thrown — it only degrades a later
     * replay to the rebuild fallback, never fails the committed reservation.
     */
    private void cacheReserveResponse(String reservationId,
                                      ReservationCreateResponse response, long ttlMs) {
        if (reservationId == null || reservationId.isEmpty()) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.psetex(reserveResponseCacheKey(reservationId), ttlMs,
                    objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            LOG.warn("Failed to cache reserve response: reservation_id={} ttl_ms={} error={}",
                    LogSanitizer.sanitize(reservationId), ttlMs, LogSanitizer.sanitize(e.toString()), e);
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
    private EvidenceEmitter.EvidenceRef emitLifecycleEvidence(String artifactType, String traceId,
                                                              Object payloadBody) {
        return evidenceEmitter.emit(artifactType, System.currentTimeMillis(), traceId, payloadBody);
    }

    /**
     * Persist the just-computed evidence ref for an artifact onto the reservation
     * hash ({@code <artifact>_evidence_id} / {@code <artifact>_evidence_url}), so
     * {@code listReservations} / {@code getReservation} can surface it via
     * {@code include=evidence} — letting a consumer link a reservation to its
     * signed envelope(s) without having captured the id off the original
     * response. Both id and url are stored so hydration needs no server-id
     * reconstruction. No-op when the ref is null (evidence emission disabled) or
     * for dry-run (no reservation_id). Fail-open: a write failure is logged,
     * never thrown — it only degrades the evidence projection, never the op.
     */
    private void persistEvidenceRef(String reservationId, String artifactType,
                                    EvidenceEmitter.EvidenceRef ref) {
        if (ref == null || reservationId == null || reservationId.isEmpty()) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put(artifactType + "_evidence_id", ref.evidenceId());
            f.put(artifactType + "_evidence_url", ref.cyclesEvidenceUrl());
            jedis.hset("reservation:res_" + reservationId, f);
        } catch (Exception e) {
            LOG.warn("Failed to persist reservation evidence ref: artifact_type={} reservation_id={} evidence_id={} error={}",
                    artifactType, LogSanitizer.sanitize(reservationId), ref.evidenceId(), LogSanitizer.sanitize(e.toString()), e);
        }
    }

    /**
     * Hydrate the {@code evidence} projection from the persisted
     * {@code <artifact>_evidence_id} / {@code _url} hash fields. Returns null
     * when no artifact has recorded evidence (NON_NULL then strips the field).
     */
    private ReservationEvidence buildEvidence(Map<String, String> fields) {
        ReservationEvidence.ReservationEvidenceBuilder b = ReservationEvidence.builder();
        boolean any = false;
        for (String artifact : new String[] {"reserve", "commit", "release"}) {
            String id = fields.get(artifact + "_evidence_id");
            String url = fields.get(artifact + "_evidence_url");
            if (id == null || id.isEmpty() || url == null || url.isEmpty()) {
                continue;
            }
            CyclesEvidenceRef ref = CyclesEvidenceRef.builder()
                    .evidenceId(id).cyclesEvidenceUrl(url).build();
            switch (artifact) {
                case "reserve" -> b.reserve(ref);
                case "commit" -> b.commit(ref);
                case "release" -> b.release(ref);
                default -> { /* unreachable */ }
            }
            any = true;
        }
        return any ? b.build() : null;
    }

    /** Cache a finalized lifecycle response body (with evidence stamped) for verbatim replay,
     *  keyed by {@code <artifact>:body:<reservation_id>}, 30-day TTL matching the terminal
     *  reservation hash. Fail-open. */
    private void cacheLifecycleBody(String artifactType, String reservationId, Object response) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.psetex(lifecycleBodyCacheKey(artifactType, reservationId),
                    TERMINAL_BODY_CACHE_TTL_MS, objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            LOG.warn("Failed to cache lifecycle response body: artifact_type={} reservation_id={} ttl_ms={} error={}",
                    artifactType, LogSanitizer.sanitize(reservationId), TERMINAL_BODY_CACHE_TTL_MS, LogSanitizer.sanitize(e.toString()), e);
        }
    }

    /** Read a cached lifecycle response body (per-poll connections, bounded wait), or
     *  {@code null} if absent. */
    private <T> T readCachedLifecycleBody(String artifactType, String reservationId, Class<T> type)
            throws Exception {
        String cached = readCacheValueWithWait(
            lifecycleBodyCacheKey(artifactType, reservationId), RESERVE_BODY_CACHE_WAIT_ATTEMPTS);
        return cached == null ? null : objectMapper.readValue(cached, type);
    }

    private ReservationCreateResponse rebuildReserveReplay(String existingId) throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> existingFields = jedis.hgetAll("reservation:res_" + existingId);
            if (existingFields == null || existingFields.isEmpty()) {
                throw CyclesProtocolException.notFound(existingId);
            }
            ReservationSummary existing = buildReservationSummary(existingFields);
            List<Balance> idempotencyBalances = fetchBalancesForScopes(
                jedis, existing.getAffectedScopes(), existing.getReserved().getUnit());
            return ReservationCreateResponse.builder()
                .decision(Enums.DecisionEnum.ALLOW)
                .reservationId(existingId)
                .affectedScopes(existing.getAffectedScopes())
                .scopePath(existing.getScopePath())
                .reserved(existing.getReserved())
                .expiresAtMs(existing.getExpiresAtMs())
                .balances(idempotencyBalances)
                .idempotentReplay(true)
                .build();
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
            IdemClaim claim = acquireIdempotencyClaim(jedis, "dry_run", tenant, idempotencyKey, payloadHash);
            if (claim.cachedBody() != null) {
                ReservationCreateResponse replay =
                    objectMapper.readValue(claim.cachedBody(), ReservationCreateResponse.class);
                replay.setIdempotentReplay(true);
                return DryRunResult.replay(replay);
            }
            if (claim.pending()) {
                return DryRunResult.pending(
                    idempotencyCacheKey("dry_run", tenant, idempotencyKey), payloadHash);
            }
            dryRunClaimKey = claim.claimKey();
            dryRunClaimMarker = claim.claimMarker();

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
                                        String payloadHash, Object response) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return true;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String idemKey = idempotencyCacheKey(kind, tenant, idempotencyKey);
            Pipeline pipe = jedis.pipelined();
            if (payloadHash != null && !payloadHash.isEmpty()) {
                pipe.psetex(idemKey + ":hash", IDEMPOTENCY_CACHE_TTL_MS, payloadHash);
            }
            pipe.psetex(idemKey, IDEMPOTENCY_CACHE_TTL_MS, objectMapper.writeValueAsString(response));
            pipe.sync();
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to cache idempotent response: kind={} tenant={} idempotency_key_present={} ttl_ms={} error={}",
                    kind, LogSanitizer.sanitize(tenant), true, IDEMPOTENCY_CACHE_TTL_MS, LogSanitizer.sanitize(e.toString()), e);
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
            CommitResponse committed = CommitResponse.builder()
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

            // Idempotent replay (Lua signals `replay`): return the ORIGINAL cached body
            // (with its original cycles_evidence) verbatim; never re-emit. The internal
            // event-emission fields are intentionally absent on the cached body, and the
            // controller skips event emission on a replay.
            if (Boolean.TRUE.equals(response.get("replay"))) {
                committed.setIdempotentReplay(true);
                jedis.close();
                jedis = null;
                CommitResponse cached = readCachedLifecycleBody("commit", reservationId, CommitResponse.class);
                if (cached != null) {
                    cached.setIdempotentReplay(true);
                    return cached;
                }
                return committed; // fallback (cache absent): no evidence replayed
            }

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
            EvidenceEmitter.EvidenceRef ref = emitLifecycleEvidence("commit", traceId, evidenceBody);
            if (ref != null) {
                committed.setCyclesEvidence(CyclesEvidenceRef.builder()
                        .evidenceId(ref.evidenceId())
                        .cyclesEvidenceUrl(ref.cyclesEvidenceUrl())
                        .build());
                persistEvidenceRef(reservationId, "commit", ref);
            }
            cacheLifecycleBody("commit", reservationId, committed);
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
        LOG.debug("Releasing reservation: {}", reservationId);

        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
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

            // Idempotent replay: return the original cached body verbatim; never re-emit.
            if (Boolean.TRUE.equals(response.get("replay"))) {
                releasedResponse.setIdempotentReplay(true);
                jedis.close();
                jedis = null;
                ReleaseResponse cached = readCachedLifecycleBody("release", reservationId, ReleaseResponse.class);
                if (cached != null) {
                    cached.setIdempotentReplay(true);
                    return cached;
                }
                return releasedResponse; // fallback (cache absent): no evidence replayed
            }

            // Fresh release: emit `release` evidence over {reservation_id, request, response}
            // (before cycles_evidence is stamped), stamp the ref, cache the body for replay.
            // Release the Lua connection first (see commit) to avoid nested pool acquisition.
            jedis.close();
            jedis = null;
            Map<String, Object> evidenceBody = new LinkedHashMap<>();
            evidenceBody.put("reservation_id", reservationId);
            evidenceBody.put("request", request);
            evidenceBody.put("response", releasedResponse);
            EvidenceEmitter.EvidenceRef ref = emitLifecycleEvidence("release", traceId, evidenceBody);
            if (ref != null) {
                releasedResponse.setCyclesEvidence(CyclesEvidenceRef.builder()
                        .evidenceId(ref.evidenceId())
                        .cyclesEvidenceUrl(ref.cyclesEvidenceUrl())
                        .build());
                persistEvidenceRef(reservationId, "release", ref);
            }
            cacheLifecycleBody("release", reservationId, releasedResponse);
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
            LOG.error("Failed to get reservation by id: {}", LogSanitizer.sanitize(reservationId), e);
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
     * deliberately NOT part of the filter/cursor binding (see
     * {@link FilterHasher} — it is not passed there), so changing {@code include}
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
        // Normalize once at entry: tolerate a null arg (direct callers) and take a
        // private snapshot so a caller mutating their set mid-scan can't affect
        // projection. EnumSet.copyOf requires a non-empty source.
        Set<ReservationInclude> includeFields = (include == null || include.isEmpty())
            ? EnumSet.noneOf(ReservationInclude.class)
            : EnumSet.copyOf(include);
        boolean sortRequested = sortBy != null || sortDir != null;
        Optional<SortedListCursor> parsedCursor = SortedListCursor.decode(startCursor);

        // Route to sorted path when sort params are provided OR when the incoming cursor
        // is a sorted-path cursor (client may omit sort params on the follow-up request,
        // expecting the cursor to carry the sort state — we honour that).
        if (sortRequested || parsedCursor.isPresent()) {
            return listReservationsSorted(tenant, idempotencyKey, status, workspace, app,
                workflow, agent, toolset, limit, parsedCursor.orElse(null), sortBy, sortDir,
                fromMs, toMs, expiresFromMs, expiresToMs, finalizedFromMs, finalizedToMs, includeFields);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().match("reservation:res_*").count(100);
            List<ReservationSummary> result = new ArrayList<>();
            ScanPageCursor pageCursor = ScanPageCursor.decode(startCursor);
            String cursor = pageCursor.redisCursor();
            int batchOffset = pageCursor.offset();

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

                    int startIndex = Math.min(batchOffset, keys.size());
                    batchOffset = 0;
                    for (int i = startIndex; i < keys.size(); i++) {
                        String key = keys.get(i);
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
                            if (!createdAtInWindow(fields, fromMs, toMs)) continue;
                            if (!expiresAtInWindow(fields, expiresFromMs, expiresToMs)) continue;
                            if (!finalizedAtInWindow(fields, finalizedFromMs, finalizedToMs)) continue;

                            result.add(toSummary(buildReservationSummary(fields), includeFields));

                            if (result.size() >= limit) {
                                String nextCursor = ScanPageCursor.nextCursor(
                                    cursor, i + 1, keys.size(), scan.getCursor());
                                // Spec: has_more is true only when next_cursor is present
                                return ReservationListResponse.builder()
                                    .reservations(result)
                                    .hasMore(nextCursor != null)
                                    .nextCursor(nextCursor)
                                    .build();
                            }
                        } catch (Exception e) {
                            LOG.warn("Failed to parse reservation: {}", LogSanitizer.sanitize(key), e);
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
            int limit, SortedListCursor resumeCursor, String sortBy, String sortDir,
            Long fromMs, Long toMs,
            Long expiresFromMs, Long expiresToMs,
            Long finalizedFromMs, Long finalizedToMs,
            Set<ReservationInclude> include) {

        // Normalize for cursor storage + comparator use. Null sort_dir with a non-null
        // sort_by defaults to DESC per spec; null sort_by with a non-null sort_dir defaults
        // to CREATED_AT_MS. Resume-from-cursor honours the tuple encoded in the cursor.
        String effectiveSortBy = sortBy != null ? sortBy.toLowerCase()
            : (resumeCursor != null ? resumeCursor.getSortBy() : "created_at_ms");
        String effectiveSortDir = sortDir != null ? sortDir.toLowerCase()
            : (resumeCursor != null ? resumeCursor.getSortDir() : "desc");

        // include is deliberately NOT folded into the filter hash: it is a
        // projection-only parameter (which fields serialize), not a filter
        // (which rows / what order). Changing include across pages MUST NOT
        // invalidate a cursor. Spec: cycles-protocol-v0 revision 2026-06-19.
        String filterHash = FilterHasher.hash(tenant, idempotencyKey, status,
            workspace, app, workflow, agent, toolset, fromMs, toMs,
            expiresFromMs, expiresToMs, finalizedFromMs, finalizedToMs);

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
                            if (!createdAtInWindow(fields, fromMs, toMs)) continue;
                            if (!expiresAtInWindow(fields, expiresFromMs, expiresToMs)) continue;
                            if (!finalizedAtInWindow(fields, finalizedFromMs, finalizedToMs)) continue;

                            matching.add(toSummary(buildReservationSummary(fields), include));
                        } catch (Exception e) {
                            LOG.warn("Failed to parse reservation: {}", LogSanitizer.sanitize(key), e);
                        }
                    }
                }
                scanCursor = scan.getCursor();
            } while (!"0".equals(scanCursor));

            if (matching.size() >= SORTED_HYDRATE_WARN_THRESHOLD) {
                LOG.warn("listReservationsSorted hydrated {} rows for tenant={} sort_by={} sort_dir={}; narrow filters or add sorted indices before this grows further",
                    matching.size(), LogSanitizer.sanitize(tenant), effectiveSortBy, effectiveSortDir);
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

    /**
     * Inclusive time-window predicate for listReservations from/to filters
     * (cycles-protocol-v0.yaml revision 2026-05-21). Reads the per-reservation
     * {@code created_at} hash field (stored as epoch-ms decimal string) and
     * returns true iff the row is inside the requested window. A row with
     * missing or unparseable {@code created_at} is treated as out-of-window
     * when EITHER bound is supplied — leaking malformed-write rows past a
     * time filter would silently break the contract.
     *
     * <p>Returns true when both bounds are null (filter inactive).
     */
    private static boolean createdAtInWindow(Map<String, String> fields, Long fromMs, Long toMs) {
        if (fromMs == null && toMs == null) return true;
        String createdAtStr = fields.get("created_at");
        if (createdAtStr == null) return false;
        long createdAt;
        try {
            createdAt = Long.parseLong(createdAtStr);
        } catch (NumberFormatException e) {
            return false;
        }
        if (fromMs != null && createdAt < fromMs) return false;
        if (toMs != null && createdAt > toMs) return false;
        return true;
    }

    /**
     * Inclusive time-window predicate for listReservations expires_from / expires_to
     * filters (cycles-protocol-v0.yaml revision 2026-05-22). Reads the per-reservation
     * {@code expires_at} hash field (epoch-ms decimal string). Same defensive shape as
     * {@link #createdAtInWindow}: missing or unparseable {@code expires_at} is treated
     * as out-of-window when EITHER bound is supplied. Returns true when both bounds
     * are null (filter inactive).
     *
     * <p>{@code expires_at_ms} is REQUIRED on every reservation per the spec, so the
     * field-absent path here is purely defensive against malformed Redis writes — in
     * normal operation every hydrated reservation has it.
     */
    private static boolean expiresAtInWindow(Map<String, String> fields, Long fromMs, Long toMs) {
        if (fromMs == null && toMs == null) return true;
        String expiresAtStr = fields.get("expires_at");
        if (expiresAtStr == null) return false;
        long expiresAt;
        try {
            expiresAt = Long.parseLong(expiresAtStr);
        } catch (NumberFormatException e) {
            return false;
        }
        if (fromMs != null && expiresAt < fromMs) return false;
        if (toMs != null && expiresAt > toMs) return false;
        return true;
    }

    /**
     * Inclusive time-window predicate for listReservations finalized_from /
     * finalized_to filters (cycles-protocol-v0.yaml revision 2026-05-22).
     *
     * <p>Per the spec, {@code finalized_at_ms} is populated ONLY on COMMITTED and
     * RELEASED rows; absent on ACTIVE and EXPIRED. Mirrors the same projection logic
     * as {@link #buildReservationSummary}: {@code committed_at} populates the
     * timestamp for COMMITTED rows, {@code released_at} for RELEASED rows. Rows
     * missing both hash fields (ACTIVE, EXPIRED, malformed) MUST be excluded from
     * results when either bound is supplied — the spec makes this exclusion
     * normative so conformant servers agree on the contract.
     *
     * <p>Returns true when both bounds are null (filter inactive); this case
     * preserves the row regardless of whether {@code finalized_at_ms} is
     * present, matching the v0.1.25.20 unfiltered behavior byte-for-byte.
     */
    private static boolean finalizedAtInWindow(Map<String, String> fields, Long fromMs, Long toMs) {
        if (fromMs == null && toMs == null) return true;
        String finalizedAtStr = resolveFinalizedAtStr(fields);
        if (finalizedAtStr == null) return false;
        long finalizedAt;
        try {
            finalizedAt = Long.parseLong(finalizedAtStr);
        } catch (NumberFormatException e) {
            return false;
        }
        if (fromMs != null && finalizedAt < fromMs) return false;
        if (toMs != null && finalizedAt > toMs) return false;
        return true;
    }

    /**
     * Centralized resolver for the projected {@code finalized_at_ms} timestamp,
     * shared between the {@link #finalizedAtInWindow} predicate and the
     * {@link #buildReservationSummary} projection. Both call sites MUST agree
     * on which Redis hash field is the source of truth, otherwise a malformed
     * row with both {@code committed_at} and {@code released_at} populated
     * could be filtered using one timestamp and returned with another —
     * violating the spec's contract that the filter operates against the
     * returned {@code finalized_at_ms}.
     *
     * <p>Released-wins: {@code released_at} dominates when both are set, matching
     * the projection's last-write-wins assignment order in
     * {@link #buildReservationSummary}. In the normal lifecycle the two fields
     * are mutually exclusive (commit XOR release), so this rule is a defensive
     * tie-breaker for malformed Redis writes only.
     *
     * <p>Returns {@code null} when both fields are absent (ACTIVE / EXPIRED rows
     * in the normal lifecycle). Returns the raw string form so callers can
     * decide on parse-failure handling (the predicate swallows
     * NumberFormatException to exclude the row from filter results; the
     * projection lets it propagate as a data-corruption signal).
     */
    private static String resolveFinalizedAtStr(Map<String, String> fields) {
        String releasedAt = fields.get("released_at");
        if (releasedAt != null) return releasedAt;
        return fields.get("committed_at");
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
                response = evaluateDecisionBudget(jedis, request);
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
            EvidenceEmitter.EvidenceRef ref = evidenceEmitter.emit("decide",
                System.currentTimeMillis(), traceId, evidenceBody);
            if (ref != null) {
                response.setCyclesEvidence(CyclesEvidenceRef.builder()
                    .evidenceId(ref.evidenceId())
                    .cyclesEvidenceUrl(ref.cyclesEvidenceUrl())
                    .build());
            }
            if (!cacheIdempotentBody("decide", tenant, idempotencyKey, payloadHash, response)) {
                clearIdempotencyClaim(claim.claimKey(), claim.claimMarker());
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

    private DecisionResponse evaluateDecisionBudget(Jedis jedis, DecisionRequest request) throws Exception {
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
            Number chargedNum = (Number) response.get("charged");
            if (chargedNum != null) {
                charged = new Amount(request.getActual().getUnit(), chargedNum.longValue());
            }

            Map<String, Long> scopeDebtIncurred = parseScopeDebtIncurred(response);

            if (!idempotentReplay) {
                metrics.recordEvent(tenant, "APPLIED", "OK", overagePolicyTag);
            }
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

    private ReservationSummary toSummary(ReservationDetail detail, Set<ReservationInclude> include) {
        ReservationSummary.ReservationSummaryBuilder builder = ReservationSummary.builder()
            .reservationId(detail.getReservationId())
            .status(detail.getStatus())
            .idempotencyKey(detail.getIdempotencyKey())
            .subject(detail.getSubject())
            .action(detail.getAction())
            .reserved(detail.getReserved())
            .createdAtMs(detail.getCreatedAtMs())
            .expiresAtMs(detail.getExpiresAtMs())
            // Spec: cycles-protocol-v0.yaml revision 2026-05-22. Optional;
            // null on ACTIVE and EXPIRED rows. NON_NULL JsonInclude on the
            // class skips the field from the wire when absent, preserving
            // byte-for-byte response shape for pre-revision callers.
            .finalizedAtMs(detail.getFinalizedAtMs())
            .scopePath(detail.getScopePath())
            .affectedScopes(detail.getAffectedScopes())
            // committed (the COMMIT charge) is projected UNCONDITIONALLY on list
            // rows, on the same footing as finalized_at_ms — a small scalar, and
            // NON_NULL strips it on non-COMMITTED rows. Spec: cycles-protocol-v0
            // revision 2026-06-19 (cycles-server#201).
            .committed(detail.getCommitted());
        // metadata / committed_metadata are arbitrary-size, possibly-PII maps, so
        // they are OMITTED FROM LIST ROWS BY DEFAULT and projected only when the
        // caller opts in via ?include=. The single-row getReservation path always
        // carries them (it serializes the ReservationDetail directly). Spec rev
        // 2026-06-19.
        if (include.contains(ReservationInclude.METADATA)) {
            builder.metadata(detail.getMetadata());
        }
        if (include.contains(ReservationInclude.COMMITTED_METADATA)) {
            builder.committedMetadata(detail.getCommittedMetadata());
        }
        // evidence is the linkage from a reservation to its signed envelope(s);
        // a small map of refs, but opt-in on list rows for symmetry with the
        // other heavy projections. Always present on the single-row detail.
        // Spec: cycles-protocol-v0.yaml revision 2026-06-22 (v0.1.25.9).
        if (include.contains(ReservationInclude.EVIDENCE)) {
            builder.evidence(detail.getEvidence());
        }
        return builder.build();
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
        // Shared resolver with finalizedAtInWindow — the predicate and the
        // projection MUST agree on which hash field wins when both are set.
        // See resolveFinalizedAtStr javadoc for the released-wins rationale.
        String finalizedAtStr = resolveFinalizedAtStr(fields);
        if (finalizedAtStr != null) {
            finalizedAtMs = Long.parseLong(finalizedAtStr);
        }

        // Parse metadata if present
        Map<String, Object> metadata = null;
        String metadataJson = fields.get("metadata_json");
        if (metadataJson != null && !metadataJson.isEmpty()) {
            metadata = objectMapper.readValue(metadataJson, Map.class);
        }

        // Parse commit-time metadata if present. commit.lua persists the COMMIT
        // request's metadata as committed_metadata_json; surface it as
        // committed_metadata so it is readable, not write-only (cycles-server#197).
        Map<String, Object> committedMetadata = null;
        String committedMetadataJson = fields.get("committed_metadata_json");
        if (committedMetadataJson != null && !committedMetadataJson.isEmpty()) {
            committedMetadata = objectMapper.readValue(committedMetadataJson, Map.class);
        }

        // As of cycles-protocol revision 2026-06-19 (cycles-server#201) committed
        // / metadata / committed_metadata live on the shared ReservationSummary
        // base, so set them via the inherited setters rather than a detail-only
        // constructor.
        ReservationDetail detail = new ReservationDetail();
        detail.setCommitted(committed);
        detail.setFinalizedAtMs(finalizedAtMs);
        detail.setMetadata(metadata);
        detail.setCommittedMetadata(committedMetadata);
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
        // Evidence refs recorded at reserve/commit/release time (spec v0.1.25.9).
        // Always hydrated onto the detail; listReservations strips it in toSummary
        // unless include=evidence. Null when the reservation has no recorded
        // evidence (emission disabled, or pre-evidence reservation).
        detail.setEvidence(buildEvidence(fields));
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
            case "INVALID_REQUEST":
                throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST, message, 400);
            default:
                throw new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR, "Script error: " + error, 500);
        }
    }
}
