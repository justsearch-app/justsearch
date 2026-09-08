#!/usr/bin/env bash
# Lane F: the 917 Derisk 1 Head measurement, scripted so PR 0's before/after and the post-PR 0
# baseline (docs/design/lane-f-engine-jvm/design.md 17.2) run the same procedure.
#
# Phases: launch (dev-runner, 512m Head heap, GC + safepoint log) -> 60 s idle -> ingest
# docs/explanation + docs/reference -> 60 sequential searches during enrichment -> wait for
# every enrichment stage -> 60 searches after -> ai_activate (compact) + one agent turn ->
# warm restart (second GC log) -> stop. The Head and Worker working sets are sampled every 2 s.
#
# Usage (from the repo root, no dev stack running, dists installed):
#   bash scripts/jseval/lane-f/head-flag-run.sh <label> <outdir> [api-port]
# Then: node scripts/jseval/lane-f/analyze-head-run.cjs <outdir>
set -u
label=${1:?label}
out=${2:?outdir}
port=${3:-33221}
mkdir -p "$out"
out_abs=$(cd "$out" && pwd)
root=$(pwd)
# Java and the backend want Windows paths; the shell is POSIX (Git Bash).
out_win=$(cygpath -w "$out_abs" 2>/dev/null || echo "$out_abs")
root_win=$(cygpath -w "$root" 2>/dev/null || echo "$root")
here="$root/scripts/jseval/lane-f"
runner=${DEV_RUNNER:-scripts/dev/dev-runner.cjs}
base="http://127.0.0.1:$port"
hdr=(-H "Host: 127.0.0.1:$port" -H "Content-Type: application/json")
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$out_abs/driver.log"; }
now_ms() { date +%s%3N; }
iso() { date -Iseconds; }

phase_json="$out_abs/phases.json"
echo "{}" > "$phase_json"
phase_mark() { # name start end
  node -e 'const fs=require("fs");const p=process.argv[1];const j=JSON.parse(fs.readFileSync(p,"utf8"));j[process.argv[2]]=[process.argv[3],process.argv[4]];fs.writeFileSync(p,JSON.stringify(j));' "$phase_json" "$1" "$2" "$3"
}

poll_ready() { # t0_ms label -> writes <label>-startup.txt
  local t0=$1 name=$2 http="" head="" worker="" body now el
  for ((i = 0; i < 1500; i++)); do
    body=$(curl -s -m 2 -H "Host: 127.0.0.1:$port" "$base/api/health" 2>/dev/null)
    now=$(now_ms); el=$((now - t0))
    if [ -n "$body" ] && [ -z "$http" ]; then http=$el; fi
    if [ -z "$head" ] && echo "$body" | grep -q '"head":{"state":"LIFECYCLE_STATE_READY"'; then head=$el; fi
    if [ -z "$worker" ] && echo "$body" | grep -q '"worker":{"state":"LIFECYCLE_STATE_READY"'; then worker=$el; fi
    if [ -n "$head" ] && [ -n "$worker" ]; then
      echo "http_first_response_ms=$http head_ready_ms=$head worker_ready_ms=$worker" | tee "$out_abs/$name-startup.txt"
      return 0
    fi
    sleep 0.2
  done
  echo "TIMEOUT http=$http head=$head worker=$worker" | tee "$out_abs/$name-startup.txt"
  return 1
}

search_load() { # count outfile [lexical]
  local n=$1 f=$2 pin=${3:-} a b t0 t1 code body extra
  local words=(index lifecycle commit fingerprint migration worker head grpc lucene vector quantization readiness snapshot codec settle chunk splade embedding reranker citation agent conversation stream tombstone segment merge identity feedback retrieval)
  extra=""
  if [ "$pin" = "lexical" ]; then
    extra=',"pipeline":{"sparseEnabled":true,"denseEnabled":false,"spladeEnabled":false,"fusionAlgorithm":"none","lambdamartEnabled":false,"crossEncoderEnabled":false}'
  fi
  echo "i,ms,http,effectiveMode,decisionKind,tookMs" > "$f"
  for ((i = 0; i < n; i++)); do
    a=${words[$((i % ${#words[@]}))]}
    b=${words[$(((i * 7 + 3) % ${#words[@]}))]}
    t0=$(date +%s%N)
    code=$(curl -s -o "$out_abs/last-search.json" -w '%{http_code}' -m 30 -X POST "${hdr[@]}" -d "{\"query\":\"$a $b\",\"limit\":10$extra}" "$base/api/knowledge/search")
    t1=$(date +%s%N)
    body=$(node -e 'try{const j=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));const t=j.searchTrace||j.introspection||{};console.log([t.effectiveMode||"",t.decisionKind||"",j.tookMs??""].join(","))}catch(e){console.log(",,")}' "$out_abs/last-search.json")
    echo "$i,$(((t1 - t0) / 1000000)),$code,$body" >> "$f"
  done
}

start_stack() { # gclog clean
  local gclog=$1 clean=$2
  JUSTSEARCH_HEAD_HEAP=512m JAVA_OPTS="-Xlog:gc*,safepoint:file=$(cygpath -w "$gclog" 2>/dev/null || echo "$gclog"):time,uptime,level,tags" \
    node "$runner" start --api-port "$port" --clean "$clean" --skip-build --lease-duration-sec 3600 \
    > "$out_abs/dev-runner-$3.log" 2>&1 &
  echo $!
}

stop_stack() {
  node "$runner" stop --active --json > "$out_abs/stop-$1.json" 2>&1 || true
  sleep 3
}

log "label=$label out=$out_abs port=$port head=$(git rev-parse --short HEAD)"
git diff --stat -- scripts/dev/dev-runner.cjs modules/shell/src-tauri/src/lib.rs > "$out_abs/flag-diff-stat.txt"
grep -n "UseSerialGC\|TieredStopAtLevel\|MetaspaceSize" scripts/dev/dev-runner.cjs modules/shell/src-tauri/src/lib.rs > "$out_abs/flag-sites.txt"

# --- sampler ---
rm -f "$out_abs/rss.stop"
powershell -NoProfile -ExecutionPolicy Bypass -File "$here/head-rss-sampler.ps1" -Out "$out_abs/head-rss.csv" -Stop "$out_abs/rss.stop" -IntervalSec 2 > "$out_abs/sampler.log" 2>&1 &
sampler_pid=$!

# --- cold-ish launch (soft clean: fresh index, authored stores kept) ---
t0=$(now_ms); s0=$(iso)
runner_pid=$(start_stack "$out_abs/head-gc.log" soft first)
log "dev-runner pid=$runner_pid"
poll_ready "$t0" first || { log "startup timeout"; }
curl -s -m 5 -H "Host: 127.0.0.1:$port" "$base/api/runtime/manifest" > "$out_abs/manifest-first.json"
jcmd_pid=$(node -e 'const j=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(j.pid??"")' "$out_abs/manifest-first.json" 2>/dev/null)
[ -n "$jcmd_pid" ] && jcmd "$jcmd_pid" VM.flags > "$out_abs/vm-flags.txt" 2>&1
s1=$(iso); phase_mark startup "$s0" "$s1"

log "idle 60 s"
sleep 60
s2=$(iso); phase_mark idle_first60s "$s1" "$s2"

# --- ingest ---
log "ingest docs/explanation + docs/reference"
paths_json=$(node -e 'const p=require("path");const r=process.argv[1];console.log(JSON.stringify({paths:[p.join(r,"docs","explanation"),p.join(r,"docs","reference")]}))' "$root_win")
curl -s -m 120 -X POST "${hdr[@]}" -d "$paths_json" "$base/api/knowledge/ingest" > "$out_abs/ingest-response.json"
log "ingest response: $(head -c 300 "$out_abs/ingest-response.json")"
search_load 60 "$out_abs/search-load-during-enrich.csv"
log "waiting for enrichment"
(cd scripts/jseval && python -c "
import sys; from jseval.readiness import wait_pipeline_complete
r = wait_pipeline_complete('$base', timeout_sec=1500)
print(r)
" > "$out_abs/enrichment-wait.txt" 2>&1)
log "enrichment: $(tail -c 300 "$out_abs/enrichment-wait.txt")"
curl -s -m 5 -H "Host: 127.0.0.1:$port" "$base/api/status" > "$out_abs/status-after-enrich.json"
s3=$(iso); phase_mark ingest_enrich "$s2" "$s3"

# --- search after enrichment, then one agent turn ---
search_load 60 "$out_abs/search-load-after-enrich.csv"
search_load 30 "$out_abs/search-load-lexical-after-enrich.csv" lexical
log "ai activate (compact)"
curl -s -m 30 -X POST "${hdr[@]}" -d '{"variantId":"cuda12","chatProfile":"compact"}' "$base/api/ai/runtime/activate" > "$out_abs/ai-activate.json"
for ((i = 0; i < 90; i++)); do
  st=$(curl -s -m 5 -H "Host: 127.0.0.1:$port" "$base/api/ai/runtime/status")
  if echo "$st" | grep -q '"state":"completed"'; then break; fi
  sleep 2
done
echo "$st" > "$out_abs/ai-status.json"
t_chat0=$(now_ms)
curl -s -N -m 300 -X POST "${hdr[@]}" -H "Accept: text/event-stream" \
  -d '{"messages":[{"role":"user","content":"According to the docs, what is the suicide pact between the Head and the Worker, and which document describes it?"}],"maxIterations":3}' \
  "$base/api/chat/agent" > "$out_abs/chat-run.sse"
t_chat1=$(now_ms)
echo "agent_turn_ms=$((t_chat1 - t_chat0)) bytes=$(wc -c < "$out_abs/chat-run.sse")" | tee "$out_abs/agent-turn.txt"
s4=$(iso); phase_mark search_and_agent "$s3" "$s4"

# --- warm restart ---
log "warm restart"
stop_stack first
t1=$(now_ms); s5=$(iso)
runner_pid=$(start_stack "$out_abs/head-gc-2.log" none second)
poll_ready "$t1" restart || { log "restart timeout"; }
sleep 20
s6=$(iso); phase_mark restart_warm "$s5" "$s6"
stop_stack second
touch "$out_abs/rss.stop"
wait "$sampler_pid" 2>/dev/null
log "done"
