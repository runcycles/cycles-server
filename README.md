[![CI](https://github.com/runcycles/cycles-server/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-server/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-95%25+-brightgreen)](https://github.com/runcycles/cycles-server/actions)

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

Server starts on **port 7878**. Interactive API docs: http://localhost:7878/swagger-ui.html

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

Server starts on **port 7878**. Interactive API docs: http://localhost:7878/swagger-ui.html

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

All endpoints require `X-Cycles-API-Key` header authentication.

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
- `ADMIN_API_KEY` — the bootstrap admin key for the admin server (32+ random bytes recommended)
- `WEBHOOK_SECRET_ENCRYPTION_KEY` — encrypts webhook subscriber secrets at rest in Redis (32 bytes, base64)

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
| PR-speed default (from `jqwik.properties`) | `mvn test -Pproperty-tests` | 20 | ~20 s |
| Nightly CI (5× coverage) | `mvn test -Pproperty-tests -Djqwik.defaultTries=100` | 100 | ~2 min |
| Manual deep run | `mvn test -Pproperty-tests -Djqwik.defaultTries=500` | 500 | ~10 min |

The property annotation deliberately does **not** set `tries` — the count comes from `cycles-protocol-service-api/src/test/resources/jqwik.properties` (defaults to 20) and can be overridden with `-Djqwik.defaultTries=<N>`. An annotation literal would win over the system property and silently ignore the override.

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
| `redis.pool.max-total` | `128` | Max Redis connections |
| `redis.pool.max-idle` | `32` | Max idle Redis connections |
| `redis.pool.min-idle` | `16` | Min idle Redis connections |
| `WEBHOOK_SECRET_ENCRYPTION_KEY` | *(empty)* | AES-256-GCM key for webhook signing secret encryption at rest (base64, 32 bytes). Must match admin + events services. Generate: `openssl rand -base64 32` |
| `EVENT_TTL_DAYS` | `90` | Redis TTL for `event:{id}` keys (days) |
| `DELIVERY_TTL_DAYS` | `14` | Redis TTL for `delivery:{id}` keys (days) |

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

## Monitoring

### Health Check

```
GET /actuator/health
```

### Prometheus Metrics

```
GET /actuator/prometheus
```

Exposes JVM, HTTP, and Spring Boot metrics in Prometheus format. Both endpoints are unauthenticated. Configure your Prometheus scrape target to `http://<host>:7878/actuator/prometheus`.

#### Domain counters (v0.1.25.11+)

In addition to Spring Boot's auto-emitted `http_server_requests_seconds`, the service exposes seven domain-level counters under the `cycles_*` namespace (reserve / commit / release / extend / expired / events / overdraft). Operators can alert on denial rates, overdraft incidence, and per-tenant activity without reverse-engineering it from HTTP status codes.

Full metric inventory, tag semantics, ready-to-paste Prometheus alert rules, SLO definitions, and an incident playbook live in [`OPERATIONS.md`](OPERATIONS.md).

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — release notes for downstream consumers (Docker / JAR)
- [`OPERATIONS.md`](OPERATIONS.md) — operator runbook: metrics inventory, alert recipes, SLOs, incident playbook
- [`AUDIT.md`](AUDIT.md) — engineering history and rationale for each release
- [Cycles Documentation](https://runcycles.io) — full docs site
- [Deploy the Full Stack](https://runcycles.io/quickstart/deploying-the-full-cycles-stack) — deployment guide with server setup
- [Server Configuration Reference](https://runcycles.io/configuration/server-configuration-reference-for-cycles) — all server configuration options
- [`cycles-protocol-service/README.md`](cycles-protocol-service/README.md) — core concepts, authentication, error codes, and the Redis data model
