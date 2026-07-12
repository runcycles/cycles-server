# Deferred: per-tenant ZSET indices for sorted list-reservations

**Status:** deferred — not currently scheduled. Triggers below.
**Context:** analysed 2026-04-16 during v0.1.25.12 release (introduced `sort_by` + `sort_dir` on `GET /v1/reservations` per cycles-protocol spec revision 2026-04-16).
**Owner:** unassigned until a triggering condition lands.

## The problem this would solve

v0.1.25.12 ships a **sorted list path** on `GET /v1/reservations`. The current implementation does a full Redis `SCAN` of `reservation:res_*`, filters in-stream, sorts in memory, and slices by cursor. This is **O(N) in total reservations** (not per-tenant) because `SCAN` walks the whole keyspace. Every sorted-list request repeats this work.

At runtime-plane scale (~10³ reservations per tenant, ~10⁴ total across tenants), full-SCAN is fine — ~4ms on the benchmark, well inside any practical SLO. Above that, latency degrades roughly linearly with total keyspace size.

```
  sorted-list p50 vs. total reservations
  --------------------------------------
       100   —    ~4ms       (baseline)
     1,000   —   ~10ms
    10,000   —  ~80–100ms
   100,000   —  ~800ms+      (Redis GC pressure dominates)
```

The ZSET-indexed approach replaces the full SCAN with `ZRANGEBYSCORE` (or `ZRANGEBYLEX` for string keys), which is **O(log N + page_size)** regardless of total keyspace size.

## Trigger — when to schedule this

Don't do this speculatively. Schedule it when **any** of the following is true:

1. A real tenant crosses ~10 k active reservations. Track via `http_server_requests_seconds{uri="/v1/reservations"}` p99 broken down by `tenant` tag.
2. Sorted-list p99 on a production deployment crosses ~50 ms sustained over any 1-hour window.
3. A new customer ships with an advertised SLO on list-endpoint latency tighter than 100 ms p99.

None of these conditions hold as of v0.1.25.12. The `OPERATIONS.md` "Reservation list sorting" section already names the metric to watch.

## Design

### New Redis key shape — 7 sorted sets per tenant

One ZSET per (`tenant`, sort key) combination:

```
reservation:idx:<tenant>:reservation_id   (ZSET, lex, score=0)
reservation:idx:<tenant>:tenant           (ZSET, lex, score=0)
reservation:idx:<tenant>:scope_path       (ZSET, lex, score=0)
reservation:idx:<tenant>:status           (ZSET, score=status_ordinal, lex tiebreak)
reservation:idx:<tenant>:reserved         (ZSET, numeric score = amount)
reservation:idx:<tenant>:created_at_ms    (ZSET, numeric score = epoch ms)
reservation:idx:<tenant>:expires_at_ms    (ZSET, numeric score = epoch ms)
```

- **Member** is always `reservation_id` (canonical cursor anchor).
- **Score** is numeric for numeric keys, ordinal for `status` (so `status=ACTIVE` all sort together), and `0` for pure lex keys (`ZRANGEBYLEX` handles ordering without touching the score).
- Per-tenant partitioning keeps each index small — even a tenant with 10 k reservations yields a 10 k-entry ZSET, which Redis handles in microseconds.

### Lua script changes (5 of 6 scripts touched)

All index updates go **inside** the existing Lua `EVAL` so the hash and index can't drift under partial failure.

| Script | Change | Why |
|---|---|---|
| `reserve.lua` | `ZADD` into all 7 indices on successful creation | New reservation becomes visible to sorted list |
| `commit.lua` | `ZADD` into status index with new ordinal | ACTIVE → COMMITTED |
| `release.lua` | Same as commit | ACTIVE → RELEASED |
| `extend.lua` | `ZADD` into `expires_at_ms` index (overwrite) | Score changes with extension |
| `expire.lua` | `ZADD` into status index (ACTIVE → EXPIRED) | Background sweep must update |
| `event.lua` | no change | Events don't mutate reservations |

`ZADD` inside Lua is atomic with the existing `HSET` — if the Lua aborts, neither lands.

### Repository changes — `RedisReservationRepository.listReservationsSorted`

- Replace full `SCAN` with `ZRANGEBYSCORE` (numeric) or `ZRANGEBYLEX` (string).
- `ZREVRANGEBYSCORE` for `sort_dir=desc`.
- **Filtered + sorted** is the hard bit — three options, pick one:
  1. **Per-filter ZSETs** — `reservation:idx:<tenant>:<sort_key>:<filter_key>=<filter_value>`. Combinatorial explosion; rejected.
  2. **Hybrid batch-and-filter** (recommended): `ZRANGEBYSCORE` a wide batch (~2× `limit`), `HGETALL`-pipeline the members, post-filter, continue if the page is short. Adapts the existing cursor logic; simplest to ship.
  3. **`ZINTERSTORE` transient sets** — expensive, adds Redis write load on every read. Rejected.
- Cursor format evolves to store `(score, member)` instead of `(last_sort_value, last_reservation_id)`. The on-wire cursor is opaque, so this is an implementation detail clients don't see — no protocol bump.

### Backfill migration

One-time job to populate all 7 ZSETs from existing `reservation:res_*` hashes:
- Standalone `MigrationService` triggered by an admin endpoint, or on-startup idempotent check (count-compare index ZCARD vs. tenant reservation count; populate if drift).
- **Read-path fallback during rollout:** if `ZRANGEBYSCORE` returns empty but the tenant has reservations (`EXISTS reservation:res_*`), fall back to the current SCAN path. Removes once migration completes.

## Performance impact

### Write path — regression (new `ZADD`s inside Lua)

| Operation | Indices touched | Per-op cost (N=10k per tenant) | Expected latency delta |
|---|---|---|---|
| **Reserve** | **7** — all sort indices | 7 × O(log N) ≈ ~100 μs in Lua | **+3–6% p50** (5.3 ms → ~5.5–5.6 ms) |
| **Commit** | 1 (status) | ~14 μs | **+1–2% p50** |
| **Release** | 1 (status) | ~14 μs | **+1–2% p50** |
| **Extend** | 1 (expires_at_ms, overwrite) | ~14 μs | **+1% p50** |
| **Expire** (background) | 1 (status) | ~14 μs | off hot path |
| **Decide** | 0 — no state change | — | unchanged |
| **Event** | 0 — no reservation mutation | — | unchanged |

**Concurrent throughput at 32 threads:** probably **−2 to −4%** (reserve-dominated mix). Benchmark reference: 2,632 ops/s at v0.1.25.7 → estimated 2,520–2,580 ops/s.

All costs are in-memory, single-threaded inside Redis, no new network round-trips.

### Read path — dramatic improvement

| Scenario | Current full-SCAN | With ZSET | Delta |
|---|---|---|---|
| Sorted list, **100 reservations** | ~4 ms | ~3 ms | ~25% faster |
| Sorted list, **1 k reservations** | ~10 ms | ~2 ms | **5× faster** |
| Sorted list, **10 k reservations** | ~80–100 ms | ~2–3 ms | **30–50× faster** |
| Sorted list, **100 k reservations** | ~800 ms+ | ~3–5 ms | **100×+ faster** |
| Unsorted list (legacy SCAN path) | ~4 ms | unchanged | no change |
| `GET /v1/reservations/{id}` | ~3 ms | unchanged | no change |
| `GET /v1/balances` | ~4 ms | unchanged | no change |

Inflection point is ~1 k reservations per tenant. Below that, SCAN is already fine.

### Memory overhead

- **~560 bytes/reservation** in index overhead (7 ZSET entries × ~80 bytes each).
- At 100 k reservations: ~56 MB total.
- For deployments with `maxmemory 256mb` (smaller prod setups), that's ~22% of the Redis budget — **worth flagging in ops docs**. For typical multi-GB Redis deployments, rounding error.

## Benchmark suite impact

### What would change in the existing benchmark (current suite at v0.1.25.7)

| Benchmark | Current | Expected with ZSET | Release-gate risk |
|---|---|---|---|
| Reserve p50 | 5.3 ms | **5.5–5.6 ms (+4–6%)** | Safe — 25% gate has headroom, but chips into it |
| Reserve p99 | ~13 ms | ~13.5–14 ms (+4–8%) | Safe |
| Commit p50 | 4.6 ms | ~4.65 ms (+1%) | Within noise |
| Release p50 | 4.8 ms | ~4.85 ms (+1%) | Within noise |
| Extend p50 | 7.5 ms | ~7.6 ms (+1%) | Within noise |
| LIST reservations (unsorted) | 4.5 ms | unchanged | no change |
| Concurrent @ 32 threads | 2,632 ops/s | ~2,520–2,580 (−2 to −4%) | Safe |

Reserve +4–6% consumes **about a fifth of the gate's 25% safety margin** in a single change. Future write-path work that stacks on this (new event emission, another filter dimension) could compound.

### What would need to be ADDED to the suite

The current `CyclesProtocolReadBenchmarkTest` has no sorted-list case — the endpoint was added in v0.1.25.12, the suite was last run at v0.1.25.7. To make the optimization's win visible:

- **New benchmarks:** `LIST reservations sorted@1k`, `LIST reservations sorted@10k`.
- Seed fixtures of 1 k / 10 k reservations inside the Testcontainers Redis (slower test setup: ~30 s seed time).
- Run against both the current full-SCAN path (baseline) and the new ZSET path (comparison).

**Without new benchmarks, the optimization looks like a pure regression** in the write path with no visible upside. The new benchmarks are not optional.

### Release-process guidance (when this work is scheduled)

1. **Freeze a baseline run at the version immediately preceding** the ZSET change so the regression is measured, not inferred. Do this before any of the ZSET code lands.
2. **Do NOT use `[benchmark-skip]`** on the release notes. This is a hot-path change; benchmarks must run.
3. **Re-baseline deliberately.** Mirror the v0.1.25.6 precedent ("zero overhead on success path" documented): write a BENCHMARKS.md section that explicitly documents the +5% reserve regression as an accepted trade-off for the read-path gain. Then manually update `benchmarks/baseline.json` on the merge commit.
4. **Ship the sorted-list benchmarks in the same release** so the delta tells the full story.

## Rollback story

Pure Redis-only change, fully reversible:
1. Delete all `reservation:idx:*` keys via `SCAN + DEL`.
2. Flip the repository feature flag (add one) back to the full-SCAN path.
3. Redeploy. No wire format changed; clients see no difference.

## Effort estimate

| Workstream | Days |
|---|---|
| Lua changes (5 scripts × ZADD + tests) | ~1 |
| Repository (ZRANGE-based paging + filtered-sort hybrid) | ~2 |
| Migration + fallback | ~1 |
| Tests + new benchmarks | ~2 |
| **Total** | **~1 week** |

Tractable for a single engineer.

## What this does NOT change

- Wire format — cursors stay opaque; clients don't see the internals.
- `cycles-protocol-v0.yaml` spec — no spec change required.
- Admin spec / governance surface — pure runtime concern.
- New sort keys — 7 covers the full spec enum; no scope creep.
- Non-sorted list path — byte-for-byte unchanged.

## Decision log

- **2026-04-16 (v0.1.25.12):** chose hybrid "load-all + sort-in-memory" for the initial sorted-list implementation; deferred ZSETs until a scale trigger. Rationale in `AUDIT.md` entry for v0.1.25.12. This document captures the deferred design for when a trigger fires.
