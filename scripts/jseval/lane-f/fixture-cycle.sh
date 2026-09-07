#!/usr/bin/env bash
# Lane F: one fixture capture on a FRESH corpus (design 17.2 baseline; the fixture's own rule is
# one capture per fresh corpus because the chat turns index agent history).
# Precondition: a dev stack started with a hard-cleaned data dir is up on <port>.
#
# CAPTURE-RUN SETTINGS THE ORCHESTRATOR SETS AT STACK LAUNCH (boot-time, not per request --
# this script does not set them, it assumes them; both sides of a paired diff must match):
#   JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH=true  every kNN query exact, so the dense leg
#       stops returning an approximate neighbour set that moves with the HNSW graph
#   JUSTSEARCH_LLM_SLOTS=1                          exactly one llama-server slot (default 2,
#       EnvRegistry.LLM_SLOTS / justsearch.llm.slots) -- two slots make the prompt-cache
#       prefix a turn sees a scheduling outcome
#   JUSTSEARCH_RERANK_DEADLINE_MS / JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS pinned HIGH
#       (defaults 200 / 150) so the cross-encoder rerank cannot miss its deadline under load
#       and reorder the hit set for a reason that is machine load, not the build
#   ONE chat profile for both sides -- pass the SAME [profile] argument to both runs; the
#       fixture's own `sampling` block (temperature 0.0 + seed) pins the rest.
#
# Steps: ingest docs/explanation + docs/reference by absolute path, wait for every enrichment
# stage, activate the requested chat profile, run `workflow-fixture capture`.
#
# Exits: 0 captured; 2 a usage/backend error from `capture`; 3 enrichment did not complete in
# time; 4 the inference engine was not online on the requested profile. 3 and 4 are both
# "the preconditions for a capture were not met" -- the artifact is refused, not degraded.
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
# POSIX-side directory for the diagnostics written beside the capture (the activate response and
# the final runtime status). $out_abs is a Windows path for python's benefit; these are written
# by the shell, so they need the shell's view.
out_dir=$(cd "$(dirname "$out")" && pwd)
log() { echo "[$(date +%H:%M:%S)] $*"; }

# The Worker yields GPU backfill while the LLM is active (main_gpu_active, ADR-0048), so an
# autostarted llama-server stalls enrichment: deactivate first, activate after the wait.
curl -s -m 30 -X POST "${hdr[@]}" -d '{}' "$base/api/ai/runtime/deactivate" > /dev/null

body=$(node -e 'const p=require("path");const r=process.argv[1];console.log(JSON.stringify({paths:[p.join(r,"docs","explanation"),p.join(r,"docs","reference")]}))' "$root_win")
log "ingest: $(curl -s -m 120 -X POST "${hdr[@]}" -d "$body" "$base/api/knowledge/ingest" | head -c 200)"
# The enrichment wait is a PRECONDITION, not a progress message. The four-capture acceptance
# had side A cycle 1 return READY False after the full 900s and capture anyway, on a partially
# enriched index: that side then marked 11 of 12 queries noisy (26 unmatched hits, max CE delta
# 0.0982 against 0.0000 on the healthy side) and the whole verdict hid behind the noise mask.
# A capture taken on a half-enriched index is not a slower capture, it is a different index --
# so this aborts rather than recording one. `capture_health` refuses such a capture too (belt
# and braces: this stops it being written, that stops it being trusted if it ever is).
# NOTE the exit code is taken from the command, NOT through a pipe: `cmd | tail -1` reports
# tail exit status, so piping here would have silently swallowed the very failure this guards.
wait_out=$( (cd scripts/jseval && python -c "
import sys
from jseval.readiness import wait_pipeline_complete
r = wait_pipeline_complete('$base', timeout_sec=900)
print('READY', r.passed)
sys.exit(0 if r.passed else 1)
") 2>&1 )
wait_rc=$?
log "$(echo "$wait_out" | tail -1)"
if [[ $wait_rc -ne 0 ]]; then
  log "enrichment did not complete within 900s -- REFUSING to capture a partially enriched index"
  exit 3
fi

# --- bring the engine up, and PROVE it is up ------------------------------------------------
# What this used to do, and why all six captures of the 2026-09-07 acceptance recorded
# `aiRuntimeState: "failed"`: it fired activate unconditionally, discarded the response, and
# then polled `activation.state == "completed"`. That field is the last activation PROCEDURE's
# outcome, NOT liveness. The engine was in fact online every time — the chat turns terminated
# `done` with per-turn samplingApplied — so the poll simply ran to its 180s expiry on a healthy
# stack and the script captured anyway. A genuinely dead engine would have produced a capture
# on exactly the same path.
#
# Liveness is the realized identity under `active`, which the backend projects only while the
# engine is online. The dev-MCP reads it that way for the same reason
# (scripts/dev/justsearch-dev-mcp/server.mjs:2680-2687, tempdoc 842 review D3/N1): an engine
# brought up by AI AUTOSTART never runs the activation state machine at all.
#
# The pre-check is also what keeps the deactivate above from racing autostart: if autostart
# brought the engine back during the ingest+enrichment wait ON THE PROFILE WE WANT, activating
# again would tear a healthy engine down and re-run a GPU self-test for nothing. A DIFFERENT
# profile still activates -- that is a real profile switch.
ai_status() { curl -s -m 10 -H "Host: 127.0.0.1:$port" "$base/api/ai/runtime/status"; }
# Online AND on the requested profile. Reads the JSON with the same predicate the capture's
# `read_ai_runtime` uses, so the gate and the artifact cannot disagree.
ai_ready() {
  node -e '
    let raw = "";
    process.stdin.on("data", d => raw += d);
    process.stdin.on("end", () => {
      let st;
      try { st = JSON.parse(raw); } catch { process.exit(1); }
      const active = st && typeof st.active === "object" && st.active ? st.active : {};
      const online = active.modelPath != null
        || (st?.activation?.state === "completed" && active.activeVariantId);
      const profile = active.chatProfile ?? st?.chatProfile;
      process.exit(online && profile === process.argv[1] ? 0 : 1);
    });
  ' "$1"
}

st=$(ai_status)
if echo "$st" | ai_ready "$profile"; then
  log "ai: already online on $profile — not re-activating (a needless GPU self-test + restart)"
else
  log "activate $profile"
  # The activate response is KEPT: a 4xx/5xx here used to vanish into /dev/null, so a refused
  # activation was indistinguishable from a slow one.
  act=$(curl -s -m 30 -o "$out_dir/ai-activate.json" -w '%{http_code}' "${hdr[@]}" -X POST \
    -d "{\"variantId\":\"cuda12\",\"chatProfile\":\"$profile\"}" \
    "$base/api/ai/runtime/activate")
  log "activate -> HTTP $act $(head -c 200 "$out_dir/ai-activate.json" 2>/dev/null)"
  for ((i = 0; i < 90; i++)); do
    st=$(ai_status)
    if echo "$st" | ai_ready "$profile"; then break; fi
    sleep 2
  done
fi

echo "$st" > "$out_dir/ai-status.json"
act_state=$(echo "$st" | grep -o '"state":"[a-z_]*"' | head -1)
log "ai: engine=$(echo "$st" | ai_ready "$profile" && echo online || echo OFFLINE) profile=$(echo "$st" | grep -o '"chatProfile":"[a-z]*"' | head -1) last-activation-procedure=$act_state"
if ! echo "$st" | ai_ready "$profile"; then
  log "the inference engine is not online on profile '$profile' -- REFUSING to capture chat"
  log "turns no model answered. Status written to $out_dir/ai-status.json"
  exit 4
fi

log "capture -> $out_abs"
(cd scripts/jseval && python -m jseval workflow-fixture capture --base-url "$base" --out "$out_abs" --corpus-root "$root_win" 2>&1 | grep -v " INFO ")
log "done"
