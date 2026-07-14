# Operations guide

Operator-facing runbook for running `cycles-server` in production. Covers
metrics, alerting recipes, SLOs, and an incident playbook.

Assumes you are already deploying via the published Docker image
(`ghcr.io/runcycles/cycles-server:<version>`) with Prometheus scraping
`/actuator/prometheus` using `X-Admin-API-Key`. If you haven't set that up yet,
see the Monitoring section of [`README.md`](README.md) first.

## Table of contents

1. [Metrics inventory](#metrics-inventory)
2. [Alerts worth paging on](#alerts-worth-paging-on)
3. [SLO definitions](#slo-definitions)
4. [Dashboards](#dashboards)
5. [Incident playbook](#incident-playbook)
6. [Correlation and tracing](#correlation-and-tracing)
7. [Configuration tuning](#configuration-tuning)

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
| `cycles_reservations_quarantined_total` | `tenant`, `reason` | Corrupt ACTIVE reservations removed from the bounded expiry queue without changing ledger state. Reasons are bounded: `INVALID_EXPIRY`, `INVALID_ESTIMATE`, `MISSING_SCOPES`, or `MALFORMED_SCOPES`. Any increase requires reconciliation of the reservation hash and its held budget. |

### Events

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_events_total` | `tenant`, `decision`, `reason`, `overage_policy` | Every repository-handled `POST /v1/events` outcome. Events are direct-debit — `decision=APPLIED`, with `reason=OK` on a fresh success and `reason=IDEMPOTENT_REPLAY` on a successful replay. |

### Overdraft

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_overdraft_incurred_total` | `tenant` | Every commit or event that **actually** accrued non-zero `debt`. Unit-free — the amount is in Redis; this counter is "how often". |

### Scheduled Redis maintenance

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_maintenance_runs_total` | `job`, `outcome` | One result per scheduled tick. Jobs are `reservation_expiry`, `audit_retention`, `event_retention`, `created_at_repair`, and `created_at_sweep`; outcomes are a fixed enum. `skipped_locked` is expected when multiple replicas share Redis. |
| `cycles_maintenance_duration_seconds` | `job`, `outcome` | End-to-end time including lease acquisition/release. Use the `success` series for normal runtime and failure outcomes for incident timing. |

Each job uses an owner-token Redis lease with a heartbeat. Exactly one healthy
replica normally performs a tick; another replica records `skipped_locked`.
Lease loss does not cancel an already-running operation, so the underlying
jobs remain idempotent. During a Redis outage, coordination is best-effort and
the runner records `lease_error` without failing the scheduling thread.
Scheduled-path failures now share the structured
`Scheduled Redis maintenance failed: job=<job>` log message. Replace alerts
matching the former job-specific failure strings with the
`CyclesMaintenanceFailures` metric alert below.

### Reason codes

Tag values for `reason` come from `Enums.ErrorCode` (the same enum the
HTTP error body uses) plus two success-path sentinels (`OK` and
`IDEMPOTENT_REPLAY`). The operationally-observable set on the domain
counters is:

- Success: `OK`, `IDEMPOTENT_REPLAY` (reserve, commit, release, and direct-event
  replays use `IDEMPOTENT_REPLAY` without re-counting mutation-only side effects)
- Budget denials: `BUDGET_EXCEEDED`, `OVERDRAFT_LIMIT_EXCEEDED`,
  `DEBT_OUTSTANDING`
- Budget state: `BUDGET_FROZEN`, `BUDGET_CLOSED`
- Tenant state: `TENANT_CLOSED` (owning tenant closed — governance
  Rule 2 terminal-owner guard on reservation create/commit/release/extend)
- Reservation state: `RESERVATION_EXPIRED`, `RESERVATION_FINALIZED`
- Request issues: `IDEMPOTENCY_MISMATCH`, `UNIT_MISMATCH`,
  `MAX_EXTENSIONS_EXCEEDED`, `NOT_FOUND`
- Unexpected: `INTERNAL_ERROR` (Redis unavailable, Lua script failure)

Auth-layer errors (`UNAUTHORIZED`, `FORBIDDEN`, `INVALID_REQUEST`) are
rejected by filters before they reach the repository, so they don't
appear on the domain counters — they only show up on the Spring Boot
`http_server_requests_seconds` timer at the corresponding `status` label.

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

- alert: CyclesMaintenanceFailures
  expr: |
    sum by (job, outcome) (
      increase(cycles_maintenance_runs_total{outcome=~"failed|lease_error|lease_lost"}[15m])
    ) > 0
  for: 5m
  labels: {severity: warning}
  annotations:
    summary: scheduled Redis maintenance is failing or losing its lease
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

**Prerequisite:** Spring Boot doesn't emit percentile histogram buckets by
default. To make `histogram_quantile` queries return real values, set
this on the cycles-server side:

```properties
# application.properties — opt into histogram buckets for the HTTP timer
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

Without this setting, `http_server_requests_seconds_bucket` has no
`le`-labelled series and the alert will silently evaluate to `NaN`
(not a false negative — `NaN > 0.2` is `false`). A mean-latency
fallback alert works without any configuration:

```yaml
- alert: CyclesServerMeanLatency
  # Mean latency = sum / count. Works without histogram buckets.
  # 50ms mean on a write path suggests something's wrong (typical is <10ms).
  expr: |
    (sum by (uri) (rate(http_server_requests_seconds_sum{job="cycles-server",uri=~"/v1/reservations.*"}[5m])))
    / (sum by (uri) (rate(http_server_requests_seconds_count{job="cycles-server",uri=~"/v1/reservations.*"}[5m])))
    > 0.05
  for: 10m
  labels: {severity: ticket}
  annotations:
    summary: "cycles-server mean latency > 50ms on {{ $labels.uri }}"
```

After enabling the histogram property, the p99 alert is:

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
| Reserve p99 latency | ≤ 50ms | `http_server_requests_seconds` histogram at `uri=/v1/reservations, method=POST` (requires `percentiles-histogram` setting — see Latency alert above) |
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
`/actuator/health/readiness` includes a Redis `PING` health contributor, so
container/orchestrator readiness healthchecks should flip DOWN during the same
outage.

For Kubernetes, wire Redis-dependent health to readiness, not liveness:
`/actuator/health/readiness` includes Redis and `/actuator/health/liveness`
stays process-only. A Redis outage should drain traffic rather than
restart-storm otherwise healthy API pods. The Redis health check uses the
application `JedisPool`, so DOWN can be delayed by the pool wait/socket timeout
during a saturated or partitioned Redis incident.

Only liveness/readiness are anonymous. Aggregate `/actuator/health`,
`/actuator/info`, `/actuator/prometheus`, API docs, and Swagger require
`X-Admin-API-Key`; configure Prometheus scrapes or trusted ingress to supply it.

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

## Correlation and tracing

Every response carries two correlation identifiers (v0.1.25.14+):

| Header | Shape | Grain |
|---|---|---|
| `X-Request-Id` | UUIDv4 | One HTTP request |
| `X-Cycles-Trace-Id` | 32 lowercase hex (W3C Trace Context) | One logical operation |

### Client-supplied trace_id

Clients can set the trace_id themselves by sending either:

- `traceparent: 00-{trace_id}-{span_id}-{trace_flags}` — standard W3C Trace Context header (OpenTelemetry-native). Takes precedence.
- `X-Cycles-Trace-Id: {trace_id}` — flat 32-hex, for clients not using OpenTelemetry.

If both are present and disagree, `traceparent` wins. Malformed values are silently ignored (the server never 400s for a bad correlation header).

### Propagation

The trace_id is attached to:

- The response (`X-Cycles-Trace-Id` header).
- `ErrorResponse` bodies (`trace_id` field).
- Emitted events (`trace_id` field in the event body and on the `WebhookDelivery` row).
- Audit-log entries for admin-driven releases (`trace_id` field in `AuditLogEntry`).

Sweeper-generated events (`reservation.expired`) get a fresh trace_id per sweep batch — they have no originating HTTP request.

### Log correlation

Every log line produced during a request carries both `requestId` and `traceId` MDC keys. Grep a trace_id across all log lines to see everything that happened in one logical operation:

```bash
grep '4bf92f3577b34da6a3ce929d0e0e4736' server.log
```

### Webhook correlation

Outbound webhook deliveries (from `cycles-server-events`) carry the same `trace_id` in the `X-Cycles-Trace-Id` and `traceparent` headers, plus the `trace_id` field in the event body. Subscribers can correlate their downstream processing back to the originating Cycles request. This server persists three correlation fields on the `WebhookDelivery` Redis row so the events sidecar can lift them straight into the outbound HTTP request:

- `trace_id` — the trace identifier.
- `trace_flags` — the W3C trace-flags byte. When an inbound request carried a valid `traceparent`, this is the inbound byte (preserves sampling decision); otherwise `01`.
- `traceparent_inbound_valid` — boolean. Tells the sidecar whether to preserve the above `trace_flags` (`true`) or default to `01` (`false`).

Wire-up of the outbound HTTP headers themselves happens in the `cycles-server-events` repo.

---

## Configuration tuning

All configurable via `application.properties` or environment variables.
Defaults are sensible for most deployments; tune these when the defaults
don't fit.

| Property | Default | When to change |
|---|---|---|
| `cycles.metrics.tenant-tag.enabled` | `true` | Set `false` for internet-adjacent or high-tenant-count production Prometheus targets. Production Compose sets `CYCLES_METRICS_TENANT_TAG_ENABLED=false` to avoid tenant-id disclosure and high-cardinality series. |
| `cycles.events.emit.threads` | `0` | Non-blocking runtime event-emission worker count. `0` uses a CPU-derived default. Raise only if event persistence is healthy but emission backlog is observed. |
| `cycles.events.emit.queue-capacity` | `10000` | Bounded in-process queue for non-blocking runtime event emission. When full, the API drops only the event side effect and logs a structured warning instead of growing heap without limit. |
| `cycles.expiry.interval-ms` | `5000` | Lower for tighter sweep cadence on short-TTL reservations; raise if sweep work is measurable on Redis CPU. |
| `cycles.expiry.initial-delay-ms` | `5000` | Mostly a test knob. Leave. |
| `spring.task.scheduling.pool.size` | `4` | Bounded workers for expiry plus audit, event, and index maintenance. Keep at least `2`; production can override with `CYCLES_SCHEDULER_POOL_SIZE`. |
| `cycles.maintenance.lease-ttl-ms` | `30000` | Per-job Redis coordination lease. Raise only when Redis/client pauses can approach 30 seconds; override with `CYCLES_MAINTENANCE_LEASE_TTL_MS`. |
| `cycles.maintenance.renew-interval-ms` | `10000` | Heartbeat for active maintenance leases. Must stay below the TTL; a value near one-third of the TTL leaves room for transient delay. Override with `CYCLES_MAINTENANCE_RENEW_INTERVAL_MS`. |
| `cycles.reservation-index.created-at.enabled` | `false` | Enables backfill and indexed reads for `created_at_ms` sorting. Both production Compose files default it off. In every topology, enable only after every writer runs v0.1.25.54+. |
| `cycles.reservation-index.created-at.repair-interval-ms` | `300000` | Minimum delay between demand-triggered reconciliation attempts. Lower only when a large initial backfill completes comfortably and faster repair is operationally necessary. |
| `cycles.reservation-index.created-at.initial-delay-ms` | `5000` | Delay before the first enabled reconciliation after startup. Leave unless startup Redis load needs staggering. |
| `cycles.reservation-index.created-at.failure-backoff-ms` | `3600000` | Backoff after a reconciliation completes with malformed tenant rows. This prevents permanent corruption from driving a full-keyspace scan every five minutes; override with `RESERVATION_CREATED_AT_INDEX_FAILURE_BACKOFF_MS`. |
| `cycles.reservation-index.created-at.sweep-cron` | `0 45 3 * * *` | Nightly stale-member and score-drift cleanup. Override with `RESERVATION_CREATED_AT_INDEX_SWEEP_CRON` to move the Redis maintenance window. |
| `cycles.tenant-config.cache-ttl-ms` | `60000` | Lower if admin tenant config changes need to take effect faster than 60s. |
| `admin.api-key` | (empty) | Set to a fixed-length secret to enable the admin-on-behalf-of endpoint (v0.1.25.8+) and operational endpoint access. Production Compose requires it. |
| `audit.retention.days` | `400` | Retention for runtime-written audit rows (v0.1.25.15+). Default matches admin's `audit.retention.authenticated.days` — SOC2 Type II 12-month lookback + 1-month auditor-lag buffer. Set `0` for indefinite retention (legal hold, archive-store deployments). |
| `audit.sweep.cron` | `0 0 3 * * *` | Daily cron for pruning stale ZSET index pointers (v0.1.25.15+). Lower cadence if audit write volume is very high; leave as-is otherwise. Skipped when `audit.retention.days=0`. |
| `events.retention.sweep-cron` | `0 30 3 * * *` | Daily cleanup of stale `events:*` and `deliveries:*` ZSET pointers after their backing rows expire. Override with `EVENT_RETENTION_SWEEP_CRON` when Redis maintenance needs a different window. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Add more actuator endpoints if you need them, but `prometheus` is the one ops cares about. |
| `springdoc.api-docs.enabled` | `true` | Set `false` in production unless API docs are intentionally exposed to callers with `X-Admin-API-Key`. Production Compose disables it. |
| `springdoc.swagger-ui.enabled` | `true` | Set `false` in production unless Swagger UI is intentionally exposed to callers with `X-Admin-API-Key`. Production Compose disables it. |

In `docker-compose.full-stack.prod.yml`, `WEBHOOK_SECRET_ENCRYPTION_KEY` is
required because admin writes webhook signing secrets and events decrypts them
for delivery signing. The events worker is exposed on management port `9980`;
do not publish its internal worker port `7980` on ingress.

## Reservation list sorting (v0.1.25.12+)

`GET /v1/reservations` accepts `sort_by` (one of `reservation_id`,
`tenant`, `scope_path`, `status`, `reserved`, `created_at_ms`,
`expires_at_ms`) and `sort_dir` (`asc` or `desc`, default `desc`).

Since v0.1.25.54, the default `created_at_ms` sort can use a per-tenant
ZSET and bounded candidate hydration. Reservation hashes remain authoritative:
the reader atomically checks READY metadata and `expected_count == ZCARD`
before and after indexed work, and falls back to the complete global SCAN on
any uncertainty. The other six sort keys still use full-SCAN + in-memory sort
per page.

For a multi-pod rollout, keep
`RESERVATION_CREATED_AT_INDEX_ENABLED=false` while deploying v0.1.25.54 to
every writer. After the old writers are gone, set it to `true` and restart or
redeploy the readers. Enabling it starts a restartable backfill; until that
finishes, requests remain on the scan path. Do not enable during a mixed-version
writer rollout: an older writer cannot increment the completeness counter.

Monitor `cycles_reservations_created_at_index_reads_total{outcome=...}`.
`INDEX` is the fast path; a short-lived `SCAN_NOT_READY` during initial
backfill is expected. Persistent `SCAN_NOT_READY`, `SCAN_DRIFT`, or
`SCAN_ERROR` warrants checking reconciliation logs and Redis key types. The
metric intentionally has no tenant tag. Disable the flag to roll back
immediately; after all indexed readers are disabled, the
`reservation:idx:*:created_at_ms` and `reservation:idxmeta:*:created_at_ms`
keys may be deleted without affecting reservation data.

The implemented design, safety proof, and measured baseline are recorded in
[`docs/deferred-optimizations/sorted-list-zset-indices.md`](docs/deferred-optimizations/sorted-list-zset-indices.md).

Legacy clients (no sort params) stay on the existing Redis-SCAN
cursor path and are unaffected by this concern.

Cursors encode the `(sort_by, sort_dir, filters)` tuple — reusing
a cursor with a different sort or filter set returns HTTP 400
`INVALID_REQUEST`. This is intentional; front-end code that mutates
the sort/filter on a page change must reset to the first page.

## Getting help

- Bug reports / feature requests:
  https://github.com/runcycles/cycles-server/issues
- Release notes: [`CHANGELOG.md`](CHANGELOG.md)
- Engineering history & rationale: [`AUDIT.md`](AUDIT.md)
