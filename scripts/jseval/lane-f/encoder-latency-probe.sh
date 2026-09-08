#!/usr/bin/env bash
# Lane F: request-time encoder latency from the search trace (design 16, "request-time encoders").
# Sends each fixture query N times with debug tracing and records every stage's ms from
# searchTrace.stages, so the query-side NER (query-understanding), retrieval (which embeds the
# query) and cross-encoder rerank stages can be summarised per call. Run with the agent idle
# and no bulk indexing for the idle figure; run during bulk embedding for the contended figure.
#
# Usage (repo root, dev stack running): bash scripts/jseval/lane-f/encoder-latency-probe.sh <outdir> [repeats] [api-port]
set -u
out=${1:?outdir}
reps=${2:-3}
port=${3:-33221}
mkdir -p "$out"
base="http://127.0.0.1:$port"
fixture=scripts/jseval/lane-f-workflow-fixture.v1.json
csv="$out/encoder-stage-ms.csv"
echo "queryId,rep,http,tookMs,effectiveMode,stageId,status,ms" > "$csv"
queries=$(node -e 'const f=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));for(const q of f.queries) console.log(q.id+"\t"+q.query)' "$fixture")
while IFS=$'\t' read -r qid qtext; do
  [ -n "$qid" ] || continue
  for ((r = 0; r < reps; r++)); do
    body=$(node -e 'console.log(JSON.stringify({query:process.argv[1],limit:10,debug:true}))' "$qtext")
    code=$(curl -s -o "$out/last.json" -w '%{http_code}' -m 60 -X POST -H "Host: 127.0.0.1:$port" -H "Content-Type: application/json" -d "$body" "$base/api/knowledge/search")
    node -e '
      const [qid, rep, code, p] = process.argv.slice(1);
      let j; try { j = JSON.parse(require("fs").readFileSync(p, "utf8")); } catch { console.log([qid, rep, code, "", "", "", "", ""].join(",")); process.exit(0); }
      const t = j.searchTrace || {};
      for (const s of t.stages || []) console.log([qid, rep, code, j.tookMs ?? "", t.effectiveMode ?? "", s.id, s.status, s.ms ?? ""].join(","));
    ' "$qid" "$r" "$code" "$out/last.json" >> "$csv"
  done
done <<< "$queries"
rm -f "$out/last.json"
node -e '
  const fs = require("fs");
  const rows = fs.readFileSync(process.argv[1], "utf8").trim().split("\n").slice(1).map((l) => l.split(","));
  const by = {};
  for (const r of rows) { if (r[7] === "") continue; (by[r[5]] ??= []).push(+r[7]); }
  const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
  const took = rows.filter((r) => r[3] !== "").map((r) => +r[3]);
  const lines = [`# Request-time stage latency (ms) over ${rows.length ? new Set(rows.map((r) => r[0] + "/" + r[1])).size : 0} calls`];
  lines.push(`- tookMs: p50 ${pct(took, 0.5)} · p95 ${pct(took, 0.95)} · max ${Math.max(...took)}`);
  for (const [id, ms] of Object.entries(by)) lines.push(`- ${id}: n=${ms.length} p50 ${pct(ms, 0.5)} · p95 ${pct(ms, 0.95)} · max ${Math.max(...ms)}`);
  fs.writeFileSync(process.argv[2], lines.join("\n") + "\n");
  console.log(lines.join("\n"));
' "$csv" "$out/encoder-stage-summary.md"
