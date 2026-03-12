#!/usr/bin/env bash
set -euo pipefail

# Cycles Quickstart — starts the full stack and verifies end-to-end budget enforcement.
# Prerequisites: Docker and Docker Compose v2+

echo "=== Cycles Quickstart ==="
echo ""

# 1. Start the full stack (Redis + Cycles Server + Cycles Admin)
echo "[1/6] Starting the full Cycles stack..."
docker compose -f docker-compose.full-stack.prod.yml up -d

# 2. Wait for services
echo "[2/6] Waiting for services to be healthy..."
for port in 7878 7979; do
  until curl -sf "http://localhost:$port/actuator/health" > /dev/null 2>&1; do
    sleep 1
  done
done
echo "       Cycles Server (7878) and Admin Server (7979) are up."

# 3. Create a tenant
echo "[3/6] Creating tenant 'acme-corp'..."
curl -s -X POST http://localhost:7979/v1/admin/tenants \
  -H "Content-Type: application/json" \
  -H "X-Admin-API-Key: admin-bootstrap-key" \
  -d '{"tenant_id": "acme-corp", "name": "Acme Corporation"}' > /dev/null

# 4. Create an API key
echo "[4/6] Creating API key..."
API_KEY=$(curl -s -X POST http://localhost:7979/v1/admin/api-keys \
  -H "Content-Type: application/json" \
  -H "X-Admin-API-Key: admin-bootstrap-key" \
  -d '{
    "tenant_id": "acme-corp",
    "name": "quickstart-key",
    "permissions": ["reservations:create","reservations:commit","reservations:release","reservations:extend","reservations:list","balances:read"]
  }' | grep -o '"key_secret":"[^"]*"' | cut -d'"' -f4)
echo "       Key: $API_KEY"

# 5. Create and fund a budget ($1.00 = 100,000,000 microcents)
echo "[5/6] Creating and funding budget (\$1.00)..."
curl -s -X POST http://localhost:7979/v1/admin/budgets \
  -H "Content-Type: application/json" \
  -H "X-Cycles-API-Key: $API_KEY" \
  -d '{"scope": "tenant:acme-corp", "unit": "USD_MICROCENTS", "allocated": {"amount": 100000000, "unit": "USD_MICROCENTS"}}' > /dev/null

curl -s -X POST "http://localhost:7979/v1/admin/budgets/tenant:acme-corp/USD_MICROCENTS/fund" \
  -H "Content-Type: application/json" \
  -H "X-Cycles-API-Key: $API_KEY" \
  -d '{"operation": "CREDIT", "amount": {"amount": 100000000, "unit": "USD_MICROCENTS"}, "idempotency_key": "qs-fund-001"}' > /dev/null

# 6. Verify: reserve → commit → check balance
echo "[6/6] Verifying reserve → commit → balance..."

RESERVATION_ID=$(curl -s -X POST http://localhost:7878/v1/reservations \
  -H "Content-Type: application/json" \
  -H "X-Cycles-API-Key: $API_KEY" \
  -d '{
    "idempotency_key": "qs-reserve-001",
    "subject": {"tenant": "acme-corp"},
    "action": {"kind": "llm.completion", "name": "openai:gpt-4o"},
    "estimate": {"amount": 500000, "unit": "USD_MICROCENTS"},
    "ttl_ms": 30000
  }' | grep -o '"reservation_id":"[^"]*"' | cut -d'"' -f4)

COMMIT_STATUS=$(curl -s -X POST "http://localhost:7878/v1/reservations/$RESERVATION_ID/commit" \
  -H "Content-Type: application/json" \
  -H "X-Cycles-API-Key: $API_KEY" \
  -d '{"idempotency_key": "qs-commit-001", "actual": {"amount": 350000, "unit": "USD_MICROCENTS"}}' | grep -o '"status":"[^"]*"' | cut -d'"' -f4)

echo ""
if [ "$COMMIT_STATUS" = "COMMITTED" ]; then
  echo "=== Success! Cycles is running. ==="
else
  echo "=== Verification failed. Check the logs with: docker compose -f docker-compose.full-stack.prod.yml logs ==="
  exit 1
fi

echo ""
echo "  Runtime server:  http://localhost:7878/swagger-ui.html"
echo "  Admin server:    http://localhost:7979/swagger-ui.html"
echo "  API key:         $API_KEY"
echo ""
echo "Next steps:"
echo "  - Explore the API:  curl -s http://localhost:7878/v1/balances?tenant=acme-corp -H 'X-Cycles-API-Key: $API_KEY'"
echo "  - Read the docs:    https://runcycles.github.io/docs/quickstart/deploying-the-full-cycles-stack"
echo "  - Stop the stack:   docker compose -f docker-compose.full-stack.prod.yml down"
