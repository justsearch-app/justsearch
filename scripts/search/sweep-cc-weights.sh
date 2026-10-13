#!/usr/bin/env bash
# CC fusion weight sweep — restart backend with each config, run jseval eval.
# Usage: bash scripts/search/sweep-cc-weights.sh [dataset] [modes]
#   dataset: scifact (default), nfcorpus, arguana
#   modes:   comma-separated jseval modes (default: full,lexical,bm25_splade)
#
# Requires: python with jseval installed, curl, Java.
# On Windows: run from Git Bash. Uses PowerShell for process management.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODELS_DIR="${JUSTSEARCH_MODELS_DIR:-$REPO_ROOT/models}"

# Use a fresh data directory to avoid index contamination from persistent watch roots.
# If JUSTSEARCH_DATA_DIR is set, use it; otherwise create a timestamped fresh dir.
if [ -n "${JUSTSEARCH_DATA_DIR:-}" ]; then
  DATA_DIR="$JUSTSEARCH_DATA_DIR"
else
  DATA_DIR="$REPO_ROOT/tmp/eval-fresh-$(date +%Y%m%d-%H%M%S)"
  echo "Using fresh data directory: $DATA_DIR"
fi
PORT=33221
BASE_URL="http://127.0.0.1:$PORT"
DATASET="${1:-scifact}"
MODES="${2:-full,lexical,bm25_splade}"
OUTPUT_BASE="$REPO_ROOT/tmp/beir-eval/cc-weight-sweep"
INSTALL_BAT="$REPO_ROOT/modules/ui/build/install/ui/bin/ui.bat"

# Weight grid on the constrained simplex (sparse, dense, splade, name)
CONFIGS=(
  "1.00 0.00 0.00 bm25-only"
  "0.70 0.15 0.15 bm25-heavy"
  "0.60 0.20 0.20 bm25-dominant"
  "0.50 0.25 0.25 balanced-bm25"
  "0.35 0.35 0.30 current-default"
  "0.33 0.34 0.33 equal"
  "0.50 0.00 0.50 bm25-splade"
  "0.50 0.50 0.00 bm25-dense"
  "0.40 0.40 0.20 dense-leaning"
  "0.40 0.20 0.40 splade-leaning"
)

kill_backend() {
  [ -s "$OWNED_RECORD" ] || return 0
  powershell -NoProfile -Command '
    $r=Get-Content -Raw -LiteralPath $env:JUSTSEARCH_SWEEP_RECORD | ConvertFrom-Json
    $result = & $env:JUSTSEARCH_SWEEP_TERMINATOR -Pid $r.pid -CreationFileTimeUtc $r.creationFileTimeUtc -Code 1 -Tree
    $status = $LASTEXITCODE
    $result | Write-Output
    if ($status -eq 3 -and ($result | Select-Object -Last 1 | ConvertFrom-Json).outcome -in @("gone", "mismatch")) { exit 0 }
    exit $status
  ' || { echo "Owned backend tree stop incomplete; inspect $OWNED_RECORD before retrying" >&2; return 1; }
  : > "$OWNED_RECORD"
}

OWNED_RECORD="$(mktemp)"
export JUSTSEARCH_SWEEP_RECORD="$(cygpath -w "$OWNED_RECORD")"
export JUSTSEARCH_SWEEP_TERMINATOR="$(cygpath -w "$REPO_ROOT/scripts/dev/lib/terminate-process.ps1")"
trap 'kill_backend; if [ ! -s "$OWNED_RECORD" ]; then rm -f "$OWNED_RECORD"; fi' EXIT

wait_ready() {
  local max_wait=90
  for i in $(seq 1 "$max_wait"); do
    local health
    health=$(curl -sf "$BASE_URL/api/health" 2>/dev/null || echo "")
    if echo "$health" | grep -q '"worker":{"state":"LIFECYCLE_STATE_READY"'; then
      # Extra settle time for searcher initialization
      sleep 3
      echo "Backend ready after ${i}s"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: Backend did not become ready in ${max_wait}s" >&2
  return 1
}

verify_corpus() {
  # Verify single-corpus purity and SPLADE backfill coverage
  local status
  status=$(curl -sf "$BASE_URL/api/status" 2>/dev/null || echo "{}")

  local doc_count splade_coverage
  doc_count=$(echo "$status" | python -c "import sys,json; print(json.load(sys.stdin).get('indexedDocuments',0))" 2>/dev/null || echo 0)
  splade_coverage=$(echo "$status" | python -c "import sys,json; print(json.load(sys.stdin).get('spladeCoveragePercent',0))" 2>/dev/null || echo 0)

  echo "Corpus check: docs=$doc_count, spladeCoverage=${splade_coverage}%"

  # Warn if SPLADE coverage is low (modes with SPLADE may be unreliable)
  local coverage_int
  coverage_int=$(echo "$splade_coverage" | cut -d. -f1)
  if [ "$coverage_int" -lt 95 ] 2>/dev/null; then
    echo "WARNING: SPLADE coverage is ${splade_coverage}% (<95%). SPLADE-involving modes may be unreliable."
    echo "  Wait for backfill to complete or use --modes without SPLADE (e.g., lexical)."
  fi
}

start_backend() {
  local sparse=$1 dense=$2 splade=$3

  export JUSTSEARCH_DATA_DIR="$DATA_DIR"
  export JUSTSEARCH_MODELS_DIR="$MODELS_DIR"
  export JUSTSEARCH_WATCH_PATHS=""
  export JUSTSEARCH_INFERENCE_ENABLED=false
  export JUSTSEARCH_API_PORT=$PORT
  export JUSTSEARCH_HYBRID_CC_WEIGHT_SPARSE="$sparse"
  export JUSTSEARCH_HYBRID_CC_WEIGHT_DENSE="$dense"
  export JUSTSEARCH_HYBRID_CC_WEIGHT_SPLADE="$splade"

  # A listener we did not start is a conflict, never a target to kill.
  if curl -sf "$BASE_URL/api/status" >/dev/null 2>&1; then
    echo "API port is occupied; stop its owner yourself or choose another port" >&2; return 1
  fi
  powershell -NoProfile -File "$REPO_ROOT/scripts/dev/start-recorded-command.ps1" \
    -CommandFile "$(cygpath -w "$INSTALL_BAT")" -RecordPath "$JUSTSEARCH_SWEEP_RECORD"
}

# Ensure distribution is built
if [ ! -f "$INSTALL_BAT" ]; then
  echo "Building distribution..."
  cd "$REPO_ROOT"
  ./gradlew.bat :modules:app-launcher:installDist -q
fi

mkdir -p "$OUTPUT_BASE"
SUMMARY="$OUTPUT_BASE/summary-${DATASET}.txt"
{
  echo "CC Weight Sweep — $DATASET — $(date -Iseconds)"
  echo "Modes: $MODES"
  echo "================================================"
  printf "%-18s  %6s %6s %6s  %8s %8s %8s %8s\n" \
    "Config" "sparse" "dense" "splade" "nDCG@10" "AP@10" "RR@10" "R@10"
  echo "------------------------------------------------------------------------"
} > "$SUMMARY"

total=${#CONFIGS[@]}
current=0

for config_line in "${CONFIGS[@]}"; do
  read -r sparse dense splade name <<< "$config_line"
  current=$((current + 1))
  echo ""
  echo "=== [$current/$total] $name (sparse=$sparse, dense=$dense, splade=$splade) ==="

  kill_backend
  start_backend "$sparse" "$dense" "$splade"

  if ! wait_ready; then
    printf "%-18s  %6s %6s %6s  %8s %8s %8s %8s\n" \
      "$name" "$sparse" "$dense" "$splade" "FAIL" "FAIL" "FAIL" "FAIL" >> "$SUMMARY"
    continue
  fi

  # Verify corpus purity and SPLADE coverage on first config
  if [ "$current" -eq 1 ]; then
    verify_corpus
  fi

  OUT_DIR="$OUTPUT_BASE/$name"
  LOG_FILE="$OUTPUT_BASE/${name}.log"

  if python -m jseval requery \
    --dataset "$DATASET" \
    --modes "$MODES" \
    --base-url "$BASE_URL" \
    --output-dir "$OUT_DIR" \
    --top-k 10 \
    --allow-errors \
    2>&1 | tee "$LOG_FILE"; then
    eval_ok=true
  else
    eval_ok=false
    echo "WARNING: jseval failed for $name"
  fi

  # Parse metrics from jseval output (last occurrence for multi-mode runs)
  # jseval prints: "    nDCG@10: 0.6622" and "  full: nDCG@10=0.6574"
  # Use sed instead of grep -P for Windows Git Bash compatibility
  ndcg=$(sed -n 's/.*nDCG@10[=:] *\([0-9.]*\).*/\1/p' "$LOG_FILE" 2>/dev/null | tail -1)
  ap=$(sed -n 's/.*AP@10[=:] *\([0-9.]*\).*/\1/p' "$LOG_FILE" 2>/dev/null | tail -1)
  rr=$(sed -n 's/.*RR@10[=:] *\([0-9.]*\).*/\1/p' "$LOG_FILE" 2>/dev/null | tail -1)
  recall=$(sed -n 's/.*R@10[=:] *\([0-9.]*\).*/\1/p' "$LOG_FILE" 2>/dev/null | tail -1)
  ndcg=${ndcg:-N/A}; ap=${ap:-N/A}; rr=${rr:-N/A}; recall=${recall:-N/A}

  printf "%-18s  %6s %6s %6s  %8s %8s %8s %8s\n" \
    "$name" "$sparse" "$dense" "$splade" "$ndcg" "$ap" "$rr" "$recall" >> "$SUMMARY"

  echo "--- $name: nDCG@10=$ndcg ---"
done

kill_backend

echo ""
echo "=== SWEEP COMPLETE ==="
echo ""
cat "$SUMMARY"
echo ""
echo "Results in: $OUTPUT_BASE"
echo "Summary:    $SUMMARY"
