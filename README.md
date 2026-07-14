[![CI](https://github.com/runcycles/cycles-server/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-server/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-95%25+-brightgreen)](https://github.com/runcycles/cycles-server/actions)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/runcycles/cycles-server/badge)](https://scorecard.dev/viewer/?uri=github.com/runcycles/cycles-server)
[![CII Best Practices](https://www.bestpractices.dev/projects/12734/badge)](https://www.bestpractices.dev/projects/12734)

# Cycles Server — Runtime budget and action authority for AI agents

**Self-hosted server that enforces hard limits on AI agent spend, risk, and tool actions before execution.** Reference implementation of the [Cycles Protocol](https://github.com/runcycles/cycles-protocol) (v0.1.25) — a reservation-based control plane for multi-tenant AI agent runtimes.

Drop-in budget governance for OpenAI, Anthropic, MCP servers, OpenAI Agents SDK, LangChain, and custom agent frameworks. Reserve cost up front, commit on success, release on failure — with concurrency-safe enforcement across thousands of agents and tenants.


<p align="center">
  <a href="https://runcycles.io">
    <img src="https://runcycles.io/demo-runaway.gif" alt="Cycles preventing runaway AI agent spend ($6 in 30s → hard stop at $1)" width="720"/>
  </a><br/>
  <em>Cycles enforcing a hard budget cap on a runaway agent loop — see the <a href="https://github.com/runcycles/cycles-runaway-demo">runaway demo</a> for the full walkthrough.</em>
</p>

<p align="center">
  <a href="https://runcycles.io">
    <img src="https://runcycles.io/demo-action-authority.gif" alt="Cycles blocking an unauthorized agent action before it executes" width="720"/>
  </a><br/>
  <em>Action authority — Cycles blocking an unauthorized tool call before the side effect lands. <a href="https://github.com/runcycles/cycles-agent-action-authority-demo">Action-authority demo →</a></em>
</p>

## Quick Start

### One-command quickstart (recommended)

Starts the full stack (Redis + Cycles Server + Admin Server), creates a tenant, API key, and budget, and verifies the full reserve/commit lifecycle:

```bash
./quickstart.sh
```

**Prerequisites:** Docker and Docker Compose v2+. No Java or Maven required.

### Docker (server only)

```bash
# Build from source and start (no local Java/Maven required)
docker compose up --build
```

Server starts on **port 7878**. API docs are available when SpringDoc is
enabled and require `X-Admin-API-Key`.

### Pre-built image (no source code needed)

```bash
# Download docker-compose.prod.yml, then:
docker compose -f docker-compose.prod.yml up
```

This pulls the latest image from `ghcr.io/runcycles/cycles-server`.

### Manual build

**Prerequisites:** Java 21+, Maven, Docker (for Redis)

```bash
# 1. Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# 2. Build
cd cycles-protocol-service
./build-all.sh

# 3. Seed a sample budget
./init-budgets.sh

# 4. Run
REDIS_HOST=localhost REDIS_PORT=6379 \
  java -jar cycles-protocol-service-api/target/cycles-protocol-service-api-*.jar
```

Server starts on **port 7878**. API docs are available when SpringDoc is
enabled and require `X-Admin-API-Key`.

## Architecture

```
HTTP client
    │  X-Cycles-API-Key
    ▼
Spring Boot 3.5 (port 7878)
    │  ApiKeyAuthenticationFilter
    │  Controllers → Repository → Lua scripts (atomic)
    ▼
Redis 7+
    │  event:{id}, delivery:{id}, LPUSH dispatch:pending
    ▼
cycles-server-events (port 7980)
    │  BRPOP → HTTP POST with HMAC-SHA256 signature
    ▼
Webhook receivers
```

**Event emission:** Runtime operations emit events to the shared Redis dispatch queue. The events delivery service (`cycles-server-events`) picks them up and delivers via HTTP POST with HMAC-SHA256 signing.

**Modules** (under `cycles-protocol-service/`):

| Module | Purpose |
|---|---|
| `cycles-protocol-service-model` | Shared request/response POJOs |
| `cycles-protocol-service-data` | Redis repository + Lua scripts |
| `cycles-protocol-service-api` | Spring Boot controllers + auth |

## API Endpoints

All protocol endpoints require `X-Cycles-API-Key` header authentication — **except `GET /v1/evidence/{id}`**, a public capability URL (the unguessable `evidence_id` is the bearer secret), and **`GET /v1/.well-known/cycles-jwks.json`**, which publishes public verification keys for CyclesEvidence signer resolution (see [CyclesEvidence](#cyclesevidence)). Operational endpoints are separate: liveness/readiness are public for probes, while aggregate actuator, Prometheus, API docs, and Swagger require `X-Admin-API-Key` when enabled.

| Endpoint | Method | Description |
|---|---|---|
| `/v1/decide` | POST | Evaluate budget decision without reserving |
| `/v1/reservations` | POST | Create budget reservation |
| `/v1/reservations` | GET | List reservations (with pagination/filters) |
| `/v1/reservations/{id}` | GET | Fetch a single reservation |
| `/v1/reservations/{id}/commit` | POST | Record actual spend |
| `/v1/reservations/{id}/release` | POST | Return reserved budget |
| `/v1/reservations/{id}/extend` | POST | Extend reservation TTL |
| `/v1/events` | POST | Direct debit without prior reservation (returns 201) |
| `/v1/balances` | GET | Query budget balances for scopes |
| `/v1/evidence/{id}` | GET | Fetch a signed [CyclesEvidence](#cyclesevidence) envelope by id (public capability URL — no API key) |
| `/v1/.well-known/cycles-jwks.json` | GET | Fetch the public CyclesEvidence signer JWK Set for authority resolution and rotation history (no API key) |

### CyclesEvidence

Every budget decision can be backed by a tamper-evident, signed audit envelope. On `decide` / `reserve` / `commit` / `release` — and on budget/lifecycle **denials** (e.g. a 409 `BUDGET_EXCEEDED` `error`) — the response carries an optional `cycles_evidence` ref (`{ evidence_id, cycles_evidence_url }`). `cycles-server` computes the content-addressed `evidence_id` synchronously and returns it on the wire; the `cycles-server-events` tier asynchronously Ed25519-signs the envelope and stores it, served back verbatim at the public `GET /v1/evidence/{id}` capability URL. A consumer (e.g. an APS gateway) records the `evidence_id` to bind its own receipt to the decision, then fetches + verifies the envelope offline — no live ledger access needed.

Evidence is **off until configured**: set the shared identity env vars (below). The private signing key lives only in `cycles-server-events` — see its [identity enablement runbook](https://github.com/runcycles/cycles-server-events/blob/main/docs/evidence-identity-enablement.md). Wire shape: `cycles-protocol-v0.yaml` (`CyclesEvidenceRef`, `getEvidence`, `getEvidenceJwks`) + [`cycles-evidence-v0.2.yaml`](https://github.com/runcycles/cycles-protocol/blob/main/cycles-evidence-v0.2.yaml), whose signer-key resolution/JWKS layer is additive to the raw-hex envelope shape.

## Build

All commands run from the `cycles-protocol-service/` directory.

```bash
cd cycles-protocol-service

# Full build (compile + unit tests + package)
mvn clean install

# Or use the wrapper script
./build-all.sh
```

The fat JAR is produced at `cycles-protocol-service-api/target/cycles-protocol-service-api-<version>.jar` (where `<version>` is the `revision` property in `cycles-protocol-service/pom.xml` — e.g. `0.1.25.14`).

## Docker Deployment

Two Docker Compose files are provided for different use cases:

| File | Use case | Command |
|------|----------|---------|
| `docker-compose.yml` | **Development** — builds from source inside Docker (multi-stage build, no local Java/Maven needed) | `docker compose up --build` |
| `docker-compose.prod.yml` | **Production / end-user** — pulls pre-built image from GHCR | `docker compose -f docker-compose.prod.yml up` |

Both start Redis 7 and the cycles-server on port 7878.

### Container images

Pre-built images are published to GitHub Container Registry on each release:

```
ghcr.io/runcycles/cycles-server:latest
ghcr.io/runcycles/cycles-server:<version>    # e.g. 0.1.25.14
```

## Production deployment & TLS

The Cycles Server listens on HTTP at port 7878 by design — TLS termination is the responsibility of the ingress layer in front of it. The recommended deployment topology:

```
client → [TLS-terminating reverse proxy] → cycles-server:7878 (HTTP)
                                              ↓
                                     redis:6379 (TCP, password-protected)
```

### TLS termination

Use any TLS-terminating proxy in front of cycles-server. Common choices:

- **nginx** with Let's Encrypt — minimal config, well-documented
- **Caddy** — automatic Let's Encrypt + HTTP/3
- **Traefik** — natural fit for Docker / Kubernetes
- **AWS ALB / GCP Load Balancer / Cloudflare** — managed TLS at the edge

The cycles-server itself doesn't need to know it's behind a proxy. Standard `X-Forwarded-For` / `X-Forwarded-Proto` headers are honored by Spring Boot's defaults.

**Why HTTP at the app layer:** keeps the container minimal (no cert renewal logic in the app), lets ops centralize TLS posture (cipher suites, HSTS, mTLS) at the proxy, and matches how production Spring Boot services are typically deployed.

### Network hardening

| Path | Recommendation |
|---|---|
| **Public → reverse proxy** | TLS 1.2+ only, modern cipher suites, HSTS enabled |
| **Reverse proxy → cycles-server** | Same private network or VPC; not exposed publicly |
| **cycles-server → Redis** | Same private network; Redis password set via `REDIS_PASSWORD` env var; for highest assurance, also enable Redis TLS (Redis 6+ supports it natively) |
| **Admin server (port 7979)** | Internal network only — never expose publicly. The admin server is the management plane (tenants, budgets, API keys); compromise here is total. |

### Required environment variables for production

- `REDIS_PASSWORD` — never run Redis without one in production
- `ADMIN_API_KEY` — the bootstrap admin key for the admin server and cycles-server operational endpoints (32+ random bytes recommended)
- `WEBHOOK_SECRET_ENCRYPTION_KEY` — encrypts webhook subscriber secrets at rest in Redis (32 bytes, base64)
- `EVIDENCE_SERVER_ID` + `EVIDENCE_SIGNING_SIGNER_DID` — (optional) the public [CyclesEvidence](#cyclesevidence) identity; set both to enable evidence, **byte-identical to the `cycles-server-events` values**. `cycles-server` holds only the public half — the private signing key lives in `cycles-server-events`. Leave unset to run without evidence. See the [enablement runbook](https://github.com/runcycles/cycles-server-events/blob/main/docs/evidence-identity-enablement.md).
- `EVIDENCE_SIGNING_KID` — (optional) public JWK `kid` label for `GET /v1/.well-known/cycles-jwks.json`; defaults to the first 16 hex chars of `EVIDENCE_SIGNING_SIGNER_DID`. Not a signing key.
- `EVIDENCE_SIGNING_NBF_MS` — (optional) active key validity start in epoch milliseconds; default `0` is appropriate for a never-rotated key.
- `EVIDENCE_SIGNING_RETIRED_KEYS` — (optional) JSON array of retired public keys for JWKS rotation history (`signer_did`, `kid`, `nbf_ms`, `exp_ms`).

See [Configuration](#configuration) below for the full env-var matrix.

## Testing

The test suite is split into four categories, each gated by a surefire tag or Maven profile so PR CI stays fast while heavier suites run on demand or on a schedule.

```bash
cd cycles-protocol-service

# Unit tests only (no Docker required) — default PR CI feedback loop
mvn test

# Unit + integration tests (requires Docker for Testcontainers Redis)
mvn clean install -Pintegration-tests

# Concurrent load benchmarks (measures throughput + latency percentiles)
mvn test -Pbenchmark

# Property-based concurrent invariant checks (jqwik)
mvn test -pl cycles-protocol-service-api -am -Pproperty-tests
```

### Integration tests

`*IntegrationTest.java` classes use [Testcontainers](https://www.testcontainers.org/) to spin up a Redis instance automatically. Excluded from the default build; enabled via the `-Pintegration-tests` Maven profile.

PR CI includes deterministic Redis resilience contracts: a paused-Redis
transport test, a lost-successful-response replay matrix for all idempotent
write/evaluation endpoints, and frozen legacy Redis-shape fixtures for rolling
upgrades. The lost-response cases discard a completed response instead of
using packet-timing sleeps, then prove byte-identical replay, exactly-once
ledger/evidence/event side effects, and unchanged mismatch behavior.

### Property-based tests

[jqwik](https://jqwik.net/)-driven property tests that force concurrent interleavings and assert system-wide invariants. Four property tests ship today:

| Test | Invariants |
|---|---|
| `BudgetExhaustionConcurrentPropertyTest` | Under REJECT overage policy: no overdraw, no dual-terminal states, no leaked ACTIVE reservations after sweep. |
| `OverdraftConcurrentPropertyTest` | ALLOW_IF_AVAILABLE never creates debt; ALLOW_WITH_OVERDRAFT respects `overdraft_limit`; ledger invariant (`allocated = remaining + spent + reserved + debt`) holds under contention. |
| `ScopeAttributionConcurrentPropertyTest` | Multi-scope spend attribution: `spent[level]` equals `Σ charged_amount` at every level for scope chains of depth 1–6. |
| `AuditLogCompletenessPropertyTest` | 1:1 mutation↔audit-entry on admin-driven releases; dual-index consistency (`audit:logs:_all` + `audit:logs:{tenant}`); required fields including `metadata.actor_type=admin_on_behalf_of`. |

Tagged `@Tag("property-tests")` and excluded from default PR CI. Run locally with `-Pproperty-tests`; a nightly GitHub Actions workflow (`.github/workflows/nightly-property-tests.yml`) runs at 06:00 UTC with deeper coverage.

**Try count is configurable:**

| Mode | Command | Try count | Runtime |
|------|---------|-----------|---------|
| PR-speed default (from `junit-platform.properties`) | `mvn test -Pproperty-tests` | 20 | ~20 s |
| Nightly CI (5× coverage) | `mvn test -Pproperty-tests -Djqwik.tries.default=100` | 100 | ~2 min |
| Manual deep run | `mvn test -Pproperty-tests -Djqwik.tries.default=500` | 500 | ~10 min |

The property annotation deliberately does **not** set `tries` — the count comes from `cycles-protocol-service-api/src/test/resources/junit-platform.properties` (defaults to 20) and can be overridden with `-Djqwik.tries.default=<N>`. An annotation literal would win over the system property and silently ignore the override.

**Reproducing a failure:** jqwik prints a `seed = <number>` line on failure. Pass it back via `-Djqwik.seeds.tries.default=<number>` to replay the exact same interleaving against the fixed code.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | *(empty)* | Redis password |
| `cycles.expiry.interval-ms` | `5000` | Background expiry sweep interval (ms) |
| `JAVA_OPTS` | *(empty)* | JVM options (e.g. `-XX:MaxRAMPercentage=75 -XX:+UseG1GC`) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | *(unset)* | Set to `ecs` or `logstash` for JSON logging in production |
| `ADMIN_API_KEY` | *(empty)* | Admin key for admin-on-behalf-of runtime paths and operational endpoints (`/actuator/prometheus`, aggregate actuator, docs). Production Compose requires it. |
| `CYCLES_METRICS_TENANT_TAG_ENABLED` | `true` | Include tenant labels on custom domain metrics. Production Compose sets this to `false` to avoid tenant-id disclosure and high-cardinality series. |
| `CYCLES_EVENTS_EMIT_THREADS` | `0` | Worker count for non-blocking runtime event emission. `0` uses a CPU-derived default. |
| `CYCLES_EVENTS_EMIT_QUEUE_CAPACITY` | `10000` | Bounded in-process queue for non-blocking runtime event emission. When full, only the event side effect is dropped and logged; API ledger mutations are unchanged. |
| `CYCLES_MAINTENANCE_LEASE_TTL_MS` | `30000` | Redis lease TTL used to coordinate each scheduled maintenance job across server instances. |
| `CYCLES_MAINTENANCE_RENEW_INTERVAL_MS` | `10000` | Lease heartbeat interval. Must be positive and lower than the maintenance lease TTL. |
| `SPRINGDOC_API_DOCS_ENABLED` | `true` | Expose `/api-docs`. Production Compose sets this to `false`; when enabled, access requires `X-Admin-API-Key`. |
| `SPRINGDOC_SWAGGER_UI_ENABLED` | `true` | Expose `/swagger-ui.html`. Production Compose sets this to `false`; when enabled, access requires `X-Admin-API-Key`. |
| `redis.pool.max-total` | `128` | Max Redis connections |
| `redis.pool.max-idle` | `32` | Max idle Redis connections |
| `redis.pool.min-idle` | `16` | Min idle Redis connections |
| `WEBHOOK_SECRET_ENCRYPTION_KEY` | *(empty)* | AES-256-GCM key for webhook signing secret encryption at rest (base64, 32 bytes). Must match admin + events services. Generate: `openssl rand -base64 32` |
| `EVENT_TTL_DAYS` | `90` | Redis TTL for `event:{id}` keys (days) |
| `DELIVERY_TTL_DAYS` | `14` | Redis TTL for `delivery:{id}` keys (days) |
| `EVIDENCE_SERVER_ID` | *(empty)* | Public CyclesEvidence issuer base including `/v1`; set with `EVIDENCE_SIGNING_SIGNER_DID` to emit `cycles_evidence` refs. Must match `cycles-server-events`. |
| `EVIDENCE_SIGNING_SIGNER_DID` | *(empty)* | Public raw Ed25519 key (64 hex) stamped as `signer_did`; must match the events worker signing key public half. |
| `EVIDENCE_SIGNING_KID` | *(empty)* | Optional public JWK `kid` label for the signer JWKS; defaults to the first 16 hex chars of `EVIDENCE_SIGNING_SIGNER_DID`. |
| `EVIDENCE_SIGNING_NBF_MS` | `0` | Active JWKS key validity start, epoch ms inclusive; set to the rotation time when rotating keys. |
| `EVIDENCE_SIGNING_RETIRED_KEYS` | *(empty)* | Optional JSON array of retired public signing keys with bounded validity windows for evidence rotation history. |

### Webhook Event Emission

The runtime server emits events to the shared Redis dispatch queue for:
- `reservation.denied` — reserve or decide returned DENY
- `reservation.commit_overage` — commit actual exceeded reservation estimate
- `reservation.expired` — reservation TTL expired without commit/release (via background sweeper)
- `budget.exhausted` — remaining budget reached 0 after an operation
- `budget.over_limit_entered` — scope entered over-limit state (debt > overdraft_limit or ALLOW_IF_AVAILABLE cap)
- `budget.debt_incurred` — commit/event created debt via ALLOW_WITH_OVERDRAFT

These events are delivered by `cycles-server-events` to webhook subscribers via HTTP POST with HMAC-SHA256 signing. Event emission is non-blocking — failures are logged but never affect the API response.

**If the events service is down:** Events and deliveries accumulate in Redis with TTL (90d/14d). When the events service restarts, deliveries older than 24h are auto-failed. The admin and runtime servers continue operating normally.

In the full-stack production Compose deployment, `WEBHOOK_SECRET_ENCRYPTION_KEY`
is required because admin stores webhook secrets and events decrypts them for
delivery signing. The events worker exposes management endpoints on port `9980`;
its internal worker port `7980` is not published by the production full-stack
file.

## Monitoring

### Health Check

```
GET /actuator/health/readiness
```

Reports application readiness plus the Redis ledger dependency. If Redis is
unreachable, the endpoint reports DOWN so container and orchestrator
healthchecks stop treating the process as ready for traffic. Liveness remains
available at `GET /actuator/health/liveness` and does not include Redis.

### Prometheus Metrics

```
GET /actuator/prometheus
```

Exposes JVM, HTTP, and Spring Boot metrics in Prometheus format. Prometheus is
protected with `X-Admin-API-Key`; configure Prometheus to send that header or
inject it at trusted ingress. Production Compose also disables tenant labels on
custom domain metrics by setting `CYCLES_METRICS_TENANT_TAG_ENABLED=false`.

#### Domain and maintenance metrics (v0.1.25.11+)

In addition to Spring Boot's auto-emitted `http_server_requests_seconds`, the
service exposes domain-level counters under the `cycles_*` namespace for
reservation lifecycle, quarantine, events, and overdraft. Since v0.1.25.56,
`cycles_maintenance_runs_total` and `cycles_maintenance_duration_seconds`
also expose the fixed job/outcome of every scheduled Redis-maintenance tick.
Successful keyed lifecycle and direct-event replays use
`reason=IDEMPOTENT_REPLAY`; mutation-only counters such as overdraft incurred
remain exactly-once.
Operators can alert on denials, overdraft incidence, quarantine, and
maintenance failure without reverse-engineering them from HTTP status codes.

Full metric inventory, tag semantics, ready-to-paste Prometheus alert rules, SLO definitions, and an incident playbook live in [`OPERATIONS.md`](OPERATIONS.md).

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — release notes for downstream consumers (Docker / JAR)
- [`OPERATIONS.md`](OPERATIONS.md) — operator runbook: metrics inventory, alert recipes, SLOs, incident playbook
- [`AUDIT.md`](AUDIT.md) — engineering history and rationale for each release
- [Cycles Documentation](https://runcycles.io) — full docs site
- [Deploy the Full Stack](https://runcycles.io/quickstart/deploying-the-full-cycles-stack) — deployment guide with server setup
- [Server Configuration Reference](https://runcycles.io/configuration/server-configuration-reference-for-cycles) — all server configuration options
- [`cycles-protocol-service/README.md`](cycles-protocol-service/README.md) — core concepts, authentication, error codes, and the Redis data model

