# Cycles Protocol v0.1.23 — Server Implementation Audit

**Date:** 2026-03-15
**Spec:** `cycles-protocol-v0.yaml` (OpenAPI 3.1.0, v0.1.23)
**Server:** Spring Boot 3.5.11 / Java 21 / Redis (Lua scripts)

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

**All previously identified issues have been fixed. No remaining spec violations found.**

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
- Overdraft/debt model (ALLOW_WITH_OVERDRAFT, is_over_limit, DEBT_OUTSTANDING)
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
- UNIT_MISMATCH → 400 (enforced in commit.lua and event.lua)
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
- `is_over_limit` set to true when `debt > overdraft_limit` after commit
- `GET /v1/reservations/{id}` returns `ReservationDetail` with `status: EXPIRED` for expired reservations
- All mutation responses (reserve, commit, release, extend, event) populate optional `balances` field

### Lua Atomicity (correct)
- `ALLOW_IF_AVAILABLE` in commit.lua uses fail-fast pattern (all checks before mutations)
- `ALLOW_WITH_OVERDRAFT` in commit.lua uses fail-fast pattern across all scopes
- Event.lua uses same fail-fast atomicity patterns
- Reserve.lua atomically checks and deducts across all derived scopes
- All Lua scripts leverage Redis single-threaded execution for atomicity

### Test Coverage (above 98%)
- JaCoCo line coverage threshold raised from 90% to **95%** (enforced in parent pom.xml)
- **API module**: 209/209 lines covered — **100%** line coverage (91 unit tests)
- **Data module**: 766/780 lines covered — **98.2%** line coverage (14 uncovered lines are defensive catch blocks in scan/parse error paths)
- **Model module**: Coverage skipped (POJOs only, no business logic)
- Total unit test count: 91 (API) + 68 (Data) + 5 (Model) = **164 unit tests**
- All tests pass without Docker/Testcontainers (integration tests excluded by default)

### Overdraft/Debt Model (correct)
- `ALLOW_WITH_OVERDRAFT` policy supported on both commit and event
- Debt tracked per-scope, `is_over_limit` flag set when `debt > overdraft_limit`
- Over-limit scopes block new reservations with OVERDRAFT_LIMIT_EXCEEDED
- Concurrent commit/event behavior matches spec (per-operation check, not cross-operation atomic)
- Event overage_policy defaults to REJECT, supports all three policies

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

---

## Verdict

The server implementation is **fully compliant** with the YAML spec (v0.1.23). All 9 endpoints are implemented, all schemas match, auth/tenancy/idempotency are correctly enforced, and the normative behavioral requirements (atomic operations, debt/overdraft handling, scope derivation, error semantics, dry-run rules, grace period handling) are properly implemented. No remaining spec violations found.
