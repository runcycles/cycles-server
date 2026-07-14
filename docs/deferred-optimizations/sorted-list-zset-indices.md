# Tracked: completeness-safe index for sorted list-reservations

**Status:** Phase A released in v0.1.25.53; Phase B implemented in v0.1.25.54 under [cycles-server#240](https://github.com/runcycles/cycles-server/issues/240).
**Context:** first analysed 2026-04-16 for v0.1.25.12; revised 2026-07-14 after the failed candidate-index experiment in #235.
**Owner:** runtime maintainers.

## Problem

Supplying `sort_by` or `sort_dir` to `GET /v1/reservations` selects a path that:

1. scans every `reservation:res_*` key across all tenants;
2. pipelines the reservation projection for every key;
3. retains every matching tenant row in application heap;
4. sorts all matches; and
5. slices the requested page.

Every page repeats that work. The path is therefore O(global reservations) in
Redis reads and O(matching tenant reservations) in heap, even for the default
`created_at_ms desc&limit=20` query.

The v0.1.25.52 pre-index benchmark fixture contains an even 50/50 split between
the authenticated tenant and an unrelated tenant. Three-run local medians were:

| Population | Target-tenant rows | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|
| 1,000 | 500 | 22.5 ms | 40.9 ms | 48.3 ms |
| 10,000 | 5,000 | 164.9 ms | 210.2 ms | 232.0 ms |

These numbers are environment-dependent, but they establish the linear scaling
shape and a frozen comparison point for #240.

## Why the previous index shape was removed

PR #235 briefly added a per-tenant candidate ZSET and a permanent `:ready`
marker. It was removed before merge because completeness could silently fail:

- an evicted/deleted ZSET could survive behind its marker;
- an old pod in a rolling deployment could create an unindexed reservation
  after a new pod published readiness;
- a partial backfill could swallow a row error and still publish readiness;
- stale members had no bounded cleanup when tenants did not issue sorted reads;
- indexed reads still hydrated the entire ZSET before slicing.

An empty-index fallback cannot detect a partially missing index. The replacement
must prove completeness or use the existing full-SCAN path.

## Phased rollout

### Phase A — ship the measurement first

- Add `LIST sorted @1k` and `LIST sorted @10k` to
  `CyclesProtocolReadBenchmarkTest`.
- Seed hashes directly through a Redis pipeline so fixture creation is outside
  the measured request.
- Track both p50 metrics in nightly/release benchmark data.
- Release this benchmark-only change before the runtime index so the next
  release has a real pre-change baseline.

### Phase B — optimize only the default timestamp sort

Create one ZSET per tenant:

```
reservation:idx:<tenant>:created_at_ms
```

The member is `reservation_id`; the score is `created_at_ms`. Timestamps at the
current epoch are exactly representable by Redis's IEEE-754 score. The other six
protocol sort keys remain on the current full-SCAN path until measurements
justify their write and memory cost.

`reserve.lua` adds the member atomically with the reservation hash. No lifecycle
script changes the score. Terminal rows remain indexed while their retained hash
is listable; cleanup removes members only after the hash disappears.

The read path:

- checks index-completeness metadata before using the ZSET;
- reads candidates in bounded batches;
- pipelines only those candidates' HMGET projections;
- applies the existing tenant/status/idempotency/scope/time filters; and
- continues until it fills the page or exhausts the index.

It must preserve the current total order: timestamp in the requested direction,
then `reservation_id` ascending in **both** directions. Redis reverses equal-score
members under `ZREVRANGE`, so descending pagination must explicitly handle a
complete equal-score group; a simple `ZREVRANGE` slice is not equivalent.

The existing opaque sorted cursor already carries the last sort value and
reservation ID, so its wire shape does not change. Cursor sort and filter
binding remains unchanged.

## Completeness protocol

Indexed reads are an optimization, never the authority. A tenant uses the index
only when all of these hold:

1. new writers have been deployed across the fleet;
2. a fully successful, restartable backfill has completed;
3. metadata says the index is ready and records its expected member count; and
4. `ZCARD` equals that expected count.

The metadata and readiness publication must follow these rules:

- Missing metadata, wrong Redis type, count mismatch, interrupted backfill, or
  any unrepresentable historical row selects the full-SCAN fallback.
- Any per-row scan/HMGET/parse/ZADD failure prevents readiness.
- Readiness and the final expected count are published atomically.
- A concurrent new reservation is serialized with readiness publication: before
  readiness its ZADD is included in final `ZCARD`; after readiness its atomic
  create increments the expected count only when `ZADD` adds a new member.
- Operators enable readiness only after mixed-version writers are gone. An old
  writer cannot participate in the completeness counter, so automatic cutover
  during a rolling deployment is forbidden.
- Index/meta eviction or deletion must be detectable. No permanent marker may
  vouch for state stored in an independently losable key without count
  validation.

A scheduled sweep removes members whose reservation hashes no longer exist and
updates the expected count atomically. Indexed reads also skip missing hashes and
may enqueue lazy cleanup, but correctness must not depend on a tenant eventually
reading every stale member.

## Phase B validation

- Complete pagination above 2,000 and at 10,000 rows.
- Equal timestamps in ASC and DESC with the existing ID-ascending tiebreak.
- Selective filters spanning multiple candidate batches.
- Interrupted/partially failed backfill never publishes readiness.
- Missing index, metadata, or individual members causes fallback without
  omissions.
- Concurrent reserve at the backfill/readiness boundary is present exactly once.
- Mixed-version writers cannot enable indexed reads prematurely.
- Terminal rows remain visible throughout hash retention; stale members are
  eventually removed.
- Cursor/filter/sort mismatch behavior remains protocol-compatible.
- All non-default sort keys retain the existing results and cursor semantics.

The v0.1.25.54 suite covers these conditions with real Redis, including a full
10,000-row / 100-page traversal, a 300-member equal-timestamp group in both
directions, a forged cursor naming a missing tie-group member, selective
filters across candidate batches, concurrent readiness-boundary writers,
wrong Redis types, partial backfill failures, drift fallback, and stale cleanup.

## Expected cost and benefit

One `ZADD` on reserve is approximately O(log tenant reservations), rather than
the seven writes in the original design. Commit, release, extend, expire, event,
and decide do not update the index. Expected reserve latency impact is around
1–2%, subject to the release benchmark gate.

One ZSET entry is roughly 80 bytes. At 100,000 retained reservations this is
about 8 MB, versus roughly 56 MB for the discarded seven-index design.

The target read complexity is O(log N + candidates examined) and bounded heap
per batch. At 10,000 rows, the indexed default query should materially beat the
164.9 ms pre-change p50; the exact acceptance number comes from the same-host
Phase A release baseline.

## Rollback

The indexed path is feature-flagged. Rollback selects the current full-SCAN path
without changing the wire cursor contract, then deletes `reservation:idx:*`
keys after no readers use them. Reservation hashes remain authoritative.

## What this does not change

- No cycles-protocol spec or response-format change.
- Cursors remain opaque to clients.
- The unsorted legacy SCAN path is unchanged.
- `reservation_id`, `tenant`, `scope_path`, `status`, `reserved`, and
  `expires_at_ms` sorts continue to use the complete in-memory path.
- No per-filter ZSETs or transient `ZINTERSTORE` keys.

## Decision log

- **2026-04-16 (v0.1.25.12):** shipped load-all/sort-in-memory and deferred
  indexing until scale justified it.
- **2026-07-14 (#240):** measured the 10k path, rejected the old seven-index
  readiness model, and chose a benchmark-first, one-index rollout with a strict
  correctness fallback.
- **2026-07-14 (v0.1.25.54):** implemented the one-index design. An initial
  correct score-at-a-time iterator measured 59.2ms p50 at 1k because client
  round trips dominated; the final bounded Lua iterator preserves equal-score
  ID ordering in one server-side call per candidate batch and produced
  10.7ms/11.1ms p50 three-trial medians at 1k/10k.
