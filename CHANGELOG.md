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

## [0.1.25.18] — 2026-04-26

Dependency hygiene aligning all three Cycles services (events / server /
admin) on the same Spring Boot patch and Redis client major. No code or
wire-format changes — pom-only patch.

### Changed

- **Spring Boot 3.5.13 → 3.5.14.** Patch upgrade picking up upstream
  security hardening (constant-time comparison for remote DevTools
  secret, `RandomValuePropertySource` switched to `SecureRandom`,
  hostname verification applied consistently for Cassandra/RabbitMQ
  SSL) plus symlink-handling fixes in `ApplicationPidFileWriter` /
  `ApplicationTemp`. Mirrors the events-server bump shipped in
  `cycles-server-events` v0.1.25.12.
- **Drop `<tomcat.version>10.1.54</tomcat.version>` override.** Spring
  Boot 3.5.14's BOM now manages Tomcat 10.1.54 directly (verified
  against `spring-boot-dependencies-3.5.14.pom`), so the explicit pin
  added in v0.1.25.16 to close CVE-2026-34483 / CVE-2026-34487 is
  redundant. Same effective Tomcat version, smaller pom diff for
  future Spring Boot bumps.
- **Jedis 7.4.1 → 6.2.0.** Aligns with `cycles-server-events` and
  `cycles-server-admin` on a single Redis-client major across the
  fleet, simplifying coordinated dependency upgrades. All call sites
  use stable APIs (`Jedis`, `JedisPool`, `Pipeline`, `Response`,
  `ScanParams`, `ScanResult`, `JedisNoScriptException`) — no 7.x-only
  API in use; all 152 tests pass on 6.2.0.

### Retained

- `<commons-lang3.version>3.18.0</commons-lang3.version>` override
  stays — Spring Boot 3.5.14's BOM still manages commons-lang3 at
  3.17.0 (CVE-2025-48924 unfixed there), so the explicit 3.18.0 pin
  added in v0.1.25.17 is still required. Override comment updated to
  reference SB 3.5.14.

## [0.1.25.17] — 2026-04-22

### Fixed (security)

- Pin `commons-lang3.version=3.18.0` in `cycles-protocol-service/pom.xml`
  to close **CVE-2025-48924** (Trivy HIGH) on `commons-lang3-3.17.0`,
  which ships transitively in the fat-jar image via
  `swagger-core-jakarta` (OpenAPI UI). Spring Boot 3.5.13's BOM manages
  `commons-lang3` at `3.17.0`; the override is removable once Spring
  Boot ships a managed version of `3.18.0+`.

### Notes

- No code, API, or Lua-script changes. All 152 tests pass. Wire format
  unchanged from `v0.1.25.15`.

## [0.1.25.16] — 2026-04-19

Pom-version-only bump — no standalone GitHub release was cut for this
version. Its changes ship cumulatively in `v0.1.25.17`.

### Fixed (security)

- Bump `spring-boot-starter-parent` `3.5.11 → 3.5.13` and pin
  `tomcat.version=10.1.54` to close five HIGH/CRITICAL CVEs surfaced by
  the new PR-time Trivy container scan:
  - **CVE-2026-22732 (CRITICAL)** — `spring-security-web`; fixed in
    `6.5.9`, pulled in transitively by Spring Boot 3.5.13.
  - **CVE-2026-29129 (HIGH)** and **CVE-2026-29145 (CRITICAL)** —
    `tomcat-embed-core`; fixed in `10.1.53`, transitive via Spring
    Boot 3.5.13.
  - **CVE-2026-34483 (HIGH)** and **CVE-2026-34487 (HIGH)** —
    `tomcat-embed-core`; fixed in `10.1.54`. The explicit
    `tomcat.version` property override is needed until Spring Boot
    3.5.14 (with 10.1.54+ as its managed version) ships.

### Notes

- No code or API changes. All 152 tests pass. Wire format unchanged
  from `v0.1.25.15`.

## [0.1.25.15] — 2026-04-18

### Fixed

- Runtime-written audit-log entries now respect a configurable retention
  TTL (default 400 days). Previously, `AuditRepository.log()` wrote
  `audit:log:{id}` keys with no `EXPIRE`, so runtime-written rows
  persisted indefinitely until Redis eviction — silently failing to
  participate in the 400-day retention tier the admin plane applies to
  authenticated audit rows. Matches the authenticated-tier default on
  `cycles-server-admin`'s `AuditRepository` (admin's
  `audit.retention.authenticated.days=400`). Runtime never writes the
  admin-plane `__admin__` / `__unauth__` sentinels, so a single tier
  is sufficient.

### Added

- `audit.retention.days` config (default `400`, env `AUDIT_RETENTION_DAYS`).
  Set to `0` for indefinite retention (legal hold, HIPAA-adjacent
  deployments, or environments that offload audit to an archive store).
- `audit.sweep.cron` config (default `0 0 3 * * *`, env `AUDIT_SWEEP_CRON`).
  Daily `@Scheduled` sweep prunes stale `audit:logs:{tenantId}` and
  `audit:logs:_all` ZSET pointers whose target `audit:log:{id}` key has
  TTL-expired. Self-contained — does not depend on admin's sweep running
  against the same Redis. Safe to run in parallel with admin's sweep
  (idempotent `ZREMRANGEBYSCORE`).

### Internal

- `AuditRepository.LOG_AUDIT_LUA` now reads ARGV[4] as an optional TTL
  in seconds (`0` or negative = no `EX`). Same shape as admin's script,
  minus the sentinel branching.

## [0.1.25.14] — 2026-04-18

### Added

- W3C Trace Context correlation per `cycles-protocol-v0.yaml` revision
  2026-04-18. Every response now carries an `X-Cycles-Trace-Id`
  header. The server accepts a `traceparent` (W3C version 00) or
  `X-Cycles-Trace-Id` header on inbound requests and echoes back the
  same trace_id; when neither is present it generates a fresh 128-bit
  id (32 lowercase hex). Malformed headers are silently ignored; the
  server never rejects a request for a bad correlation header.
- `trace_id` field on `ErrorResponse`, `Event`, `WebhookDelivery`,
  and `AuditLogEntry` bodies. Optional for wire back-compat; conformant
  servers populate it on every payload causally downstream of the
  request.
- `trace_flags` (`^[0-9a-f]{2}$`) and `traceparent_inbound_valid`
  (boolean) on `WebhookDelivery` per governance-admin spec v0.1.25.28.
  These preserve the upstream W3C sampling decision so the events
  sidecar can reconstruct an outbound `traceparent` with the correct
  trace-flags byte instead of defaulting to `01`.
- SLF4J MDC now carries `traceId` alongside `requestId` for every
  request — log aggregators can group by trace_id to see all lines
  produced during a single logical operation.
- `ReservationExpiryService` mints a fresh trace_id per sweep batch
  so `reservation.expired` events emitted in the same sweep correlate
  to each other.

### Internal

- New `TraceContextFilter` (`@Order(0)`) runs before `RequestIdFilter`
  and sets the `cyclesTraceId` request attribute for downstream code.
- `EventEmitterService.emit(...)` gains a final `String traceId`
  parameter. The full-arity `emitBalanceEvents(...)` signature
  likewise. Three prior overloads kept as delegating wrappers
  (`traceId = null`) for source compatibility with existing tests.
- `BaseController` exposes protected `resolveRequestId` and
  `resolveTraceId` helpers that controllers use to thread the ids
  into event-emission and audit-log calls.

## [0.1.25.13] — 2026-04-16

### Fixed

- `GET /v1/reservations` sorted path no longer hydrates an unbounded
  reservation population before the in-memory sort. A
  `SORTED_HYDRATE_CAP = 2000` guard mirrors the admin-plane pattern
  shipped in `cycles-server-admin` v0.1.25.24: once the cap is hit
  the SCAN loop breaks, a WARN is logged so operators can see the
  window was truncated, and the cursor page still fills from the
  capped slice. Callers that need to see past the cap should narrow
  filters (`status`, `idempotency_key`, `workspace`/`app`/
  `workflow`/`agent`/`toolset`) — same workaround doc pattern as
  admin.

### Internal

- `Enums.ReservationSortBy` and `Enums.SortDirection` now carry
  `@JsonValue getWire()` + `@JsonCreator fromWire(String)` Jackson
  annotations matching the admin plane's `SortSpec` /
  `SortDirection` pattern. Wire form stays lowercase, parsing stays
  case-insensitive with `null → null`. Controller-level validation
  is unchanged: unknown tokens still surface as HTTP 400
  `INVALID_REQUEST` with the documented allow-list payload.
- `RedisReservationRepository.SORTED_HYDRATE_CAP` is package-private
  (test-visible) to allow cap-hit tests to assert the bound
  deterministically.

### Notes for upgraders

Behavior-visible for callers that previously relied on the sorted
path silently returning all rows even for a single tenant with
thousands of reservations: the page shape is identical, but rows
beyond row 2000 in the capped slice are now unreachable without
narrowing filters. Same trade-off doc as the admin plane cap.

## [0.1.25.12] — 2026-04-16

### Added

- `GET /v1/reservations` now accepts `sort_by` and `sort_dir` query
  parameters (cycles-protocol spec revision 2026-04-16). Valid
  `sort_by` values: `reservation_id`, `tenant`, `scope_path`,
  `status`, `reserved`, `created_at_ms`, `expires_at_ms`. Valid
  `sort_dir` values: `asc`, `desc` (default `desc` when `sort_by`
  is provided). Invalid enum values return HTTP 400
  `INVALID_REQUEST`.
- Server-side ordering with deterministic `reservation_id ASC`
  tiebreaker so pagination is unambiguous under ties.
- Opaque sorted cursor (base64url-no-pad JSON, `{v,sb,sd,fh,lsv,lrid}`)
  that binds to the `(sort_by, sort_dir, filters)` tuple via an
  8-byte SHA-256 filter hash. Reusing a cursor under a different
  tuple returns HTTP 400.

### Wire format

Backward compatible. Omitting `sort_by`/`sort_dir` preserves the
existing Redis-SCAN cursor semantics; legacy all-digit cursors
continue to work unchanged.

### Internal

- New `support.SortedListCursor`, `support.FilterHasher`,
  `support.ReservationComparators` utilities.
- `RedisReservationRepository.listReservations` signature extended
  with trailing `sortBy`, `sortDir` parameters (10 → 12 args).
  Direct callers inside the service have been updated; external
  callers (if any) must add two trailing `null`s.

### Notes for upgraders

No action required for clients that don't use the new parameters.
Ops teams monitoring sorted-list query latency should watch for
the documented O(N) full-SCAN behaviour — see OPERATIONS.md.

## [0.1.25.11] — 2026-04-14

### Added

- Thundering-herd test for idempotency cache expiry. Asserts that N
  concurrent retries with the same idempotency key (arriving after the
  cache has expired) produce exactly one reservation, not N. Also
  verifies metric tags split correctly: 1 × `reason=OK` (the winner)
  + (N-1) × `reason=IDEMPOTENT_REPLAY` (the replays).
- Concurrent-accuracy test for the custom `cycles.reservations.reserve`
  counter under 8-thread × 10-request load. Counter count must match
  client-observed successes with zero lost increments.

### Wire format

Unchanged. Test-only release. No production-code changes.

### Notes for upgraders

No action required. These tests are regression gates — if you're not
refactoring the reservation path or the metrics component, nothing
changes for you.

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

### Fixed

- `getBalances` and `listReservations` now lowercase the stored scope
  before segment matching, so operator-curated budgets with mixed-case
  scope paths are findable. Writes were already lowercase; this was a
  read-side defensive fix. Closes
  `cycles-openclaw-budget-guard#70`, `cycles-server-admin#54`.

## [0.1.25.1] — 2026-04-01

### Added

- Webhook event emission from the runtime server. Reserve/commit/release/
  extend and decide now emit events to the shared Redis dispatch queue
  for `cycles-server-events` to fan out to configured subscribers.
  Events include `reservation.denied` (on DENY decisions) and
  `reservation.commit_overage` (when committed `actual > estimate`).
- `EventEmitterService` with async, non-blocking emission on a
  dedicated daemon thread pool (`CompletableFuture.runAsync`). The
  request thread never waits on event writes.
- TTL retention: event keys expire after 90 days, delivery keys after
  14 days. Configurable via `EVENT_TTL_DAYS` / `DELIVERY_TTL_DAYS`.

### Performance

- Event save (SET + EXPIRE + 2× ZADD) and subscription lookup
  (2× SMEMBERS) batched into one Redis pipeline round-trip (was 6
  sequential). Near-zero overhead on non-event paths; commit p50
  recovered from 13.4ms to 5.6ms after the fix below.

### Fixed

- Commit overage event was firing on every commit, not just true
  overages. Now emits only when `actual > estimateAmount`.

---

## Archive

v0.1.x and earlier versions predating this changelog: see `AUDIT.md`.

[0.1.25.11]: https://github.com/runcycles/cycles-server/compare/v0.1.25.10...v0.1.25.11
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
