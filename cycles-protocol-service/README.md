# Cycles Protocol Server v0.1.24

Reference implementation of the [Cycles Budget Authority API](../cycles-protocol-v0.yaml) — a reservation-based budget control service for AI agents and workflows. All atomic budget operations are executed via Redis Lua scripts; no external database is required.

## Contents

- [Architecture](#architecture)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Core Concepts](#core-concepts)
- [Authentication & Authorization](#authentication--authorization)
- [API Reference](#api-reference)
- [Error Codes](#error-codes)
- [Development](#development)
- [Redis Data Model](#redis-data-model)

---

## Architecture

```
HTTP client
    │  X-Cycles-API-Key
    ▼
Spring Boot (port 7878)
    │  ApiKeyAuthenticationFilter
    │  Controllers → Repository
    ▼
Redis 7+  (Lua scripts for atomicity)
```

**Modules:**
- `cycles-protocol-service-model` — shared request/response POJOs
- `cycles-protocol-service-data` — Redis repository + Lua scripts
- `cycles-protocol-service-api` — Spring Boot controllers + auth

---

## Quick Start

### Docker (recommended)

No local Java or Maven required. From the repository root:

```bash
docker compose up --build
```

Or pull the pre-built image (no source code needed):

```bash
docker compose -f docker-compose.prod.yml up
```

The server starts on **port 7878**. Interactive API docs: http://localhost:7878/swagger-ui.html

### Manual build

**Prerequisites:** Java 21+, Maven, Docker (for Redis)

```bash
# 1. Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# 2. Build
./build-all.sh

# 3. Seed a sample budget
./init-budgets.sh

# 4. Run
REDIS_HOST=localhost REDIS_PORT=6379 \
  java -jar cycles-protocol-service-api/target/cycles-protocol-service-api-0.1.24.jar
```

The server starts on **port 7878**. Interactive API docs: http://localhost:7878/swagger-ui.html

---

## Configuration

All settings are via environment variables (with defaults):

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | *(empty)* | Redis password (omit for no auth) |

---

## Core Concepts

### Subject

A subject identifies who is spending budget. Fields form a **scope hierarchy**:

```
tenant → workspace → app → workflow → agent → toolset
```

At least one standard field is required (`tenant` is typical); a subject containing only `dimensions` is invalid (`400 INVALID_REQUEST`). Only explicitly provided levels are included in the scope path — intermediate gaps are skipped, not filled with "default". A reservation is enforced at **every derived scope that has a budget** — at least one scope must have a budget defined.

```json
{
  "tenant": "acme",
  "workspace": "prod",
  "agent": "summarizer-v2"
}
```

Derived scopes (checked on every operation; scopes without budgets are skipped):
```
tenant:acme
tenant:acme/workspace:prod
tenant:acme/workspace:prod/agent:summarizer-v2
```

An optional `dimensions` map is accepted for custom taxonomies (e.g. `cost_center`, `department`). v0 servers accept and round-trip `dimensions` but MAY ignore it for budgeting decisions.

### Units

| Value | Description |
|---|---|
| `TOKENS` | LLM token counts |
| `USD_MICROCENTS` | Cost in USD × 10⁻⁸ (1 USD = 10⁸ USD_MICROCENTS) |
| `CREDITS` | Generic credit units |
| `RISK_POINTS` | Risk scoring units |

All amounts are non-negative integers (int64). A single reservation lifecycle uses exactly one unit; mixing units at commit time returns `UNIT_MISMATCH`.

### Reservation Lifecycle

```
POST /v1/reservations  →  ACTIVE
        │
        ├── POST …/commit  →  COMMITTED  (spend recorded)
        ├── POST …/release →  RELEASED   (budget returned)
        └── POST …/extend  →  ACTIVE     (TTL extended)

Beyond expires_at_ms + grace_period_ms: commit/release blocked (410)
Beyond expires_at_ms:                  extend blocked (410)
```

### Overage Policies

`overage_policy` is set **at reservation time** and controls commit behaviour when `actual > reserved`:

| Policy | Behaviour |
|---|---|
| `REJECT` *(default)* | Commit fails with `409 BUDGET_EXCEEDED` if actual exceeds reserved |
| `ALLOW_IF_AVAILABLE` | Delta is atomically charged from remaining budget; fails with `409 BUDGET_EXCEEDED` if insufficient |
| `ALLOW_WITH_OVERDRAFT` | Delta creates debt up to `overdraft_limit`; fails with `409 OVERDRAFT_LIMIT_EXCEEDED` if `debt + delta > overdraft_limit` |

The same three policies apply to `/v1/events` for direct debits.

When `overage_policy` is omitted from the request, the server resolves it from the tenant's `default_commit_overage_policy` (set via the Admin API). If the tenant has no default configured, `REJECT` is used.

### Debt and Overdraft

- If `overdraft_limit` is absent or `0`, no overdraft is permitted (`ALLOW_WITH_OVERDRAFT` behaves as `ALLOW_IF_AVAILABLE`).
- **`debt`** is created when `overage_policy=ALLOW_WITH_OVERDRAFT` and the commit delta exceeds remaining budget.
- When `debt > 0` and no `overdraft_limit` is configured (absent or `0`), **new reservations** against that scope are blocked with `409 DEBT_OUTSTANDING`. When an `overdraft_limit > 0` is set, debt within the limit does **not** block new reservations.
- When `debt > overdraft_limit`, the scope enters **over-limit** state (`is_over_limit=true`). New reservations are then blocked with `409 OVERDRAFT_LIMIT_EXCEEDED`.
- Debt does **not** block direct events (`/v1/events`); events always apply their own `overage_policy`.
- Existing active reservations MAY be committed or released normally while a scope is over-limit.
- Overdraft limit checks are per-commit and are **not** atomic across concurrent commits — concurrent commits may independently pass the check, causing total debt to temporarily exceed `overdraft_limit`. This is by design; the `is_over_limit` flag prevents further damage.
- Debt is repaid via external budget funding operations (out of scope for this API).

### Idempotency

Every mutating request requires an `idempotency_key` body field and accepts an optional `X-Idempotency-Key` header. If both are present they MUST match; a mismatch returns `409 IDEMPOTENCY_MISMATCH`.

On replay of a successful request with the same key, the server returns the original response — including any server-generated identifiers such as `reservation_id`.

| Operation | Idempotency TTL |
|---|---|
| Reserve | reservation TTL + grace period |
| Commit | stored on reservation hash indefinitely |
| Release | stored on reservation hash indefinitely |
| Extend | remaining reservation lifetime after extension |
| Decide | 24 hours |
| Event | 7 days |

---

## Authentication & Authorization

Every request requires an API key in the header:

```
X-Cycles-API-Key: <key>
```

### API Key Validation

The `ApiKeyAuthenticationFilter` runs on every non-public request and performs a multi-step validation:

1. **Header check** — `X-Cycles-API-Key` must be present; `401 UNAUTHORIZED` if missing.
2. **Prefix lookup** — the key prefix is extracted and looked up in `apikey:lookup:{prefix}` for O(1) resolution to a `keyId`.
3. **Key load** — the full key object is read from `apikey:{keyId}`.
4. **Status check** — key must be `ACTIVE`; `REVOKED` or `EXPIRED` keys return `401`.
5. **Expiration check** — if `expires_at` is set and in the past, returns `401 KEY_EXPIRED`.
6. **Hash verification** — the submitted secret is verified against the stored bcrypt hash; `401 INVALID_KEY` on mismatch.
7. **Tenant association** — the key must have a non-blank `tenant_id`; `401 KEY_NOT_OWNED_BY_TENANT` otherwise.
8. **Tenant status** — the tenant record (`tenant:{tenantId}`) is checked; `SUSPENDED` or `CLOSED` tenants return `401`.

On success the filter stores an `ApiKeyAuthentication` in the Spring `SecurityContext` containing the resolved `tenant_id` and any `permissions`.

### Tenant Authorization

Controllers enforce tenant isolation via `BaseController.authorizeTenant()`:

- If the request includes a `tenant` (in the body, query, or resolved from a resource ID), it **must match** the tenant from the API key.
- A mismatch returns `403 FORBIDDEN`.
- The effective tenant always comes from the API key — the client cannot escalate by supplying a different tenant in the request.

For operations by resource ID (e.g. `GET /v1/reservations/{id}`), the server first looks up the owning tenant from the reservation hash, then runs the same authorization check. This prevents cross-tenant access even when a reservation UUID is known.

### Filter Chain Order

| Order | Filter | Responsibility |
|---|---|---|
| 1 | `RequestIdFilter` | Generates `X-Request-Id` UUID, stored as request attribute and response header |
| 2 | `RateLimitHeaderFilter` | Adds `X-RateLimit-Remaining` / `X-RateLimit-Reset` headers (sentinel values in v0) |
| 3 | `ApiKeyAuthenticationFilter` | Validates key, populates `SecurityContext`, sets `X-Cycles-Tenant` header |

Public paths (Swagger UI, actuator health, etc.) bypass authentication.

All responses include an `X-Request-Id` header. All timestamps are Unix milliseconds (int64).

---

## API Reference

### POST /v1/decide

Evaluate whether a budget operation would be allowed **without** reserving. Returns `ALLOW`, `ALLOW_WITH_CAPS`, or `DENY`. Never returns `409` — debt and over-limit states surface as `DENY` with a `reason_code`.

**Request**
```json
{
  "idempotency_key": "decide-abc-123",
  "subject": { "tenant": "acme", "agent": "summarizer-v2" },
  "action":  { "kind": "llm.completion", "name": "openai:gpt-4o" },
  "estimate": { "unit": "TOKENS", "amount": 5000 },
  "metadata": {}
}
```

**Response** `200 OK`
```json
{
  "decision": "ALLOW",
  "affected_scopes": ["tenant:acme", "tenant:acme/.../agent:summarizer-v2"]
}
```

Only `decision` is required. `affected_scopes`, `reason_code`, `retry_after_ms`, and `caps` are optional.

On `DENY` (insufficient budget, debt, or over-limit). Possible `reason_code` values: `NOT_FOUND`, `BUDGET_EXCEEDED`, `OVERDRAFT_LIMIT_EXCEEDED`, `DEBT_OUTSTANDING`:
```json
{
  "decision": "DENY",
  "reason_code": "BUDGET_EXCEEDED",
  "affected_scopes": ["tenant:acme", "..."]
}
```

On `ALLOW_WITH_CAPS` (budget exists but soft constraints apply):
```json
{
  "decision": "ALLOW_WITH_CAPS",
  "caps": { "max_tokens": 8192 },
  "affected_scopes": ["tenant:acme", "..."]
}
```

---

### POST /v1/reservations

Reserve budget before executing an action. Returns `200 OK`.

**Request**
```json
{
  "idempotency_key": "req-abc-123",
  "subject": { "tenant": "acme", "agent": "summarizer-v2" },
  "action":  { "kind": "llm.completion", "name": "openai:gpt-4o" },
  "estimate": { "unit": "TOKENS", "amount": 5000 },
  "ttl_ms": 60000,
  "grace_period_ms": 5000,
  "overage_policy": "ALLOW_WITH_OVERDRAFT",
  "dry_run": false,
  "metadata": {}
}
```

| Field | Required | Default | Constraints |
|---|---|---|---|
| `idempotency_key` | yes | — | 1–256 chars |
| `subject` | yes | — | at least one standard field |
| `action.kind` | yes | — | max 64 chars |
| `action.name` | yes | — | max 256 chars |
| `action.tags` | no | — | max 10 items, each max 64 chars |
| `estimate.unit` | yes | — | see Units |
| `estimate.amount` | yes | — | ≥ 0 |
| `ttl_ms` | no | tenant `default_reservation_ttl_ms` or `60000` | 1000–86400000 ms; capped to tenant `max_reservation_ttl_ms` |
| `grace_period_ms` | no | `5000` | 0–60000 ms |
| `overage_policy` | no | tenant `default_commit_overage_policy` or `REJECT` | see Overage Policies |
| `dry_run` | no | `false` | evaluates without persisting if true |

**Response** `200 OK`
```json
{
  "decision": "ALLOW",
  "reservation_id": "550e8400-e29b-41d4-a716-446655440000",
  "affected_scopes": ["tenant:acme", "tenant:acme/.../agent:summarizer-v2"],
  "expires_at_ms": 1700000060000,
  "scope_path": "tenant:acme/.../agent:summarizer-v2",
  "reserved": { "unit": "TOKENS", "amount": 5000 }
}
```

`decision` and `affected_scopes` are always present. Optional fields: `caps` (only when `ALLOW_WITH_CAPS`), `reason_code`, `retry_after_ms`, `balances`.

On `ALLOW_WITH_CAPS` (budget exists but soft constraints apply):
```json
{
  "decision": "ALLOW_WITH_CAPS",
  "reservation_id": "550e8400-e29b-41d4-a716-446655440000",
  "affected_scopes": ["tenant:acme", "tenant:acme/.../agent:summarizer-v2"],
  "expires_at_ms": 1700000060000,
  "scope_path": "tenant:acme/.../agent:summarizer-v2",
  "reserved": { "unit": "TOKENS", "amount": 5000 },
  "caps": { "max_tokens": 8192 }
}
```

**`dry_run=true` response rules:**
- `reservation_id` and `expires_at_ms` MUST be absent (no reservation is created)
- `affected_scopes` MUST be populated regardless of decision
- `caps` MUST be present only when `decision=ALLOW_WITH_CAPS`
- `reason_code` SHOULD be populated when `decision=DENY`
- `balances` MAY be included

On budget denial (insufficient budget, over-limit, outstanding debt), the server returns `409` — `decision=DENY` never appears in a non-dry-run successful response.

---

### POST /v1/reservations/{reservation_id}/commit

Record actual spend. Must be called before `expires_at_ms + grace_period_ms`. Returns `200 OK`.

**Request**
```json
{
  "idempotency_key": "commit-abc-123",
  "actual": { "unit": "TOKENS", "amount": 4200 },
  "metrics": {
    "tokens_input": 1200,
    "tokens_output": 3000,
    "latency_ms": 840,
    "model_version": "gpt-4o-2024-08"
  },
  "metadata": {}
}
```

**Response** `200 OK`
```json
{
  "status": "COMMITTED",
  "charged": { "unit": "TOKENS", "amount": 4200 },
  "released": { "unit": "TOKENS", "amount": 800 }
}
```

`released` is present only when `actual < reserved` (over-reservation is automatically returned).

---

### POST /v1/reservations/{reservation_id}/release

Return the full reserved amount to the budget without charging. Returns `200 OK`.

**Request**
```json
{
  "idempotency_key": "release-abc-123",
  "reason": "action cancelled"
}
```

**Response** `200 OK`
```json
{
  "status": "RELEASED",
  "released": { "unit": "TOKENS", "amount": 5000 }
}
```

---

### POST /v1/reservations/{reservation_id}/extend

Extend the reservation TTL. Only allowed while `server_time ≤ expires_at_ms` (no grace window). Returns `200 OK`.

**Request**
```json
{
  "idempotency_key": "extend-abc-123",
  "extend_by_ms": 30000,
  "metadata": {}
}
```

| Field | Required | Constraints |
|---|---|---|
| `idempotency_key` | yes | 1-256 chars |
| `extend_by_ms` | yes | 1-86400000 ms |
| `metadata` | no | arbitrary key-value pairs |

**Response** `200 OK`
```json
{
  "status": "ACTIVE",
  "expires_at_ms": 1700000090000
}
```

`extend_by_ms` is added to the current `expires_at_ms` (not to request time). Does not change reserved amount, unit, subject, action, or scope.

**Error conditions:** `409 RESERVATION_FINALIZED` if the reservation is already COMMITTED or RELEASED; `410 RESERVATION_EXPIRED` if `server_time > expires_at_ms`; `409 MAX_EXTENSIONS_EXCEEDED` if the tenant's `max_reservation_extensions` limit has been reached; `404 NOT_FOUND` if the reservation does not exist.

---

### GET /v1/reservations

List reservations for the effective tenant. Optional recovery/debug endpoint. Returns `200 OK`.

**Query parameters**

| Parameter | Description |
|---|---|
| `idempotency_key` | Recover `reservation_id` from a prior create call |
| `status` | Filter by `ACTIVE`, `COMMITTED`, `RELEASED`, or `EXPIRED` |
| `tenant` | Must match effective tenant if provided |
| `workspace` / `app` / `workflow` / `agent` / `toolset` | Subject field filters |
| `limit` | Max results per page (default `50`, max `200`) |
| `cursor` | Opaque pagination cursor from previous response |

**Response** `200 OK`
```json
{
  "reservations": [
    {
      "reservation_id": "550e8400-...",
      "status": "ACTIVE",
      "idempotency_key": "req-abc-123",
      "subject": { "tenant": "acme", "agent": "summarizer-v2" },
      "action": { "kind": "llm.completion", "name": "openai:gpt-4o" },
      "reserved": { "unit": "TOKENS", "amount": 5000 },
      "created_at_ms": 1700000000000,
      "expires_at_ms": 1700000060000,
      "scope_path": "tenant:acme/...",
      "affected_scopes": ["tenant:acme", "..."]
    }
  ],
  "has_more": false,
  "next_cursor": null
}
```

---

### GET /v1/reservations/{reservation_id}

Fetch a single reservation by ID. Returns `200 OK` for all statuses including `COMMITTED`, `RELEASED`, and `EXPIRED`.

**Response** `200 OK`

Includes all fields from the list entry above, plus:

| Field | Description |
|---|---|
| `committed` | Amount actually charged (present when COMMITTED) |
| `finalized_at_ms` | Timestamp of commit or release |
| `metadata` | Metadata supplied at reservation time |

---

### GET /v1/balances

Query budget balances. At least one of `tenant/workspace/app/workflow/agent/toolset` is required (`400 INVALID_REQUEST` otherwise). Returns `200 OK`.

**Query parameters**

| Parameter | Description |
|---|---|
| `tenant` | Defaults to effective tenant |
| `workspace` / `app` / `workflow` / `agent` / `toolset` | Subject field filters |
| `include_children` | MAY be ignored by v0 implementations |
| `limit` | Max results per page (default `50`, max `200`) |
| `cursor` | Opaque pagination cursor |

**Response** `200 OK`
```json
{
  "balances": [
    {
      "scope": "tenant:acme/.../agent:summarizer-v2",
      "scope_path": "tenant:acme/.../agent:summarizer-v2",
      "remaining": { "unit": "TOKENS", "amount": 950000 },
      "reserved":  { "unit": "TOKENS", "amount": 5000 },
      "spent":     { "unit": "TOKENS", "amount": 45000 },
      "allocated": { "unit": "TOKENS", "amount": 1000000 }
    }
  ],
  "has_more": false,
  "next_cursor": null
}
```

`remaining` uses a signed amount and may be negative when a scope is in overdraft. `debt`, `overdraft_limit`, and `is_over_limit` are omitted when zero/false.

**Ledger invariant** (when all fields are present): `remaining = allocated − spent − reserved − debt`

---

### POST /v1/events

Record a direct debit without a prior reservation. Applied atomically across all derived scopes. Returns `201 Created`.

**Request**
```json
{
  "idempotency_key": "evt-abc-123",
  "subject": { "tenant": "acme", "agent": "summarizer-v2" },
  "action":  { "kind": "llm.completion", "name": "openai:gpt-4o" },
  "actual":  { "unit": "TOKENS", "amount": 3000 },
  "overage_policy": "REJECT",
  "metrics": {
    "tokens_input": 900,
    "tokens_output": 2100,
    "latency_ms": 620
  },
  "client_time_ms": 1700000000000,
  "metadata": {}
}
```

| Overage policy | Behaviour |
|---|---|
| `REJECT` *(default)* | Returns `409 BUDGET_EXCEEDED` if `actual > remaining` on any derived scope |
| `ALLOW_IF_AVAILABLE` | Applies atomically only if sufficient remaining exists; otherwise `409 BUDGET_EXCEEDED` |
| `ALLOW_WITH_OVERDRAFT` | Creates debt if insufficient; `409 OVERDRAFT_LIMIT_EXCEEDED` if `debt + actual > overdraft_limit` |

`client_time_ms` is advisory only; server time governs all budget and expiry decisions. Debt and over-limit state do **not** block events — only the `overage_policy` logic applies.

**Response** `201 Created`
```json
{
  "status": "APPLIED",
  "event_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## Error Codes

All errors use this envelope:

```json
{
  "error": "BUDGET_EXCEEDED",
  "message": "Insufficient remaining balance in scope tenant:acme",
  "request_id": "550e8400-...",
  "details": {}
}
```

| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_REQUEST` | 400 | Missing or invalid field |
| `UNIT_MISMATCH` | 400 | Commit unit differs from reservation unit, or event unit not supported for target scope |
| `UNAUTHORIZED` | 401 | Missing or invalid API key |
| `FORBIDDEN` | 403 | Tenant in request does not match API key |
| `NOT_FOUND` | 404 | Reservation, budget, or resource not found |
| `BUDGET_EXCEEDED` | 409 | Insufficient remaining budget |
| `RESERVATION_FINALIZED` | 409 | Reservation already committed or released |
| `IDEMPOTENCY_MISMATCH` | 409 | Idempotency key reused with different parameters |
| `OVERDRAFT_LIMIT_EXCEEDED` | 409 | `debt + delta > overdraft_limit`; or scope is over-limit |
| `DEBT_OUTSTANDING` | 409 | Scope has unresolved debt with no overdraft limit; new reservations blocked |
| `RESERVATION_EXPIRED` | 410 | Operation attempted after expiry window |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

**Precedence:** when `is_over_limit=true`, `OVERDRAFT_LIMIT_EXCEEDED` takes precedence over `DEBT_OUTSTANDING`.

`/v1/decide` MUST NOT return `409` for budget conditions — it returns `decision=DENY` with a `reason_code` instead.

---

## Development

### Running Tests

Integration tests are excluded from the default build and require the `integration-tests` Maven profile. They use [Testcontainers](https://www.testcontainers.org/) to spin up a Redis instance automatically — Docker must be running. Run from the `cycles-protocol-service/` directory:

```bash
# Unit tests only (ScopeDerivationService — no Docker required)
mvn test

# Integration tests (requires Docker for Testcontainers Redis)
mvn clean install -Pintegration-tests

# Full build without integration tests
mvn clean install
```

Integration tests (`*IntegrationTest.java`) are excluded by default via surefire in `cycles-protocol-service-api/pom.xml`. The `-Pintegration-tests` profile overrides this exclusion.

The test profile (`application-test.properties`) injects Redis connection details via `@DynamicPropertySource` from the Testcontainers Redis instance.

### Project Structure

```
cycles-protocol-service/
├── build-all.sh                          # mvn clean install wrapper
├── init-budgets.sh                       # seeds sample budget via redis-cli
├── cycles-protocol-service-model/        # shared POJOs (no Spring dependency)
│   └── src/main/java/.../model/
│       ├── Subject.java, Action.java, Amount.java, SignedAmount.java
│       ├── Reservation*.java, Commit*.java, Release*.java
│       ├── Decision*.java, Event*.java, Balance*.java
│       ├── Enums.java                    # ErrorCode, UnitEnum, OveragePolicy, etc.
│       ├── Caps.java, ErrorResponse.java
│       └── auth/                         # ApiKey, ApiKeyStatus, ApiKeyValidationResponse
├── cycles-protocol-service-data/         # Redis repository + Lua + services
│   └── src/main/
│       ├── java/.../data/
│       │   ├── repository/
│       │   │   ├── RedisReservationRepository.java  # core budget logic
│       │   │   └── ApiKeyRepository.java            # API key validation
│       │   ├── service/
│       │   │   ├── ScopeDerivationService.java      # scope hierarchy builder
│       │   │   ├── ApiKeyValidationService.java
│       │   │   └── ReservationExpiryService.java    # scheduled expiry sweep
│       │   ├── config/RedisConfig.java              # JedisPool setup
│       │   └── exception/CyclesProtocolException.java
│       └── resources/lua/
│           ├── reserve.lua    # atomic budget reservation
│           ├── commit.lua     # record actual spend with overdraft support
│           ├── release.lua    # return reserved budget
│           ├── extend.lua     # extend reservation TTL
│           ├── expire.lua     # background expiry (called by ExpiryService)
│           └── event.lua      # direct debit without reservation
└── cycles-protocol-service-api/          # Spring Boot app + controllers
    └── src/main/java/.../api/
        ├── CyclesProtocolApplication.java
        ├── controller/
        │   ├── BaseController.java              # tenant auth helpers
        │   ├── ReservationController.java       # /v1/reservations/*
        │   ├── BalanceController.java           # /v1/balances
        │   ├── DecisionController.java          # /v1/decide
        │   └── EventController.java             # /v1/events
        ├── auth/
        │   ├── ApiKeyAuthenticationFilter.java  # X-Cycles-API-Key validation
        │   ├── ApiKeyAuthentication.java        # SecurityContext token
        │   └── SecurityConfig.java              # Spring Security config
        ├── filter/
        │   ├── RequestIdFilter.java             # X-Request-Id generation
        │   └── RateLimitHeaderFilter.java       # rate limit headers (stub)
        └── exception/GlobalExceptionHandler.java
```

### Lua Scripts

All budget mutations run as Redis Lua scripts for atomicity (no multi-key race conditions). Scripts are loaded from `resources/lua/` by `RedisReservationRepository` at startup and executed via `EVALSHA`.

Each script follows a common pattern:
1. **Idempotency check** — replay cached result if the same `(tenant, idempotency_key)` is seen; detect payload mismatch via SHA-256 hash
2. **Fail-fast validation** — check all affected scopes before any mutations (budget exists, sufficient balance, no debt/over-limit)
3. **Atomic mutation** — `HINCRBY` budget fields across all scopes in a single script execution
4. **State persistence** — write reservation/event hash and idempotency cache

### Additional Configuration

| Variable | Default | Description |
|---|---|---|
| `cycles.expiry.interval-ms` | `5000` | How often the background expiry sweep runs (ms) |
| `spring.jackson.deserialization.fail-on-unknown-properties` | `true` | Reject unknown JSON fields |
| `spring.jackson.default-property-inclusion` | `non_null` | Omit null fields from responses |

---

## Redis Data Model

### Budget hash  `budget:{scope}:{unit}`

| Field | Type | Description |
|---|---|---|
| `allocated` | integer | Total budget allocated to this scope |
| `remaining` | integer | Available balance (may be negative in overdraft) |
| `reserved` | integer | Currently held by active reservations |
| `spent` | integer | Cumulative amount committed |
| `debt` | integer | Overdraft debt incurred (0 if none) |
| `overdraft_limit` | integer | Maximum permitted debt (0 = no overdraft) |
| `is_over_limit` | `"true"/"false"` | True when `debt > overdraft_limit`; blocks new reservations |
| `scope` | string | Full canonical scope path |
| `unit` | string | Unit enum value |
| `caps_json` | JSON string | Optional operator-configured caps (`max_tokens`, `max_steps_remaining`, `tool_allowlist`, `tool_denylist`, `cooldown_ms`); `tool_allowlist` takes precedence over `tool_denylist` when both are present |

Budgets must be seeded externally (see `init-budgets.sh` for an example).

### Reservation hash  `reservation:res_{uuid}`

Stores full reservation state. Indexed by expiry in the `reservation:ttl` sorted set (score = `expires_at`).

| Field | Type | Description |
|---|---|---|
| `reservation_id` | string | UUID (without `res_` prefix) |
| `tenant` | string | Owning tenant ID |
| `state` | string | `ACTIVE`, `COMMITTED`, `RELEASED`, or `EXPIRED` |
| `subject_json` | JSON string | Full subject object |
| `action_json` | JSON string | Action object (`kind`, `name`, `tags`) |
| `estimate_amount` | integer | Amount reserved |
| `estimate_unit` | string | Unit enum value |
| `scope_path` | string | Full canonical scope path to deepest level |
| `affected_scopes` | JSON string | Array of all ancestor scope paths |
| `created_at` | integer | Creation timestamp (ms) |
| `expires_at` | integer | Expiry timestamp (ms) |
| `grace_ms` | integer | Grace period after expiry for commit/release |
| `idempotency_key` | string | Client-provided idempotency key |
| `overage_policy` | string | `REJECT`, `ALLOW_IF_AVAILABLE`, or `ALLOW_WITH_OVERDRAFT` |
| `metadata_json` | JSON string | Optional client metadata |

**Fields added on finalization:**

| Field | State | Description |
|---|---|---|
| `charged_amount` | COMMITTED | Final amount charged |
| `debt_incurred` | COMMITTED | Debt created across scopes |
| `committed_at` | COMMITTED | Commit timestamp (ms) |
| `committed_idempotency_key` | COMMITTED | Idempotency key for the commit call |
| `committed_payload_hash` | COMMITTED | SHA-256 hash for idempotency mismatch detection |
| `committed_metrics_json` | COMMITTED | Optional metrics from commit request |
| `committed_metadata_json` | COMMITTED | Optional metadata from commit request |
| `released_at` | RELEASED | Release timestamp (ms) |
| `released_idempotency_key` | RELEASED | Idempotency key for the release call |
| `released_payload_hash` | RELEASED | SHA-256 hash for idempotency mismatch detection |
| `expired_at` | EXPIRED | Expiration timestamp (ms) |
| `extend_metadata_json` | ACTIVE | Optional metadata from extend request |

### TTL sorted set  `reservation:ttl`

Tracks active reservation expiry for the background sweep (`ReservationExpiryService`).

| Property | Value |
|---|---|
| Type | Sorted set |
| Score | `expires_at` timestamp (ms) |
| Member | Reservation UUID |

Updated on create/extend (`ZADD`), removed on commit/release/expire (`ZREM`).

### Event hash  `event:evt_{uuid}`

Immutable record of a direct debit event.

| Field | Type | Description |
|---|---|---|
| `event_id` | string | UUID (without `evt_` prefix) |
| `tenant` | string | Owning tenant ID |
| `subject_json` | JSON string | Full subject object |
| `action_json` | JSON string | Action object |
| `amount` | integer | Debit amount |
| `unit` | string | Unit enum value |
| `scope_path` | string | Full canonical scope path |
| `affected_scopes` | JSON string | Array of all ancestor scope paths |
| `created_at` | integer | Creation timestamp (ms) |
| `idempotency_key` | string | Client-provided idempotency key |
| `metrics_json` | JSON string | Optional metrics |
| `client_time_ms` | string | Client-reported timestamp (advisory) |
| `metadata_json` | JSON string | Optional client metadata |

### Idempotency keys

| Pattern | TTL |
|---|---|
| `idem:{tenant}:reserve:{key}` | reservation TTL + grace period (min 24h) |
| `idem:{tenant}:extend:{reservationId}:{key}` | remaining reservation lifetime after extension |
| `idem:{tenant}:decide:{key}` | 24 hours |
| `idem:{tenant}:dry_run:{key}` | 24 hours |
| `idem:{tenant}:event:{key}` | 7 days |

Commit and release idempotency is stored inline on the reservation hash (fields `committed_idempotency_key`/`committed_payload_hash` and `released_idempotency_key`/`released_payload_hash`) rather than as separate Redis keys. Each idempotency key also has a companion `…:hash` key storing the SHA-256 payload hash for mismatch detection.

### API key and tenant storage

| Key | Description |
|---|---|
| `apikey:{keyId}` | JSON-serialised `ApiKey` object (key hash, tenant, status, permissions, expiry) |
| `apikey:lookup:{prefix}` | Maps key prefix → `keyId` for O(1) lookup |
| `tenant:{tenantId}` | Tenant metadata hash; includes `status` (`ACTIVE`, `SUSPENDED`, `CLOSED`) checked during API key validation |
