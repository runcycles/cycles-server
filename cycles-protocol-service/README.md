# Cycles Protocol Server v0.1.23

Reference implementation of the Cycles Budget Authority API — a reservation-based budget control service for AI agents and workflows. All atomic budget operations are executed via Redis Lua scripts; no external database is required.

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

Only `tenant` is required. Intermediate gaps are filled with `default`. A reservation against `{tenant, agent}` is enforced at **every ancestor scope** — the tenant-level budget, any workspace-level budget, and the agent-level budget must all have sufficient remaining balance.

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

### Units

| Value | Description |
|---|---|
| `TOKENS` | LLM tokens |
| `USD_MICROCENTS` | Cost in USD × 10⁻⁶ cents |
| `CREDITS` | Generic credit units |
| `RISK_POINTS` | Risk scoring |

All amounts are non-negative integers. A single reservation must use one unit; mixing units across a commit is rejected with `UNIT_MISMATCH`.

### Reservation Lifecycle

```
POST /v1/reservations  →  ACTIVE
        │
        ├── POST …/commit  →  COMMITTED  (spend recorded)
        ├── POST …/release →  RELEASED   (budget returned)
        └── POST …/extend  →  ACTIVE     (TTL extended, still ACTIVE)

Expired (past expires_at + grace_period_ms): no further operations allowed
```

### Overage Policies

Controls what happens at commit time when `actual > estimate`:

| Policy | Behaviour |
|---|---|
| `REJECT` *(default)* | Returns `BUDGET_EXCEEDED` if actual exceeds estimate |
| `ALLOW_IF_AVAILABLE` | Charges the overage from remaining budget; fails if insufficient |
| `ALLOW_WITH_OVERDRAFT` | Charges overage; creates debt up to `overdraft_limit`; sets `is_over_limit` when debt ≥ limit |

A scope with **outstanding debt** or **`is_over_limit = true`** blocks all new reservations and direct events until the debt is cleared externally.

### Idempotency

Every mutating request accepts an `idempotency_key` (body field) and an optional `X-Idempotency-Key` header. If both are provided they must match. Duplicate requests with the same key replay the original result without side effects.

| Operation | Idempotency TTL |
|---|---|
| Reserve | reservation TTL + grace period |
| Commit | stored on reservation indefinitely |
| Release | stored on reservation indefinitely |
| Extend | remaining reservation lifetime after extension |
| Decide | 24 hours |
| Event | 7 days |

---

## Authentication

Every request requires an API key in the header:

```
X-Cycles-API-Key: <key>
```

Keys are stored in Redis (`apikey:{keyId}`) and validated by bcrypt hash comparison. A valid key resolves a `tenant_id` that is used for all authorization checks — the tenant in the request body must match the tenant resolved from the key.

---

## API Reference

All timestamps are Unix milliseconds. All responses include an `X-Request-Id` header.

### POST /v1/reservations

Reserve budget before executing an action. The budget is held until committed, released, or expired.

**Request**
```json
{
  "idempotency_key": "req-abc-123",
  "subject": { "tenant": "acme", "agent": "summarizer-v2" },
  "action":  { "kind": "llm_call", "name": "gpt-4o" },
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
| `subject.tenant` | yes | — | max 128 chars |
| `subject.workspace/app/workflow/agent/toolset` | no | — | max 128 chars each |
| `action.kind` | yes | — | max 64 chars |
| `action.name` | yes | — | max 256 chars |
| `estimate.unit` | yes | — | see Units |
| `estimate.amount` | yes | — | ≥ 0 |
| `ttl_ms` | no | `60000` | 1000–86400000 |
| `grace_period_ms` | no | `5000` | 0–60000 |
| `overage_policy` | no | `REJECT` | see Overage Policies |
| `dry_run` | no | `false` | if true, evaluates without persisting |

**Response** `201 Created`
```json
{
  "decision": "ALLOW",
  "reservation_id": "550e8400-e29b-41d4-a716-446655440000",
  "affected_scopes": ["tenant:acme", "tenant:acme/..../agent:summarizer-v2"],
  "expires_at_ms": 1700000060000,
  "scope_path": "tenant:acme/..../agent:summarizer-v2",
  "reserved": { "unit": "TOKENS", "amount": 5000 },
  "caps": { "max_tokens": 8192 }
}
```

On `DENY` (budget exceeded, over-limit, outstanding debt, or `dry_run`):
```json
{
  "decision": "DENY",
  "reason_code": "BUDGET_EXCEEDED"
}
```

---

### POST /v1/reservations/{id}/commit

Record actual spend. Must be called before `expires_at_ms + grace_period_ms`.

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

`released` is present only when `actual < estimate`. `charged` equals `actual`.

---

### POST /v1/reservations/{id}/release

Return the full reserved amount to the budget without charging.

**Request**
```json
{
  "idempotency_key": "release-abc-123",
  "reason": "action cancelled",
  "metadata": {}
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

### POST /v1/reservations/{id}/extend

Extend the reservation TTL. Only allowed while the reservation has not yet expired.

**Request**
```json
{
  "idempotency_key": "extend-abc-123",
  "extend_by_ms": 30000,
  "metadata": {}
}
```

**Response** `200 OK`
```json
{
  "status": "ACTIVE",
  "expires_at_ms": 1700000090000
}
```

---

### GET /v1/reservations

List reservations for the authenticated tenant. Supports cursor-based pagination.

**Query parameters**

| Parameter | Default | Description |
|---|---|---|
| `tenant` | *(from API key)* | Must match authenticated tenant |
| `limit` | `50` | Max results per page |
| `cursor` | — | Pagination cursor from previous response |

**Response** `200 OK`
```json
{
  "reservations": [
    {
      "reservation_id": "...",
      "status": "ACTIVE",
      "idempotency_key": "...",
      "subject": { "tenant": "acme", "agent": "summarizer-v2" },
      "action": { "kind": "llm_call", "name": "gpt-4o" },
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

### GET /v1/reservations/{id}

Fetch a single reservation by ID.

**Response** `200 OK` — same shape as a single entry in the list above.

---

### GET /v1/balances

Query budget balances. At least one subject filter is required.

**Query parameters**

| Parameter | Description |
|---|---|
| `tenant` | *(defaults to authenticated tenant)* |
| `workspace` | Filter by workspace |
| `app` | Filter by app |
| `workflow` | Filter by workflow |
| `agent` | Filter by agent |
| `toolset` | Filter by toolset |
| `limit` | Max results (default `50`) |
| `cursor` | Pagination cursor |

**Response** `200 OK`
```json
{
  "balances": [
    {
      "scope": "tenant:acme/workspace:prod/app:default/agent:summarizer-v2",
      "scope_path": "tenant:acme/workspace:prod/app:default/agent:summarizer-v2",
      "remaining": { "unit": "TOKENS", "amount": 950000 },
      "reserved":  { "unit": "TOKENS", "amount": 5000 },
      "spent":     { "unit": "TOKENS", "amount": 45000 },
      "allocated": { "unit": "TOKENS", "amount": 1000000 },
      "debt":            null,
      "overdraft_limit": null,
      "is_over_limit":   null
    }
  ],
  "has_more": false,
  "next_cursor": null
}
```

`remaining` uses a signed amount and may be negative when a scope is in overdraft. `debt`, `overdraft_limit`, and `is_over_limit` are omitted when zero/false.

---

### POST /v1/decide

Evaluate whether a budget operation would be allowed **without** reserving. Useful for pre-flight checks. Idempotent with a 24-hour replay window.

**Request**
```json
{
  "idempotency_key": "decide-abc-123",
  "subject": { "tenant": "acme", "agent": "summarizer-v2" },
  "action":  { "kind": "llm_call", "name": "gpt-4o" },
  "estimate": { "unit": "TOKENS", "amount": 5000 },
  "metadata": {}
}
```

**Response** `200 OK`
```json
{
  "decision": "ALLOW",
  "affected_scopes": ["tenant:acme", "..."]
}
```

On `DENY`:
```json
{
  "decision": "DENY",
  "reason_code": "BUDGET_EXCEEDED"
}
```

---

### POST /v1/events

Record a direct debit without a prior reservation. The amount is immediately charged. Idempotent with a 7-day replay window.

**Request**
```json
{
  "idempotency_key": "evt-abc-123",
  "subject": { "tenant": "acme", "agent": "summarizer-v2" },
  "action":  { "kind": "llm_call", "name": "gpt-4o" },
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

**Response** `201 Created`
```json
{
  "status": "APPLIED",
  "event_id": "evt_550e8400-..."
}
```

---

## Error Codes

All errors follow this envelope:

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
| `UNAUTHORIZED` | 401 | Missing or invalid API key |
| `FORBIDDEN` | 403 | Tenant in request does not match API key |
| `NOT_FOUND` | 404 | Reservation or resource not found |
| `BUDGET_EXCEEDED` | 422 | Insufficient remaining budget |
| `RESERVATION_EXPIRED` | 422 | Operation attempted after expires_at + grace_period |
| `RESERVATION_FINALIZED` | 422 | Reservation already committed or released |
| `IDEMPOTENCY_MISMATCH` | 409 | Idempotency key reused with different parameters |
| `UNIT_MISMATCH` | 422 | Commit unit differs from reservation estimate unit |
| `OVERDRAFT_LIMIT_EXCEEDED` | 422 | Overage would exceed overdraft limit |
| `DEBT_OUTSTANDING` | 422 | Scope has unresolved debt; new reservations blocked |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

---

## Redis Data Model

### Budget hash  `budget:{scope}:{unit}`

| Field | Type | Description |
|---|---|---|
| `allocated` | integer | Total budget allocated to this scope |
| `remaining` | integer | Available balance (may go negative in overdraft) |
| `reserved` | integer | Currently held by active reservations |
| `spent` | integer | Cumulative amount committed |
| `debt` | integer | Overdraft debt incurred (0 if none) |
| `overdraft_limit` | integer | Maximum permitted debt (0 = no overdraft) |
| `is_over_limit` | `"true"/"false"` | Blocks new reservations when true |
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
| `idem:{tenant}:commit:{key}` | stored on reservation |
| `idem:{tenant}:release:{key}` | stored on reservation |
| `idem:{tenant}:extend:{key}` | remaining reservation lifetime |
| `idem:{tenant}:decide:{key}` | 24 hours |
| `idem:{tenant}:event:{key}` | 7 days |

### API key storage

| Key | Description |
|---|---|
| `apikey:{keyId}` | JSON-serialised `ApiKey` object |
| `apikey:lookup:{prefix}` | Maps key prefix → `keyId` for O(1) lookup |
