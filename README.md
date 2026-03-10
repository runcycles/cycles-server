# cycles-server

Reference implementation of the [Cycles Budget Authority API](cycles-protocol-v0.yaml) (v0.1.23) — a reservation-based budget control service for AI agents and workflows.

## Quick Start

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
  java -jar cycles-protocol-service-api/target/cycles-protocol-service-api-0.1.23.jar
```

Server starts on **port 7878**. Interactive API docs: http://localhost:7878/swagger-ui.html

## Architecture

```
HTTP client
    │  X-Cycles-API-Key
    ▼
Spring Boot 3.5 (port 7878)
    │  ApiKeyAuthenticationFilter
    │  Controllers → Repository
    ▼
Redis 7+  (Lua scripts for atomicity)
```

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

The fat JAR is produced at `cycles-protocol-service-api/target/cycles-protocol-service-api-0.1.23.jar`.

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

## Documentation

See [`cycles-protocol-service/README.md`](cycles-protocol-service/README.md) for full documentation including core concepts, authentication, error codes, and the Redis data model.
