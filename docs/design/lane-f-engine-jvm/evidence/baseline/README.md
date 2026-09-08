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

## 2. Workflow fixture capture (retaken under PR 0b)

`fixture-pr0b/`: the split-side record the stage E gate diffs against. `side-a/capture-1.json` is
the primary capture; `side-a/capture-{2,3}.json` are its same-build noise captures, each on a fresh
ingest of the fixture corpus (`docs/explanation` + `docs/reference` of this tree, 91 documents plus
the 5 bundled help documents, paths relative to the worktree root), compact chat profile, every
capture-run pin on and recorded in `provenance.pins`, applied sampling echoed per turn, enrichment
complete at capture start (refused otherwise). `side-b/` is a second set of three taken the same
way on the same build, and `gate.json` is the gate over the two: **PASS, 216 equal, 3 allowed, 0 regressions, 3 noisy fields on one side and 2 on the other
(1.4 and 0.9 percent, ceiling 5 percent)**. The noisy fields are the hit lists of 3 of 12 queries
at the rerank-window boundary; all three chat turns (one cancelled) and every other field were
identical across all six captures. `gate-before-dense-leg-fix.json` is the same gate before the
chunk dense leg's tie-break was fixed (7 noisy fields per side), kept to show what that fix
removed.

**Read the PASS for what it is: on ONE build, it is the noise withdrawal by construction.** Both
sides of this record are the same tree, so the only correct verdict is "no difference", and the
gate reaches it partly by *excluding* fields rather than by finding them equal. Without the noise
mask the same six captures give **5 raw cross-side regressions** — `queries.hits[]` on q02, q04,
q05, q09 and q12 — every one of which is withdrawn because the side's own same-build captures
already move it. That is the mechanism working exactly as designed, and it is also the reason the
run cannot certify more than it does: 5 of the 12 query hit-lists are outside the verdict, so a
real regression in one of them would land in the same blind spot. What the PASS establishes is
that the 210 fields the pins DID make deterministic are deterministic. Narrowing that blind spot
is a matter of removing noise sources (the index-time GPU embedding jitter upstream of every
candidate budget), not of tuning the mask.

That is the measured noise floor of split mode under the pins; `round-*-raw-pair-diff.json`
are the raw two-capture diffs of the five pin rounds that preceded it (37, 5, 11, 2, 2, 5
regressions), and `two-per-side-gate-with-stale-side-b.json` is the run that showed two captures
per side under-sample the noise. The capture before PR 0b (`fixture/`) is kept for the record and
is not comparable (request breadth and pins changed).

Capture-run pins (all in `scripts/jseval/lane-f/fixture-pair.sh`): `index.vector.exhaustive_search`,
`justsearch.llm.slots=1`, both rerank deadlines at 60 s, `rerank.top_k=40` with
`rerank.gpu_mem_mb=4096`, `index.hybrid.candidate_limit_max=5000`,
`chunk_collapse_limit_multiplier=50`, leg arbitration and recall-complete splice off; the chat
`sampling` override at temperature 0 with a fixed seed. CPU encoders were measured too slow for
this corpus (38 percent of document embeddings in 15 minutes at four threads) and stay a documented
instrument.

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

## What the first captures found (before PR 0b)

Four captures on one build. Stable across all of them: the cancelled turn's outcome and the rank
order of every hit both sides returned. Unstable: scores (GPU float jitter, max delta 0.0094;
the fixture groups ties within 0.01 and does not diff the score), the index itself when chat
turns run (agent history is indexed, doc count 96 to 97; one capture per fresh corpus, search
half first), top-10 membership at the margin across two index builds (unsorted chunk-leg ties on internal docId), and the chat
trajectory (no temperature or seed control on the agent path; `AgentLlmCaller.java:277-288`). Two open-ended chat
questions were replaced by narrow single-document ones because they exhausted eight iterations
on both profiles. Design section 0 carries the owner items; the fixture keeps the affected
fields `exact` and no class was added after a diff was seen.
