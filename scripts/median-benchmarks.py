#!/usr/bin/env python3
"""
Median-aggregate multiple benchmark trial JSON records into one.

Usage:
    python scripts/median-benchmarks.py trial1.json trial2.json trial3.json > merged.json

Each input file is one JSON object produced by parse-benchmarks.py.
Numeric fields are medianed; metadata (commit, tag) is taken from the
first trial; timestamp is set to "now" at merge time.

Non-numeric fields or missing values are dropped from the median
calculation per-metric — a partial trial doesn't poison the whole
record.
"""

from __future__ import annotations

import json
import statistics
import sys
from datetime import datetime, timezone


# Fields we median across trials. Others (timestamp, commit, tag) are
# handled by copying from the first trial.
NUMERIC_FIELDS = [
    "reserve_p50_ms",
    "reserve_p99_ms",
    "commit_p50_ms",
    "commit_p99_ms",
    "release_p50_ms",
    "event_p50_ms",
    "concurrent_throughput_32t",
]


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: median-benchmarks.py <trial.json>...", file=sys.stderr)
        return 1

    trials = []
    for path in sys.argv[1:]:
        with open(path, "r", encoding="utf-8") as f:
            trials.append(json.load(f))
    if not trials:
        print("ERROR: no trials loaded", file=sys.stderr)
        return 2

    out = {
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commit": trials[0].get("commit"),
        "tag": trials[0].get("tag"),
        "trials": len(trials),
    }
    for field in NUMERIC_FIELDS:
        values = [
            t[field] for t in trials
            if field in t and isinstance(t[field], (int, float))
        ]
        if values:
            out[field] = round(statistics.median(values), 2)
        else:
            out[field] = None
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
