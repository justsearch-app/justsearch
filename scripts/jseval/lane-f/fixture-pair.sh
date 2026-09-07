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

# --- Candidate-budget and step-function pins (PR 0b, second pair round) -------------------
# A chunk that is in one build's top-10 and outside the other's top-20 has moved further than
# score jitter. Two mechanisms in the pipeline can do that, and both are pinned here.
#
# (a) CANDIDATE TRUNCATION. Every leg hands fusion a bounded candidate list. A chunk at the
#     edge of one is inside on one build and outside on the other, and a chunk missing from a
#     leg scores 0.0 for that leg with the leg's weight STILL in the denominator
#     (index.hybrid.chunk_cc_zero_exclude inherits cc_zero_exclude, default false), so it
#     loses that leg's whole weighted share at once — a rank collapse, not a nudge. Min-max
#     normalisation is per-leg over the returned list, so dropping one member also shifts its
#     neighbours' normalised scores.
# (b) STEP FUNCTIONS. Two gates change behaviour discontinuously on a one-document change:
#     leg arbitration compares the two legs' top-10 doc ids by Jaccard and flips alpha 0.5->0.7
#     when overlap < 0.1 — with two 10-element sets that reduces to "share at most ONE doc"
#     (i/(20-i) < 0.1 => i < 1.82), so a single doc entering either top-10 re-weights the whole
#     doc-level fusion; and the recall-complete splice protects each leg's rank<=10 and EVICTS
#     a fused hit to make room. Both are ON by default.
#
# These are ordinary tuning keys set to non-truncating values, not a capture-only code path:
# the product behaviour under them is a behaviour the product supports.
# NOT PINNED, and this is a correction: JUSTSEARCH_RERANK_TOP_K=100 was set here for pair run 3
# to widen the wire limit (searchLimit = max(requestedLimit, rerankConfig.topK())). It BROKE the
# cross-encoder. The CE window is the same number, so the batch went 20 -> 100 documents and the
# stage came back `skipped` / INFERENCE_FAILED on every query — ONNX Runtime arena exhaustion, the
# failure KnowledgeSearchEngine.java:1094-1098 names (remedy: JUSTSEARCH_RERANK_GPU_MEM_MB). Pair 3
# therefore ran with NO reranker: results kept fusion order, `observed.scoreBasis` was `delivered`
# for all 12 queries, and its "2 regressions" measured a degraded pipeline, not a quieter one.
# Widening the budgets without enlarging the CE batch means raising the REQUEST limit (which the
# capture owns) while leaving top_k alone, or raising JUSTSEARCH_RERANK_GPU_MEM_MB first — neither
# is pinned blind here. capture_health now REFUSES a capture whose cross-encoder was dropped, so
# this cannot recur silently.
export JUSTSEARCH_INDEX_HYBRID_CANDIDATE_LIMIT_MAX=5000     # whole-doc legs stop truncating (default 100, corpus ~102 docs)
export JUSTSEARCH_HYBRID_CHUNK_COLLAPSE_LIMIT_MULTIPLIER=50 # parent collapse cap 40 -> 5000 (default 2)
export JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED=false      # kill the Jaccard alpha step function
export JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE=false  # kill the rank<=10 splice step function
#
# NOT fully pinnable, recorded so the next reader does not assume otherwise:
#   SearchExecutor.CHUNK_INITIAL_CANDIDATE_MULTIPLIER = 10 and CHUNK_RETRY_MULTIPLIER = 2 are
#   hard-coded (SearchExecutor.java:63-64). At wire limit 100 the chunk legs see 1000 chunks,
#   which is AT this corpus's chunk count rather than above it.
#   SearchPlanner.MAX_LIMIT = 100 (SearchPlanner.java:37) caps the wire limit, so branch fusion
#   can emit at most 100 docs — below a 102-document corpus. No env setting lifts that ceiling.

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
  # /api/debug/effective-config — NOT /api/config/effective, which is not a registered route and
  # 404s (both effective-config-*.json of the 2026-09-07 pairs are that error). This is the file
  # `provenance.pins` is read from, so a 404 here silently costs the capture its pin proof.
  curl -s -m 5 -H "Host: 127.0.0.1:$port" "$base/api/debug/effective-config" > "$out_abs/effective-config-$n.json" 2>/dev/null || true
  bash scripts/jseval/lane-f/fixture-cycle.sh "$out_abs/capture-$n.json" "$profile" "$port" 2>&1 | tee -a "$out_abs/pair.log"
  node scripts/dev/dev-runner.cjs stop --active --json > "$out_abs/stop-$n.json" 2>&1 || true
  sleep 3
  node scripts/dev/dev-runner.cjs cleanup --active --force --clean hard --json > "$out_abs/cleanup-after-$n.json" 2>&1 || true
}

log "tree=$(git rev-parse --short HEAD) profile=$profile"
log "pins: exhaustive=$JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH slots=$JUSTSEARCH_LLM_SLOTS rerank_deadline_ms=$JUSTSEARCH_RERANK_DEADLINE_MS"
log "pins: candidate_limit_max=$JUSTSEARCH_INDEX_HYBRID_CANDIDATE_LIMIT_MAX collapse_mult=$JUSTSEARCH_HYBRID_CHUNK_COLLAPSE_LIMIT_MULTIPLIER"
log "pins: leg_arbitration=$JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED recall_complete=$JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE"
cycle 1 || exit 1
cycle 2 || exit 1
(cd scripts/jseval && python -m jseval workflow-fixture diff --baseline "$out_abs/capture-1.json" --candidate "$out_abs/capture-2.json" --report-out "$out_abs/diff.json") 2>&1 | tee -a "$out_abs/pair.log"
log "done (diff exit ${PIPESTATUS[0]})"
