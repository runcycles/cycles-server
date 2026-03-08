# Cycles Protocol v0.1.23 — Server Implementation Audit

**Date:** 2026-03-08
**Spec:** `cycles-protocol-v0.yaml` (OpenAPI 3.1.0, v0.1.23)
**Server:** Spring Boot 3.5.11 / Java 21 / Redis (Lua scripts)

---

## Summary

| Category | Pass | Issues |
|----------|------|--------|
| Endpoints & Routes | 9/9 | 0 |
| Request Schemas | 6/6 | 0 |
| Response Schemas | 8/8 | 1 |
| HTTP Status Codes | — | 1 |
| Response Headers | — | 0 |
| Error Handling | — | 0 |
| Auth & Tenancy | — | 0 |
| Idempotency | — | 0 |
| Scope Derivation | — | 0 |
| Normative Requirements | — | 2 |

**Total real issues: 4** (1 medium, 2 low, 1 informational)

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
- `POST /v1/events` — EventController

### Request/Response Schemas (all match)
- All 6 request schemas match spec (fields, types, constraints, defaults)
- All 8 response schemas match spec (fields, required markers, types)
- All 12 error codes match spec enum exactly
- All 4 status enums match spec
- `Subject` anyOf validation correctly enforced via `hasAtLeastOneStandardField()`
- `additionalProperties: false` correctly enforced via `@JsonIgnoreProperties(ignoreUnknown = false)`
- `Balance` schema includes all debt/overdraft fields (debt, overdraft_limit, is_over_limit)

### Auth & Tenancy (fully correct)
- X-Cycles-API-Key authentication via `ApiKeyAuthenticationFilter`
- Tenant validation via `BaseController.authorizeTenant()` — returns 403 on mismatch
- Reservation ownership enforced on all mutation/read endpoints
- Balance visibility correctly scoped to effective tenant
- List reservations correctly scoped to effective tenant

### Scope Derivation (fully correct)
- Canonical ordering: tenant → workspace → app → workflow → agent → toolset
- Gap-filling with "default" for intermediate levels
- Scope paths lowercased for stable canonicalization

### Idempotency (fully correct)
- Per-endpoint namespacing in all Lua scripts:
  - `idem:{tenant}:reserve:{key}` (reserve.lua)
  - `idem:{tenant}:decide:{key}` (repository Java code)
  - `idem:{tenant}:dry_run:{key}` (repository Java code)
  - `idem:{tenant}:event:{key}` (event.lua)
  - `idem:{tenant}:extend:{reservation_id}:{key}` (extend.lua)
  - Commit/release: stored on reservation hash itself (per-reservation)
- Header vs body key mismatch detection: `BaseController.validateIdempotencyHeader()`
- Payload hash-based mismatch detection via SHA-256
- Idempotency replay returns original response for all endpoints

### Response Headers (correct)
- `X-Request-Id` set on every response via `RequestIdFilter`
- `X-Cycles-Tenant` set on every authenticated response via `RequestIdFilter`
- `X-RateLimit-Remaining` / `X-RateLimit-Reset` set on `/v1/decide` (optional in v0)

### Error Semantics (all correct)
- BUDGET_EXCEEDED → 409
- OVERDRAFT_LIMIT_EXCEEDED → 409
- DEBT_OUTSTANDING → 409
- RESERVATION_FINALIZED → 409
- RESERVATION_EXPIRED → 410
- NOT_FOUND → 404
- UNIT_MISMATCH → 400
- IDEMPOTENCY_MISMATCH → 409
- INVALID_REQUEST → 400
- INTERNAL_ERROR → 500
- `/decide` correctly returns `decision=DENY` (not 409) for debt/overdraft conditions
- Commit accepts until `expires_at_ms + grace_period_ms`
- Extend only accepts while `status=ACTIVE` and not expired

### Normative Spec Rules (correctly implemented)
- Reserve is atomic across all derived scopes (Lua script)
- Commit and release are idempotent (Lua scripts)
- No double-charge on retries (idempotency key enforced)
- `dry_run=true` does not mutate balances or persist reservations
- `dry_run=true` omits `reservation_id` and `expires_at_ms`
- Events applied atomically across derived scopes (Lua script)
- Event endpoint returns 201 (not 200) per spec
- `include_children` parameter accepted but ignored (spec allows in v0)
- Over-limit blocking: reservations rejected with OVERDRAFT_LIMIT_EXCEEDED when `is_over_limit=true`
- `is_over_limit` set to true when `debt > overdraft_limit` after commit

---

## ISSUES FOUND

### Issue 1 [MEDIUM]: `GET /v1/reservations/{reservation_id}` always returns 410 for EXPIRED reservations

**Spec says:** `ReservationDetail` includes `status` with enum `[ACTIVE, COMMITTED, RELEASED, EXPIRED]`, implying expired reservations should be retrievable with `status: EXPIRED`. The spec also lists 410 as a valid error response.

**Server does:** `RedisReservationRepository.getReservationById()` (line 382-384) throws `CyclesProtocolException.reservationExpired()` (HTTP 410) for ANY reservation with status EXPIRED, making it impossible to retrieve expired reservation details.

**Impact:** Operators cannot inspect expired reservations for debugging. The 410 is technically valid per the spec's error response list, but returning the `ReservationDetail` with `status: EXPIRED` would better serve the spec's stated purpose ("useful for debugging and monitoring").

**Location:** `RedisReservationRepository.java:382-384`

---

### Issue 2 [LOW]: `ReleaseResponse.released` can be null despite spec marking it required

**Spec says:** `ReleaseResponse` has `required: [status, released]`.

**Server does:** In `RedisReservationRepository.releaseReservation()` (lines 298-304), `releasedAmount` is null if `estimateAmountStr` or `estimateUnitStr` is null (data corruption). Since `@JsonInclude(NON_NULL)` is used, null values are silently omitted rather than failing validation — violating the spec's `required` constraint.

**Impact:** Only occurs under data corruption. The `@JsonInclude(NON_NULL)` + `@NotNull` combination creates a silent violation rather than a hard error.

**Location:** `ReleaseResponse.java:15`, `RedisReservationRepository.java:298-304`

---

### Issue 3 [LOW]: `GET /v1/reservations` status query parameter not validated against enum

**Spec says:** The `status` parameter should be a `ReservationStatus` enum value.

**Server does:** `ReservationController.list()` accepts `status` as a raw `String` and passes it directly to the repository for string comparison against the Redis `state` field (line 420). Invalid values like `status=INVALID` don't produce an error — they simply return zero results.

**Impact:** Minor UX issue. Clients passing invalid status values get an empty list instead of a 400 error.

**Location:** `ReservationController.java:100`, `RedisReservationRepository.java:420`

---

### Issue 4 [INFO]: Non-dry_run reservation create does not populate optional `balances` field

**Spec says:** `ReservationCreateResponse` includes optional `balances` (array of Balance).

**Server does:** `dry_run=true` populates balances; `dry_run=false` does not. All other mutation endpoints (commit, release, extend, events) populate balances.

**Impact:** Not a spec violation (balances is optional), but inconsistent with other endpoints. Clients expecting operator visibility on reserve responses won't get balance snapshots.

**Location:** `RedisReservationRepository.java:94-101`

---

## Consolidated Issue Summary

| # | Severity | Issue | Spec Violation? |
|---|----------|-------|-----------------|
| 1 | Medium | GET reservation returns 410 for EXPIRED instead of data with status=EXPIRED | Ambiguous — spec lists both 200 and 410 |
| 2 | Low | ReleaseResponse.released can be null despite being spec-required | Yes (edge case only) |
| 3 | Low | Status query param on list reservations not validated against enum | Minor |
| 4 | Info | Non-dry_run create reservation omits optional balances | No (optional field) |

---

## Verdict

The server implementation is **well-aligned** with the YAML spec. All 9 endpoints are implemented, all schemas match, auth/tenancy/idempotency are correctly enforced, and the normative behavioral requirements (atomic operations, debt/overdraft handling, scope derivation, error semantics) are properly implemented.

The 4 issues found are minor: 1 ambiguous behavior, 1 edge-case data corruption path, 1 missing input validation, and 1 optional field inconsistency. None represent critical spec violations or security concerns.
