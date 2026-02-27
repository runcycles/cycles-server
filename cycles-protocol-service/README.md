# Cycles Protocol Server v0.1.23

Complete implementation of Cycles Budget Authority API with overdraft/debt support.

## Features

- ✅ Reserve/Commit/Release/Extend operations
- ✅ Overdraft support with ALLOW_WITH_OVERDRAFT policy
- ✅ Debt tracking and over-limit state management
- ✅ Atomic multi-scope reservations
- ✅ Idempotent commits and releases
- ✅ Balance queries with debt visibility
- ✅ All Lua scripts for atomic operations

## Quick Start

```bash
./build-all.sh
docker run -d -p 6379:6379 redis:7-alpine
./init-budgets.sh
java -jar cycles-protocol-api/target/cycles-protocol-api-0.1.23.jar
```

## API Documentation

http://localhost:8080/swagger-ui.html

## Endpoints

- POST   /v1/reservations - Create reservation
- POST   /v1/reservations/{id}/commit - Commit spend
- POST   /v1/reservations/{id}/release - Release reservation
- POST   /v1/reservations/{id}/extend - Extend TTL
- GET    /v1/reservations - List reservations
- GET    /v1/balances - Query balances

## Version 0.1.23 Updates

All components updated to v0.1.23 with version annotations in Java files.
