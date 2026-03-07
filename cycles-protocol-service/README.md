# Cycles Protocol Server v0.1.23

Reference implementation of the [Cycles Budget Authority API](../cycles-protocol-v0.yaml) — a reservation-based budget control service for AI agents and workflows. All atomic budget operations are executed via Redis Lua scripts; no external database is required.

## Contents

- [Architecture](#architecture)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Core Concepts](#core-concepts)
- [Authentication](#authentication)
- [API Reference](#api-reference)
- [Error Codes](#error-codes)
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
  java -jar cycles-protocol-service-api/target/cycles-protocol-service-api-0.1.23.jar
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

At least one standard field is required (`tenant` is typical). Intermediate gaps are filled with `default`. A reservation against `{tenant, agent}` is enforced at **every ancestor scope** — the tenant-level budget, any workspace-level budget, and the agent-level budget must all have sufficient remaining balance.

```json
{
  "tenant": "acme",
  "workspace": "prod",
  "agent": "summarizer-v2"
}
```

Derived scopes (all checked on every operation):
```
tenant:acme
tenant:acme/workspace:prod
tenant:acme/workspace:prod/app:default
tenant:acme/workspace:prod/app:default/workflow:default
tenant:acme/workspace:prod/app:default/workflow:default/agent:summarizer-v2
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

### Debt and Overdraft

- **`debt`** is created when `overage_policy=ALLOW_WITH_OVERDRAFT` and the commit delta exceeds remaining budget.
- When `debt > 0`, **new reservations** against that scope are blocked with `409 DEBT_OUTSTANDING`.
- When `debt > overdraft_limit`, the scope enters **over-limit** state (`is_over_limit=true`). New reservations are then blocked with `409 OVERDRAFT_LIMIT_EXCEEDED` (takes precedence over `DEBT_OUTSTANDING`).
- Debt does **not** block direct events (`/v1/events`); events always apply their own `overage_policy`.
- Existing active reservations MAY be committed or released normally while a scope is over-limit.
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

## Authentication

Every request requires an API key in the header:

```
X-Cycles-API-Key: <key>
```

Keys are stored in Redis (`apikey:{keyId}`) and validated by bcrypt hash comparison. A valid key resolves a `tenant_id` used for all authorization checks — the `subject.tenant` in the request must match the tenant from the API key; otherwise the server returns `403 FORBIDDEN`.

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

On `DENY` (insufficient budget, debt, or over-limit):
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
| `ttl_ms` | no | `60000` | 1000–86400000 ms |
| `grace_period_ms` | no | `5000` | 0–60000 ms |
| `overage_policy` | no | `REJECT` | see Overage Policies |
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

Fetch a single reservation by ID. Returns `200 OK` for all statuses including `COMMITTED` and `RELEASED`. Returns `410 RESERVATION_EXPIRED` for expired reservations.

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
| `NOT_FOUND` | 404 | Reservation or resource not found |
| `BUDGET_EXCEEDED` | 409 | Insufficient remaining budget |
| `RESERVATION_FINALIZED` | 409 | Reservation already committed or released |
| `IDEMPOTENCY_MISMATCH` | 409 | Idempotency key reused with different parameters |
| `OVERDRAFT_LIMIT_EXCEEDED` | 409 | `debt + delta > overdraft_limit`; or scope is over-limit |
| `DEBT_OUTSTANDING` | 409 | Scope has unresolved debt; new reservations blocked |
| `RESERVATION_EXPIRED` | 410 | Operation attempted after expiry window |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

**Precedence:** when `is_over_limit=true`, `OVERDRAFT_LIMIT_EXCEEDED` takes precedence over `DEBT_OUTSTANDING`.

`/v1/decide` MUST NOT return `409` for budget conditions — it returns `decision=DENY` with a `reason_code` instead.

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

Budgets must be seeded externally (see `init-budgets.sh` for an example).

### Reservation hash  `reservation:res_{uuid}`

Stores full reservation state. Indexed by expiry in `reservation:ttl` (sorted set).

### Event hash  `event:evt_{uuid}`

Immutable record of a direct debit event.

### Idempotency keys

| Pattern | TTL |
|---|---|
| `idem:{tenant}:reserve:{key}` | reservation TTL + grace period |
| `idem:{tenant}:commit:{key}` | stored on reservation hash indefinitely |
| `idem:{tenant}:release:{key}` | stored on reservation hash indefinitely |
| `idem:{tenant}:extend:{reservationId}:{key}` | remaining reservation lifetime after extension |
| `idem:{tenant}:decide:{key}` | 24 hours |
| `idem:{tenant}:dry_run:{key}` | 24 hours |
| `idem:{tenant}:event:{key}` | 7 days |

### API key storage

| Key | Description |
|---|---|
| `apikey:{keyId}` | JSON-serialised `ApiKey` object |
| `apikey:lookup:{prefix}` | Maps key prefix → `keyId` for O(1) lookup |
