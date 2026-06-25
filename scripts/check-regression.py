#!/usr/bin/env python3
"""
Compare a current benchmark record against a baseline and exit non-zero
on regression.

Usage:
    # Release gate (25% threshold against rolling history, baseline fallback):
    python scripts/check-regression.py release \\
        --current benchmarks/current.json \\
        --baseline benchmarks/baseline.json \\
        --history benchmarks/history.jsonl \\
        --window 7 \\
        --threshold 0.25

    # Nightly trend (30% threshold against rolling-7 median of history.jsonl):
    python scripts/check-regression.py trend \\
        --current benchmarks/current.json \\
        --history benchmarks/history.jsonl \\
        --window 7 \\
        --threshold 0.30

Emits a markdown summary to stdout (captured into workflow annotations).
Exits 0 on no-regression, 1 on regression. In release mode, history with at
least two prior records is preferred so a single unusually fast release does
not become the only comparison point. Missing history falls back to
baseline.json; a missing or empty baseline bootstraps and still exits 0.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from typing import Dict, List, Optional


# Headline metrics. 2nd element = direction (True = lower-is-better/latency,
# False = higher-is-better/throughput). 3rd element = GATING: whether a breach
# fails the build. p99 tail latency on a 200-iteration micro-benchmark over
# shared CI runners swings ~2x run-to-run (GC pauses / runner contention), far
# beyond any sane threshold, so p99 is tracked + reported but NON-GATING; the
# stable signals — p50 latency and concurrent throughput — are the hard gates.
HEADLINE_METRICS = [
    ("reserve_p50_ms",            True,  True),
    ("reserve_p99_ms",            True,  False),
    ("commit_p50_ms",             True,  True),
    ("commit_p99_ms",             True,  False),
    ("release_p50_ms",            True,  True),
    ("event_p50_ms",              True,  True),
    ("concurrent_throughput_32t", False, True),
]


def load_json(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def load_history(path: str) -> List[dict]:
    entries = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                entries.append(json.loads(line))
    except FileNotFoundError:
        return []
    return entries


def rolling_median(history: List[dict], window: int) -> Dict[str, float]:
    """Take the last `window` history entries, median each metric."""
    recent = history[-window:] if window > 0 else history
    result: Dict[str, float] = {}
    for metric, *_ in HEADLINE_METRICS:
        values = [
            e[metric] for e in recent
            if metric in e and isinstance(e[metric], (int, float))
        ]
        if values:
            result[metric] = statistics.median(values)
    return result


def pct_change(current: float, baseline: float, lower_is_better: bool) -> float:
    """Positive = worse; negative = better. Sign flips by direction."""
    if baseline == 0:
        return 0.0
    if lower_is_better:
        return (current - baseline) / baseline
    return (baseline - current) / baseline


def compare(
    current: dict,
    baseline: Dict[str, float],
    threshold: float,
) -> List[dict]:
    """Return a list of {metric, current, baseline, change, regressed}."""
    results = []
    for metric, lower_is_better, gating in HEADLINE_METRICS:
        c = current.get(metric)
        b = baseline.get(metric)
        if c is None or b is None:
            results.append({
                "metric": metric,
                "current": c,
                "baseline": b,
                "change_pct": None,
                "regressed": False,
                "breached": False,
                "gating": gating,
                "note": "missing",
            })
            continue
        change = pct_change(c, b, lower_is_better)
        breached = change > threshold
        results.append({
            "metric": metric,
            "current": c,
            "baseline": b,
            "change_pct": round(change * 100, 1),
            # Only a GATING metric's breach fails the build; a non-gating
            # (p99) breach is reported but does not regress the gate.
            "regressed": breached and gating,
            "breached": breached,
            "gating": gating,
            "note": None,
        })
    return results


def format_summary(
    mode: str,
    results: List[dict],
    threshold: float,
    any_regressed: bool,
    bootstrap: bool = False,
) -> str:
    lines = [
        f"## Performance {mode} check",
        "",
        f"- Threshold: **{threshold * 100:.0f}%**",
    ]
    if bootstrap:
        lines.append(
            "- Bootstrap run: no prior baseline; current numbers become the new baseline."
        )
    lines.append("")
    # ASCII-only so this renders on Windows local + Linux CI without
    # encoding-codec workarounds.
    lines.append("| Metric | Baseline | Current | Change | Status |")
    lines.append("|---|---|---|---|---|")
    for r in results:
        b = "-" if r["baseline"] is None else f"{r['baseline']:.2f}"
        c = "-" if r["current"] is None else f"{r['current']:.2f}"
        if r["change_pct"] is None:
            delta = "-"
            status = "MISSING"
        else:
            sign = "+" if r["change_pct"] >= 0 else ""
            delta = f"{sign}{r['change_pct']}%"
            if r["regressed"]:
                status = "REGRESSED"
            elif r.get("breached"):
                # exceeded threshold but the metric is non-gating (p99 noise)
                status = "noisy (non-gating)"
            else:
                status = "OK"
        lines.append(f"| `{r['metric']}` | {b} | {c} | {delta} | {status} |")
    lines.append("")
    lines.append(
        "_p99 latency is non-gating (informational): tail latency on a "
        "micro-benchmark over shared CI runners is too noisy to gate; p50 "
        "and throughput are the gating signals._"
    )
    lines.append("")
    lines.append(
        f"**Overall: {'REGRESSION DETECTED' if any_regressed else 'OK'}**"
    )
    return "\n".join(lines)


def run_release(args) -> int:
    current = load_json(args.current)

    if args.history:
        history = load_history(args.history)
        if len(history) >= 2:
            baseline = rolling_median(history, args.window)
            results = compare(current, baseline, args.threshold)
            any_regressed = any(r["regressed"] for r in results)
            print(format_summary(
                f"release-gate (rolling-{args.window} median)",
                results, args.threshold, any_regressed,
            ))
            return 1 if any_regressed else 0

    try:
        baseline_raw = load_json(args.baseline)
    except FileNotFoundError:
        baseline_raw = {}

    # Bootstrap: empty baseline means this is the first-ever release under
    # the gate. Accept and let the caller overwrite baseline.json with
    # `current`.
    if not baseline_raw or not any(
        m in baseline_raw for m, *_ in HEADLINE_METRICS
    ):
        results = [
            {
                "metric": m,
                "current": current.get(m),
                "baseline": None,
                "change_pct": None,
                "regressed": False,
                "note": "bootstrap",
            }
            for m, *_ in HEADLINE_METRICS
        ]
        print(format_summary(
            "release-gate", results, args.threshold,
            any_regressed=False, bootstrap=True,
        ))
        return 0

    baseline = {
        m: baseline_raw[m]
        for m, *_ in HEADLINE_METRICS
        if m in baseline_raw
    }
    results = compare(current, baseline, args.threshold)
    any_regressed = any(r["regressed"] for r in results)
    print(format_summary("release-gate", results, args.threshold, any_regressed))
    return 1 if any_regressed else 0


def run_trend(args) -> int:
    current = load_json(args.current)
    history = load_history(args.history)
    if len(history) < 2:
        # Not enough history to compute a trend — succeed silently.
        # ASCII-only so this works on Windows cp1252 + Linux CI alike.
        print(f"## Performance trend check\n\n"
              f"Only {len(history)} prior run(s) in history; need >= 2 to "
              f"compute a rolling median. Skipping trend comparison.")
        return 0

    baseline = rolling_median(history, args.window)
    results = compare(current, baseline, args.threshold)
    any_regressed = any(r["regressed"] for r in results)
    print(format_summary(
        f"trend (rolling-{args.window} median)",
        results, args.threshold, any_regressed,
    ))
    # Trend check warns but doesn't block — exit 0 either way unless the
    # caller explicitly asks for strict mode.
    if args.strict and any_regressed:
        return 1
    return 0


def main() -> int:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="mode", required=True)

    rel = sub.add_parser("release", help="Gate for the release workflow")
    rel.add_argument("--current", required=True)
    rel.add_argument("--baseline", required=True)
    rel.add_argument("--history", default=None,
                     help="Optional history.jsonl for rolling-median release baseline")
    rel.add_argument("--window", type=int, default=7)
    rel.add_argument("--threshold", type=float, default=0.25)
    rel.set_defaults(func=run_release)

    trd = sub.add_parser("trend", help="Nightly trend comparison")
    trd.add_argument("--current", required=True)
    trd.add_argument("--history", required=True)
    trd.add_argument("--window", type=int, default=7)
    trd.add_argument("--threshold", type=float, default=0.30)
    trd.add_argument("--strict", action="store_true",
                     help="Exit 1 on regression (default: exit 0, warn-only)")
    trd.set_defaults(func=run_trend)

    args = p.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
