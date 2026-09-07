#!/usr/bin/env bash
# Lane F: two fixture captures on two FRESH ingests of the same corpus, one build, then the diff.
# This is PR 0b's acceptance ("two fresh-corpus captures on one build diff clean on every exact
# field") and the shape of one side of stage E's paired capture. Each cycle launches the
# dev-runner from THIS tree with the capture-run pins, runs fixture-cycle.sh, and tears the stack
# down with a hard clean so the second cycle indexes from nothing.
#
# Pins (design section 0, PR 0b): exhaustive dense retrieval, one llama-server slot, the
# reranker deadlines out of the way, one chat profile for both captures.
#
# Usage (repo root, dists installed, no dev stack running):
#   bash scripts/jseval/lane-f/fixture-pair.sh <outdir> [profile] [api-port]
set -u
out=${1:?outdir}
profile=${2:-compact}
port=${3:-33221}
mkdir -p "$out"
out_abs=$(cd "$out" && pwd)
base="http://127.0.0.1:$port"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$out_abs/pair.log"; }

export JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH=true
export JUSTSEARCH_LLM_SLOTS=1
export JUSTSEARCH_RERANK_DEADLINE_MS=60000
export JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS=60000

wait_ready() {
  for ((i = 0; i < 900; i++)); do
    body=$(curl -s -m 2 -H "Host: 127.0.0.1:$port" "$base/api/health" 2>/dev/null)
    if echo "$body" | grep -q '"worker":{"state":"LIFECYCLE_STATE_READY"'; then return 0; fi
    sleep 0.5
  done
  return 1
}

cycle() { # n
  local n=$1
  node scripts/dev/dev-runner.cjs cleanup --active --force --clean hard --json > "$out_abs/cleanup-before-$n.json" 2>&1 || true
  node scripts/dev/dev-runner.cjs start --api-port "$port" --clean hard --skip-build --lease-duration-sec 3600 \
    > "$out_abs/dev-runner-$n.log" 2>&1 &
  local runner=$!
  log "cycle $n: dev-runner pid=$runner"
  wait_ready || { log "cycle $n: stack not ready"; tail -20 "$out_abs/dev-runner-$n.log"; return 1; }
  curl -s -m 5 -H "Host: 127.0.0.1:$port" "$base/api/config/effective" > "$out_abs/effective-config-$n.json" 2>/dev/null || true
  bash scripts/jseval/lane-f/fixture-cycle.sh "$out_abs/capture-$n.json" "$profile" "$port" 2>&1 | tee -a "$out_abs/pair.log"
  node scripts/dev/dev-runner.cjs stop --active --json > "$out_abs/stop-$n.json" 2>&1 || true
  sleep 3
  node scripts/dev/dev-runner.cjs cleanup --active --force --clean hard --json > "$out_abs/cleanup-after-$n.json" 2>&1 || true
}

log "tree=$(git rev-parse --short HEAD) profile=$profile pins: exhaustive=$JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH slots=$JUSTSEARCH_LLM_SLOTS rerank_deadline_ms=$JUSTSEARCH_RERANK_DEADLINE_MS"
cycle 1 || exit 1
cycle 2 || exit 1
(cd scripts/jseval && python -m jseval workflow-fixture diff --baseline "$out_abs/capture-1.json" --candidate "$out_abs/capture-2.json" --report-out "$out_abs/diff.json") 2>&1 | tee -a "$out_abs/pair.log"
log "done (diff exit ${PIPESTATUS[0]})"
