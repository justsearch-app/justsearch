#!/usr/bin/env bash
# GET /api/knowledge/status handler: modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java:716-724.
# Fields: modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeStatusView.java:23-54.
# That view has no chunk count. Supplemental GET /api/status supplies
# worker.enrichment.chunk.chunkDocCount (modules/app-api/src/main/java/io/justsearch/app/api/status/ChunkCoverageView.java:14).
# Usage: bash status-sampler.sh <outdir> [api-port] [stop-file]
# Samples every 5 seconds; failures/stale views abort rather than inventing zero progress.
set -euo pipefail
out=${1:?outdir}
port=${2:-33221}
stop=${3:-$out/status.stop}
[[ $port =~ ^[0-9]+$ && $port -ge 1 && $port -le 65535 ]] || { echo 'Invalid API port' >&2; exit 2; }
mkdir -p "$out"
base="http://127.0.0.1:$port"
file="$out/status-series.csv"
echo 'ts,indexedDocuments,pendingNerCount,completedNerCount,embeddingCoveragePercent,spladeCoveragePercent,chunkDocCount' > "$file"
while [[ ! -e $stop ]]; do
  knowledge=$(curl --noproxy '*' -fsS -m 5 -H "Host: 127.0.0.1:$port" "$base/api/knowledge/status")
  status=$(curl --noproxy '*' -fsS -m 5 -H "Host: 127.0.0.1:$port" "$base/api/status")
  node -e '
    const k=JSON.parse(process.argv[1]), s=JSON.parse(process.argv[2]);
    if(k.ready!==true || k.statusStale===true || s.workerRpcStale===true) throw new Error("Status unavailable or stale");
    const counters=[k.indexedDocuments,k.pendingNerCount,k.completedNerCount,k.embeddingCoveragePercent,k.spladeCoveragePercent,s.worker?.enrichment?.chunk?.chunkDocCount];
    if(counters.some(v=>typeof v!=="number" || !Number.isFinite(v) || v<0)) throw new Error("Missing numeric enrichment/chunk counters");
    console.log([new Date().toISOString(),...counters].join(","));
  ' "$knowledge" "$status" >> "$file"
  sleep 5
done
