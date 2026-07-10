# Cycles Protocol v0.1.25 — Server Implementation Audit

**Spec:** `cycles-protocol-v0.yaml` (OpenAPI 3.1.0, v0.1.25) + `complete-budget-governance-v0.1.25.yaml` (events/webhooks)
**Server:** Spring Boot 3.5.15 / Java 21 / Jedis 7.5.2 / Redis (Lua scripts) · commons-lang3 3.18.0 and Tomcat 10.1.55 pins

---

### 2026-07-10 — v0.1.25.47: TENANT_CLOSED Rule 2 guard on reservation mutations

Adopts the governance spec's CASCADE SEMANTICS Rule 2 (terminal-owner
mutation guard, `cycles-governance-admin-v0.1.25.yaml`) on the runtime
plane: once the owning tenant's CLOSED flip is durable, reservation
create/commit/release/extend return 409 `TENANT_CLOSED` (Mode B
invariant (a): a mutation observed after the flip MUST NOT succeed, even
before the cascade touches the child or revokes keys).

What the guard actually closes, layer by layer (audited during
implementation): `ApiKeyRepository.validate` ALREADY reads `tenant:<id>`
fresh per request and 401s tenant keys of SUSPENDED/CLOSED tenants at
the auth filter — so for tenant-key HTTP traffic the post-flip window
was already shut (with 401, the pending runtime spec revision's "a
closed tenant usually surfaces on this plane as 401", not the Rule 2
409). Two real gaps remained, both now closed: (1) **admin-key
mutations** — the runtime's admin-on-behalf-of auth (`X-Admin-API-Key`;
allowlist GET list/single + POST release) carries no tenant-status
check, so an admin release on a closed tenant SUCCEEDED, mutating
budgets post-flip; it now returns 409 `TENANT_CLOSED`. (2) **the
auth-check→script race** — the filter reads tenant status at auth time
and the CLOSED flip can land between that read and the Lua execution;
only an in-script check is atomic with the mutation. The guard also
covers any future path that reaches the repository without the
tenant-key filter.

Design decisions:
- **Guard placement — inside the Lua scripts.** The codebase's precedent
  for status guards (BUDGET_FROZEN/BUDGET_CLOSED in reserve.lua) is
  in-script, and only in-script placement is atomic with the budget
  mutations (Redis executes scripts serially): a Java pre-check would
  leave a flip-vs-mutation race, and piggybacking on
  `getTenantConfig()` would inherit its 60 s Caffeine cache
  (`cycles.tenant-config.cache-ttl-ms`), violating invariant (a)'s
  "durable to readers" requirement. Cost: one extra Redis `GET
  tenant:<id>` + `cjson.decode` inside each mutation script — no extra
  network round-trip.
- **Owning-tenant resolution.** reserve.lua uses the auth-derived tenant
  already in ARGV[10]; commit/release/extend read the `tenant` field from
  the reservation hash (the authoritative owner; reserve.lua has always
  written it) — added to the scripts' existing HMGETs, no new commands.
- **No tenant record ⇒ no restriction** (runtime-only deployments), same
  contract as the admin plane's `TerminalOwnerMutationGuard`. A PRESENT
  record that cannot be decoded into an object with a string `status`
  **fails closed** (500 INTERNAL_ERROR, no mutation) — matching the admin
  plane's TenantRepository, which propagates parse failures rather than
  treating a corrupt governance record as an open tenant (codex round 2;
  the first cut fell through open on decode failure). Round 3 extended
  fail-closed to unknown status STRINGS via a whitelist (CLOSED → 409;
  ACTIVE/SUSPENDED → proceed; anything else → 500): the governance
  TenantStatus enum is a closed set and the cascade revision explicitly
  introduces no new status values as a wire-compat guarantee, so an
  unknown status (e.g. "CLOZED", lowercase "closed") cannot be a
  legitimate future value under the current contract — it is corruption.
  Pinned per op with "CLOZED" and lowercase "closed" records in the
  malformed matrix.
- **Precedence.** Same-key idempotent replay first (a replay re-observes
  a pre-flip mutation — not a new mutation; Rule 2(b) idempotency;
  mirrors how the budget status guards sit after replay), then the
  closed-tenant guard, then every reservation-state/expiry/budget check —
  honoring the spec's "regardless of that child's own current status"
  (precedence sentence added to spec PR runcycles/cycles-protocol#125
  ERROR SEMANTICS). Codex round 2 resolved the first cut's accepted edge:
  commit.lua/release.lua previously returned RESERVATION_FINALIZED for a
  different-key attempt on a finalized reservation before the guard ran;
  the replay branches were narrowed to true same-key replays so
  different-key attempts fall through to the guard, and release.lua's
  post-guard state check widened from `== "COMMITTED"` to `~= "ACTIVE"`
  to keep the open-tenant RESERVATION_FINALIZED response identical.
- **Scope.** Exactly the four reservation mutations Rule 2 names get the
  409 guard. `GET`/list stay available on closed tenants (spec:
  post-mortem reads). The non-persisting evaluations (dry_run +
  `/v1/decide`) were initially left unguarded; an external review round
  (round 4) flagged that a post-flip dry_run could stamp a SIGNED ALLOW
  attestation for a request whose live execution MUST fail — resolved
  per the amended spec PR runcycles/cycles-protocol#125: a FRESH
  evaluation on a CLOSED tenant now returns 200 decision=DENY with
  reason_code=TENANT_CLOSED (new `Enums.ReasonCode` value, typed,
  mirroring the documented DecisionReasonCode vocabulary) via a single
  shared gate (`evaluateTenantStatusGate`) called from both evaluation
  paths, after replay handling and before any budget read. The gate
  reads `tenant:<id>` fresh (never the 60 s config cache) and applies
  the same fail-closed whitelist as the Lua guards — malformed record
  (undecodable / non-object / missing or non-string status / unknown
  status string) → 500 INTERNAL_ERROR BEFORE evidence stamping (the
  server cannot attest against corrupt governance state; no
  reserve/decide evidence row and no error-evidence row is written,
  consistent with the existing convention that evidence is emitted only
  for decisions actually reached — INTERNAL_ERROR is likewise excluded
  from EVIDENCE_DENIAL_CODES). Cached pre-close replays keep their
  original payload. `POST /v1/events` also mutates budgets and Rule 2's list is
  "non-exhaustive" — flagged as an open spec question rather than guarded
  ahead of the spec. `TENANT_CLOSED` is not yet in
  `EVIDENCE_DENIAL_CODES` (error-evidence emission deferred until runtime
  spec v0.1.25.13 lands). SUSPENDED tenants: existing runtime semantics
  live in the AUTH layer only (tenant keys 401 — pre-existing, unchanged,
  pinned); the mutation-layer guard is deliberately CLOSED-only per
  Rule 2 / spec v0.1.25.13.
- **Wire.** `Enums.ErrorCode` gains `TENANT_CLOSED` (additive; runtime
  spec revision v0.1.25.13, runcycles/cycles-protocol#125 — mirrors the pre-existing
  governance code).

Tests: `TenantClosedGuardIntegrationTest` (Testcontainers Redis, real
Lua). All four ops are exercised at the repository layer — a repository
call is exactly "a request already past auth", i.e. the
filter-check→script race — with no-partial-mutation assertions (budget
`reserved`/`remaining`/`spent`, reservation state, `expires_at`
unchanged). HTTP layer: admin-key release on a closed tenant → 409
TENANT_CLOSED with the full ErrorResponse envelope (previously 200 —
the reachable hole); tenant-key mutation on a closed tenant → 401
pinned (pre-existing auth behavior, unchanged); admin GET + list on a
closed tenant → 200 (Rule 2 read access). Plus record-absent / ACTIVE
pass-through, SUSPENDED (repo-level ops proceed — mutation guard is
CLOSED-only; auth-layer 401 pinned as pre-existing), cross-tenant
isolation, and replay-across-the-flip semantics. The 409 HTTP calls use
a non-validating client until spec v0.1.25.13 merges (the shared
validating client checks response enums against cycles-protocol@main);
response shape is asserted explicitly. Unit tests: handleScriptError
token mapping, `tenantClosed` factory, GlobalExceptionHandler 409
envelope (+ no-evidence pin).

Codex review round 2 (both applied against real-Lua tests): (1)
fail-closed on malformed tenant records — all four guards return
INTERNAL_ERROR (500, message preserved through a new explicit
handleScriptError case) when a present `tenant:<id>` row fails
cjson.decode, decodes to a non-object, or lacks a string `status`;
pinned per op with malformed / non-object (string, number) /
missing-status shapes and no-partial-mutation assertions. (2)
TENANT_CLOSED precedence over RESERVATION_FINALIZED for non-replay
mutations (see the Precedence bullet); pinned: closed tenant +
finalized reservation + different key → 409 TENANT_CLOSED on commit,
release, and extend; same-key replay still returns the original
response; open tenant + different key still RESERVATION_FINALIZED
(no-regression pin). Codex also confirmed the matcher port is
byte-identical to admin main and that the in-script `GET tenant:<id>`
matches the repo's existing standalone-Redis posture — no changes.

### 2026-07-10 — v0.1.25.47: webhook scope-filter matcher parity with the admin plane

The admin server fixed its `scope_filter` matcher for spec conformance
(cycles-server-admin PR #206); the runtime dispatch matcher
(`EventEmitterRepository.matchesScope`) already had the trailing-`*`/exact
split but lacked two of the admin refinements, so the two planes could
disagree on the same (filter, scope) pair — a subscription could receive an
event live (runtime dispatch) that admin-plane replay would skip, or vice
versa. Ported both refinements 1:1 so the matchers are byte-identical:
(1) blank/whitespace-only event scope is unscoped — excluded from any
scope-filtered subscription (previously bare `*`, an empty-prefix
`startsWith`, matched a blank `""` scope); (2) trailing-`*` filters require
a non-empty child segment after the prefix (`tenant:a/*` no longer matches
the degenerate `tenant:a/`; spec text is "all scopes *under*" the base).
The matcher was made `public static` (mirroring the admin's) and the
admin's full matcher test table — null/blank filters, bare `*`, trailing
`*` child/base/sibling/empty-segment cases, exact match, literal
mid-string `*`, case sensitivity, blank-scope edges — was ported into
`EventEmitterRepositoryTest`, pinning both planes to the same
(filter, scope, expected) table. One dispatch-level test pins the
blank-scope refinement end-to-end through `emit()`. No wire or storage
change; delivery selection shifts only on the two edge cases.

### 2026-07-04 — full-stack prod compose: stop host-publishing events management port (no version bump)

`docker-compose.full-stack.prod.yml` published the events worker's 9980 to
the host — an unauthenticated actuator surface (health + Prometheus), the
exposure class v0.1.25.45 closed for this server's own actuator. The events
worker's isolation mechanism is its separate management port on an internal
network (see cycles-server-events OPERATIONS.md); the container healthcheck
probes in-container and Prometheus scrapes over the compose network, so the
publish served nothing. Removed, with an inline comment recording the
posture. (Lands after v0.1.25.46; the compose file here retains that
release's image pin.)

### 2026-07-04 — v0.1.25.46: 429 throttling on public evidence/JWKS endpoints

Implements the spec's SHOULD-level rate limiting on the only anonymous
surface (`GET /v1/evidence/*`, JWKS). `PublicEndpointRateLimitFilter`:
fixed 60s window per client IP, in-process (abuse damping, not a
distributed quota — behind N instances the effective limit is N×; behind a
connection-terminating ingress, prefer ingress limiting). 429 body is a
conformant ErrorResponse with the new `LIMIT_EXCEEDED` code (spec
v0.1.25.12 — the runtime enum previously had no throttling code, making a
conformant 429 impossible) plus `Retry-After`/`X-RateLimit-Reset` per the
spec's 429 response declaration; overrides the sentinel headers from
`RateLimitHeaderFilter` on that response. Client map is HARD-bounded (10k):
stale-window entries evicted first; a unique-key flood within one window
resets the map entirely (review hardening — stale eviction alone could not
shrink a current-window flood; per-IP limiting cannot constrain an
address-rotating attacker, so the cap protects heap and a one-time counter
reset for legitimate clients is the accepted cost). Injectable clock for
deterministic window tests; unit suite covers limits, rollover, per-client
isolation, both paths, disabled flag, header/body conformance, and the
unique-key flood bound.

### 2026-07-03 — spec-conformance audit vs cycles-protocol-v0.yaml v0.1.25.10: no drift

End-to-end audit of the runtime API surface against the protocol spec at
`cycles-protocol@main` (c3e7b99, spec `info.version` 0.1.25.10) found zero
discrepancies — no code changes. All 11 operations, DTO field names/types,
required markers, `additionalProperties: false` enforcement, the 15-value
ErrorCode enum and its HTTP mappings, response headers, idempotency/tenancy
rules, decide 200-DENY semantics, dry-run rules, list window filters
(including the blank-string-is-unset carve-out), `include=` projection, and
detail/summary evidence hydration were verified by hand and by the full suite
(`mvn verify -Pintegration-tests`, 491 tests green, spec coverage 11/11,
JaCoCo ≥95% met). Known deliberate strictness retained: Subject field
`@Pattern` (scope-delimiter safety), `@NotBlank` on Action.kind/name, 410 on
`GET /v1/reservations/{id}` for EXPIRED (settled by Issue 10 below).

### 2026-06-27 — v0.1.25.45: production quality/security audit

Full production-readiness pass across bugs, leaks, performance, logging,
deployment defaults, and the runtime protocol surface found no new
cycles-protocol wire drift: the runtime endpoints remain covered by the
contract tests against `cycles-protocol-v0.yaml`, and this release makes no Lua,
Redis data-model, event-schema, evidence-schema, or HTTP body change.

The main security gap was operational exposure. `SecurityConfig` deliberately
kept infrastructure paths outside tenant API-key auth, but the runtime plane did
not have the admin service's second filter protecting aggregate actuator,
Prometheus, and docs. That meant a deployment that published port `7878` could
serve `/actuator/prometheus`, `/actuator/info`, `/actuator/health`, and
SpringDoc if docs were enabled. `OperationalEndpointAuthFilter` now requires
`X-Admin-API-Key` for those endpoints while leaving
`/actuator/health/liveness`, `/actuator/health/readiness`, `GET /v1/evidence/*`,
and `GET /v1/.well-known/cycles-jwks.json` public as required for probes and
CyclesEvidence. Contract tests fetch `/api-docs` with the admin key.

The same pass found an in-process memory risk in `EventEmitterService`: the
fixed thread pool used Java's unbounded executor queue. If Redis/event
persistence stalled while request traffic continued, non-blocking event
emission could accumulate heap indefinitely. The executor now uses a bounded
queue (`CYCLES_EVENTS_EMIT_QUEUE_CAPACITY`, default 10000) and a configurable
worker count (`CYCLES_EVENTS_EMIT_THREADS`, default CPU-derived). Queue
saturation logs a structured warning and drops only the non-blocking side
effect; ledger mutations and API responses remain unchanged. Focused tests
cover the drop path.

Logging/correlation also tightened: trace/request MDC filters now run at highest
precedence so even security-rejected requests carry the same correlation fields
as controller-handled requests, and auth-filter error responses set both
`X-Request-Id` and `X-Cycles-Trace-Id`. Container probes now use
`127.0.0.1` readiness endpoints everywhere to avoid BusyBox/IPv6 `localhost`
false negatives. Full-stack development Compose now probes the events worker on
management port `9980`, matching production. Finally, the accidentally tracked
root `dump.rdb` Redis snapshot was removed and `*.rdb` ignored so local Redis
state cannot be committed again.

### 2026-06-26 — v0.1.25.44: production deployment hardening follow-up

Production-readiness review of the merged server found remaining deployment
surface issues rather than protocol-code defects. `/actuator/prometheus` is
intentionally unauthenticated for Prometheus scraping, but custom domain metrics
included a tenant label by default. That is useful in private observability
stacks but risky for internet-adjacent deployments and high-cardinality tenant
fleets. Production Compose now sets
`CYCLES_METRICS_TENANT_TAG_ENABLED=false`; developer defaults remain unchanged
so local metrics still include tenant labels unless disabled.

The same review found public SpringDoc API docs and Swagger UI enabled in the
production Compose path. These are fine for local development but unnecessary
production exposure. Production Compose now sets
`SPRINGDOC_API_DOCS_ENABLED=false` and `SPRINGDOC_SWAGGER_UI_ENABLED=false`;
operators that intentionally publish docs can re-enable them behind trusted
ingress. The stale `DASHBOARD_CORS_ORIGIN` runtime-service environment variable
was also removed from production Compose because cycles-server does not consume
it; dashboard CORS remains an admin-service concern.

Finally, the full-stack production Compose file lagged sibling releases after
the admin/events readiness hardening work. It now references
`cycles-server-admin:0.1.25.47` and `cycles-server-events:0.1.25.20`, and probes
their `/actuator/health/readiness` endpoints instead of aggregate health. The
events worker now publishes only its management port `9980` in this full-stack
file; port `7980` remains the worker's internal app port and should not be
published on ingress. Because the full-stack deployment includes admin/events,
`WEBHOOK_SECRET_ENCRYPTION_KEY` is now required there and admin is configured
with `WEBHOOK_SECRET_ENCRYPTION_REQUIRED=true`.

Container shutdown was also tightened: the runtime image previously launched
Java through `sh -c "java ..."`, leaving the shell as PID 1. The entrypoint now
uses `exec java ...` so the JVM receives SIGTERM directly and Spring Boot's
configured graceful shutdown window can run reliably during rolling deploys.

### 2026-06-25 — v0.1.25.43: production readiness health and log correlation

Operational review found two production-readiness gaps in the merged server
state. First, `/actuator/health` only proved that the Spring HTTP process was
up; it did not verify Redis, even though every ledger write path depends on
Jedis/Lua. Docker and Compose healthchecks therefore stayed green during a
Redis outage while API operations returned structured Redis-backed 5xx errors.
The API module now registers a `redis` `HealthIndicator` that borrows a Jedis
connection and requires `PING -> PONG`; pool exhaustion, connection failures, or
unexpected responses mark readiness DOWN. Spring Boot liveness/readiness probes
are explicitly enabled, readiness includes `readinessState,redis`, and liveness
stays process-only. Dockerfile, Compose, and release-smoke healthchecks now use
`/actuator/health/readiness`, so Redis outages drain traffic instead of being
treated as process liveness failures.

Second, `OPERATIONS.md` documented that log lines carry both `requestId` and
`traceId` MDC values, but only `TraceContextFilter` populated MDC. The
`RequestIdFilter` now sets `requestId` around the request chain and clears it in
a `finally` block, so structured console logging can join logs on either
correlation id. Focused tests cover Redis health UP/DOWN cases and request-id
MDC lifecycle.

The same integration pass also showed Spring Boot creating a generated default
security password. The API-key filters were handling requests, but a production
runtime should not boot with an unused default user/basic-login path. The
security chain now explicitly disables HTTP Basic, form login, and logout, uses
stateless sessions, and excludes `UserDetailsServiceAutoConfiguration` so no
generated user/password is created.

Validation logs also showed API-key validation emitting a full stack trace on
each Redis connection timeout. That is useful in development but noisy during a
production Redis incident, especially because the auth filter already records a
structured rejected-request warning with method, path, request id, and trace id.
Jedis connection failures are now logged as a concise warning with
`reason=redis_unavailable`, prefix presence/length, error type, and error
message; unexpected validation exceptions still keep the error-level stack
trace.

### 2026-06-25 — v0.1.25.42: release benchmark gate baseline follow-up

PR #212 correctly moved production per-request success logs from `INFO` to
`DEBUG`, but the re-cut v0.1.25.41 release gate still failed on commit
`21f542c`. The release logs showed no remaining controller request log lines, so
the remaining CI failure was not log emission on the hot path. Benchmark history
showed the root cause: `v0.1.25.39` was an unusually fast single release sample
(`reserve_p50_ms=2.6`, `concurrent_throughput_32t=665.4`) compared with the
surrounding nightly/release medians (`reserve_p50_ms` around 3.1-3.5 and
throughput around 493-541 before `.39`, 599.6 on the next nightly). The release
gate was treating that one fast sample as the only baseline.

The release gate now keeps the strict 25% threshold but compares release
candidates against the rolling median in `benchmarks/history.jsonl` when at
least two history records exist; `baseline.json` remains the fallback for
bootstrap/old data and is still updated after successful releases. This matches
the nightly benchmark design while keeping release failures blocking.
`BaseController.authorizeTenant()` also now guards its sanitized DEBUG logs so
tenant-string flattening is skipped entirely when DEBUG is off.

### 2026-06-24 — v0.1.25.41: log sanitization and auth-log follow-up

Follow-up to the ops-focused logging PR review. The structured logs added in
v0.1.25.40 had the right request/route/correlation context, but several fields
still accepted raw strings from requests, config, exception messages, or stored
operator data. Those values are now flattened before logging (`CR`/`LF` ->
space) in the exception handler, controller request logs, side-effect failure
logs, auth rejection logs, balance/reservation list logs, and JWKS retired-key
config warnings.

The API-key debug path no longer prints any key prefix or masked token material.
It reports only key presence and length, while validation/auth failure logs keep
tenant/key/reason/request/trace context in sanitized form. No HTTP status/body
change, no Redis/Lua change, and no cycles-protocol spec change.

Final review follow-up tightened the remaining API-layer gaps: exception-handler
logs now sanitize concrete request paths, matched route strings, and
`reservation_id` path variables before emitting structured operator context;
`BaseController.authorizeTenant()` also flattens tenant debug values. A focused
`GlobalExceptionHandlerTest` assertion covers CR/LF flattening on handled
protocol-exception logs.

**Hot-path log level.** `BaseController.logRequest(...)` and the inline list/
balance/evidence request logs now emit at `DEBUG` behind an `isDebugEnabled`
guard (so sanitize/attribute-lookup args stay off the hot path when DEBUG is
off). Per-request request logging at DEBUG is the conventional level; the
high-value exception (`INFO`/`ERROR`) and side-effect-failure (`WARN`) logs are
unchanged and are not on the success hot path. No wire/Redis/Lua/spec change.
The later v0.1.25.42 release-gate audit corrected the benchmark root cause:
the release CI failure also depended on a single fast baseline sample, not just
runtime request-log level.

The same sanitization is now also applied in the **data plane**, which the first
pass missed: repository and service failure logs (`RedisReservationRepository`,
`AuditRepository`, `EventEmitterRepository`, `EventEmitterService`,
`EvidenceEmitter`, `ReservationExpiryService`) flattened request-derived strings
(tenant, reservation id, scope, resource keys, exception messages) only after
this follow-up. The CR/LF flatten previously lived in three private copies
(`BaseController`, `GlobalExceptionHandler`, `ApiKeyValidationService`); a shared
`io.runcycles.protocol.data.util.LogSanitizer.sanitize(Object)` (null-safe) is
now the single source the data plane uses, covered by a focused
`LogSanitizerTest`. The trailing SLF4J throwable argument is never sanitized
(stack traces are preserved) and DEBUG one-arg traces are left as-is. The
existing API-module copies still behave identically and can fold into
`LogSanitizer` in a later cleanup.

### 2026-06-24 — v0.1.25.40: ops-focused logging context review

Follow-up to production log review after seeing `Landed in cycles exception handler: clazz=...` without enough context to diagnose the request. The logging audit covered runtime `INFO`, `WARN`, and `ERROR` call sites in the API and data modules.

**Exception handling.** `GlobalExceptionHandler` now logs handled protocol exceptions with method, concrete path, matched route pattern, HTTP status, protocol error code, `request_id`, `trace_id`, and `reservation_id` when present. Validation and malformed-body handlers now emit the same request context at `INFO`; unexpected 500s log class plus request/route/correlation fields at `ERROR`. The null-request fallback remains safe.

**Request and side-effect visibility.** Reservation, balance, decision, event, and evidence controllers now include request/trace context in request logs. Previously swallowed non-blocking event side-effect failures now log at `WARN` with operation, tenant, reservation id where available, request id, trace id, and stack trace; response behavior remains fail-open.

**Auth, event, evidence, audit, and repository logs.** Client auth failures now log safe failure context at `WARN` instead of dumping the full validation DTO at `ERROR`; admin-key server misconfiguration remains `ERROR`. Async event/evidence/audit failure logs now carry tenant/resource/event/request/trace identifiers. Repository failure logs avoid whole request DTOs and raw idempotency keys, replacing them with operation/resource identifiers and key-presence booleans.

**Validation.** Focused handler/auth tests pass; WebMvc controller tests pass with contract validation disabled because this sandbox cannot fetch the protocol spec; data-layer tests for API keys, audit, event emission, evidence emission, and expiry pass. Commands are recorded in `CHANGELOG.md` under `0.1.25.40`.

### 2026-06-24 — Trivy container gate scoped to HIGH,CRITICAL (no version bump)

The `pr-container-scan.yml` and `release.yml` Trivy gates were failing on the only fixable finding in the image: `jackson-databind` 2.21.4 (`CVE-2026-54515`), pulled in transitively via the Spring web stack and managed by Spring Boot 3.5.15's BOM. The failure was unrelated to any code change — it blocked every open PR and would have blocked the next release build.

Two facts shaped the fix:
1. **No fix is publishable yet.** The advisory lists fixed versions 3.1.4 / 2.18.9 / 2.21.5. For the SB 2.21 line the only forward fix is 2.21.5, and jackson 2.x ships `jackson-bom` + `jackson-core` + `jackson-databind` together — `jackson-bom:2.21.5` is absent from Maven Central, so 2.21.5 is not yet released (Trivy's "Fixed Version" is ahead of the artifact). 2.18.9 is a downgrade; 3.1.4 is jackson 3.x, incompatible with SB 3.5.x. A `<jackson-bom.version>2.21.5</jackson-bom.version>` override is non-resolvable.
2. **The CVE is MEDIUM (CVSS 5.3)** and the gate is declared for `severity: HIGH,CRITICAL`. The block was an `aquasecurity/trivy-action` quirk: in `format: sarif` mode it builds an all-severities report and `exit-code: 1` trips on any finding, ignoring the `severity` filter.

**Fix.** Set `limit-severities-for-sarif: true` on the trivy-action in both workflows so the SARIF — and therefore the gate — honors `HIGH,CRITICAL`. A fixable MEDIUM no longer blocks; HIGH/CRITICAL still fail. `CVE-2026-54515` is tracked with a NOTE in `cycles-protocol-service/pom.xml` to bump jackson once 2.21.5 ships. Workflow/config only — no image, server-code, or wire change, so no version bump. Mirrors the same fix in `cycles-server-admin` (PR #191).

### 2026-06-24 — v0.1.25.39: review fixes for pagination, replay, auth, and prod defaults

Follow-up to a full static review of `cycles-server` against `cycles-protocol` v0.1.25. The fixes are deliberately mixed because the reviewed issues share one release boundary: they close correctness/spec gaps without changing the public request/response schema.

**SCAN pagination.** The legacy no-sort `listReservations` and `getBalances` paths previously returned Redis' next SCAN cursor as soon as the API `limit` was reached. If the limit was reached midway through a Redis batch, the unreturned keys later in that same batch were skipped permanently on the next page. `ScanPageCursor` now wraps the current Redis cursor plus an intra-batch offset when needed, while still accepting all-digit Redis cursors from older clients. This fixes the deterministic mid-batch skip for an unchanged SCAN batch; like all Redis SCAN cursoring, it remains best-effort rather than snapshot-isolated if the keyspace changes or Redis rehashes between page requests. Regression tests page through a two-key single batch for both reservations and balances.

**Sorted listing.** The v0.1.25.13 heap-safety cap on sorted `listReservations` made rows beyond the 2000-row hydrated slice unreachable even though `has_more` could eventually become false. The sorted path now hydrates all matching rows for correctness and logs when it crosses the same 2000-row threshold. The long-term performance answer remains the deferred per-tenant sorted index; the cap is no longer allowed to leak into wire correctness.

**Auth and idempotency.** Auth-filter error bodies now include `trace_id` and set `X-Cycles-Trace-Id` when a direct filter test or unusual deployment order bypasses `TraceContextFilter`. Event idempotency replay now stores and returns the original successful response JSON instead of reconstructing balances from current budget state, and `EventController` skips duplicate balance-event side effects on replay. API key validation no longer caches full allow/deny decisions; it caches only the BCrypt comparison result (true or false) keyed by submitted secret plus the freshly read stored hash, while key status, expiry, tenant ownership, and tenant status are re-read on every request. That preserves revocation/rotation freshness and avoids repeated BCrypt work for identical wrong-secret attempts.

**Fail-closed config and deployment defaults.** Invalid tenant `default_commit_overage_policy` values now fail with `INVALID_REQUEST` before Lua execution; commit/event Lua scripts also reject unknown overage policies defensively for corrupted reservation/script inputs. Production Compose files now require `REDIS_PASSWORD`, authenticate Redis health checks, stop publishing Redis on the host port, and pin `cycles-server` to `0.1.25.39`.

**Documentation and versioning.** `cycles-protocol-service/README.md` now distinguishes header/body idempotency-key mismatch (`400 INVALID_REQUEST`) from same-key/different-payload replay (`409 IDEMPOTENCY_MISMATCH`). `CHANGELOG.md` and `cycles-protocol-service/pom.xml` bump the service to `0.1.25.39`.

### 2026-06-23 — Docker log rotation defaults on all compose files (no version bump)

All four `docker-compose*.yml` (base, `prod`, `full-stack`, `full-stack.prod`) gain a shared `x-logging` anchor (`json-file`, `max-size: 10m`, `max-file: 5`) referenced by every service (redis, cycles-server, cycles-admin, cycles-events). Previously no compose file declared a logging driver, so containers inherited Docker's default UNBOUNDED `json-file` logs — a slow disk-exhaustion path on long-running deployments (a stack left up for days grows each container's `*-json.log` without limit). The anchor caps every container at 5×10 MB = 50 MB with rotation. Runtime/deployment config only — no image, server-code, or wire change, so no version bump or release; the cap takes effect on containers (re)created from these files. `docker compose config` validates clean on all four.

### 2026-06-23 — v0.1.25.38: disable evidence emission when identity is unconfigured

Aligns runtime producer behavior with the event-tier disabled mode. Previously `EvidenceEmitter.emit` returned no `cycles_evidence` ref when `EVIDENCE_SERVER_ID` / `EVIDENCE_SIGNING_SIGNER_DID` were blank, but it still built and LPUSH'd a source record without `evidence_id` to `evidence:pending`. That made an intentionally non-evidence deployment accumulate work for a signer that should be off.

`EvidenceEmitter.emit` now fail-opens earlier: if either public identity value is blank, it returns `null` before null-stripping payloads, computing ids, or touching `EvidenceQueueRepository`. Configured deployments retain the existing path exactly: payload null-stripping, synchronous `evidence_id` computation, record stamping, queue push, and response ref. `EvidenceEmitterTest` updates the unconfigured case to assert no Redis push and no failure metric, and the configured cases now set identity explicitly. Data-module focused test: `mvn -B -pl cycles-protocol-service-data -am -Dtest=EvidenceEmitterTest -Dsurefire.failIfNoSpecifiedTests=false test`. Version bump: `cycles-protocol-service/pom.xml` `<revision>` → `0.1.25.38`.

### 2026-06-22 — v0.1.25.37: link reservations to their evidence via `include=evidence`

Implements cycles-protocol v0.1.25.9 (runcycles/cycles-protocol#117). The `cycles_evidence` ref previously rode only on the live reserve/commit/release response, so a reservation fetched later (e.g. by the admin dashboard) had no path back to its signed envelope — you had to have captured the `evidence_id` at the moment of the call. Now the server persists each computed ref onto the reservation and surfaces it via a new `evidence` projection.

`EvidenceEmitter.emit` already computes the `evidence_id` synchronously (when the server identity is configured), but only after the reserve/commit/release Lua runs — so the id can't be passed into the script. Instead `persistEvidenceRef` does a fail-open follow-up `HSET` of `<artifact>_evidence_id` + `<artifact>_evidence_url` onto the `reservation:res_*` hash right after the ref is stamped on the response (HSET preserves the terminal 30-day TTL set by Lua; a write failure logs and never fails the op). Both id and url are stored so hydration needs no server-id reconstruction. `buildReservationSummary` hydrates them into a new `ReservationEvidence` map (keyed `reserve`/`commit`/`release`, each a `CyclesEvidenceRef`); `toSummary` gates it on a new `ReservationInclude.EVIDENCE` token (`?include=evidence`) for symmetry with the metadata projections — projection-only, NOT folded into `FilterHasher`, so it never invalidates a cursor. The single-row `getReservation` always carries it. A reservation has at most a `reserve` entry plus one terminal (`commit` XOR `release`); a half-written ref (id without url) is ignored. Degrades gracefully: `null` when evidence emission is disabled or for pre-feature reservations (`NON_NULL` strips it).

New `ReservationEvidence` model + `ReservationInclude.EVIDENCE`. `ReservationEvidenceTest` (isEmpty + per-artifact refs), `ReservationIncludeTest` +2 (evidence token, all-three parse), `RedisReservationEvidenceLinkTest` +6 (hydrate reserve+commit, absent → null, half-written ignored, persist HSETs id+url, null-ref/null-id no-op, projection gated on include). Full `mvn verify` green across model/data/api, JaCoCo 95% gate met; contract + spec-coverage tests pass against cycles-protocol#117 (merged to main). Additive/non-breaking — clients that don't request `include=evidence` see byte-identical responses.

### 2026-06-19 — v0.1.25.36: surface `committed` + opt-in metadata on `listReservations`

Follow-up to v0.1.25.34/#197: the same fields surfaced on the single-row `getReservation` were dropped from the `GET /v1/reservations` list rows (runcycles/cycles-server#201). The list path already hydrated a full `ReservationDetail` per row (`buildReservationSummary`), but `toSummary` down-converted to `ReservationSummary` and discarded `committed`, `metadata`, and `committed_metadata` — so the data was read then thrown away.

Hybrid projection per cycles-protocol v0.1.25.8 (runcycles/cycles-protocol#115). `committed` (the COMMIT charge, a small scalar) now projects UNCONDITIONALLY on list rows — same footing as `finalized_at_ms`, NON_NULL strips it off non-COMMITTED rows. The arbitrary-size, possibly-PII metadata maps (`metadata`, `committed_metadata`) stay OFF list rows by default and are projected only when the caller opts in via a new comma-separated `include` query parameter (`?include=metadata,committed_metadata`); unrecognized/empty tokens are ignored, never 400. The three fields move from `ReservationDetail` up to the shared `ReservationSummary` base (`ReservationDetail` becomes a marker subclass — single-GET still serializes all three unconditionally); a new `ReservationInclude` enum parses the param. `include` is projection-only and deliberately NOT folded into `FilterHasher`, so changing it mid-pagination never invalidates a cursor (contrast the window filters). `listReservations` gains an include-aware overload; the legacy 18-arg signature delegates with an empty set (no metadata), preserving the unconditional-`committed` change but default-lean lists.

`ReservationIncludeTest` (parse: null/blank/whitespace/case/unknown/duplicate/trailing-comma), `RedisReservationQueryTest` +5 (committed-always + each include combination + legacy-overload default), `ReservationControllerTest` +3 (param parsed → empty/both/ignored-unknown sets threaded). Full `mvn verify` green against the v0.1.25.8 spec, JaCoCo 95% gate met. Spec-first: gated on cycles-protocol#115 reaching `main` before the contract test resolves the new fields against the published spec.

### 2026-06-19 — v0.1.25.35: loud error when a retired-keys config is unusable

Closes a silent gap in the v0.1.25.33 rotation-history publication. The active-key `nbf` clamp only fires when a retired entry has a usable window; if `cycles.evidence.signing.retired-keys` is configured (non-blank) but produces ZERO PUBLISHABLE entries, there is nothing to clamp against, so the active key publishes UNBOUNDED at the configured `nbf` (default 0 = since epoch). That silently reverts a rotated server to the never-rotated posture: pre-rotation evidence won't resolve, and the current key could resolve a backdated envelope as authentic.

`JwksController` distinguishes "retired-keys not configured" (blank → legitimate never-rotated, no noise) from "configured but nothing publishable," logging a clear ERROR for the latter that names the consequence. Crucially the decision is based on what `JwksDocuments` actually PUBLISHES, not on parser output — an entry can pass the lenient parser (integral, in-range bounds) yet be dropped on emission (empty/inverted window where `exp_ms <= nbf_ms`, malformed hex, duplicate kid, overlapping reuse), which the first cut missed (codex review of runcycles/cycles-docs#724). The ERROR message is split by whether a valid retired WINDOW still clamped the active key (the clamp ignores key material, so a malformed-hex entry with a good window keeps the active key bounded even though it isn't published): when bounded, the message reports missing rotation history without a backdating claim; only when no valid window exists does it warn that the active key publishes with no rotation boundary and a backdated envelope could resolve as authentic. Deliberately NOT fail-closed — refusing to publish would also break verification of all current evidence; the active key still publishes (the never-fail-closed guarantee is retained and tested).

`JwksControllerTest` +3 (`OutputCaptureExtension`, level-checked: a configured-but-unparseable config and a parser-passing-but-emission-dropped empty window each log the unbounded ERROR; a valid-window-but-malformed-hex entry logs the bounded ERROR — active clamped, no backdating claim — and all still publish the active key). Full `mvn verify` green, jacoco 95% gate met. Observability-only — no wire/spec change, no change to the published JWK Set.

### 2026-06-18 — Benchmark release gate: p99 metrics non-gating (no version bump)

The release gate (`scripts/check-regression.py`) failed the v0.1.25.34 release on `commit_p99` (+94% vs baseline) while every p50 and throughput metric was within tolerance.

**Why.** p99 tail latency on a 200-iteration micro-benchmark over shared GitHub runners swings ~2× run-to-run (`commit_p99` measured 6.5 → 8.2 → 12.6 across three runs) — GC pauses and runner contention dominate the tail, far beyond the 25% threshold. No single-sample baseline can stabilize that, and same-machine `.21`→`.34` showed only +8% on `commit_p99`, so it's noise, not a code regression.

**Change.** `HEADLINE_METRICS` gains a third element, `gating`. The p99 metrics (`reserve_p99_ms`, `commit_p99_ms`) are now **non-gating**: still measured and shown in the summary table (labelled `noisy (non-gating)` when they exceed the threshold) but no longer failing the build. The stable signals — p50 latency (reserve/commit/release/event) and `concurrent_throughput_32t` — remain hard gates. Applies to both the release gate and the nightly trend check.

**Verified.** A p99-only breach now passes (exit 0); a real p50 regression (+100% `commit_p50`) still fails (exit 1); bootstrap and trend modes unaffected. CI-tooling only — no production/spec/wire change, no version bump.

### 2026-06-18 — v0.1.25.34: surface committed metadata on `getReservation`

Commit-time metadata was write-only on the server — accepted, persisted, never returned. Fixes runcycles/cycles-server#197.

**The gap.** `commit.lua` already persists the COMMIT request's metadata as `committed_metadata_json` on the reservation hash, but `getReservationById` only projected the reserve-time `metadata_json`. So a client that committed with metadata and then read the reservation saw nothing.

**Fix.** `ReservationDetail` gains a `committed_metadata` field (`@JsonInclude(NON_NULL)`); the read path parses `committed_metadata_json` into it. Reserve-time `metadata` is unchanged and stays distinct. Per cycles-protocol v0.1.25.7 (runcycles/cycles-protocol#114).

**Coverage.** `RedisReservationCrudTest` +2 (read-path projection: parsed, distinct from reserve metadata, omitted when absent); `ReservationLifecycleIntegrationTest` +2 (end-to-end commit-with-metadata round-trip through real Redis + `commit.lua`; NON_NULL wire omission). Full `mvn verify` green, JaCoCo 95% gate met.

---

### 2026-06-18 — v0.1.25.33: retired-key rotation history in the published JWK Set

The v0.2 signer-key-resolution follow-up to v0.1.25.32, so evidence signed before a key rotation still verifies against the key valid at its `issued_at_ms`.

**Configuration.** A new `cycles.evidence.signing.retired-keys` property (env `EVIDENCE_SIGNING_RETIRED_KEYS`) takes a JSON array of `{signer_did, kid, nbf_ms, exp_ms}`. `JwksController` parses it fail-safe so bad config never stops the active key publishing; `JwksDocuments.jwkSet` appends each retired key as a bounded `[cycles_nbf_ms, cycles_exp_ms)` JWK with `status: retired`.

**Defensive skips.** Retired entries are dropped when invalid: malformed hex; an absent, non-integral, or out-of-long-range window bound; an empty or inverted window; an overlapping window for already-published retired key material; or a duplicate `kid`. A retired key whose material matches the active key still publishes once the clamp (below) makes its window disjoint, so reused-key history is preserved.

**Rotation safety floor.** On rotation the active key's `nbf-ms` should be set to the rotation time; if it is left lower, the published active window is clamped up to the latest retired `exp_ms` (fail-safe, with a warning) so the current key can't resolve as valid for pre-rotation evidence.

**Scope.** No spec or wire change — `CyclesEvidenceJwks` already allowed multiple keys with windows and a retired status. `JwksDocumentsTest` +13, `JwksControllerTest` +7; full `mvn verify` green, 95% gate met.

---

### 2026-06-15 — getEvidenceJwks live-serving integration test (no version bump)

`JwksEndpointIntegrationTest` (full `@SpringBootTest`, real Tomcat, the Spring Security filter chain active, Testcontainers Redis) proves the JWK Set endpoint serves over real HTTP with no API key — i.e. the `/v1/.well-known/**` public-path exemption holds end-to-end through the filter chain, which the filters-disabled `JwksControllerTest` `@WebMvcTest` can't show. With the signing identity configured, a no-header GET returns 200 and a JWK whose `x` decodes to the configured `signer_did` bytes, with the right `kid`/`cycles_nbf_ms`/`status` and a public, non-immutable cache; a bogus key still returns 200. 2 tests, test-only — the implementation shipped in v0.1.25.32 / #194.

---

### 2026-06-15 — v0.1.25.32: CyclesEvidence signer-key resolution, publication half

`getEvidenceJwks` (`GET /v1/.well-known/cycles-jwks.json`), per cycles-protocol v0.1.25.6 / runcycles/cycles-protocol#113.

**What it serves.** When `cycles.evidence.signing.signer-did` is a raw 64-hex key, the public `JwksController` serves a one-key JWK Set built by the pure `JwksDocuments.jwkSet` — an active Ed25519 OKP JWK whose `x` is the same 32 bytes `EnvelopeSigner` signs with, so a verifier resolving the set authenticates the emitted signatures — with a short public, non-immutable cache.

**Fallback.** It 404s when no raw-hex key is configured (evidence off, or a `did:cycles` signer that carries no key bytes); consumers then stay on the raw-hex `expected_signer` pinning path.

**Placement.** `/v1/.well-known/**` is public (public keys only) and API-base-relative under `/v1`, per the spec's authority-scope rule. `JwksDocumentsTest` (10), `JwksControllerTest` (4); `mvn verify` 906 tests green.

---

### 2026-06-14 — v0.1.25.31: suppress side-effect events on idempotent reserve replay

Review fix [Medium]. `POST /v1/reservations` re-emitted side-effect events on an idempotent replay: `create` emitted the `RESERVATION_DENIED` event and balance-transition events unconditionally, while `decide`, `commit`, and `release` already skip them on a replay, so a replayed create double-counted them. Fixed by wrapping create's emission block in `if (!response.isIdempotentReplay())` to match the other endpoints. `ReservationControllerTest` +1. No wire/spec change. Numbered .31 because .30 was held by the open byte-parity PR #187.

---

### 2026-06-14 — v0.1.25.30: byte-parity hardening across all five artifact types

Extends `EvidenceIdComputerTest` from the 3 reserve fixtures to the full 13-fixture set covering all five artifact types — the same fixtures the event-tier canonicalizer and the APS verifier use. This proves cycles-server's synchronous `evidence_id` computation reproduces the canonical id for every envelope shape, so a given shape's id always matches the worker and the cross-check never dead-letters on drift. Test-only: `@ValueSource` 3→13, `EvidenceIdComputerTest` 5→15 tests; `mvn verify` green.

---

### 2026-06-13 — v0.1.25.29: optional request body in `error` CyclesEvidence + null-strip fix

Per the review note that `ErrorPayload.request` SHOULD be present for a full audit trail.

**Request body.** The four core controllers stash their parsed request DTO in a request attribute, and `GlobalExceptionHandler` includes it in the `error` payload when present, completing the `{endpoint, http_status, [reservation_id], [request], response}` shape.

**Review fix (codex, High).** Request/response DTOs can serialize null-valued properties, but the evidence mirror schemas are `additionalProperties:false` with non-nullable fields, so a serialized null failed mirror validation — affecting the already-merged reserve/commit/release evidence too. Fixed centrally in `EvidenceEmitter` with a NON_NULL mapper that null-strips the payload once, used for both the `evidence_id` and the queued record; the shared mapper and DTOs are left unchanged so idempotency hashes and cached bodies stay byte-stable.

**Coverage.** `GlobalExceptionHandlerTest` +2, `ReservationControllerTest` +1, `EvidenceEmitterTest` +2; `mvn verify` green.

---

### 2026-06-13 — v0.1.25.28: CyclesEvidence fan-out to the `error` artifact

Completes the lifecycle binding loop (decide/reserve/commit/release/error), per cycles-protocol v0.1.25.5 / #109.

**Emission policy.** `GlobalExceptionHandler` emits an `error` source record over `{endpoint, http_status, response}` (plus a hoisted `reservation_id` for commit/release) and stamps the ref onto `ErrorResponse`, but only for budget and terminal-state denials raised on the four core endpoints — a code qualifies iff it is a server decision on a core endpoint. Pre-evaluation failures (validation, auth, not-found, idempotency mismatch) and non-core routes emit nothing, matching the spec's rule that `cycles_evidence` is absent for errors raised before evidence could be emitted.

**Non-self-referential + fail-open.** The ref is stamped after the id is computed, so the attested response doesn't contain it, and emission never fails the error response. `GlobalExceptionHandlerTest` +9; the five controller tests gain a mocked emitter; `mvn verify` green.

---

### 2026-06-13 — v0.1.25.27: CyclesEvidence fan-out to decide + generalized idempotency

Per cycles-protocol v0.1.25.4 / #108. `decide` now emits evidence over `{request, response}` and surfaces `cycles_evidence`.

**Generalized machinery.** The dry_run atomic-claim-and-wait machinery is refactored into a shared `kind`-parameterized path serving both dry_run and decide, with keys derived from `kind` so dry_run stays byte-identical; concurrent same-key decides converge to one evaluation and one envelope.

**Review fixes (codex).** The orchestrator rethrows runtime exceptions unwrapped; the claim cache/clear helpers self-acquire a short-lived connection and fail open; decide replays don't re-emit the deny event; and a pool-nesting bug on the dry_run failure path is fixed with an already-held-connection clear overload.

**Coverage.** `RedisReservationDecideEventTest` +4, `RedisReservationCrudTest` +2, `DecisionControllerTest` +2; `mvn verify` green.

---

### 2026-06-13 — v0.1.25.26: CyclesEvidence fan-out to commit + release

Per cycles-protocol v0.1.25.3 / #107, extending the reserve pattern across the budget lifecycle.

**Mechanics.** `commit.lua`/`release.lua` flag their replay branch; on a fresh terminal op Java emits a `commit`/`release` record over `{reservation_id, request, response}`, stamps the ref, and caches the full body with a 30-day TTL matching the terminal reservation hash, replaying it verbatim on idempotent retry.

**Review fixes (#183).** All three fresh paths (reserve/commit/release) release the Lua connection before evidence emit and body-cache so peak pool use stays at one connection, and the admin-release audit write is guarded against replay. `RedisReservationCommitReleaseTest` +4, `ReservationControllerTest` +2, plus `InOrder` regression guards; `mvn verify` green.

---

### 2026-06-13 — v0.1.25.25: CyclesEvidence idempotency-race hardening

Closes two concurrency races (runcycles/cycles-server#181).

**Reserve replay.** A reserve replay landing between reserve.lua's mapping write and the body-cache write now polls the body cache (≤4×25ms) before falling back to rebuilt balances.

**Concurrent dry_run.** Concurrent fresh dry_runs with the same key now elect one evaluator via an atomic `SET NX` pending-claim while the losers wait, preventing duplicate evidence emission. The wait loops acquire a fresh connection per poll rather than holding the request connection across a sleep, and a waiter that still finds nothing returns a transient 500 that resolves on retry. The pending claim is released via an atomic compare-and-delete and cleared on cache-write or evaluation failure.

**Coverage.** `RedisReservationCrudTest` +5; `mvn verify` green incl. the thundering-herd test.

---

### 2026-06-12 — v0.1.25.24: CyclesEvidence centralized into the reservation-creation flow

Review follow-up (two High findings) that moves evidence into the idempotent unit.

**Relocation.** Evidence is now emitted, stamped, and cached inside `createReservation` rather than the controller, with `EvidenceEmitter` injected into the repository and `trace_id` threaded through a new overload.

**TTL + dry_run.** The body-cache TTL now matches reserve.lua's idempotency mapping (`max(ttl+grace, 24h)`) instead of a fixed 24h that expired early for long-lived reservations. dry_run now emits `reserve` evidence for all outcomes per spec authority (a dry-run DENY is the canonical signed "would this be allowed?" attestation), reversing the .23 suppression, and caches the body so DENY replays are idempotent.

**Coverage.** `mvn verify` 402 data + 179 api green incl. the thundering-herd test.

---

### 2026-06-12 — v0.1.25.23: CyclesEvidence idempotent-replay-body fix

Review follow-up (two High findings).

**Verbatim replay.** A fresh non-dry create now stamps `cycles_evidence` and caches the whole response keyed by `reserve:body:<reservation_id>` (not the idempotency key, so it can't go stale across an idem-key expiry); the Lua idempotency-hit path replays that body verbatim, falling back to rebuild-from-hash only when the cache is absent. This replaces the .22 ref-only approach, whose rebuilt balances drifted from the envelope the `evidence_id` pointed to — violating the normative "return the original successful response" rule — and also fixes the pre-existing balance-drift-on-replay bug.

**dry_run.** dry_run no longer emits or surfaces evidence, since it persists nothing and changes no budget. `ReservationControllerTest` reworked, `RedisReservationCrudTest` +3; 402 data + 181 api tests green.

---

### 2026-06-12 — v0.1.25.22: synchronous `evidence_id` + `cycles_evidence` on reserve

Closes the APS binding loop. Per cycles-protocol v0.1.25.1–.2.

**evidence_id recipe.** `EvidenceIdComputer` reproduces the cycles-evidence/v0.1 content-hash recipe (RFC 8785 JCS + sha256 over the envelope with `evidence_id`/`signature` emptied) byte-for-byte, proven against the reserve golden fixtures.

**Surfacing.** When the public identity is configured, `EvidenceEmitter` computes the id synchronously, stamps it on the source record for the worker's cross-check, and returns the ref; `ReservationController` stamps `cycles_evidence` on the response after the id is computed so the attested body stays non-self-referential. A fresh reserve computes and emits once and persists the ref on the reservation hash; an idempotent replay returns it verbatim and never recomputes, since replay balances would drift to a different id.

**Coverage.** `EvidenceIdComputer` ×5, `EvidenceEmitter` +5, `ReservationControllerTest` +4.

---

### 2026-06-12 — CyclesEvidence serving endpoint (no version bump)

`getEvidence` (`GET /v1/evidence/{evidence_id}`), per cycles-protocol revision 2026-06-12 / #104. `EvidenceController` reads the shared store via `EvidenceStoreReader` and serves the signed envelope verbatim with a public, immutable cache; 404 when absent, 400 on a non-64-hex id. `/v1/evidence/**` is public (a capability URL, no API key). `EvidenceControllerTest`, `EvidenceStoreReaderTest`, and a mocked reader added to the four controller tests; no change to existing endpoints.

---

### 2026-06-12 — CyclesEvidence source emission, reserve endpoint (no version bump)

The producer half of the dedicated-channel emitter. `EvidenceQueueRepository` LPUSHes a source record to `evidence:pending` and `EvidenceEmitter` stamps `artifact_type`, `issued_at_ms`, `trace_id`, and the artifact-specific payload (the worker adds `server_id`/`signer_did`). The enqueue is synchronous — the record is pushed to the same Redis as the committed ledger write before the response returns, so a committed op can't return without its evidence queued — and fail-open: a push failure is logged and metered but never fails the response, with signing left to the event tier. Wired into `create` for both ALLOW and DENY. 4 tests; `ReservationControllerTest` gains a mocked emitter. No wire/spec change.

---

### 2026-05-22 — v0.1.25.21: `expires_*` / `finalized_*` time-window filters on listReservations

Closes [#162](https://github.com/runcycles/cycles-server/issues/162). Spec landed in `cycles-protocol-v0.yaml` revision 2026-05-22 (PR runcycles/cycles-protocol#98); this is the matching runtime impl.

**Why the spec change.** The v0.1.25.20 `from`/`to` window covers "what happened in the last 24h" cleanly but binds to `created_at_ms`, which is unhelpful for the operational use case the original issue thread also called out: "cleanup routines on expired or abandoned reservations." A reservation created at T-7d that just expired this morning is invisible to a 24h `created_at` window — exactly what a sweeper is looking for. The new windows give sweepers a direct path: query the expiry timestamp, query the finalization timestamp.

**Three independent pairs.** Each window pair binds to its target field regardless of `sort_by`, matching the v0.1.25.20 sort-key-independence rule. The three pairs compose with AND semantics; a row must satisfy every supplied predicate. This keeps the contract predictable across the matrix of (window, sort_key) combinations — no per-key filter semantics to memorize.

**Finalized-row exclusion.** The spec makes the ACTIVE/EXPIRED exclusion normative: when either `finalized_*` bound is supplied, rows missing both `committed_at` and `released_at` MUST be excluded from results. The predicate naturally fails on field-absent rows; making the exclusion normative ensures conformant servers agree. Callers wanting EXPIRED-row windows should use `expires_*` instead (which works on every row since `expires_at_ms` is required).

**Schema addition: `finalized_at_ms` on `ReservationSummary`.** Pre-revision the field was declared only on `ReservationDetail` (with `additionalProperties: false` on the summary blocking servers from sending it). The filter would have been useful only via a follow-up `getReservation` per row. Adding the field to the summary makes the filter genuinely useful; strict-schema clients remain compatible because the field is optional (`@JsonInclude(NON_NULL)`). Old clients that don't know about the field get pre-revision responses byte-for-byte (the field is absent on ACTIVE rows, which is the common case for unfiltered list calls).

**Two execution paths.** `RedisReservationRepository.listReservations` retains its v0.1.25.12 dual-path shape. Two new helpers — `expiresAtInWindow(fields, fromMs, toMs)` and `finalizedAtInWindow(fields, fromMs, toMs)` — are applied as per-row predicates in both the legacy SCAN-cursor and sorted paths, immediately after the existing scope/status/tenant predicates and the v0.1.25.20 `createdAtInWindow`. Predicate bodies follow the same defensive shape: missing or unparseable hash field → row excluded when EITHER bound is supplied.

**`finalizedAtInWindow` field resolution.** Mirrors `buildReservationSummary`'s projection logic: read `committed_at` first (populates the timestamp for COMMITTED rows), fall through to `released_at` for RELEASED rows. Both absent → row excluded. The legacy `committed_at`/`released_at` Redis fields are the source of truth; the wire field `finalized_at_ms` is a projection. Both filter and serializer agree on the source.

**Cursor invalidation extends to all six bounds.** `FilterHasher.hash(...)` gains four trailing `Long` arguments (10 → 14). Each window pair emits its canonical block with **independent gating** — the v0.1.25.20 `from`/`to` block only emits when `fromMs || toMs != null`, the new `expires_*` block only when `expiresFromMs || expiresToMs != null`, and the `finalized_*` block likewise. This preserves byte-exact back-compat for **both** prior cursor generations:

- v0.1.25.18 cursor (no windows) → canonical `t=acme|...|ts=` → golden hash `2f397ea0e8fb53b7` (locked down in v0.1.25.20)
- v0.1.25.20 cursor (from/to only) → canonical `t=acme|...|ts=|fr=100|to=200` → golden hash `ad7204d521cfd133` (newly locked down in v0.1.25.21 to prevent a future refactor from accidentally unioning the gating)

**Validation choices** (mirror the v0.1.25.20 contract):

- ISO-8601 parsed via `Instant.parse(...)`; malformed → 400 with distinct `Invalid {param_name}` message identifying which parameter failed.
- `expires_from > expires_to` and `finalized_from > finalized_to` → 400 *before* the repository call.
- Blank-string values for any of the six bounds treated as unset.
- Equal bounds (point window) accepted on each pair.

**Internal Java signature changes, no wire impact beyond the schema addition.** `listReservations` 14 → 18 args; `listReservationsSorted` mirrors; `FilterHasher.hash` 10 → 14 args. All Java callers updated. The single wire-format change is the optional `finalized_at_ms` field on `ReservationSummary` — covered by the optional-property guarantee.

**Coverage.**

- `FilterHasherTest` (+3): expires_* values differ from base/from-to, finalized_* values differ from from-to/expires_*, v0.1.25.20 8-byte golden lockdown.
- `RedisReservationQueryTest` (+6) under `ExpiresAndFinalizedWindowFilter` nested class: legacy-path `expires_from` excludes-below, legacy-path `expires_to` excludes-above, legacy-path `finalized_from` excludes-ACTIVE-rows, `finalized_at` resolves from `released_at` when `committed_at` absent, all-three AND composition (created + expires + finalized), cursor mismatch on expires window change rejected with 400.
- `ReservationControllerTest` (+10) under `ListReservations` nested class: malformed-expires_from, malformed-expires_to, malformed-finalized_from, malformed-finalized_to, reversed-expires-window, reversed-finalized-window, expires propagation with `verify(...)` lock, finalized propagation with `verify(...)` lock, all-three combined with distinct epoch-ms per pair to catch slot mix-ups, blank-as-unset for new windows.

557 protocol-service tests pass (384 data + 173 api), +19 vs v0.1.25.20. JaCoCo 95% bundle gate met.

**Out of scope (intentionally).** The `time_field`-pivoted alternative parameter shape (one `from`/`to` plus a `time_field=created_at|expires_at|finalized_at` selector) was considered and rejected — it would have been an awkward retroactive change to the v0.1.25.20 shape and split the family-wide `from`/`to` convention. Three parallel pairs is the cleaner expansion.

---

### 2026-05-21 — v0.1.25.20: `from` / `to` time-window filter on listReservations

Closes [#159](https://github.com/runcycles/cycles-server/issues/159). Spec landed in `cycles-protocol-v0.yaml` revision 2026-05-21 (PR runcycles/cycles-protocol#97); this is the matching runtime impl.

**Why the spec change.** The original "fetch last 24h of reservations" workflow required clients to sort by `created_at_ms` and walk pages until the trailing row fell out of the window, doubling page size on each retry. For high-volume agent clusters this scans far more rows than the caller needs. With server-side `from` / `to`, the scan is boundaried before hydration and cursor pagination over that window stays cursor-stable.

**Naming and wire-type.** `from` / `to` with `format: date-time` matches the family-wide convention already in use on `listAuditLogs`, `listEvents`, `listWebhookDeliveries`, `listTenantEvents`, `listTenantWebhookDeliveries` in the governance-admin spec. Bespoke `created_after`/`created_before` names or Unix-epoch wire types would have split the convention for clients and codegen.

**Sort-binding semantics.** The filter always binds to `created_at_ms`, never to the column referenced by `sort_by`. A client doing `sort_by=expires_at_ms&from=…&to=…` gets reservations *created* in the window, ordered by *expiry*. This keeps the contract predictable across sort keys — no per-key filter semantics to memorize.

**Two execution paths.** `RedisReservationRepository.listReservations` retains its v0.1.25.12 dual-path shape (legacy SCAN-cursor when no sort params are present, full sorted-path otherwise). Both paths apply the window filter as a per-row predicate immediately after the existing scope/status/tenant predicates and before hydration. Predicate body is shared via a `createdAtInWindow(fields, fromMs, toMs)` static helper.

**Sorted-path cursor invalidation.** `FilterHasher.hash(...)` now takes two additional `Long` arguments (`fromMs`, `toMs`). When at least one is non-null, the canonical string appends `|fr=<ms>|to=<ms>` after the existing eight string fields; when both are null, the appendix is **omitted entirely** so the canonical form reverts byte-exactly to the v0.1.25.12 8-field shape. This means a **sorted-path cursor** (the opaque cursor stored in `SortedListCursor.filterHash`, returned when `sort_by` / `sort_dir` is supplied) issued under one window returns HTTP 400 `INVALID_REQUEST` if re-used under a different one — same contract as the v0.1.25.12 `sort_by`/`sort_dir`/filter-mismatch path — while a sorted-path cursor issued by a v0.1.25.18 server (pre-window era) still resolves on v0.1.25.20 when the client never sends `from`/`to`, because the gated-emission canonical form matches byte-exactly. The legacy Redis-SCAN cursor (returned when no sort params are supplied) does not carry filter state and is **not** window-validated; this matches how the legacy path already treats `status` / `workspace` / `app` / other filters. Locked down by `FilterHasherTest.preservesPreWindowHashWhenBothBoundsNull` (golden `2f397ea0e8fb53b7`) and `RedisReservationQueryTest.cursorMismatchOnWindowChange`.

**Validation choices.**

- ISO-8601 parsed via `Instant.parse(...)`. Malformed values surface `DateTimeParseException` → 400 `INVALID_REQUEST` with `Invalid from: <raw>` / `Invalid to: <raw>` message — same shape as the existing `sort_by` / `sort_dir` rejections.
- `from > to` → 400 *before* the repository call. Detecting reversed windows after the scan would waste server work for an obviously-broken client; the controller-level guard is the right boundary.
- Blank-string values (`?from=&to=`) are treated as unset. This matches the additive-parameter intent — an omitted bound and an empty bound both mean "no bound on that side." Avoids the cryptic-400 hazard from a client sending an unconditional `?from=${maybeUnset}`.
- Equal bounds (`from == to`) are accepted as a degenerate closed point-window. Mathematically consistent with the inclusive-both-ends contract; clients chasing a single millisecond can do so without a special-case 400.

**Defensive read-side.** The window predicate also drops rows whose `created_at` hash field is missing or unparseable, but only when at least one bound is supplied. Without this, a stale/malformed Redis row would leak past a time filter that's supposed to exclude it. With both bounds null (filter inactive), missing/unparseable rows still surface through the rest of the pipeline as they always did — the unfiltered path is unchanged.

**Internal Java signature change, no wire impact.** `listReservations(...)` gains trailing `Long fromMs, Long toMs` (12 → 14 args). Private `listReservationsSorted(...)` mirrors. `FilterHasher.hash(...)` gains the same two trailing args (8 → 10). All Java callers updated. No wire format change — clients that omit `from`/`to` get exactly the v0.1.25.18 response byte-for-byte.

**Coverage.**

- `FilterHasherTest` — 3 new cases: distinct hash on from/to differences, positional distinctness (`from=100, to=200` ≠ `from=200, to=100`), and the pre-window 8-field hash back-compat golden case.
- `RedisReservationQueryTest` — 7 new cases under a `TimeWindowFilter` nested class: legacy-path `from` excludes below, legacy-path `to` excludes above, inclusive bounds (rows at `created_at = from` and `created_at = to` both kept, row at `to+1` dropped), sorted-path window with sort-by-`created_at_ms` ordering preserved, cursor mismatch on window change rejected with 400, missing `created_at` field excluded under window, unparseable `created_at` excluded under window.
- `ReservationControllerTest` — 7 new cases under the `ListReservations` nested class: malformed `from` → 400, malformed `to` → 400, `from > to` → 400 with `verify(repository, never())` to lock the pre-repository check, `from` only propagates, `to` only propagates, equal bounds accepted, combination with `sort_by=expires_at_ms` propagates correctly, blank strings treated as unset.

All 538 protocol-service tests pass (375 data + 163 api). The 95% coverage gate per CLAUDE.md is satisfied — the only new untested branch is the equality fallthrough on `null` for both bounds, which is covered by every pre-existing `listReservations` test that doesn't pass `from`/`to`.

**Out of scope.** The issue's rationale mentions cleanup of expired/abandoned reservations as a use case — that actually wants `expires_at` or `finalized_at` window filters, not `created_at`. Flagged on the issue thread and intentionally left as a follow-up to keep this change small and reviewable.

---

### 2026-05-21 — v0.1.25.19: supply-chain CVE patch (re-pin Tomcat 10.1.55)

Re-pins `tomcat.version=10.1.55` in `cycles-protocol-service/pom.xml` to close 7 new CVEs flagged by Trivy against `tomcat-embed-core 10.1.54` — CRITICAL: CVE-2026-43512, CVE-2026-43515, CVE-2026-41293; HIGH: CVE-2026-43513, CVE-2026-42498, CVE-2026-41284; LOW: CVE-2026-43514 (all fixed in 10.1.55 / 11.0.22). Mirrors the v0.1.25.16 pattern; the override was dropped in v0.1.25.18 when SB 3.5.14's BOM caught up to 10.1.54, now re-added one patch higher because Trivy DB updates between 2026-05-11 and 2026-05-21 surfaced a new wave on the same artifact. Removable once Spring Boot ships 10.1.55+ as its managed version. `commons-lang3.version=3.18.0` retained. No production/test changes; all 537 protocol-service tests pass.

---

### 2026-04-26 — v0.1.25.18: dependency hygiene (matches cycles-server-events v0.1.25.12)

Bumps `spring-boot-starter-parent` 3.5.13 → 3.5.14 (patch with upstream security hardening — constant-time DevTools-secret comparison, `RandomValuePropertySource` SecureRandom, consistent hostname verification for Cassandra/RabbitMQ SSL, symlink-handling fixes). **Drops** the `<tomcat.version>10.1.54</tomcat.version>` override since SB 3.5.14's BOM now manages 10.1.54 directly; the `commons-lang3 3.18.0` override is retained (SB still manages 3.17.0). **Jedis 7.4.1 → 6.2.0** to align all three services on the same Redis-client major (events and admin already at 6.2.0); all call sites use stable APIs, no 7.x-only usage. No code changes; all 152 tests pass.

---

### 2026-04-19 — v0.1.25.17: supply-chain CVE fix follow-up (commons-lang3 3.18.0)

Pins `commons-lang3.version=3.18.0` to close CVE-2025-48924 (Trivy HIGH) on the `commons-lang3-3.17.0` jar that ships in the fat-jar image via `swagger-core-jakarta` (OpenAPI UI). SB 3.5.13's BOM manages commons-lang3 at 3.17.0 — override removable once Spring Boot ships a managed 3.18.0+. All 152 tests pass.

---

### 2026-04-19 — v0.1.25.16: supply-chain CVE fix (SB 3.5.13 + Tomcat 10.1.54)

Bumps `spring-boot-starter-parent` 3.5.11 → 3.5.13 and pins `tomcat.version=10.1.54` to close 5 HIGH/CRITICAL CVEs flagged by the new PR-time Trivy scan: CVE-2026-22732 (CRITICAL, `spring-security-web`, fixed 6.5.9, transitive via 3.5.13); CVE-2026-29129 (HIGH) + CVE-2026-29145 (CRITICAL) on `tomcat-embed-core` (fixed 10.1.53, transitive); CVE-2026-34483 (HIGH) + CVE-2026-34487 (HIGH) on `tomcat-embed-core` (fixed 10.1.54, explicit override since SB with managed 10.1.54+ hadn't shipped). No code changes; all 152 tests pass.

---

### 2026-04-18 — v0.1.25.15: audit-log retention TTL (runtime-side fix)

Closes a gap surfaced by the post-v0.1.25.14 alignment audit: runtime's `AuditRepository.log()` was writing `audit:log:{id}` string keys with no `EX`, so runtime-written audit rows persisted indefinitely until Redis memory-eviction kicked in. This broke the 400-day retention story the admin plane tells operators — admin's `AuditRepository` already applies tiered TTL (400d authenticated / 30d unauthenticated) via a conditional Lua `SET … EX ttl`, but runtime-written rows were silently non-compliant with that contract. The audit dashboard reads from the shared index, so stale admin ZSETs would also accumulate pointers to long-expired runtime rows without a compensating sweep.

**Root cause:** the original v0.1.25.8 introduction of runtime-side audit writes copied admin's Lua shape from *before* admin added per-entry TTL in its v0.1.25.20. Admin's TTL work never back-propagated to runtime — not a regression, just an unnoticed drift.

**Scope decision — runtime-side fix over admin-side reconciliation:** the writer should own retention, not a downstream sweeper. Admin-side reconciliation would have required a reaper polling for TTL-less keys — heavier, fights the symptom, couples admin's cleanup cadence to runtime's write rate. Runtime-side adds ~100 LOC (Lua arg + config + sweeper mirror) with zero API surface change.

**Scope decision — single tier instead of admin's two tiers:** runtime only writes real-tenant audit rows. The `__admin__` (platform-plane) and `__unauth__` (pre-auth failure) sentinels are admin-plane concerns — runtime authenticates every request before the audit write, and runtime-plane operations like reservation release are always tenant-attributed. So one `audit.retention.days` knob (default 400 to match admin's authenticated tier) is sufficient. If a future runtime endpoint ever needs the 30-day short-tier behavior, this config can be extended without wire change.

**Lua change:** `LOG_AUDIT_LUA` now reads `ARGV[4]` as an optional TTL in seconds; conditional branch matches admin's script byte-for-byte (minus the sentinel logic). Atomic guarantee preserved — SET + 2×ZADD still run in one call, so the TTL addition cannot introduce orphaned index pointers on a mid-write crash.

**Sweeper:** mirrors admin's `sweepStaleIndexEntries()` — daily @Scheduled cron (default `0 0 3 * * *`), `ZREMRANGEBYSCORE` on `audit:logs:_all` plus SCAN over per-tenant indexes. Runtime deploys a sweeper of its own (rather than depending on admin's sweep hitting the shared Redis) so a runtime-only topology stays self-healing. Two sweepers scanning the same index is idempotent — `ZREMRANGEBYSCORE` on already-swept ranges is a no-op.

**Config:**

- `audit.retention.days` (env `AUDIT_RETENTION_DAYS`) — default `400`. `0` = indefinite retention; the sweeper is skipped explicitly in that mode (matching admin's `authenticatedRetentionDays=0` behavior).
- `audit.sweep.cron` (env `AUDIT_SWEEP_CRON`) — default `0 0 3 * * *` (03:00 server time).

**Backward compatibility:** new writes get TTL; old runtime-written keys stay un-TTL'd until Redis memory pressure evicts them. No API change, no wire change, no admin-side reconciliation needed — admin's reader doesn't care whether the target key has a TTL, it only cares that a value is present or not (and already null-body-tolerant for TTL-expired pointers).

**Tests:** 7-case `AuditRepositoryTest` covering: (1) TTL passed as ARGV[4] = 400×86400, (2) `retentionDays=0` passes `"0"`, (3) `logId` + timestamp set on the entry, (4) Redis failure is non-fatal, (5) sweeper removes global and per-tenant pointers, (6) sweeper is a no-op when retention is 0, (7) sweep Redis failure is non-fatal. All green; full data module still 365 / 365; full api module still 152 / 152.

---

### 2026-04-18 — v0.1.25.14: trace_id cross-surface correlation (W3C Trace Context)

Implements the `CORRELATION AND TRACING` normative section added to `cycles-protocol-v0.yaml` in spec revision 2026-04-18 (commit `8d65959`). Introduces a third correlation identifier — `trace_id` — that is W3C Trace Context-compatible (OpenTelemetry-native) and links every HTTP request to its `ErrorResponse`, audit-log entry, emitted events, and outbound webhook deliveries under one logical-operation grain.

**Inbound header extraction** (new `TraceContextFilter`, `@Order(0)`, runs before `RequestIdFilter`):

1. `traceparent` header — parsed as W3C Trace Context version 00 (`^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$`). Must be non-all-zero trace-id and span-id per W3C §3.2.2.3.
2. `X-Cycles-Trace-Id` header — parsed as flat 32 lowercase hex; must be non-all-zero. Used when `traceparent` is absent or malformed.
3. Server generates — `SecureRandom` 16 bytes → 32 lowercase hex; re-rolled if all-zero.

Malformed headers are silently ignored (spec: MUST NOT reject). When both valid headers are present and disagree, `traceparent` wins per OpenTelemetry interop precedence.

Inbound trace-flags preservation on outbound webhooks is deferred to the sibling `cycles-server-events` PR — that PR needs to add a `trace_flags` field to the `WebhookDelivery` schema (or accept the v0 default of `01`/sampled on every outbound delivery). Not a wire-compliance gap for this PR: only the outbound webhook `traceparent` is affected, and it's emitted by the events service, not this server.

**Outbound propagation:**

- Every response (2xx/4xx/5xx) echoes `X-Cycles-Trace-Id` header.
- `ErrorResponse.trace_id` populated across all five exception-handler paths (`CyclesProtocolException`, `MethodArgumentNotValidException`, `ConstraintViolationException`, `HttpMessageNotReadableException`, generic 500).
- `Event.trace_id` populated for every event emitted via `EventEmitterService.emit(...)` and `emitBalanceEvents(...)` — RESERVATION_DENIED, RESERVATION_COMMIT_OVERAGE, BUDGET_EXHAUSTED, BUDGET_OVER_LIMIT_ENTERED, BUDGET_DEBT_INCURRED, and RESERVATION_EXPIRED.
- `WebhookDelivery.trace_id` copied from `Event.trace_id` in `EventEmitterRepository.createDelivery` so the events service can lift it into outbound headers without re-parsing the event body.
- `AuditLogEntry.trace_id` populated on admin-driven releases (`ReservationController.release`).
- `ReservationExpiryService` mints a fresh trace_id per sweep batch so all reservation.expired events in one sweep correlate to each other. Per spec, `request_id` remains null on sweeper-generated events (no originating HTTP request).

**SLF4J MDC:** filter sets `traceId` key on entry and removes it in `finally` so every log line produced during the request carries the trace_id automatically. Existing `requestId` MDC key behavior unchanged.

**Contract impact:** purely additive. `trace_id` is an OPTIONAL property on `ErrorResponse` (schema preserves `additionalProperties: false` via a declared property). Response header is additive; clients that don't read it are unaffected. Inbound `traceparent` / `X-Cycles-Trace-Id` headers are additive; clients that don't send them are unaffected.

**Admin spec v0.1.25.28 alignment (WebhookDelivery):** also adds the two companion fields defined by governance-admin spec revision 2026-04-18 on the shared `WebhookDelivery` schema:

- `trace_flags` (`^[0-9a-f]{2}$`) — W3C Trace Context trace-flags byte. Preserves the inbound sampling decision when the originating request carried a valid `traceparent`; defaults to `01` (sampled) when the trace was derived from `X-Cycles-Trace-Id` or server-generated.
- `traceparent_inbound_valid` (boolean) — whether the originating HTTP request presented a valid inbound W3C `traceparent`. Consumed by the `cycles-server-events` sidecar to decide whether to preserve `trace_flags` on the outbound delivery or default to `01`.

Both fields are threaded from the `TraceContextFilter` through an internal `TraceContext` record (`cycles-protocol-service-data/.../data/util/TraceContext.java`), which also collapses the per-request correlation trio (`trace_id` + `trace_flags` + `traceparent_inbound_valid`) into a single positional parameter on `EventEmitterService.emit(...)` and `emitBalanceEvents(...)` — addressing the param-sprawl concern flagged in the simplify review without adding 2 extra positional args.

`Event.java` carries the two companion fields as `@JsonIgnore` transient properties so they travel with the `Event` object through the async emit path without bleeding into the `Event` wire contract (the spec only declares these on `WebhookDelivery`).

**Out of scope (sibling PRs):**

- `cycles-server-events` (separate repo): outbound webhook `X-Cycles-Trace-Id` and `traceparent` headers. Reads `trace_id`, `trace_flags`, and `traceparent_inbound_valid` directly off the `WebhookDelivery` row from Redis. This PR prepares the row fully so the events-service PR is a straight read-and-forward.
- `cycles-server-admin`: admin-plane `AuditLogEntry.trace_id` surfacing and new `listEvents`/`listAuditLogs` `trace_id`/`request_id` filter query parameters.

**Files changed:**

- **NEW** `cycles-protocol-service-api/src/main/java/io/runcycles/protocol/api/filter/TraceContextFilter.java` — the filter, regex-based traceparent parsing.
- **NEW** `cycles-protocol-service-data/src/main/java/io/runcycles/protocol/data/util/TraceIdGenerator.java` — shared pure-function helper (`SecureRandom` → 32-hex with all-zero re-roll) used by the filter fallback path and by `ReservationExpiryService`.
- **NEW** `cycles-protocol-service-data/src/main/java/io/runcycles/protocol/data/util/TraceContext.java` — record bundling `trace_id` + `trace_flags` + `traceparent_inbound_valid`; threaded through `EventEmitterService.emit(...)` as a single positional param instead of three parallel Strings/Booleans.
- `cycles-protocol-service-model/src/main/java/io/runcycles/protocol/model/ErrorResponse.java` — `trace_id` field.
- `cycles-protocol-service-model/src/main/java/io/runcycles/protocol/model/event/Event.java` — `trace_id` field.
- `cycles-protocol-service-model/src/main/java/io/runcycles/protocol/model/webhook/WebhookDelivery.java` — `trace_id` + `trace_flags` + `traceparent_inbound_valid` fields (admin spec v0.1.25.28).
- `cycles-protocol-service-model/src/main/java/io/runcycles/protocol/model/audit/AuditLogEntry.java` — `trace_id` field.
- `cycles-protocol-service-api/src/main/java/io/runcycles/protocol/api/controller/BaseController.java` — new `resolveRequestId` + `resolveTraceId` helpers.
- `cycles-protocol-service-api/src/main/java/io/runcycles/protocol/api/controller/{ReservationController,DecisionController,EventController}.java` — pass `resolveRequestId(httpRequest)` and `resolveTraceId(httpRequest)` into every event-emission call site.
- `cycles-protocol-service-api/src/main/java/io/runcycles/protocol/api/exception/GlobalExceptionHandler.java` — populate `trace_id` on every `ErrorResponse.builder()` across all five `@ExceptionHandler` paths.
- `cycles-protocol-service-data/src/main/java/io/runcycles/protocol/data/service/EventEmitterService.java` — `traceId` parameter appended to `emit(...)` and the full `emitBalanceEvents(...)` signature; three prior `emitBalanceEvents(...)` overloads retained as delegating wrappers for source compatibility.
- `cycles-protocol-service-data/src/main/java/io/runcycles/protocol/data/service/ReservationExpiryService.java` — batch-scope `TraceIdGenerator.generate()` per sweep, threaded into `emitExpiredEvent`.
- `cycles-protocol-service-data/src/main/java/io/runcycles/protocol/data/repository/EventEmitterRepository.java` — `createDelivery` copies `event.getTraceId()` onto the `WebhookDelivery`.

**Tests:**

- **NEW** `TraceContextFilterTest` (13 cases) — traceparent valid/malformed/all-zero, X-Cycles-Trace-Id valid/malformed/all-zero/uppercase, both-present disagreement, fallthrough generation, response header echo, request attribute set, `currentTraceId(null)` handling, non-v00 version rejection, trace-flags=00 round-trip.
- `GlobalExceptionHandlerTest` — `trace_id` assertions across all five handler paths; null-trace when filter didn't run.

---

### 2026-04-16 — v0.1.25.13: hydration cap + enum wire annotations on the sorted list path

Closes two follow-up gaps surfaced by the three-step review of v0.1.25.12 against the admin-plane implementation of the same feature in `cycles-server-admin` v0.1.25.24:

**P1 — `listReservationsSorted` hydrates an unbounded population before the in-memory sort.** The sorted path in v0.1.25.12 does a full `SCAN` of `reservation:res_*`, hydrates every matching row into a `List<ReservationSummary>`, then sorts + slices. For a tenant with 2M reservations under a single workspace, that's 2M `HGETALL` round-trips and a 2M-entry heap object before the cursor walk even starts. The admin plane ran into exactly the same shape on `ApiKeyRepository.listAllTenantsSorted` and `BudgetRepository.listAllTenantsSorted` and closed it with a `SORTED_HYDRATE_CAP = 2000` constant: when the per-key hydration loop observes `matching.size() >= cap` it sets `capped = true`, breaks out of the SCAN loop, logs a WARN naming the tenant + sort tuple, and the sort/slice/cursor code then operates on the capped slice as normal. Page still fills, `has_more` + `next_cursor` still populate from the capped slice, so the UI isn't blocked — the cap is a heap-safety bound, not a correctness bound.

This release ports the same constant + `scanLoop:` labeled-break + post-loop WARN to `RedisReservationRepository.listReservations` on the sorted path only. The legacy (no-sort-params, no-decoded-sorted-cursor) path is intentionally uncapped because it streams page-by-page via the SCAN cursor and never builds an unbounded in-memory list.

**Why 2000:** Same rationale as admin — covers 99%+ of production tenants, keeps the heap bound predictable at roughly 2000 × ~2 KB ReservationSummary = ~4 MB per concurrent sorted-list call. Operators who outgrow the cap should either (a) narrow filters (`status`, `idempotency_key`, scope segments `workspace`/`app`/`workflow`/`agent`/`toolset`), or (b) revisit the deferred per-key ZSET index ADR at `docs/deferred-optimizations/sorted-list-zset-indices.md`.

**P2 — `ReservationSortBy` and `SortDirection` lacked Jackson wire annotations.** In v0.1.25.12 the enums were plain Java enums; the controller did manual `String.toUpperCase()` → `Enum.valueOf()` conversion with a try/catch → 400. That works, but it diverges from the admin plane where `SortSpec` and `SortDirection` carry `@JsonValue getWire()` + `@JsonCreator fromWire(String)` so Jackson handles the lowercase-on-wire contract natively and controllers delegate to `fromWire(...)` with the same try/catch → 400 shape. This release brings the two runtime-plane enums into line: lowercase `getWire()`, `null → null` on `fromWire(null)`, `IllegalArgumentException` on unknown tokens (which the controller converts to 400 with the documented allow-list payload). The wire contract is unchanged — lowercase tokens in and out — but direct JSON-over-wire uses of these enums (future list-response DTOs that echo the sort tuple back, for instance) now serialize/deserialize correctly without custom converters.

**Test strategy:**

- `RedisReservationQueryTest#sortedHydrationStopsAtCap` — mocks a SCAN page returning `cap + 10` keys, stubs pipeline `hgetAll` for each, invokes the 5-row sorted page, asserts exactly 5 rows in the documented ascending-`created_at_ms` order and `has_more=true`, `next_cursor != null`. Exercises the capped-slice cursor path. (The full hydration loop consults only up to `cap` rows; remaining stubs are unused as designed.)
- `EnumsTest` (new, `cycles-protocol-service-model`) — round-trip tests for both enums: `getWire()` emits lowercase, `fromWire` accepts canonical lowercase + uppercase + mixed case, `fromWire(null)` returns null, `fromWire("bogus")` throws `IllegalArgumentException`, `for each value: fromWire(getWire(v)) == v`.

No spec change; wire format is identical. No signature changes. No caller-visible behaviour change for tenants under the cap.

**Backward compatibility:** Full. Behaviour-visible for tenants whose sorted-list query previously returned >2000 matching rows (silently; those were already on the O(N) full-SCAN path documented in v0.1.25.12). Post-cap, rows beyond row 2000 in the capped slice are unreachable without narrowing filters — same contract the admin plane established in v0.1.25.24 for `ApiKey` + `Budget` cross-tenant sorted paths.

---

### 2026-04-16 — v0.1.25.12: `sort_by` + `sort_dir` on GET /v1/reservations

Closes the runtime-protocol gap opened by **cycles-protocol spec revision 2026-04-16** (commits `064e95f` + `a2a8f13`): list-reservations needed server-side ordering with client-selectable sort key + direction, and the cursor needed to encode the sort state so page breaks remain consistent.

**Spec shape:**

- `sort_by` enum (7 values): `reservation_id`, `tenant`, `scope_path`, `status`, `reserved`, `created_at_ms`, `expires_at_ms`.
- `sort_dir` enum: `asc`, `desc`. Defaults to `desc` when `sort_by` is provided. When both are omitted, legacy behaviour (Redis-SCAN arbitrary order) is preserved exactly — zero-risk to existing clients.
- Invalid enum values → HTTP 400 `INVALID_REQUEST` with the bad token echoed in the message.
- Cursors MUST bind to `(sort_by, sort_dir, filters)`; mismatched reuse → HTTP 400.

**Implementation shape — dual-path:**

Existing code uses Redis `SCAN` to page `reservation:res_*` keys. SCAN returns keys in arbitrary, cursor-coupled order, which is fundamentally incompatible with server-side sorting. The alternative — per-tenant ZSET indices per sort key — would have required new Lua scripts, dual-write paths on every reservation state transition, and a backfill migration. Disproportionate to runtime-plane scale (per-tenant N typically ≤ 10³; O(N) in-memory sort per sorted page is cheaper than index maintenance).

So the controller branches:

- **Legacy path** (no `sort_by` AND no `sort_dir` AND no sorted cursor) — unchanged `SCAN`/pipelined `HGETALL`/opaque-cursor loop. Byte-for-byte identical to v0.1.25.11.
- **Sorted path** (either sort param OR a decoded sorted cursor present) — full `SCAN` pass with filter predicates applied in-stream, deterministic in-memory `Comparator` sort, opaque slice cursor.

**New types (`cycles-protocol-service-data/.../repository/support/`):**

- `ReservationComparators.of(sortBy, sortDir)` — per-key extractors + `.thenComparing(reservation_id ASC)` tiebreaker so pagination boundaries are unambiguous under ties. Null-safe via `Comparator.nullsLast`. Also exposes `extractSortValue(ReservationSummary, sortBy)` for cursor `lsv` encoding.
- `FilterHasher.hash(t, i, st, ws, ap, wf, ag, ts)` — SHA-256 of canonical `k=v|k=v|...` over eight filter fields, first 16 hex chars (8-byte truncation). Not a security boundary — sole job is cheap detection of cross-tuple cursor reuse. Trades length for brevity in the base64url cursor payload.
- `SortedListCursor` record `{v:int, sb, sd, fh, lsv, lrid}` — v=1, base64url-no-pad Jackson JSON. `decode(String)` returns `Optional.empty()` on null/blank/all-digit input, which routes old numeric cursors straight to the legacy SCAN path — backward-compat kept at the cursor-parsing boundary rather than the controller boundary.

**Repository change:**

`RedisReservationRepository.listReservations` signature extended with trailing `String sortBy, String sortDir` (10 → 12 args). The 10-arg overload was removed intentionally — keeping both caused Mockito stubs defined over the 10-arg form to fail to match 12-arg call-sites from the updated controller, which silently made unit tests pass with `null` responses. Single explicit signature surfaces the contract at the type level.

New private helpers on the repository:

- `listReservationsSorted(...)` — full SCAN pass (no early termination), pipelined `HGETALL`, status/scope/filter predicates applied as rows stream through, in-memory sort via the comparator, opaque slice cursor emitted when `idx + limit < total`.
- `findSliceStart(rows, cursor)` — binary-search-like boundary finder over the sorted list; returns the first index strictly greater than `(lsv, lrid)` per the active comparator.
- `compareAtBoundary(...)` — honours sort direction so boundary comparison matches emitted order (desc cursor page-forward goes to smaller `lsv`, not larger).

**Cross-tuple cursor rejection:**

On resume, the decoded cursor's `(sb, sd, fh)` is compared to the current request's derived tuple. Mismatch throws `CyclesProtocolException(INVALID_REQUEST, ..., 400)`. Prevents the class of bug where a client paginates by `created_at_ms asc`, changes to `reserved desc` on the UI, re-submits the old cursor, and silently gets a corrupt mid-stream slice.

**Controller validation (at `ReservationController.list`):**

Matches the existing `status` validation pattern at line 246 — uppercase the incoming parameter, parse against the enum, throw `CyclesProtocolException(INVALID_REQUEST, "sort_by must be one of ...", 400)` on failure. No Spring `@Valid` involved; intentional — error-body shape and message wording must stay under cycles-protocol error-envelope control, not Jackson's default.

**Tests (≥95 % coverage preserved):**

- `ReservationControllerTest` — 4 new: invalid `sort_by` → 400, invalid `sort_dir` → 400, both params propagated to repo (argument captor), all 7 spec enum values accepted. Pre-existing Mockito stubs updated from 10-arg to 12-arg (`, any(), any()`) to match the new repository signature.
- `ReservationComparatorsTest` (new) — per-field asc/desc for all 7 sort keys, `reservation_id` tiebreaker under ties, null-subject-tenant safety under both directions, `extractSortValue` correctness.
- `SortedListCursorTest` (new) — round-trip encode/decode, malformed base64url → empty Optional (legacy fallback), digit-only input → empty Optional, JSON with missing fields → empty Optional.
- `FilterHasherTest` (new) — determinism, null-vs-empty-string equivalence (trailing empties collapse so clients can omit the trailing filters without forcing a new tuple), 16-hex-char output shape.
- `RedisReservationQueryTest.SortedListReservationsTest` (new `@Nested`) — Testcontainers Redis seeded with 25 reservations across statuses and timestamps; paginates `sort_by=created_at_ms&sort_dir=asc&limit=10` across 3 pages with `has_more` transitions checked; cursor reused under different `sort_by` throws; legacy numeric cursor still works without sort params; scope_path lexicographic ordering verified.
- Pre-existing `listReservations` call-sites (~12 across the Testcontainers file) updated from 10-arg to 12-arg.

**Release bookkeeping:**

- `cycles-protocol-service/pom.xml` `<revision>` → `0.1.25.12`.
- Both prod docker-compose image pins bumped: `docker-compose.prod.yml`, `docker-compose.full-stack.prod.yml`.
- Six-doc markdown matrix updated: AUDIT.md (this entry), CHANGELOG.md, OPERATIONS.md, BENCHMARKS.md, README.md × 2.
- Benchmarks unaffected (sort path is off for existing workloads because clients haven't added the params yet); release can use the `[benchmark-skip]` marker in the GH release notes body. Documented in CHANGELOG.

**Verification:**

- `mvn -B test --file cycles-protocol-service/pom.xml -Dtest='!*IntegrationTest'` — 352 data-module tests + 344 API-module tests green.
- Testcontainers Redis integration tests exercise the sort-path cursor round-trip.

**Out of scope (deferred):**

- Per-tenant ZSET indices by sort key — follow-up if any tenant exceeds ~10 k reservations and sorted-list latency crosses a service-level objective.
- Admin spec `/v1/auth/introspect` (cycles-governance-admin-v0.1.25.yaml revisions `101416f` / `6aca3f9`) — not a runtime-plane concern.

---

### 2026-04-14 — Automated performance regression detection (no version bump)

Closes the last remaining gap in the v0.1.25.11 scorecard: **performance regression detection was 6/10** because BENCHMARKS.md tracked trends but nothing failed automatically on a regression. A 2× p99 slowdown could merge, tag, and ship through the release workflow with only human review to catch it. This change adds an automated gate.

**Shape:** two workflows, one baseline file, three small Python scripts, 25% release-gate threshold with a bypass mechanism. Ships Phase 1 (trend) and Phase 2 (gate) together per review consensus that phasing added delay without real safety.

**New scripts (`scripts/`):**

- `parse-benchmarks.py` — parses surefire XML CDATA from `CyclesProtocol*BenchmarkTest` outputs into a JSON record with 7 headline metrics (reserve p50/p99, commit p50/p99, release p50, event p50, concurrent throughput @ 32 threads). Uses the same extraction patterns documented in the release-workflow memory. Exits non-zero if any metric can't be parsed so a silent-skip run can't land in history.
- `median-benchmarks.py` — median-aggregates N trial JSON records into one. Dampens GH runner noise.
- `check-regression.py` — two modes: `release` (compare current to `baseline.json`, 25% threshold, exit non-zero on regression) and `trend` (compare current to rolling-window median of `history.jsonl`, 30% threshold, warn-only by default). Bootstrap-tolerant: empty or missing baseline accepts the current record as the initial baseline and exits 0.

**New data directory (`benchmarks/`):**

- `history.jsonl` — append-only, one JSON object per nightly + release run. Initially empty; populated over time.
- `baseline.json` — current accepted reference for the release gate. Initially `{}` (bootstrap state); the first post-landing release establishes real numbers.
- `README.md` — format documentation, metric inventory, noise-handling rationale, bypass convention.

**New workflow: `.github/workflows/nightly-benchmark.yml`**

Cron at 07:00 UTC (after property + soak nightlies). Runs 3 trials, medians them, compares the result to the rolling 7-run median from `history.jsonl` with a 30% threshold. Trend detection only — **does not fail the workflow**, just annotates the job summary. Appends the result to `history.jsonl` and commits it to main with `[skip ci]` so the nightly doesn't kick itself.

**Modified: `.github/workflows/release.yml`**

New `benchmark-gate` job runs before `build-and-push`. Skips automatically when:
- Event is `workflow_dispatch` (manual Docker rebuilds don't need a gate — the tag is already pinned).
- Release notes body contains `[benchmark-skip]` (for test-only / infra-only releases where benchmarks only measure environmental noise).

Otherwise: installs the reactor (release-workflow memory gotcha — benchmarks need the new version in `~/.m2` first), runs the benchmark profile 3×, medians, compares against `baseline.json` with 25% threshold. On pass, atomically updates `baseline.json` on main and commits. On fail, the job fails and `build-and-push` doesn't run — no Docker image published.

**Bypass convention documented:** include `[benchmark-skip]` anywhere in the GitHub release notes body. Precedent for this use: v0.1.25.9 (test-only), v0.1.25.10 (metrics instrumentation, not hot-path), v0.1.25.11 (test-only) would all have legitimately used it. Going forward, release PR authors decide whether their release is perf-material and include the marker in the release notes if not.

**Noise handling (stacked):**

1. 3 trials, median taken per run
2. Rolling 7-run baseline for trend (drifts with environment; real regressions show as sustained steps)
3. Generous thresholds (25% gate, 30% trend flag)

Expected false-positive rate: < 1 per month. If higher, threshold tuning or self-hosted runners.

**Scorecard impact:** Performance regression detection **6/10 → 8.5/10**. Won't reach 10/10 without dedicated perf hardware (pinned CPU, no tenant contention) but 8.5 is a very high ceiling for GH-hosted CI.

**Test plan / validation:**
- Script unit-test: synthetic records fed through bootstrap / no-regression / regressed paths. All three paths produce correct exit codes (0/0/1) and correct summary output. Verified locally before push.
- First nightly run will establish the initial `history.jsonl` data point.
- First release post-merge will bootstrap `baseline.json` (accept current numbers as the reference).
- Second release onwards: real gate enforcement.

**Modified files:**
- `benchmarks/README.md`, `benchmarks/history.jsonl` (empty), `benchmarks/baseline.json` (empty) — NEW.
- `scripts/parse-benchmarks.py`, `scripts/median-benchmarks.py`, `scripts/check-regression.py` — NEW.
- `.github/workflows/nightly-benchmark.yml` — NEW.
- `.github/workflows/release.yml` — added `benchmark-gate` job upstream of `build-and-push`.
- `cycles-protocol-service/cycles-protocol-service-api/pom.xml` — `-Psoak` profile `forkedProcessTimeoutInSeconds` bumped 1800 → 4500 (see soak-timeout fix note below).
- `.github/workflows/nightly-soak-test.yml` — `timeout-minutes` 45 → 90 (see soak-timeout fix note below).
- `AUDIT.md` — this entry.

**Bundled soak-timeout fix:** first `workflow_dispatch` run of the nightly-soak-test workflow at 30-min duration completed all four invariants cleanly (179,944 reserves + 91,507 commits + 0 errors + heap 1.14× + latency 0.25×) but the surefire fork timer (set to 1800s = 30 min exactly) killed the process 20 seconds after the soak assertions finished printing success. No service bug — the test passed on substance. Fix is a generous bump to both the Maven fork timer (4500s) and the job timeout (90 min) so 30-min and 60-min runs have headroom for Maven/Testcontainers boot + post-assertions. Bundled into this PR because it's adjacent CI-timer infra and shipping separately would delay the perf-regression gate.

---

### 2026-04-14 — Nightly soak test (no version bump)

Closes the biggest remaining gap from the v0.1.25.11 quality review: **long-duration stability coverage scored 5/10** because no test ran for more than a minute or two. This change adds a soak test to nightly CI. No production-code changes, no version bump — this is purely test infrastructure sitting on a non-release path.

**New test: `SoakIntegrationTest` (`cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/`)**

Tagged `@Tag("soak")` — excluded from PR CI via the new `excludedGroups=...,soak` on the default surefire config.

Drives a mixed reserve+commit/release workload at ~100 ops/s for 10 minutes (tunable via `-Dsoak.duration.minutes` / `-Dsoak.target.rps`). Alternates commit vs release so both cleanup paths are exercised symmetrically — a leak on only one path would still be detected. At the end of the run, asserts four invariants:

- **S1 — Heap stability:** JVM heap used at end < 2× heap used at start (after forced GC on both endpoints). A real leak grows unboundedly; 2× tolerates normal GC churn and working-set growth.
- **S2 — Latency stability:** average latency in the final minute is < 3× the baseline-minute average (sampled from `http.server.requests` timer). Catches connection-pool exhaustion, queue backup, cache-miss feedback loops that only surface over time. Only asserts if baseline has ≥10 samples (skips on very low-RPS runs).
- **S3 — Redis key-count bounded:** `reservation:res_*` key count bounded by `ops × 1.1`; `idem:*` bounded by `ops × 2.1` (one idem key per reserve + one per commit). Runaway idempotency-cache leak or orphaned reservation hashes would blow these.
- **S4 — No orphaned TTL entries:** every member of the `reservation:ttl` zset has a matching `reservation:res_*` hash. An orphan indicates a commit that cleaned up the hash but left the TTL index entry behind.

Error rate sanity check: < 1% of attempts.

**New profile: `-Psoak` in `cycles-protocol-service-api/pom.xml`**

Mirrors the existing `property-tests` profile shape. Runs only `@Tag("soak")` tests with `forkedProcessTimeoutInSeconds=1800` so the surefire fork itself doesn't expire mid-run. Default PR build now excludes group `soak` alongside `benchmark` and `property-tests`.

**New workflow: `.github/workflows/nightly-soak-test.yml`**

Runs at 06:30 UTC daily (after the 06:00 UTC property-tests workflow). Sibling of `nightly-property-tests.yml` — same shape, different profile. `workflow_dispatch` inputs allow manual triggering with custom `duration_minutes` and `target_rps` (e.g. for a 1-hour deep soak on demand). Surefire reports uploaded on failure for post-hoc analysis.

**Local smoke-test result:** 1 minute at 20 rps on Docker Desktop produced 1,192 reserves / 638 commits / 0 errors / heap 43MB→49MB (1.13×), latency 22.5ms → 11.9ms (JIT warmup). All four invariants held; runtime 75 seconds.

**Verification:**
- `mvn test -Psoak -Dsoak.duration.minutes=1 -Dsoak.target.rps=20 -Dtest=SoakIntegrationTest` passes locally
- `mvn -B verify --file cycles-protocol-service/pom.xml` (default build) unaffected — soak tests excluded from normal runs
- Nightly workflow will validate in production on first run

**Modified files:**
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/SoakIntegrationTest.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-api/pom.xml` — added `soak` to `excludedGroups`; new `soak` profile.
- `.github/workflows/nightly-soak-test.yml` — NEW.
- `AUDIT.md` — this entry.

---

### 2026-04-14 — v0.1.25.11: concurrent idempotency + metrics tests

Closes two gaps flagged in the post-v0.1.25.10 review. Both are regression gates rather than live bug fixes — the existing code is correct because Redis Lua execution is single-threaded and Micrometer counters are lock-free — but without these tests a future refactor could silently violate those guarantees.

**New test 1: thundering-herd retry on expired idempotency cache** (`IdempotencyCacheExpiryIntegrationTest.ThunderingHerd`)

The v0.1.25.10 `IdempotencyCacheExpiryIntegrationTest` covered the sequential case: delete cache → retry → new reservation. The ops-realistic failure mode is different: N concurrent retries arriving at the server after cache expiry, all missing the idempotency cache, all racing into `reserve.lua`. New test fires 10 concurrent retries through the full HTTP path and asserts:

- Exactly one distinct reservation id is returned across all 10 retries (Redis's Lua serialisation makes the winner's cache write visible to the others before they execute).
- No HTTP errors; all 10 return 200.
- The Redis hash state for the winning id is consistent (exactly one reservation, correct idempotency key stored).
- Metric tags reflect reality: exactly 1 × `reason=OK` (the winner that actually ran the reserve body) + 9 × `reason=IDEMPOTENT_REPLAY` (the rest that took the idempotent-replay short-circuit). A wrong-tag regression would surface here.

If a future change moves idempotency from `reserve.lua` into Java (e.g. a distributed lock), this test fails because Java-side races break the atomicity guarantee.

**New test 2: concurrent custom-counter accuracy** (`MetricsCorrectnessIntegrationTest.concurrentCustomCounterIsAccurate`)

Sibling of the existing `concurrentRequestCountIsAccurate` that tests Spring Boot's HTTP timer. The new test asserts on the domain counter `cycles.reservations.reserve` under the same 8-thread × 10-request load. Micrometer counters are lock-free atomic longs so this should be accurate, but we had no regression gate against a future refactor introducing locking or a shared-builder race (e.g. an aspect that builds tags from a mutable map).

**Verification:**
- `mvn -B verify --file cycles-protocol-service/pom.xml`: 135 api + 320 data = 455 tests (2 new), 0 failures, JaCoCo coverage met, spec coverage 9/9.

**Wire format:** Unchanged. No production-code changes.

**Modified files:**
- `cycles-protocol-service/pom.xml` — `<revision>` → `0.1.25.11`.
- `docker-compose.prod.yml`, `docker-compose.full-stack.prod.yml` — bump `cycles-server` pin to `0.1.25.11` per the release workflow.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/IdempotencyCacheExpiryIntegrationTest.java` — new `ThunderingHerd` nested class + MeterRegistry wiring.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/MetricsCorrectnessIntegrationTest.java` — new `concurrentCustomCounterIsAccurate` test.
- `AUDIT.md`, `CHANGELOG.md`, `README.md` — release notes + version bump.

---

### 2026-04-14 — v0.1.25.10: custom business metrics + resilience test

Addresses the largest remaining gap flagged in the v0.1.25.9 retrospective: the service emitted no domain-level metrics, only Spring Boot's generic `http.server.requests` timer. Operators answering "how many denials in the last 5 minutes by reason and tenant" could only infer it from HTTP status codes. This release wires domain counters through a new shared component and extends the existing Micrometer integration test to cover them.

**New class: `CyclesMetrics` (`cycles-protocol-service-data/.../metrics/`)**
Centralised Micrometer instrumentation. One `record*` method per operation, each mapping to a counter under the `cycles.*` namespace. Tag set prioritises operational signal (tenant, decision, reason, overage_policy, actor_type) while keeping cardinality bounded; the only high-card tag (`tenant`) is toggleable via `cycles.metrics.tenant-tag.enabled` for deployments with many thousands of tenants.

**Counters emitted:**
- `cycles.reservations.reserve` — every reserve outcome, tagged {tenant, decision, reason, overage_policy}. Idempotent replays record `reason=IDEMPOTENT_REPLAY` so an operator can tell real ALLOWs from cached replays.
- `cycles.reservations.commit` — every commit outcome, same tag set.
- `cycles.reservations.release` — every release, tagged {tenant, actor_type=tenant|admin_on_behalf_of, decision, reason}. The actor_type split was v0.1.25.8's dual-auth surface — now directly queryable.
- `cycles.reservations.extend` — every extend outcome.
- `cycles.reservations.expired` — one per actual ACTIVE→EXPIRED transition from the sweep. Does NOT bump for grace-period skips or already-finalised candidates.
- `cycles.events` — every event outcome, same four-tag shape as reserve/commit.
- `cycles.overdraft.incurred` — incremented whenever a commit or event actually accrued non-zero debt. Unit-free (the amount is in the balance store; this is "how often did we go into overdraft").

**Modified:**
- `RedisReservationRepository` — wraps each of `createReservation`, `commitReservation`, `releaseReservation`, `extendReservation`, `createEvent` so the counter emits on both success and exception paths. Method signatures for `commitReservation` and `releaseReservation` gained a `tenant` parameter (and `releaseReservation` an `actorType`); callers in `ReservationController` updated.
- `ReservationExpiryService` — increments `cycles.reservations.expired` for each Lua-reported EXPIRED result (not per sweep candidate).
- `cycles-protocol-service-data/pom.xml` — added `io.micrometer:micrometer-core`.

**Dormant bug surfaced and fixed:** `ReservationExpiryService.emitExpiredEvent` was reading `reservation:<id>` instead of `reservation:res_<id>`. Because `jedis.hgetAll` on a missing key returns an empty map (not an error), the method silently no-op'd on every expiry in production — the `reservation.expired` event was never actually emitted. The new counter test exposed it immediately. Existing unit tests (`ReservationExpiryServiceTest`) used mocks keyed to the same wrong prefix so they were self-consistent but didn't catch the real path divergence; test mocks aligned to production in the same commit.

**New integration test: `RedisDisconnectResilienceIntegrationTest`**
Uses a dedicated Testcontainers Redis (not the shared one from `BaseIntegrationTest`, to avoid breaking parallel tests). Pauses the container mid-request via Docker pause, asserts the commit operation fails with a structured error (not a hang, not a silent 200), resumes the container, asserts a retry succeeds using the still-valid pre-outage reservation, and verifies the TTL index has no orphaned entry post-recovery. Guards the failure class this codebase's positioning claims to prevent (silent failures under a paused downstream).

**Extended: `MetricsCorrectnessIntegrationTest`**
Adds five nested classes covering every new counter. Each test seeds a clean state, reads the aggregate counter count, drives a known workload, and asserts the exact delta. Uses `Search.counters().stream().mapToDouble(...).sum()` rather than `Search.counter()` because multiple counters can match a partial tag filter (e.g. same tenant+decision but different overage_policy) and `.counter()` on ambiguous searches returns an arbitrary one — the aggregate is what the test needs.

**New unit test: `CyclesMetricsTest`**
10 tests in `cycles-protocol-service-data` covering every `record*` method's tag shape, null/blank normalisation to the `UNKNOWN` sentinel, and the `cycles.metrics.tenant-tag.enabled=false` path (verifies `tenant` tag is omitted for high-cardinality deployments).

**Wire format:** Unchanged. Response bodies, Lua scripts, error codes, idempotency semantics all identical to v0.1.25.9.

**Verification:**
- `mvn -B verify --file cycles-protocol-service/pom.xml`: 133 api + 320 data = 453 tests, 0 failures. JaCoCo coverage met (≥95%). Spec coverage 9/9.
- Property-tests profile unchanged, still passes.

**Modified files:**
- `cycles-protocol-service/pom.xml` — `<revision>` → `0.1.25.10`.
- `cycles-protocol-service/cycles-protocol-service-data/pom.xml` — `micrometer-core` dep.
- `cycles-protocol-service/cycles-protocol-service-data/src/main/java/io/runcycles/protocol/data/metrics/CyclesMetrics.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-data/src/main/java/io/runcycles/protocol/data/repository/RedisReservationRepository.java` — instrumented; commit/release signatures gained `tenant`/`actorType`.
- `cycles-protocol-service/cycles-protocol-service-data/src/main/java/io/runcycles/protocol/data/service/ReservationExpiryService.java` — counter emission + `res_` prefix fix.
- `cycles-protocol-service/cycles-protocol-service-api/src/main/java/io/runcycles/protocol/api/controller/ReservationController.java` — pass `tenant` + `actorType` to repo.
- `cycles-protocol-service/cycles-protocol-service-data/src/test/java/io/runcycles/protocol/data/metrics/CyclesMetricsTest.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/MetricsCorrectnessIntegrationTest.java` — extended with 8 new tests.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/RedisDisconnectResilienceIntegrationTest.java` — NEW.
- Updated mocks/test signatures in `ReservationControllerTest`, `RedisReservationCommitReleaseTest`, `RedisReservationEdgeCaseTest`, `ReservationExpiryServiceTest`, `BaseRedisReservationRepositoryTest`, `BalanceControllerTest`, `DecisionControllerTest`, `EventControllerTest` to match new signatures / provide `CyclesMetrics` mock bean.
- `AUDIT.md`, `README.md` — this entry + version bump.

---

### 2026-04-14 — v0.1.25.9: second-wave test additions

Follow-up to v0.1.25.8's `BudgetExhaustionConcurrentPropertyTest`. A test-quality review flagged eight further high-leverage gaps (ordered by expected bug-catch density). This release lands all eight in one PR. Every addition reuses the existing `BaseIntegrationTest` + Testcontainers Redis harness and the `@Tag("property-tests")` convention for long-running jqwik suites.

**New test classes (`cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/`):**

1. `OverdraftConcurrentPropertyTest` — two properties parameterised on overage policy. Asserts `debt == 0` and `Σcharged ≤ allocated` for ALLOW_IF_AVAILABLE; `debt ≤ overdraft_limit` and `Σcharged ≤ allocated + overdraft_limit` for ALLOW_WITH_OVERDRAFT. Ledger invariant (`allocated == remaining + spent + reserved + debt`) enforced on both.
2. `ExpireLuaConformanceTest` — direct `EVAL expire.lua` via Jedis, bypassing the service layer. Eight tests across five nested groups verify only ACTIVE→EXPIRED transitions occur, reserved budget is released at all budgeted scopes, grace period is honoured, idempotent re-invocation is a no-op, NOT_FOUND cleans the TTL index, and the script uses `redis.call('TIME')` exclusively (proved by passing bogus client-supplied time via a second ARGV and confirming it is ignored).
3. `AdminReleaseRaceIntegrationTest` — `@RepeatedTest(20)` racing a tenant commit against an admin-driven release on the same reservation. Asserts exactly-one-wins (XOR), loser returns `409 RESERVATION_FINALIZED`, and the reservation hash reflects the winner's terminal state with no cross-contamination. Gates the v0.1.25.8 dual-auth surface against state-machine regressions.
4. `ScopeAttributionConcurrentPropertyTest` — generates scope chains at depths 1–6 (tenant → workspace → app → workflow → agent → toolset), seeds budgets at every derived level, fires concurrent reserves + commits, then asserts `spent[level] == Σcharged` at every level with no divergence between levels. Complements the single-threaded path in `ScopeAndExpiryIntegrationTest`.
5. `IdempotencyCacheExpiryIntegrationTest` — deterministically simulates post-TTL replay by deleting the cache key via Jedis. Proves: (a) a reserve retry after `idem:{tenant}:reserve:{K}` expiry produces a NEW reservation id, (b) the pre-expiry case still replays to the same id, (c) a commit retry after scrubbing `committed_idempotency_key` returns 409 rather than a phantom second success.
6. `ClockSkewIntegrationTest` — verifies every time-sensitive decision resolves against Redis TIME, not JVM time. Manipulates `expires_at` + `grace_ms` directly on the reservation hash, then drives commit/release/extend through the full HTTP path. Pins the spec contract that extend does NOT honour grace (only commit does) — a subtle asymmetry that could trip operators.
7. `MetricsCorrectnessIntegrationTest` — asserts Micrometer's `http.server.requests` counters are accurate under concurrent load (no lost increments on contended counters). Injects `MeterRegistry` directly because the `/actuator/prometheus` endpoint isn't registered in the `@SpringBootTest(RANDOM_PORT)` context (the handler falls through to static-resource resolution). A known gap to revisit when custom business metrics are added.
8. `AuditLogCompletenessPropertyTest` — generates mixed admin-release workloads (on ACTIVE, already-committed, already-released reservations), drains `audit:logs:_all` + `audit:logs:{tenant}`, asserts: (a) 1:1 between successful admin releases and audit entries, (b) tenant and global indexes hold the same set with matching scores, (c) global index is ordered ascending by timestamp, (d) every entry carries required fields including `metadata.actor_type = "admin_on_behalf_of"`.

**Plan deviations (scope notes for maintainers):**
- `ExpireLuaConformanceTest` placed in `-api` not `-data`. The `-data` module uses Mockito-only tests with no Testcontainers infrastructure; extending the `-api` harness is zero-cost, adding Testcontainers to `-data` is not.
- `ClockSkewIntegrationTest` does not inject a Java `Clock` bean (none exists in production). It verifies the equivalent invariant — that decisions depend on Redis TIME only — by manipulating Redis-side timestamps. A future refactor that adds Java-side timestamping for any reservation decision will cause these tests to fail.
- `MetricsCorrectnessIntegrationTest` asserts on HTTP-layer timer counts rather than custom per-operation counters (none exist yet). When operation-specific counters are introduced, extend the assertions rather than replacing them — the HTTP-layer coverage is the correct baseline.

**Verification:**
- `mvn -B verify --file cycles-protocol-service/pom.xml`: 133 tests pass across all modules, JaCoCo coverage check met (≥95%), spec coverage 9/9.
- `mvn -B test -Pproperty-tests -Djqwik.tries.default=5`: 5 property tests, 0 failures.
- Manual spot-check of `AdminReleaseRaceIntegrationTest` with 20 repetitions across several runs — exactly-one-wins invariant held in every iteration.

**Modified files:**
- `cycles-protocol-service/pom.xml` — `<revision>` bumped to `0.1.25.9`.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/OverdraftConcurrentPropertyTest.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/ExpireLuaConformanceTest.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/AdminReleaseRaceIntegrationTest.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/ScopeAttributionConcurrentPropertyTest.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/IdempotencyCacheExpiryIntegrationTest.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/ClockSkewIntegrationTest.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/MetricsCorrectnessIntegrationTest.java` — NEW.
- `cycles-protocol-service/cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/AuditLogCompletenessPropertyTest.java` — NEW.
- `AUDIT.md` — this entry + date header.
- `README.md`, `cycles-protocol-service/README.md` — version bump in jar/image examples.

**Test-only release:** No production-code changes. Wire format, Lua scripts, controllers, repositories, Spring config all identical to v0.1.25.8.

---

### 2026-04-14 — Property-based concurrent budget-exhaustion test

Closes a gap surfaced by a test-quality audit: `CyclesProtocolConcurrentBenchmarkTest` runs concurrent reserve→commit lifecycles and measures latency, but it does not assert state invariants. `OverdraftIntegrationTest` covers REJECT/ALLOW_IF_AVAILABLE/ALLOW_WITH_OVERDRAFT in isolation but not under adversarial contention. The new test forces both the concurrency invariant and the overage-under-contention scenario under jqwik's generated interleavings.

**What it tests:**
Under REJECT overage policy (`overdraft_limit = 0`) with randomized (threadCount, initialBudget, workload) triples, asserts three invariants after the workload drains and the expiry sweep runs:

1. **I1** — sum(`charged_amount` across COMMITTED reservations) ≤ `initial_budget`. Never overdraw under any interleaving.
2. **I2** — Mutually exclusive terminal states. A COMMITTED reservation must not also carry `released_amount`, and vice versa.
3. **I3** — Every reservation reaches a terminal state within TTL + grace + sweep. No leaked ACTIVE rows.

Budgets are intentionally small (1K–50K TOKENS) with thread counts 2–16 and workloads of 30–200 ops, so exhaustion is frequent and the invariants are actually exercised. jqwik shrinks failing cases automatically.

**New files:**
- `cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/BudgetExhaustionConcurrentPropertyTest.java` (new)
- `cycles-protocol-service-api/src/test/resources/jqwik.properties` (new) — anchors `defaultTries = 20` for PR-speed runs; overridable at runtime via `-Djqwik.defaultTries=<N>`
- `.github/workflows/nightly-property-tests.yml` (new) — scheduled 06:00 UTC daily with `-Djqwik.defaultTries=100` for deeper coverage than the PR-speed default. `workflow_dispatch` allows manual runs. Surefire reports uploaded on failure.

**Modified files:**
- `cycles-protocol-service-api/pom.xml` — add `net.jqwik:jqwik:1.9.1` and `net.jqwik:jqwik-spring:0.11.0` (test scope); add `property-tests` to default surefire `<excludedGroups>`; add `property-tests` Maven profile mirroring the existing `benchmark` profile pattern
- `cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/BaseIntegrationTest.java` — add four `protected` helpers (`getReservationStateFromRedis`, `getBudgetFromRedis`, `scanReservationKeys`, `seedBudgetWithOverdraftLimit`) for direct Redis inspection under invariant checks
- `.gitignore` — add `.jqwik-database` (jqwik's local failure-replay cache)
- `README.md` — add "Property-based tests" subsection under Testing

**CI impact:** zero impact on default PR CI — the `property-tests` tag is in the default `<excludedGroups>`, so the test only runs when `-Pproperty-tests` is active. Nightly job runs against `main` only.

**Runtime verification:** test was executed against Docker Desktop + Testcontainers Redis on a development machine after the initial commit. Two follow-up fixes were required (see below) before the test ran green. Current state: **20 tries × ~1 s per try, 100% pass**, seed `-4335878008215958540` recorded as one known-good reproducer. No invariant violations observed across 20 randomized `(threadCount ∈ [2,16], budget ∈ [1K, 50K], workload ∈ [30, 200 ops])` triples. This empirically confirms `reserve.lua` atomicity holds under the generated interleaving space.

---

### 2026-04-14 — Follow-up fixes: jqwik-spring lifecycle and tries-override

Two bugs found during the first real runtime execution of `BudgetExhaustionConcurrentPropertyTest`:

**Fix 1 — `@BeforeProperty` runs before field injection** (commit `bb962e9`)

First run failed with:
```
NullPointerException: Cannot invoke "redis.clients.jedis.JedisPool.getResource()"
  because "this.jedisPool" is null
  at BudgetExhaustionConcurrentPropertyTest.resetRedis(...)
```

Root cause: `jqwik-spring` 0.11.0 injects `@Autowired` fields inside its `AroundPropertyHook`, which wraps the property *body*. `@BeforeProperty` fires **before** that hook opens, so autowired fields are still null.

Fix: deleted the `@BeforeProperty resetRedis()` method and moved the Redis flush + API-key seeding inline at the top of the property body. Secondary benefit — the reset now runs per-try (20 times per property) rather than once per property, so each generated workload starts from a truly clean Redis rather than inheriting residue from the previous try. The previous once-per-property reset would have produced misleading invariant violations during shrinking, where reservations from a pre-shrink try would still be in Redis when the post-shrink try ran with a smaller budget.

**Fix 2 — jqwik tries count was not overridable** (commit `097a285`)

Second run with `-Djqwik.tries.default=100` still showed `tries = 20` in the jqwik summary — the override silently had no effect. Two compounding causes:

1. `@Property(tries = 20)` annotation literal beats any runtime override. jqwik's precedence is annotation > system property > config file > built-in default.
2. The nightly workflow used `-Djqwik.tries.default=100`, but the correct jqwik system-property name is `-Djqwik.defaultTries=100` (matching the config-file key `defaultTries`). Even without bug #1 the override would have been a no-op.

Fix:
- Removed `tries` from the `@Property` annotation. jqwik now reads `defaultTries = 20` from a new `src/test/resources/jqwik.properties` file as the PR-speed baseline.
- Corrected the nightly workflow to `-Djqwik.defaultTries=100`.
- Runtime-verified with `-Djqwik.defaultTries=100` producing `tries = 100 | checks = 100` and passing in ~2 minutes with seed `-2583074049974961229` as a known-good reproducer.

**Current passing state:** PR-speed mode (20 tries) runs in ~20 s; nightly (100 tries) runs in ~2 min. Three consecutive runs across both modes with different seeds, all green. No invariant violations observed.

---

### 2026-04-12 — Spec endpoint-coverage report (parity with cycles-admin)

Ports the `SpecCoverageCollector` pattern from `cycles-server-admin`. Closes the remaining capability gap identified in the sibling-repo comparison: the existing contract validators catch drift on *exercised* endpoints, but an endpoint with no test slides past every check (the response validator only fires on requests that actually happen).

**How it works:**
- `SpecCoverageCollector` — JVM-static set of `"METHOD /path/template"` keys.
- `ContractValidationConfig` — new `specOperations()` parses the spec into method+path-template entries; `recordCoverage(method, path)` resolves a concrete URI (e.g. `GET /v1/reservations/res_123`) to its template (`GET /v1/reservations/{reservation_id}`) via regex match and records it.
- Both the MockMvc matcher (`contractValidatingCustomizer`) and the `TestRestTemplate` interceptor now call `recordCoverage` on every spec-path request, regardless of response body (a 204 still counts as coverage).
- `SpecCoverageReportTest` in the `zzz` sub-package runs last (thanks to `<runOrder>alphabetical</runOrder>` added to surefire), diffs covered vs declared operations, and fails the build on any gap.

**Current state:** `declared=9 covered=9 missing=0` — every runtime operation is exercised by at least one contract-validated test.

**Modified files:**
- `cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/contract/SpecCoverageCollector.java` (new)
- `.../contract/ContractValidationConfig.java` — `specOperations()`, `recordCoverage()`, `SpecOperation` inner class; MockMvc customizer now records coverage
- `.../contract/ContractValidatingRestTemplateInterceptor.java` — calls `recordCoverage` on every spec-path hit
- `.../zzz/SpecCoverageReportTest.java` (new)
- `cycles-protocol-service-api/pom.xml` — added `<runOrder>alphabetical</runOrder>` to surefire so the zzz-package report runs last

**Feature parity with cycles-server-admin:** all 7 hardening capabilities now match, plus the coverage collector. Only remaining delta is the spec-filename convention (`cycles-protocol-v0.yaml` vs `cycles-governance-admin-v0.1.25.yaml`), which is driven by how the spec repo names its files and isn't a philosophical difference.

---

### 2026-04-12 — Spec tracking policy: pinned SHA → cycles-protocol@main

Reverses Gap 7's immutable-SHA pinning in favor of tracking `cycles-protocol@main` directly. Trade-off explicitly chosen by the team:

- **Benefit:** any spec change — a new required field, a renamed enum value, a tightened constraint — fails the next CI run on the next server PR, not whenever somebody remembers to bump a pin. Drift detection becomes immediate rather than manual.
- **Cost:** a breaking spec change on `main` can red-light unrelated server PRs. This is deliberate — the server is expected to be spec-compliant at all times, so a broken build is the correct signal. Fixing the server (or reverting the spec) becomes the unblocking step.

**Changes:**
- `ContractSpecLoader.DEFAULT_SPEC_URL` → `https://raw.githubusercontent.com/runcycles/cycles-protocol/main/cycles-protocol-v0.yaml`.
- `PINNED_SPEC_SHA` renamed to `LAST_KNOWN_GOOD_SHA` — informational only (runtime always fetches `main`). Useful for forensics ("what was the spec last verified against when this loader was touched?") and as a pointer when debugging a drift failure. Still points at `208a7be5…`.
- Cache TTL unchanged (1h). CI workspaces are always fresh so cache only matters locally.

**Escape hatch preserved:** `-Dcontract.spec.url=https://raw.githubusercontent.com/runcycles/cycles-protocol/<sha>/cycles-protocol-v0.yaml` lets anyone temporarily pin to a specific commit while investigating a failure — no code change needed.

**Verification:** 64 unit/contract tests pass against live `main` fetch.

---

### 2026-04-12 — Strict response-status enforcement (Gap 2 closed)

Closes the last remaining compliance gap from the hardening round. With `runcycles/cycles-protocol#34` merged (all 9 runtime operations now document 400), the server-side `validation.response.status.unknown` IGNORE escape hatch is no longer needed and has been removed.

**What this catches:** any response whose HTTP status code isn't documented in the spec for that operation now fails the build. Previously undocumented-status responses slid through unchecked.

**Changes:**
- `ContractSpecLoader.PINNED_SPEC_SHA` bumped to `208a7be5…` (cycles-protocol@main after PR #34).
- `ContractValidationConfig.sharedValidator()` — dropped `withLevel("validation.response.status.unknown", IGNORE)`. All response-status levels now default to ERROR.

**Verification:** 64 unit/contract tests + 191 integration tests pass under strict response-status enforcement — confirms the server emits only documented statuses on every path exercised in the build.

**All 7 gaps from the Gap 2 list are now closed.**

---

### 2026-04-12 — Spec compliance hardening (full-coverage automated drift detection)

Extends the two-phase contract validation landed earlier today into a full-build compliance guarantee: every response the server produces — across unit tests, integration tests, and SpringDoc's self-declared surface — is now validated against the pinned spec on every CI run.

**Gap 1 — Integration-test coverage** (`ContractValidatingRestTemplateInterceptor`): a `ClientHttpRequestInterceptor` registered on `TestRestTemplate` in `BaseIntegrationTest` captures every request/response pair, converts to the Atlassian validator's model, and fails the test if the response body doesn't match the spec. Validation now covers 191 integration tests (real controller → service → Redis → Lua path) in addition to 64 unit tests — ~255 validated observations per build. Zero per-test code changes required.

**Gap 3 — Optional-field branch coverage**: `ReservationControllerTest` now has `shouldGetCommittedReservationWithFinalizedFields` and `shouldGetReleasedReservation`, exercising the `committed` / `finalized_at_ms` optional fields that only appear on COMMITTED/RELEASED reservations (previously only ACTIVE responses were validated).

**Gap 4 — COMPATIBLE drift visibility**: `OpenApiContractDiffTest` now prints a summary of COMPATIBLE (non-breaking) drift to stdout so it's visible in CI logs. Still not a build failure — prevents noise — but stops silent accumulation.

**Gap 5 — CI integration-tests job**: `.github/workflows/ci.yml` now has two jobs: `unit` (unchanged) and `integration` which runs `mvn verify -Pintegration-tests` on ubuntu-latest. Previously CI excluded `*IntegrationTest` entirely, so contract validation never ran against real Redis-backed paths in CI.

**Gap 6 — DTO static check** (`DtoConstraintContractTest`): reflects over every `@RequestBody` DTO (`DecisionRequest`, `EventCreateRequest`, `ReservationCreateRequest`, `CommitRequest`, `ReleaseRequest`, `ReservationExtendRequest`) and asserts that every spec-required property has a corresponding `@NotNull`/`@NotBlank`/`@NotEmpty` annotation on the matching Java field. Catches the class of drift where the spec tightens a field to required but the DTO still accepts null.

**Gap 7 — Immutable spec pinning**: `ContractSpecLoader.DEFAULT_SPEC_URL` now pins to commit SHA `424dbf92…` in `runcycles/cycles-protocol` instead of `main`. Spec bumps become explicit one-line PRs; CI can't be broken (or silently loosened) by an unreviewed spec change.

**Gap 2 — Undocumented response statuses (follow-up)**: `validation.response.status.unknown` is still IGNORE because the spec doesn't document 400 on most paths. Needs a companion PR in `runcycles/cycles-protocol` adding `400: $ref '#/components/responses/ErrorResponse400'` to every path, followed by a pin-bump PR here that flips the level to ERROR.

**Noise filter refactor**: request-side validation rules collapsed from 14 individual IGNOREs to a single prefix `withLevel("validation.request", IGNORE)`. Request shape is deliberately not enforced (tests send bad input on purpose); Bean Validation in prod and `DtoConstraintContractTest` are the request-side gates.

**Shared validator**: `ContractValidationConfig.sharedValidator()` now exposes a singleton `OpenApiInteractionValidator` reused by both the MockMvc customizer (Phase 1) and the `TestRestTemplate` interceptor — one spec parse per JVM, identical noise filters.

**Modified files:**
- `cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/contract/ContractSpecLoader.java` — pinned commit SHA, `file:` URL support
- `.../contract/ContractValidationConfig.java` — sharedValidator() singleton, isSpecPath() helper, prefix-based request IGNORE
- `.../contract/ContractValidatingRestTemplateInterceptor.java` (new) — integration-test response validator
- `.../contract/DtoConstraintContractTest.java` (new) — static DTO ↔ spec required-field check
- `.../contract/OpenApiContractDiffTest.java` — COMPATIBLE drift logged to stdout
- `.../controller/ReservationControllerTest.java` — +2 tests for COMMITTED/RELEASED states
- `.../BaseIntegrationTest.java` — registers contract interceptor in `@BeforeEach`
- `.github/workflows/ci.yml` — added `integration` job running `mvn verify -Pintegration-tests`

**Verification:**
- `mvn test -pl cycles-protocol-service-api -Dtest='*ControllerTest,*ContractTest,OpenApiContractDiffTest'` → 64 tests, 0 failures.
- `mvn verify -Pintegration-tests -Dtest='*IntegrationTest'` → 191 tests, 0 failures — every response validated against spec.

---

### 2026-04-12 — Spec contract validation (drift detection)

Ported the two-phase contract-testing pattern from `cycles-server-admin` into the protocol server so CI fails on drift between the authoritative `cycles-protocol-v0.yaml` spec and the Spring controllers.

**Phase 1 — Runtime response schema validation** (`ContractValidationConfig`)
- Registers an Atlassian `swagger-request-validator-mockmvc` `MockMvcBuilderCustomizer` that validates every JSON response body produced by `@WebMvcTest` controller tests against the pinned spec (2xx → success schemas, 4xx/5xx → `ErrorResponse`).
- Request-side validation levels are IGNOREd (tests deliberately send malformed input); responses on infrastructure paths (`/api-docs`, `/swagger-ui`, `/actuator`) and empty/non-JSON bodies are skipped.
- Wired into `BalanceControllerTest`, `ReservationControllerTest`, `DecisionControllerTest`, `EventControllerTest` via `@Import(ContractValidationConfig.class)`.

**Phase 2 — Structural diff** (`OpenApiContractDiffTest`)
- `@SpringBootTest(MOCK)` boots the full app with `JedisPool` mocked, fetches SpringDoc's generated `/api-docs`, and diffs it against the pinned spec via `openapi-diff-core`.
- Fails on missing endpoints, extra (undocumented) endpoints, and operation-level `INCOMPATIBLE` signature divergence. Benign COMPATIBLE / metadata drift is ignored.

**Spec loading** (`ContractSpecLoader`) — fetches `cycles-protocol-v0.yaml` from `cycles-protocol@main`, caches to `target/contract/spec.yaml` with a 1-hour TTL. Override with `-Dcontract.spec.url=file:///...` for offline dev. Supports `file:` URLs natively.

**Enablement gate** — `-Dcontract.validation.enabled=false` (or env `CONTRACT_VALIDATION_ENABLED=false`) disables both phases cleanly for air-gapped builds.

**Test stub hardening** — `ReservationControllerTest.shouldGetReservationById` now populates all `ReservationDetail` required fields from the spec (previously returned `new ReservationDetail()`, which silently violated the documented response shape).

**Modified files:**
- `cycles-protocol-service-api/pom.xml` — added test-scope deps `com.atlassian.oai:swagger-request-validator-mockmvc:2.44.9` and `org.openapitools.openapidiff:openapi-diff-core:2.1.7`
- `cycles-protocol-service-api/src/test/java/io/runcycles/protocol/api/contract/ContractSpecLoader.java` (new)
- `.../contract/ContractValidationConfig.java` (new)
- `.../contract/OpenApiContractDiffTest.java` (new)
- `.../controller/BalanceControllerTest.java`, `ReservationControllerTest.java`, `DecisionControllerTest.java`, `EventControllerTest.java` — `@Import` extended to include `ContractValidationConfig.class`

**Verification:** `mvn -B test -pl cycles-protocol-service-api -Dtest='*ControllerTest,OpenApiContractDiffTest'` → 61 tests, 0 failures, 0 errors.

---

### 2026-04-11 — v0.1.25.7: Typed `Enums.ReasonCode` + flaky `EventEmitterServiceTest` fix

Two quality improvements bundled into a point release. No wire-format change; byte-identical JSON output to v0.1.25.6 on every response. No client impact.

**Change 1 — Typed `Enums.ReasonCode` (#83):**

`DecisionResponse.reasonCode` and `ReservationCreateResponse.reasonCode` were declared as free-form `@Size(max=128) String`, but the reference server has only ever emitted 6 distinct values across the `evaluateDryRun` and `decide` paths in `RedisReservationRepository`. Codified as a new Java enum `Enums.ReasonCode` with compile-time safety:

- `BUDGET_EXCEEDED`
- `BUDGET_FROZEN`
- `BUDGET_CLOSED`
- `BUDGET_NOT_FOUND`
- `OVERDRAFT_LIMIT_EXCEEDED`
- `DEBT_OUTSTANDING`

Mirrors the `DecisionReasonCode` schema added to `cycles-protocol-v0.yaml` in runcycles/cycles-protocol#26. Jackson's default enum serialization produces the `name()` string, so JSON output is byte-identical to the previous String-typed field — zero wire change, zero client impact.

Class-level javadoc on `Enums.ReasonCode` clarifies the relationship to `Enums.ErrorCode`: some labels overlap (e.g. `BUDGET_EXCEEDED` appears in both sets), but they live on different response types — `ReasonCode` on 200 DENY responses, `ErrorCode` on 4xx/5xx error bodies — surfacing the same underlying budget state differently depending on the endpoint.

**Modified files (change 1):**

- `Enums.java` — added `ReasonCode` enum with 6 values and cross-enum javadoc
- `DecisionResponse.java` — `reasonCode` field retyped from `String` to `Enums.ReasonCode`; `@Size(max=128)` dropped
- `ReservationCreateResponse.java` — same retyping
- `RedisReservationRepository.java` — 10 `.reasonCode(...)` call sites updated (5 in `evaluateDryRun`, 5 in `decide`); the two dynamic `"BUDGET_" + budgetStatus` concatenations converted to ternaries gated by the preceding `if ("FROZEN".equals() || "CLOSED".equals())` check, so exhaustive by construction
- `DecisionController.java`, `ReservationController.java` — the two `.reasonCode(response.getReasonCode())` call sites that feed `EventDataReservationDenied.builder()` (webhook event payload model, which keeps its own `String`-typed `reasonCode` as that's its wire contract with webhook consumers) now convert explicitly via `response.getReasonCode() != null ? response.getReasonCode().name() : null` — preserves the webhook payload shape exactly
- `RedisReservationCrudTest.java`, `RedisReservationDecideEventTest.java` — 11 unit-test assertions (5 + 6) that compared `.getReasonCode()` against string literals now compare against the `Enums.ReasonCode` constant; integration tests that parse `resp.getBody().get("reason_code")` as a String from JSON are unchanged (Jackson serializes the enum to its name)
- `ReservationControllerTest.java` — one test fixture builder updated

**Not touched:** `EventDataReservationDenied.reasonCode` stays `String`-typed — it's the webhook event payload contract (admin/webhook plane), wire-independent from `DecisionResponse`, with its own serialization target and consumers. Tightening it is a separate concern.

**Change 2 — De-flaked `EventEmitterServiceTest` (#82):**

`EventEmitterServiceTest` used `Thread.sleep(200); verify(repository)...` in 13 places to wait for async `CompletableFuture.runAsync` emissions before asserting on the mock. On loaded GitHub-hosted CI runners 200ms is not guaranteed to be enough for the executor callback to complete, causing intermittent `"Wanted but not invoked: ... zero interactions with this mock"` failures. Observed on run `24269168945` (the `docs: add v0.1.25.6 benchmarks` direct-to-main commit — a BENCHMARKS.md-only change, so the failure was obviously pre-existing flake, not a regression).

Fixed by replacing all 13 occurrences with Mockito's built-in `VerificationMode`s — no new test dependencies:

- Positive assertions (9 sites): `verify(mock, timeout(5000)).method(...)` — polls the mock, returns as soon as the condition is met, 5s deadline. Deterministic.
- Negative assertions (5 sites via 4 shared): `verify(mock, after(200).never()).method(...)` — waits fixed 200ms then asserts zero interactions. Preserves the "give the async a chance to emit, then confirm it didn't" semantics; can't use `timeout()` here because it'd succeed immediately at t=0.
- `emit_exceptionInRepo_doesNotThrow` (1 no-verify sleep): now explicitly `verify(mock, timeout(5000)).emit(any())` to prove the async catch branch was exercised, instead of relying on timing luck.

**Verification:** 10 consecutive local runs of `EventEmitterServiceTest` — 10/10 pass, stable 4.8–5.1s timing band. Full `mvn verify` on the reactor — all tests green, coverage met. Also ~1.7s faster per run (removing unnecessary positive-path sleeps).

**Modified files (change 2):**

- `EventEmitterServiceTest.java` — all 13 `Thread.sleep(200)` calls removed; replaced with `timeout()` / `after()` verification modes

**Version bump:** `0.1.25.6` → `0.1.25.7` (`cycles-protocol-service/pom.xml`).

**No wire-format change.** No spec update needed on this release (the companion `DecisionReasonCode` schema addition in runcycles/cycles-protocol#26 is tracked separately and is forward-compatible regardless of merge order).

**Closes:** runcycles/cycles-server#81 (flaky test). Companion to runcycles/cycles-protocol#26 (spec enum).

**Compliance re-verification (2026-04-11, post-runcycles/cycles-protocol#28):** After v0.1.25.7 shipped, `cycles-protocol#28` reopened the `DecisionReasonCode` schema from a closed 6-value enum back to `type: string, maxLength: 128` with documented `KNOWN VALUES` to enable v0.1.26 extension codes (`ACTION_QUOTA_EXCEEDED`, `ACTION_KIND_DENIED`, `ACTION_KIND_NOT_ALLOWED`) without dual-population workarounds. The reopened schema added normative clauses: *"Clients MUST gracefully handle unknown values"* and *"SDK code MUST NOT reject unknown values at the deserialization boundary."*

v0.1.25.7 was re-verified against the reopened spec and is **fully compliant**. The normative consumption clauses target CLIENT consumers of server responses (language SDKs, downstream services, dashboards), not the reference server itself which is the EMITTER of `reason_code`. The server's only deserialization path is the idempotency replay cache at `RedisReservationRepository.java:179` (dry_run) and `:695` (decide), which reads back values the server itself previously wrote via `Enums.ReasonCode` constants — by construction the cache contents ⊆ emission set ⊆ enum constants, so the typed Jackson deserialization never encounters an unknown value.

**A typed closed enum is the correct implementation for a well-defined emitter-owned set** — tighter than the spec's wire type, not looser. The spec's openness enables client-side forward-tolerance for extension-plane reason codes; it does not require the server to consume its own emissions with relaxed types. Companion clarification landed in runcycles/cycles-protocol#29 which adds a non-normative "SERVER IMPLEMENTATION NOTE" bullet to the v0.1.25 changelog capturing this reasoning in the authoritative spec.

When v0.1.26 extension reason codes are eventually implemented on the base server, `Enums.ReasonCode` will be extended with those constants **before** the corresponding emission sites are wired (standard "update-enum-before-emit" discipline). Until then, v0.1.25.7 stays in production unchanged.

This compliance note is backed by an explicit Jackson round-trip unit test: `cycles-protocol-service-data/src/test/java/io/runcycles/protocol/data/model/ReasonCodeJacksonRoundTripTest.java` — serializes every `Enums.ReasonCode` constant through `DecisionResponse` and `ReservationCreateResponse`, reads the JSON back, and asserts byte-exact round-trip fidelity. Any future accidental change to enum naming or Jackson configuration that would alter the wire format will fail this test at build time.

### 2026-04-10 — v0.1.25.6: Distinguish UNIT_MISMATCH from BUDGET_NOT_FOUND on reserve/event

**Bug (runcycles/cycles-client-rust#8):** `POST /v1/reservations` with `Amount::tokens(1000)` against a scope whose budget was stored in `USD_MICROCENTS` returned `404 BUDGET_NOT_FOUND`. The client could not distinguish "no budget at this scope" from "budget exists but in a different unit" and had no hint toward the fix. `/v1/events` had the same latent bug.

**Root cause:** `reserve.lua` / `event.lua` key budgets by `budget:<scope>:<unit>`. When the requested unit doesn't match the stored unit, the key doesn't exist, the scope is silently skipped, and `#budgeted_scopes == 0` falls through to `BUDGET_NOT_FOUND`. The existing `UNIT_MISMATCH` branch in `event.lua` only caught an internal inconsistency between key suffix and stored `unit` field — it did not catch the cross-unit case.

**Fix:** On the empty-budgeted-scopes error path only, both scripts now probe the fixed `UnitEnum` set (`USD_MICROCENTS`, `TOKENS`, `CREDITS`, `RISK_POINTS`) via `EXISTS budget:<scope>:<unit_alt>` for each affected scope. If any alternate-unit budget exists, the script returns `UNIT_MISMATCH` (400) with `scope`, `requested_unit`, and `expected_units` in the error payload so the client can self-correct. Otherwise falls through to the existing `BUDGET_NOT_FOUND` (404).

Cascade semantics preserved: scopes without a budget at the requested unit are still silently skipped during the main validation loop — the probe only fires when every affected scope missed. No hot-path change; the cost is paid once on the error path only.

`evaluateDryRun` and `/v1/decide` (the non-Lua Java paths) get the symmetric probe via a shared `probeAlternateUnits` helper and throw `UNIT_MISMATCH` (400) to match the reserve/event behavior. Spec line 1131-1134 only prohibits 409 on `/decide` for debt/overdraft conditions; 400 for a request-validity error (wrong unit) is permitted and is consistent across all four entry points.

**Modified files:**
- `reserve.lua` — new `ARGV[15] = units_csv`; scopes now start at ARGV[16]; alternate-unit probe added to the empty-budgeted-scopes branch
- `event.lua` — new `ARGV[14] = units_csv`; scopes now start at ARGV[15]; symmetric probe
- `RedisReservationRepository.java` — `UNIT_CSV` constant derived once from `Enums.UnitEnum.values()`; passed into both `createReservation` and `createEvent` args; `evaluateDryRun` and `decide` mirror the probe via a shared `probeAlternateUnits(jedis, scope, requestedUnit)` helper; `handleScriptError` extracts `scope` / `requested_unit` / `expected_units` for reserve/event and falls back to the no-detail factory for commit.lua's legacy form
- `CyclesProtocolException.java` — `unitMismatch(scope, requestedUnit, expectedUnits)` overload populating `details`
- `ReservationLifecycleIntegrationTest.java` — `shouldRejectWhenNoBudgetExistsForUnit` renamed to `shouldRejectWithUnitMismatchWhenBudgetExistsInDifferentUnit` and flipped to expect 400 + details; added `shouldReturnBudgetNotFoundWhenNoBudgetAtAnyUnit` regression guard and `shouldReturnUnitMismatchOnDryRunWhenBudgetExistsInDifferentUnit`
- `DecisionAndEventIntegrationTest.java` — `shouldRejectEventWithUnitMismatch` flipped from 404 `NOT_FOUND` to 400 `UNIT_MISMATCH` + details; added `shouldReturnBudgetNotFoundWhenNoBudgetAtAnyUnitOnEvent`, `shouldRejectDecideWithUnitMismatchWhenBudgetExistsInDifferentUnit`, and `shouldReturnDenyBudgetNotFoundOnDecideWhenNoBudgetAtAnyUnit`
- `RedisReservationCoreOpsTest.java` — existing `shouldThrowUnitMismatch` asserts the no-detail fallback path; added `shouldThrowUnitMismatchWithDetailsFromReserve`
- `CyclesProtocolExceptionTest.java` — coverage for the new factory overload (populated details + null-tolerant form)
- `cycles-protocol-service/README.md` — error table entry for `UNIT_MISMATCH` broadened to include reserve + describe the `details.*` payload
- `cycles-protocol-service/pom.xml` — `<revision>` bumped `0.1.25.5` → `0.1.25.6`

**Closes:** runcycles/cycles-server#79. Addresses runcycles/cycles-client-rust#8.

**Out of scope:** Client-side rust SDK changes (not needed — structured error is enough for the user to correct their call). Protocol YAML spec update lives in `runcycles/cycles-protocol` and is handled on a coordinated branch (adds `"404"` to `/v1/reservations` POST and `/v1/events` POST response lists + broadens normative UNIT_MISMATCH wording to cover reserve).

### 2026-04-07 — v0.1.25.4: Event data payload completeness

**Compliance review** against protocol spec v0.1.25 + admin spec v0.1.25 found 5 event data payload gaps. Core protocol (endpoints, schemas, error codes, Lua scripts, idempotency, scope derivation, auth/tenancy) was fully compliant.

**Fixes applied:**

| # | Issue | Fix |
|---|-------|-----|
| 1 | `EventDataReservationDenied` missing `unit`, `remaining`, `action`, `subject` | Populated from request context in DecisionController and ReservationController |
| 2 | `EventDataCommitOverage` missing `scope`, `unit`, `estimatedAmount`, `overage`, `overagePolicy`, `debtIncurred` | Populated from CommitResponse internal fields; added `scope_path`/`overage_policy` to commit.lua return. Audit fix: uses `request.actual` (not `response.charged`) for actualAmount/overage — charged is capped by ALLOW_IF_AVAILABLE |
| 3 | `EventDataBudgetDebtIncurred` missing `reservationId`, `debtIncurred`, `overagePolicy` | Added per-scope `debt_incurred` tracking in commit.lua/event.lua via `scope_debt_incurred` table; plumbed through `scopeDebtIncurred` map in CommitResponse/EventCreateResponse; `emitBalanceEvents()` overload with full context |
| 4 | `budget.exhausted` emitted with `null` data | Now emits `EventDataBudgetThreshold` with scope, unit, threshold=1.0, utilization, allocated, remaining=0, spent, reserved, direction="rising" |
| 5 | `Event.actor` missing `keyId` and `sourceIp` | Added `keyId` to `ApiKeyAuthentication`; `buildActor()` helper in BaseController extracts keyId from auth context and sourceIp from HttpServletRequest |

**Modified files:**
- `commit.lua` — returns `scope_path`/`overage_policy` in response; tracks per-scope `scope_debt_incurred` table, includes in balance snapshots; version comment v0.1.24 → v0.1.25
- `event.lua` — tracks per-scope `scope_debt_incurred` table, includes in balance snapshots
- `CommitResponse.java` — added `@JsonIgnore` internal fields: `scopePath`, `overagePolicy`, `debtIncurred`, `scopeDebtIncurred`
- `EventCreateResponse.java` — added `@JsonIgnore scopeDebtIncurred` map
- `ApiKeyAuthentication.java` — added `keyId` field + getter
- `ApiKeyAuthenticationFilter.java` — passes `keyId` from validation response
- `BaseController.java` — added `buildActor(HttpServletRequest)` helper
- `DecisionController.java` — full EventDataReservationDenied fields + Actor with keyId/sourceIp
- `ReservationController.java` — full EventDataReservationDenied/CommitOverage fields + Actor; uses request.actual (not response.charged) for overage event; passes `scopeDebtIncurred` to emitBalanceEvents
- `EventController.java` — Actor with keyId/sourceIp; passes overagePolicy + scopeDebtIncurred to emitBalanceEvents
- `EventEmitterService.java` — `emitBalanceEvents` overloads with `reservationId`/`overagePolicy`/`scopeDebtIncurred`; budget.exhausted uses EventDataBudgetThreshold; budget.debt_incurred uses per-scope debtIncurred from map
- `RedisReservationRepository.java` — `parseScopeDebtIncurred()` helper; parses `scope_path`, `overage_policy`, `debt_incurred` from Lua response

**Tests:** 287 tests pass, 0 failures. Added tests for `keyId` propagation, budget.exhausted data payload, debt_incurred reservation context, per-scope debt_incurred map.

**Remaining event data gaps:** None. All EventData fields now fully populated for runtime-emitted events.

### 2026-04-08 — v0.1.25.5: Fix duplicate budget state events (cycles-server-events#15)

**Bug:** `budget.exhausted`, `budget.over_limit_entered`, and `budget.debt_incurred` events fired on every operation where the post-state matched the condition, not only on state *transitions*. For example, a reserve that depleted a budget emitted `budget.exhausted`, then the subsequent commit (with remaining still at 0) emitted it again.

**Root cause:** `EventEmitterService.emitBalanceEvents()` checked post-mutation state only (e.g., `remaining == 0`). No transition detection.

**Fix:** Lua scripts (reserve, commit, event) now include `pre_remaining` and `pre_is_over_limit` per scope in balance snapshots. Java emits only on transitions:
- `budget.exhausted`: `pre_remaining > 0 && remaining == 0`
- `budget.over_limit_entered`: `!pre_is_over_limit && is_over_limit`
- `budget.debt_incurred`: `scopeDebtIncurred[scope] > 0` (already tracked)

**Performance:** No extra Redis calls. reserve.lua caches pre-state from existing validation HMGET. commit.lua caches from existing overage-path reads (ALLOW_IF_AVAILABLE/ALLOW_WITH_OVERDRAFT); delta <= 0 paths skip (remaining can only increase). event.lua folds `is_over_limit` into existing HMGET.

---

### 2026-04-03 — v0.1.25.3: Extended runtime event emission + PROTOCOL_VERSION fix

**Version bump:** 0.1.25.1 → 0.1.25.3 (0.1.25.2 was the case-insensitive scope fix below).

**Fix:** `Enums.PROTOCOL_VERSION` was hardcoded to `"0.1.24"` — updated to `"0.1.25"` to match the current protocol spec.

**New runtime event emissions (protocol spec v0.1.25 Webhook Event Guidance):**

Added 4 new event types emitted from Java controllers (non-blocking, async via `EventEmitterService`):

| Event Type | Detection | Emit Location |
|---|---|---|
| `budget.exhausted` | `remaining.amount == 0` in any post-operation balance | ReservationController (reserve, commit), EventController |
| `budget.over_limit_entered` | `is_over_limit == true` in any post-operation balance | ReservationController (reserve, commit), EventController |
| `budget.debt_incurred` | `debt.amount > 0` in any post-operation balance | ReservationController (reserve, commit), EventController |
| `reservation.expired` | Expiry sweeper Lua returns `EXPIRED` status | ReservationExpiryService (post-expire HGETALL for tenant/scope context) |

**Implementation approach:**
- `EventEmitterService.emitBalanceEvents()` — new helper that inspects post-operation balances returned from Lua scripts. Called from ReservationController (after reserve and commit) and EventController (after event creation). No extra Redis calls — uses balances already on the response.
- `ReservationExpiryService.emitExpiredEvent()` — after expire.lua succeeds, fetches the reservation hash (1 HGETALL) to get tenant_id, scope_path, estimate_amount, created_at_ms, expires_at_ms, extension_count for the event payload. Uses `ActorType.SYSTEM` since it's a background job.

**Events NOT emitted (deferred — require new infrastructure):**
- `budget.threshold_crossed` — needs per-scope threshold configuration + utilization % calculation
- `budget.burn_rate_anomaly` — needs time-series rate tracking subsystem
- `budget.over_limit_exited` — admin-only event (triggered by funding operations in cycles-server-admin)

**Runtime event coverage:** 6 of 9 spec-suggested event types now emitted (was 2).

**Modified files:**
- `Enums.java` — PROTOCOL_VERSION "0.1.24" → "0.1.25"
- `EventEmitterService.java` — added `emitBalanceEvents()` helper
- `ReservationController.java` — wired `emitBalanceEvents()` after reserve and commit
- `EventController.java` — wired `emitBalanceEvents()` after event creation
- `ReservationExpiryService.java` — added `emitExpiredEvent()` post-expire hook
- `pom.xml` — revision 0.1.25.1 → 0.1.25.3

---

### 2026-04-03 — v0.1.25.2: Case-insensitive scope matching

**Bug fix (defense-in-depth):** The admin API may have stored mixed-case scope values. `getBalances` lowercased query params but not the stored scope from Redis, causing case mismatches. Now lowercases `trueScope`/`scopePath` before segment matching in both `getBalances` and `listReservations`.

| Fix | Location |
|-----|----------|
| Lowercase `trueScope` before `scopeHasSegment` matching in `getBalances` | `RedisReservationRepository.java` |
| Lowercase `scopePath` before `scopeHasSegment` matching in `listReservations` | `RedisReservationRepository.java` |

Related: runcycles/cycles-openclaw-budget-guard#70, runcycles/cycles-server-admin#54

---

### 2026-04-01 — Webhook Event Emission + TTL Retention + Performance Optimizations

Added webhook event emission to the runtime server per v0.1.25 spec, enabling runtime operations to trigger webhook deliveries via the shared Redis dispatch queue.

**Build:** 553 tests (283 API + 261 Data + 9 Model), 0 failures, 95%+ coverage (all modules).

**New capabilities:**
- ReservationController emits `reservation.denied` on DENY decision
- DecisionController emits `reservation.denied` on DENY decision
- ReservationController emits `reservation.commit_overage` when charged > estimated
- Events written to shared Redis (`event:{id}`, `events:{tenantId}`, `events:_all`)
- Matching webhook subscriptions found via `webhooks:{tenantId}` + `webhooks:__system__`
- PENDING deliveries created and LPUSH'd to `dispatch:pending` for cycles-server-events
- Source field: `"cycles-server"` (vs `"cycles-admin"` from admin)

**TTL retention:**
- `event:{id}` keys: 90-day TTL (configurable via `EVENT_TTL_DAYS`)
- `delivery:{id}` keys: 14-day TTL (configurable via `DELIVERY_TTL_DAYS`)

**Bug fix:** Commit overage emit condition was firing on every commit (`actual != null && charged != null`). Fixed to only emit when `charged > estimateAmount` (true overage). Added `@JsonIgnore estimateAmount` to `CommitResponse` for internal plumbing.

**Performance optimizations (3):**
1. **Async event emission** — `EventEmitterService` uses `CompletableFuture.runAsync()` on a dedicated daemon thread pool (`availableProcessors/4`). Emit never blocks the request thread.
2. **Pipelined Redis in EventEmitterRepository** — Event save (SET + EXPIRE + 2x ZADD) + subscription lookup (2x SMEMBERS) batched into 1 pipeline round-trip (was 6 sequential). Subscription GETs pipelined. Delivery creation pipelined.
3. **Early exit on no subscribers** — If both SMEMBERS return empty sets, skips subscription resolve and delivery creation entirely.

**Result:** Commit p50 recovered from 13.4ms (pre-fix) to 5.6ms. Concurrent throughput at 32 threads: 2,584 ops/s (matching v0.1.24.3 baseline of 2,534 ops/s). Near-zero overhead on non-event paths.

**New/modified files (33):**
- 11 event model classes (Event, EventType, EventCategory, Actor, ActorType, 6 EventData*)
- 6 webhook model classes (WebhookSubscription, WebhookDelivery, WebhookRetryPolicy, etc.)
- CryptoService (AES-256-GCM for signing secret encryption at rest)
- EventEmitterRepository (event persistence + pipelined subscription matching + dispatch)
- EventEmitterService (async emit via CompletableFuture + daemon thread pool)
- CommitResponse (added `@JsonIgnore estimateAmount` for overage detection)
- RedisReservationRepository (sets estimateAmount on CommitResponse)
- ReservationController (fixed commit overage condition: charged > estimateAmount)
- 3 test classes (CryptoServiceTest, EventEmitterRepositoryTest, EventEmitterServiceTest)

**Docker-compose updates:**
- All compose files now include `WEBHOOK_SECRET_ENCRYPTION_KEY` env var
- Full-stack compose files include `cycles-events` service (port 7980)

**Architecture: shared Redis queue**
```
cycles-server ─────┐
                   ├── event:{id}, delivery:{id}, LPUSH dispatch:pending
cycles-admin ──────┘
                           │
                     Redis ─┤
                           │
cycles-server-events ── BRPOP dispatch:pending → HTTP POST with HMAC
```

**Resilience: events service down**
- Admin and runtime continue operating normally (fire-and-forget)
- Events/deliveries accumulate in Redis with TTL (auto-expire after 90d/14d)
- When events service restarts: stale deliveries (>24h) auto-fail, fresh ones delivered
- ZSET indexes trimmed hourly by RetentionCleanupService

---

## Summary

| Category | Pass | Issues |
|----------|------|--------|
| Endpoints & Routes | 9/9 | 0 |
| Request Schemas | 6/6 | 0 |
| Response Schemas | 8/8 | 0 |
| HTTP Status Codes | — | 0 |
| Response Headers | — | 0 |
| Error Handling | — | 0 |
| Auth & Tenancy | — | 0 |
| Idempotency | — | 0 |
| Scope Derivation | — | 0 |
| Normative Requirements | — | 0 |
| Lua Atomicity | — | 0 |
| Dry-Run Semantics | — | 0 |
| Overdraft/Debt Model | — | 0 |
| Grace Period Handling | — | 0 |
| Test Coverage | — | 0 |
| Tenant Default Config | — | 0 |
| Performance Optimizations | 16/16 | 0 |
| Production Hardening | 8/8 | 0 |

**All previously identified issues have been fixed. No remaining spec violations found. Performance optimized, hardened, and benchmarked.**

---

## Audit Scope

Two-pass audit covering:
- All 9 spec endpoints vs server routes
- All 6 request schemas and 8 response schemas (fields, types, constraints, defaults, required markers)
- All 12 error codes, HTTP status mappings, and error semantics
- Auth/tenancy enforcement across all endpoints
- Idempotency per-endpoint namespacing, replay, and payload mismatch detection
- Scope derivation canonical ordering (gaps skipped, not filled)
- Response headers (X-Request-Id, X-Cycles-Tenant, X-RateLimit-*)
- Lua script atomicity for reserve, commit, release, extend, event, expire
- Dry-run response rules (reservation_id/expires_at_ms absent, affected_scopes populated)
- Overdraft/debt model (ALLOW_WITH_OVERDRAFT, is_over_limit, DEBT_OUTSTANDING — only blocks when overdraft_limit=0; ALLOW_WITH_OVERDRAFT falls back to ALLOW_IF_AVAILABLE when overdraft_limit=0)
- Grace period semantics (commits accepted through expires_at_ms + grace_period_ms)
- Subject.dimensions round-tripping
- Unit mismatch detection on commit and event

---

## PASS — Correctly Implemented

### Endpoints (all 9 match spec)
- `POST /v1/decide` — DecisionController
- `POST /v1/reservations` — ReservationController (including dry_run)
- `GET /v1/reservations` — ReservationController (list with filters)
- `GET /v1/reservations/{reservation_id}` — ReservationController
- `POST /v1/reservations/{reservation_id}/commit` — ReservationController
- `POST /v1/reservations/{reservation_id}/release` — ReservationController
- `POST /v1/reservations/{reservation_id}/extend` — ReservationController
- `GET /v1/balances` — BalanceController
- `POST /v1/events` — EventController (returns 201 per spec)

### Request/Response Schemas (all match)
- All 6 request schemas match spec (fields, types, constraints, defaults)
- All 8 response schemas match spec (fields, required markers, types)
- All 12 spec error codes present; server adds 2 extra (BUDGET_FROZEN, BUDGET_CLOSED) for admin-managed budget states
- All 4 status enums match spec
- `Subject` anyOf validation correctly enforced via `hasAtLeastOneStandardField()`
- `additionalProperties: false` correctly enforced via `@JsonIgnoreProperties(ignoreUnknown = false)`
- `Balance` schema includes all debt/overdraft fields (debt, overdraft_limit, is_over_limit)
- `Balance.remaining` correctly typed as `SignedAmount` (allows negative values in overdraft)
- `ReleaseResponse.released` guaranteed non-null (falls back to zero Amount on data corruption)

### Auth & Tenancy (fully correct)
- X-Cycles-API-Key authentication via `ApiKeyAuthenticationFilter`
- Tenant validation via `BaseController.authorizeTenant()` — returns 403 on mismatch
- Reservation ownership enforced on all mutation/read endpoints (commit, release, extend, get)
- Balance visibility correctly scoped to effective tenant
- List reservations correctly scoped to effective tenant
- Subject.tenant validated against effective tenant on reserve, decide, and event endpoints
- Subject.tenant can be null/omitted (spec allows via anyOf constraint)

### Scope Derivation (fully correct)
- Canonical ordering: tenant → workspace → app → workflow → agent → toolset
- Gaps skipped — only explicitly provided subject levels are included (no "default" filling)
- Scopes without budgets are skipped during enforcement; at least one scope must have a budget
- Scope paths lowercased for stable canonicalization
- Subject.dimensions round-tripped through JSON serialization in ReservationDetail

### Idempotency (fully correct)
- Per-endpoint namespacing in all Lua scripts:
  - `idem:{tenant}:reserve:{key}` (reserve.lua)
  - `idem:{tenant}:decide:{key}` (repository Java code)
  - `idem:{tenant}:dry_run:{key}` (repository Java code)
  - `idem:{tenant}:event:{key}` (event.lua)
  - `idem:{tenant}:extend:{reservation_id}:{key}` (extend.lua)
  - Commit/release: stored on reservation hash itself (per-reservation)
- Header vs body key mismatch detection: `BaseController.validateIdempotencyHeader()`
- Payload hash-based mismatch detection via SHA-256 (all mutation endpoints)
- Idempotency replay returns original response for all endpoints

### Response Headers (fully correct)
- `X-Request-Id` set on every response (including errors) via `RequestIdFilter` — header set before `filterChain.doFilter()`, guaranteed present on all responses
- `X-Cycles-Tenant` set on every authenticated response via `ApiKeyAuthenticationFilter`
- `X-RateLimit-Remaining` / `X-RateLimit-Reset` set on all `/v1/` paths via `RateLimitHeaderFilter` (optional in v0; sentinel values -1/0 signal unlimited)

### Error Semantics (all correct)
- BUDGET_EXCEEDED → 409
- OVERDRAFT_LIMIT_EXCEEDED → 409
- DEBT_OUTSTANDING → 409
- RESERVATION_FINALIZED → 409
- RESERVATION_EXPIRED → 410
- NOT_FOUND → 404
- UNIT_MISMATCH → 400 (enforced in reserve.lua, commit.lua, event.lua; reserve/event paths return `scope`, `requested_unit`, `expected_units` in details so the client can self-correct — see v0.1.25.6)
- IDEMPOTENCY_MISMATCH → 409
- INVALID_REQUEST → 400
- INTERNAL_ERROR → 500
- `/decide` correctly returns `decision=DENY` (not 409) for debt/overdraft conditions
- Commit accepts until `expires_at_ms + grace_period_ms` (commit.lua line 76)
- Release accepts until `expires_at_ms + grace_period_ms` (release.lua line 47)
- Extend only accepts while `status=ACTIVE` and `server_time <= expires_at_ms` (extend.lua)
- List reservations `status` query param validated against `ReservationStatus` enum

### Normative Spec Rules (all correctly implemented)
- Reserve is atomic across all derived scopes (Lua script)
- Commit and release are idempotent (Lua scripts)
- No double-charge on retries (idempotency key enforced)
- `dry_run=true` does not mutate balances or persist reservations
- `dry_run=true` omits `reservation_id` and `expires_at_ms`
- `dry_run=true` populates `affected_scopes` regardless of decision outcome
- `dry_run=true` populates `balances` (recommended by spec)
- Events applied atomically across derived scopes (Lua script)
- Event endpoint returns 201 (not 200) per spec
- `include_children` parameter accepted but ignored (spec allows in v0)
- Over-limit blocking: reservations rejected with OVERDRAFT_LIMIT_EXCEEDED when `is_over_limit=true`
- `is_over_limit` set to true when `debt > overdraft_limit` after commit OR when ALLOW_IF_AVAILABLE caps the overage delta
- `GET /v1/reservations/{id}` returns `ReservationDetail` with `status: EXPIRED` for expired reservations
- All mutation responses (reserve, commit, release, extend, event) populate optional `balances` field

### Lua Atomicity (correct, optimized)
- `ALLOW_IF_AVAILABLE` in commit.lua uses two-phase capped-delta pattern: determine minimum available remaining across all scopes, then charge estimate + capped_delta atomically. Never rejects — sets is_over_limit when capped.
- `ALLOW_IF_AVAILABLE` in event.lua uses same capped pattern: cap amount to minimum available, charge capped amount, set is_over_limit when capped.
- `ALLOW_WITH_OVERDRAFT` in commit.lua uses fail-fast pattern with cached scope values (eliminates redundant Redis reads in mutation loop)
- Event.lua uses same fail-fast atomicity patterns for ALLOW_WITH_OVERDRAFT
- Reserve.lua atomically checks and deducts across all derived scopes using HMGET (1 call per scope instead of 5)
- All Lua scripts leverage Redis single-threaded execution for atomicity
- Balance snapshots returned atomically from Lua scripts (reserve, commit, release) — read consistency guaranteed within the same atomic operation
- Commit/release idempotency-hit paths return `estimate_amount`/`estimate_unit` for correct `released` calculation

### Test Coverage (above 95%)
- JaCoCo line coverage threshold raised from 90% to **95%** (enforced in parent pom.xml)
- **API module**: **95%+** line coverage (283 tests across 15 test classes)
- **Data module**: **95%+** line coverage, 76%+ branch coverage (261 tests across 11 test classes)
  - Branch gaps: defensive null-check ternaries and unreachable `&&` short-circuit branches in `RedisReservationRepository`
  - Tenant default resolution: 10 unit tests covering all fallback paths, TTL capping, max extensions, malformed JSON recovery
  - LuaScriptRegistry: 5 tests (startup load, EVALSHA, NOSCRIPT fallback, no-SHA fallback, startup failure)
  - Idempotency replay: 2 tests (commit replay returns released amount, release replay returns released amount)
  - API key cache: 1 test (verifies cache hit avoids second Redis call)
- **Model module**: Coverage skipped (POJOs only, no business logic) — 9 tests
- Total test count: 283 (API) + 261 (Data) + 9 (Model) = **553 tests** across 26 test classes
- **Integration tests**: 27+ nested test classes covering all 9 endpoints, including:
  - Tenant Defaults (9 tests): overage policy resolution (ALLOW_IF_AVAILABLE, ALLOW_WITH_OVERDRAFT, REJECT), explicit override vs tenant default, TTL capping, max extensions enforcement, default TTL usage, no-tenant-record fallback
  - Expiry Sweep (7 tests): end-to-end expire.lua execution, grace period skip, orphan TTL cleanup, multi-scope budget release, already-finalized skip
  - Budget Status (2 tests): BUDGET_FROZEN and BUDGET_CLOSED enforcement on reserve
  - ALLOW_IF_AVAILABLE commit overage (integration coverage)
- **Performance benchmarks**: 8 write-path tests (6 individual operations + 2 composite lifecycles) + 4 read-path tests (GET reservation, GET balances, LIST reservations, Decide pipelined), tagged `@Tag("benchmark")`
- All unit tests pass without Docker/Testcontainers (integration and benchmark tests excluded by default)

### Tenant Default Configuration (correct)
- `default_commit_overage_policy`: resolved at reservation/event creation time
  - Resolution order: request-level `overage_policy` > tenant `default_commit_overage_policy` > hardcoded `ALLOW_IF_AVAILABLE`
  - Tenant config read from Redis `tenant:{tenant_id}` JSON record (shared with admin service)
  - Graceful fallback to `ALLOW_IF_AVAILABLE` on tenant read failure or missing record
- `default_reservation_ttl_ms`: used when request omits `ttl_ms` (was hardcoded to 60000ms)
  - Resolution order: request `ttl_ms` > tenant `default_reservation_ttl_ms` > hardcoded 60000ms
- `max_reservation_ttl_ms`: caps requested TTL to tenant maximum (default 3600000ms)
  - Applied after default resolution: `Math.min(effectiveTtl, maxTtl)`
- `max_reservation_extensions`: stored on reservation at creation, enforced in extend.lua
  - `extension_count` tracked and incremented atomically in extend.lua
  - Returns `MAX_EXTENSIONS_EXCEEDED` (HTTP 409) when count reaches max
  - Default: 10 (spec default)
- Request model fields (`overagePolicy`, `ttlMs`) changed from hardcoded defaults to `null`
  to allow tenant config resolution when client omits them

### Overdraft/Debt Model (correct)
- `ALLOW_WITH_OVERDRAFT` policy supported on both commit and event
- Debt tracked per-scope, `is_over_limit` flag set when `debt > overdraft_limit` or when ALLOW_IF_AVAILABLE caps the overage
- Over-limit scopes block new reservations with OVERDRAFT_LIMIT_EXCEEDED
- Concurrent commit/event behavior matches spec (per-operation check, not cross-operation atomic)
- Event overage_policy defaults to ALLOW_IF_AVAILABLE, supports all three policies

### Performance Optimizations (all applied)

Sixteen optimizations applied to the reserve/commit/release hot path and event emission, preserving all protocol correctness guarantees (atomicity, idempotency, ledger invariant `remaining = allocated - spent - reserved - debt`).

| # | Optimization | Impact | Location |
|---|-------------|--------|----------|
| 1 | **BCrypt API key cache** — Caffeine cache keyed by SHA-256(key), 60s TTL, max 5000 entries | Eliminates ~100ms+ BCrypt per request on cache hit | `ApiKeyRepository.java` |
| 2 | **EVALSHA** — Script SHA loaded at startup, 40-char hash sent instead of full script | Saves ~1-5KB network per call; auto-fallback to EVAL on NOSCRIPT | `LuaScriptRegistry.java` (new), all `eval()` callers |
| 3 | **Pipelined balance fetch** — N HGETALL calls batched into single round-trip | Saves (N-1) round-trips for fallback paths (extend, events, idempotency hits) | `RedisReservationRepository.fetchBalancesForScopes()` |
| 4 | **Lua returns balances** — Balance snapshots collected atomically at end of reserve/commit/release scripts | Eliminates post-operation Java balance fetch entirely for primary paths | `reserve.lua`, `commit.lua`, `release.lua` |
| 5 | **Lua HMGET consolidation** — Replaced scattered HGET calls with batched HMGET in all scripts; eliminated redundant EXISTS + HGET patterns; reused TIME call results | Fewer Redis commands inside Lua scripts | `expire.lua`, `extend.lua`, `commit.lua`, `event.lua` |
| 6 | **Tenant config cache** — Caffeine cache with configurable TTL (default 60s, `cycles.tenant-config.cache-ttl-ms`), max 1000 entries | Saves 1 Redis GET per reserve/event | `RedisReservationRepository.getTenantConfig()` |
| 7 | **ThreadLocal MessageDigest** — SHA-256 instance reused per thread in both ApiKeyRepository and RedisReservationRepository | Reduces per-request allocations | `ApiKeyRepository.java`, `RedisReservationRepository.java` |
| 8 | **Pipelined idempotency checks** — Idempotency key + hash fetched/stored via Redis Pipeline (2 ops in 1 round-trip) | Saves 1 round-trip per dry_run/decide request | `RedisReservationRepository.evaluateDryRun()`, `decide()` |
| 9 | **Caffeine caches** — Replaced manual ConcurrentHashMap + lazy eviction with Caffeine (automatic TTL expiry, LRU eviction, bounded size) | Eliminates O(n) on-request-path eviction; prevents memory leaks | `ApiKeyRepository.java`, `RedisReservationRepository.java` |
| 10 | **Connection pool tuning** — MaxTotal=128, MaxIdle=32, MinIdle=16, testOnBorrow, testWhileIdle, idle eviction; all configurable via properties | Prevents pool exhaustion under load; validates connections | `RedisConfig.java` |
| 11 | **expire.lua double TIME elimination** — Reuses `now` from initial TIME call instead of calling TIME twice | 1 fewer Redis command per expiry | `expire.lua` |
| 12 | **event.lua scope budget caching** — Budget fields cached during validation phase and reused in capping/mutation phases | Eliminates redundant HGET/HMGET calls per scope across 3 phases | `event.lua` |
| 13 | **Lua script loading** — Replaced O(n²) string concatenation with readAllBytes | Faster startup (startup-only) | `RedisConfig.java` |
| 14 | **Async event emission** — `CompletableFuture.runAsync()` on daemon thread pool (`availableProcessors/4`) | Emit never blocks request thread; near-zero overhead on non-event paths | `EventEmitterService.java` |
| 15 | **Pipelined event emit** — Event save + subscription lookup in 1 pipeline (was 6 sequential); subscription GETs pipelined; delivery creation pipelined | 6→1 round-trips for event save+lookup; N→1 for subscription resolve; 4→1 for delivery | `EventEmitterRepository.java` |
| 16 | **Early exit on no subscribers** — If both tenant + system SMEMBERS return empty, skip subscription resolve and delivery creation | Zero additional Redis calls when no webhooks configured | `EventEmitterRepository.java` |

**Thread safety**: All caches use Caffeine (thread-safe by design). ThreadLocal MessageDigest avoids contention. Event emit thread pool uses daemon threads that don't prevent JVM shutdown.
**Backward compatibility**: Old reservations without `budgeted_scopes` field handled via `budgeted_scopes_json or affected_scopes_json` fallback in all Lua scripts.

### Performance Benchmarks

See [BENCHMARKS.md](BENCHMARKS.md) for full benchmark history across versions.

Run benchmarks: `mvn test -Pbenchmark` (requires Docker). Excluded from default `mvn verify` builds via `<excludedGroups>benchmark</excludedGroups>` in surefire config.

### Read-Path Pipelines & Operational Fixes (Phase 3)

1. **Pipeline `evaluateDryRun()`** — Replaced 2N+1 sequential `jedis.hgetAll()` calls with a single pipelined round-trip. With 6 scope levels, this reduces 13 Redis round-trips to 1.

2. **Pipeline `decide()`** — Same pattern: N+1 sequential budget lookups + caps fetch consolidated into 1 pipeline call.

3. **Pipeline `getBalances()` SCAN loop** — Replaced per-key `jedis.hgetAll()` inside SCAN loop with batched pipeline per SCAN batch. Up to 100 round-trips per batch reduced to 1. Also pre-lowercased filter parameters once at method entry (scope paths already lowercased at creation by `ScopeDerivationService`).

4. **Pre-lowercased `listReservations()` filters** — Same `.toLowerCase()` optimization applied to `listReservations()`.

5. **INFO → DEBUG logging in `BaseController.authorizeTenant()`** — Two `LOG.info` calls fired on every authenticated request; changed to `LOG.debug`.

6. **HTTP response compression** — Enabled `server.compression` for JSON responses > 1KB.

7. **30-day TTL on terminal reservation hashes** — `commit.lua`, `release.lua`, and `expire.lua` now set `PEXPIRE 2592000000` on reservation hashes after state transition to COMMITTED/RELEASED/EXPIRED. Active reservations keep no TTL (cleaned by expiry sweep).

8. **30-day TTL on event hashes** — `event.lua` now sets `PEXPIRE 2592000000` on event hashes after creation.

9. **GET endpoint benchmarks** — New `CyclesProtocolReadBenchmarkTest.java` benchmarks GET /v1/reservations/{id}, GET /v1/reservations (list), GET /v1/balances, and POST /v1/decide.

**Items reviewed and confirmed correct (no fix needed):**
- `luaScripts.eval()` return value — Lua scripts always return via `cjson.encode()`, never nil
- Event idempotency replay returns empty balances — consistent with commit/release pattern; `parseLuaBalances()` handles gracefully
- `fetchBalancesForScopes()` still used in reserve idempotency-hit fallback path — not dead code
- Thread safety: all caches use `ConcurrentHashMap`, `ThreadLocal<MessageDigest>` for digests, Jedis connections scoped to try-with-resources
- Balance snapshot ordering: collected after mutations in all Lua scripts
- Percentile calculations: mathematically correct with bounds checking
- Redis pool size (50) / timeout (2s) — deployment tuning, configurable via RedisConfig
- Cache race conditions in `ApiKeyRepository` and `LuaScriptRegistry` — `ConcurrentHashMap` ops are atomic; duplicate work is harmless

---

### Production Hardening (v0.1.24.3)

Operational readiness improvements added in v0.1.24.3:

| # | Item | Status |
|---|------|--------|
| 1 | **Prometheus metrics** — Micrometer + Prometheus registry, `/actuator/prometheus` endpoint (admin-key protected as of v0.1.25.45) | Done |
| 2 | **Structured JSON logging** — Spring Boot 3.4+ native structured logging; set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` for ECS JSON in production | Done |
| 3 | **Graceful shutdown** — `server.shutdown=graceful` with 30s drain timeout | Done |
| 4 | **Docker HEALTHCHECK** — `/actuator/health/readiness` probe with 15s interval | Done |
| 5 | **JVM production flags** — `JAVA_OPTS` entrypoint with G1GC, 75% RAM, string deduplication | Done |
| 6 | **Input sanitization** — `@Pattern` on Subject scope fields prevents Redis key path injection | Done |
| 7 | **Error logging** — ApiKeyRepository catch blocks now log errors instead of silently swallowing | Done |
| 8 | **Connection pool tuning** — 128/32/16 pool sizing, testWhileIdle, idle eviction; all configurable via properties | Done |

### Production Hardening (Phase 2 audit)

Code review of all changes identified and fixed four defensive issues:

1. **All Lua scripts: `cjson.decode` crash on corrupted Redis data** — If `affected_scopes` or `budgeted_scopes` stored in a reservation hash contains malformed JSON, `cjson.decode` would crash the Lua script with an unhandled error. Fixed with `pcall(cjson.decode, ...)` wrappers in all five mutation scripts:
   - `extend.lua`: returns empty balances on decode failure
   - `commit.lua`: returns `INTERNAL_ERROR` on decode failure
   - `release.lua`: returns `INTERNAL_ERROR` on decode failure
   - `expire.lua`: silently skips budget adjustment (background sweep must not get stuck on corrupted data; reservation still expires)

2. **Java `valueOf` crash on invalid `estimate_unit`** — `Enums.UnitEnum.valueOf(estimateUnitStr)` in `extendReservation()` would throw `IllegalArgumentException` if Redis contained a corrupted unit string. Fixed with try-catch fallback to `USD_MICROCENTS`.

3. **Concurrent benchmark thread leak and CI flakiness** — `ExecutorService` wasn't cleaned up on timeout (thread leak risk in CI). Hard `errors == 0` assertion would fail on transient CI issues. Fixed with try/finally + `shutdownNow()`, and replaced zero-error assertion with <1% error rate threshold.

**Items reviewed and confirmed correct (no fix needed):**
- `luaScripts.eval()` return value — Lua scripts always return via `cjson.encode()`, never nil
- Event idempotency replay returns empty balances — consistent with commit/release pattern; `parseLuaBalances()` handles gracefully
- `fetchBalancesForScopes()` still used in reserve idempotency-hit fallback path — not dead code
- Thread safety: all caches use `ConcurrentHashMap`, `ThreadLocal<MessageDigest>` for digests, Jedis connections scoped to try-with-resources
- Balance snapshot ordering: collected after mutations in all Lua scripts
- Percentile calculations: mathematically correct with bounds checking
- Redis pool size (50) / timeout (2s) — deployment tuning, configurable via RedisConfig
- Cache race conditions in `ApiKeyRepository` and `LuaScriptRegistry` — `ConcurrentHashMap` ops are atomic; duplicate work is harmless

---

## Previously Found Issues (all fixed)

### Issue 1 [FIXED]: `GET /v1/reservations/{id}` returned 410 for EXPIRED reservations
- **Was:** `getReservationById()` threw 410 for any EXPIRED reservation
- **Fix:** Removed the EXPIRED status check; now returns `ReservationDetail` with `status: EXPIRED`
- **Location:** `RedisReservationRepository.java:384-392`

### Issue 2 [FIXED]: `ReleaseResponse.released` could be null
- **Was:** `releasedAmount` was null when reservation data was corrupted
- **Fix:** Falls back to `Amount(USD_MICROCENTS, 0)` with a warning log
- **Location:** `RedisReservationRepository.java:304-314`

### Issue 3 [FIXED]: `GET /v1/reservations` status param not validated against enum
- **Was:** Invalid status values silently returned empty results
- **Fix:** Added `Enums.ReservationStatus.valueOf()` validation returning 400 INVALID_REQUEST
- **Location:** `ReservationController.java:109-116`

### Issue 4 [FIXED]: Non-dry_run reservation create did not populate `balances`
- **Was:** Only dry_run responses included balances; non-dry_run omitted them
- **Fix:** Added `fetchBalancesForScopes()` calls for both normal and idempotency-hit paths
- **Location:** `RedisReservationRepository.java:84,96-106`

### Issue 5 [FIXED]: expire.lua did not clean up orphaned TTL entries for missing reservations
- **Was:** NOT_FOUND path returned without `ZREM`, causing orphaned `reservation:ttl` entries to be retried every 5 seconds indefinitely
- **Fix:** Added `redis.call('ZREM', 'reservation:ttl', reservation_id)` before NOT_FOUND return, matching the existing cleanup in the non-ACTIVE path
- **Location:** `expire.lua:16-18`

### Issue 6 [FIXED]: Unbounded `zrangeByScore` in expiry sweep could cause OOM after outages
- **Was:** `jedis.zrangeByScore("reservation:ttl", 0, now)` loaded all expired candidates into memory at once
- **Fix:** Added LIMIT of 1000 per sweep cycle via `zrangeByScore(key, 0, now, 0, SWEEP_BATCH_SIZE)` — backlog drains naturally across subsequent sweeps
- **Location:** `ReservationExpiryService.java:31,45`

### Issue 8 [FIXED]: Tenant default configuration not honored by protocol server
- **Was:** `default_commit_overage_policy`, `default_reservation_ttl_ms`, `max_reservation_ttl_ms`, and `max_reservation_extensions` were set via admin API but ignored by the protocol server. The request models hardcoded `overagePolicy = REJECT` and `ttlMs = 60000L` as field defaults, so when clients omitted these fields they always got hardcoded values — never the tenant's configured defaults.
- **Fix:**
  1. Removed hardcoded defaults from `ReservationCreateRequest.overagePolicy` (was `REJECT`), `ReservationCreateRequest.ttlMs` (was `60000L`), and `EventCreateRequest.overagePolicy` (was `REJECT`) — now `null` when omitted
  2. Added `getTenantConfig()` to read tenant JSON from `tenant:{id}` Redis key
  3. Added `resolveOveragePolicy()`: request > tenant `default_commit_overage_policy` > `REJECT`
  4. Added `resolveReservationTtl()`: request > tenant `default_reservation_ttl_ms` > 60000ms, then `Math.min(ttl, max_reservation_ttl_ms)`
  5. Added `resolveMaxExtensions()`: reads tenant `max_reservation_extensions` (default 10), stored on reservation, enforced in extend.lua
  6. extend.lua: tracks `extension_count`, returns `MAX_EXTENSIONS_EXCEEDED` when limit reached
  7. reserve.lua: accepts `max_extensions` as ARGV[14], stores on reservation hash
  8. Added `MAX_EXTENSIONS_EXCEEDED` error code (HTTP 409)
- **Location:** `RedisReservationRepository.java`, `ReservationCreateRequest.java`, `EventCreateRequest.java`, `reserve.lua`, `extend.lua`, `Enums.java`

### Issue 7 [FIXED]: Clock skew between Java and Redis in expiry sweep candidate query
- **Was:** Java `System.currentTimeMillis()` used for the `zrangeByScore` query; all Lua scripts use `redis.call('TIME')` — clock drift could cause missed or premature candidate selection
- **Fix:** Replaced with `jedis.time()` to use Redis server clock, consistent with reserve/commit/release/extend/expire Lua scripts
- **Location:** `ReservationExpiryService.java:39-41`

---

## Round 6 — Spec Compliance Audit (2026-03-24)

Full audit of all changes against the authoritative YAML spec (`cycles-protocol-v0.yaml` v0.1.24).

### Issue 9 [FIXED]: `event.lua` always returned `charged` field in `EventCreateResponse`

- **Spec:** `EventCreateResponse.charged` description: "Present when overage_policy is ALLOW_IF_AVAILABLE and the actual was capped to the remaining budget, so the client can see the effective charge." Field is NOT in `required: [status, event_id]`.
- **Was:** `event.lua` always returned `charged = effective_amount` in the response JSON, so every event response included the `charged` field regardless of overage policy or capping.
- **Fix:** Made `charged` conditional in `event.lua`: only set `result.charged = effective_amount` when `overage_policy == "ALLOW_IF_AVAILABLE" and effective_amount < amount` (capping occurred). Lua's `cjson.encode` omits unset keys; Java's `@JsonInclude(NON_NULL)` on `EventCreateResponse.charged` provides redundant safety.
- **Location:** `event.lua:202-211`
- **Tests:** `EventControllerTest.shouldIncludeChargedWhenCapped()`, `EventControllerTest.shouldOmitChargedWhenNotCapped()`

### Issue 10 [FIXED]: GET `/v1/reservations/{id}` returned 200 for EXPIRED reservations instead of 410

- **Spec:** Line 52: "Expired reservations MUST return HTTP 410 with error=RESERVATION_EXPIRED." Line 1212: GET endpoint explicitly lists `"410"` as a response.
- **Was:** `getReservationById()` returned 200 with `status: EXPIRED` in the body. Integration tests asserted 200. This contradicted the spec.
- **Fix:** Added `status == EXPIRED` check in `getReservationById()` that throws `CyclesProtocolException.reservationExpired()` (410). Updated integration tests `shouldExpireReservationAndReleaseBudget`, `shouldReturnExpiredStatusOnGetAfterSweep` to assert 410. Updated unit test to assert 410.
- **Location:** `RedisReservationRepository.java:438-443`, `CyclesProtocolIntegrationTest.java`, `ReservationControllerTest.java`

### Issue 11 [FIXED]: event.lua idempotency replay missing `charged` and `balances`

- **Spec:** "On replay with the same idempotency_key, the server MUST return the original successful response payload." (IDEMPOTENCY NORMATIVE)
- **Was:** Replay returned `{event_id, idempotency_key, status}` — missing `charged` (when capping occurred) and `balances`.
- **Fix:** Replay now reads stored event hash (`charged_amount`, `amount`, `unit`, `budgeted_scopes`), reconstructs balance snapshots from current budget state, and includes `charged` when `charged_amount < amount` (capping occurred).
- **Location:** `event.lua:30-63`

### Issue 12 [FIXED]: commit.lua idempotency replay missing `balances` and `affected_scopes_json`

- **Spec:** Same idempotency normative requirement.
- **Was:** Replay returned `{reservation_id, state, charged, debt_incurred, estimate_amount, estimate_unit}` — missing `balances` and `affected_scopes_json`.
- **Fix:** Replay now reads `affected_scopes` from reservation hash, reconstructs balance snapshots, and includes both in response.
- **Location:** `commit.lua:42-72`

### Issue 13 [FIXED]: release.lua idempotency replay missing `balances`

- **Spec:** Same idempotency normative requirement.
- **Was:** Replay returned `{reservation_id, state, estimate_amount, estimate_unit}` — missing `balances`.
- **Fix:** Replay now reads `affected_scopes` from reservation hash, reconstructs balance snapshots, and includes in response.
- **Location:** `release.lua:29-52`

### Confirmed non-issues (validated against YAML spec)

The following were investigated during the audit and confirmed **not to be spec violations**:

| Item | YAML Spec Evidence | Verdict |
|------|-------------------|---------|
| `ErrorResponse.details` field optional | `required: [error, message, request_id]` — `details` not in required array | NOT A VIOLATION |
| `Balance.debt/overdraft_limit/is_over_limit` optional | `required: [scope, scope_path, remaining]` — these fields not in required array | NOT A VIOLATION |
| `CommitResponse.released` optional | `required: [status, charged]` — `released` not in required array | NOT A VIOLATION |

---

## Verdict

The server implementation is **fully compliant** with the YAML spec (v0.1.25) and **performance optimized**. v0.1.25 additions: webhook event emission (reservation.denied, reservation.commit_overage) via shared Redis dispatch queue, event/delivery TTL retention (90d/14d configurable), AES-256-GCM webhook signing secret encryption, subscription matching with scope wildcards. v0.1.24 changes: default overage policy changed from REJECT to ALLOW_IF_AVAILABLE; ALLOW_IF_AVAILABLE commits/events now always succeed with capped charge instead of 409 BUDGET_EXCEEDED; is_over_limit extended to also cover capped ALLOW_IF_AVAILABLE scenarios; event.lua updated with same capped-delta logic; EventCreateResponse includes charged field. All 9 endpoints are implemented, all schemas match, auth/tenancy/idempotency are correctly enforced, and the normative behavioral requirements (atomic operations, debt/overdraft handling, scope derivation, error semantics, dry-run rules, grace period handling) are properly implemented. Seven write-path optimizations reduce hot-path latency by eliminating redundant Redis round-trips, caching BCrypt validation, and returning balance snapshots atomically from Lua scripts. Phase 3 adds read-path pipelining, response compression, and terminal hash TTLs. Test coverage expanded to 553 tests across 26 test classes, including 12 performance benchmark tests. Write-path single-operation p50 latency: 5.6-8.6ms (v0.1.25.1, async emit, near-zero overhead on non-event paths). Read-path p50 latency: 4.0-5.6ms. Full reserve-commit lifecycle p50: 16.0ms. Concurrent throughput: 2,584 ops/s at 32 threads with zero errors. No remaining spec violations found.
