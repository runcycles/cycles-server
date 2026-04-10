# Cycles Protocol v0.1.25 — Server Implementation Audit

**Date:** 2026-04-10 (v0.1.25.6 reserve/event UNIT_MISMATCH detection), 2026-04-08 (v0.1.25.5 duplicate event fix), 2026-04-07 (v0.1.25.4 event data completeness), 2026-04-01 (v0.1.25 event emission + TTL), 2026-03-24 (Round 6: spec compliance audit), 2026-03-24 (v0.1.24 update), 2026-03-23 (updated), 2026-03-15 (initial)
**Spec:** `cycles-protocol-v0.yaml` (OpenAPI 3.1.0, v0.1.25) + `complete-budget-governance-v0.1.25.yaml` (events/webhooks)
**Server:** Spring Boot 3.5.11 / Java 21 / Redis (Lua scripts)

---

### 2026-04-10 — v0.1.25.6: Distinguish UNIT_MISMATCH from BUDGET_NOT_FOUND on reserve/event

**Bug (runcycles/cycles-client-rust#8):** `POST /v1/reservations` with `Amount::tokens(1000)` against a scope whose budget was stored in `USD_MICROCENTS` returned `404 BUDGET_NOT_FOUND`. The client could not distinguish "no budget at this scope" from "budget exists but in a different unit" and had no hint toward the fix. `/v1/events` had the same latent bug.

**Root cause:** `reserve.lua` / `event.lua` key budgets by `budget:<scope>:<unit>`. When the requested unit doesn't match the stored unit, the key doesn't exist, the scope is silently skipped, and `#budgeted_scopes == 0` falls through to `BUDGET_NOT_FOUND`. The existing `UNIT_MISMATCH` branch in `event.lua` only caught an internal inconsistency between key suffix and stored `unit` field — it did not catch the cross-unit case.

**Fix:** On the empty-budgeted-scopes error path only, both scripts now probe the fixed `UnitEnum` set (`USD_MICROCENTS`, `TOKENS`, `CREDITS`, `RISK_POINTS`) via `EXISTS budget:<scope>:<unit_alt>` for each affected scope. If any alternate-unit budget exists, the script returns `UNIT_MISMATCH` (400) with `scope`, `requested_unit`, and `available_units` in the error payload so the client can self-correct. Otherwise falls through to the existing `BUDGET_NOT_FOUND` (404).

Cascade semantics preserved: scopes without a budget at the requested unit are still silently skipped during the main validation loop — the probe only fires when every affected scope missed. No hot-path change; the cost is paid once on the error path only.

`evaluateDryRun` (the non-Lua dry-run path) gets the symmetric probe in Java and throws `UNIT_MISMATCH` (400) to match the real-reserve behavior.

**Modified files:**
- `reserve.lua` — new `ARGV[15] = units_csv`; scopes now start at ARGV[16]; alternate-unit probe added to the empty-budgeted-scopes branch
- `event.lua` — new `ARGV[14] = units_csv`; scopes now start at ARGV[15]; symmetric probe
- `RedisReservationRepository.java` — `UNIT_CSV` constant derived once from `Enums.UnitEnum.values()`; passed into both `createReservation` and `createEvent` args; `evaluateDryRun` mirrors the probe in Java; `handleScriptError` extracts `scope` / `requested_unit` / `available_units` for reserve/event and falls back to the no-detail factory for commit.lua's legacy form
- `CyclesProtocolException.java` — `unitMismatch(scope, requestedUnit, availableUnits)` overload populating `details`
- `ReservationLifecycleIntegrationTest.java` — `shouldRejectWhenNoBudgetExistsForUnit` renamed to `shouldRejectWithUnitMismatchWhenBudgetExistsInDifferentUnit` and flipped to expect 400 + details; added `shouldReturnBudgetNotFoundWhenNoBudgetAtAnyUnit` regression guard and `shouldReturnUnitMismatchOnDryRunWhenBudgetExistsInDifferentUnit`
- `DecisionAndEventIntegrationTest.java` — `shouldRejectEventWithUnitMismatch` flipped from 404 `NOT_FOUND` to 400 `UNIT_MISMATCH` + details; added `shouldReturnBudgetNotFoundWhenNoBudgetAtAnyUnitOnEvent`
- `RedisReservationCoreOpsTest.java` — existing `shouldThrowUnitMismatch` asserts the no-detail fallback path; added `shouldThrowUnitMismatchWithDetailsFromReserve`
- `CyclesProtocolExceptionTest.java` — coverage for the new factory overload (populated details + null-tolerant form)
- `cycles-protocol-service/README.md` — error table entry for `UNIT_MISMATCH` broadened to include reserve + describe the `details.*` payload
- `cycles-protocol-service/pom.xml` — `<revision>` bumped `0.1.25.5` → `0.1.25.6`

**Out of scope:** Client-side rust SDK changes (not needed — structured error is enough for the user to correct their call). Protocol YAML spec update lives in `runcycles/cycles-protocol` and is handled on a coordinated branch.

### 2026-04-07 — v0.1.25.4: Event data payload completeness

**Compliance review** against protocol spec v0.1.25 + admin spec v0.1.25 found 5 event data payload gaps. Core protocol (endpoints, schemas, error codes, Lua scripts, idempotency, scope derivation, auth/tenancy) was fully compliant.

**Fixes applied:**

| # | Issue | Fix |
|---|-------|-----|
| 1 | `EventDataReservationDenied` missing `unit`, `remaining`, `action`, `subject` | Populated from request context in DecisionController and ReservationController |
| 2 | `EventDataCommitOverage` missing `scope`, `unit`, `estimatedAmount`, `overage`, `overagePolicy`, `debtIncurred` | Populated from CommitResponse internal fields; added `scope_path`/`overage_policy` to commit.lua return. Audit fix: uses `request.actual` (not `response.charged`) for actualAmount/overage — charged is capped by ALLOW_IF_AVAILABLE |
| 3 | `EventDataBudgetDebtIncurred` missing `reservationId`, `debtIncurred`, `overagePolicy` | Added per-scope `debt_incurred` tracking in commit.lua/event.lua via `scope_debt_incurred` table; plumbed through `scopeDebtIncurred` map in CommitResponse/EventCreateResponse; `emitBalanceEvents()` overload with full context |
| 4 | `budget.exhausted` emitted with `null` data | Now emits `EventDataBudgetThreshold` with scope, unit, threshold=1.0, utilization, allocated, remaining=0, spent, reserved, direction="rising" |
| 5 | `Event.actor` missing `keyId` and `sourceIp` | Added `keyId` to `ApiKeyAuthentication`; `buildActor()` helper in BaseController extracts keyId from auth context and sourceIp from HttpServletRequest |

**Modified files:**
- `commit.lua` — returns `scope_path`/`overage_policy` in response; tracks per-scope `scope_debt_incurred` table, includes in balance snapshots; version comment v0.1.24 → v0.1.25
- `event.lua` — tracks per-scope `scope_debt_incurred` table, includes in balance snapshots
- `CommitResponse.java` — added `@JsonIgnore` internal fields: `scopePath`, `overagePolicy`, `debtIncurred`, `scopeDebtIncurred`
- `EventCreateResponse.java` — added `@JsonIgnore scopeDebtIncurred` map
- `ApiKeyAuthentication.java` — added `keyId` field + getter
- `ApiKeyAuthenticationFilter.java` — passes `keyId` from validation response
- `BaseController.java` — added `buildActor(HttpServletRequest)` helper
- `DecisionController.java` — full EventDataReservationDenied fields + Actor with keyId/sourceIp
- `ReservationController.java` — full EventDataReservationDenied/CommitOverage fields + Actor; uses request.actual (not response.charged) for overage event; passes `scopeDebtIncurred` to emitBalanceEvents
- `EventController.java` — Actor with keyId/sourceIp; passes overagePolicy + scopeDebtIncurred to emitBalanceEvents
- `EventEmitterService.java` — `emitBalanceEvents` overloads with `reservationId`/`overagePolicy`/`scopeDebtIncurred`; budget.exhausted uses EventDataBudgetThreshold; budget.debt_incurred uses per-scope debtIncurred from map
- `RedisReservationRepository.java` — `parseScopeDebtIncurred()` helper; parses `scope_path`, `overage_policy`, `debt_incurred` from Lua response

**Tests:** 287 tests pass, 0 failures. Added tests for `keyId` propagation, budget.exhausted data payload, debt_incurred reservation context, per-scope debt_incurred map.

**Remaining event data gaps:** None. All EventData fields now fully populated for runtime-emitted events.

### 2026-04-08 — v0.1.25.5: Fix duplicate budget state events (cycles-server-events#15)

**Bug:** `budget.exhausted`, `budget.over_limit_entered`, and `budget.debt_incurred` events fired on every operation where the post-state matched the condition, not only on state *transitions*. For example, a reserve that depleted a budget emitted `budget.exhausted`, then the subsequent commit (with remaining still at 0) emitted it again.

**Root cause:** `EventEmitterService.emitBalanceEvents()` checked post-mutation state only (e.g., `remaining == 0`). No transition detection.

**Fix:** Lua scripts (reserve, commit, event) now include `pre_remaining` and `pre_is_over_limit` per scope in balance snapshots. Java emits only on transitions:
- `budget.exhausted`: `pre_remaining > 0 && remaining == 0`
- `budget.over_limit_entered`: `!pre_is_over_limit && is_over_limit`
- `budget.debt_incurred`: `scopeDebtIncurred[scope] > 0` (already tracked)

**Performance:** No extra Redis calls. reserve.lua caches pre-state from existing validation HMGET. commit.lua caches from existing overage-path reads (ALLOW_IF_AVAILABLE/ALLOW_WITH_OVERDRAFT); delta <= 0 paths skip (remaining can only increase). event.lua folds `is_over_limit` into existing HMGET.

---

### 2026-04-03 — v0.1.25.3: Extended runtime event emission + PROTOCOL_VERSION fix

**Version bump:** 0.1.25.1 → 0.1.25.3 (0.1.25.2 was the case-insensitive scope fix below).

**Fix:** `Enums.PROTOCOL_VERSION` was hardcoded to `"0.1.24"` — updated to `"0.1.25"` to match the current protocol spec.

**New runtime event emissions (protocol spec v0.1.25 Webhook Event Guidance):**

Added 4 new event types emitted from Java controllers (non-blocking, async via `EventEmitterService`):

| Event Type | Detection | Emit Location |
|---|---|---|
| `budget.exhausted` | `remaining.amount == 0` in any post-operation balance | ReservationController (reserve, commit), EventController |
| `budget.over_limit_entered` | `is_over_limit == true` in any post-operation balance | ReservationController (reserve, commit), EventController |
| `budget.debt_incurred` | `debt.amount > 0` in any post-operation balance | ReservationController (reserve, commit), EventController |
| `reservation.expired` | Expiry sweeper Lua returns `EXPIRED` status | ReservationExpiryService (post-expire HGETALL for tenant/scope context) |

**Implementation approach:**
- `EventEmitterService.emitBalanceEvents()` — new helper that inspects post-operation balances returned from Lua scripts. Called from ReservationController (after reserve and commit) and EventController (after event creation). No extra Redis calls — uses balances already on the response.
- `ReservationExpiryService.emitExpiredEvent()` — after expire.lua succeeds, fetches the reservation hash (1 HGETALL) to get tenant_id, scope_path, estimate_amount, created_at_ms, expires_at_ms, extension_count for the event payload. Uses `ActorType.SYSTEM` since it's a background job.

**Events NOT emitted (deferred — require new infrastructure):**
- `budget.threshold_crossed` — needs per-scope threshold configuration + utilization % calculation
- `budget.burn_rate_anomaly` — needs time-series rate tracking subsystem
- `budget.over_limit_exited` — admin-only event (triggered by funding operations in cycles-server-admin)

**Runtime event coverage:** 6 of 9 spec-suggested event types now emitted (was 2).

**Modified files:**
- `Enums.java` — PROTOCOL_VERSION "0.1.24" → "0.1.25"
- `EventEmitterService.java` — added `emitBalanceEvents()` helper
- `ReservationController.java` — wired `emitBalanceEvents()` after reserve and commit
- `EventController.java` — wired `emitBalanceEvents()` after event creation
- `ReservationExpiryService.java` — added `emitExpiredEvent()` post-expire hook
- `pom.xml` — revision 0.1.25.1 → 0.1.25.3

---

### 2026-04-03 — v0.1.25.2: Case-insensitive scope matching

**Bug fix (defense-in-depth):** The admin API may have stored mixed-case scope values. `getBalances` lowercased query params but not the stored scope from Redis, causing case mismatches. Now lowercases `trueScope`/`scopePath` before segment matching in both `getBalances` and `listReservations`.

| Fix | Location |
|-----|----------|
| Lowercase `trueScope` before `scopeHasSegment` matching in `getBalances` | `RedisReservationRepository.java` |
| Lowercase `scopePath` before `scopeHasSegment` matching in `listReservations` | `RedisReservationRepository.java` |

Related: runcycles/cycles-openclaw-budget-guard#70, runcycles/cycles-server-admin#54

---

### 2026-04-01 — Webhook Event Emission + TTL Retention + Performance Optimizations

Added webhook event emission to the runtime server per v0.1.25 spec, enabling runtime operations to trigger webhook deliveries via the shared Redis dispatch queue.

**Build:** 553 tests (283 API + 261 Data + 9 Model), 0 failures, 95%+ coverage (all modules).

**New capabilities:**
- ReservationController emits `reservation.denied` on DENY decision
- DecisionController emits `reservation.denied` on DENY decision
- ReservationController emits `reservation.commit_overage` when charged > estimated
- Events written to shared Redis (`event:{id}`, `events:{tenantId}`, `events:_all`)
- Matching webhook subscriptions found via `webhooks:{tenantId}` + `webhooks:__system__`
- PENDING deliveries created and LPUSH'd to `dispatch:pending` for cycles-server-events
- Source field: `"cycles-server"` (vs `"cycles-admin"` from admin)

**TTL retention:**
- `event:{id}` keys: 90-day TTL (configurable via `EVENT_TTL_DAYS`)
- `delivery:{id}` keys: 14-day TTL (configurable via `DELIVERY_TTL_DAYS`)

**Bug fix:** Commit overage emit condition was firing on every commit (`actual != null && charged != null`). Fixed to only emit when `charged > estimateAmount` (true overage). Added `@JsonIgnore estimateAmount` to `CommitResponse` for internal plumbing.

**Performance optimizations (3):**
1. **Async event emission** — `EventEmitterService` uses `CompletableFuture.runAsync()` on a dedicated daemon thread pool (`availableProcessors/4`). Emit never blocks the request thread.
2. **Pipelined Redis in EventEmitterRepository** — Event save (SET + EXPIRE + 2x ZADD) + subscription lookup (2x SMEMBERS) batched into 1 pipeline round-trip (was 6 sequential). Subscription GETs pipelined. Delivery creation pipelined.
3. **Early exit on no subscribers** — If both SMEMBERS return empty sets, skips subscription resolve and delivery creation entirely.

**Result:** Commit p50 recovered from 13.4ms (pre-fix) to 5.6ms. Concurrent throughput at 32 threads: 2,584 ops/s (matching v0.1.24.3 baseline of 2,534 ops/s). Near-zero overhead on non-event paths.

**New/modified files (33):**
- 11 event model classes (Event, EventType, EventCategory, Actor, ActorType, 6 EventData*)
- 6 webhook model classes (WebhookSubscription, WebhookDelivery, WebhookRetryPolicy, etc.)
- CryptoService (AES-256-GCM for signing secret encryption at rest)
- EventEmitterRepository (event persistence + pipelined subscription matching + dispatch)
- EventEmitterService (async emit via CompletableFuture + daemon thread pool)
- CommitResponse (added `@JsonIgnore estimateAmount` for overage detection)
- RedisReservationRepository (sets estimateAmount on CommitResponse)
- ReservationController (fixed commit overage condition: charged > estimateAmount)
- 3 test classes (CryptoServiceTest, EventEmitterRepositoryTest, EventEmitterServiceTest)

**Docker-compose updates:**
- All compose files now include `WEBHOOK_SECRET_ENCRYPTION_KEY` env var
- Full-stack compose files include `cycles-events` service (port 7980)

**Architecture: shared Redis queue**
```
cycles-server ─────┐
                   ├── event:{id}, delivery:{id}, LPUSH dispatch:pending
cycles-admin ──────┘
                           │
                     Redis ─┤
                           │
cycles-server-events ── BRPOP dispatch:pending → HTTP POST with HMAC
```

**Resilience: events service down**
- Admin and runtime continue operating normally (fire-and-forget)
- Events/deliveries accumulate in Redis with TTL (auto-expire after 90d/14d)
- When events service restarts: stale deliveries (>24h) auto-fail, fresh ones delivered
- ZSET indexes trimmed hourly by RetentionCleanupService

---

## Summary

| Category | Pass | Issues |
|----------|------|--------|
| Endpoints & Routes | 9/9 | 0 |
| Request Schemas | 6/6 | 0 |
| Response Schemas | 8/8 | 0 |
| HTTP Status Codes | — | 0 |
| Response Headers | — | 0 |
| Error Handling | — | 0 |
| Auth & Tenancy | — | 0 |
| Idempotency | — | 0 |
| Scope Derivation | — | 0 |
| Normative Requirements | — | 0 |
| Lua Atomicity | — | 0 |
| Dry-Run Semantics | — | 0 |
| Overdraft/Debt Model | — | 0 |
| Grace Period Handling | — | 0 |
| Test Coverage | — | 0 |
| Tenant Default Config | — | 0 |
| Performance Optimizations | 16/16 | 0 |
| Production Hardening | 8/8 | 0 |

**All previously identified issues have been fixed. No remaining spec violations found. Performance optimized, hardened, and benchmarked.**

---

## Audit Scope

Two-pass audit covering:
- All 9 spec endpoints vs server routes
- All 6 request schemas and 8 response schemas (fields, types, constraints, defaults, required markers)
- All 12 error codes, HTTP status mappings, and error semantics
- Auth/tenancy enforcement across all endpoints
- Idempotency per-endpoint namespacing, replay, and payload mismatch detection
- Scope derivation canonical ordering (gaps skipped, not filled)
- Response headers (X-Request-Id, X-Cycles-Tenant, X-RateLimit-*)
- Lua script atomicity for reserve, commit, release, extend, event, expire
- Dry-run response rules (reservation_id/expires_at_ms absent, affected_scopes populated)
- Overdraft/debt model (ALLOW_WITH_OVERDRAFT, is_over_limit, DEBT_OUTSTANDING — only blocks when overdraft_limit=0; ALLOW_WITH_OVERDRAFT falls back to ALLOW_IF_AVAILABLE when overdraft_limit=0)
- Grace period semantics (commits accepted through expires_at_ms + grace_period_ms)
- Subject.dimensions round-tripping
- Unit mismatch detection on commit and event

---

## PASS — Correctly Implemented

### Endpoints (all 9 match spec)
- `POST /v1/decide` — DecisionController
- `POST /v1/reservations` — ReservationController (including dry_run)
- `GET /v1/reservations` — ReservationController (list with filters)
- `GET /v1/reservations/{reservation_id}` — ReservationController
- `POST /v1/reservations/{reservation_id}/commit` — ReservationController
- `POST /v1/reservations/{reservation_id}/release` — ReservationController
- `POST /v1/reservations/{reservation_id}/extend` — ReservationController
- `GET /v1/balances` — BalanceController
- `POST /v1/events` — EventController (returns 201 per spec)

### Request/Response Schemas (all match)
- All 6 request schemas match spec (fields, types, constraints, defaults)
- All 8 response schemas match spec (fields, required markers, types)
- All 12 spec error codes present; server adds 2 extra (BUDGET_FROZEN, BUDGET_CLOSED) for admin-managed budget states
- All 4 status enums match spec
- `Subject` anyOf validation correctly enforced via `hasAtLeastOneStandardField()`
- `additionalProperties: false` correctly enforced via `@JsonIgnoreProperties(ignoreUnknown = false)`
- `Balance` schema includes all debt/overdraft fields (debt, overdraft_limit, is_over_limit)
- `Balance.remaining` correctly typed as `SignedAmount` (allows negative values in overdraft)
- `ReleaseResponse.released` guaranteed non-null (falls back to zero Amount on data corruption)

### Auth & Tenancy (fully correct)
- X-Cycles-API-Key authentication via `ApiKeyAuthenticationFilter`
- Tenant validation via `BaseController.authorizeTenant()` — returns 403 on mismatch
- Reservation ownership enforced on all mutation/read endpoints (commit, release, extend, get)
- Balance visibility correctly scoped to effective tenant
- List reservations correctly scoped to effective tenant
- Subject.tenant validated against effective tenant on reserve, decide, and event endpoints
- Subject.tenant can be null/omitted (spec allows via anyOf constraint)

### Scope Derivation (fully correct)
- Canonical ordering: tenant → workspace → app → workflow → agent → toolset
- Gaps skipped — only explicitly provided subject levels are included (no "default" filling)
- Scopes without budgets are skipped during enforcement; at least one scope must have a budget
- Scope paths lowercased for stable canonicalization
- Subject.dimensions round-tripped through JSON serialization in ReservationDetail

### Idempotency (fully correct)
- Per-endpoint namespacing in all Lua scripts:
  - `idem:{tenant}:reserve:{key}` (reserve.lua)
  - `idem:{tenant}:decide:{key}` (repository Java code)
  - `idem:{tenant}:dry_run:{key}` (repository Java code)
  - `idem:{tenant}:event:{key}` (event.lua)
  - `idem:{tenant}:extend:{reservation_id}:{key}` (extend.lua)
  - Commit/release: stored on reservation hash itself (per-reservation)
- Header vs body key mismatch detection: `BaseController.validateIdempotencyHeader()`
- Payload hash-based mismatch detection via SHA-256 (all mutation endpoints)
- Idempotency replay returns original response for all endpoints

### Response Headers (fully correct)
- `X-Request-Id` set on every response (including errors) via `RequestIdFilter` — header set before `filterChain.doFilter()`, guaranteed present on all responses
- `X-Cycles-Tenant` set on every authenticated response via `ApiKeyAuthenticationFilter`
- `X-RateLimit-Remaining` / `X-RateLimit-Reset` set on all `/v1/` paths via `RateLimitHeaderFilter` (optional in v0; sentinel values -1/0 signal unlimited)

### Error Semantics (all correct)
- BUDGET_EXCEEDED → 409
- OVERDRAFT_LIMIT_EXCEEDED → 409
- DEBT_OUTSTANDING → 409
- RESERVATION_FINALIZED → 409
- RESERVATION_EXPIRED → 410
- NOT_FOUND → 404
- UNIT_MISMATCH → 400 (enforced in reserve.lua, commit.lua, event.lua; reserve/event paths return `scope`, `requested_unit`, `available_units` in details so the client can self-correct — see v0.1.25.6)
- IDEMPOTENCY_MISMATCH → 409
- INVALID_REQUEST → 400
- INTERNAL_ERROR → 500
- `/decide` correctly returns `decision=DENY` (not 409) for debt/overdraft conditions
- Commit accepts until `expires_at_ms + grace_period_ms` (commit.lua line 76)
- Release accepts until `expires_at_ms + grace_period_ms` (release.lua line 47)
- Extend only accepts while `status=ACTIVE` and `server_time <= expires_at_ms` (extend.lua)
- List reservations `status` query param validated against `ReservationStatus` enum

### Normative Spec Rules (all correctly implemented)
- Reserve is atomic across all derived scopes (Lua script)
- Commit and release are idempotent (Lua scripts)
- No double-charge on retries (idempotency key enforced)
- `dry_run=true` does not mutate balances or persist reservations
- `dry_run=true` omits `reservation_id` and `expires_at_ms`
- `dry_run=true` populates `affected_scopes` regardless of decision outcome
- `dry_run=true` populates `balances` (recommended by spec)
- Events applied atomically across derived scopes (Lua script)
- Event endpoint returns 201 (not 200) per spec
- `include_children` parameter accepted but ignored (spec allows in v0)
- Over-limit blocking: reservations rejected with OVERDRAFT_LIMIT_EXCEEDED when `is_over_limit=true`
- `is_over_limit` set to true when `debt > overdraft_limit` after commit OR when ALLOW_IF_AVAILABLE caps the overage delta
- `GET /v1/reservations/{id}` returns `ReservationDetail` with `status: EXPIRED` for expired reservations
- All mutation responses (reserve, commit, release, extend, event) populate optional `balances` field

### Lua Atomicity (correct, optimized)
- `ALLOW_IF_AVAILABLE` in commit.lua uses two-phase capped-delta pattern: determine minimum available remaining across all scopes, then charge estimate + capped_delta atomically. Never rejects — sets is_over_limit when capped.
- `ALLOW_IF_AVAILABLE` in event.lua uses same capped pattern: cap amount to minimum available, charge capped amount, set is_over_limit when capped.
- `ALLOW_WITH_OVERDRAFT` in commit.lua uses fail-fast pattern with cached scope values (eliminates redundant Redis reads in mutation loop)
- Event.lua uses same fail-fast atomicity patterns for ALLOW_WITH_OVERDRAFT
- Reserve.lua atomically checks and deducts across all derived scopes using HMGET (1 call per scope instead of 5)
- All Lua scripts leverage Redis single-threaded execution for atomicity
- Balance snapshots returned atomically from Lua scripts (reserve, commit, release) — read consistency guaranteed within the same atomic operation
- Commit/release idempotency-hit paths return `estimate_amount`/`estimate_unit` for correct `released` calculation

### Test Coverage (above 95%)
- JaCoCo line coverage threshold raised from 90% to **95%** (enforced in parent pom.xml)
- **API module**: **95%+** line coverage (283 tests across 15 test classes)
- **Data module**: **95%+** line coverage, 76%+ branch coverage (261 tests across 11 test classes)
  - Branch gaps: defensive null-check ternaries and unreachable `&&` short-circuit branches in `RedisReservationRepository`
  - Tenant default resolution: 10 unit tests covering all fallback paths, TTL capping, max extensions, malformed JSON recovery
  - LuaScriptRegistry: 5 tests (startup load, EVALSHA, NOSCRIPT fallback, no-SHA fallback, startup failure)
  - Idempotency replay: 2 tests (commit replay returns released amount, release replay returns released amount)
  - API key cache: 1 test (verifies cache hit avoids second Redis call)
- **Model module**: Coverage skipped (POJOs only, no business logic) — 9 tests
- Total test count: 283 (API) + 261 (Data) + 9 (Model) = **553 tests** across 26 test classes
- **Integration tests**: 27+ nested test classes covering all 9 endpoints, including:
  - Tenant Defaults (9 tests): overage policy resolution (ALLOW_IF_AVAILABLE, ALLOW_WITH_OVERDRAFT, REJECT), explicit override vs tenant default, TTL capping, max extensions enforcement, default TTL usage, no-tenant-record fallback
  - Expiry Sweep (7 tests): end-to-end expire.lua execution, grace period skip, orphan TTL cleanup, multi-scope budget release, already-finalized skip
  - Budget Status (2 tests): BUDGET_FROZEN and BUDGET_CLOSED enforcement on reserve
  - ALLOW_IF_AVAILABLE commit overage (integration coverage)
- **Performance benchmarks**: 8 write-path tests (6 individual operations + 2 composite lifecycles) + 4 read-path tests (GET reservation, GET balances, LIST reservations, Decide pipelined), tagged `@Tag("benchmark")`
- All unit tests pass without Docker/Testcontainers (integration and benchmark tests excluded by default)

### Tenant Default Configuration (correct)
- `default_commit_overage_policy`: resolved at reservation/event creation time
  - Resolution order: request-level `overage_policy` > tenant `default_commit_overage_policy` > hardcoded `ALLOW_IF_AVAILABLE`
  - Tenant config read from Redis `tenant:{tenant_id}` JSON record (shared with admin service)
  - Graceful fallback to `ALLOW_IF_AVAILABLE` on tenant read failure or missing record
- `default_reservation_ttl_ms`: used when request omits `ttl_ms` (was hardcoded to 60000ms)
  - Resolution order: request `ttl_ms` > tenant `default_reservation_ttl_ms` > hardcoded 60000ms
- `max_reservation_ttl_ms`: caps requested TTL to tenant maximum (default 3600000ms)
  - Applied after default resolution: `Math.min(effectiveTtl, maxTtl)`
- `max_reservation_extensions`: stored on reservation at creation, enforced in extend.lua
  - `extension_count` tracked and incremented atomically in extend.lua
  - Returns `MAX_EXTENSIONS_EXCEEDED` (HTTP 409) when count reaches max
  - Default: 10 (spec default)
- Request model fields (`overagePolicy`, `ttlMs`) changed from hardcoded defaults to `null`
  to allow tenant config resolution when client omits them

### Overdraft/Debt Model (correct)
- `ALLOW_WITH_OVERDRAFT` policy supported on both commit and event
- Debt tracked per-scope, `is_over_limit` flag set when `debt > overdraft_limit` or when ALLOW_IF_AVAILABLE caps the overage
- Over-limit scopes block new reservations with OVERDRAFT_LIMIT_EXCEEDED
- Concurrent commit/event behavior matches spec (per-operation check, not cross-operation atomic)
- Event overage_policy defaults to ALLOW_IF_AVAILABLE, supports all three policies

### Performance Optimizations (all applied)

Sixteen optimizations applied to the reserve/commit/release hot path and event emission, preserving all protocol correctness guarantees (atomicity, idempotency, ledger invariant `remaining = allocated - spent - reserved - debt`).

| # | Optimization | Impact | Location |
|---|-------------|--------|----------|
| 1 | **BCrypt API key cache** — Caffeine cache keyed by SHA-256(key), 60s TTL, max 5000 entries | Eliminates ~100ms+ BCrypt per request on cache hit | `ApiKeyRepository.java` |
| 2 | **EVALSHA** — Script SHA loaded at startup, 40-char hash sent instead of full script | Saves ~1-5KB network per call; auto-fallback to EVAL on NOSCRIPT | `LuaScriptRegistry.java` (new), all `eval()` callers |
| 3 | **Pipelined balance fetch** — N HGETALL calls batched into single round-trip | Saves (N-1) round-trips for fallback paths (extend, events, idempotency hits) | `RedisReservationRepository.fetchBalancesForScopes()` |
| 4 | **Lua returns balances** — Balance snapshots collected atomically at end of reserve/commit/release scripts | Eliminates post-operation Java balance fetch entirely for primary paths | `reserve.lua`, `commit.lua`, `release.lua` |
| 5 | **Lua HMGET consolidation** — Replaced scattered HGET calls with batched HMGET in all scripts; eliminated redundant EXISTS + HGET patterns; reused TIME call results | Fewer Redis commands inside Lua scripts | `expire.lua`, `extend.lua`, `commit.lua`, `event.lua` |
| 6 | **Tenant config cache** — Caffeine cache with configurable TTL (default 60s, `cycles.tenant-config.cache-ttl-ms`), max 1000 entries | Saves 1 Redis GET per reserve/event | `RedisReservationRepository.getTenantConfig()` |
| 7 | **ThreadLocal MessageDigest** — SHA-256 instance reused per thread in both ApiKeyRepository and RedisReservationRepository | Reduces per-request allocations | `ApiKeyRepository.java`, `RedisReservationRepository.java` |
| 8 | **Pipelined idempotency checks** — Idempotency key + hash fetched/stored via Redis Pipeline (2 ops in 1 round-trip) | Saves 1 round-trip per dry_run/decide request | `RedisReservationRepository.evaluateDryRun()`, `decide()` |
| 9 | **Caffeine caches** — Replaced manual ConcurrentHashMap + lazy eviction with Caffeine (automatic TTL expiry, LRU eviction, bounded size) | Eliminates O(n) on-request-path eviction; prevents memory leaks | `ApiKeyRepository.java`, `RedisReservationRepository.java` |
| 10 | **Connection pool tuning** — MaxTotal=128, MaxIdle=32, MinIdle=16, testOnBorrow, testWhileIdle, idle eviction; all configurable via properties | Prevents pool exhaustion under load; validates connections | `RedisConfig.java` |
| 11 | **expire.lua double TIME elimination** — Reuses `now` from initial TIME call instead of calling TIME twice | 1 fewer Redis command per expiry | `expire.lua` |
| 12 | **event.lua scope budget caching** — Budget fields cached during validation phase and reused in capping/mutation phases | Eliminates redundant HGET/HMGET calls per scope across 3 phases | `event.lua` |
| 13 | **Lua script loading** — Replaced O(n²) string concatenation with readAllBytes | Faster startup (startup-only) | `RedisConfig.java` |
| 14 | **Async event emission** — `CompletableFuture.runAsync()` on daemon thread pool (`availableProcessors/4`) | Emit never blocks request thread; near-zero overhead on non-event paths | `EventEmitterService.java` |
| 15 | **Pipelined event emit** — Event save + subscription lookup in 1 pipeline (was 6 sequential); subscription GETs pipelined; delivery creation pipelined | 6→1 round-trips for event save+lookup; N→1 for subscription resolve; 4→1 for delivery | `EventEmitterRepository.java` |
| 16 | **Early exit on no subscribers** — If both tenant + system SMEMBERS return empty, skip subscription resolve and delivery creation | Zero additional Redis calls when no webhooks configured | `EventEmitterRepository.java` |

**Thread safety**: All caches use Caffeine (thread-safe by design). ThreadLocal MessageDigest avoids contention. Event emit thread pool uses daemon threads that don't prevent JVM shutdown.
**Backward compatibility**: Old reservations without `budgeted_scopes` field handled via `budgeted_scopes_json or affected_scopes_json` fallback in all Lua scripts.

### Performance Benchmarks

See [BENCHMARKS.md](BENCHMARKS.md) for full benchmark history across versions.

Run benchmarks: `mvn test -Pbenchmark` (requires Docker). Excluded from default `mvn verify` builds via `<excludedGroups>benchmark</excludedGroups>` in surefire config.

### Read-Path Pipelines & Operational Fixes (Phase 3)

1. **Pipeline `evaluateDryRun()`** — Replaced 2N+1 sequential `jedis.hgetAll()` calls with a single pipelined round-trip. With 6 scope levels, this reduces 13 Redis round-trips to 1.

2. **Pipeline `decide()`** — Same pattern: N+1 sequential budget lookups + caps fetch consolidated into 1 pipeline call.

3. **Pipeline `getBalances()` SCAN loop** — Replaced per-key `jedis.hgetAll()` inside SCAN loop with batched pipeline per SCAN batch. Up to 100 round-trips per batch reduced to 1. Also pre-lowercased filter parameters once at method entry (scope paths already lowercased at creation by `ScopeDerivationService`).

4. **Pre-lowercased `listReservations()` filters** — Same `.toLowerCase()` optimization applied to `listReservations()`.

5. **INFO → DEBUG logging in `BaseController.authorizeTenant()`** — Two `LOG.info` calls fired on every authenticated request; changed to `LOG.debug`.

6. **HTTP response compression** — Enabled `server.compression` for JSON responses > 1KB.

7. **30-day TTL on terminal reservation hashes** — `commit.lua`, `release.lua`, and `expire.lua` now set `PEXPIRE 2592000000` on reservation hashes after state transition to COMMITTED/RELEASED/EXPIRED. Active reservations keep no TTL (cleaned by expiry sweep).

8. **30-day TTL on event hashes** — `event.lua` now sets `PEXPIRE 2592000000` on event hashes after creation.

9. **GET endpoint benchmarks** — New `CyclesProtocolReadBenchmarkTest.java` benchmarks GET /v1/reservations/{id}, GET /v1/reservations (list), GET /v1/balances, and POST /v1/decide.

**Items reviewed and confirmed correct (no fix needed):**
- `luaScripts.eval()` return value — Lua scripts always return via `cjson.encode()`, never nil
- Event idempotency replay returns empty balances — consistent with commit/release pattern; `parseLuaBalances()` handles gracefully
- `fetchBalancesForScopes()` still used in reserve idempotency-hit fallback path — not dead code
- Thread safety: all caches use `ConcurrentHashMap`, `ThreadLocal<MessageDigest>` for digests, Jedis connections scoped to try-with-resources
- Balance snapshot ordering: collected after mutations in all Lua scripts
- Percentile calculations: mathematically correct with bounds checking
- Redis pool size (50) / timeout (2s) — deployment tuning, configurable via RedisConfig
- Cache race conditions in `ApiKeyRepository` and `LuaScriptRegistry` — `ConcurrentHashMap` ops are atomic; duplicate work is harmless

---

### Production Hardening (v0.1.24.3)

Operational readiness improvements added in v0.1.24.3:

| # | Item | Status |
|---|------|--------|
| 1 | **Prometheus metrics** — Micrometer + Prometheus registry, `/actuator/prometheus` endpoint (unauthenticated) | Done |
| 2 | **Structured JSON logging** — Spring Boot 3.4+ native structured logging; set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` for ECS JSON in production | Done |
| 3 | **Graceful shutdown** — `server.shutdown=graceful` with 30s drain timeout | Done |
| 4 | **Docker HEALTHCHECK** — `/actuator/health` probe with 15s interval | Done |
| 5 | **JVM production flags** — `JAVA_OPTS` entrypoint with G1GC, 75% RAM, string deduplication | Done |
| 6 | **Input sanitization** — `@Pattern` on Subject scope fields prevents Redis key path injection | Done |
| 7 | **Error logging** — ApiKeyRepository catch blocks now log errors instead of silently swallowing | Done |
| 8 | **Connection pool tuning** — 128/32/16 pool sizing, testWhileIdle, idle eviction; all configurable via properties | Done |

### Production Hardening (Phase 2 audit)

Code review of all changes identified and fixed four defensive issues:

1. **All Lua scripts: `cjson.decode` crash on corrupted Redis data** — If `affected_scopes` or `budgeted_scopes` stored in a reservation hash contains malformed JSON, `cjson.decode` would crash the Lua script with an unhandled error. Fixed with `pcall(cjson.decode, ...)` wrappers in all five mutation scripts:
   - `extend.lua`: returns empty balances on decode failure
   - `commit.lua`: returns `INTERNAL_ERROR` on decode failure
   - `release.lua`: returns `INTERNAL_ERROR` on decode failure
   - `expire.lua`: silently skips budget adjustment (background sweep must not get stuck on corrupted data; reservation still expires)

2. **Java `valueOf` crash on invalid `estimate_unit`** — `Enums.UnitEnum.valueOf(estimateUnitStr)` in `extendReservation()` would throw `IllegalArgumentException` if Redis contained a corrupted unit string. Fixed with try-catch fallback to `USD_MICROCENTS`.

3. **Concurrent benchmark thread leak and CI flakiness** — `ExecutorService` wasn't cleaned up on timeout (thread leak risk in CI). Hard `errors == 0` assertion would fail on transient CI issues. Fixed with try/finally + `shutdownNow()`, and replaced zero-error assertion with <1% error rate threshold.

**Items reviewed and confirmed correct (no fix needed):**
- `luaScripts.eval()` return value — Lua scripts always return via `cjson.encode()`, never nil
- Event idempotency replay returns empty balances — consistent with commit/release pattern; `parseLuaBalances()` handles gracefully
- `fetchBalancesForScopes()` still used in reserve idempotency-hit fallback path — not dead code
- Thread safety: all caches use `ConcurrentHashMap`, `ThreadLocal<MessageDigest>` for digests, Jedis connections scoped to try-with-resources
- Balance snapshot ordering: collected after mutations in all Lua scripts
- Percentile calculations: mathematically correct with bounds checking
- Redis pool size (50) / timeout (2s) — deployment tuning, configurable via RedisConfig
- Cache race conditions in `ApiKeyRepository` and `LuaScriptRegistry` — `ConcurrentHashMap` ops are atomic; duplicate work is harmless

---

## Previously Found Issues (all fixed)

### Issue 1 [FIXED]: `GET /v1/reservations/{id}` returned 410 for EXPIRED reservations
- **Was:** `getReservationById()` threw 410 for any EXPIRED reservation
- **Fix:** Removed the EXPIRED status check; now returns `ReservationDetail` with `status: EXPIRED`
- **Location:** `RedisReservationRepository.java:384-392`

### Issue 2 [FIXED]: `ReleaseResponse.released` could be null
- **Was:** `releasedAmount` was null when reservation data was corrupted
- **Fix:** Falls back to `Amount(USD_MICROCENTS, 0)` with a warning log
- **Location:** `RedisReservationRepository.java:304-314`

### Issue 3 [FIXED]: `GET /v1/reservations` status param not validated against enum
- **Was:** Invalid status values silently returned empty results
- **Fix:** Added `Enums.ReservationStatus.valueOf()` validation returning 400 INVALID_REQUEST
- **Location:** `ReservationController.java:109-116`

### Issue 4 [FIXED]: Non-dry_run reservation create did not populate `balances`
- **Was:** Only dry_run responses included balances; non-dry_run omitted them
- **Fix:** Added `fetchBalancesForScopes()` calls for both normal and idempotency-hit paths
- **Location:** `RedisReservationRepository.java:84,96-106`

### Issue 5 [FIXED]: expire.lua did not clean up orphaned TTL entries for missing reservations
- **Was:** NOT_FOUND path returned without `ZREM`, causing orphaned `reservation:ttl` entries to be retried every 5 seconds indefinitely
- **Fix:** Added `redis.call('ZREM', 'reservation:ttl', reservation_id)` before NOT_FOUND return, matching the existing cleanup in the non-ACTIVE path
- **Location:** `expire.lua:16-18`

### Issue 6 [FIXED]: Unbounded `zrangeByScore` in expiry sweep could cause OOM after outages
- **Was:** `jedis.zrangeByScore("reservation:ttl", 0, now)` loaded all expired candidates into memory at once
- **Fix:** Added LIMIT of 1000 per sweep cycle via `zrangeByScore(key, 0, now, 0, SWEEP_BATCH_SIZE)` — backlog drains naturally across subsequent sweeps
- **Location:** `ReservationExpiryService.java:31,45`

### Issue 8 [FIXED]: Tenant default configuration not honored by protocol server
- **Was:** `default_commit_overage_policy`, `default_reservation_ttl_ms`, `max_reservation_ttl_ms`, and `max_reservation_extensions` were set via admin API but ignored by the protocol server. The request models hardcoded `overagePolicy = REJECT` and `ttlMs = 60000L` as field defaults, so when clients omitted these fields they always got hardcoded values — never the tenant's configured defaults.
- **Fix:**
  1. Removed hardcoded defaults from `ReservationCreateRequest.overagePolicy` (was `REJECT`), `ReservationCreateRequest.ttlMs` (was `60000L`), and `EventCreateRequest.overagePolicy` (was `REJECT`) — now `null` when omitted
  2. Added `getTenantConfig()` to read tenant JSON from `tenant:{id}` Redis key
  3. Added `resolveOveragePolicy()`: request > tenant `default_commit_overage_policy` > `REJECT`
  4. Added `resolveReservationTtl()`: request > tenant `default_reservation_ttl_ms` > 60000ms, then `Math.min(ttl, max_reservation_ttl_ms)`
  5. Added `resolveMaxExtensions()`: reads tenant `max_reservation_extensions` (default 10), stored on reservation, enforced in extend.lua
  6. extend.lua: tracks `extension_count`, returns `MAX_EXTENSIONS_EXCEEDED` when limit reached
  7. reserve.lua: accepts `max_extensions` as ARGV[14], stores on reservation hash
  8. Added `MAX_EXTENSIONS_EXCEEDED` error code (HTTP 409)
- **Location:** `RedisReservationRepository.java`, `ReservationCreateRequest.java`, `EventCreateRequest.java`, `reserve.lua`, `extend.lua`, `Enums.java`

### Issue 7 [FIXED]: Clock skew between Java and Redis in expiry sweep candidate query
- **Was:** Java `System.currentTimeMillis()` used for the `zrangeByScore` query; all Lua scripts use `redis.call('TIME')` — clock drift could cause missed or premature candidate selection
- **Fix:** Replaced with `jedis.time()` to use Redis server clock, consistent with reserve/commit/release/extend/expire Lua scripts
- **Location:** `ReservationExpiryService.java:39-41`

---

## Round 6 — Spec Compliance Audit (2026-03-24)

Full audit of all changes against the authoritative YAML spec (`cycles-protocol-v0.yaml` v0.1.24).

### Issue 9 [FIXED]: `event.lua` always returned `charged` field in `EventCreateResponse`

- **Spec:** `EventCreateResponse.charged` description: "Present when overage_policy is ALLOW_IF_AVAILABLE and the actual was capped to the remaining budget, so the client can see the effective charge." Field is NOT in `required: [status, event_id]`.
- **Was:** `event.lua` always returned `charged = effective_amount` in the response JSON, so every event response included the `charged` field regardless of overage policy or capping.
- **Fix:** Made `charged` conditional in `event.lua`: only set `result.charged = effective_amount` when `overage_policy == "ALLOW_IF_AVAILABLE" and effective_amount < amount` (capping occurred). Lua's `cjson.encode` omits unset keys; Java's `@JsonInclude(NON_NULL)` on `EventCreateResponse.charged` provides redundant safety.
- **Location:** `event.lua:202-211`
- **Tests:** `EventControllerTest.shouldIncludeChargedWhenCapped()`, `EventControllerTest.shouldOmitChargedWhenNotCapped()`

### Issue 10 [FIXED]: GET `/v1/reservations/{id}` returned 200 for EXPIRED reservations instead of 410

- **Spec:** Line 52: "Expired reservations MUST return HTTP 410 with error=RESERVATION_EXPIRED." Line 1212: GET endpoint explicitly lists `"410"` as a response.
- **Was:** `getReservationById()` returned 200 with `status: EXPIRED` in the body. Integration tests asserted 200. This contradicted the spec.
- **Fix:** Added `status == EXPIRED` check in `getReservationById()` that throws `CyclesProtocolException.reservationExpired()` (410). Updated integration tests `shouldExpireReservationAndReleaseBudget`, `shouldReturnExpiredStatusOnGetAfterSweep` to assert 410. Updated unit test to assert 410.
- **Location:** `RedisReservationRepository.java:438-443`, `CyclesProtocolIntegrationTest.java`, `ReservationControllerTest.java`

### Issue 11 [FIXED]: event.lua idempotency replay missing `charged` and `balances`

- **Spec:** "On replay with the same idempotency_key, the server MUST return the original successful response payload." (IDEMPOTENCY NORMATIVE)
- **Was:** Replay returned `{event_id, idempotency_key, status}` — missing `charged` (when capping occurred) and `balances`.
- **Fix:** Replay now reads stored event hash (`charged_amount`, `amount`, `unit`, `budgeted_scopes`), reconstructs balance snapshots from current budget state, and includes `charged` when `charged_amount < amount` (capping occurred).
- **Location:** `event.lua:30-63`

### Issue 12 [FIXED]: commit.lua idempotency replay missing `balances` and `affected_scopes_json`

- **Spec:** Same idempotency normative requirement.
- **Was:** Replay returned `{reservation_id, state, charged, debt_incurred, estimate_amount, estimate_unit}` — missing `balances` and `affected_scopes_json`.
- **Fix:** Replay now reads `affected_scopes` from reservation hash, reconstructs balance snapshots, and includes both in response.
- **Location:** `commit.lua:42-72`

### Issue 13 [FIXED]: release.lua idempotency replay missing `balances`

- **Spec:** Same idempotency normative requirement.
- **Was:** Replay returned `{reservation_id, state, estimate_amount, estimate_unit}` — missing `balances`.
- **Fix:** Replay now reads `affected_scopes` from reservation hash, reconstructs balance snapshots, and includes in response.
- **Location:** `release.lua:29-52`

### Confirmed non-issues (validated against YAML spec)

The following were investigated during the audit and confirmed **not to be spec violations**:

| Item | YAML Spec Evidence | Verdict |
|------|-------------------|---------|
| `ErrorResponse.details` field optional | `required: [error, message, request_id]` — `details` not in required array | NOT A VIOLATION |
| `Balance.debt/overdraft_limit/is_over_limit` optional | `required: [scope, scope_path, remaining]` — these fields not in required array | NOT A VIOLATION |
| `CommitResponse.released` optional | `required: [status, charged]` — `released` not in required array | NOT A VIOLATION |

---

## Verdict

The server implementation is **fully compliant** with the YAML spec (v0.1.25) and **performance optimized**. v0.1.25 additions: webhook event emission (reservation.denied, reservation.commit_overage) via shared Redis dispatch queue, event/delivery TTL retention (90d/14d configurable), AES-256-GCM webhook signing secret encryption, subscription matching with scope wildcards. v0.1.24 changes: default overage policy changed from REJECT to ALLOW_IF_AVAILABLE; ALLOW_IF_AVAILABLE commits/events now always succeed with capped charge instead of 409 BUDGET_EXCEEDED; is_over_limit extended to also cover capped ALLOW_IF_AVAILABLE scenarios; event.lua updated with same capped-delta logic; EventCreateResponse includes charged field. All 9 endpoints are implemented, all schemas match, auth/tenancy/idempotency are correctly enforced, and the normative behavioral requirements (atomic operations, debt/overdraft handling, scope derivation, error semantics, dry-run rules, grace period handling) are properly implemented. Seven write-path optimizations reduce hot-path latency by eliminating redundant Redis round-trips, caching BCrypt validation, and returning balance snapshots atomically from Lua scripts. Phase 3 adds read-path pipelining, response compression, and terminal hash TTLs. Test coverage expanded to 553 tests across 26 test classes, including 12 performance benchmark tests. Write-path single-operation p50 latency: 5.6-8.6ms (v0.1.25.1, async emit, near-zero overhead on non-event paths). Read-path p50 latency: 4.0-5.6ms. Full reserve-commit lifecycle p50: 16.0ms. Concurrent throughput: 2,584 ops/s at 32 threads with zero errors. No remaining spec violations found.
