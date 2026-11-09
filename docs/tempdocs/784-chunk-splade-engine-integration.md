---
title: "chunk-SPLADE engine integration — cash in the measured 5-9x sparse-leg revival on long docs (F-033 → production)"
type: tempdocs
status: "chartered (2026-07-22). The offline result is banked (tempdoc 712 / F-033 / Q-017); this lane is engine integration behind a flag, following the F-031 dense-leg playbook."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: search-engine / sparse-retrieval
related:
  - 712 (offline chunk-SPLADE probe)     # the banked measurement this lane productionizes
  - 783-long-document-representation-program  # sibling; 784 is the sparse face of the same scale problem
  - 775-evidence-span-authority          # chunk provenance conventions to reuse
---

## §A. Problem

**Historical projections (2026-11-09):** `bootstrap_ci`, `rank_diff` and `stratified_metrics` generators/tests are retired; references below describe past implementations and captured evidence. The SPLADE leg is effectively dead on long documents: 512-token encoder truncation means a
legal doc's sparse representation covers its head only (register F-033). Measured: legal
splade-mode 0.059 at defaults, while offline per-chunk SPLADE over the whole doc revives it
to **0.327 (max-pool merge) / 0.545 (chunk-MaxP)** — the largest measured, unclaimed
headroom in the engine (Q-017). This is the exact structural twin of the dense leg's F-031
(single-pass long-doc vectors, 0.060→0.297 at defaults), whose playbook — flag-gated engine
integration, fresh-build re-measure, defaults flip on evidence — already worked once.

## §B. Scope

1. Per-chunk SPLADE encoding at enrichment time for docs past the truncation boundary
   (reuse the existing chunking + `chunk_start_char` provenance; no second chunking
   authority), with a merge policy at query time: start with the two offline-validated
   candidates (max-pool merge; chunk-MaxP) behind one flag + one policy knob.
2. Index/storage shape for chunk-level sparse vectors — conform to how chunk dense vectors
   are stored (adapters-lucene); Worker-side only (Head never touches Lucene).
3. Measurement: fresh `--clean` builds; legal-clerc-200 + the 781 rebuilt strata for the
   target effect; scifact/enron/miracl as no-regression controls; both merge policies
   measured; enrichment-throughput delta captured (this ADDS encoder work on long docs —
   coordinate with 785's profiling so the two lanes don't measure past each other).
4. Ship default-OFF with the measured case; the defaults flip is a founder decision with
   register re-pins, per the now-established F-041/775 pattern.

## §C. Acceptance

- Legal splade-mode row moves into the offline-predicted band (0.3–0.55) on a fresh build,
  flag-on; hybrid-mode contribution measured (fusion may need no change — CC already
  weights splade 0.2).
- No short-doc regression (controls flat within the cohort envelope).
- Enrichment throughput delta quantified and judged acceptable (or windowed/bounded).
- Language-agnostic invariant untouched (the multilingual SPLADE model stack stays the
  only sparse authority; no per-language artifacts).
- Register: F-033 updated engine-integrated; Q-017 closed with citation.

## §D. Notes

- Storage growth is the known risk (sparse vectors × chunks); measure index size delta and
  state it — do not silently accept unbounded growth (a cap/pruning policy is in scope if
  the delta is material).

---

## §B.2-proposal — chunk-level sparse vector storage (DESIGN ONLY, 2026-07-27)

> Written by a worker agent against `worktree-agent-a073fde95379070dd` (base `961fcf42`).
> No production code changed. Every current-behaviour claim below carries a `file:line`.

### Owner-review summary (decide from this block alone)

1. **The decision asked for:** approve **Step 0**, a *free* re-measurement that tests whether
   F-036's "chunk-SPLADE is hybrid-neutral" verdict (which closed Q-017 and set default-OFF)
   was confounded — before any storage work is designed or built.
2. **§B.2's storage question is already largely answered:** chunk-level sparse vectors ship
   today. Chunk docs carry the same `splade` FeatureField, written behind
   `rag.chunk_splade.enabled` and read by `searchChunksSplade`. No new field is needed.
3. **The likely confound (measured):** fusion multiplies the SPLADE leg by
   `spladeParentLengthMultiplier`, which is **exactly 0.0 for any parent ≥4,096 tokens**
   (`HybridFusionUtils.java:803-805,914-919`) and is applied to the **chunk** branch too
   (`SearchExecutor.java:912-913`). On legal-clerc-200 that zeroes **77.8% of docs** — so
   F-036 measured a revived leg that fusion had already multiplied out.
4. **Step 0 costs one A/B run and zero code:** the threshold is a JVM system property
   (`justsearch.splade.zero_weight_min_tokens`, `HybridFusionUtils.java:26-27`).
5. **Recommended storage option if Step 0 reopens the lane:** **Option B — max-pool the chunk
   vectors into the *parent* doc's existing `splade` field**. Measured index growth: **zero**
   (the ONNX graph bakes `TopK(k=256)`, and every encode saturates it — measured 256/256).
   Query path unchanged.
6. **The one-sentence risk:** Option B costs the same encoder work as today's flag
   (**+108% enrichment wall**, F-036) while 785 measures legal enrichment at ~1.0 doc/s — so
   the throughput bill is real even though the storage bill is zero.

---

### 1. Current-state map (primary-source)

**1a. Whole-doc SPLADE — write.** The catalog declares one sparse field, `splade`, type
`splade`, `stored:false`, `rmwPolicy: reset-status:splade_status`
(`SSOT/catalogs/fields.v1.json:427-435`). `FieldMapper` maps that type to one Lucene
`FeatureField` per (token, weight) pair (`FieldMapper.java:357-364`, import at `:24`).
Parent docs are enrolled by the combined enrichment pass — "Parent doc: full enrichment
(embed + SPLADE + NER)" (`CombinedEnrichmentBackfillOps.java:331`).

**1b. Whole-doc SPLADE — the truncation.** `SpladeEncoder` hard-truncates at the model's
`maxSequenceLength`: `int seqLen = Math.min(encoding.getIds().length, maxSeqLen)`
(`SpladeEncoder.java:257`, batch paths `:381`, `:459`). The `SpladeTruncationEvidence`
sidecar only *records* the loss (`:255-256`) — it never windows. This is F-033's mechanism,
still in force.

**1c. Whole-doc SPLADE — read.** `TextQueryOps.buildSpladeQuery` builds one
`FeatureField.newLinearQuery` per query term on `"splade"` and **excludes chunk docs**
(`MUST_NOT is_chunk=true`) — `TextQueryOps.java:487-521`, exclusion at `:501-509`. The
resulting leg is the third input to the whole-doc 3-way CC fusion
(`SearchExecutor.java:458-467`).

**1d. Chunk documents — what already exists.** `ChunkDocumentWriter.regenerateChunks` creates
a chunk doc for every parent ≥ `CHUNK_THRESHOLD_CHARS = 2000` (`ChunkDocumentWriter.java:28,92`),
splitting at `DEFAULT_CHUNK_TOKENS = 500` / `DEFAULT_OVERLAP_TOKENS = 50`
(`ChunkSplitter.java:92,95`). Each chunk doc carries `is_chunk`, `parent_doc_id`,
`chunk_index/total`, `chunk_content`, `chunk_start_char/end_char`, `chunk_start_line/end_line`,
heading context, and `parent_token_count` (`ChunkDocumentWriter.java:114-176`; catalog
`fields.v1.json:274-352`). It is seeded `SPLADE_STATUS = PENDING` at creation
(`ChunkDocumentWriter.java:179`) — i.e. **the chunk document model already reserves the sparse
slot**, independent of the flag.

**1e. Chunk DENSE vectors — write/read (the precedent §B.2 asks us to conform to).**
Write: `EmbeddingBackfillOps` puts `SchemaFields.CHUNK_VECTOR` into the chunk doc's update map
(`EmbeddingBackfillOps.java:410,437`); the catalog declares `chunk_vector` as a 768-dim vector
with `rmwPolicy: preserve-reread-or-reset:chunk_embedding_status`
(`fields.v1.json:186-197`), materialized as a `KnnFloatVectorField` (`FieldMapper.java:347-355`).
Read: `ChunkSearchOps.searchChunkVector(...)` (`SearchExecutor.java:883`).

**1f. Chunk SPARSE vectors — ALREADY IMPLEMENTED.** This is the load-bearing finding for §B.2.
- *Flag:* `rag.chunk_splade.enabled` / `JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED`, default **false**
  (`EnvRegistry.java:1018`; `ResolvedConfigBuilder.java:403,1492`; threaded at
  `BackfillScheduler.java:395`).
- *Write:* the combined pass enrols the chunk doc's `chunk_content` into the **same `splade`
  field**, bundled into the chunk's existing RMW write — chunk lane
  `CombinedEnrichmentBackfillOps.java:314-327`, parent-lane pickup `:349-369`. `FAILED` is
  respected as a poison pill (`:323`, `:361`).
- *Read:* `ChunkSearchOps.searchChunksSplade(...)` — one `FeatureField.newLinearQuery` per query
  term on `SchemaFields.SPLADE`, `FILTER is_chunk=true` (`ChunkSearchOps.java:275-312`,
  query construction `:282-299`).
- *Merge:* the chunk leg joins BM25 + dense in a chunk-side 3-way CC fusion
  (`SearchExecutor.java:887-913`), then collapses to parents best-chunk-wins via
  `collapseChunkHitsToParents` (`SearchExecutor.java:929-930`), then branch-fuses with the
  whole-doc branch at 0.50/0.50 (`SearchExecutor.java:759-780`).
- *Register:* F-036 / Q-017 record this as shipped in **#145**, live-A/B'd, verdict default-OFF
  (`docs/reference/search-quality-register.md:1059-1075`, `:1824-1853`).

**Consequence for §B.2 as chartered:** the scope line "Index/storage shape for chunk-level
sparse vectors — conform to how chunk dense vectors are stored" is **already satisfied by
construction**: chunk sparse conforms to chunk dense (same doc, adjacent field, same bundled
RMW write, same is_chunk-filtered query op, same parent collapse). 784 was chartered
2026-07-22 citing an *offline* result (F-033, 2026-07-11) whose live sibling (F-036, same day)
had already shipped the integration. **There is no green-field storage decision left to make**
— only the three refinements enumerated in §3.

---

### 2. The confound: fusion multiplies the sparse leg by zero on exactly the target corpus

This was found while mapping the read path and it changes what 784 should do.

`fuseWithCC3`'s ninth parameter is `applyParentLengthModulation`
(`HybridFusionUtils.java:610-619`). When true, the SPLADE leg's weight is scaled by
`spladeParentLengthMultiplier(fields)` (`HybridFusionUtils.java:692-697`), which linearly
interpolates from **1.0 at ≤1,024 tokens to 0.0 at ≥4,096 tokens**
(`HybridFusionUtils.java:803-805` → `linearInterpolationByParentLength`, `:908-924`; bounds
`:24-27`). The multiplier reads the stored `parent_token_count` field
(`HybridFusionUtils.java:787-800`), which **chunk docs also carry**
(`ChunkDocumentWriter.java:167-169`).

`SearchExecutor` passes `true` on **both** call sites:
- whole-doc fusion — `SearchExecutor.java:458-467` (prefix `""`, `true`)
- **chunk-branch fusion** — `SearchExecutor.java:904-913` (prefix `"chunk_"`, `true`)

So the chunk SPLADE leg — whose entire purpose is that each chunk is ~500 tokens and therefore
*not* truncated — is nonetheless suppressed by its **parent's** length. The gate was built to
compensate for the truncation that per-chunk encoding removes; it now suppresses the fix.

**Measured blast radius** (method + commands in §5):

| corpus | docs | % with SPLADE weight **exactly 0** (≥4,096 tok) | % partially suppressed (≥1,024 tok) |
|---|---|---|---|
| legal-clerc-200 | 198 | **77.8%** | 97.5% |
| enron-qa | 5,485 | 7.9% | 25.6% |
| miracl-de-2k | 3,103 | 0.0% | 0.0% |

F-036's headline was `splade` mode 0.0591 → 0.2588 (4.4×) but `hybrid` 0.5625 → 0.5592 (flat),
and it attributed the flatness to *signal overlap at fusion*
(`search-quality-register.md:1067-1069`). `splade` mode is the single-leg path; `hybrid` is the
CC3 path that applies the multiplier. **The two arms differ by exactly the gate that zeroes the
leg on 77.8% of this corpus** — so the stated mechanism is unverified, and an equally
consistent explanation is that the revived leg never entered the fusion at all.

This is a `wrong-gate`-class hazard in the *evidence*, not in the code's intent: nobody changed
a flag incorrectly, but a conclusion was drawn from an arm where the measured quantity was
multiplied by zero. Per `interrogate-results`, the expected-shaped result (isolated win, fused
flat — "the F-004 pattern") is the most dangerous kind: it matched a known pattern, so it was
not dug into.

**Step 0 (recommended first action, zero code).** Both thresholds are JVM system properties read
via `Long.getLong` (`HybridFusionUtils.java:24-27`), so an arm with suppression disabled needs
no build:

```
-Djustsearch.splade.zero_weight_min_tokens=1000000000
-Djustsearch.splade.full_weight_max_tokens=1000000000
```

Re-run F-036's clean same-session A/B on legal-clerc-200 with `rag.chunk_splade.enabled=true`,
three arms: (i) flag-off baseline, (ii) flag-on + suppression as-shipped (reproduces F-036),
(iii) flag-on + suppression disabled. Short-doc control (battlefield-en-v1) and miracl-de-2k as
no-regression controls (0.0% suppressed there → arm (iii) must be identical to (ii), which is a
built-in validity check on the probe itself).

- If (iii) ≈ (ii): F-036's overlap mechanism is confirmed, Q-017's closure stands, and **784
  should close as already-answered** — no storage work at all.
- If (iii) > (ii) materially: F-036's verdict was confounded, Q-017 must be reopened, and the
  storage options in §3 become live.

---

### 3. Options for chunk-sparse storage

All four keep the invariants: Worker-side only (Head never touches Lucene); the multilingual
SPLADE stack stays the only sparse authority; no per-language artifact.

**Sizing model used throughout** — inputs are measured, the byte conversion is estimated:
- The shipped ONNX bakes `ReLU → Add(1) → Log → ReduceMax → TopK(k=256)` into the graph and
  declares `output_idx/output_weights` as `[B,256]`
  (`models/splade/naver-splade-v3/build.json:11-12,22-24`). **Posting cardinality per document
  is therefore hard-capped at 256 by the model, not by policy.**
- Measured: every encode *saturates* that cap — 256/256 slots carry weight > 0 for truncated
  whole docs **and** for chunks (6 docs, 36 chunks, mean/min/max all 256). The decode filter
  that would drop zero-weight slots (`SpladeEncoder.java:1083`) never fires.
- Measured (char-model): legal-clerc-200 = 198 docs, **4,116 chunks**, **20.8 chunks/parent**,
  median 17, p90 41, max 75. Cross-validated: my 194/198 docs >512 tokens matches F-033's
  independently tokenizer-measured 194/198 exactly, and median 7,242 est. vs 6,615 measured
  (+9.5% char-model bias, corrected where it matters).
- SPLADE vocab = 105,879 terms (`wc -l models/splade/naver-splade-v3/vocab.txt`), so 1.05M
  chunk postings spread over ≤105,879 terms ≈ 10 postings/term — the term dictionary is
  bounded and already paid for by the parent postings.
- **Byte conversion is ESTIMATED**: ~3 B/posting (VInt docID delta + VInt freq slot on a
  few-thousand-doc segment) → ~3 MB for legal-clerc-200's chunk sparse. Not measured; see §5.

**Reference footprint for the same corpus (context for "is this material?")**: the chunk docs
already exist and already carry a 768-dim float vector — 4,116 × 3,072 B = **12.6 MB of raw
`chunk_vector`** (+ HNSW graph) plus `stored:true` `chunk_content`
(`fields.v1.json:186-196`, `:302-308`). Chunk sparse is a **minority addition to storage that
is already committed**, not a new tier.

| # | Option | Shape | Write path | Query path | Index-size delta | Migration | max-pool | chunk-MaxP |
|---|---|---|---|---|---|---|---|---|
| **A** | **Status quo (shipped)** | sparse postings on the existing chunk docs, same `splade` field | already built: `CombinedEnrichmentBackfillOps.java:314-327,349-369` | already built: `ChunkSearchOps.java:275-312` | **+1,053,696 postings** (4,116 × 256) vs 50,688 for parents = 20.8× sparse postings; **est. ~3 MB**, ~24% on top of the 12.6 MB chunk-dense already present | none (field + status exist); re-enrich chunk docs only | ✗ not expressible | ✓ native (`collapseChunkHitsToParents`, `SearchExecutor.java:929-930`) |
| **B** | **Max-pool merged into the PARENT `splade` field** | encode each chunk, per-term max across chunks, re-prune to top-256, write to the parent's existing `splade` field; chunk docs get no sparse | new merge step in the parent lane; **no new field, no catalog change** | **unchanged** — `TextQueryOps.buildSpladeQuery` (`:487-521`) already reads it | **ZERO** — parent stays exactly 256 postings (measured saturation + top-256 re-prune) | re-enrich parents only; no chunk-doc churn | ✓ native | ✗ not expressible |
| **C** | **A + B together** | both | both | both | = A's (+~3 MB) | both | ✓ | ✓ |
| **D** | Dedicated `chunk_splade` field / sidecar sparse index | a second sparse field or structure | new catalog field | new query op | ≈ A's, plus a second term dictionary | catalog change + SSOT dual-copy + `check-language-agnostic-analysis` | ✓ | ✓ |

**Why D is rejected**, stated explicitly per the projection-vs-fork rule: `splade` already has the
right type, the right rmwPolicy, an existing `is_chunk`-filtered query op, and an existing status
seam. A parallel field would be a *fork* of an existing representation with an identical
type and query — a second authority that will drift, for zero capability gain.

**How the two offline-validated merge policies compute under each option:**
- *chunk-MaxP* (offline 0.545, F-033) = retrieve chunks by their own sparse score, collapse to
  parent taking the best chunk. Requires **per-chunk postings** → Options A, C, D only. Already
  native in A: `collapseChunkHitsToParents` (`SearchExecutor.java:929-930`), which is why
  F-036's arm measured the MaxP policy without new merge code.
- *max-pool merge* (offline 0.327, F-033) = per-term max across a doc's chunk vectors into one
  doc-level vector. This is a **write-time** operation on the vectors, not a query-time
  operation on the scores — it is **not expressible in Option A at all**, because A stores no
  doc-level merged vector. Options B, C, D only. Under B it is exactly a fold over the chunk
  encodes the pass already performs, plus a top-256 re-prune to hold size at zero delta.
  F-033's anti-dilution signature (`sum`-merge 0.089 ≪ `max`-merge 0.327,
  `search-quality-register.md:1088-1089`) says the fold must be `max`, never `sum`.

---

### 4. Recommendation

**Step 0 — run the free confound-check in §2 before designing storage.** This is the
recommendation. A storage design built on top of a possibly-confounded verdict is premature;
the check costs one A/B run, no code, and it is decisive in either direction.

**If Step 0 reopens the lane, the storage recommendation is Option B, then C.** Reasoning, made
explicit:

1. **B has zero storage cost — measured, not argued.** The TopK(256) bake plus measured
   saturation means the parent's sparse footprint is *identical* before and after; only the
   *contents* of the 256 slots improve. §D's storage risk is fully retired for B.
2. **B is the only option that repairs the leg branch fusion always consumes.** Branch fusion
   merges the whole-doc branch at 0.50 with the chunk branch (`SearchExecutor.java:759-780`),
   and the whole-doc branch's SPLADE leg is the *truncated* parent vector
   (`SpladeEncoder.java:257`). F-035 established the structural argument on the dense side — a
   degraded parent representation actively dilutes the chunk branch (−0.204)
   (`search-quality-register.md:1840-1843`) — and Q-017 explicitly flagged that the sparse
   parent should be *measured*, not inherited. **F-036's arm never touched the parent**: it
   enrolled chunk docs only (`CombinedEnrichmentBackfillOps.java:314-327`). So the sparse half
   of that question is still open, and B is the experiment that answers it.
3. **B needs no query-side work and no catalog change**, so it is the smallest diff that can
   move the number — and it is the *only* way to test the max-pool policy at all (§3).
4. **C afterwards**, because C is the only shape in which max-pool and chunk-MaxP can be A/B'd
   against each other on one index, which is what §B.1's "one flag + one policy knob" wants.
5. **A stays as-is** — already shipped, default-OFF, correct as a corpus-specific lever.

**Evidence that would falsify this recommendation:**
- Step 0 arm (iii) ≈ arm (ii) → F-036's overlap mechanism is right, there is no fusion headroom
  on this corpus, and **B's parent repair cannot help either**. Recommendation dies; 784 closes.
- A parent-only max-pool arm that moves `splade` mode but leaves `hybrid` flat *with suppression
  disabled* → the parent representation was not the dilution source; B was the wrong lever.
- Measured index delta for B ≠ 0 on a fresh build (i.e. the top-256 re-prune is not actually
  size-neutral in the index) → B's headline property is false and it collapses into C's cost.
- 785's throughput work failing to bring the +108% enrichment wall down → the *cost* side kills
  B and C regardless of quality, since both require encoding every chunk (see §5 risks).

---

### 5. Risks, kill criteria, and what a cap/pruning policy would look like

**R1 — Index-size growth (the §D risk).** Largely retired by measurement.
- Bound I would accept for a **default-ON** change: **≤ +10% total index size**, kill at +15%.
- Option B: **0%** (measured cardinality invariant). Option A/C: +1.05M postings on
  legal-clerc-200, est. ~3 MB, against 12.6 MB of already-present chunk dense vectors.
- The growth is **linear in chunk count and hard-capped per chunk by the model's TopK(256)** —
  it cannot run away. Worst measured chunk count in this corpus is 75 for one doc.

**R2 — Enrichment throughput (the real cost, and it is not solved by B).** Be explicit: B does
**not** save encoder work. Max-pooling requires encoding every chunk, exactly as A does — B only
avoids *storing* the per-chunk results. So both A and B carry F-036's measured **+108%
enrichment wall (132 s → 275 s on legal-clerc-200**, `search-quality-register.md:1064-1066`)
and both land on precisely the documents 785 measures at **~1.0 doc/s**
(`docs/tempdocs/785-long-doc-enrichment-throughput.md:9,20` names 784 as the lane that will add
this work). Sequencing: **do not measure 784's throughput delta before 785 has a baseline**, or
the two lanes measure past each other (784 §B.3 already says this).
- Kill criterion: if, after 785's throughput work, the enrichment wall regression is still
  >2× **and** the hybrid gain is <+0.02 nDCG@10 on legal-clerc-200, ship nothing default-ON.

**R3 — The suppression gate itself becomes a design object.** If Step 0 shows the gate is what
flattened F-036, then re-fitting it is in scope and must be done *representation-aware*, not by
deletion: the multiplier is correct for a truncated parent and wrong for a chunk. The minimal
correct shape is to stop applying parent-length modulation to the **chunk** branch
(`SearchExecutor.java:912`, ninth arg → `false`), leaving the whole-doc branch's compensation
intact — but only if B does not also repair the parent, in which case the whole-doc bound needs
re-fitting too. This is a *fusion* change, so it needs its own controls (miracl/enron, where
the gate is inert at 0.0%/7.9%, are the natural no-op checks).

**R4 — Cap/pruning policy, if growth ever becomes material.** Per-chunk cardinality is already
capped at 256 in the ONNX graph, so the only remaining lever is **chunk count**:
- *Head-N cap*: enrol only the first N chunks per parent for sparse (bounds worst case; loses
  tail recall — directly contrary to F-033's finding that R@10 0.14→0.775 comes from relevant
  terms living in *some* chunk).
- *Stride sample*: enrol every k-th chunk (uniform coverage at 1/k cost).
- *Length-gated enrolment*: enrol chunk sparse only for parents above a token threshold — the
  same shape as the existing suppression gate, and the natural one since short parents are not
  truncated and gain nothing.
- Under Option B, none of these is needed for *storage* (delta is zero); they are throughput
  levers only, which is the honest framing.

**R5 — Migration.** No option requires a schema/catalog change (A and B both reuse `splade`).
All require **re-enrichment**, and per the repo's own pitfall, existing indices do not
retroactively gain fields — measurement must be on fresh `--clean` builds
(784 §B.3 already requires this).

---

### 6. Measured vs estimated (explicit ledger)

**Measured (commands reproducible from the main checkout `F:\justsearch-public`):**
- Chunk/token distribution and chunk counts for legal-clerc-200, enron-qa, miracl-de-2k —
  `node <scratchpad>/measure_chunks.mjs datasets/mixed/<corpus>/corpus.jsonl`, replicating
  `ChunkSplitter`'s own char model (`ChunkSplitter.java:92,95,112`) and
  `ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS` (`:28`). Cross-validated against F-033's
  independent tokenizer measurement (194/198 exact match; median +9.5% bias).
- Suppression blast radius (77.8% / 97.5% for legal-clerc-200; 7.9% / 25.6% enron;
  0.0% miracl-de) — same corpora, thresholds from `HybridFusionUtils.java:24-27`, char-model
  token estimate calibrated by the 6615/7242 factor.
- SPLADE posting cardinality — `python <scratchpad>/measure_expansion.py 6` against the
  **production-shipped** `models/splade/naver-splade-v3/model.onnx` via onnxruntime 1.24.4 CPU +
  `tokenizers` 0.23.1: 6 docs truncated-whole and 36 chunks, **all 256/256 non-zero**.
- Vocab size 105,879 (`wc -l models/splade/naver-splade-v3/vocab.txt`).
- All current-behaviour claims in §1-§2 — read from source, `file:line` inline.

**Estimated (flagged as such):**
- **Bytes per posting (~3 B)** and therefore the "~3 MB" figure for Option A. Measured
  *postings*; the byte conversion is a Lucene-encoding estimate.
- **Chunk counts** are the char-model's, not the real boundary-aware splitter's output; the real
  splitter merges sub-`MIN_CHUNK_TOKENS` tails and snaps to paragraph/sentence boundaries, so
  actual counts will differ by a few percent. The ~20.8×/parent figure is consistent with
  Q-017's independently stated "~19× SPLADE forward-pass multiplier"
  (`search-quality-register.md:1830`).
- **Option B's zero-delta claim** follows from measured cardinality + a top-256 re-prune; it is
  a design property, not yet an observed index measurement.

**Could not determine (exact blocker):**
- **Actual on-disk index bytes, before/after.** No legal-clerc index exists on this machine
  (`C:\Users\Elias\AppData\Local\JustSearch\index` is an unrelated personal index from
  2026-06-11) and building one requires a dev-stack `--clean` run, which is out of scope for a
  design-only task and contended (shared stack). This is the one number §D asks for that a
  proposal cannot supply — it must come from the Step-0 / Option-B measurement runs, and I
  recommend capturing `du` of the index dir per arm at that point.


## §B.3 Step 0 executed — verdict: lane parked pending adaptive fusion (2026-07-28)

The §B.2-proposal's Step 0 ran as a 4-arm 2×2 (chunk-splade flag × parent-length gate) on
legal-clerc-200, knob-firing verified per arm (worker config line + `WorkerSpawner` JVM-opts log
line). Full table + reading in the register's F-036 RESOLVED annotation. Summary: the gate WAS
masking the isolated revival (0.2902 un-gated), but un-gating costs hybrid ~7% and the harm is
weight-policy-driven, not leg-quality-driven — A4 (garbage leg, un-gated) ≈ A3 (revived leg,
un-gated). A 0.29 leg cannot help a 0.64 ensemble under static CC weights regardless of storage
shape.

**Lane disposition:** PARKED. The §B.2 storage options (incl. zero-delta Option B) are moot until
fusion can exploit a mid-quality leg — score-aware/adaptive fusion is the prerequisite, and that
belongs to the 783 program's intervention space, not to this lane. Re-open trigger: an adaptive-
fusion mechanism that demonstrably benefits from a revived sparse leg on long docs. The Step-0
pre-declared decision rule (ship only a >10% improvement with a correctness story) resolved to
freeze: no engine change ships pre-hero.

## §K. Fusion-attribution study — the Step-0 gate-harm mechanism, per-query (2026-07-29)

**Why this ran.** §B.3 parked this lane on a mechanism claim: "a 0.29 leg cannot help a 0.64
ensemble under static CC weights." That claim was then refuted per-query on 2026-07-28 — harm is
uncorrelated with per-query SPLADE strength (rho ~ +0.08, wrong-signed; 6 of the 10 worst-harmed
queries had SPLADE STRONG). The park therefore rested on an explanation the data did not support.
This study explains the harm offline, over the existing arm artifacts. Analysis only — no engine
change was made, and none is licensed by this section.

**Deliverables.** `scripts/jseval/experiments/fusion_attribution_784.py` (pure-offline, no backend,
no GPU, no paid calls) → `tmp/784-fusion-attribution/fusion-attribution.v1.json` + `report.md`
(worktree `tmp/`, gitignored; regenerate with the command in the report header).

### §K.1 Data inventory — what the Step-0 arm dirs actually hold

Each `tmp/781-certification/step0/arm-A{1..4}/` contains: `hybrid_per_query.json` and
`splade_per_query.json` (200 records each — metrics, `predictedDocIds`, and `judgeSignals` for the
top-10 only), `hybrid_run.trec` / `splade_run.trec`, `qrels.json`, `manifest.json`, `summary.json`,
`metrics{,-worker}.ndjson`, and `projections/{bootstrap_ci,stratified_metrics,staged_recall_accounting,rank_diff,encoder_drift,...}.json`.

`judgeSignals` carries per-hit `{bm25,splade,dense}_{rank,score}`, `fusion_score`, `ce_score`
(`scripts/jseval/jseval/provenance.py:325-361`). Coverage measured across all four arms
(2,000 rows each): bm25 rank present 1,610-1,638; dense 1,102-1,159; `fusion_score` 2,000;
`ce_score` 1,990; **`splade_rank`/`splade_score` present 0 — in every arm.**

**not_derivable (honest limits).**
- *Per-leg SPLADE score under hybrid.* The typed splade leg IS attached
  (`SearchExecutor.java:470-472` → `HitProvenanceProjector.attachRetrieval`), so a null means the
  returned doc was absent from the whole-doc SPLADE candidate list. No doc reaching a final top-10
  has a recoverable SPLADE contribution.
- *Chunk-branch membership/scores.* `attachChunkMerge` puts a `chunk-merge` stage on the wire
  (`HitProvenanceProjector.java:134-164`), but `extract_judge_signals` reads only
  sparse/splade/dense/branch-fusion/fusion/cross-encoder — dropped at the artifact boundary.
- *Normalised per-leg scores, effective weights, `parent_token_count`.* Emitted only under
  `include_detail`/`debug` (`SearchResponseBuilder.java:297-299`; keys at
  `HybridFusionUtils.java:752-777`). jseval's eval path never sets `debug`, so the numeric detail
  tier was never on the wire for these arms.
- *Anything below the top-10.* A gold doc that fell out of the raised-gate top-10 has no record there.

**Ranking-semantics finding (method caveat).** The API returns the top-10 in **cross-encoder**
order — verified: 0 non-CE-descending queries across 200 queries × 4 arms, while 199/200 are
non-fusion-descending. But jseval scores with `ir_measures.ScoredDoc(score=hit["score"])`
(`retriever.py:143`) and `hit["score"]` is the fused score. So the reported nDCG@10 is the
**CE-selected set re-ordered by the fused score**. Both arms were measured identically, so Step 0's
comparison is sound — but it means a fusion change reaches the metric through two channels (which
ten docs the CE keeps, and how the fused score orders them), and it is why §K.2(iii) is observable.

### §K.2 Verdict — the knob moved two levers, and the harm belongs to the second

`SPLADE_ZERO_WEIGHT_MIN_TOKENS` is ONE static constant (`HybridFusionUtils.java:26-27`) read by
TWO functions:

| lever | function | applied at | direction |
|---|---|---|---|
| Stage 3A SPLADE leg suppression | `spladeParentLengthMultiplier` (`:803-806`) | `:693` (`fuseWithCC3`) | 1.0 → **0.0** as parents lengthen |
| Stage 3B whole-vs-chunk **branch** ramp | `chunkBranchParentLengthMultiplier` (`:826-834`) | `:488-491`, called from `SearchExecutor.java:766-780` | **0.25** → 1.0 as parents lengthen |

At the default (1024, 4096) with this corpus's median parent of 6,615 tokens (F-033), the chunk
branch runs at multiplier **1.0**. The Step-0 recipe (§B.2's own lines 200-203) raised the bounds to
1e9, which pins the chunk multiplier at the ramp's **low** end, **0.25**
(`branchChunkMinWeightMultiplier`, confirmed in every arm manifest). For a document found by BOTH
branches the effective whole:chunk split therefore moves **[0.5, 0.5] → [0.8, 0.2]** — the chunk
branch loses ~60% of its relative influence, for every document, with no involvement from SPLADE.
(Robustness: with only `zero_weight_min_tokens` raised, the multiplier is
`0.25 + (tokens-1024)/(1e9-1024)*0.75` = 0.2500 to four decimals — same conclusion. And A3's
splade-mode 0.2902 vs A2's 0.0901 proves `zero_weight_min_tokens` *was* raised.)

Under `zeroExclude` (`fuseWithCCNamed:495-503`) this bites **only** on docs present in both branches
— whole-only and chunk-only docs renormalise to weight 1.0 on their single branch and are untouched.
The gate raise thus selectively demotes exactly the documents two independent branches agreed on.

**Per-query evidence, decisiveness order.**

1. **SPLADE-invariance (decisive).** A1→A4 raises the gate over the truncated 0.0591 leg; A2→A3 over
   the revived 0.2902 leg (4.9× better). Per-query harm is **exactly identical on 195/200 queries**
   (Pearson r = 0.980, mean |diff| 0.0051, max 0.333 — the 5 differing queries are F-036's known
   chunk-splade-sensitive set). The worst-10 harmed qid lists are identical with identical deltas.
   Whatever causes the harm does not read the SPLADE leg's contents.
2. **No SPLADE displacement (refutes hypothesis (a)).** `splade_rank` null on 100% of returned hits.
   On the leg-identical comparison A1→A4, overlap between the isolated SPLADE top-10 and the final
   top-10 does not rise (1.065 → 1.045); of 259 docs entering the raised-gate top-10, 20 (7.7%) are
   SPLADE top-10 docs — *below* the 10.5% base rate. Entrants are less SPLADE-flavoured than the
   average surviving doc. (A2→A3's overlap rise 1.095 → 2.455 is the SPLADE *ranking itself*
   improving; it coincides with identical harm, so it is not the causal channel.)
3. **~23% of the harm has zero displacement (hypothesis (b), cleanly).** 13 harmed queries (A1→A4;
   12 for A2→A3) have a **byte-identical returned top-10 set** — nothing entered, nothing left —
   carrying 3.394 of 14.534 total nDCG of harm (23.35%). Seven of them lose exactly 0.3691. With the
   same ten documents and the same CE ordering, only the fused score can have moved.
4. **Arithmetic reconstruction (hypothesis (b), quantified).** Solving
   `fused = e_whole·nWhole + e_chunk·nChunk` across the two arms for every doc present in both
   top-10s: **97.4%** of 1,741 doc-pairs admit a solution with both normalised branch scores in
   [0,1] under the source-derived [0.5,0.5]→[0.8,0.2] shift. Sweeping the hypothesised shift:
   "no change" (whole weight 0.51) explains 27.9%; 0.75 explains 84.4%; 0.80 explains 97.4% and the
   curve saturates above 0.83. **This is a lower bound, not a point estimate** — but it rules out
   any small re-weighting, and adding a leg at CC weight 0.2 cannot produce a shift of this size.
   Recovered values are semantically coherent: on q0003, doc `349014` recovers nChunk 1.00 /
   nWhole 0.67 and loses 0.099 fused score, while doc `11848476` recovers nWhole 0.51 / nChunk 0.30
   and gains 0.064. (Caveat: each branch's min-max normalisation is over its own candidate set, which
   the gate can perturb — the recovered values are a consistency test, not exact arithmetic.)
5. **Worst-10 profile.** Six of the ten worst-harmed queries have splade-mode nDCG exactly **0.000**
   — SPLADE found nothing for them — and they are still among the worst harmed. Of 12 entrant docs
   across those queries, 11 carry whole-doc-branch provenance and 1 is a SPLADE top-10 doc. Two
   queries (q0003, q0005) lose 0.369 with **zero** entrants and the gold doc at an unchanged
   returned position.

**Mechanism named:** *the gate raise de-weights the Stage-3B chunk branch ~4× via a constant shared
with the SPLADE lever, demoting documents both branches found; the harm reaches the metric as a
fused-score re-ordering (~23%, no displacement) plus the pool/composition consequences of the same
re-ordering one stage earlier (~77%).* Hypothesis (a) is refuted; (b) is confirmed as the direct
channel; (c) is the same cause acting upstream; (d) is not needed.

### §K.3 What this un-parks — and what it does not

- **Step 0's decision stands.** Engine frozen, chunk-SPLADE default OFF, no pre-hero change. Right
  call, wrong reason.
- **The park's stated reason does not stand.** "A 0.29 leg cannot help a 0.64 ensemble under static
  CC weights" was never measured: no Step-0 arm isolated the SPLADE lever. The 7% is the chunk
  branch's cost, not the sparse leg's. **Whether static CC fusion can exploit a mid-quality sparse
  leg on long documents is OPEN and unmeasured.** The lane stays parked pending the §K.4 rerun, but
  it is now parked on an honest "unmeasured", not on a refuted mechanism.
- **A real engine defect is now named** (`wrong-gate` class): a knob named for SPLADE silently
  retunes the whole-vs-chunk branch balance. Any future experiment touching
  `justsearch.splade.*_tokens` is confounded until it is separated. **Not fixed here** — the fix is a
  one-line separation: give the Stage-3B ramp its own bounds
  (e.g. `justsearch.chunk_branch.{full_weight_max,min_weight_max}_tokens`) defaulting to today's
  1024/4096 so shipped behaviour is byte-identical (`HybridFusionUtils.java:826-834`).
- **A second, independent finding for the 783 program:** the chunk-branch weight is worth ~7% nDCG on
  this corpus in the wrong direction — so it is a *live lever*, not inert. Its parent-length ramp has
  never been A/B'd on its own.

### §K.4 The rerun this needs (scripted, $0, not run here)

A definitive attribution cannot be finished offline because both levers moved together and no arm
separates them. The rerun is four arms on legal-clerc-200, same session, same index generation, 200
queries, hybrid + splade modes, local GPU only:

| arm | SPLADE gate | chunk-branch ramp |
|---|---|---|
| (i) | default | default |
| (ii) | raised (via a knob that does NOT touch the branch ramp) | default |
| (iii) | default | collapsed to 0.25 |
| (iv) | raised | collapsed |

**Arm (iii) is decisive**: if it reproduces the ~7% harm on its own, the chunk-branch coupling is the
whole story, and arm (ii) then gives the first clean measurement of the SPLADE lever's true cost or
benefit. Arms (ii)/(iii) require the one-line constant separation above, so this rerun is gated on an
engine change this study was not licensed to make. Wall clock ≈ 8 min/arm (Step-0 arm timestamps
00:22 / 00:30 / 00:37 / 00:41).

**Instrumentation the rerun should add** (all cheap, all in jseval):
1. Pass `debug`/`include_detail` on eval queries — `retriever.py:165-177` already supports it;
   `run.py` never sets it. That puts the numeric detail tier on the wire.
2. Extend `extract_judge_signals` (`provenance.py:325-361`) to carry: `chunk-merge` stage rank/score;
   `whole_branch`/`chunk_branch`/`branch_merge_cc_effective_weight_*`/`cc_modifier_*`; Stage-3A
   `cc_effective_weight_{sparse,dense,splade}` + `cc_modifier_splade`; and `parent_token_count`.
3. Persist judgeSignals for the full returned window (or the pre-CE fused pool), not only the final
   top-10, so a gold doc that falls out still has a record.

**Why it was not run in this session:** the brief forbids contending for the shared backend/GPU
(another worker may hold port 33221 for hours), and arms (ii)/(iii) need an engine change outside
this study's licence. Documented as the open step, per the brief.
