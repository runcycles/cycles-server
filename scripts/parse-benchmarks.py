#!/usr/bin/env python3
"""
Parse surefire XML output from the benchmark profile into a JSON record
for the perf-regression pipeline.

Usage:
    python scripts/parse-benchmarks.py <surefire-reports-dir> [--trial-of N]

Emits one JSON object on stdout with the seven headline metrics defined
in `benchmarks/README.md`. Values are in ms except throughput (ops/s).

When called with `--trial-of N` (for N=1..3), emits an object tagged with
that trial index; callers median-aggregate across trials.

Exits non-zero if any headline metric can't be parsed — intentional, a
benchmark run that silently skipped a test shouldn't land in history.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from typing import Optional


# Each (metric_name, regex) pair extracts one headline number.
# The surefire XML wraps benchmark stdout in <system-out>...</system-out>
# with CDATA blocks, which we read verbatim as a string. The benchmark
# tests print a pipe-delimited table like:
#
#   | Reserve                   |   5.3ms|   8.1ms|  18.2ms| ...
#
# We match on operation name followed by the first three latency
# columns (p50, p95, p99).
#
# Concurrent throughput comes from:
#   [Concurrent] 32 threads: 78960 ops in 30s = 2632.0 ops/s  p50=...
BENCH_TABLE_ROW = (
    r"\|\s*{op}\s*\|"
    r"\s*([0-9.]+)ms\|"  # p50
    r"\s*([0-9.]+)ms\|"  # p95
    r"\s*([0-9.]+)ms\|"  # p99
)

EXTRACTORS = [
    ("reserve_p50_ms", BENCH_TABLE_ROW.format(op="Reserve"), 1),
    ("reserve_p99_ms", BENCH_TABLE_ROW.format(op="Reserve"), 3),
    ("commit_p50_ms",  BENCH_TABLE_ROW.format(op="Commit"),  1),
    ("commit_p99_ms",  BENCH_TABLE_ROW.format(op="Commit"),  3),
    ("release_p50_ms", BENCH_TABLE_ROW.format(op="Release"), 1),
    ("event_p50_ms",   BENCH_TABLE_ROW.format(op="Event"),   1),
]

CONCURRENT_32T_RE = re.compile(
    r"\[Concurrent\]\s*32\s*threads:\s*\d+\s*ops\s*in\s*\d+s\s*=\s*([0-9.]+)\s*ops/s",
    re.IGNORECASE,
)


def read_all_xml(directory: str) -> str:
    """Concatenate every surefire XML that matches a benchmark class."""
    patterns = [
        "TEST-*CyclesProtocolBenchmarkTest*.xml",
        "TEST-*CyclesProtocolReadBenchmarkTest*.xml",
        "TEST-*CyclesProtocolConcurrentBenchmarkTest*.xml",
    ]
    text_parts = []
    for pat in patterns:
        for path in glob.glob(os.path.join(directory, pat)):
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                text_parts.append(f.read())
    if not text_parts:
        print(
            f"ERROR: no benchmark surefire XMLs found in {directory}",
            file=sys.stderr,
        )
        sys.exit(2)
    return "\n".join(text_parts)


def extract_metric(text: str, regex: str, group_idx: int) -> Optional[float]:
    m = re.search(regex, text)
    if not m:
        return None
    try:
        return float(m.group(group_idx))
    except (ValueError, IndexError):
        return None


def extract_concurrent_throughput(text: str) -> Optional[float]:
    m = CONCURRENT_32T_RE.search(text)
    if not m:
        return None
    try:
        return float(m.group(1))
    except ValueError:
        return None


def git_short_sha() -> str:
    try:
        out = subprocess.check_output(
            ["git", "rev-parse", "--short=7", "HEAD"],
            stderr=subprocess.DEVNULL,
        )
        return out.decode("utf-8").strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument(
        "reports_dir",
        help="Path to target/surefire-reports/ containing benchmark XMLs",
    )
    p.add_argument(
        "--trial-of",
        type=int,
        default=None,
        help="Trial index (1..N) when called repeatedly for median aggregation",
    )
    p.add_argument(
        "--tag",
        default=None,
        help="Release tag, if this run is part of a release",
    )
    args = p.parse_args()

    text = read_all_xml(args.reports_dir)

    record = {
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commit": git_short_sha(),
        "tag": args.tag,
    }

    missing = []
    for name, regex, group_idx in EXTRACTORS:
        val = extract_metric(text, regex, group_idx)
        if val is None:
            missing.append(name)
        record[name] = val

    throughput = extract_concurrent_throughput(text)
    if throughput is None:
        missing.append("concurrent_throughput_32t")
    record["concurrent_throughput_32t"] = throughput

    if args.trial_of is not None:
        record["trial"] = args.trial_of

    if missing:
        print(
            "ERROR: could not parse metrics: " + ", ".join(missing),
            file=sys.stderr,
        )
        # Emit what we have, but exit non-zero so the pipeline fails loudly.
        # Partial data is still useful for debugging why parsing broke.
        print(json.dumps(record))
        return 3

    print(json.dumps(record))
    return 0


if __name__ == "__main__":
    sys.exit(main())
