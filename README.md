[![CI](https://github.com/runcycles/cycles-server/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-server/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-95%25+-brightgreen)](https://github.com/runcycles/cycles-server/actions)

# Runcycles Server

Reference implementation of the [Cycles Budget Authority API](https://github.com/runcycles/cycles-protocol/blob/main/cycles-protocol-v0.yaml) (v0.1.25) — a reservation-based budget control service for AI agents and workflows.

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
  java -jar cycles-protocol-service-api/target/cycles-protocol-service-api-0.1.25.6.jar
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

The fat JAR is produced at `cycles-protocol-service-api/target/cycles-protocol-service-api-0.1.25.6.jar`.

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
ghcr.io/runcycles/cycles-server:<version>    # e.g. 0.1.25.7
```

## Testing

```bash
cd cycles-protocol-service

# Unit tests only (no Docker required)
mvn test

# Unit + integration tests (requires Docker for Testcontainers Redis)
mvn clean install -Pintegration-tests
```

Integration tests (`*IntegrationTest.java`) use [Testcontainers](https://www.testcontainers.org/) to spin up a Redis instance automatically. They are excluded from the default build and enabled via the `-Pintegration-tests` Maven profile.

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

## Documentation

- [Cycles Documentation](https://runcycles.io) — full docs site
- [Deploy the Full Stack](https://runcycles.io/quickstart/deploying-the-full-cycles-stack) — deployment guide with server setup
- [Server Configuration Reference](https://runcycles.io/configuration/server-configuration-reference-for-cycles) — all server configuration options
- [`cycles-protocol-service/README.md`](cycles-protocol-service/README.md) — core concepts, authentication, error codes, and the Redis data model
