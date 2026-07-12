#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

: "${CINEGRAPH_HOST:?Set CINEGRAPH_HOST}"
command -v k6 >/dev/null 2>&1 || {
  echo "k6 is required: https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
  exit 1
}

RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
mkdir -p results
LOG="results/run-${RUN_ID}.log"

set +e
k6 run --summary-export "results/summary-${RUN_ID}.json" -e RUN_ID="${RUN_ID}" perf-test.js 2>&1 | tee "${LOG}"
status=${PIPESTATUS[0]}
set -e

grep -o 'PERF_HASH [0-9a-f]\{64\}' "${LOG}" | awk '{print $2}' | sort -u > "results/hashes-${RUN_ID}.txt"

echo
echo "Run log:          ${LOG}"
echo "Summary JSON:     results/summary-${RUN_ID}.json"
echo "Created hashes:   results/hashes-${RUN_ID}.txt ($(wc -l < "results/hashes-${RUN_ID}.txt") hashes, keep for cleanup)"
exit "${status}"
