---
title: jseval Pipeline Reference
type: reference
status: stable
description: "CLI reference for the jseval evaluation, pipeline profiling, and throughput benchmarking toolkit."
---

# jseval Pipeline Reference

`python -m jseval` is the canonical **agent-only** tool for dataset
evaluation, pipeline profiling, and throughput benchmarking. It is not
designed for human developers — all output and progress reporting is
optimized for machine consumption. The `duplicate-review-label` command is the narrow exception: it opens
a private native review window for a human or model-assisted labeling campaign. Agents should use jseval for ALL
eval/profiling work instead of ad-hoc bash/node scripts. When jseval
lacks a feature, improve it (`scripts/jseval/`) rather than building
workarounds.

Unless a command says otherwise, run the examples below from `scripts/jseval/`. Paths beginning with
`tmp/` therefore resolve to the repository's gitignored `scripts/jseval/tmp/` data root, including the
mandatory private destination for text-bearing duplicate review packets.

## Quick Reference

### Ingest + eval (most common)

```bash
# Ingest SciFact, wait for ALL enrichments, run queries
python -m jseval run --dataset scifact --modes lexical,hybrid --pipeline

# Quick iteration (10 queries, skip ingest)
python -m jseval run --dataset scifact --modes hybrid --max-queries 10 --skip-ingest

# Full lifecycle: start backend, clean data, ingest, wait, query, stop
python -m jseval run --dataset scifact --modes lexical --pipeline \
  --start-backend --clean --timeline tmp/timeline.tsv

# Full inference/VDU: first start and activate an owned dev stack with justsearch-dev MCP.
# The eval backend's read-only settings cannot activate inference; --start-backend --llm rejects.
python -m jseval run --dataset multihop --modes hybrid --pipeline --base-url <owned-api-url>

# From YAML config file
python -m jseval run --config eval-run.yaml --start-backend
```

### Compare runs

```bash
# Compare two eval runs for regression
python -m jseval compare tmp/eval-results/run-a tmp/eval-results/run-b

# Fail CI on regression (includes pipeline timing comparison)
python -m jseval compare run-a run-b --fail-on-regression
```

### Benchmarks

```bash
# Indexing throughput (Claim B)
python -m jseval ingest-bench --corpus-dir tmp/eval-corpora/scifact

# Engine-only indexing (Claim A)
python -m jseval engine-bench --corpus <path>

# kNN latency
python -m jseval knn-bench
```

### Duplicate prevalence (descriptive)

The raw `mixed/format-breadth-v1` sibling corpus is a frozen 33-file, single-source-per-format real-input
cohort for deterministic production-path characterization: 16 untouched CMU Enron RFC822 messages, all
nine RTF members in the pinned Govdocs1 000/001 archives, and eight sorted nested ZIP members from
NapierOne `ZIP-DEFLATE-tiny`. Its recipe reports one conservative collection-level producer/source count
for each covered format; it is not evidence of producer-diverse or representative robustness. It is
separate from immutable `mixed/realdocs-v1`; raw bytes remain gitignored and cache-backed. Ordinary
materialization requires the committed observed source/member manifest and validates the materialized
realdocs tree against its own immutable 620-file manifest before comparing hashes. Source drift, member
drift, unsafe archive paths, count/extension mismatch, Napier sidecar mismatch, or any SHA-256 overlap
fails closed:

```bash
python -m jseval corpus-fetch-format-breadth
```

`--write-manifest` is only for a deliberate first-source observation or reviewed recipe revision. It writes
the exact source/member hashes that must be reviewed before commit; it is not a drift-acceptance switch for
an already observed manifest. The sibling intentionally leaves MBOX, EPUB, ODF, and optional MSG as
deterministic-only coverage until separately provenance-cleared real sources exist.

`duplicate-prevalence` measures either the immutable, pre-dedup Enron **source-body proxy** or a
strictly reconciled **production-extracted** snapshot. The Enron v2 path does not claim Worker/Tika
behavior: it streams the complete archive, commits raw → parsed → eligible → retained stage counts, and
computes exact raw-body and normalized-content censuses over every eligible occurrence before first-SHA
retention. The memory-heavy analyzer runs only on a deterministic uniform reservoir. The production
adapter obtains parent ids and stored content through Head-to-Worker RPC bridges, never through
Head/Python Lucene access. Both modes emit no source paths, archive member names, document ids, or text.

```bash
python -m jseval duplicate-prevalence \
  --input-spec tmp/enron-duplicate-prevalence-input.json \
  --out tmp/enron-duplicate-prevalence.json

# In the same analysis pass, optionally emit the text-bearing packet needed for
# human calibration and holdout labels. This file is sensitive and local-only.
python -m jseval duplicate-prevalence \
  --input-spec tmp/enron-duplicate-prevalence-input.json \
  --out tmp/enron-duplicate-prevalence.json \
  --review-packet-out tmp/enron-duplicate-review.local.json \
  --review-per-stratum-quota 2 \
  --review-calibration-fraction 0.5 \
  --review-seed 897

# Open the blinded human-labeling window. The state file contains pair ids and
# decisions only; it never contains the packet's text or experimental strata.
python -m jseval duplicate-review-label \
  --packet tmp/enron-duplicate-review.local.json \
  --labels-out tmp/enron-duplicate-review-labels.local.json \
  --triage tmp/enron-duplicate-review-model-triage.local.json

# After every pair is labeled, select the threshold on calibration only and
# evaluate that one frozen threshold once on the disjoint holdout.
python -m jseval duplicate-review-decide \
  --packet tmp/enron-duplicate-review.local.json \
  --labels tmp/enron-duplicate-review-labels.local.json \
  --triage tmp/enron-duplicate-review-model-triage.local.json \
  --out tmp/enron-duplicate-review-decision.local.json

# Apply the saved decision to its original analyzer output without scoring again.
python -m jseval duplicate-prevalence-apply-decision \
  --prevalence tmp/enron-duplicate-prevalence.json \
  --decision tmp/enron-duplicate-review-decision.local.json \
  --expected-decision-hash <recorded-canonical-decision-sha256> \
  --out tmp/enron-calibrated-prevalence.json

# Bind a production snapshot to one query run and decorate its result sidecar.
# The raw corpus resolved by the run must exactly match source.raw_root in the spec.
python -m jseval run --dataset mixed/realdocs-v1 \
  --modes lexical,vector,splade,hybrid \
  --start-backend --clean --fresh-index \
  --duplicate-prevalence-input-spec tmp/realdocs-duplicate-prevalence-input.json

# On a fresh owned full-inference dev stack, ingest and await VDU plus re-enrichment.
python -m jseval duplicate-prevalence \
  --input-spec tmp/realdocs-duplicate-prevalence-input.json \
  --ingest --wait-timeout-seconds 7200 --timeline tmp/realdocs-readiness.tsv \
  --out tmp/realdocs-duplicate-prevalence.json
```

The input is strict `jseval.duplicate-prevalence-input.v1` or `.v2` JSON with exactly `schema`, `source`,
and `analysis`. New full-scale Enron runs use v2: `source.kind=enron-eligible-body-proxy` supplies a
dedicated one-file `raw_root`, canonical relative `tarball`, nonnegative `min_words`, and required
`eligible_sample` using `algorithm-r-reservoir-without-replacement-v1`, a size from 1 through 5,000, and a
nonnegative sampling seed. Legacy v1 Enron execution remains readable and testable but is superseded for
full-archive runs because it retains every eligible body. `source.kind=production-extracted` remains v1 and
instead supplies `raw_root` plus an explicit-port HTTP loopback `base_url`; production mode reads the
optional per-boot token only from `JUSTSEARCH_SESSION_TOKEN`, never from the input or output artifact.
`analysis` supplies every pinned analyzer field: shingle width, fixed 64-bit SimHash configuration,
increasing Jaccard threshold sweep, exhaustive-slice size, bootstrap draws, seed, and candidate-pair limit.
Unknown or missing fields, archive traversal/ambiguity, candidate truncation, and corpus drift fail closed.

`duplicate-review-decide` requires the packet, completed text-free labels, and their bound model-triage
sidecar. A predicted near duplicate must come from the SimHash candidate frame and meet the full-shingle
Jaccard threshold. The command selects the maximum weighted calibration F1, breaking ties by higher weighted
precision and then higher threshold, and evaluates only that threshold on holdout. It reports raw and
Horvitz–Thompson-weighted confusion, precision, recall, F1, and a deterministic within-stratum bootstrap.
Bootstrap output includes requested, valid, and invalid draw counts; if any planned draw has an undefined
metric, that metric receives no percentile interval instead of silently dropping the draw. `UNCERTAIN` and
`ABSTAIN` remain counted but excluded from binary metrics. The aggregate contains no text, paths, pair ids, or
format ids, but it remains under `scripts/jseval/tmp/` pending publication review. Output may not resolve to
any input. An existing output is reusable only when its self-hash is valid and its canonical JSON bytes,
including numeric and Boolean types, are identical to the newly computed artifact. Model-assisted
metrics are conditional on those labels; they do not quantify label error or archive-population prevalence.

`--review-packet-out` reuses the private observations from that same analysis or production snapshot; it
does not extract or scan the corpus twice. The command binds the packet to the aggregate artifact hash,
observation commitment, measurement identity, and exact analyzer configuration. Candidate-connected
families are partitioned before pair formation so calibration and holdout remain disjoint. The packet
contains document text and null label fields, is marked `local-review-text` / `uncommitted-local-only`, and
must never be committed or published. The destination is mechanically restricted to a file under the
repository's gitignored `scripts/jseval/tmp/` root (with symlinks resolved); repository-visible and external
paths are rejected before analysis. The aggregate `--out` remains text-free, and the two destinations must
differ. Review options default to quota 2, fraction 0.5, and seed 897; set them explicitly when preregistering
a labeling campaign.

`duplicate-review-label` is a native Tk window and starts no HTTP server. It verifies the packet hash and
analyzer binding before displaying text, hides split, candidate/control frame, similarity, stratum, format,
token counts, and opaque ids, and uses packet-bound deterministic hashes for both pair order and left/right
orientation. Closing and reopening the command resumes at the first unlabeled pair in the same blinded order.
Every decision is atomically autosaved under the same private root to a strict
`jseval.duplicate-review-labels.v1` artifact. That artifact contains only packet/analyzer hashes, presentation
method identifiers, pair ids, and labels; it contains no text, paths, notes, labeler identity, timestamps, or
experimental metadata. An existing state file with an altered hash, vocabulary, binding, population, or order
is rejected before any packet text is shown. The global `--json` mode is rejected for this interactive command.

For a model-assisted campaign, the optional `--triage` file is a strict, text-free
`jseval.duplicate-review-model-triage.v1` sidecar. It binds the pre-triage and post-triage label-state hashes,
packet/analyzer hashes, exact reviewed pair ids, and the blinded high-confidence-binary triage method. Model
triage may auto-assign only `NEAR_DUPLICATE` or `NOT_NEAR_DUPLICATE`; every ambiguous pair remains unlabeled
with disposition `HUMAN_REVIEW`. Passing the bound sidecar to the GUI displays only that human-review queue.
Previously saved human judgments are preserved. The sidecar must accompany downstream scoring so results are
described as model-assisted rather than wholly human-labeled.

Use exactly four judgments. `NEAR_DUPLICATE` means the same substantive content despite wrappers, quoting, or
formatting; `NOT_NEAR_DUPLICATE` means substantively distinct content. `UNCERTAIN` means both texts are
reviewable but the semantic judgment is ambiguous. `ABSTAIN` means no judgment is possible because a text is
unreadable, truncated, or otherwise not reviewable. Downstream threshold selection must exclude the two
nonbinary labels and report their counts. It must select a threshold from calibration labels only, then evaluate
that frozen threshold on the disjoint holdout; the label command intentionally performs neither operation.

`duplicate-prevalence-apply-decision` requires the externally recorded decision hash, verifies both input
hashes and the complete decision's evidence accounting, and requires the decision's analyzer hash
to match the complete original prevalence artifact. It selects one existing threshold-sweep row and retains
the original denominators, candidate-recall evidence, and descriptive intervals. It neither selects a new
threshold nor evaluates holdout labels again. The selected report binds both input artifact hashes and
rejects changed output or overwriting either input. Enron sample calibration does not validate another
corpus, extractor, or sample, and does not estimate archive-wide near-duplicate prevalence. Query-result
redundancy currently measures normalized-content-exact clusters only; it does not consume this near-duplicate
decision. Uncalibrated production cohorts retain an explicit near-duplicate non-decision.

Production capture is capped at 50,000 source documents. A strict raw manifest is the source-population
authority; indexed extraction successes and explicitly declared terminal parser failures must partition it
exactly. A terminal exclusion declaration binds the relative path, source SHA-256, expected failed-job state,
exact expected error message, and the closed reason `corrupt-or-unsupported-parser-input`. The adapter
revalidates each declaration against the raw bytes and `/api/indexing-jobs/failed`, rejects undeclared or
stale failures, and persists only aggregate exclusion counts and reasons. It never treats a missing source as
an analyzable document or emits the declaration's path, digest, or error text.

Immediate production capture remains read-only. A positive `--wait-timeout-seconds` waits through the
existing readiness engine before strict capture; `--ingest` explicitly registers the source root on that
already-owned backend and requires a positive wait. Before registration it inspects the complete existing
document-id set and rejects foreign identities, malformed exports, or indexed terminal exclusions. Empty
indexes and valid partial corpus resumes are accepted; final capture still verifies all identities and
source revisions. Inspection, registration, and readiness share the requested time budget and session token.
This path awaits VDU and subsequent enabled enrichment
stages, including the declared terminal-failure disposition that ordinary pipeline readiness cannot accept.
Every readiness wait fails immediately with `indexing_loop_failed` on a fresh `indexState=FAILED`
snapshot. Stale snapshots retain the freshness rules; ordinary document `ERROR` can still satisfy
the production predicate when every terminal failure matches its exact declaration.
Chunk completion requires zero pending/failed dense work and complete enabled chunk-SPLADE accounting;
the search-readiness tolerance of 99.9% cannot hide an unfinished production chunk. Registration forwards
`JUSTSEARCH_SESSION_TOKEN` and shares the requested time budget with readiness polling. Generated outputs
for this wait/ingest path must reside outside the raw corpus so the watcher cannot ingest them.
`--timeline` records aggregate counters, including `vdu_pending` and `vdu_processing`; missing VDU values
remain blank rather than being reported as zero. The caller owns stack startup, inference activation,
clean data-directory selection, and shutdown through the dev-stack workflow.

An isolated full-inference measurement must start with zero indexed documents. Normal application
startup adds bundled help documents, which correctly fail this adapter's exact corpus reconciliation.
Use the normal dev runner with a fresh data directory and append `-Djustsearch.eval.mode=true` to its
ambient `JAVA_OPTS`. Keep normal writable settings: `runHeadlessEval` separately pins settings to
`IN_MEMORY`, which prevents inference activation. The eval property alone skips bundled help and
enables eval diagnostics; it does not disable inference or change the production extraction pipeline.
After MCP `quick_health` and `preflight`, the existing CLI route is
`node scripts/dev/dev-runner.cjs start --json --dist-from <worktree> --data-dir <fresh-directory> --clean none --session-id <session> --lease-duration-sec 7200 --chat-profile standard --api-port 33221`.
Scope the environment change to that launcher process and preserve existing JVM options. The CLI uses
the same ownership/lease register as MCP; MCP startup currently has no ambient-JVM-option argument.
Verify MCP ownership/readiness, an initial zero-document index, and successful standard-model activation
before registering the corpus. Do not remove reserved help documents, forge the help-ingestion marker,
or filter an unexpected extra document out of capture. MCP shutdown retains ownership enforcement.

For a corpus requiring VDU, activate the intended model profile through the dev MCP, then use
`api_call {method:"POST", path:"/api/inference/mode", body:{mode:"indexing"}}` on the owned run.
Leaving interactive inference online defers automatic VDU. In indexing mode the production
scheduler waits for five minutes without search, suggestion, or folder-listing activity and for
normal energy availability, then starts the visual batch and parks inference for subsequent
enrichment. Status/readiness observation does not constitute user activity. The duplicate capture
command waits for this lifecycle; it does not change inference mode itself. Resume a preserved
index with a positive wait and omit `--ingest` when its watched root is already registered.

`/api/status`, `/api/debug/state`, and the Worker's complete id set must agree with that disposition
accounting. The evaluation-only document-id export is one immutable request: callers must send offset zero and
a limit no greater than 50,000, and the endpoint rejects continuation offsets rather than pretending that
separate Worker reader generations form one snapshot. The production debug envelope's nested `worker`
projection is authoritative, with the legacy flat shape accepted only for compatibility. Head and Worker must
both be ready before and after capture; queues and writers must be quiescent, search and ingest generations
identical, and build/generation/commit/count identity unchanged after capture. The aggregate lifecycle may be
`DEGRADED` only for the exact `inference.offline`
component disposition while Head and Worker remain ready and no VDU work is pending or processing. On the
owned live-HTTP path, a bounded parent-id settle waits only for the final expected cohort after pipeline
readiness; all subsequent before/after lifecycle, generation, manifest, failed-job, and complete-id checks
remain strict.

Every preview page must make exact UTF-16 offset progress without splitting a Unicode scalar and retain stable
`totalChars`, parser/policy/status metadata, and the Worker-stored source SHA-256. The Worker reads content
and provenance through one Lucene searcher and exposes its canonical `content_sha256` revision as
`contentSha256`. Every page must retain that revision, and the assembled UTF-8 text must hash to it; missing
revisions, malformed Unicode, and same-source VDU changes fail closed. The source digest must equal
the strict raw-manifest row, so a same-path/same-count stale index is rejected. `SUCCESS_PARTIAL` is accepted
only when the Worker reports `contentTruncated=true`, and the extraction snapshot v2 discloses partial-success
and terminal-exclusion counts in both reconciliation and top-level denominators. Any inconsistent status,
truncation flag, or disposition fails closed. Run-local opaque ids, path aliases, extracted text, the strict
path-bearing manifest digest, terminal-exclusion declarations, HMAC key, and Ed25519 private key remain in
memory. The persisted corpus signature is a keyed commitment to the strict manifest, not a dictionary-testable
path hash; only the aggregate artifact, committed provenance digest, alias HMAC, verification key, and
aggregate disposition accounting may persist.

Production result decoration is a CLI-only `run` option; it is intentionally not accepted from YAML. It
requires a nonempty query-mode set, an owned `--start-backend --clean --fresh-index` lifecycle, normal
ingestion, and an exactly matching raw root and base URL. Index-cache adoption and
`JUSTSEARCH_CORPUS_SIGNATURE` are forbidden. Capture happens after readiness and before queries; after
queries the raw manifest, lifecycle/generation identity, and complete Worker id set are revalidated against
that in-memory snapshot, then `write_run` preflights and emits the aggregate
analysis, signed result-identity mapping, and summary/manifest corpus bindings. The required
`staged_recall_accounting` projection must succeed before history publication. Worker telemetry is not
mirrored into a private run directory because indexing spans contain source paths; it remains only in the
local backend scratch directory. The ordinary run path remains
identity-only and unchanged. A standalone production aggregate can still be written with
`duplicate-prevalence`, but later decoration from that artifact is deliberately unsupported.

The source SHA-256 is a stored Worker field added by this contract, so existing indexes cannot satisfy it:
use a complete clean reindex. For each newly admitted or reindexed source the Worker performs two additional
sequential full-file reads—immediately before and after extraction—to bind the stored digest to stable source
bytes. Unchanged documents are rejected before these reads. The reads currently sit outside the parser
timeout; real-corpus runs must record their throughput impact before this mechanism is considered a standing
default rather than a measurement-only provenance guard.

The versioned output validates against `scripts/jseval/duplicate-prevalence.v1.schema.json`. Byte-exact,
normalized-content-exact, and near-duplicate results remain separate. Enron v2 labels the exact census
`all-eligible-body-occurrences-before-sha-retention` and every analyzer block
`frozen-uniform-eligible-body-sample`. Only the exact census supports archive-level prevalence; sampled
exact and near-duplicate blocks are descriptive, and archive-level near-duplicate prevalence is explicitly
unmeasured because unsampled mates would bias a document-reservoir estimate. SimHash banding guarantees
candidate coverage only within the configured Hamming radius; the artifact reports measured Jaccard
candidate recall on its deterministic exhaustive slice. Component bootstrap intervals are stability
descriptions, not population confidence intervals. Production content-exact statistics are descriptive
only. The base analyzer artifact keeps the near-duplicate decision `UNDECIDED`; a separate bound
`duplicate-review-decide` artifact can resolve the threshold after disjoint calibration and holdout labels
exist without rewriting the census artifact.

### Standing ratchets (engine-quality gates)

Five **relative** regression ratchets (no absolute SLO) catch silent engine/agent-utility regressions;
run them after engine/inference/MCP-surface edits. All share
`jseval/ratchet_kernel.py` (load baselines → resolve run → compare → report) and project their floors from a
canonical source (never hand-typed).

```bash
# Relevance (nDCG@10 mean) — floor projects from release.v1.json
python -m jseval relevance-gate --data-dir <dir> --dataset beir/scifact
# Performance (CE-stage p50 latency / throughput / resident footprint) — floor projects from release.v1.json
python -m jseval perf-gate      --data-dir <dir> --dataset scifact
# Recall-leak (cross-mode leak_rate — a leg's correct answer dropped before the judge; needs leg-modes run)
python -m jseval leak-gate      --data-dir <dir> --dataset beir/scifact
# LLM-generation latency/throughput (TTFT / e2e / tokens-sec) — needs a bench, not an eval run; AI must be active
python -m jseval llm-bench --base-url <api-url> --output-dir <d> && python -m jseval llm-gate --bench-file <d>/llm-bench.json
# Agent-utility (condition-C absolute-accuracy floor on the util-smoke smoke corpus — tempdoc 673; DETECTION,
# not the near-null realistic with-tool-vs-baseline delta 624 reports) — reads a utility-comparison.v1 RECORD
# directly (not a run-dir projection); costs a real paid agent-call run, so it's deliberate/periodic, not
# routinely re-run like the four above.
python -m jseval utility-gate --record <utility-comparison.v1.json> --corpus golden/util-smoke
```

Re-pin after a deliberate change: `perf-gate --update-baseline` (re-pins from the run); `leak-gate-derive --datasets
<slugs>`; `llm-gate --bench-file <f> --update-baseline`; `utility-gate --record <f> --update-baseline` (or
`utility-gate-derive --records <f1,f2>` for multiple corpora at once). Relevance re-baselines when
`release.v1.json` is recomposed (`jseval release --latest-per-dataset`). Floor files:
`scripts/jseval/{relevance,perf,llm-gen,utility-ratchet}-baselines.v1.json` + `leak-gate-baselines.v1.json`.
Exit codes: 0 = within band, 1 = regression, 2 = data/projection missing.

### Workflow fixture — semantic non-regression across two backend builds

Ranking metrics say nothing about *what a client was handed*. `workflow-fixture` captures a fixed
set of search queries and chat turns — returned evidence (doc/chunk ids, source identity,
truncation points), citations, and cancellation behaviour — to a JSON artifact, and diffs two
captures taken from two backend builds. It is **mode-agnostic**: it speaks HTTP to a running
backend and knows nothing about how many processes are behind it.

```bash
# On each build, against a backend serving the same corpus:
python -m jseval workflow-fixture capture --base-url <api-url> --corpus-root <abs-root> \
    --out <dir>/workflow-fixture-capture.v1.json
# Then compare:
python -m jseval workflow-fixture diff --baseline <a>.json --candidate <b>.json
```

Exit codes: 0 = pass, 1 = regression / a field present in one capture and missing from the other /
an unhealthy capture, 2 = usage or definition error. `--report-out` writes the full structured
result. `--skip-chat` captures the search half only (AI offline) — that artifact is for
*inspection*, not gating: the capture-health check below fails a diff whose chat section is empty
on both sides.

**The equality relation is declared before any run**, in `scripts/jseval/lane-f-workflow-fixture.v1.json`
(`fields`): every captured field path maps to `exact` or to exactly one **allowed-difference
class**, from a closed set of three.

| class | means | how the differ compares |
|---|---|---|
| `new-reason-code` | a reason code that did not exist in the baseline | code **sets**: baseline ⊆ candidate. A code only the candidate has is allowed; one the baseline had and the candidate lost is a regression. For a dict-valued field the key is carried into the code (`"<key>=<code>"`), so a code that *relocates* to another stage is a regression, not an unchanged set |
| `equal-score-order` | ordering of equal-score hits | the value is a score-tagged list; consecutive hits within the fixture's `scoreTieEpsilon` form a group, compared as an unordered multiset of **whole** values. A tie permutation is allowed; a changed member is a regression. A `None`/non-numeric score is a shape violation, not a universal tie |
| `generative-text` | generative text, nominally under a fixed seed | any difference allowed |

**One hit is one value.** `queries.hits[]` carries the entire per-hit projection — id, source
identity, chunk span, excerpt spans, matched fields, stage ids — as a single dict per position,
not one score-tagged list per attribute. Independent lists let the differ permute a hit's
attributes independently of its identity, so a candidate whose chunk spans swapped inside a tie
group while the ids stayed put read as an allowed reordering. One value per hit makes that a
regression. The one sub-key that keeps `new-reason-code` semantics is `extractionReasonCode`
(module constant `_HIT_REASON_CODE_KEYS`): tie-group members are paired on their exact keys first,
and only then is the subset rule applied.

**Scores are not byte-stable, so "equal" is instantiated as within-epsilon.** Design 16 names the
class "ordering of equal-score hits"; the width of a tie is a mechanism detail (17.6). Measured on
two consecutive captures of **one** build against **one** index: over 118 identity-matched hits the
score delta was non-zero on 111, max 0.009442, mean 0.001658 — GPU float nondeterminism in the
dense / cross-encoder legs. In the same data *no* adjacent score gap was zero, so exact-equality
grouping yields all-singleton groups and permits nothing. That calibration was against the
DELIVERED score and gave `0.01` — just above the observed max jitter, below all but 3 of the 108
adjacent gaps (smallest 0.003652). It is superseded on both counts: the grouping basis is now the
cross-encoder score, whose measured cross-build jitter is `0.0000`, and the committed epsilon is
`0.001`, derived from the CE-score gap distribution (below). Consequences: `queries.hits[].score` is **not** a declared field and is
not in the diffed capture (observed values go to the capture's non-diffed `observed` block); the
differ never compares scores, only the groups it derives from them; and the tie-group partition is
consulted **only when the delivered order actually changed** — an unconditional partition
comparison is itself jitter-sensitive (measured: one query's hits were identical in identity *and*
order, but a gap moved 0.010470 → 0.009788 across the epsilon and the query read as a regression
with nothing changed). Grouping is over *consecutive* hits, so it is deliberately conservative: it
under-permits, never over-permits.

**Which score is grouped on: the cross-encoder's, because that is the delivered sort key.** The
list arrives in the cross-encoder's order — `KnowledgeSearchEngine.java:1077` applies
`applyRerankOrder(…)` — while the score carried *on* the hit is the pre-rerank fusion score,
freshness-decayed afterwards without re-sorting (`SearchResultMapper.java:129-134`, on for `hybrid`
via `SearchPipelinePresets.java:53-54`). Grouping on that number grouped a CE-ordered list by an
unrelated key: measured non-monotone in **all 12 queries of both** 2026-09-07 captures, 7–11
inversions per 20 hits. The CE's own per-hit score is on the wire as a `CROSS_ENCODER` `HitStage`
(`SearchTraceMapper.java:44-51`, `contracts/wire/knowledge.proto:289-294`) and is not gated by
`include_detail`, so the capture reads it as the score-tagged `score`. The delivered and fusion
scores stay in the non-diffed `observed` block (`scores`, `fusionScores`), with `scoreBasis`
recording per query which number grouped. A query the CE did not score every hit for (a
lexical/TEXT fallback, or hits outside the rerank window) falls back to the delivered score for the
**whole** query — a list mixing two bases would have epsilon comparing different scales.

**The scale is measured, not assumed.** The cross-encoder emits raw,
unsigmoided model output (`CrossEncoderReranker.java:369-379` returns the logits directly; the only
sigmoid in that module belongs to `CitationScorer`, a different class), so the epsilon — derived
against the roughly 0–1 *delivered* score — had to be re-checked against the real scale. Measured
on a real `debug:true`, `limit:20` response over `docs/explanation`: 20 cross-encoder scores
spanning **−3.0566 … +0.2603** (span 3.32), **17 of them negative**. It is therefore neither a 0–1
score nor the ±11 a generic ms-marco model would suggest; reading only the top three hits (0.26,
0.14, 0.019) makes it look normalised, which is an artefact of the best hits sitting near zero. On
that scale `0.01` leaves only 2 of the 19 adjacent gaps inside the epsilon (smallest 0.001404) —
the same profile the original calibration had (below all but 3 of 108 gaps). That kept it
defensible; the wider six-capture gap distribution below is what later moved it to `0.001`. Still
unmeasured at this point was the cross-encoder's *jitter* across two runs: that needs a pair
with `observed.crossEncoderScores` on both sides, then the per-hit delta over identity-matched hits.

**A dropped cross-encoder fails capture health.** When the stage is skipped for a drop reason
(`INFERENCE_FAILED`, `DEADLINE_EXCEEDED`, `MODEL_NOT_LOADED`, `RPC_FAILED`, `UNKNOWN`) the results
keep their fusion order, so the capture records a *different pipeline's* output. Both sides of a
pair degrade together, so the diff gets **quieter**, not noisier — the dangerous direction. Pair run
3 read as "2 regressions, nearly clean" while every query showed `cross-encoder: skipped /
INFERENCE_FAILED`, caused by a `JUSTSEARCH_RERANK_TOP_K=100` pin that pushed the rerank batch from
20 to 100 documents into an ONNX Runtime arena failure. That pin has been removed; the health check
makes a green earned by disabling the reranker impossible to mistake for a clean pair.

**`equal-score-order` covers the tie group straddling the rank-K cutoff, because equal-score hits
beyond K are unobserved at K** (K = the query spec's `limit`, 10 in the shipped fixture). Measured
on the 2026-09-07 paired capture: `q06` (tie-group score 0.121) and `q10` (0.236) each reported
"tie group N is not a permutation" with *one* member of the bottom tie group differing — index-time
GPU embedding jitter moved near-tied chunks across the rank-10 cutoff, so a swap with a member
sitting at rank 11 read as a membership change. That is still "ordering of equal-score hits", now
across the K boundary, so it stays in the existing class; **no fourth class was added**. Capture
side: the request asks for `2 * K`, and `hits[]` stores the top K *plus* the remainder of the tie
group holding rank K, dropping everything after that group. `hitCount` deliberately stays the
**top-K** count (`min(returned, K)`) — it is diffed `exact`, and a legitimate 11-vs-12 cutoff-group
size must not fail on it; `hitsCaptured` / `hitsCompared` go in the capture's non-diffed `observed`
block for the same reason. Diff side: groups *above* the cutoff group keep the rules described
above, and the cutoff group is compared as an unordered **set** over its full observed membership —
any permutation allowed, a member absent from the other side's group is a regression, and the
`extractionReasonCode` subset rule still applies to members present on both sides. It **fails
closed** when the cutoff group runs to the end of a full 2K response (or an older capture records no
`hitsCaptured`): the group may continue past what was observed, so its membership is unproven.

**The capture requests `captureLimitMultiplier * K` (4K), and the multiplier is declared.** K is
what gets compared; the extra breadth does two jobs. It makes the cutoff tie group observable
whole, and it puts the **cross-encoder window** well past the compared K. The window is the request
limit (`searchLimit = max(requestedLimit, rerankConfig.topK())`, `KnowledgeSearchEngine.java:625-629`;
window = `min(topK, results.size())`, `:969-970`), so at 2K it ended *exactly* at the captured set
and a chunk whose fusion rank jittered across rank 20 was reranked on one build and not the other —
the three unmatched hits at ranks 7–8 of pair run 4. At 4K a hit near compared rank 10 has to move
~30 fusion ranks to fall out of the window. `limit` also feeds the candidate budget / collapse
limit / rerank pool, so the top-K set at one multiplier is not guaranteed identical to another's:
both sides of a pair must use the same one. Re-capture both sides.

**The rerank window and its arena move together, or the reranker dies.** `JUSTSEARCH_RERANK_TOP_K`
sets *both* the wire limit and the cross-encoder batch, and that batch is one un-split tensor padded
up to a bucket from `{4,8,16,24,32,48,64}` (`CrossEncoderReranker.BATCH_SIZE_BUCKETS`). Pair run 3
pinned `top_k=100`, which is outside the ladder entirely, and exhausted the default 2048 MB arena on
every query. The capture now pins `JUSTSEARCH_RERANK_TOP_K=40` (pads to bucket 48) with
`JUSTSEARCH_RERANK_GPU_MEM_MB=4096`, sized to hold the per-row headroom the working configuration
had rather than guessed:

| docs | padded bucket | arena | MB per padded row | outcome |
|---|---|---|---|---|
| 20 | 24 | 2048 | 85.3 | worked |
| 40 | 48 | **4096** | **85.3** | this capture |
| 100 | 100 | 2048 | 20.5 | `INFERENCE_FAILED` |

The active reranker is 12-layer / 12-head / 768-hidden (`models/onnx/reranker/config.json`,
`NewForSequenceClassification` — not the MiniLM backup), so one layer's attention scores at bucket
48 and sequence 512 are roughly 576 MB: inside 4096, not comfortably inside 2048. If the reranker
drops again the ladder steps **down**, not up — `top_k=30` (bucket 32) at 3072 MB, else the known-good
20 at 2048.

**CPU encoders work, and are too slow to capture with (measured).** The theory holds: CUDA
kernels reduce in a nondeterministic order, so the same text embeds to slightly different vectors
on two runs, and those vectors move fusion candidates *upstream of every candidate budget* — which
is why no budget pin removed it. ONNX Runtime on the CPU execution provider is bit-deterministic
for a fixed thread count. But measured on this corpus: after the 15-minute enrichment wait,
document embeddings were at **38%** and **0 of 1,283 chunks** had been embedded, at four intra-op
threads. A cycle would exceed an hour and a pair two.

So `fixture-pair.sh` does not set them. The keys stay as documented instruments and the capture
*records* them when they are set, so a bit-stable capture is legible as one, but they are not
required and a GPU capture is not failed for lacking them. The per-role provider keys
(`justsearch.embed.gpu.enabled`, `justsearch.splade.gpu_enabled`, `justsearch.ner.gpu_enabled`,
`justsearch.rerank.gpu.enabled`, `justsearch.bgem3.gpu_enabled`) already existed;
`justsearch.onnxruntime.intra_op_threads` was added because `SessionOptionsApplier.applyBase` never
set the intra-op count for production sessions, and on CPU that count decides a GEMM's reduction
order and therefore an embedding's low bits. GPU encoder jitter is handled instead by the noise
pair below.

## Gating with a noise pair

**Determinism is measured, not assumed.** Five paired rounds established that some fields are
simply not stable on this stack. The gate therefore captures each side **N times on one build**
(default three), each on a fresh ingest of the same corpus, and those captures *define what
"within noise" means for each field*:

```bash
bash scripts/jseval/lane-f/fixture-pair.sh tmp/gate/split  compact 33221 3   # capture-1..3
bash scripts/jseval/lane-f/fixture-pair.sh tmp/gate/single compact 33221 3
bash scripts/jseval/lane-f/fixture-gate.sh tmp/gate/split tmp/gate/single report.json
```

`fixture-gate.sh` uses **every** `capture-*.json` in each directory (`capture-1` is the primary),
so adding a cycle deepens the gate with no other change.

**Why three and not two.** Gate run 3 had `q05` and `q12` differ across sides while being *stable
within both sides' two-capture pairs*, on one build. Two draws under-sample: a field can agree once
and disagree on the next, so the pair reported a stability it had not established. A field is
therefore noisy when **any two** of a side's captures disagree — every pair is compared, so a field
counts as stable only when every capture agreed with every other. A side that brings only one
capture is **refused** (`no noise captures on <side>`) rather than judged stable by default, which
is the direction that silently passes.

`diff` gains repeatable `--baseline-noise` / `--candidate-noise`. Per compared field it first asks
whether the field is stable *within* a side, then whether it differs *between* sides. A field any
two of a side's captures disagree on is reported `noisy-baseline` / `noisy-candidate` /
`noisy-both`, counted under `counts.noisy`, and **excluded from the regression verdict** — a difference the same build
produces against itself cannot evidence a difference between two builds. The verdict the gate
*would* have returned is kept on the entry as `crossSideStatus`, so nothing is hidden. Without the
two flags, `diff` behaves exactly as before.

This is the alternative to two worse options. Declaring the unstable fields non-exact would blind
the gate to real regressions in them forever — the class would swallow the signal with the noise.
Pinning the pipeline hard enough to silence them (CPU encoders) costs over an hour per cycle and
stops measuring the shipping configuration.

**A capture on a partially enriched index is refused.** `fixture-cycle.sh` exits non-zero without
capturing when the enrichment wait fails, `fixture-pair.sh` retries that cycle once from a hard
clean, the capture records `provenance.enrichment` (index state, per-stage enabled flags, coverage
percentages, pending counts) read at capture *start*, and `capture_health` refuses a capture whose
enabled stages are not complete. The verdict is
`jseval.readiness._check_pipeline_complete_conditions` **imported**, not restated — two copies of
"is the index ready" drift, and then a capture can satisfy the ingest wait while failing the
capture check. This gap is why the first four-capture acceptance passed with 0 regressions: one
side captured a half-enriched index, disagreed with *itself* on 11 of 12 queries, and every
disagreement was withdrawn as noise.

**A capture whose chat turns had no live engine is refused, and liveness is not
`activation.state`.** All six captures of the 2026-09-07 acceptance recorded
`provenance.aiRuntimeState: "failed"` while the engine was demonstrably answering — their chat
turns terminated `done` with per-turn `samplingApplied` read off `session_started`. The field was
simply the wrong one. `activation.state` is the outcome of the last activation **procedure**;
liveness is the realized identity under `active`, which the backend projects only while the engine
is online. The dev-MCP reads it that way after the same false negative bit it
(`scripts/dev/justsearch-dev-mcp/server.mjs:2680-2687`, tempdoc 842 review D3/N1): an engine
brought up by AI **autostart** never runs the activation state machine at all, so
activation-completed alone is a false negative there.

Three things follow, and all three are now in place. `fixture-cycle.sh` **pre-checks** before
firing `activate` — if the engine is already online on the requested profile it does not
re-activate, which both avoids tearing down a healthy engine for a needless GPU self-test and
closes the race in which autostart brings the engine back during the ingest wait after the
script's own opening `deactivate`. It then polls on engine-online **and** profile-match rather
than on `activation.state == "completed"`, keeps the `activate` response (a 4xx there used to
vanish into `/dev/null`, making a refused activation indistinguishable from a slow one), and
**exits 4** rather than capturing chat turns no model answered — the same shape as the
enrichment refusal above, and `fixture-pair.sh` retries it once from a hard clean. The capture
records **both** signals (`aiRuntimeState` beside `aiRuntimeOnline` / `aiRuntimeOnlineSignal` /
`aiRuntimeVariantId` / `aiRuntimeModelFile`), because a failed activation procedure standing
beside a live engine is worth seeing. And `capture_health` refuses a capture that ran chat turns
without engine-online evidence — including one that never recorded it, since an unknown that
reads as healthy is exactly how the half-enriched side passed the four-capture acceptance. A
`--skip-chat` capture is exempt: it measures search alone.

What made the failed activation itself is *not* established. Each cycle tears its data dir down
with `--clean hard`, which takes the activation status file and the head log with it, so the
`errorCode` from those six runs is gone. That is why the script now writes `capture-N-ai-activate.json` and
`capture-N-ai-status.json` beside each capture — per capture, since a side runs N cycles into one
directory: the next occurrence is diagnosable from the artifact.

**Per-side noise fractions include `noisy-both`.** A field unstable on both sides is unstable on
each, so it counts towards both fractions. Counting only a side's exclusive noise understated
every side sharing an unstable field with the other — the same acceptance measured baseline 9
exclusive + 6 both and scored 9/222 = 4%, under the ceiling, when the honest figure is 15/222 =
6.8%.

**The withdrawal is bounded.** `maxNoisyFraction` (fixture key, `0.05`) refuses the whole run when
a side's noise pair moves more than that share of compared fields. That is about 10 of the shipped
fixture's ~209 compared fields — roughly twice the 6 that pairs 4 and 5 actually measured. At
`0.10` the gate would tolerate about triple today's noise before objecting, which catches only a
*collapse* into noise; at `0.05` it also notices a *drift towards* it, with clear headroom above
what has been observed. Raise it only against a measurement, the same way `scoreTieEpsilon` is set. Every noisy field leaves the
verdict, so without a ceiling a pipeline degraded on *both* sides — a dropped reranker, an
unfinished enrichment — would present as a very quiet diff with most fields silently excluded, and
"almost nothing was compared" would read as "nothing regressed". A noise capture whose `pins`,
`samplingApplied` or `chatProfile` differ from its own side's primary is refused for the same
reason: it is a second configuration, so the noise it reports belongs to the configuration change
rather than to the build. A field *absent* from a noise capture is not called noisy — absent
evidence of instability leaves it in the verdict, so a truncated noise capture can only make the
gate stricter, never laxer.

**The cross-encoder score is deterministic across builds (measured), and `scoreTieEpsilon` is
derived from the score distribution, not chosen.** Pair run 4, over 117 identity-matched hits
between two fresh ingests of one corpus on one build: the cross-encoder score delta was **0.0000 at
max, p95 and p50**. The sort key itself does not jitter, so the epsilon is not absorbing CE noise —
there is none. It is pure margin, and margin is not free: every adjacent pair of hits whose scores
fall inside it is declared a tie, and a tie may reorder freely under the equal-score-order class.

So the width is set against what it would excuse. Over the six committed captures (655 adjacent
score pairs, no exact ties), the adjacent-gap distribution is min `0.000488`, p5 `0.004456`, p10
`0.005859`, p25 `0.0208`, p50 `0.0524`. At `0.01` the tie window swallows **102 of 655 (15.6%)** of
adjacent pairs — a sixth of all neighbouring ranks free to swap on genuinely different scores. At
`0.001` it is **13 of 655 (2.0%)**, still an order of magnitude above the float32 granularity of a
score near `0.26` (~`1e-7`) and above the smallest gap actually observed. `0.001` is what the
fixture declares. Going lower buys nothing measurable: at `0.0001` no observed pair is fused at all,
which is the same as switching the class off.

**The candidate budgets are pinned too, and one cutoff cannot be.** Beyond the four
determinism pins above (exhaustive kNN, one LLM slot, the two rerank deadlines), a capture sets
`JUSTSEARCH_RERANK_TOP_K=40` and `JUSTSEARCH_RERANK_GPU_MEM_MB=4096` — the window and the arena
that has to hold it, which move together or the reranker dies (see above) —
`JUSTSEARCH_INDEX_HYBRID_CANDIDATE_LIMIT_MAX=5000`,
`JUSTSEARCH_HYBRID_CHUNK_COLLAPSE_LIMIT_MULTIPLIER=50`,
`JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED=false` and
`JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE=false`. Two mechanisms motivate them. *Candidate
truncation*: every leg hands fusion a bounded list, and a chunk missing from a leg scores `0.0`
for it with the leg's weight still in the denominator (`index.hybrid.chunk_cc_zero_exclude`
inherits `cc_zero_exclude`, default `false`), so falling out of one leg costs a chunk that leg's
whole weighted share at once — and per-leg min-max normalisation then shifts its neighbours too.
*Step functions*: leg arbitration flips alpha `0.5 → 0.7` when the two legs' top-10 doc ids share
at most **one** document (Jaccard `i/(20-i) < 0.1` reduces to `i < 1.82`), and the recall-complete
splice protects each leg's `rank ≤ 10` and **evicts** a fused hit to make room. Both are on by
default, and both change behaviour discontinuously on a one-document difference.

Three cutoffs stay hard-coded and the capture does not pretend otherwise:
`SearchExecutor.CHUNK_INITIAL_CANDIDATE_MULTIPLIER = 10` and `CHUNK_RETRY_MULTIPLIER = 2`
(`SearchExecutor.java:63-64`), and `SearchPlanner.MAX_LIMIT = 100` (`SearchPlanner.java:37`). The
last is a hard ceiling: it caps the wire limit, so branch fusion emits at most 100 documents —
below a 102-document corpus. No env setting lifts it; closing it needs a product change.

**Candidate-pool sizes are not compared.** `queries.totalHits` and `queries.trace.stageCardinality`
were dropped from the captured set. Both count how many candidates a *stage* happened to consider,
which follows the candidate budget and which chunks sat at a per-leg cutoff — not what the search
found. Both moved between two fresh ingests of one corpus on one build (q02, q03, q07, q09 of the
2026-09-07 pair) while the evidence was otherwise equal, so diffing them reported pool churn as a
semantic regression. Design 16 names evidence selection, truncation points, citation targets and
cancellation as the byte-equal fields and never names either of these. They are recorded in the
non-diffed `observed` block, where a reader diagnosing a hit-set difference still has them.
`queries.matchCount` stays `exact`: it is an `IndexSearcher.count` over the query, a property of
the corpus and the query rather than of any budget.

**One capture per fresh corpus.** The chat turns index their own agent history, so a second capture
on the same stack sees a changed index (measured: `docCount` 91 → 102 across one capture's three
turns; one query's `totalHits` moved 50 → 49 on the re-run). Inside a capture the search half runs
before the chat half for exactly this reason. A stability check *of the fixture* therefore needs a
re-ingested corpus, not a re-run. `provenance.docCountAtStart` / `docCountAtEnd` record the
movement (non-diffed).

**The two ordinary chat turns are meant to complete.** Both truncated at `disposition:
MAX_ITERATIONS` with `maxIterations: 3` on the compact model, which makes their citations partial
by construction; `maxIterations` is now 8, and capture-health raises `chat turn ended
MAX_ITERATIONS` for any non-cancelled turn that still truncates, so a truncated baseline is refused
rather than stored. The cancelled turn is exempt — it is supposed to end early.

`c02` was **replaced** for exactly this reason. Its IndexingPacing duty-cycle question still ended
`MAX_ITERATIONS` at 8 in *both* captures of the paired run, at temperature 0 — a two-part question
whose "which ADR" half has no single obvious anchor, so the agent kept searching. A looping turn is
worse than a partial answer: its tool trajectory diverges as soon as retrieval does, so every
`exact` field downstream of the trajectory (`toolNames`, `toolCallsExecuted`, `sourceRefs`,
`citationTargets`) differs for a reason that is not a build difference. `c02` now asks a
single-document architecture question of `c01`'s shape, anchored on
`docs/explanation/01-system-overview.md`, with `maxIterations` still 8. Read the question itself
from the fixture (`scripts/jseval/lane-f-workflow-fixture.v1.json`), **not from here**: this file is
inside the capture corpus (`docs/reference/**`), so restating a chat turn's distinctive wording
would make this page a retrieval attractor for that very turn and put a document *about* the
instrument into the evidence the instrument compares. `c01`'s wording appears nowhere in the corpus
outside the documents that genuinely answer it, and `c02`'s must not either.

**Both captures of a paired diff must be on the same chat profile.** The profile decides which
model answered the chat turns, so a split-vs-single pair taken on different profiles compares two
models, not two builds. `capture` records `provenance.chatProfile` and `provenance.aiRuntimeState`
from `GET /api/ai/runtime/status` (non-diffed, and printed in the capture summary), reading the
same fields the dev-MCP does. The 2026-09-07 baseline was taken on **`compact`** — the dev default
— because the standard model's ~11 GB resident set on the dev machine tripped the harness's
low-memory guard twice. `c01` completes on compact in 3 iterations; the *original* `c02` did not
(see above), which is why it was replaced. Whether the replacement completes is what the next
paired capture measures — capture-health refuses a `MAX_ITERATIONS` baseline either way, so a
looping turn cannot be stored as one.

**Generation is nondeterministic, and `citationTargets` is deliberately still `exact`.** Across two
captures of one build, one turn's citation targets differed (`[]` vs two targets). The agent path is
*shape-driven* and never reaches `ConversationEngine.parseSamplingParams`
(`ConversationEngine.java:1151` — that is the chat-completions path, not `POST /api/chat/agent`):
an agent turn samples under `SamplingParams.AGENT`, temperature **0.7** / top_p **0.8**
(`SamplingParams.java:175`), returned by `AgentLlmCaller.resolveAgentSampling`
(`AgentLlmCaller.java:282-295`, which applies the run's optional override through `agentBaseSampling` at `:310-322`), which adds `tool_choice`/grammar on a forced-tool turn and
otherwise hands back that constant unchanged. PR 0b adds an optional top-level `sampling` object on
the chat request — `{"temperature", "top_p", "seed"}`, each key optional, absent/null meaning no
override — applied over that constant; the fixture declares the pin it wants (`temperature: 0.0` +
a fixed seed) and `capture` sends it on every turn and records it in the non-diffed
`provenance.sampling`. Design 16 lists citation targets as deterministic, so reclassifying them is
an owner decision, not the instrument's. Until both sides of a pair are captured under the pin,
expect the chat `exact` fields to be the fixture's most fragile assumption and read a diff there as
a finding, not as licence to reclassify. The **cancelled** turn was fully stable across both
captures (terminal event, `CANCELLED` error code, session state, disposition and cancel trigger all
identical), so the cancellation half of the row is sound as it stands.

**The capture records the *requested* pin and the *applied* one, and the difference is the point.**
`provenance.sampling` is only what the capture asked for — it writes its own request back, so run
the same fixture against a build predating the override and the artifact still reads as pinned while
the backend silently ignored it. `provenance.samplingApplied` is what each run said it would
*actually* use: the `session_started` frame echoes `samplingTemperature` / `samplingTopP` /
`samplingSeed`, each key **omitted** when the backend resolved no value and **all three absent on a
build predating PR 0b**. The capture reads them per turn and never defaults an absent key, because
that absence is the signal. A pin that can only be asserted and never contradicted is not evidence;
only these two records can disagree.

**Both are capture-health checks, not diffed fields, and `diff` fails on them.** `capture_health`
refuses a pair when either side's `provenance.pins` is absent or missing one of the keys in
`PINNED_CONFIG_KEYS` (ten today), when
the two sides' pins disagree on any key, when `provenance.samplingApplied` is absent or missing a
recorded turn, when a turn's applied sampling is entirely null (the pre-PR-0b build), or when the
two sides applied different sampling for a turn. They are health problems rather than declared
fields because they are not outputs either build produced — they are the conditions both runs were
measured under, and a pair captured under different conditions is not a comparison of two builds at
all, so the right verdict is "this diff is not evidence" rather than "field X differs".

**The pins come from `GET /api/debug/effective-config`.** Its body carries a top-level
`resolvedConfig` array of `{key, value, source, ordinal, detail, candidates[]}`; `value` is always a
**string** and is *omitted* (the record is `@JsonInclude(NON_NULL)`) for a key no source supplied,
so an unset `index.vector.exhaustive_search` — which has no registered default — reads as **missing**
rather than as a value, and the health check fires. An unreachable endpoint yields empty pins rather
than an exception: the capture is still written, and the diff built on it then fails. Note the route:
the PR 0b pair run fetched `/api/config/effective`, which does not exist
(`tmp/pr0b-pair/effective-config-1.json` is the `NOT_FOUND` body), so that run recorded no pins and
could not have caught a pin mismatch.

**The capture run is pinned at boot, not only in the request.** Both sides of a paired diff must be
captured with the same four settings, the first three set at stack launch:
`JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH=true` (every kNN query exact, so the dense leg is not an
approximate neighbour set that moves with the HNSW graph's build order); exactly **one**
llama-server slot (`justsearch.llm.slots` / `JUSTSEARCH_LLM_SLOTS`, default 2 — two parallel slots
make the prompt-cache prefix a turn sees a scheduling outcome); the cross-encoder reranker deadlines
pinned high (`justsearch.rerank.deadline_ms` / `justsearch.rerank.chunks.deadline_ms`, defaults 200
/ 150 — at the defaults a loaded machine can miss the deadline and reorder the hit set); and one
chat profile for both sides. `scripts/jseval/lane-f/fixture-cycle.sh` carries the same list in its
header.

**Paths are relative to a declared corpus root.** Every path-bearing value the backend returns is
the *absolute* indexed path (`IndexingDocumentOps` writes `DOC_ID = PATH = absolutePath`; a chunk's
`PATH` is its parent's; the agent's `sources[]` carry the same string). The baseline and candidate
captures come from two different worktrees, so unanchored **every** hit differs on `path` and the
diff is all-REGRESSION for a reason that is not semantic — measured: 21 regressions across
`queries.hits[]`, `chatTurns.sourcePaths`, `sourceRefs`, `citationTargets`; 0 once anchored.
`capture` rewrites each one to a forward-slash, case-preserved path relative to `--corpus-root`
(else `$JUSTSEARCH_FIXTURE_CORPUS_ROOT`, else the single registered watched root; zero or several
roots is a refusal naming them, not a guess). Matching is case-insensitive because
`PathNormalizer` lowercases on Windows. **Both captures must be taken with the corpus at the same
relative layout under their respective roots** — this makes two worktrees comparable, not two
different corpus arrangements. An absolute path outside the root becomes `<outside-corpus-root>`
and is a capture-health problem; the raw value survives only in the non-diffed
`provenance.rawRootExample` / `pathsOutsideCorpusRootExamples`, so a reader can see what was
stripped. An already-relative value passes through untouched.

**Hit `id` / `doc_id` are not captured at all.** They are the same string, and for a *chunk* hit it
is `"chunk:" + UUID.randomUUID()`, minted fresh per indexing run (`ChunkIds`) — two builds of the
same corpus never agree on it, and unlike an absolute path there is nothing to rewrite it to. For a
whole-document hit it is the normalized absolute path, i.e. redundant with `path`. A chunk's stable
identity is `parentDocId` (relative) + `chunkIndex` + `chunkSpan`, all captured.

**Capture health is part of the verdict, not a warning.** Two identical *failures* are byte-equal,
so an all-401 backend would otherwise diff clean and read as "no semantic regression". `diff` fails
when a section is empty in both captures, when a declared field path was never captured, when any
captured `httpStatus` is not 200, or when a query recorded `hitCount` 0; `capture` refuses (exit 2)
when every fixture query was rejected. The mutation token (`--session-token`, else
`$JUSTSEARCH_SESSION_TOKEN`) rides every request, not just the cancel `DELETE` —
`ApiSecurityFilters.TOKEN_REQUIRED_METHODS` is `{POST, PUT, DELETE}`.

Three rules make the table load-bearing rather than decorative: a field with **no** declared class
must be byte-equal; a field captured but **absent from the table** is a regression by construction,
so a newly captured field cannot slip through unclassified; and the differ **raises** on a class
name outside the three. **No class is added after a diff is seen** — a changed citation is a
regression unless its class was declared beforehand.

Timing is **not captured at all** rather than given a class (`stages[].ms`, `tookMs`, the
`latencyMs` figures, SSE `heartbeat` frames, the per-run session id), and the capture's
`provenance` block is informational — the two builds are expected to differ there, so it is not
diffed.

Caveat, recorded rather than worked around: `POST /api/chat/agent` carries **no** `seed` request
field (the only `seed` reaching llama-server is the vision/VDU agreement probe), so generative text
is treated under `generative-text` unconditionally, not "under a fixed seed". The fixture's chat
turns are deliberately narrow and factual so the chat fields declared `exact` (citation targets,
source identity, tool names, terminal disposition) have the best chance of holding without one.
`chatTurns.errorCode` / `toolErrorCodes` are deliberately `exact`, not `new-reason-code`: a run
that did not fail in the baseline and fails in the candidate must not be waved through as "a code
that did not exist before". And `cancel.httpStatus` carries no signal today —
`AgentController.handleCancelSession` returns 200 unconditionally, so read
`cancel.sessionState` / `sessionDisposition` / `sessionCancelTrigger` for whether the cancel took
effect. Implementation: `jseval/workflow_fixture.py`.

### Chunk-completeness validity guard (tempdoc 718)

A fresh `--clean` index build can silently ship with its chunk (RAG passage) sub-system absent
(tempdoc 717) — the run reports `COMPLETED`, gates pass, and vector-mode nDCG is simply worse (a
measured case: 0.34 instead of a healthy 0.62), with no error anywhere. This is a
**measurement-integrity** hole distinct from 717's enrichment-correctness bug: any consumer that
reads the degenerate index (release scorecard, ratchets, a founder A/B) scores it as healthy.

Every `run` embeds a `chunk_completeness` block in `summary.json` (sibling of `manifest` /
`corpus_identity`): `{"expected": N, "observed": M, "verdict":
"ok"|"chunk-free"|"degenerate"|"unevaluable", "reasons": [...], "threshold_chars": T|null}`.
`expected` is computed OFFLINE from the corpus's `corpus.jsonl` — a count of docs whose
materialized content (`title + "\n\n" + text`) reaches the chunk threshold —
before/independent of any ingest, so a degenerate enrichment pipeline can never move it (the
anti-spoof property: a build that suppresses chunk-doc *creation* still can't fake the *offline*
expectation). `observed` is `chunkDocCount`/`chunkVectorCoveragePercent` from the run-completion
`/api/status`, corroborated by `chunk_merge` in vector mode's `pipeline_tracking.observed`. A
`chunk-free` verdict (`expected == 0`, e.g. a short-doc BEIR/golden corpus) is a legitimate pass,
distinguished from a `degenerate` verdict (`expected > 0` but the index shows none/incomplete
chunk docs) — the two 0-chunk cases that are otherwise bit-identical at the pipeline-output layer.

**Threshold provenance (tempdoc 821 §3-C3).** The threshold is no longer a jseval-side mirror of
`ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS`; the worker's enrichment auditor OWNS it and publishes
it on the wire as `worker.enrichment.chunkMinChars`, which `resolve_chunk_threshold_chars()` reads
off the same `/api/status` snapshot the observed counts come from. There is deliberately **no local
fallback constant** — a fallback would re-create the mirror. `threshold_chars` in the block records
which value the expectation was computed against (`null` = the backend published none). A backend
that predates the field reports `0` for it (proto3 scalar — never absent), which yields the fourth
verdict, `unevaluable`: the expectation could not be computed at all. `unevaluable` is deliberately
NOT collapsed into `chunk-free`, because `chunk-free` is the affirmative claim "no corpus doc
reaches the threshold" — a fact that path never established, and one that is affirmatively wrong on
a degenerate build. It does not gate (back-compat), but `assert_chunk_completeness` prints a loud
stderr stand-down warning rather than passing silently, so a degenerate build measured against an
old backend can no longer read as a clean pass.

All four ratchet gates (`relevance-gate`, `perf-gate`, `leak-gate`, `union-recall-gate`) refuse an
un-overridden `degenerate` run before evaluating anything, exit code 2:

```bash
python -m jseval relevance-gate --data-dir <dir> --dataset golden/legal-clerc
# {"exit_code": 2, "error": "chunk-completeness guard: ...", "expected": 340, "observed": 0, ...}
```

Escape hatch (deliberate chunk-incomplete certification only): `--allow-chunk-incompleteness` per
gate command, or `JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1` — mirrors `--allow-engine-mismatch` /
`JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL`. A run predating the guard (no `chunk_completeness` block)
is treated as `ok` — backward-compatible. Implementation: `jseval/chunk_completeness.py`
(`resolve_chunk_threshold_chars`, `expected_chunk_docs`, `chunk_completeness_verdict`,
`unevaluable_result`) + `ratchet_kernel.assert_chunk_completeness`.

**Dual-source-of-truth risk — closed (tempdoc 821 §3-C3).** The 2000-char mirror of
`ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS` that tempdoc 718 flagged as drift-prone has been
deleted; the oracle reads the backend's published `worker.enrichment.chunkMinChars` instead (see
*Threshold provenance* above), and stands down loudly (`unevaluable`) rather than guessing when a
backend does not publish it.

### Diagnostics

```bash
# Check backend health, models, GPU before running eval
python -m jseval preflight

# Discover worker.log path from running backend
python -m jseval log-path

# List available datasets and modes
python -m jseval datasets
python -m jseval modes
```

### Interactive development (360)

```bash
# Start eval backend and keep running until Ctrl-C (attaches if already running)
python -m jseval dev [--clean]

# Send a single search and show full pipeline execution (CE status, timing)
python -m jseval search --query "vitamin D" [--mode hybrid] [--ce] [--json]

# Tail Worker/Head logs with structured filtering
python -m jseval logs [--source worker|head] [--filter rerank] [--tail] [--level WARN]
```

### Long detached runs (Windows)

A long pipeline run launched through the Bash tool's `run_in_background` gets
**killed mid-run** (observed repeatedly, e.g. mid-enrichment). Launch it fully
detached instead, and stamp a `.done` marker with the exit code on completion:

```powershell
# Runs in a PowerShell that outlives the tool call; writes the exit code to a marker.
Start-Process powershell -WindowStyle Hidden -ArgumentList @(
  '-Command',
  'python -m jseval run --dataset scifact --output-dir tmp/run1; $LASTEXITCODE | Out-File tmp/run1.done'
)
```

Then wait on the `tmp/run1.done` marker with the `Monitor` tool, and read results
from the run's `--output-dir` (`tmp/run1`) — do **not** parse the process's
redirected stdout/stderr: PowerShell 5.1 writes those UTF-16 and wraps stderr
lines, so the run's own JSON artifacts are the reliable source.

> **`--clean` caveat:** `jseval run --clean` does **not** reliably wipe the index /
> `watched_roots` (observations-logged defect). When a clean state matters between
> arms, wipe `tmp/headless-eval-data` manually.
>
> **Windows console encoding:** Inspect AI's rich display crashes with
> `UnicodeEncodeError` when stdout is redirected or backgrounded — cp1252 cannot encode
> its braille spinner. Set `INSPECT_DISPLAY=none PYTHONUTF8=1` for **any** backgrounded
> `eval_set` / `jseval utility-run` invocation.

### Invoking jseval from a worktree (Windows)

`jseval` is normally pip-installed editable against wherever it was **first** installed,
so invoking it from a *different* worktree once silently ran the wrong copy. It now
**fails closed** instead, printing the exact remedy inline:

```text
PYTHONPATH=<worktree>/scripts/jseval
```

Follow the printed fix. `JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL=1` overrides the guard
deliberately — use it only when you actually intend to run another checkout's copy.

### Observability (tempdoc 400 Layer 1/4/5)

Post-§23 closure, jseval is the single CLI surface for every piece of
tempdoc 400 observability. Every subcommand below reads/writes the
jseval-owned data root (tempdoc 716): `--data-dir` defaults to
`scripts/jseval/tmp/`, which hosts `eval-results/` (where a defaults `run`
writes) — defaults-only invocations compose without path flags. See
`docs/explanation/08-observability.md` for the schema.

Tempdoc 930 §18.1 row 7 removed the cohort-envelope / drift-calibration
commands (`calibrate`, `calibrate-drift-baseline`,
`recalibrate-nightly-baseline`) and the `cohort_baselines/` directory they
wrote: no baseline was ever captured on any machine. Run-to-run variation is
reported per run instead — `summary.json`'s `latency_stats` and
`encoder_latency` blocks (see below).

```bash
# Projection-presence gate (Phase 6/6.13; was scripts/ci/phase3_*)
python -m jseval gate [--report-out <json>]

# Layer-5 experiment runners
python -m jseval counterfactual --dataset scifact --max-queries 50
python -m jseval shadow-eval --dataset scifact \
  --policy-a <a.json> --policy-b <b.json>
python -m jseval bench-concurrency --dataset scifact --concurrency 4 \
  --max-queries 50 [--warmup N]
python -m jseval bisect --run-a <run_dir> --run-b <run_dir> \
  [--synthesize --dataset scifact --modes full --dry-run]
```

**Operator guides:** `docs/how-to/interpret-bisect-output.md`.

## Available Datasets

Use `python -m jseval datasets` to list all available datasets,
including local mixed/golden corpora discovered on disk.

| Slug | Source | Notes |
|------|--------|-------|
| `scifact` | BEIR | 5183 docs, 300 queries, academic |
| `nfcorpus` | BEIR | nutrition/health |
| `arguana` | BEIR | argumentation |
| `fiqa` | BEIR | financial QA |
| `webis-touche2020` | BEIR | controversial topics |
| `mixed/<name>` | local | Scanned from `datasets/mixed/` |
| `mixed/ohr-bench-clean` | local | OHR-Bench ground-truth text (7 domains, 1000 docs, 962 queries) |
| `mixed/ohr-bench-tika-pdf` | local | OHR-Bench original PDFs through Tika StructuredContentExtractor |
| `mixed/ohr-bench-got-moderate` | local | OHR-Bench GOT OCR extraction (moderate noise) |
| `mixed/ohr-bench-mineru-moderate` | local | OHR-Bench MinerU extraction (moderate noise) |
| `golden/<name>` | local | Scanned from `datasets/golden/` |

## Available Modes

Use `python -m jseval modes` to list all modes with their components.

| Mode | Resolution | Components |
|------|-----------|------------|
| `lexical` | client | sparse (BM25) |
| `vector` | client | dense |
| `splade` | client | SPLADE |
| `bm25_splade` | client | sparse + SPLADE |
| `dense_splade` | client | dense + SPLADE |
| `full` | client | sparse + dense + SPLADE |
| `hybrid` | server | sparse + dense + RRF + LambdaMART |

Client-resolved modes send an explicit pipeline config. Server-resolved
modes (like `hybrid`) send a mode string for backend resolution.

**Trap — an omitted leg mode silently lowers union recall.** The
`staged_recall_accounting` projection computes its leg union over
`LEG_MODES = ("vector", "lexical", "splade")` and keeps only the modes
actually present (`projections/staged_recall_accounting.py:76`, `:240`).
A mode you did not run is *absent from the union*, not an error, and the
projection still reports `status: "ok"` as long as one leg plus a final
mode (`hybrid`/`full`) is present. So `--modes lexical,vector,hybrid`
looks green while under-measuring `leg_union_recall`. Measured instance:
a cell certified at union 0.75 with all three legs re-measured at 0.48 —
exactly its vector recall, i.e. one contributing leg — with `splade`
omitted, enough to fail a 0.65 floor after a full paid + GPU run. **When
a run feeds `union_recall` / `leak_floor` gates, or is compared against a
threshold derived elsewhere, pass all three leg modes:**
`--modes lexical,vector,splade,hybrid --embedding --splade`. Calibration
runs additionally require `--embedding` with `hybrid` as the headline
mode. The structural check is satisfied by two legs, so an omitted
`splade` produces a green-looking projection with a silently lowered
`leg_union_recall` — every `union_recall` gate then fails after the run.

The same projection can add a `jseval.result-redundancy.v1` section when a run has a fully
reconciled `result_identity.v1.json` sidecar with confirmed duplicate-cluster assignments. In v1,
only normalized-content-exact clusters are accepted. A calibration/holdout decision artifact chooses a
near-duplicate threshold but does not itself decorate result identities with confirmed clusters, so reviewed
near-duplicate labels remain outside this contract. This
section is intentionally separate from recall: recall and failure buckets continue to use qrels and
the score-ranked TREC artifact, while redundancy uses the API's delivered `predictedDocIds` order
truncated to ten. It reports delivered hits, unique clusters, redundant hits, and affected queries
for the projection's selected final mode; it does not emit separate per-leg redundancy aggregates.
An absent sidecar—or an identity-only sidecar not yet decorated by confirmed analysis—omits the
section. Once cluster assignments are present, wrong schema, corpus mismatch, missing/extra hits,
ambiguous assignments, or order mismatches fail the projection instead of producing a partial rate.

## What jseval Handles

- **Corpus materialization**: Downloads and converts datasets to .txt
- **Ingestion**: Adds watched root, waits for file watcher to start
- **Readiness wait**: Polls `/api/status` with progress logging every
  30s (embedding %, SPLADE %, NER count, chunk %, GPU %, VRAM, heap)
- **Pipeline wait**: `--pipeline` waits for ALL enrichment stages
  (embedding, SPLADE, chunks, NER) to reach completion
- **Timeline recording**: `--timeline out.tsv` captures status snapshots
  with GPU/VRAM/enrichment counters per row
- **Pipeline summary**: Per-stage completion times written to
  `summary.json → pipeline_timing`
- **Query execution**: Runs queries, computes nDCG@10/P@1/R@10
- **Result comparison**: A/B diff with per-query rank analysis and
  pipeline timing comparison
- **Backend lifecycle**: `--start-backend` starts runHeadlessEval,
  `--clean` wipes the whole data dir, auto-stops via taskkill on
  completion. `--clean` is **fail-closed** (tempdoc 711 item 4) and,
  since tempdoc 716, **unconditional**: every durable jseval artifact is
  filed under the jseval data root (`scripts/jseval/tmp/`), never inside the
  backend data dir, so nothing in the backend dir is protected from the wipe.
  Because the Worker JVM (spawned by the Head as a grandchild of the
  Gradle process) has been observed to survive the process-tree
  `taskkill` and keep the Lucene index open, the wipe runs a
  double-keyed orphan-Worker sweep (matched by the index lock file's
  recorded PID/start-time **and** by the process command line's
  `-Djustsearch.data.dir=` value, so it can never target another
  session's process on a shared machine) before retrying any failed
  deletion. If a survivor remains after the sweep and retry, the run
  raises a hard error naming the survivor and the last-known holder
  PID/cmdline instead of silently proceeding on a dirty data dir. This
  also runs on `stop_backend()` after every `--start-backend` run, not
  only under `--clean`.
  `--llm` with this eval-backend entry point is rejected: its read-only
  settings discard the runtime activation intent. Inference-capable measurement uses the owned
  normal dev-runner recipe above, with writable settings and explicit activation.
- **Index reset**: `--reset` calls `POST /api/debug/reset-index` before
  ingestion — wipes index without process restart (requires running
  backend in eval mode). Mutually exclusive with `--start-backend`.
- **Preflight checks**: `jseval preflight` reports loaded models, GPU
  status, enrichment coverage, and `embedding_model_sha256` from
  commit metadata
- **YAML config**: `--config run.yaml` for reproducible runs with GPU
  settings, dataset, modes in a single file
- **Crash detection**: Fails fast after 5 consecutive status fetch
  failures; checks `meta.workerRpcStale` for Worker-down detection
- **NDJSON progress**: `--json` emits structured progress objects to
  stderr and the final result to stdout
- **Output**: Structured `summary.json` with metrics, git SHA,
  pipeline timing, comparability tracking

## Key Flags

| Flag | Effect |
|------|--------|
| `--pipeline` | Wait for ALL enrichments (implies `--embedding --splade`) |
| `--embedding` | Wait for embedding coverage ≥ 99.9% |
| `--splade` | Wait for SPLADE coverage ≥ 99.9% |
| `--start-backend` | Start runHeadlessEval, stop when done |
| `--llm` | Rejected with `--start-backend`: eval settings are read-only; use the owned full-inference dev-stack recipe |
| `--clean` | Clean data dir before start (requires `--start-backend`); fail-closed — wipes the WHOLE backend data dir (tempdoc 716: calibration state lives under `scripts/jseval/tmp/`, not here), sweeps orphan Worker processes on a delete failure, raises rather than proceeding if a survivor remains (711 item 4) |
| `--reset` | Reset index via API before ingestion (eval mode, no restart) |
| `--timeline PATH` | Record status snapshots to TSV during wait |
| `--config PATH` | Load YAML run configuration file |
| `--max-queries 0` | Ingest only, no queries (pipeline profiling). When 0, `--modes` is not required. Scoring uses filtered qrel count (only evaluated queries), not full corpus query count (353). |
| `--skip-ingest` | Query only, skip materialization and ingestion |
| `--corpus-dir PATH` | Use existing corpus dir as-is (no materialization) |
| `--allow-errors` | Continue on query errors (don't abort run) |
| `--lambdamart` | Enable LambdaMART reranking check |
| `--json` | NDJSON progress to stderr, JSON result to stdout |
| `-v` / `--verbose` | DEBUG logging (httpcore/httpx suppressed) |
| `--history-db PATH` | Shared history database for trend tracking |
| `--search-load-qpm N` | Drive N queries/minute (evenly spaced) against `POST /api/knowledge/search` on a background thread **during** ingest + the readiness/pipeline wait, and record a `search_load` block in `summary.json` (mode, queries issued, errors, latency p50/p95/max, start/end). Queries come from the dataset's own query file, in `hybrid` mode. Off by default; nothing changes when it is absent (885) |
| `--search-load continuous` | As above but back-to-back with one request in flight (the continuous MCP-style agent loop). Mutually exclusive with `--search-load-qpm` |
| `--first-search-probe` | After every batch of `--first-search-probe-files` (default 50) newly indexed documents, issue ONE search and record its latency separately from `--search-load*`. Reopen-on-demand moves the segment-open cost onto exactly that query, so averaging it into steady-state traffic hides it. Off by default (885 item 19) |
| `--settle-index` | Force-merge the active index to one tombstone-free segment (`POST /api/indexing/settle` with `expungeDeletesOnly: false, maxSegments: 1`) after the pre-query readiness gate and before the query phase, so two arms of a paired comparison query indexes with **equal merge state**. Expunge-only was not enough: Lucene skips segments under its 10 % deleted-fraction threshold, so it left 181 tombstones on scifact (931 C1 campaign). Readiness is re-checked once afterwards (the settle commits and reopens the searcher). Records `index_state_at_query.settled` plus the before/after counts under `index_state_at_query.settle`. Degrades to `settled: false` with a WARN on a 404 (pre-931 backend), a worker refusal, or a transport failure -- the run continues. Off by default: it holds the writer for the duration of a force-merge (931 SS-E item 10) |

That endpoint is the one that writes the Worker's MMF activity slot, so these two flags are how a
throughput measurement is taken *with foreground search traffic present* — see tempdoc 885's
chunk-1 baseline for the measured effect. Both are ignored (with a WARN) on a run that does no
ingest, i.e. `--skip-ingest` or an adopted index-cache entry.

**Trap — `datasets/` resolves differently per command.** `jseval run`
resolves `datasets/` from the **repo root** and ignores the current
working directory (`corpora.py:306` → `REPO_ROOT / "datasets"`), while
`corpus-certify --datasets-dir datasets` is a `click.Path` resolved
against **cwd** (`commands/corpus.py:184-197`). Materializing into
`scripts/jseval/datasets/` therefore satisfies `corpus-certify` run from
`scripts/jseval/` and then fails `jseval run` with
`FileNotFoundError: corpus.jsonl not found at <repo-root>/datasets/...`.
Materialize into the **repo-root `datasets/`** and pass `--datasets-dir`
as an absolute path. Both `datasets/` and `datasets-*/` are gitignored,
so nothing there survives a fresh checkout.

## Output Structure

The jseval data root (`scripts/jseval/tmp/`; tempdoc 716) hosts every
durable jseval artifact, so every gate reader's `--data-dir` default
composes with `run`'s default `--output-dir`:

```text
scripts/jseval/tmp/                        # DEFAULT_JSEVAL_DATA_DIR
  eval-results/<timestamp>_<dataset>/      # `run` default --output-dir
    summary.json            # Metrics, config, git SHA, pipeline timing
    <mode>_per_query.json   # Per-query scores and ranks
    <mode>_run.trec         # TREC-format run file
    result_identity.v1.json # Path-free run-local IDs for raw delivered hits
```

**Additive schema key, always present (885 item 19).** Every `run` emits a `cadence` block in
`summary.json`, whether or not `--first-search-probe` was passed — this is a disclosed schema
addition, not a no-op: a consumer that enumerates `summary.json` keys will see it on every run.
It carries `reopen_total`, `commit_total` and `segments_since_reopen` read from the Worker
telemetry NDJSON (`index.runtime.*`), plus `first_search_after_indexing` (null unless the probe
ran). Every field degrades to `null` when the Worker does not publish the metric, so the comparison
columns exist on every row rather than appearing only on some — which is the point, an arm table
with missing columns cannot be read.

**Additive schema key, always present (tempdoc 931 §E item 10).** Every `run` with modes (a
query phase) emits an `index_state_at_query` block, snapshotted right after the pre-query
readiness gate passes: `{"max_doc", "num_docs", "deleted_docs", "chunk_splade_coverage_percent",
"splade_coverage_percent", "chunk_vector_coverage_percent", "settled", "readiness_passed_at"}`,
plus a nested `settle` sub-block (`max_doc_before` / `num_docs_before` / `max_doc_after` /
`num_docs_after` / `segments_after` / `elapsed_ms`) when `--settle-index` ran and succeeded.
`settled` distinguishes "equal merge state by construction" from "equal by accident". `deleted_docs`
is `max_doc - num_docs` — the tombstone count that inflates BM25 collection statistics when it
differs between two otherwise-identical fresh indexes of the same corpus (a measured case moved
2,629 vs 222, shifting hit counts 3-4% with no code cause). Every field is `null` when the backend
doesn't publish it (older backend, or `--skip-readiness`), so a paired-arm comparison always has
the column to check before attributing a metric delta to code.

**Additive schema key, always present (930 §18.1 row 7).** Every `run` also emits an
`encoder_latency` block: `{"encoders": {"<encoder.name>": {"n", "p50_ms", "p95_ms"}}}`, derived
from the `encoder.ort_run` spans in the Worker's `traces.ndjson` (rotated siblings included).
`encoders` is `{}` when the Worker published no such spans — typically because
`JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` was not exported. Absolute numbers, no baseline and
no threshold: this replaced the `encoder_drift` PSI projection, whose per-cohort baseline never
existed. Read it alongside `cpu_fallback_counts` to tell "the encoder got slower" from "the
encoder moved to CPU".

`result_identity.v1.json` is a local-run sidecar, not a replacement document namespace. It captures
the full raw hit path/id in memory before legacy BEIR filename/stem normalization, assigns a random
run-namespaced opaque ID, reconciles every delivered position to the unchanged
`predictedDocIds`, and persists no raw path or plain path hash. Same-leaf files in different folders
and cross-format copies therefore remain distinct even when their BEIR IDs collide. Duplicate
clusters are minted only while raw aliases and the production observations are both in memory. The
complete observation-to-alias catalog is committed by keyed HMAC into the extraction snapshot, so even a
bijective alias swap fails before the join while the private-scratch key keeps paths from becoming
dictionary-testable hashes. The key is never persisted. A separate private-scratch Ed25519 key signs the
complete decorated sidecar, while its verification key is committed into the extraction artifact; the
projection therefore rejects any later query, opaque-id, fingerprint, or cluster reassignment. The builder
re-runs the duplicate analyzer over those observations and requires an exact artifact match;
the projection then revalidates the content-addressed aggregate analysis beside the run. The sidecar
nonce, complete semantic hash, analyzer hash, and verification key are independently pinned in the run
summary, preventing a signed sidecar/analyzer pair from being replaced or replayed across runs. The
sidecar must also be bound to a strict corpus SHA-256. Private alias/catalog material remains outside the run
directory.

**Local prerequisite for the full pytest suite.** `python -m pytest scripts/jseval/tests` needs the
optional extras: `pip install -e "scripts/jseval[dev,agent]"`. Without them four test modules fail
at *collection* (`inspect_ai`, `hypothesis`), so the run reports collection errors and executes
nothing rather than reporting a partial pass.

`summary.json` fields agents typically need:
- `per_mode.<mode>.aggregate_metrics["nDCG@10"]` — headline quality
- `per_mode.<mode>.pipeline_tracking.observed` — which retrieval legs ran
- `per_mode.<mode>.comparable` — whether metrics are trustworthy
- `pipeline_timing.stages` — per-stage completion times (when `--pipeline`)
- `pipeline_timing.inference.<stage>.total_ms` — cumulative ORT inference wall time (350)
- `pipeline_timing.inference.<stage>.batches` — batch count for this stage (350)
- `pipeline_timing.inference.<stage>.avg_ms_per_batch` — average per-batch time (350)
- `pipeline_timing.encoder_profiles.<encoder>.calls` — total ORT inference calls (357)
- `pipeline_timing.encoder_profiles.<encoder>.ort_p50_us` — ORT call latency p50 in microseconds (357)
- `pipeline_timing.encoder_profiles.<encoder>.ort_p95_us` — ORT call latency p95 (357)
- `pipeline_timing.encoder_profiles.<encoder>.ort_p99_us` — ORT call latency p99 (357)
- `pipeline_timing.encoder_profiles.<encoder>.phases` — per-phase cumulative time map (357)
- `pipeline_timing.encoder_profiles.<encoder>.seq_len` — sequence length stats (357)
- `pipeline_timing.primary_indexing.docs_per_s` — indexing rate
- `ingest.worker_throughput_docs_per_sec` — primary indexing throughput
- `search_config` — active search pipeline config snapshot from `/api/status` (343)
- `env_overrides` — env vars applied by jseval config that differed from defaults (343)
- `index_state_at_query.deleted_docs` — tombstone count at query-phase start, for paired-arm
  merge-state comparability (931 §E item 10)
- `index_state_at_query.settled` — whether `--settle-index` equalized this arm's merge state
  before the query phase (931 §E item 10)
- `git_sha` — for reproducibility

## YAML Run Config

```yaml
dataset: scifact
modes: [lexical, hybrid]
embedding: true
splade: true
pipeline: true
max_queries: 0
output_dir: tmp/eval-results

gpu:
  embed:
    enabled: true
    layers: 32
    mem_mb: 2048
  splade:
    enabled: true

backend:
  clean: true
  llm: true

# Passthrough env vars (arbitrary)
env:
  JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY: REINDEX
```

## Source Code

jseval lives at `scripts/jseval/`. Key files:
- `jseval/_paths.py` — Canonical path constants (`REPO_ROOT`, default output dirs)
- `jseval/types.py` — Shared types (`IngestConfig` dataclass for parameter threading)
- `jseval/cli.py` — Click CLI, subcommand registration
- `jseval/run.py` — Eval run orchestration, summary building
- `jseval/ingest.py` — Corpus ingestion, readiness wait
- `jseval/readiness.py` — Status polling, readiness conditions, progress logging
- `jseval/retriever.py` — Query execution, doc ID resolution
- `jseval/scoring.py` — ir-measures wrapper for nDCG/AP/RR
- `jseval/corpora.py` — Dataset registry, BEIR + local loading
- `jseval/timeline.py` — Timeline recording, pipeline summary computation
- `jseval/preflight.py` — Backend health and model identity checks
- `jseval/backend.py` — Backend lifecycle (start/stop)
- `jseval/run_config.py` — YAML config loading and env mapping
- `jseval/compare_runs.py` — Statistical comparison with pipeline timing
- `jseval/provenance.py` — Per-hit and per-run evidence extraction
- `jseval/artifacts.py` — Output file writing (JSON, TREC)
- `jseval/result_identity.py` — Collision-safe raw-hit capture, sidecar validation, and reconciliation

When improving jseval, follow existing patterns in these files.
