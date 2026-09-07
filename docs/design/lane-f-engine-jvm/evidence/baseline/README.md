# Split-side baseline (design 17.2)

The record the stage E paired rows (16) compare against. Design 17.2 asks for it "on `main`
after PR 0 merges"; it was captured 2026-09-07 on the PR 0 branch head instead, whose tree is
what that merge produces (PR 0 changes no Java; the squash lands the same content). Each item
names the tree, machine and corpus it was taken on. Nothing here is a design fact: 17.7's
starting values are instantiated from these numbers at stage E.

Machine: NVIDIA GeForce RTX 4070 (12 GB), Windows 11, JDK 25.0.2. Tree: lane branch at the
PR 0 head (see the PR), Head flags after PR 0 (`UseSerialGC`, `MetaspaceSize=128m`, no
`TieredStopAtLevel`), dev-runner launch, `--skip-build`.

## 1. Search-quality baselines (jseval, scifact)

`scifact/`: `summary.json`, `manifest.json`, `result_identity.v1.json` of
`python -m jseval run --dataset scifact --modes lexical,hybrid --pipeline --start-backend --clean`
(the eval backend, `runHeadlessEval`; 5,183 documents, 300 queries; 252 s wall clock, of which
37 s primary indexing at 127 docs/s, embedding complete at 210 s, SPLADE at 249 s).

| mode | nDCG@10 |
|---|---|
| lexical | 0.6605 |
| hybrid (cross-encoder on 300/300 eligible, zero silent drops) | 0.7546 |

`relevance-gate.json`: within band against the release pin (0.7572, floor 0.7372).
`perf-gate.json`: no perf baseline is pinned for this mode (`baseline-pinned: skip`), so the
perf ratchet is not part of this record; 16's latency rows use the fixture runs below.

Index-time encoder profiles (`summary.json` `pipeline_timing.inference` and
`encoder_profiles`): embedding 60 batches at 919 ms/batch, SPLADE 83 at 1,283 ms, NER 58 at
456 ms; per-encoder ORT p50/p95 in `encoder_profiles`.

## 2. Workflow fixture capture

`fixture/workflow-fixture-capture.v1.json`: `python -m jseval workflow-fixture capture` against the
dev stack on the fixture corpus (`docs/explanation` + `docs/reference` of this tree, 91 documents
plus the 5 bundled help documents, paths stored relative to the worktree root), **compact** chat
profile (the standard model's 11 GB resident set tripped the dev machine's memory guard twice;
both captures of a paired diff must use one profile, so stage E picks the profile once and
captures both sides on it). Both ordinary turns completed (3 and 6 iterations); the cancelled
turn cancelled.

`fixture/stability-second-fresh-corpus-capture.json` and `fixture/stability-diff.json`: a second
capture on a second fresh ingest of the same documents, same build and profile, diffed under
the fixture's relation. Result: FAIL, 190 equal, 19 allowed, 37 regressions, all with a named
cause and none caused by code: `totalHits` moved by one on q03, q07, q09, q10 and one tie-group
member was replaced on q02 and q10 (the chunk BM25 and SPLADE legs search without a sort, so
equal scores break on Lucene's internal docId, which differs per index build; corrected from the
first HNSW reading by the PR 0b investigation), and both ordinary chat turns hit the iteration
cap this time (the agent samples at temperature 0.7 with no seed, `AgentLlmCaller.java:277-288`). These are the owner items in design section 0 and
17.7; the fixture instrument itself behaved as specified (the differ named each cause, the
health check refused the looping turns as a baseline).

## 3. Brief v2 performance list

`../pr0/after/` is the record (startup, working set per phase, search p50/p95 per mode, GC):
the PR 0 after-run is the same tree and flags. Summary: HTTP 2.56 s and worker ready 7.52 s
warm; Head working set p50 361 MB idle, 439 MB under search plus agent turn; Worker 4.04 GB
idle; hybrid search p50 423 / p95 898 ms after enrichment, lexical-pinned p50 94 / p95 160 ms;
zero full GCs.

## 4. Request-time encoder latencies

`encoder-idle/`: 12 fixture queries times 3, agent idle, no bulk indexing, with debug tracing.
The Head trace times the cross-encoder per call (p50 147 ms, p95 258 ms over 36 calls;
end-to-end p50 754 ms, p95 1,737 ms) but carries no `ms` for `query-understanding` or
`dense-retrieval`, so per-call query NER and query embedding latency are **not observable at
this base**. Stage D2 (request-time paths, the session gate) is where those two calls get
their own timing; the 16 request-time-encoder row is measured there on both sides.

## 5. Worker restart rate

From every dev-runner run recorded under `tmp/dev-runner/runs/` (183 runs with a `worker.log`,
2026-08-11 to 2026-09-07): each log carries exactly one "Starting Knowledge Server" banner, no
rotated `worker.log.N` exists, and no Head-side restart line occurs in any `backend.stdout.log`.
Observed Worker restarts in development: 0 in 183 runs. The soak at stage E is the first place
a rate under load is measured; this is the reference.

## What the captures found

Four captures on one build. Stable across all of them: the cancelled turn's outcome and the rank
order of every hit both sides returned. Unstable: scores (GPU float jitter, max delta 0.0094;
the fixture groups ties within 0.01 and does not diff the score), the index itself when chat
turns run (agent history is indexed, doc count 96 to 97; one capture per fresh corpus, search
half first), top-10 membership at the margin across two index builds (unsorted chunk-leg ties on internal docId), and the chat
trajectory (no temperature or seed control on the agent path; `AgentLlmCaller.java:277-288`). Two open-ended chat
questions were replaced by narrow single-document ones because they exhausted eight iterations
on both profiles. Design section 0 carries the owner items; the fixture keeps the affected
fields `exact` and no class was added after a diff was seen.
