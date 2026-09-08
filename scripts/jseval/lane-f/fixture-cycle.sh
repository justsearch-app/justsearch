#!/usr/bin/env bash
# Lane F: one fixture capture on a FRESH corpus (design 17.2 baseline; the fixture's own rule is
# one capture per fresh corpus because the chat turns index agent history).
# Precondition: a dev stack started with a hard-cleaned data dir is up on <port>.
# Steps: ingest docs/explanation + docs/reference by absolute path, wait for every enrichment
# stage, activate the requested chat profile, run `workflow-fixture capture`.
#
# Usage (repo root): bash scripts/jseval/lane-f/fixture-cycle.sh <out.json> [profile] [api-port]
set -u
out=${1:?out.json}
profile=${2:-standard}
port=${3:-33221}
base="http://127.0.0.1:$port"
hdr=(-H "Host: 127.0.0.1:$port" -H "Content-Type: application/json")
root=$(pwd)
root_win=$(cygpath -w "$root" 2>/dev/null || echo "$root")
out_abs=$(cygpath -w "$(cd "$(dirname "$out")" && pwd)/$(basename "$out")" 2>/dev/null || echo "$out")
log() { echo "[$(date +%H:%M:%S)] $*"; }

# The Worker yields GPU backfill while the LLM is active (main_gpu_active, ADR-0048), so an
# autostarted llama-server stalls enrichment: deactivate first, activate after the wait.
curl -s -m 30 -X POST "${hdr[@]}" -d '{}' "$base/api/ai/runtime/deactivate" > /dev/null

body=$(node -e 'const p=require("path");const r=process.argv[1];console.log(JSON.stringify({paths:[p.join(r,"docs","explanation"),p.join(r,"docs","reference")]}))' "$root_win")
log "ingest: $(curl -s -m 120 -X POST "${hdr[@]}" -d "$body" "$base/api/knowledge/ingest" | head -c 200)"
(cd scripts/jseval && python -c "
from jseval.readiness import wait_pipeline_complete
r = wait_pipeline_complete('$base', timeout_sec=900)
print('READY', r.passed)
") 2>&1 | tail -1

log "activate $profile"
curl -s -m 30 -X POST "${hdr[@]}" -d "{\"variantId\":\"cuda12\",\"chatProfile\":\"$profile\"}" "$base/api/ai/runtime/activate" > /dev/null
for ((i = 0; i < 90; i++)); do
  st=$(curl -s -m 5 -H "Host: 127.0.0.1:$port" "$base/api/ai/runtime/status")
  if echo "$st" | grep -q "\"state\":\"completed\"" && echo "$st" | grep -q "\"chatProfile\":\"$profile\""; then break; fi
  sleep 2
done
log "ai: $(echo "$st" | grep -o '"state":"[a-z]*"' | head -1) $(echo "$st" | grep -o '"chatProfile":"[a-z]*"' | head -1)"

log "capture -> $out_abs"
(cd scripts/jseval && python -m jseval workflow-fixture capture --base-url "$base" --out "$out_abs" --corpus-root "$root_win" 2>&1 | grep -v " INFO ")
log "done"
