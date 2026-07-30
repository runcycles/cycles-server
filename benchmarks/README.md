# Performance regression detection

Machine-readable benchmark history and release-gate baseline. Companion to
[`../BENCHMARKS.md`](../BENCHMARKS.md) (human-curated prose analysis).

## Where the data lives

**Data files are on the dedicated `benchmark-data` branch, not on `main`.**

| File (on `benchmark-data`) | Purpose | Writers |
|---|---|---|
| `benchmarks/history.jsonl` | Append-only JSONL, one entry per nightly + release run | `.github/workflows/nightly-benchmark.yml` |
| `benchmarks/baseline.json` | Reference numbers for the release gate | `.github/workflows/release.yml` (on successful release) |

### Why a separate branch

`main` is protected — the `github-actions[bot]` identity can't push there.
Rather than configure branch-protection bypass or manage a PAT, the
nightly and release workflows write their telemetry to a dedicated
non-protected branch. The separation also cleanly distinguishes
"service code" (on `main`) from "CI telemetry data" (on
`benchmark-data`).

### How to read the data locally

```bash
# Pull the data branch without switching away from your working branch
git fetch origin benchmark-data:benchmark-data

# Show the file contents
git show benchmark-data:benchmarks/history.jsonl

# Or check it out into a worktree
git worktree add /tmp/bench-data benchmark-data
cat /tmp/bench-data/benchmarks/history.jsonl
```

Via the GitHub Raw API (no git required):

```
https://raw.githubusercontent.com/runcycles/cycles-server/benchmark-data/benchmarks/history.jsonl
https://raw.githubusercontent.com/runcycles/cycles-server/benchmark-data/benchmarks/baseline.json
```

## Metrics tracked

The Java suite measures reserve fan-out at 1, 10, 50, and 200 clients for both
shared and isolated ledger shapes. Nine established regression signals are
supplemented by eight machine-history fields from the 200-client saturation
level. Lower-concurrency reference medians and ranges are curated in
[`../BENCHMARKS.md`](../BENCHMARKS.md); the history pipeline keeps the widest
level to avoid a breaking expansion of existing baseline records. History is
a consistent full-suite regression signal and can inherit warm state from
earlier tests; it is not interchangeable with the independently launched
fresh-process reference cells. The Java benchmark itself fails above a 1%
request error rate or on any Redis ledger mismatch; p99 remains informational
in the cross-run regression gate because shared-runner tails are noisy.

| Metric | Source test | Why |
|---|---|---|
| `reserve_p50_ms`, `reserve_p99_ms` | `CyclesProtocolBenchmarkTest` | Primary write path |
| `commit_p50_ms`, `commit_p99_ms` | `CyclesProtocolBenchmarkTest` | Second write path |
| `release_p50_ms` | `CyclesProtocolBenchmarkTest` | Cleanup path |
| `event_p50_ms` | `CyclesProtocolBenchmarkTest` | Direct-debit path |
| `list_sorted_1k_p50_ms` | `CyclesProtocolReadBenchmarkTest` | Sorted-list scaling at moderate population |
| `list_sorted_10k_p50_ms` | `CyclesProtocolReadBenchmarkTest` | Sorted-list scaling trigger / indexed-path payoff |
| `concurrent_throughput_32t` | `CyclesProtocolConcurrentBenchmarkTest` | Scaling signal |
| `reserve_shared_200_p99_ms` | `CyclesProtocolConcurrentBenchmarkTest` | Reserve tail under contention on one shared budget |
| `reserve_shared_200_throughput` | `CyclesProtocolConcurrentBenchmarkTest` | Shared-budget reserve capacity |
| `reserve_shared_200_error_rate_pct` | `CyclesProtocolConcurrentBenchmarkTest` | Availability under shared-budget fan-out |
| `reserve_shared_200_ledger_mismatches` | `CyclesProtocolConcurrentBenchmarkTest` | Atomic shared-ledger correctness |
| `reserve_isolated_200_p99_ms` | `CyclesProtocolConcurrentBenchmarkTest` | Reserve tail across independent leaf budgets |
| `reserve_isolated_200_throughput` | `CyclesProtocolConcurrentBenchmarkTest` | Sharded reserve capacity |
| `reserve_isolated_200_error_rate_pct` | `CyclesProtocolConcurrentBenchmarkTest` | Availability under sharded fan-out |
| `reserve_isolated_200_ledger_mismatches` | `CyclesProtocolConcurrentBenchmarkTest` | Per-leaf ledger correctness |

## Entry format (`history.jsonl`)

Each line is a standalone JSON object:

```json
{"timestamp":"2026-07-30T07:00:00Z","commit":"abc1234","tag":null,"reserve_p50_ms":5.3,"reserve_p99_ms":18.2,"commit_p50_ms":4.6,"commit_p99_ms":15.1,"release_p50_ms":4.8,"event_p50_ms":4.3,"list_sorted_1k_p50_ms":22.5,"list_sorted_10k_p50_ms":164.9,"concurrent_throughput_32t":2632,"reserve_shared_200_p99_ms":210.4,"reserve_shared_200_throughput":920.2,"reserve_shared_200_error_rate_pct":0.0,"reserve_shared_200_ledger_mismatches":0,"reserve_isolated_200_p99_ms":180.7,"reserve_isolated_200_throughput":1040.6,"reserve_isolated_200_error_rate_pct":0.0,"reserve_isolated_200_ledger_mismatches":0}
```

- `timestamp` — UTC, ISO 8601
- `commit` — short SHA of the benchmarked code
- `tag` — release tag if the run happened as part of a release (non-null);
  `null` for nightly runs on main

The example fan-out values illustrate the record shape; they are not a
published measurement. Each fan-out result measures reserve HTTP latency for
five sustained seconds after a controlled warmup of at least 50 requests that
reaches every logical client, with warmup concurrency capped at 50. The ledger
is reset before timing starts. `shared` sends every client through one tenant
budget; `isolated` removes that parent budget and assigns each client its own
agent-level leaf budget. Use `-Dbenchmark.fanout.clients=<level>` plus one
fan-out test method to reproduce an individual fresh-process cell.

## Baseline format (`baseline.json`)

Same fields as a `history.jsonl` entry. Rewritten atomically by the
release workflow when a release passes the gate. Empty/missing on
first release — the gate bootstraps by accepting the first release's
numbers as the initial baseline.

## Thresholds

- **Nightly trend flag** (no gating, just visibility): any headline metric
  moves `> 30%` from the rolling-7-run median → workflow annotates the
  commit and posts a summary comment.
- **Release gate** (blocks Docker publish): any headline metric moves
  `> 25%` from `baseline.json` → release workflow fails before Docker
  push. Override by including `[benchmark-skip]` in the annotated tag
  message for test-only releases (precedent: v0.1.25.9, v0.1.25.11).

## Noise handling

GH-hosted runners have ~±10-20% variance on sub-10ms latency metrics.
Mitigations (stacked):

1. **3-trial median** per run. A single pathological trial doesn't
   swing the reported number.
2. **Rolling 7-run baseline** for nightly trend. Real regressions show
   up as a sustained step; one-night blips don't trigger.
3. **Generous thresholds** (25% / 30%). Tight enough to catch 2×
   regressions; loose enough to ignore runner noise.

If false positives appear > 1× per month, threshold tuning is
warranted. Historical data for noise characterisation lives in
[`../BENCHMARKS.md`](../BENCHMARKS.md).
