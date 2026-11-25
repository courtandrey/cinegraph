#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

: "${CINEGRAPH_HOST:?Set CINEGRAPH_HOST}"

STEPS="${STEPS:-50 75 100 125 150 200}"
COOLDOWN_S="${COOLDOWN_S:-90}"
CONTINUE_ON_FAIL="${CONTINUE_ON_FAIL:-0}"
SEQ_ID="${SEQ_ID:-seq-$(date +%Y%m%d-%H%M%S)}"

export MODE=stress
export RAMP="${RAMP:-1m}"
export HOLD="${HOLD:-3m}"
export LB_PARALLEL="${LB_PARALLEL:-5}"
export SET_SIZE="${SET_SIZE:-500}"

healthy() {
  curl -fsS --max-time 10 "${CINEGRAPH_HOST%/}/api/movies/health" >/dev/null
}

echo "Sequence ${SEQ_ID}: RPS steps [${STEPS}], ramp ${RAMP}, hold ${HOLD}, cooldown ${COOLDOWN_S}s," \
     "${LB_PARALLEL} uploads x ${SET_SIZE} films per step"

run_ids=()
first=1
for rps in ${STEPS}; do
  if [[ ${first} -eq 0 ]]; then
    echo "    cooling down ${COOLDOWN_S}s"
    sleep "${COOLDOWN_S}"
  fi
  first=0

  if ! healthy; then
    echo "!!! health check failed before RPS=${rps}; stopping" >&2
    break
  fi

  run_id="${SEQ_ID}-rps${rps}"
  run_ids+=("${run_id}")
  echo "=== RPS=${rps}  $(date +%H:%M:%S)  log: results/run-${run_id}.log"

  set +e
  RPS="${rps}" RUN_ID="${run_id}" ./run.sh >/dev/null 2>&1
  status=$?
  set -e

  case ${status} in
    0)  echo "    pass" ;;
    99) echo "    SLO breached"
        [[ ${CONTINUE_ON_FAIL} == 1 ]] || { echo "    stopping at first breach (CONTINUE_ON_FAIL=1 to go on)"; break; } ;;
    *)  echo "!!! k6 exited ${status}; see the log. Stopping." >&2; break ;;
  esac
done

[[ ${#run_ids[@]} -gt 0 ]] || exit 1
python3 -I summarize.py results "${SEQ_ID}" "${run_ids[@]}"
