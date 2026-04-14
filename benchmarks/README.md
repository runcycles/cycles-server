# Performance regression detection

Machine-readable benchmark history and release-gate baseline. Companion to
[`../BENCHMARKS.md`](../BENCHMARKS.md) (human-curated prose analysis).

## Files

| File | Purpose | Writers |
|---|---|---|
| `history.jsonl` | Append-only JSONL, one entry per nightly run | `.github/workflows/nightly-benchmark.yml` |
| `baseline.json` | Reference numbers for the release gate | `.github/workflows/release.yml` (on successful release) |

## Headline metrics tracked

Seven numbers chosen for signal density, not exhaustiveness. More metrics
means more noise and a higher false-positive rate on the gate.

| Metric | Source test | Why |
|---|---|---|
| `reserve_p50_ms`, `reserve_p99_ms` | `CyclesProtocolBenchmarkTest` | Primary write path |
| `commit_p50_ms`, `commit_p99_ms` | `CyclesProtocolBenchmarkTest` | Second write path |
| `release_p50_ms` | `CyclesProtocolBenchmarkTest` | Cleanup path |
| `event_p50_ms` | `CyclesProtocolBenchmarkTest` | Direct-debit path |
| `concurrent_throughput_32t` | `CyclesProtocolConcurrentBenchmarkTest` | Scaling signal |

## Entry format (`history.jsonl`)

Each line is a standalone JSON object:

```json
{"timestamp":"2026-04-15T07:00:00Z","commit":"abc1234","tag":null,"reserve_p50_ms":5.3,"reserve_p99_ms":18.2,"commit_p50_ms":4.6,"commit_p99_ms":15.1,"release_p50_ms":4.8,"event_p50_ms":4.3,"concurrent_throughput_32t":2632}
```

- `timestamp` — UTC, ISO 8601
- `commit` — short SHA of the benchmarked commit
- `tag` — release tag if the run happened as part of a release (non-null);
  `null` for nightly runs on main

## Baseline format (`baseline.json`)

Same fields as a `history.jsonl` entry. Rewritten atomically by the
release workflow when a release passes the gate. Empty/missing on
first run — the gate bootstraps by accepting the first benchmark run
as the initial baseline.

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
