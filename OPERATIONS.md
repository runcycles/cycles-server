# Operations guide

Operator-facing runbook for running `cycles-server` in production. Covers
metrics, alerting recipes, SLOs, and an incident playbook.

Assumes you are already deploying via the published Docker image
(`ghcr.io/runcycles/cycles-server:<version>`) with Prometheus scraping
`/actuator/prometheus`. If you haven't set that up yet, see the Monitoring
section of [`README.md`](README.md) first.

## Table of contents

1. [Metrics inventory](#metrics-inventory)
2. [Alerts worth paging on](#alerts-worth-paging-on)
3. [SLO definitions](#slo-definitions)
4. [Dashboards](#dashboards)
5. [Incident playbook](#incident-playbook)
6. [Configuration tuning](#configuration-tuning)

---

## Metrics inventory

All domain metrics live under the `cycles_*` namespace. Spring Boot
auto-metrics (`http_server_requests_seconds`, `jvm_*`, `process_*`,
`logback_events_total`) are also emitted and worth scraping.

### Reservation lifecycle

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_reservations_reserve_total` | `tenant`, `decision`, `reason`, `overage_policy` | Every `POST /v1/reservations` outcome. `decision=ALLOW`/`ALLOW_WITH_CAPS`/`DENY`; `reason=OK`/`IDEMPOTENT_REPLAY`/error code. |
| `cycles_reservations_commit_total` | `tenant`, `decision`, `reason`, `overage_policy` | Every `POST /v1/reservations/{id}/commit` outcome. `decision=COMMITTED` on success. |
| `cycles_reservations_release_total` | `tenant`, `actor_type`, `decision`, `reason` | Every release. `actor_type=tenant` vs `admin_on_behalf_of` (v0.1.25.8+). |
| `cycles_reservations_extend_total` | `tenant`, `decision`, `reason` | Every extend attempt. `reason=RESERVATION_EXPIRED` when past `expires_at` (no grace honoured for extend). |
| `cycles_reservations_expired_total` | `tenant` | Once per ACTIVE→EXPIRED transition. Grace-period skips and already-finalised candidates do **not** increment. |

### Events

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_events_total` | `tenant`, `decision`, `reason`, `overage_policy` | Every `POST /v1/events` outcome. Events are direct-debit — `decision=APPLIED` on success. |

### Overdraft

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_overdraft_incurred_total` | `tenant` | Every commit or event that **actually** accrued non-zero `debt`. Unit-free — the amount is in Redis; this counter is "how often". |

### Reason codes

Tag values for `reason` come directly from `Enums.ErrorCode`:
`OK`, `IDEMPOTENT_REPLAY`, `BUDGET_EXCEEDED`, `OVERDRAFT_LIMIT_EXCEEDED`,
`DEBT_OUTSTANDING`, `BUDGET_FROZEN`, `BUDGET_CLOSED`, `BUDGET_NOT_FOUND`,
`RESERVATION_FINALIZED`, `RESERVATION_EXPIRED`, `IDEMPOTENCY_MISMATCH`,
`UNIT_MISMATCH`, `NOT_FOUND`, `FORBIDDEN`, `INTERNAL_ERROR`.

### Tag-cardinality control

The `tenant` tag is the only high-card dimension. For deployments with
thousands of tenants, disable it:

```properties
cycles.metrics.tenant-tag.enabled=false
```

Per-tenant drill-down is lost but the time-series count drops to
O(decision × reason × overage_policy) — bounded and small.

---

## Alerts worth paging on

Copy-paste these into your `prometheus.rules.yml` and tune thresholds to
your actual traffic. The spirit is "wake someone up only when the system
is behaving unlike itself", not "alert on every error."

### Availability

```yaml
- alert: CyclesServerDown
  expr: up{job="cycles-server"} == 0
  for: 2m
  labels: {severity: page}
  annotations:
    summary: cycles-server is down
    runbook: https://github.com/runcycles/cycles-server/blob/main/OPERATIONS.md#incident-playbook

- alert: CyclesServerErrorRateHigh
  # >5% of requests returning 5xx over 5 minutes = actual server problem,
  # not budget denials (those are 409 and not counted here).
  expr: |
    sum(rate(http_server_requests_seconds_count{job="cycles-server",status=~"5.."}[5m]))
      / sum(rate(http_server_requests_seconds_count{job="cycles-server"}[5m]))
    > 0.05
  for: 5m
  labels: {severity: page}
```

### Budget denial anomalies

Denials are expected — they're the service doing its job. What you care
about is *unexpected* denial patterns that suggest misconfiguration.

```yaml
- alert: UnusualBudgetExceededRate
  # Sudden spike in BUDGET_EXCEEDED denials on a tenant that normally has
  # headroom. Compare last 5m rate to the 1h baseline.
  expr: |
    (
      sum by (tenant) (rate(cycles_reservations_reserve_total{decision="DENY",reason="BUDGET_EXCEEDED"}[5m]))
      /
      sum by (tenant) (rate(cycles_reservations_reserve_total{decision="DENY",reason="BUDGET_EXCEEDED"}[1h] offset 1h))
    ) > 3
  for: 10m
  labels: {severity: ticket}
  annotations:
    summary: "{{ $labels.tenant }}: BUDGET_EXCEEDED rate 3x baseline"
    description: "Denial rate spiked — check if the tenant's budget was resized or if an agent is misbehaving."

- alert: DebtOutstandingDenials
  # Any DEBT_OUTSTANDING denial means a tenant has unpaid debt blocking
  # new reservations. Should be rare; investigate when it happens.
  expr: |
    sum by (tenant) (rate(cycles_reservations_reserve_total{reason="DEBT_OUTSTANDING"}[10m])) > 0
  for: 5m
  labels: {severity: ticket}
```

### Overdraft incidence

```yaml
- alert: OverdraftRateHigh
  # Sustained overdraft on any tenant — means the tenant is consistently
  # over-estimating or the budget is sized wrong.
  expr: |
    sum by (tenant) (rate(cycles_overdraft_incurred_total[15m])) > 0.1
  for: 15m
  labels: {severity: ticket}
  annotations:
    summary: "{{ $labels.tenant }}: overdraft rate > 0.1/s for 15m"
    description: "Tenant is regularly going into overdraft — review estimate accuracy or allocated budget."
```

### Compliance (admin-driven release visibility)

```yaml
- alert: AdminReleaseActivity
  # Any admin-on-behalf-of release is a privileged action and should be
  # noticed by the team. Not a page — just a record for the compliance
  # channel.
  expr: |
    sum(increase(cycles_reservations_release_total{actor_type="admin_on_behalf_of"}[1h])) > 0
  for: 1m
  labels: {severity: info}
  annotations:
    summary: "admin-on-behalf-of release(s) in the last hour"
    description: "Cross-check against the audit log at audit:logs:_all."
```

### Redis connectivity (infers from operation errors)

```yaml
- alert: CyclesServerInternalErrors
  # INTERNAL_ERROR on any reservation path usually means Redis is
  # unavailable or a Lua script failed. Fast page.
  expr: |
    sum(rate(cycles_reservations_reserve_total{reason="INTERNAL_ERROR"}[5m]))
    + sum(rate(cycles_reservations_commit_total{reason="INTERNAL_ERROR"}[5m]))
    > 0.01
  for: 5m
  labels: {severity: page}
```

### Latency

```yaml
- alert: CyclesServerLatencyP99
  expr: |
    histogram_quantile(0.99, sum by (le, uri) (
      rate(http_server_requests_seconds_bucket{job="cycles-server",uri=~"/v1/reservations.*"}[5m])
    )) > 0.2
  for: 10m
  labels: {severity: ticket}
  annotations:
    summary: "cycles-server p99 latency > 200ms on reservations path"
```

---

## SLO definitions

Starting point — adjust to your SLA with customers.

| SLO | Target | Source |
|---|---|---|
| Availability (2xx + expected 4xx / total) | 99.9% over 30d | `http_server_requests_seconds_count` (exclude 5xx) |
| Reserve p99 latency | ≤ 50ms | `http_server_requests_seconds` histogram at `uri=/v1/reservations, method=POST` |
| Commit p99 latency | ≤ 50ms | same, at `uri=/v1/reservations/{reservation_id}/commit` |
| Event p99 latency | ≤ 50ms | same, at `uri=/v1/events` |
| Error budget | 0.1% 5xx / 30d | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[30d]))` |

**Note on 409 denials:** a `409 BUDGET_EXCEEDED` is *correct* behaviour —
the service is doing its job. It must **not** count against an
availability SLO. Denials are tracked via the `cycles_reservations_*`
counters with `decision=DENY`, not via HTTP 5xx.

---

## Dashboards

We don't ship a Grafana dashboard JSON yet, but a minimum-viable dashboard
should have:

**Row 1 — Request rates (by endpoint + decision):**
```promql
sum by (decision) (rate(cycles_reservations_reserve_total[1m]))
sum by (decision) (rate(cycles_reservations_commit_total[1m]))
sum by (decision) (rate(cycles_events_total[1m]))
```

**Row 2 — Denial breakdown:**
```promql
sum by (reason) (rate(cycles_reservations_reserve_total{decision="DENY"}[5m]))
sum by (reason) (rate(cycles_reservations_commit_total{decision="DENY"}[5m]))
```

**Row 3 — Overdraft / compliance:**
```promql
sum by (tenant) (rate(cycles_overdraft_incurred_total[5m]))
sum(rate(cycles_reservations_release_total{actor_type="admin_on_behalf_of"}[5m]))
```

**Row 4 — Expiry sweep health:**
```promql
sum by (tenant) (rate(cycles_reservations_expired_total[5m]))
```

**Row 5 — Latency (from Spring Boot auto-metrics):**
```promql
histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket[1m])))
```

Contributions of a packaged dashboard JSON are welcome.

---

## Incident playbook

### Symptom: high 5xx rate

1. Check Redis: `redis-cli -h <host> PING`. If that hangs or fails, Redis
   is the problem — skip to "Redis unavailable."
2. Check the application logs for `Unhandled exception:` lines from
   `GlobalExceptionHandler`. The exception class tells you where.
3. Check `cycles_reservations_reserve_total{reason="INTERNAL_ERROR"}` —
   if this is spiking, it's a script failure, not a transient network
   issue.

### Symptom: Redis unavailable

The service is designed to return structured 5xx when Redis is down
(enforced by `RedisDisconnectResilienceIntegrationTest`).

1. Confirm Redis health: `redis-cli PING`, check disk/memory on the
   Redis host.
2. Check `jvm_memory_used_bytes` on the cycles-server side — if memory
   is growing during the outage, the Jedis pool may be leaking
   connections.
3. Once Redis recovers, the service resumes without restart. If it
   doesn't, `kill -15 <pid>` and let your orchestrator restart it.

### Symptom: sudden spike in `BUDGET_EXCEEDED` for a specific tenant

1. Query the balance: `GET /v1/balances?tenant=<tenant>`. Look at
   `allocated` vs `spent + reserved + debt`.
2. If the budget is exhausted *unexpectedly*:
   - Check for a runaway agent (commit rate in the last 15 min on that
     tenant).
   - Check `cycles_overdraft_incurred_total` — if it spiked, the tenant
     is under `ALLOW_WITH_OVERDRAFT` and hit the overdraft limit.
3. Resolution: either the tenant needs more allocated budget (admin
   operation) or the upstream agent is buggy.

### Symptom: `DEBT_OUTSTANDING` blocking new reservations

Spec: a scope with `debt > 0` and `overdraft_limit == 0` blocks all new
reservations. Either:
- Reset the debt by crediting back via admin (operator decision — the
  spec intentionally doesn't provide an automatic unblock), or
- Increase `overdraft_limit` on that budget so outstanding debt is
  allowed.

### Symptom: admin-on-behalf-of release in the compliance channel

1. Grab the `request_id` from your admin key's access log.
2. Query `audit:logs:_all` in Redis for the same request_id:
   ```bash
   redis-cli ZRANGEBYSCORE audit:logs:_all -inf +inf LIMIT 0 100
   ```
3. For each `log_id` the zset returns, fetch the payload:
   ```bash
   redis-cli GET audit:log:<log_id>
   ```
4. `metadata.reason` is the free-text justification the admin supplied
   (CR/LF-stripped at write time).

---

## Configuration tuning

All configurable via `application.properties` or environment variables.
Defaults are sensible for most deployments; tune these when the defaults
don't fit.

| Property | Default | When to change |
|---|---|---|
| `cycles.metrics.tenant-tag.enabled` | `true` | Set `false` if you have 1000s of tenants and your Prometheus is stressed. |
| `cycles.expiry.interval-ms` | `5000` | Lower for tighter sweep cadence on short-TTL reservations; raise if sweep work is measurable on Redis CPU. |
| `cycles.expiry.initial-delay-ms` | `5000` | Mostly a test knob. Leave. |
| `cycles.tenant-config.cache-ttl-ms` | `60000` | Lower if admin tenant config changes need to take effect faster than 60s. |
| `admin.api-key` | (empty) | Set to a fixed-length secret to enable the admin-on-behalf-of endpoint (v0.1.25.8+). Leave empty to disable. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Add more actuator endpoints if you need them, but `prometheus` is the one ops cares about. |

## Getting help

- Bug reports / feature requests:
  https://github.com/runcycles/cycles-server/issues
- Release notes: [`CHANGELOG.md`](CHANGELOG.md)
- Engineering history & rationale: [`AUDIT.md`](AUDIT.md)
