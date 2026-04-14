# Changelog

All notable changes to `cycles-server` are recorded here. Format follows [Keep a
Changelog](https://keepachangelog.com/en/1.1.0/); versions use
[Semantic-ish Versioning](https://semver.org/) with a fourth "patch-of-patch"
segment for same-day follow-ups.

This file is for **downstream consumers** — people pulling the Docker image or
JAR. For internal engineering history (root cause analyses, rejected
alternatives, test-strategy decisions) see [`AUDIT.md`](AUDIT.md).

Wire format is considered stable within a minor version (`0.1.x`). Breaking
changes to request/response bodies or Lua-script semantics would require a
minor bump. "Internal signature changes" (e.g. Java method parameters) are
called out but are not breaking to API clients.

## [0.1.25.10] — 2026-04-14

### Added

- Seven domain-level Prometheus counters under the `cycles_*` namespace. See
  [`OPERATIONS.md`](OPERATIONS.md) for the full list, tag semantics, and
  alerting recipes. Short summary:
  - `cycles_reservations_reserve_total`
  - `cycles_reservations_commit_total`
  - `cycles_reservations_release_total`
  - `cycles_reservations_extend_total`
  - `cycles_reservations_expired_total`
  - `cycles_events_total`
  - `cycles_overdraft_incurred_total`
- Configuration flag `cycles.metrics.tenant-tag.enabled` (default `true`) —
  set to `false` in deployments with many thousands of tenants to keep
  Prometheus cardinality bounded.
- `RedisDisconnectResilienceIntegrationTest` — exercises the paused-Redis
  failure mode end-to-end and guards against silent-failure regressions.

### Fixed

- `ReservationExpiryService` was silently no-op'ing event emission on every
  expiry since v0.1.25.3 because it looked up the wrong Redis key prefix
  (`reservation:<id>` instead of `reservation:res_<id>`). The
  `reservation.expired` webhook now actually fires. If your downstream
  webhook consumer assumed expiries would never emit an event, update it
  before upgrading.

### Wire format

Unchanged. Upgrading from v0.1.25.9 requires no client changes.

### Notes for upgraders

- New counters appear on your next Prometheus scrape. No config change
  needed to emit them; they are on by default.
- The `reservation.expired` webhook fix will start delivering events you
  weren't receiving before. Confirm your webhook endpoint handles them.

## [0.1.25.9] — 2026-04-14

### Added

- Seven new test classes landing the second-wave coverage plan
  (overdraft property tests, `expire.lua` direct conformance, admin-release
  vs agent-commit race, multi-scope attribution under contention,
  idempotency-cache expiry, clock-skew resilience, audit-log completeness).
  See [`AUDIT.md`](AUDIT.md) for the full strategy.

### Wire format

Unchanged. Test-only release.

## [0.1.25.8] — 2026-04-13

### Added

- **Admin-on-behalf-of release** (spec revision 2026-04-13). Dual-auth
  endpoint: `POST /v1/reservations/{id}/release` now accepts either a
  tenant `X-Cycles-API-Key` or a server-configured `X-Admin-API-Key` via
  `AdminApiKeyAuthenticationFilter`. Admin-driven releases write an audit
  entry with `metadata.actor_type=admin_on_behalf_of` to the shared
  `audit:log:*` Redis store, surfacing in the governance dashboard.
- `admin.api-key` application property for configuring the admin key.

### Security

- Admin key comparison uses `MessageDigest.isEqual` (constant-time on
  equal-length inputs). Deployments should rotate to fixed-length keys.
- CR/LF injection guarded in audit-log `reason` field.

## [0.1.25.7] — 2026-04-11

### Added

- `Enums.ReasonCode` as a typed enum (previously stringly-typed on the
  wire). Drop-in Jackson round-trip-compatible with existing clients.

### Fixed

- Flaky `EventEmitterServiceTest` on CI. Replaced `Thread.sleep(200)` +
  `verify()` racing with `Mockito.timeout()` / `after()`.

## [0.1.25.6] — 2026-04-10

### Changed

- Reserve/event/decide now distinguish `UNIT_MISMATCH` from
  `BUDGET_NOT_FOUND`. Previously a wrong-unit request surfaced as "budget
  not found" which was misleading when the scope had a budget under a
  different unit. The scripts now probe alternate units and emit
  `UNIT_MISMATCH` with the set of configured units.

## [0.1.25.5] — 2026-04-08

### Fixed

- Duplicate emission of budget-state transition events
  (`budget.approaching_limit`, `budget.at_limit`,
  `budget.over_limit`, `debt.incurred`) on multi-scope operations. Closes
  `cycles-server-events#15`.

## [0.1.25.4] — 2026-04-07

### Changed

- Event-data payloads now include all fields the webhook consumers
  require for correct dedup/ordering (`reservation_id`, `scope`, `unit`,
  `actor`, timestamps). Previously some events arrived with missing
  fields that forced webhook consumers to re-query the server.

## [0.1.25.3] — 2026-04-03

### Added

- Runtime event emission: `reservation.reserved`, `reservation.committed`,
  `reservation.released`, `reservation.expired`, `reservation.extended`,
  `event.applied`, and the budget-state transitions. Events land on a
  Redis stream consumed by `cycles-server-events` for webhook fan-out.
- `PROTOCOL_VERSION` constant correctly set to `v0.1.25`.

## [0.1.25.2] — 2026-04-02

### Changed

- Scope matching is now case-insensitive. Tenant ids, workspace names,
  etc. normalise to lowercase on read and write. Existing data is
  unaffected because writes always used lowercase; this fixes reads
  against operator-curated budgets with mixed-case scope paths.

## [0.1.25.1] — 2026-04-01

### Added

- Reservation TTL auto-expiry (`ReservationExpiryService`).
  Background sweep transitions ACTIVE reservations past their grace
  window to EXPIRED and releases reserved budget back to all scopes.
- TTL retention: expired reservation hashes are kept 30 days for audit,
  then auto-cleaned by Redis `PEXPIRE`.
- Performance: batched `HMGET`/`HMSET` pipelines reduce round-trips on
  reserve/commit/release/extend from up to N to 2.

---

## Archive

v0.1.x and earlier versions predating this changelog: see `AUDIT.md`.

[0.1.25.10]: https://github.com/runcycles/cycles-server/compare/v0.1.25.9...v0.1.25.10
[0.1.25.9]: https://github.com/runcycles/cycles-server/compare/v0.1.25.8...v0.1.25.9
[0.1.25.8]: https://github.com/runcycles/cycles-server/compare/v0.1.25.7...v0.1.25.8
[0.1.25.7]: https://github.com/runcycles/cycles-server/compare/v0.1.25.6...v0.1.25.7
[0.1.25.6]: https://github.com/runcycles/cycles-server/compare/v0.1.25.5...v0.1.25.6
[0.1.25.5]: https://github.com/runcycles/cycles-server/compare/v0.1.25.4...v0.1.25.5
[0.1.25.4]: https://github.com/runcycles/cycles-server/compare/v0.1.25.3...v0.1.25.4
[0.1.25.3]: https://github.com/runcycles/cycles-server/compare/v0.1.25.2...v0.1.25.3
[0.1.25.2]: https://github.com/runcycles/cycles-server/compare/v0.1.25.1...v0.1.25.2
[0.1.25.1]: https://github.com/runcycles/cycles-server/releases/tag/v0.1.25.1
