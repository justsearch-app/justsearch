# C1 final live acceptance

Candidate8f8c7d775da4f4f69bc21c8399e3c69d0a90cd41, Windows11 / Temurin25.0.2,
standard Qwen3.5-9B CUDA12. Fresh owned run6abb9ef3-3cd5-48a0-a8d7-301f4ad79f3f,
API58760, data457, installed stamp061beab1388633ee. The stamp is unchanged because Engine
Java/distribution inputs are unchanged; registration records source8f8 and FRESH build/index.
Standard activation458 completes in13554ms. One registered stack, owned7200s lease.
Raw: `tmp/c1-final-live-start-457.txt`, `tmp/c1-standard-activation-458.json`.

## Default-limit fairness459 — PASS

On the empty fresh index, effective aggregate64/per-context16. Sixteen real512-token chat
holders all return200 and complete without SSE errors. All16 span the complete probe window.
The saturated client's search, suggest and MCP calls return429 ADMISSION_CONTEXT_LIMIT,
Retry-After1 and boolean retrySafe:true. Same-client health and another client's search
return200. Full capture completes in118342.376ms, below the unchanged five-minute bound.
The strict oracle passes with no failures. This fresh capture supersedes the incomplete
retrySafe evidence of historical169, without relabeling that historical capture.
Raw: `tmp/c1-final-fairness-459.txt`, `tmp/c1-final-fairness-459/fairness.json`.

## Standard-active primary460 — PASS

Continuous search is active before root addition. The initial walk reports completed at
19:01:20.071018800Z; snapshot461 records it at19:01:45.812599Z, before a60s periodic rescan
can conceal a first-walk failure. All469 materialized files index from zero, readiness passes,161.36s at2.9docs/sec.
Continuous load completes308/308 requests with zero errors over167.423s; p50=4.354ms,
p95=7515.848ms, max=9775.79ms. Many early requests query the growing index; these are
plumbing/pacing measurements, not E steady-state latency acceptance. The separate real
model-backed query completes in7008ms (CPU cross-encoder6924ms). GPU NER is available;
standard chat holds GPU scheduling, so full enrichment is intentionally a separate arm.
Dataset metadata says468 documents; the materialized identity verifies469 files. No retrieval
quality claim is made from this self-demo corpus or incomplete enrichment. Raw first-walk receipt: `tmp/c1-standard-primary-first-walk-461.json`.

Raw run: `tmp/c1-final-standard-primary-460.txt`,
`scripts/jseval/tmp/eval-results/lane-f-c1-standard-primary-460/20260909T190418_golden_synth-multihop-prose-v2/summary.json`,
`tmp/c1-standard-primary-evidence-462/`. The owned stack stopped with portsClosed:true,
clean:none; stop receipt `tmp/dev-runner/runs/6abb9ef3-3cd5-48a0-a8d7-301f4ad79f3f/stop-report.json`.

Offline full enrichment remains required; reduced aggregate is recorded below. No failed
live request is excluded or retried away, and no E latency/heap threshold is changed.

## Reduced aggregate467/468 — PASS

Fresh runa5e49954-8846-4b3a-986c-1225bb28b617, API62968, data463, same source/stamp;
standard CUDA activation464 completes10970ms. Effective aggregate3 and per-context16.
Baseline1 remains1 after each arm. Each arm offers four requests: two chats complete200,
two searches return429 ADMISSION_ENGINE_LIMIT with retrySafe:true and Retry-After1.
Many-context arm2162.888ms, one-context1940.137ms; identical admitted/refused counts,
no fairness refusals, timeout or hidden transport error. Capture467 and independent saved-file
analysis468 both pass. The five-minute per-arm cap and strict oracle are unchanged.
The owned stack stops clean:none with portsClosed:true.
Raw: `tmp/c1-final-aggregate-start-463.txt`, `tmp/c1-aggregate-activation-464.json`,
`tmp/c1-final-aggregate-467.txt`, `tmp/c1-final-aggregate-467/context-many.json`,
`tmp/c1-final-aggregate-467/context-one.json`, `tmp/c1-final-aggregate-analysis-468.txt`,
`tmp/dev-runner/runs/a5e49954-8846-4b3a-986c-1225bb28b617/stop-report.json`.

## Chat-offline full enrichment470 — interrupted, incomplete

The user requested a clean pause before usage exhaustion. Ctrl-C stopped the evaluation
(exit1); the registered stack08cbb26d-68f0-4292-a554-793306df06a4 then stopped clean:none
with portsClosed:true. Health reports ABSENT, no foreign runs; no matching evaluation process
remains. Final recorded progress at1768s: dense, SPLADE and chunk100%, NER404/469,
blocking ner_not_complete. The log contains zero Search query failed lines before interruption;
there is no completed load summary or full-pipeline pass. Retain the data and raw evidence.
On resume, obtain a fresh complete chat-offline pipeline under continuous search before
closing C1. Do not substitute skip-ingest for the pipeline readiness proof.

Raw: `tmp/c1-final-offline-start-469.txt`, `tmp/c1-final-offline-pipeline-470.txt`,
`tmp/c1-offline-first-walk-472.json`, `tmp/c1-offline-pipeline-evidence-474/`,
`tmp/c1-pause-receipt-475.json`,
`tmp/dev-runner/runs/08cbb26d-68f0-4292-a554-793306df06a4/stop-report.json`.
