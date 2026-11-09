---
title: "DESIGN-SETTLED 2026-06-24 → the remaining work's long-term design is **Staged Recall Accounting**: an eval-harness *projection* of the canonical `SearchTrace` (+ qrels) that attributes every relevance failure to one of three stages — **leg-recall / cascade-leak / judge-rank** — turning 'capability = guarantees + leaks + component quality' into a measured share. Built by re-slicing data jseval already captures (Layers 1–2, zero production change), a DEFERRED which-stage attribution layer (only if the leak share warrants it), and an LLM-oracle judge-ceiling sibling; pinned into the existing cohort-envelope/relevance ratchet (answers Q-010, subsumes Q-011 + the deferred Design-v1 long-doc eval). It is a projection of the SearchTrace seam (553 §1), NOT a new subsystem; scope-matched because 636's real present gap is *observability of recall leakage*, not a missing runtime fix (the one demonstrated leak, fusion→CE, is already fixed by v3). Principle named: **'a funnel must be observable by recall-survival, not just cardinality'** — the auditable clause of the funnel-and-judge invariant; candidate scope (recorded, not built): RAG context-budget, the agent citation funnel (harvest-not-build), the runtime truncation sites. Two standing engine rules now govern all further work: (1) stop assuming what corpus/queries users run, (2) stop designing code around it. Prior re-scope banner follows ↓ — RE-SCOPED 2026-06-23 → this tempdoc is the record of a query-time PARAPHRASE FUSION LEVER (leg arbitration, shipped DEFAULT-OFF), NOT a solution to buried-signal-in-personal-files. Rigorous shared-index validation showed it REGRESSES the product's real files (−2.1% personal email, −2.7% legal) and only wins on a synthetic paraphrase corpus (+195%), so it does not serve JustSearch's use case. The ORIGINAL buried-signal goal (a recall-stage fix for the product's personal files) is therefore UNMET by Design v2 — its correctly-aimed OPEN path is this tempdoc's own **Design v1** (the recall-stage embedding seam: late-chunking / contextual-retrieval, index-time and keyword-neutral by construction), see §'What remains' at the foot. Original title follows ↓ — Retrieval of a buried signal in long documents — the search engine should find a small distinctive fact inside a long, mostly-generic document (needle-in-haystack WITHIN a doc), diluted because a chunk's dense vector is computed from only the chunk's own text. Investigated + design-theorized: the engine ALREADY retrieves at passage granularity and scores by best passage (tempdoc 280); the missing degree of freedom is decoupling the *context a chunk vector is embedded from* from the *granularity at which it is matched* — one extension at the chunk-vector production seam (degenerate today = embed-context==chunk-text), with policies (context-prepend / late-chunking) chosen by an eval that must be built first. NOT ColBERT. Principle named: representation-context ⟂ match-granularity. Design theorized; eval-gated. UPDATE 2026-06-23: the margin-at-scale needle eval was BUILT + RUN (compose-existing) — it CONFIRMED the premise (whole-doc dense nDCG@10 collapses 0.82→0.53→0.43 across ~9× distractor scale) but REDIRECTED the fix: the seam (P1a) is NOT built because the eval measures the whole-doc vector (not the chunk vector P1a touches) and surfaced a bigger lever — lexical+CE fusion suppresses dense on paraphrase queries (vector≫hybrid). Reproducible corpus `needle-burial-v1` committed. DESIGN v2 (current, post-redirect): the fix is query-adaptive LEG ARBITRATION at the fusion layer, not the embedding seam — generalize the existing per-query `AdaptiveWeightSelector` (today a binary length switch, default off) into a per-query leg-confidence + cross-leg-agreement weight function that can DOWN-WEIGHT the lexical+CE legs when dense is confident and lexical is incoherent (the missing symmetric direction; today's low-signal gate only caps dense). This is the general recipe-weight function tempdoc 580 §10/§13 already named ('shape TBD') — now shaped + motivated + eval-validatable by F-023. Principle: symmetric per-query leg arbitration. Eval-gated on needle-burial-v1; embedding seam (v1) demoted to secondary. SHIPPED default-off (BM25-incoherence gate). HONEST OUTCOME (§Review fix #2, rigorous shared-index validation): this is a fusion-routing lever that helps PARAPHRASE/semantic queries (+195% on the needle target) but REGRESSES BM25-dominant corpora ~2–3% incl. personal email (the product's core use case, −2.1%) — so default-off is necessary, not a universal win. It does NOT solve the title's headline recall-stage 'fact buried below the cutoff in a long doc' case (that stays open: the deferred embedding-seam). Removing the BM25-dominant regression is an open research problem, not a threshold tweak. DESIGN v3 (current correctly-aimed remaining work, 2026-06-23): the demonstrated bottleneck is that the cross-encoder reranks a FUSION-RANKED prefix, so a correct dense answer that fusion buries never reaches the relevance model — fix = feed the reranker the UNION of each leg's top-N (recall-complete per leg), letting the CE arbitrate per-candidate. Keyword-NEUTRAL by construction (never down-weights a leg), so unlike Design v2 it can be default-on, and it is eval-testable on the corpora that already exist. Principle: 'fusion is a ranking step, not a recall gate'. DESIGN v4 (theorized 2026-06-24, product-aimed remaining work): the central goal — buried facts in the product's REAL (keyword-dominant, F-003) files — lives in the LEXICAL regime, where every dense-side design (v1/v2/v3) could not help. The product's failure is near-synonym vocabulary mismatch (the term IS in the doc, the user typed a synonym) — a dilution-FREE lexical problem. Design = a conditional lexical-side recall safety net: on weak-signal queries, expand into semantic variants via the existing local LLM (extending QueryUnderstanding), search the BM25+SPLADE legs, fuse. ADR-0043-compliant (learned/on-demand, not per-language). This INVERTS the stub's priority (it filed multi-query as a 'helper'; it is the real product lever). Eval-gated (build the near-synonym eval first). Principle: 'regime-matched levers — aim the lever at the regime the corpus actually occupies' (the meta-lesson of the whole v1→v4 arc; operationalized by the deferred corpus-adaptive recipe-weight function)."
type: tempdocs
status: proposed
created: 2026-06-23
author: agent analysis — originated as STUB (idea capture); investigated + premise-verified against `main` + 2025–2026 retrieval literature on 2026-06-23 (see §Investigation below). No design chosen, no implementation — per assignment.
related:
  - 635-contamination-resistant-eval-corpus     # origin — the filler-dilution wall (§Post-review fixes #2) that surfaced this
  - 580-relevance-freeze-and-fw001-thaw          # §10/§13 named the general recipe-weight function this Design v2 concretizes; FW-001 supersession
  - 280-stage3-qddf-and-chunk-level-fusion       # built the chunk-branch + best-passage-collapse this stub re-proposes as "candidates"
  - 549-unified-search-trace                     # the SearchTrace seam + per-stage cardinality "funnel rung" Staged Recall Accounting projects from
  - 553-canonical-search-execution-record        # the canonical-record + projection-not-fork register that sanctions an eval projection of SearchTrace (§1)
  - 639-candidate-set-integrity-ann-recall-and-result-dedup  # SPUN OUT of this tempdoc's coverage analysis — owns the candidate-set/leg side (ANN recall + dedup), upstream of the fusion funnel
  - 643-judge-stage-ranking-quality-next-lever   # SPUN OUT (this triage) — owns the judge side (JUDGE_RANK_LOW, the profile's dominant bucket); the symmetric counterpart to 639
---

> ## CURRENT STATE — read first (2026-06-24)
> **Historical projections (2026-11-09):** `bootstrap_ci`, `rank_diff` and `stratified_metrics` generators/tests are retired; references below describe past implementations and captured evidence.
> This tempdoc is large and **append-only**; everything below the banners is **dated history**, kept in order.
> The current truth (a projection of the latest design — see §Design theorization for why this header exists):
>
> - **Shipped (production, default-on):** v2 leg-arbitration + v3 recall-complete rerank pool — the buried-fact
>   fix for the one *demonstrated* leak (a leg's correct answer buried by fusion before the cross-encoder). v4
>   (synonym expansion) was built then **deleted** (graded 0% — the dense leg already bridges it).
> - **Built + activated (the settled remaining design — eval instrument, zero production change):** **Staged
>   Recall Accounting** — a `jseval` projection (leg-recall / cascade-leak / judge-rank) + the `leak-gate`
>   ratchet (measured-derived, pinned for 4 corpora, wired into `search-engine-hint` as the 3rd engine ratchet)
>   + the §5 judge-ceiling probe. **Cross-corpus profile produced:** leak is small everywhere; the headroom is
>   the **judge** (largest bucket) and the **legs** — this profile is the instrument's hand-off to whatever
>   builds the next lever.
> - **Settled this turn (§Design theorization):** the search problem is solved; the next *runtime* lever (a
>   sharper judge / component quality) is a **future tempdoc's** job (D-6), handed off via the profile. The
>   harness/process issues the work surfaced (multi-agent eval-backend contention, etc.) **conform to existing
>   seams** (the dev-runner lease, 553, 618) → **no new structure in 636**.
> - **Deferred by design:** Layer 3 (which internal stage leaked — leak isn't the dominant bucket); the
>   very-long-doc embedding seam (needs an eval that doesn't exist); the corpus/query router (retired by the
>   no-users rules).
> - **Opportunity space — do-able-now items now IMPLEMENTED (§IMPLEMENTED — opportunity-space):** Phase A
>   shipped — `jseval recall-profile` (#3) emitting the FP-annotated (#1) cross-corpus profile + candidate
>   recommendations (#4); 924 tests green; reproduces the known 4-corpus profile (judge-rank dominant, leak
>   smallest). Phase B (#7 Phoenix UX): the JustSearch side is **empirically validated** (a live search emits
>   the OpenInference RETRIEVER/RERANKER spans an OTLP collector captures — recipe needs
>   `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` + `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:6006/v1/traces`),
>   but Phoenix's **renderer is env-blocked on the 3.14 main env** (no `sqlean` wheel) so the **live UI was NOT
>   browser-validated**. **Completion gate (§Pre-completion confidence pass): a Python 3.12.13 *does* exist on
>   the machine, so #7 is completable in an isolated 3.12 venv — but the realistic outcome is a trace-tree +
>   retrieval/rerank drill-down, NOT the menu's "UMAP clustering" (spans carry no embeddings) and NOT a free
>   recall-survival overlay (spans carry no qid → needs a code change). So #7 is a value-vs-effort call, not a
>   feasibility one.** #2 (§5 panel) + the recorded-not-built extends stay deferred.
> - **(prior) Opportunity space + its settled design (§Opportunity space, §Design theorization — opportunity space):**
>   a research-informed menu (polish/simplify/extend/UX), none built. Key reframe from codebase investigation:
>   the answer-stage "extend" (fact/nugget recall) **already exists** as `rag_eval`/`RagQualityEvalTest`
>   (`fact_coverage`, `faithfulness`, `citation_recall`) over the **same** canonical gold record (635
>   `queries.json`, of which qrels is just the retrieval projection). So the settled design is to recognize
>   **Staged Recall Accounting + the RAG quality eval as two segments of ONE recall-survival funnel over ONE
>   gold** (conforming to 553/549/635, and to **637**'s "observe each layer's loss at its owning layer") —
>   **recorded, not built** (636's retrieval problem is solved; the answer stage is rag_eval's concern). Do-able
>   now, no design: the FP1/FP2/FP3 rename, the one-command profile, the Phoenix-reuse UX.
> - **The principle it reveals (recorded, not built):** *the D-005 discipline — observe by survival not count;
>   one canonical authority, projected not forked; no silent gate — is **layer-invariant**,* binding the
>   harness, the eval instruments, and the docs, not only the search engine.

> **⚠ RE-SCOPED (2026-06-23).** This tempdoc set out to surface buried facts in JustSearch's **real personal/local
> files**, but an eval-driven redirect turned it into a query-time **paraphrase fusion lever** ("leg arbitration").
> Rigorous shared-index validation (§Review fix #2) showed that lever **regresses the product's actual files**
> (−2.1% personal email, −2.7% legal — personal content is BM25-dominant, F-003) and only wins on a **synthetic
> paraphrase corpus** (+195%), so it ships **default-off and does not serve the product's use case**. So 636 has **two branches**: **Design v2** (the shipped fusion lever above —
> a real but narrow, opt-in tool + a negative result for the personal-files goal) and **Design v1** (the
> **recall-stage embedding seam**, theorized here and deferred) — the latter is the **correctly-aimed OPEN path** for
> the original buried-signal goal (**§What remains** at the foot). The goal is **UNMET by Design v2 but still owned by
> THIS tempdoc** via Design v1. Read 636 as the lever's history + a negative result, not as a solution to the
> personal-files goal. Everything below is dated history.
>
> **Originated as a STUB — idea + purpose only** (the sections immediately below). Captures a retrieval-engine
> limitation surfaced while building tempdoc 635's eval corpora (the "filler dilution" wall). **It has since
> been investigated and premise-verified against `main` + the 2025–2026 retrieval literature (2026-06-23)** —
> see §Investigation. The original stub text is retained as dated history; where it conflicts with §Investigation,
> the later section wins (notably: **two of the stub's four "candidate improvements" are already shipped**, and the
> stub's claim that late-interaction makes dilution "disappear by construction" is **contradicted by 2025–2026
> evidence**). **Still no design chosen and no implementation** — per the assignment, this records the verified
> premise and the sharpened decision space only.

# 636 — Retrieval of a buried signal in long documents

## The idea

JustSearch's meaning-based (dense) retrieval represents each chunk of text as **one averaged "summary
vector"**. When a document is long and mostly generic — a 500-word page whose one distinctive fact lives in a
single sentence — that averaged vector is dominated by the surrounding filler, so the distinctive fact is
**diluted away** and the document fails to rank for a query about that fact. The search engine should instead
reliably surface a small distinctive signal buried inside a long, otherwise-unremarkable document.

This is a *recall*-stage problem, not a *re-ranking* one: a cross-encoder re-ranker can only re-order
documents the first retrieval pass already surfaced, so if dilution buries the right document below the
top-K cutoff, the re-ranker never sees it. The fix has to live in how content is indexed and matched.

## The purpose / why it matters

- **It is the shape of real user files.** A long contract where the answer is one clause; a long log where it
  is one line; a long report where it is one figure. "The answer is a small part of a large document" is the
  *common* case for personal/local files — exactly JustSearch's use case — so this limitation hits real
  queries, not just synthetic ones.
- **It blocks an honest agent-utility demonstration (tempdoc 635).** The buried-signal failure is precisely
  why a clean eval corpus could not show JustSearch's retrieval beating a keyword/grep agent: the distinctive
  descriptor was swamped by filler the engine averaged over. Improving the engine is the upstream fix.
- **It is a search-quality lever, not an eval trick.** Any improvement here raises retrieval recall on the
  realistic long-document case directly (580's relevance bar), independent of the eval work that surfaced it.

## Example improvements (named, not designed or chosen)

These are candidate directions to weigh in a future design phase — ordered roughly cheap → ambitious:

- **Finer-grained chunking.** Index smaller passages (sentence / short-window) so a buried fact gets its own
  searchable unit whose vector actually represents it, instead of being averaged into ~500 words of filler.
  Trade-off: more chunks (index size + embedding compute), and smaller units carry less surrounding context.
- **"Best-passage-wins" scoring.** Score a document by its single strongest-matching passage (max), not an
  average over passages, so one strong hit is not dragged down by surrounding filler. Pairs naturally with
  finer chunking.
- **Late-interaction / multi-vector retrieval (ColBERT-style).** Keep a vector *per token/phrase* rather than
  one averaged vector per chunk, and match query terms against the closest document terms. Distinctive terms
  then match directly while filler simply does not interfere — dilution disappears by construction. The
  principled, state-of-the-art fix; also the biggest change (larger index, more complex scoring).
- **Recall safety nets (helpers, not the core fix).** Broaden the learned-sparse (SPLADE) synonym coverage so
  a distinctive term still matches under paraphrase without dilution; and/or expand each query into several
  variations (multi-query) so there are more chances to surface the right document.

## Scope boundary (for the design phase, not decided here)

Out of scope for this stub: which improvement(s) to pursue, how to implement them, the index/compute cost
trade-offs, how they interact with the existing chunk-merge + cross-encoder pipeline, and how the gain is
measured (the 580/635 relevance + buried-signal evals). This file records only the **purpose and the candidate
directions**; the design comes when the work is prioritized.

---

# Investigation (2026-06-23)

> Primary-source verification pass against `main` (search pipeline code + the search-quality register) plus an
> external 2025–2026 retrieval-literature pass. Goal per assignment: fully understand the idea/motivation, verify
> every load-bearing claim against code, and think critically — question assumptions, name alternative designs.
> **No design choice and no implementation plan here.** Internal claims cite `file:line`; external claims cite URLs.

## A. Verdict in one paragraph

**The *symptom* is real and correctly named — a single distinctive sentence inside a long, generic chunk is
diluted by the surrounding filler in a single mean-pooled vector — and 635 has a live measurement of it (a
paraphrased-head query returned distractors; dense `full` nDCG@10 0.217 on the semantic flagship, `635:932-941`).
But the stub's *solution framing is substantially out of date with the codebase and the literature.* Two of its
four "candidate improvements" — **finer-grained chunking** and **best-passage-wins scoring** — are **already
shipped** (chunk-level dense over per-chunk `chunk_vector` + a best-chunk-per-parent collapse, built by tempdoc
280; `SearchExecutor.java:803,867-889`, `ChunkSearchOps.java:488-492`). A third — **recall safety nets** (SPLADE
+ multi-query/LLM expansion) — is **also built** (`SpladeEncoder`, `QueryUnderstandingService`). So the engine is
**not** "single-vector dense averaging one summary vector per chunk" with no defenses; it already retrieves at
passage granularity and already scores by best passage. The *residual* problem is narrower and sharper:
**the shipped chunk granularity (500 tokens ≈ 375 words) is ~25× larger than the buried signal (~15 words), so the
existing per-chunk vector still dilutes it.** And the stub's "principled, SOTA, dilution-disappears-by-construction"
candidate — **late interaction / ColBERT** — is the one genuinely-unbuilt option, but the 2025–2026 evidence
shows **ColBERT itself dilutes on long filler documents** (its MaxSim is uniform over tokens; §E), so it is *not*
the guaranteed fix the stub claims. **Net: 636 is a real recall problem, but it is a granularity-and-context
problem on an already-passage-aware engine, not a "build passage retrieval" problem — and its cheapest credible
levers are ones the stub does not name (late chunking, contextual-retrieval / context-prepended chunks,
parent-child multi-granularity), not the ones it does.**

## B. Claims that hold up (verified against code)

1. **Single-vector mean-pooling dilutes a buried sentence — at the embedding level.** Dense retrieval represents
   each unit as one vector; for a sentence-embedding model (active: `gte-multilingual-base`, 768-dim, mean-pooled;
   register §6 ingestion table) a ~15-word distinctive span inside a ~375-word chunk contributes ~4 % of the
   pooled signal. The mechanism the stub names is real. (Caveat: the active encoder is the llama.cpp
   `EmbeddingService`, not the `OnnxEmbeddingEncoder` mean-pool code a sub-audit first cited — the *architecture*
   claim holds for both; don't over-cite the ONNX path as "the" pooling site.)

2. **It is a recall-stage problem, not a re-rank one.** Verified: the cross-encoder only re-orders the candidate
   list it is handed (`RagContextOps` caps candidates to top-K, GPU 50 / CPU 10, before `reranker.rerank()`); a
   document buried below the recall cutoff never reaches it. The stub's "the fix must live in indexing/matching,
   not re-ranking" is correct.

3. **"The answer is a small part of a large document" is the product's real shape.** Head-never-touches-Lucene
   over the user's own local files (CLAUDE.md Hard Invariant 1) — long contracts/logs/reports where the answer is
   one clause/line/figure are exactly the deployed corpus. The motivation is sound.

## C. Critical findings (where the stub is out of date or overclaims)

### C-1 — Two of four "candidates" are already shipped; the stub reads as if the engine has no passage path *(most important)*
The stub lists "finer-grained chunking" and "best-passage-wins scoring" as *future* candidate directions. Both
exist on `main` today, built by tempdoc 280 (the "two-branch fusion" the register documents):
- **Chunk-level dense**: documents >2,000 chars are split into 500-token chunks (`ChunkDocumentWriter.java:28,92-104`,
  `ChunkSplitter.DEFAULT_CHUNK_TOKENS=500`); each chunk gets its **own** `chunk_vector` and is an independently
  retrievable KNN unit (`ChunkSearchOps.searchChunkVector` → `KnnFloatVectorQuery(CHUNK_VECTOR…)`, `:488-492`),
  wired into the default interactive path via `executeChunkBranchFusion` (`SearchExecutor.java:803`).
- **Best-passage-wins**: `collapseChunkHitsToParents` keeps the **best chunk per parent** as the doc's score and
  takes the **max** over sibling-chunk evidence (`SearchExecutor.java:867-889,933-935`). That *is* the stub's
  "score a document by its single strongest-matching passage (max), not an average."
→ **The design phase must start from "tune/extend the existing passage path," not "introduce passage retrieval."**
Re-proposing built mechanisms as greenfield is the exact `explore-before-implementing` failure mode. The honest
residual is granularity (C-2), not absence.

### C-2 — The real root cause of 635's wall is chunk granularity ≫ signal size, not "no passage path"
635 observed dilution *even though the chunk path was active* (it noted 100 % doc-coverage, not `BLOCKED_LEGACY`;
`635:939-940`). Why? A 500-word filler doc produces ~2 chunks of ~500 tokens each; the ~15-word descriptor is
still 1/25th of its chunk, so the chunk vector still looks generic. The lever the stub gestures at ("smaller
passages — sentence/short-window") is therefore the *correct residual lever*, but it is a **parameter/segmentation
change to an existing mechanism** (`CHUNK_TARGET_TOKENS`, `MIN_CHUNK_TOKENS=100`, the 2,000-char floor, the
`chunks.size()<=1 → 0` skip) — not a new retrieval mode. Its trade-offs are real and measured-adjacent: more
chunks = more vectors + embedding compute + HNSW size; smaller units strip context a sentence embedding needs to
be distinctive; and the literature (§E) finds fixed ~200-word chunks already competitive — so "go to sentence
granularity" is **not obviously a win** and must be eval-gated, not assumed.

### C-3 — The stub's strongest claim is the one the 2025–2026 evidence refutes: ColBERT does **not** make dilution "disappear by construction"
The stub calls late-interaction "the principled, state-of-the-art fix" where "distinctive terms match directly
while filler simply does not interfere — dilution disappears by construction." The 2025–2026 record says
otherwise: on long narrative documents ColBERT-v2 / ConstBERT drop **86–97 %**, and the failure is **architectural
— MaxSim's uniform per-token weighting cannot separate signal from filler, performance plateauing around ~20
words** (TREC ToT 2025 analysis, §E). Late interaction *reduces* early pooling dilution but **reintroduces** a
filler problem at the MaxSim-aggregation step on long docs. So the stub's most ambitious, highest-cost candidate
is **not** a construction-guaranteed fix; it is another bet that needs the same buried-signal eval to justify —
at a large index-size + scoring-complexity cost. This is the single biggest correction to the stub.

### C-4 — The stub omits the cheaper, more current levers (late chunking, contextual retrieval, parent-child)
The 2025–2026 chunking literature (§E) centers on techniques the stub never names, which target *exactly* the
"distinctive sentence loses its context when chunked small" trade-off C-2 raises:
- **Late chunking** — embed the whole document first (long-context encoder), then pool per-chunk from those
  already-context-aware token embeddings; each chunk vector "remembers" the global context without a smaller
  segmentation.
- **Contextual retrieval** (context-prepended chunks) — prepend an LLM-written situating sentence to each chunk
  before embedding, so a bare distinctive span is embedded *with* what disambiguates it.
- **Parent-child / multi-granularity** — retrieve on small (sentence) units for precision, return the parent for
  context.
These are cheaper than ColBERT and directly attack dilution-vs-context. A design phase that weighs only the stub's
four options is **strictly narrower than the current state of the art** and would likely pick a dominated option.

### C-5 — Sizing: dense's *measured* contribution on personal files is small, and the eval to prove a buried-signal gain is itself blocked
Two register facts temper the priority: (a) **F-003** — on personal email (EnronQA) BM25 alone hits 0.810 and
*dense adds nothing measurable*; the buried-signal win lives specifically in the paraphrase/synonym regime where
lexical fails, which 635 shows is real but **narrow**. (b) **F-009** — extraction quality is the single largest
quality bottleneck (−15 % to −33 % nDCG); a buried fact mis-extracted is unreachable regardless of chunking. And
(c) the only eval that can *measure* a buried-signal improvement is the semantic/needle corpus 635 was building
when it hit this wall — which 635 records as **blocked on a corpus-design decision** (`635:943-950`). So 636 and
635 are mutually entangled: **you cannot credibly measure 636's fix without 635's corpus, and 635 cannot finish
its agent-demo without 636's fix.** Whichever is built first must break that circular dependency deliberately
(e.g. a tiny purpose-built needle eval that does not depend on the full 635 suite).

### C-6 — There is no continuous pressure that would catch a buried-signal regression (or reward a fix)
Per **Q-010** (register, named in 580 §4c, deliberately not built): retrieval quality is gated only by an opt-in
`jseval` run a human must remember, while presentation is gated on every edit. Any 636 work ships into an
ungated surface — its gain (or a later regression of it) would be invisible unless a buried-signal metric is
pinned into an eval that actually runs. This is a *precondition* the design phase should treat as in-scope, not
an afterthought.

## D. The decision space, laid out (named, NOT chosen — for the design phase)

Ordered cheap → ambitious, against what actually matters. (Filled from §B/§C/§E + the register; a table, not a choice.)

| Option | New mechanism? | Attacks dilution | Context cost to small spans | Index/compute cost | Eval-gated? | Already-exists? |
|---|---|---|---|---|---|---|
| **0. Tune existing chunk granularity** (↓`CHUNK_TARGET_TOKENS`, ↓floor, sentence-window) | No (param/segmentation) | Partial (smaller unit = less filler per vector) | **Worse** (bare span loses context) | Low–med (more chunks/vectors) | Must be | **Path exists**; values untuned for needles |
| **1. Late chunking** (whole-doc embed → per-chunk pool) | Encoder/pipeline change | Yes (per-chunk vector stays distinctive) | **Better** (keeps global context) | Med (long-ctx encode) | Must be | No |
| **2. Contextual retrieval** (LLM context-prepend per chunk) | Indexing-time LLM pass | Yes (span embedded with its context) | **Better** | Med–high (LLM per chunk at index) | Must be | No |
| **3. Parent-child / multi-granularity** | Retrieval + return shape | Yes (sentence precision, parent context) | Decoupled by design | Med (2 granularities indexed) | Must be | Partial (chunk↔parent link exists) |
| **4. Late interaction / ColBERT** | New index + scoring | **Partial only** (MaxSim dilutes on long filler, §C-3/E) | n/a (token-level) | **High** (multi-vector index) | Must be | No |
| **5. Recall safety nets** (broaden SPLADE / multi-query) | Tuning of built parts | Sidesteps (more shots, not less dilution) | n/a | Low | Must be | **Built** (`SpladeEncoder`, QU) |

## E. External landscape (2025–2026)

- **Late interaction is not dilution-proof on long docs.** ColBERT-v2 / ConstBERT drop 86–97 % on long narrative
  queries (TREC ToT 2025); failure is architectural — MaxSim's uniform token weighting cannot separate signal
  from filler, plateauing ~20 words. Directly refutes the stub's "disappears by construction" framing. Ongoing
  long-context variants (Jina-ColBERT-v2) exist but the limitation is acknowledged, not solved.
  [Sease: ColBERT in Practice (2025)](https://sease.io/2025/11/colbert-in-practice-bridging-research-and-industry.html) ·
  [PyLate (arXiv 2508.03555)](https://arxiv.org/html/2508.03555v1) ·
  [ColBERT-Att (arXiv 2603.25248)](https://arxiv.org/pdf/2603.25248)
- **Chunking: small fixed/sentence chunks are competitive and cheap; context preservation is the active frontier.**
  Sentence chunking ≈ semantic chunking up to ~5k tokens at a fraction of the cost; fixed ~200-word chunks
  match/beat semantic (NAACL 2025 Findings). The 2026 upgrades worth weighing are **late chunking, contextual
  retrieval, cross-granularity / hierarchical** — none named in the stub. A retrieval "context cliff" ~2,500
  tokens is reported (July 2025).
  [Firecrawl: Best Chunking Strategies 2026](https://www.firecrawl.dev/blog/best-chunking-strategies-rag) ·
  [HiChunk (arXiv 2509.11552)](https://arxiv.org/pdf/2509.11552) ·
  [Adaptive Chunking (arXiv 2603.25333)](https://arxiv.org/pdf/2603.25333)

## F. Questions the design phase must answer first (not answered here)

1. **Is the residual win worth it given F-003/F-009?** Quantify the buried-signal regime's share of real queries
   before buying any of options 1–4 (sizing, not a corpus-trick — same discipline 635 §C-1 applied to accuracy).
2. **Granularity vs context — which side of C-2's trade-off?** A measured small-chunk-vs-late-chunk-vs-contextual
   A/B on a needle corpus, not an a-priori pick. Cheapest-that-works should win, and ColBERT is *not* presumed it.
3. **Break the 635↔636 circular dependency.** Define a minimal buried-signal eval that does not wait on the full
   635 suite, so a fix is measurable.
4. **Where does the buried-signal metric get pinned so a regression fails loudly?** (Q-010; in-scope, not later.)
5. **Honest limit:** every option here is a recall bet measured by an eval that does not yet exist in runnable
   form — the corpus is the gating artifact, the same wall 635 hit. The first build step is the eval, not the
   encoder.

---

# Design (theorized 2026-06-23)

> Long-term design theorization per assignment. **General, not implementation-level.** Goal: name the structure
> the *actual* problem requires — no more, no less — reusing what exists. Grounded on the §Investigation facts +
> a feasibility probe of the embedding interface. Still **no implementation**; this settles the shape, the
> build-order, and the reach.

## Thesis (one paragraph)

The engine does not need a new retrieval mode; it needs **one missing degree of freedom on a seam it already
has.** JustSearch already retrieves at passage granularity and already scores a parent by its single best
passage (the chunk-branch + `collapseChunkHitsToParents`, built by tempdoc 280). The buried-signal failure is
not "no passage path" — it is that **the text a chunk's vector is computed from is hard-wired to equal the chunk
itself**, so the only way to shrink the matched unit toward the ~15-word signal (less dilution) simultaneously
starves that unit of the context a sentence embedding needs to be distinctive (the §C-2 trade-off the
literature confirms). The correct long-term design is to **decouple the *context a chunk vector is embedded
from* from the *granularity at which the chunk is indexed and matched*** — a single extension at the
chunk-vector *production* seam, leaving the entire retrieval/fusion/collapse path untouched. The naive current
behavior becomes the degenerate case of that seam (`embed-context == chunk-text`); richer policies (embed-wide,
or context-prepend) plug into the same seam and are chosen by measurement. This is strictly smaller than ColBERT
(no new index, no new scoring) and strictly more principled than "just shrink the chunks" (which the same
literature shows re-introduces the context loss). The size of the change is the outcome of that judgment, not a
target: it is *one production-time seam*, because that is the one place the present problem is actually stuck.

## D-1 — Reuse, do not replace: what already exists and is correct

The design **extends**, it does not rebuild. Verified present and sound on `main`:
- **Passage retrieval** — per-chunk `chunk_vector`, independently KNN-retrievable (`ChunkSearchOps.searchChunkVector`),
  run in the default path via `executeChunkBranchFusion` (`SearchExecutor.java:803`).
- **Best-passage-wins** — `collapseChunkHitsToParents` keeps the best chunk per parent + max over sibling
  evidence (`SearchExecutor.java:867-889,933-935`).
- **Granularity-aware fusion** — two-branch (whole-doc ⊕ chunk-parent) CC fusion with parent-length modulation
  (register stage 13a–13c). This is the existing *granularity-decoupling* machinery (see §Reach).
- **Recall safety nets** — SPLADE + LLM query expansion / QU, already built.
None of these is the defect. The defect is one input to the first of them.

## D-2 — The structural gap (the one thing the problem actually requires)

At chunk-embedding time the production path is, verbatim in spirit, `embedContents.add(chunkContent)`
(`CombinedEnrichmentBackfillOps` chunk branch) → `EmbeddingService.embedDocumentBatch(...)`. Two consequences,
both verified:
1. **Embed-context is bound to chunk-text.** A chunk vector only ever "sees" its own ~500 tokens; there is no
   path for it to be computed with awareness of the surrounding document.
2. **The embedding interface only emits pooled vectors** (`embed → float[]`; per-token hidden states are pooled
   and discarded inside the encoder) — and **no context-aware / late-chunking / context-prepend logic exists
   anywhere** (grep-confirmed absent). Configured embed context length is **2048 tokens** (`EmbeddingConfig`),
   i.e. ~4× a chunk — wide enough to situate a chunk, narrower than a long contract (so "wide" means a window,
   not always the whole doc).

That binding (1) is the missing degree of freedom. Everything else the problem needs is already present.

## D-3 — The seam (general statement)

Introduce, at the chunk-vector production boundary only, the notion that a chunk's dense representation is
derived from a **context span that is a superset of the chunk's own text**:

> `deriveChunkVector(chunk, contextSpan)` where `contextSpan ⊇ chunkText`.

- The **current behavior is the degenerate policy** `contextSpan = chunkText` — no migration cliff, no
  behavior change until a policy is chosen.
- The **retrieval/fusion/collapse path is unchanged** — it still consumes one `chunk_vector` per chunk; the
  vector is simply *better situated*. `CHUNK_VECTOR` stays the same field representing the same thing (a
  projection improved at its source, **not** a new representation/fork — no governance register entry needed).
- The seam carries **provenance** (which policy produced a vector) so an A/B is legible and a regression is
  attributable — consistent with the engine's existing per-stage `searchTrace` discipline.

## D-4 — Policies behind the seam (named, not chosen; chosen by eval)

The seam abstracts over a small policy family with *different feasibility costs* — which is exactly why the seam,
not a single mechanism, is the right altitude:

| Policy | What `contextSpan` is | Interface change | Inference cost | Evidence-noted risk/benefit (§ext) |
|---|---|---|---|---|
| **P0. Current** (degenerate) | the chunk itself | none | none | today's diluted behavior |
| **P1a. Structural prepend** | chunk-text prefixed by a **cheap structural** string (title + enclosing headings) | **none** (one pooled embed of a longer string) | **none** (no LLM) | cheapest; bounded gain (only as good as the structure present) |
| **P1b. LLM context-prepend** ("contextual retrieval", Anthropic) | chunk-text prefixed by an **LLM-written** situating sentence | **none** | **high** — an LLM pass *per chunk* at index time | gains are **not uniformly positive** — *decreased* Claim Recall in some pipelines (§ext); local-first compute cost is real |
| **P2. Late chunking** | the whole parent / a 2048-token window, embedded once; chunk vector = pooled token sub-range | **yes** — needs token-level hidden-state export the encoder currently discards | **low** — one long-context pass per window, no LLM | benefit **concentrates on the needle/anaphora case** (+10–12% where context disambiguates) and **makes aggressive small chunking safe** (§ext) — i.e. it is the policy most targeted at *this* problem |
| **P3. Finer granularity** (a *parameter*, composes with P1/P2) | smaller chunk unit (sentence/short-window) | none (tune `CHUNK_TARGET_TOKENS`/floor) | more vectors/HNSW | only safe **combined** with P1/P2 — late chunking is specifically the thing that makes it safe (§ext) |
| **P4. Contextually-trained embedding model** (model swap) | n/a — a model trained to embed a span *with* its context ("Context is Gold", §ext) | model swap | model-dependent | orthogonal lever; eval-gated like any encoder swap; recorded, not chosen |

Design stance: **the seam is the deliverable; the policy is an eval outcome** — and the external evidence (§ext)
*sharpens* but does not pre-decide the order. The earlier "P1-first because it needs no interface change" framing
was too clean: **P1a** (structural prepend) is genuinely the cheapest no-interface-change probe and the right
*first* measurement; **P1b** (LLM-per-chunk) is expensive and can *regress* recall, so it is not a safe default;
**P2 (late chunking) is the policy the literature most directly endorses for this exact problem** (needle +
context), at the cost of the one real interface change (token-level export). So the honest order is: **P1a as the
cheap probe → P2 as the targeted fix if P1a is insufficient → P1b only if a measured gap remains and the LLM cost
is justified.** ColBERT stays out of this family (different index + scoring subsystem, not dilution-proof —
§C-3/E). **Longer-context embedding models are NOT a substitute** (§ext): they fix *truncation*, not *dilution* —
a single whole-doc vector remains the maximally-diluted parent vector, which is the failure itself.

### External sharpening (2025–2026)

- **Late chunking is the literature's most on-point answer to *this* problem, and it is converging — not
  speculative.** Its measured benefit concentrates exactly where a small span needs surrounding context to be
  distinctive (anaphora/entity-via-context, +10–12%), and it makes otherwise-unsafe aggressive small-chunking
  *safe* — the P2+P3 combination this design names. One long-context pass, no per-chunk LLM.
  [Late Chunking (arXiv 2409.04701v3, Jul 2025)](https://arxiv.org/pdf/2409.04701) ·
  [Jina: Late Chunking](https://jina.ai/news/late-chunking-in-long-context-embedding-models/) ·
  [Weaviate: Late Chunking — precision vs cost](https://weaviate.io/blog/late-chunking)
- **Contextual retrieval (P1b) is real but not a uniform win** — consistent gains for generation but *non-uniform*
  retrieval impact (Claim-Recall *drops* in some advanced pipelines), plus an index-time LLM pass per chunk.
  [Anthropic: Contextual Retrieval](https://www.anthropic.com/engineering/contextual-retrieval) ·
  [Chunking effectiveness vs cost (arXiv 2606.00881)](https://arxiv.org/html/2606.00881v1)
- **Routing around the problem with a bigger model does not work for dilution.** Longer-context encoders
  (8k–32k) remove *truncation* artifacts but a whole-doc single vector is still diluted; the field's actual
  dilution answers are *contextual* embeddings (training-time, P4) and *span/late* pooling (the seam) — both
  instances of representation-context ⟂ match-granularity.
  [Context is Gold to find the Gold Passage (arXiv 2505.24782)](https://arxiv.org/pdf/2505.24782) ·
  [Systematic chunking + embedding-sensitivity study (arXiv 2603.06976)](https://arxiv.org/html/2603.06976) ·
  [Rethinking Chunk Size for Long-Document Retrieval (arXiv 2505.21700)](https://arxiv.org/html/2505.21700v2)

---

# Pre-implementation confidence pass (2026-06-23)

> A surprise-reduction pass *before* any implementation: resolve the design's load-bearing unverified
> assumptions with code reads + a live throwaway needle probe on the dev stack. **No feature code, no eval corpus
> authored** — the probe corpus was generated, ingested, measured, and deleted. Findings below **re-weight the
> design's confidence and priorities**; the design sections above stand but should be read through this lens.

## What was checked, and what it returned

| # | Uncertainty | Finding (file:line / measured) | Effect on design |
|---|---|---|---|
| U1 | P2 (late-chunking) feasibility — can the live encoder export token-level states? | **ONNX is the *only* live embedding backend** (llama.cpp **removed March 2026**, `EnvRegistry.java:288`); assembled at `KnowledgeServer.java:968-973` / `InferenceCompositionRoot.java:171-209`. Token matrix is in scope at the pool site (`OnnxEmbeddingEncoder.java:~507`) but threads through 4+ signatures + a gRPC `EmbeddingResult` proto field + ~393 KB/embedding storage. Embed ctx **2048**, sliding-window+mean-pool beyond. | P2 = **MODERATE, not trivial** (interface + proto + storage). Confirms the doc-drift I logged: canonical docs naming a "llama.cpp embedder" are **stale** — dense embedding is ONNX. |
| U3 | Seam surface area | **3 production sites** write `CHUNK_VECTOR`, all from chunk-content-only: `CombinedEnrichmentBackfillOps.java:279`, `EmbeddingBackfillOps.java:352` and `:360`. (`ChunkDocumentWriter` only seeds PENDING.) | The seam spans **3 call sites**, not one. Plan for it. |
| U4 | Minimal needle eval constructible without the blocked 635 suite? | **Yes, cheaply** — a bare `golden/<name>/{queries.jsonl,corpus.jsonl,qrels/test.tsv}` loads with **no certification** (`corpora.py:69-97,94`); run `jseval run --dataset golden/<name> --modes vector --start-backend`; `vector` isolates the dense leg (`retriever.py:86-98`). | The design's "eval-first" step is **unblocked**. |
| U5 | Context ceiling | 2048 tokens (`ResolvedConfigBuilder.java:1022`); docs beyond it → sliding 512-tok windows mean-pooled. | "Embed-wide" for P2 means a **2048-token window**, not always the whole doc. |
| **U2** | **Premise: does the chunk-level dense path actually dilute a buried needle?** | **Live probe (3 long generic docs, one buried ~15-word fact each, zero-overlap paraphrase query).** See below. | **Biggest re-weight — the premise reproduced only in its *weak* form.** |

## The live probe (U2) — measured, then interrogated

Setup: clean dev index, 3 docs (~735 words ≈ 950 tokens each → chunked; `chunkVectorsReady:true`,
`embeddingCompatState:COMPATIBLE`), identical filler + one distinct buried fact each; query = a **zero-lexical-
overlap paraphrase** of doc_a's fact (forces the dense leg to carry it).

| Query | Mode | Active legs (from `searchTrace`) | Result |
|---|---|---|---|
| near-exact (sanity) | vector | dense executed | doc_a **#1** (0.572) — dense works |
| zero-overlap paraphrase | **vector** | dense executed; **`branch-fusion: skipped (no-chunk-branch-contribution)`** → ranking is the **whole-doc** vector | doc_a **#1 but razor-thin** (0.557) over its filler-twins doc_c 0.514 / doc_b 0.507 |
| zero-overlap paraphrase | **hybrid** | dense + **`branch-fusion: executed`** (chunk/passage branch contributed) | doc_a **#1 decisively** (0.881 vs 0.42/0.45) |

**Interrogation (not confirmation):**
1. **The strong-form premise did NOT reproduce.** The needle was *not* buried below retrievability — the default
   **hybrid** path ranked the needle doc **first by a wide margin**, because the **existing chunk-passage branch +
   best-passage collapse** (tempdoc 280) carried it. So "single-vector dense dilutes it away → it fails to rank"
   is **too strong** for the engine as shipped *when the chunk path is active*.
2. **The weak form DID reproduce, and is the real residual.** The **whole-doc** vector (vector mode, no chunk
   contribution) separated the needle from generic twins by only **~0.04**, with the two wrong filler-twins
   essentially **tied (0.514 vs 0.507 — noise)**. That thin margin is the dilution fingerprint; at realistic
   corpus scale (hundreds of near-identical generic docs) it would plausibly **flip and bury the needle**. So the
   problem is a **margin/scale** problem, not a **can't-find** problem.
3. **The dominant *operational* failure was neither — it was gating.** The first (soft-clean) probe had **all dense
   retrieval BLOCKED** by `LEGACY_INDEX_NO_FINGERPRINT` (`embeddingCompatState:BLOCKED_LEGACY`) despite 99% chunk
   coverage — dense silently off across the whole index. Combined with the static `isShortCorpus()` gate (median
   <512 tok **or** chunkRate <0.05 disables the chunk branch entirely, `CorpusProfile.java:53-54`): **the
   passage path that mitigates dilution is conditional**, and when it's off you fall back to the very whole-doc
   vector that dilutes. This strongly suggests **635's wall was as much a gating/whole-doc artifact as a
   chunk-granularity one.**

## Net effect on the design (honest re-weight)

- **Confidence in the *necessity* of new structure (the P1/P2 seam) DROPS.** The shipped chunk-passage path
  already surfaces the needle decisively in the default path; the seam is an *improvement to a margin*, not a fix
  for a *failure to retrieve*. The design's own framing (extend, don't rebuild; eval-gate; build deferred) is
  **vindicated** — but the bar "is the win worth it?" (§C-1/§F-1) is now **sharper and more skeptical**.
- **The eval (the agreed first build step) must test *margin-at-scale*, not presence/absence.** A handful of
  needle docs is insufficient — the eval needs **many generic-filler distractors** to detect whether the thin
  whole-doc margin collapses as the filler population grows. If the margin **holds** at scale, the seam may be
  **unnecessary** and the tempdoc could close as "engine already adequate." That is now a live possible outcome.
- **Two gating facts must be first-class in any design** (they were under-weighted): the `BLOCKED_LEGACY`
  fingerprint gate and the `isShortCorpus` chunk-branch gate both **silently route to the diluted whole-doc
  vector**. A buried-signal fix that ignores them fixes the wrong stage.

## Confidence rating for the remaining work

**5 / 10.** Rationale: the *mechanics* are well-understood and low-surprise now — the seam location (3 sites),
P2 feasibility (moderate, bounded), and the eval harness (cheap, unblocked) are all pinned, so *if* we build, we
know how. The rating is held to mid-range by **problem-justification risk, not execution risk**: the live probe
showed the default path already handles the small-scale case, so the central question has shifted from "how do we
fix it" to "**does it still break at scale, and is the margin gain worth a 3-site seam + proto change?**" — and
that is explicitly **unanswered** until the margin-at-scale eval is built. Building the seam before that eval
would be acting ahead of the evidence. **Highest-value next step: build the margin-at-scale needle eval (U4 says
it's cheap) and measure whether the whole-doc dilution margin actually collapses — that single result moves this
rating decisively up (toward build) or down (toward close-as-adequate).**

---

# Phase-1 eval executed — margin-at-scale results + gate decision (2026-06-23)

> The eval-gated build order (D-6) was executed: the **margin-at-scale needle eval** — the decision artifact —
> was built (composing existing 635 infra, **zero new harness**) and run. It **confirmed the premise** but
> **redirected the fix**, so per the disciplines (`fix-root-causes`, `interrogate-results`, `audit-without-test`)
> the embedding seam is **NOT built**. Details below; the reproducible corpus is committed.

## What was built (compose-existing, verified)
- `corpus_generate.generate(semantic=True, n_chains=20, hops=1, distractor_ratio={6,30,60}, doc_words=1000,
  seed=636)` → needle docs (~1000-word generic filler, one buried distinctive head per chain) with **paraphrase
  queries** (e.g. doc "reactor in the northern marshlands" ↔ query "power station in the upper wetlands" —
  **zero lexical overlap**, forcing dense). Head-only qrels via `corpus_build.build_golden`. Ran
  `jseval run --modes vector,hybrid --start-backend --clean --embedding`. **No code changed.**
- Committed the small/fast scale as the reproducible regression corpus:
  `scripts/jseval/635-corpora/needle-burial-v1/` (280 docs; s30/s60 regenerable from the seed+ratio in its
  `meta.json`).

## Results — the whole-doc dense margin collapses monotonically with scale

| Scale | Docs | **`vector` nDCG@10** (whole-doc dense) | `hybrid` nDCG@10 | head@rank-1 (vector) |
|---|---|---|---|---|
| s6  | 280  | **0.820** | 0.318 | 12/20 |
| s30 | 1240 | **0.526** | 0.153 | — |
| s60 | 2440 | **0.429** | 0.104 | — |

(20 fixed needle queries across all scales; only distractor count grows. `vector` is `comparable=True`; `hybrid`
flagged `comparable=False` / `ann_proof FAIL` at scale.)

## Interrogation (cause, not just number)
1. **Premise CONFIRMED at scale.** The whole-doc (mean-of-means) dense vector — the maximally-diluted
   representation the design predicted — drops **0.820 → 0.526 → 0.429** as near-identical filler docs grow ~9×.
   Buried-signal dilution is **real and worsens at scale**, no longer just a thin-margin hypothesis.
2. **But the eval measures the WRONG vector for the planned fix.** `vector` mode = **whole-doc** dense;
   **P1a improves *chunk* vectors**, which P1a's seam touches and the whole-doc vector does not. The collapsing
   metric is not the one P1a would move.
3. **And jseval `hybrid` does not exercise the chunk-passage path.** Per-query records show
   `chunkMergeApplied=null` for **all 20** hybrid queries, and hybrid **loses the needle entirely on 9/20**
   queries that `vector` finds (5/20 head@1 vs 12/20). This **contradicts the live interactive probe** (§Pre-impl
   pass, where production hybrid fired `branch-fusion: executed` and ranked a needle decisively) — so jseval's
   `hybrid` ≠ the production default path, and its low numbers are **not** evidence the production path collapses.
   Separately, the `vector ≫ hybrid` inversion (0.82 vs 0.32 at s6) shows the **lexical + cross-encoder legs
   actively suppress dense on zero-overlap paraphrase queries** — a **fusion/routing** effect, not an embedding
   one (related to FW-001 / low-signal gating), and the single largest gap in the whole experiment.

## Gate decision — DO NOT build P1a (the seam) now
The pre-registered gate said "BUILD if hybrid degrades at scale," and hybrid did degrade — but **mechanical
application is wrong here** because (interrogated) the degradation's cause is **not** what P1a fixes:
- P1a targets **chunk** vectors; the eval's clean collapsing signal is the **whole-doc** vector. P1a cannot be
  validated by this eval (and re-running it after P1a would show nothing, since the eval doesn't test chunk
  vectors in a clean mode).
- The default-path (`hybrid`) numbers are **confounded** (chunk branch not applying in jseval; contradicts the
  live probe), so "the default path degrades at scale" is **not established**.
- Building P1a now would be acting on an eval that does not exercise the path it changes — the exact
  `audit-without-test` trap. **Held: do not build.**

## Revised remaining work (what the evidence now points to — for the next iteration)
1. **Make the eval isolate the chunk-passage path** and reconcile **jseval-`hybrid` vs production-`hybrid`**
   (why is `chunkMergeApplied=null` under jseval? a `chunkAware`/preset gap in the eval harness, or a real
   corpus-profile gate?). Until the eval measures the chunk-dense path P1a improves, **no seam can be gated**.
2. **Investigate the fusion/routing pathology** — `vector ≫ hybrid` on grep-defeating paraphrase queries: the
   lexical + CE legs demote/drop dense-found needles. This is a **higher-impact, already-named lever**
   (FW-001 corpus/query-adaptive recipe; low-signal gating in reverse) than the embedding seam, and the eval
   surfaced it for free. Likely the real fix for buried/paraphrase retrieval.
3. **Only then** re-gate P1a/P2 against a chunk-path-isolating eval.

## Net status
- **Premise: confirmed and now measured** (whole-doc dense dilution collapses 0.82→0.43 across ~9× scale).
- **Seam (P1a/P2): not built — correctly deferred by the evidence**, not by cost. The eval did its job: it
  prevented building an unvalidated fix and **redirected** to (a) an eval that tests the right path and (b) the
  fusion/routing lever that dominates paraphrase retrieval.
- **Durable artifact shipped:** the reproducible `needle-burial-v1` corpus — a permanent buried-signal regression
  guard (the Q-010 value), independent of whether the seam is ever built. (Pinning its floor into
  `relevance-ratchet-baselines.v1.json` is the recommended next *governance* step, deliberately left to the user
  per Q-010's "awaits a user decision" status.)
- **No user-visible feature shipped this iteration → no browser validation applicable** (the gate said don't
  build). Updated confidence for "build the P1a seam": **held at ~5/10 and now better-targeted** — the blocker is
  no longer unknown, it is the two named eval/routing prerequisites above.

---

# Design v2 (post-eval redirect, 2026-06-23) — query-adaptive leg arbitration

> The Phase-1 eval (F-023) redirected this tempdoc's fix from the *embedding* seam (Design v1, above) to the
> *fusion/routing* layer: the dominant, measured failure on buried-signal/paraphrase queries is that the engine
> **over-trusts the lexical + cross-encoder legs and suppresses the dense leg that alone finds the needle**
> (`vector` 0.82 ≫ `hybrid` 0.32 at 280 docs; hybrid *misses* the needle on 9/20 queries `vector` finds). This
> section theorizes the correct long-term design for *that* problem. General, not implementation-level. v1 (the
> embedding seam) is retained above as a now-secondary lever (it targets the chunk vector, not the measured
> whole-doc/fusion failure).

## Thesis (one paragraph)

The engine already chooses, per query, *how much to trust each retrieval leg* — but it does so through **three
disconnected, asymmetric special-cases**, each handling exactly one direction of one axis: the low-signal gate
down-weights **only dense** (a dense-hijack guard), `AdaptiveWeightSelector` flips the recipe **only by retrieved
length** (long→BM25-dominant), and `isRerankerEligible` gates CE **only by query-form + doc-length**. The
buried-signal failure is the **one direction none of them covers**: a lexically-poor (paraphrase/semantic) query
where the lexical leg matches generic filler and the cross-encoder demotes the dense-found answer — i.e.
*down-weight lexical + skip CE because dense is confident and lexical is incoherent*. The correct long-term design
is **not a new router** — it is to **generalize the per-query weight decision the system already makes** at one
existing seam (`AdaptiveWeightSelector`, already per-query, already reading every leg's result set) from a
**one-axis binary length switch into a per-query leg-confidence + cross-leg-agreement arbitration**, and to feed
the **same signal** into the existing CE gate. This is exactly the *general recipe-weight function* that tempdoc
**580 §10/§13** named as the post-FW-001 target and left "shape TBD"; F-023 now supplies the shape, the
motivation, and the validating corpus.

## D2-1 — Reuse, do not replace: what already exists and is the right home

- **`AdaptiveWeightSelector.selectWeights(bm25, dense, splade, staticWeights)`** (`adapters-lucene`) — already runs
  **per query**, already receives **all three legs' result sets**, already emits a weight vector. Today: binary
  (`median parent tokens > 2048 → BM25-dominant, else balanced`), **default off**. This is the named 580 §13
  evolution point and the single correct injection site.
- **Low-signal gating** (`HybridSearchOps`) — already reads per-leg **top scores** (`vectorTop`, `bm25Top`,
  `bm25TotalHits`) and already adjusts a leg's weight/cap. The mechanism exists; it is just **asymmetric** (dense
  only). Reuse the signal-reading; remove the asymmetry.
- **`QueryClassifier` + `isRerankerEligible`** — the existing per-query routing pattern (a signal gates
  CE/expansion). Reuse the *pattern*; the gap is the *signal* (form-based, not confidence-based).
- **`spladeParentLengthMultiplier`** — the existing precedent for continuous per-unit weight modulation.

## D2-2 — The structural gap (what the present problem actually requires)

Three things are missing, all expressible at the one seam:
1. **Symmetry** — the trust decision can down-weight *any* leg, not only dense. The lexical (and SPLADE) legs must
   be down-weightable when they are incoherent and dense is confident.
2. **Per-query, not per-corpus** — F-023 shows the failure is *per query* (paraphrase vs keyword) **within one
   corpus**; the binary `isLongCorpus`/length switch structurally cannot express it (it reads "mixed" on a
   personal corpus — the dangling-seam problem 580 already diagnosed).
3. **One signal feeding both fusion-weights and CE-gating** — CE is just another leg whose per-query trust should
   follow the same confidence signal (F-002/F-006/F-008: CE hurts exactly when retrieval is already strong).

## D2-3 — The design (general)

Generalize the per-query weight decision at `AdaptiveWeightSelector` from `lengthRegime → preset` to
`legConfidenceProfile → weights`, where the profile is computed from the **leg result sets already in scope**:

- **Inputs (post-retrieval, per query) — rank-based first:** the **primary** signal is **cross-leg agreement =
  overlap of top-K *doc IDs* (ranks, not scores) between dense and lexical**; low overlap (legs diverge) is the
  "hybrid-sensitive" regime where adaptive weighting pays off (validated externally — §D2-7/DAT). A **secondary**
  confidence input is the **bounded dense cosine top-score/gap** (the existing 0.40-threshold quantity, which is
  comparable because cosine is bounded). **Do NOT** use raw BM25 top-score as a confidence input — BM25 scores are
  unbounded and distribution-incomparable (§D2-4). Rule: legs **diverge** + dense **bounded-confident** ⇒
  "lexically-poor / semantic" ⇒ shift weight toward dense, down-weight lexical/SPLADE; legs **agree** ⇒ trust the
  blended recipe as-is. This is the 580 §13 "cross-leg rank overlap + per-leg dispersion" shape, made concrete and
  corrected to be rank-led.
- **Output:** a weight vector (not a binary preset). The current length-based presets become **one region** of the
  same function (long-doc ⇒ BM25-dominant stays valid); the new region is paraphrase ⇒ dense-dominant.
- **CE gate:** extend `isRerankerEligible` to also consult the same confidence profile — skip/soft-gate CE when
  dense is highly confident and would be demoted (subsumes the queryType-only gate).
- **Legibility:** record the chosen weights + the confidence profile in `searchTrace` (today it carries leg
  *status* but **not** scores/weights/routing rationale — a small, necessary extension so the decision is
  auditable and A/B-legible).

## D2-4 — Two constraints that fix the signal's shape (avoid two known dead ends)

1. **NOT the QPP dead end (F-019).** The signal is **post-retrieval** leg agreement/confidence, *not*
   pre-retrieval QPP (`maxIdf`/`queryScope`) — F-019 closed QPP for routing (cannot separate null from
   answerable). Named explicitly so this is not mistaken for a QPP revival.
2. **NOT raw-score fusion (the score-incomparability pitfall).** BM25 scores are **unbounded** and
   distribution-incomparable with bounded cosine; naive score normalization can *worsen* results, which is why
   rank-based RRF exists (§D2-7). So the per-query signal must be **rank-led** (cross-leg doc-ID overlap), using
   only the *bounded* dense cosine as a confidence input. This is why D2-3 leads with rank agreement, not score
   confidence — it inherits the same robustness RRF gets from ignoring raw scores.

## D2-7 — External landscape (2024–2026): the design is a validated direction, corrected on signal shape

- **Query-adaptive fusion weighting is an established, statistically-significant win** — *DAT: Dynamic Alpha
  Tuning* (arXiv 2503.23013, Mar 2025) is essentially Design v2: a **per-query** dense-vs-sparse weight with **no
  predefined categories**, and its gains are **largest precisely on "hybrid-sensitive" queries where BM25 and
  dense diverge** — direct external validation of the **cross-leg-divergence** signal D2-3 leads with. Caveat:
  DAT computes α with an **LLM judge per query** (heavier; a per-query model call) — recorded as an alternative,
  but **dispreferred for local-first latency**; the rank-agreement signal is code-only and in-scope at the seam.
  [DAT (arXiv 2503.23013)](https://arxiv.org/html/2503.23013v1)
- **Score incomparability is real and load-bearing** — BM25 unbounded vs cosine [0,1], different distributions;
  naive min-max can compress the field on a BM25 outlier; **RRF works by using ranks, not scores**. Convex-α
  tuning beats RRF with **as few as ~40 labeled query-relevance pairs** — which `needle-burial-v1` + the register
  corpora can supply (conforms to 580 §13 "fit on register optima").
  [RRF & the score-normalization problem](https://avchauzov.github.io/blog/2025/hybrid-retrieval-rrf-rank-fusion/) ·
  [Hybrid search in production — BM25 still wins on some queries](https://tianpan.co/blog/2026-04-12-hybrid-search-production-bm25-dense-embeddings)
- **Selective reranking is recognized — but stays selective.** Adaptive-rerank work gates the cross-encoder by
  query type/uncertainty (keyword→keyword signals, semantic→CE), and the field notes reranking "doesn't always
  improve and needs measurement" — corroborating F-002/F-006/F-008. But CE **generally helps 10–25%**, so the CE
  gate must **skip only when the confidence signal says so**, never blanket-off.
  [Adaptive retrieval reranking](https://ragaboutit.com/adaptive-retrieval-reranking-how-to-implement-cross-encoder-models-to-fix-enterprise-rag-ranking-failures/) ·
  [ToolRerank — adaptive truncation (arXiv 2403.06551)](https://arxiv.org/pdf/2403.06551)

## D2-5 — Eval-gated on the corpus this tempdoc just built

No weight/CE-gate change ships without a `jseval` A/B showing **net-positive-or-neutral nDCG on `golden/
needle-burial-v1`** (the paraphrase regime) **AND no regression on the keyword/long-doc register corpora**
(`beir/scifact`, `mixed/enron-qa`, `mixed/courtlistener-200`) inside the ±2σ envelope. Per `interrogate-results`,
confirm the delta is the policy, not a reindex artifact. (First, Q-011's prerequisite still holds: reconcile
jseval-`hybrid` vs production-`hybrid` so the eval exercises the path being changed.)

## D2-6 — Explicitly out of scope (do not build for cases the problem lacks)

- **No new classifier model / no new indexed field** — the signal is computed from leg result sets already in
  scope; a learned query-type model or a `source_type` field (the descoped FW-001 CE half, 580 §9.2) is a larger,
  separate effort the present problem does not require.
- **No full leg-arbitration framework** unifying all four legs into one engine — see §Reach (recorded as
  candidate scope, not built now).
- **Design v1 (embedding seam P1a/P2)** stays a secondary, lower-priority lever gated on a chunk-path-isolating
  eval — not pursued until the leg-arbitration lever is measured.

---

# Reach & principle v2 (recognized, not built)

## The principle

**Symmetric per-query leg arbitration.** Every retrieval/rerank leg (BM25, dense, SPLADE, cross-encoder) should
contribute in proportion to its **per-query** confidence and cross-leg coherence — **no leg unconditionally
trusted, none unconditionally capped, and every leg down-weightable in either direction.** Stated as the failure
it prevents: *a fixed or one-axis trust recipe will always have an un-handled direction where a confident leg is
suppressed by an incoherent one.*

## It conforms to an existing seam — do not fork it

This is an **instance of the general recipe-weight function tempdoc 580 §10/§13 already named** (and left "shape
TBD"), and it must live at the seam 580 identified (`AdaptiveWeightSelector` / `SearchExecutor` weight build), not
in a new "paraphrase router." It also conforms to the existing per-query routing *pattern* (`QueryClassifier` →
gate decisions), adding a *post-retrieval confidence* signal alongside the existing *pre-retrieval form* signal.

## Candidate scope beyond this problem (named, deliberately not built now)

The principle is already **violated in three places**, each a single-axis shard of the one policy — evidence the
shape is real, not speculative:
- **Low-signal gate** down-weights only dense (asymmetric) — the direct F-023 violation.
- **`AdaptiveWeightSelector`** flips only by length — cannot express per-query regime.
- **`isRerankerEligible`** gates CE only by form + length — not by retrieval confidence (F-002/F-006/F-008 are the
  symptoms).
A full unification — one per-query confidence profile arbitrating *all four* legs (and the chunk-branch
parent-length modulation, itself another length-only special case) — is the candidate generalization. Per the
assignment it is **recorded, not constructed**: the present problem (F-023 paraphrase/buried-signal) requires only
the **lexical-down-weight direction at the `AdaptiveWeightSelector` seam + the CE-confidence gate**. Unifying all
four legs into one arbitration framework is warranted only when a second un-handled direction is measured;
recognizing the shape now (so the extension is built *as* a step toward it, not against it) is the point.

## D-5 — Explicitly out of scope (do not build for cases the problem does not have)

- **No ColBERT / multi-vector index** — a new index + scoring subsystem for a narrow regime, and not
  construction-proof (§C-3). Disproportionate.
- **No generalization of the seam to SPLADE or the whole-doc vector yet** — those are recorded as *candidate
  scope* in §Reach, not built. The present problem is dense-chunk dilution only.
- **No per-language anything** — D-003/ADR-0043 invariant; all policies are locale-invariant by construction.

## D-6 — Build order (the eval is the first artifact, breaking the 635↔636 cycle)

The recall gain is unmeasurable without a needle corpus, and 635's full suite is itself blocked (§C-5). So the
**first** build step is a **minimal buried-signal eval** — a handful of long generic docs each hiding one
distinctive ~15-word span, with paraphrased queries — that does **not** depend on the full 635 suite, plus its
metric pinned where it will actually run (the Q-010 relevance-ratchet question, in-scope here). Only then: build
the seam at P0 (no-op, proves the path), then measure P1, then P3, then P2 if needed. Each step eval-gated; the
cheapest policy that clears the needle metric without regressing the canonical baselines wins.

---

# Reach & principle (recognized, not built)

## The principle

**Representation context ⟂ match granularity.** The span of text a representation is *computed from* should be a
free dimension, independent of the granularity at which that representation is *indexed, matched, and returned*.
A retrieval unit being small (for precision / low dilution) must not force it to be context-poor (for
distinctiveness). Stated as the failure it prevents: *do not let the matched unit's size dictate the encoder's
field of view.*

## It conforms to an existing seam — do not fork it

The system already embodies the sibling half of this principle: it decoupled **match-unit from return-unit**
(chunk retrieves; parent is scored/returned, via `collapseChunkHitsToParents` + two-branch fusion). The design
here adds the **third leg** — decoupling **embed-context from match-unit** — onto the *same* chunk/branch
machinery. It is therefore **not a parallel subsystem**: it must live in the existing chunk-vector production +
branch-fusion path, reuse `CHUNK_VECTOR`/`parent_doc_id`/`parent_token_count`, and emit provenance through the
existing `searchTrace`. Building a separate "context-aware index" would be the anti-pattern.

## Candidate scope beyond this problem (named, deliberately not built now)

The same coupling is already being *patched* elsewhere, which is evidence the principle is real, not speculative:
- **SPLADE chunk encoding** truncates at 256 tokens and the engine compensates with **parent-length weight
  modulation** (register stage 13b) — a workaround for the identical "context-stripped unit" coupling. The seam
  would let SPLADE be encoded with situating context instead of down-weighted after the fact.
- **The whole-doc parent `vector`** is the degenerate opposite endpoint — *maximum* context, *minimum*
  granularity (the whole doc) — i.e. the mean-of-means maximally-diluted vector that **is** the buried-signal
  failure. Both the parent vector and the chunk vector are points on the one axis this principle names.
Per the assignment, this is **recorded, not constructed**: the present problem requires the seam only at the
dense-chunk production point. A unified context-decoupling abstraction over {dense, sparse} × {chunk, parent}
would be premature until a second consumer (SPLADE) actually needs it — at which point this note is the warrant.
Recognizing the general shape and building the general structure are kept separate on purpose.

---

# Implementation (2026-06-23) — leg arbitration SHIPPED behind a default-off flag, validated at all three tiers

> Design v2's primary lever was implemented, eval-gated, and browser-validated, in worktree
> `worktree-636-leg-arbitration`. **Default off** (`JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED`).

## What was built (extends the existing seam; ~120 LOC + tests + 2 config keys)
- **Re-targeted to the path the default `hybrid` actually uses.** `index.hybrid.fusion_strategy` defaults to
  **`cc`**, so the default 2-way hybrid runs `executeHybrid` → `fuseWithCC(…, ccAlpha=0.5, …)` — **not** the RRF
  low-signal-gate path and **not** `AdaptiveWeightSelector` (3-way CC only). So the fix is a **per-query adaptive
  `ccAlpha`** in `HybridSearchOps.executeHybrid` (`adapters-lucene`): when the dense leg is bounded-confident (top
  cosine ≥ 0.5) AND the legs diverge (top-K doc-id Jaccard < 0.1), raise alpha toward dense (`max(ccAlpha,
  alphaDiverge)`), down-weighting the lexical leg. Rank-led signal (`topKDocIdOverlap`), per D2-4.
- **One-directional** (only raises alpha — the measured direction); `fuseWithCC` already records `cc_alpha` in
  debug scores (legibility, no new wire field). Config: `…leg_arbitration_enabled` (default false) +
  `…leg_arbitration_alpha_diverge` (default **0.7**, env-tunable). Unit tests in `HybridSearchOpsTest`.

## Eval-gate (live backend jseval A/B)

| Eval | flag OFF | flag ON | Δ |
|---|---|---|---|
| **needle-burial-v1** (paraphrase target) | 0.241 | **0.672** (α=0.7) / 0.712 (α=0.85) | **+178%** |
| **scifact** (factoid no-regression) | 0.7566 | **0.7585** (α=0.7) / 0.743 (α=0.85) | **+0.25%** at 0.7 (neutral) |
| **needle-verbatim** control (legs agree) | 0.6131 | 0.6131 | **bit-identical** (gate inert) |

**α=0.7 is the validated knee:** keeps the paraphrase win while the −1.8% scifact regression at α=0.85 vanishes.
Root cause (interrogated, per-query): 21/300 scifact queries where dense was *confident-but-wrong* and BM25 was
right; α=0.85 nearly zeroed BM25 (1.0→0.0 craters). α=0.7 keeps BM25 at 30% (preserves BM25-correct answers)
while dense (70%) still surfaces the needle. The verbatim control proves the gate is **inert when legs agree** —
the no-regression guarantee.

## Browser validation (required — user-visible ranking change)
Live UI (dev stack from the worktree dist, flag on), 4 long generic docs each hiding one fact, queried the
**zero-overlap paraphrase** "solar panel electricity output at the upper storage building on warm days" → the UI
ranked **doc_a #1**, excerpt showing the buried *"rooftop photovoltaic installation … fourteen megawatt-hours …"*,
above the three filler-twins; results header "Semantic + keyword". Screenshot captured. `static-green ≠
live-working` satisfied across unit + jseval-live + browser.

## Status & residual
- **Shipped behind a default-off flag**, validated at all three tiers: massive paraphrase win, factoid-neutral at
  the chosen default, inert when legs agree.
- **Residual (recorded, not a blocker):** the single-knob (alpha) gate has a floor on neutrality-vs-win; the
  cleaner long-term discriminator is "BM25-incoherence" (require the lexical leg *weak*, not just divergent) — a
  follow-up letting alpha be more aggressive without factoid cost. **CE-confidence gate** stays deferred
  (cross-process, 580 §9.2); **embedding seam v1** and **4-leg unification** stay deferred/recorded.
- Out-of-scope finding logged: pre-existing `HeadAssembly.java` class-size drift (1180→1189), unrelated to 636.

---

# Review fix (2026-06-23) — BM25-incoherence discriminator + complete cross-corpus validation

> A critical-review pass found the §Implementation above shipped on **incomplete validation** (only scifact;
> `enron-qa`/`courtlistener` skipped) and a **weak confidence signal** (the 0.5 dense bar ≡ cosine 0). Running the
> skipped corpora exposed a **real, severe regression** the single-corpus claim had hidden — and the fix went
> deeper than tuning. This section supersedes the "factoid-neutral on one corpus" claim above.

## What the review caught (measured)
- **courtlistener-200 (long legal, BM25-dominant): −23%** (0.6204 → 0.4756) with the alpha-only gate. The gate
  fired on legal queries where dense was *confident-but-wrong* and down-weighted BM25 — the leg that is actually
  right on long legal docs. The "validated/factoid-neutral" claim was overstated; the riskiest corpus was unchecked.

## The fix — the **BM25-incoherence** discriminator (the real lever)
The root cause: the gate fired on "dense confident + legs diverge" but could not tell *BM25-is-noise* (needle →
fire correctly) from *BM25-is-right* (legal/email → must NOT fire). The fix adds a third firing condition computed
from **BM25's own** `top2/top1` score ratio (intra-leg, so no cross-leg score-incomparability issue): fire **only
when BM25 is incoherent** (flat top, ratio ≥ `bm25IncoherenceMin`, i.e. no clear lexical winner). A peaked BM25
winner (BM25-dominant corpora) stays below the threshold and is left alone. New env-tunable key
`…_BM25_INCOHERENCE_MIN`, default **0.9** (calibrated by sweep). The dense-confidence-*gap* the review suggested
was **tried and measurement-rejected** — it over-blocked the all-similar-docs needle regime (needle 0.67 → 0.41)
without adding discrimination, so the dense bar stays a weak sanity floor and BM25-incoherence does the work.

## Complete validation (live jseval A/B, flag OFF vs ON @ α=0.7, bm25_incoherence=0.9)

| Corpus | regime | OFF | ON | Δ |
|---|---|---|---|---|
| `golden/needle-burial-v1` | paraphrase (the target) | 0.241 | **0.7123** | **+195%** |
| `scifact` | academic factoid | 0.7566 | 0.7603 | +0.5% (neutral) |
| `mixed/enron-qa` | **personal email (product use case, BM25-dominant)** | 0.7364 | 0.7325 | **−0.5% (neutral)** |
| `mixed/courtlistener-200` | long legal (BM25-dominant) | 0.6204 | 0.6062 | **−2.3%** (was −23%) |

The BM25-incoherence gate **eliminates the catastrophic courtlistener regression** (−23% → −2.3%) and keeps the
two other BM25-dominant/factoid corpora neutral, while the needle win is **preserved and slightly improved**
(0.672 → 0.7123). The courtlistener −2.3% **plateaus** even at threshold 0.97 — an irreducible residual of queries
where BM25 is genuinely flat yet still right; fully removing it needs a richer signal (a future follow-up). The
feature remains **default-off**.

## Verification at all tiers (re-confirmed)
Unit tests (`HybridSearchOpsTest`: BM25-peaked→no-fire, BM25-flat→fire, sanity-floor, never-lowers, ratio helper)
green; full `build -x test` green (compile + governance incl. class-size changeset + PMD/spotless). Live jseval
A/B above. Earlier browser validation showed the needle ranked #1 in the real UI; the sharpened gate produces an
equal/better needle result on the same backend API, so the user-visible behavior is unchanged-or-better.

## Net
The review converted an over-confident single-corpus claim into a measured one. **(Superseded by §Review fix #2
below — even this "neutral on email" claim was itself noise-confounded.)**

---

# Review fix #2 (2026-06-23) — rigorous shared-index validation corrects the record + scope boundary

> A second review pass found two of its own predecessors' problems persisted: (a) the no-regression claims still
> rested on **single, separately-built indexes**, and (b) the records still implied the tempdoc's *headline* goal
> was solved. Re-measuring on a **shared index** (build once, run OFF and ON on the *same* Lucene index, so the
> delta is the pure flag effect with zero index-rebuild confound) **overturned a key claim**.

## The rigorous numbers (shared-index, pure flag effect — supersede all earlier single-build A/Bs)

| Corpus | regime | OFF | ON @0.9 | Δ | verdict |
|---|---|---|---|---|---|
| `golden/needle-burial-v1` | paraphrase (target) | 0.241 | 0.712 | **+195%** | big win (deterministic; verbatim control bit-identical) |
| `scifact` | academic factoid | 0.7599 | 0.7641 | +0.6% | neutral |
| **`mixed/enron-qa`** | **personal email — the product's core use case** | 0.7422 | 0.7268 | **−2.1%** | **REAL regression** |
| `mixed/courtlistener-200` | long legal | 0.6054 | 0.5893 | **−2.7%** | real regression |

**The correction that matters:** my earlier single-build A/B reported enron-qa **−0.5% ("neutral")**; the shared-index
measurement shows **−2.1% (a real regression)**. The single-build number was confounded — the OFF baseline itself
moved ~0.8–2.4% between rebuilds (GPU embedding non-determinism), swamping the flag effect. **The rigor critique was
right, and it changed the conclusion.**

## Honest verdict (what this feature actually is)
- **Net win only for paraphrase/semantic workloads** (+195% on the buried-signal target). **Net loss (~2–3%) on
  keyword/entity-heavy corpora — including personal email**, which per register **F-003** is exactly the
  BM25-dominant shape of JustSearch's *primary* use case (personal files). So the feature **hurts the product's core
  corpus type** when enabled.
- Therefore **default-off is necessary, not merely cautious**, and **default-on is not recommended** for a
  general/personal-files deployment. It is a specialized, opt-in lever for paraphrase-heavy retrieval.
- The regression on both BM25-dominant corpora is the **same irreducible ambiguity**: the gate cannot tell
  *dense-found-the-buried-answer* (fire, correct) from *dense-is-confidently-wrong* (don't fire) — both look like
  "BM25 flat + legs diverge." No available fusion-site signal (BM25 flatness, dense-top-absent-from-BM25, maxIdf)
  resolves "which leg is right"; that needs a label-bearing or learned signal. So removing the residual is a genuine
  **open research problem**, not a threshold tweak — recorded as future, deliberately **not** curve-fit further.

## Scope boundary (correcting the title's implied "solved")
This work does **not** solve the tempdoc's *headline* problem — a *recall-stage* fix for a fact buried *below the
retrieval cutoff* in a *long* document ("the fix has to live in how content is indexed and matched"). The shipped
fix is a **fusion re-weighting** that only rescues a fact the dense leg **already retrieved** but the lexical leg
buried in the blend — validated on **moderate-length** docs where dense always retrieved the needle. The deep case
(severe whole-doc dilution in very long docs, where dense itself misses the fact) remains **open**, handled only by
the deferred chunk-granularity / embedding-seam work (Design v1). A reader should treat 636 as "a real fusion-routing
lever for paraphrase queries", **not** "buried-signal-in-long-docs solved".

## Methodology lesson (for future eval work)
Comparing two *separately-built* indexes confounds the A/B with embedding-rebuild noise (here ~0.8–2.4% on small
corpora — larger than the effect being measured). **Run A/Bs of a query-time flag on a single shared index**
(build once with `--clean --embedding`; re-run with `--skip-ingest`, no `--clean`) for a noise-free delta. This is
also far faster (one embed, not N) — the index-reuse throughput lever, applied.

---

# What remains — the correctly-aimed OPEN path is this tempdoc's own Design v1 (2026-06-23)

> The shipped fusion lever (Design v2) is a narrow opt-in tool and a **negative result** for the original goal
> (it regresses the product's personal files). The original goal — surface a buried fact in JustSearch's **real
> personal/local files**, at the **recall stage** — is **still owned by this tempdoc**, via **Design v1** (already
> theorized above: the *context-decoupled chunk-vector seam* — late-chunking / contextual-retrieval / finer
> granularity; principle *representation-context ⟂ match-granularity*). It is **unbuilt and open**. This section is
> the bridge: the binding **lesson Design v2 taught** + the eval prerequisites, so a future agent resumes Design v1
> aimed correctly, not from the stale stub.

## Why Design v1 (recall-stage embedding seam), not a richer Design v2 (fusion)
- **Design v2 regressed personal files because it down-weights the keyword leg at query time** — and personal
  content is BM25-dominant (F-003: dense adds nothing on email). A query-time trust-shift inherently trades keyword
  precision for dense recall, which the product's files can't afford.
- **Design v1 is an *index-time* change to the chunk vectors.** It makes a buried fact *retrievable* by improving
  its dense representation, and it **does not touch the keyword leg at query time** — so it is **keyword-neutral by
  construction**, the exact property Design v2 violated. That is why Design v1, not a curve-fit-further fusion gate,
  is the path that can actually help the product. (Keyword-neutrality must be *verified*, not assumed — see eval.)

## Binding constraint (the Design-v2 lesson, now a requirement)
Any recall-stage fix here **must not regress keyword/entity retrieval** on personal/legal corpora. This is the
acceptance gate Design v2 lacked until §Review fix #2's shared-index re-measure caught the −2.1% email regression.

## Eval prerequisites before resuming Design v1
1. A **realistic personal-files buried-signal eval** — `needle-burial-v1` is synthetic zero-overlap paraphrase and
   is **not** representative of how people search their own files. Need a corpus closer to real personal content,
   with a buried distinctive fact.
2. That eval must **pin keyword/BM25 retrieval as a no-regression guard** (per the binding constraint).
3. Use the **shared-index A/B methodology** by default (§Review fix #2 methodology lesson) — build once, vary the
   lever, re-query the same index; separately-built indexes confound the delta with embedding-rebuild noise.
4. The **late-chunking (P2) feasibility gate** still stands (§Pre-implementation confidence pass): the embedding
   backend must expose token-level states (moderate change). **P1 (structural / contextual prepend)** needs no
   interface change and is the cheaper first probe.

(No separate tempdoc was opened for this — it is 636's own deferred Design v1, kept here to avoid fragmenting the
record. An earlier draft spun it into a new tempdoc 639; that was redundant and was folded back.)

> **⚠ QUALIFIED by the Direction investigation below (2026-06-23).** Before resuming Design v1, a verification pass
> re-examined the Phase-1 gating blocker and found Design v1's *premise* is not supported by the evidence we have:
> the chunk-dense path **already fires** and whole-doc dense **already retrieves** the needle, so the demonstrated
> bottleneck is **fusion**, not chunk-vector quality. Read the section below before treating Design v1 as the open
> path — it is now "investigated, evidence-against, untested for the very-long-doc regime", not "the clear next step". **The correctly-aimed remaining work is now Design v3** (CE-arbitrated, recall-complete rerank pool — "fusion is a ranking step, not a recall gate") at the **foot** of this tempdoc.

---

# Direction investigation (2026-06-23, post-fold) — Design v1's premise is challenged; the chunk path already fires, the bottleneck is fusion

> Goal of this pass (per the assignment "investigate until confident in the direction"): resolve the Phase-1
> **Revised-work #1** blocker ("jseval `hybrid` doesn't exercise the chunk path; until it does, no seam can be
> gated"). Resolving it **reversed the premise** and reshaped the direction. Method: `verify-don't-guess` against
> the live index + harness, not the stale Phase-1 note.

## What was found (proven, file/measurement-cited)
1. **The chunk-passage branch DOES fire under jseval `hybrid`.** The run's provenance `observed` legs =
   `["branch_fusion","chunk_merge","cross_encoder","dense","hybrid","query_classification"]`, built from the
   server's own searchTrace `chunk_stage.status=="executed"` (`scripts/jseval/jseval/provenance.py:72`).
   Corroborated by the earlier live interactive probe (§Pre-impl pass: "branch-fusion: executed").
2. **The Phase-1 "chunk path not exercised" conclusion was a MISREAD.** It read the per-query top-level
   `chunkMergeApplied` (`artifacts.py:226`), which is **null because that response field is unpopulated** — a
   *reporting gap*, not the behavioral truth. The searchTrace stage (the authoritative source) says executed. So
   Phase-1 Revised-work #1's blocker **does not exist**; the chunk path was active all along.
3. **The needle index genuinely has chunks**: `chunkDocCount=1400` (280 docs × ~5 chunks), `chunkVectorsReady=true`,
   `chunkVectorCoveragePercent=100`, `chunkAwareEnabled=true` (manifest, re-ingested needle-burial-v1). Docs are
   median **6750 chars** — well over the 2000-char chunk floor (`ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS=2000`).
4. **Whole-doc dense — the *maximally diluted* vector — already retrieves the needle: `vector` nDCG@10 = 0.82** (s6).
   Hybrid (chunk + dense + lexical + CE) = **0.24**; down-weighting the lexical leg (Design v2) **recovers it to 0.71**.

## What it means for the direction (inference, strong)
The buried-signal failure **as measured is fusion suppression, not chunk-vector dilution**:
- The chunk-dense path already exists and fires; dense already retrieves the buried fact (0.82); the loss is the
  lexical+CE legs burying it in fusion (0.82→0.24), and the proven lever that recovers it is **down-weighting
  lexical** (Design v2, +195%) — *not* a better chunk vector.
- A better chunk vector (Design v1) would be subjected to the **same fusion suppression**. So Design v1 targets a
  **non-bottleneck** for this corpus. Combined with **F-003** (dense adds nothing on the product's BM25-dominant
  personal files), there is **no evidence Design v1 helps the product, and the evidence we have points the other way.**

## Honest residual (what is NOT proven — do not over-conclude the reverse)
The Phase-1 eval over-concluded by misreading a field; this pass must not over-conclude the opposite. Untested:
- **Chunk-dense in isolation** (vs whole-doc dense, esp. at distractor scale s30/s60) was never measured — the
  harness has no chunk-dense-only mode. If chunk-dense ≫ whole-doc at scale, there is a *recall* argument for finer
  vectors — but it is still bounded by the fusion bottleneck above and by F-003.
- **The very-long-doc regime (contracts/logs — the title's headline case) is untested.** needle-burial is
  ~1000-word docs, where whole-doc dense isn't badly diluted (head ≈1.2% of doc) and already works. For a 50-page
  contract (head ≈0.01% of doc) whole-doc dense would fail and chunk-dense would be the only path — but the existing
  **500-token** chunks already make a buried head ≈4% (more distinctive than the 1.2% that already works), so even
  there the *existing* chunk path is the likely lever, not Design v1's context-aware refinement. **No eval exists
  for this regime.**

## Direction (confident)
**Do not build Design v1 speculatively.** The demonstrated buried-signal lever is fusion (Design v2 — shipped
default-off, bounded by the personal-files regression); the chunk-dense path it would improve already fires and is
not the measured bottleneck. The honest status of 636's "remaining work" is therefore **not** "build the embedding
seam" but: **(a)** the buried-signal-via-dense problem is real but narrow and is a *fusion* phenomenon at the scale
we can measure; **(b)** if the very-long-doc case is ever prioritized, the **first** artifact is an eval for *that*
regime (which does not exist) — building the seam before it would repeat the `audit-without-test` /
build-ahead-of-evidence trap. 636 closes as: a narrow default-off paraphrase fusion lever + a negative result for
the personal-files goal + this evidence that chunk-vector quality is not the missing lever.

> **↳ Superseded in part by Design v3 below.** "The bottleneck is fusion" was right; "so the remaining work is only
> an eval" was premature. Tracing the fusion→reranker boundary revealed the *specific structural defect* and a
> correctly-aimed, **keyword-neutral, eval-testable-now** design. Design v3 is 636's correctly-aimed remaining work.

---

# Design v3 (2026-06-23) — CE-arbitrated retrieval: a recall-complete (per-leg-union) candidate pool for the reranker

> Long-term design theorization, general (not implementation-level). Grounded on the §Direction-investigation
> finding (the bottleneck is fusion, not chunk vectors) + a verified trace of the fusion→cross-encoder boundary.
> This is the design that survives every prior elimination: it is the *fusion* layer (where the demonstrated
> failure lives), but precise where Design v2 was blunt, and **keyword-neutral by construction** (the property
> Design v2 lacked). It is eval-gated by the corpora that already exist — so unlike Design v1 it is testable today.

## Thesis (one paragraph)

The cross-encoder (CE) is the system's strongest relevance signal — it reads (query, document) with full
cross-attention and is the one component that can actually tell *which leg is right* on a paraphrase query. But it
is applied **last, over a candidate pool selected by fusion rank** (`KnowledgeSearchEngine.java:288-291`
over-retrieves only `max(limit, rerankTopK)` hits, ordered by the fused CC/RRF score; the Worker truncates to that
window before the gRPC boundary). On a grep-defeating paraphrase query the dense leg ranks the correct doc #1, but
CC fusion blends its score against high-lexical filler (`α·denseScore + (1−α)·0` loses to `α·0 + (1−α)·highLexical`),
so the correct doc is **buried below the rerank window and never reaches the CE** — the CE cannot reorder a
candidate it never sees. This is the engine's own opening principle (a reranker only reorders what the first pass
surfaced) — but the burying stage is **fusion**, not the dense leg. The correct long-term design is to **select the
reranker's input pool as the union of each leg's top-N (recall-complete per leg), not the fusion-ranked top-K**, so
every leg's best candidates reach the CE, which then arbitrates by measured relevance. Current behavior is the
degenerate case (pool = fusion prefix). Nothing else moves: same CE, same legs, same wire types.

## D3-1 — Reuse, do not replace: what already exists and is correct
- **The CE reranker stage** (`KnowledgeSearchEngine` Head-side, `RerankerService`) — keep it; it is the arbiter.
- **The legs + their candidate pools** (`HybridSearchOps.executeHybrid`: each leg already retrieves ~100
  candidates before fusion truncates) — the union material already exists, pre-fusion.
- **The wire `SearchHit` + `HitProvenanceSignals`** — already carries per-hit leg provenance, so a union pool with
  "which leg proposed this" is expressible on the existing contract (no new representation/fork).
- **Fusion (CC/RRF)** — keep, but **demote** from "gatekeeper of the rerank pool" to "a prior/feature + the final
  tiebreaker among CE-equivalent hits." It stops being a recall gate.

## D3-2 — The structural gap (the one thing the present problem requires)
The rerank window is filled by **fusion rank** (`KnowledgeSearchEngine.java:288-291` + the Worker's
`fuseWithCC/RRF … .limit()`), so a lossy cross-leg score blend sits **between** the recall stage (legs) and the
precision stage (CE) and silently acts as a **recall gate**. A leg's correct answer that fusion underweights is
dropped before the CE — verified structurally here and empirically by the eval (`hybrid` 0.24 ≪ `vector` 0.82 on
the needle corpus; the CE is present yet cannot rescue the needle because it never receives it). That single
input — *how the rerank pool is selected* — is the missing degree of freedom. Everything else is present.

## D3-3 — The seam (general statement)
At the boundary that builds the reranker's candidate list, introduce:

> `rerankPool = ⋃_legs topN(leg)` (recall-complete per leg), ordered by the fused score as a **prior**;
> the CE's relevance score is the **arbiter**; fusion is the tiebreaker.

- **Degenerate policy = today**: `rerankPool = topK(fusedScore)` — no behavior change until the union policy is on.
- **Bounded by construction**: `|rerankPool| ≤ N · (#legs)` (e.g. N=20, ≤3 legs ⇒ ≤60), inside the CE budget —
  *not* "rerank everything" (cost) and *not* "a bigger fusion prefix" (still fusion-gatekept).
- **Provenance-carried**: each pooled hit records its proposing leg, so an A/B is legible and a regression
  attributable — consistent with the existing per-stage `searchTrace` discipline.

## D3-4 — Why it is keyword-neutral (the property Design v2 lacked, stated as the load-bearing claim)
Design v2 (D-004) regressed personal/legal corpora because it **globally down-weighted a whole leg** at query time
on a heuristic guess of which leg was right. Design v3 **never down-weights a leg**: it guarantees every leg is
represented in the pool and lets the CE judge **per candidate**. On a keyword-dominant query the lexical leg's top
candidates reach the CE, score high (they are relevant), and win — no regression. On a paraphrase query the dense
leg's top candidates reach the CE, score high, and win. The "is dense right or is BM25 right?" ambiguity that no
fusion-site proxy could resolve (D-004's irreducible residual) is resolved by **asking the relevance model**, which
is exactly what a CE is for. This is why v3 can be a candidate for *default-on* where v2 could not.

## D3-5 — Eval-gated by corpora that ALREADY exist (satisfies the eval-first discipline)
Unlike Design v1 (which needed a new chunk-isolating / long-doc eval), v3 is testable today:
- **Recovery**: does `needle-burial-v1` `hybrid` move from 0.24 toward the 0.82 the dense leg already achieves?
- **Keyword-neutrality (the binding constraint)**: shared-index A/B on `enron-qa` + `courtlistener-200` must show
  **no regression** (the §Review-fix-#2 methodology — build once, vary the lever, re-query the same index).
- **Honest pre-registration**: the hypothesis is "the CE rescues the needle once it sees it." If the CE sees the
  needle and still mis-ranks it, that is a **CE-quality** finding (the structural fix is still necessary — the CE
  cannot help what it never receives — but the gain would then need a better reranker, recorded not assumed).

## D3-6 — Architectural note (the one real boundary the change crosses)
The CE is Head-side; the legs + fusion are Worker-side; today the Worker returns the **fused** window across gRPC.
For the CE to see the per-leg union, the Worker must return the **union pool with leg provenance** instead of (or
alongside) the fused prefix. The existing `SearchResult{List<SearchHit>}` + provenance contract already supports
this shape, so it is a **selection change at the result-assembly seam**, not a new wire representation. (This
respects *head-never-touches-Lucene*: the Worker still owns retrieval; it just returns a recall-complete pool.)

## D3-7 — Explicitly out of scope (do not build for cases the problem lacks)
- **Not a learned fusion model.** 580 §13.7 / register **F-021** discredited GPL→LambdaMART learned fusion
  (structurally + by data). v3 reuses the *already-trained* CE; it adds no learned combiner.
- **Not fusion-weight tuning.** `AdaptiveWeightSelector` / leg-arbitration (580 §13.3, D-004) tune *weights*; v3
  is orthogonal — it changes *which candidates the CE sees*. They compose but do not overlap.
- **Not moving/retraining the CE, not the embedding seam (Design v1), not the very-long-doc regime** (still needs
  its own eval, §Direction investigation).

---

# Reach & principle v3 (recognized, not built)

## The principle
**"Fusion is a ranking step, not a recall gate"** — equivalently, *the precise reranker's input pool must be
recall-complete per source; a stronger downstream judge must not be gatekept by a weaker upstream combiner.* The
two-stage contract is recall-then-precision: the legs do recall, the CE does precision. Any stage that **truncates
by a lossy score** between them silently becomes a recall gate and can discard exactly what the precision stage
exists to rescue.

## It conforms to an existing seam — do not fork it
This is **not a new principle** — it is the engine's own opening framing in this very tempdoc ("a re-ranker can
only re-order documents the first retrieval pass already surfaced"), applied at the **correct** seam. The stub
located the burial at the dense leg (dilution); the evidence located it at **fusion→rerank**. So v3 conforms to the
existing recall→precision staging rather than inventing a parallel mechanism; it removes a recall gate that crept
into the middle of it.

## Candidate scope beyond this problem (named, deliberately not built now)
The same shape recurs at every pre-reranker truncation. Where it would apply, and whether existing code already
violates it:
- **Low-signal `vectorOnlyCap`** (`HybridSearchOps`, caps dense-only hits at ~10 before fusion) — a candidate
  recall gate on exactly the paraphrase/dense-only queries this tempdoc is about. **Verified NOT a violation for the
  needle case** (confidence pass below): low-signal fires on *low dense top score*, but needle queries have a
  *confident* dense top, so it doesn't fire; and even capped at 10 it keeps the dense **rank-1** needle. Stays a
  candidate-scope item for genuinely low-dense queries.
- **`collapseChunkHitsToParents`** before the CE — collapses sibling chunks by max; check it never drops a parent
  the CE would rank. *Candidate — unverified.*
- **The LambdaMART stage** (runs before the CE, 256-F2) — same pool-selection question for its input.
- **RAG context selection** (excerpt/passage budget feeding the LLM) — the generator is the "judge" there.

**Do not build the generalized "every stage is recall-complete" framework now.** The present problem requires the
fix at exactly one seam (fusion→CE). The rest is recorded candidate scope: recognizing the principle ≠ building the
general structure (the AHA / premature-abstraction discipline). A cheap follow-up is a single guard/oracle that
flags when a known-relevant doc is dropped *before* the reranker — but that, too, is for when a second instance
actually appears.

---

# Pre-implementation confidence pass — Design v3 (2026-06-23)

> Read-only de-risking before implementing v3 (no feature code). Reduced the 9 named uncertainties via code
> analysis (authoritative for gating logic) + the existing measured anchors (`vector` 0.82 / `hybrid` 0.24 /
> Design-v2-ON 0.71). Most resolved favorably; one new design subtlety surfaced; one residual remains for the A/B.

## Resolved favorably
- **Diagnosis (burial) — airtight.** CE rerank window = `topK` **20** (`RerankerConfig` default); on a paraphrase
  query CC blends the needle's high dense score against high-lexical filler, dropping it below 20, so the CE
  (`KnowledgeSearchEngine` reranks the top-20) never receives it. `hybrid` 0.24 ≪ `vector` 0.82 confirms.
- **No upstream gate (#4).** Low-signal gating (`HybridSearchOps.java:150-162`) fires on *low dense top*; needle
  queries have a *confident* dense top, so it doesn't fire — and `vectorOnlyCap=10` would still keep the dense
  rank-1 needle. The union-pool fix is sufficient; there is no second gate to lift.
- **Feasibility, no proto change (#6).** Per-leg score maps are in scope at `HybridFusionUtils` fusion; hits
  already carry per-leg provenance (`HitProvenanceSignals`, populated by `HitProvenanceProjector.attachRetrieval`).
  The Worker can return the union as a larger candidate list — the Head treats it as a bigger pool. Worker-side,
  local change.
- **LambdaMART (#8)** off by default (`NO_MODEL` skip) and reorders-only (no prune) — not a pre-CE gate.
- **Final order = pure CE score (#9).** The reranked prefix is ordered solely by the CE's `sortedIndices`; fusion
  score is not blended back. So *needle in the CE pool + CE ranks it high ⇒ needle wins.*
- **CE-quality (#1) — resolved by chaining two facts.** Design-v2-ON puts the needle into the window → result
  **0.71**; and final order is **pure CE** → therefore the 0.71 *is the CE's own ranking* of the needle. So the CE
  ranks the paraphrase needle high once it sees it. (Ceiling ≈ 0.71, not the full 0.82 — recovery is **real but
  partial**; set expectations accordingly.)

## New design subtlety surfaced (must fold into D3-3 at build time)
The CE reranks only the **top-`topK`** of whatever the Worker returns. So "return the union" is **not sufficient by
itself** — the union must either be **interleaved into the first `topK`** (round-robin each leg's top candidates) or
`topK` must be **raised to cover the union**. This **couples the union-pool to the CE budget**: `RerankerConfig`
default is `topK=20 @ deadlineBudgetMs=200`; CE cost is ~**linear** in candidate count (≈3× for 60, *not* the 9×
an O(n²)-per-pair intuition suggests — each (query,doc) pair is one fixed-512 forward pass), and the reranker can be
GPU. So the pool size is a **tunable latency parameter**, not a blocker — but the implementation must size it and
verify latency, not assume the current 20-window covers the union.

## The residual (only the A/B settles it)
- **Keyword-neutrality (#2).** Reasoned *down* substantially: the keyword-correct doc stays in the pool (its leg
  proposes it); the current CE pool already contains dense-influenced candidates; and v3 only adds *new* candidates
  where the legs **disagree**, where a **relevance model (CE)** adjudicates per-doc — far safer than Design v2's
  blind whole-leg down-weight that caused the −2.1%/−2.7% regression. But the *magnitude* of any residual keyword
  regression is unprovable pre-implementation; it is exactly what the shared-index A/B on `enron-qa` +
  `courtlistener-200` (the §Review-fix-#2 method) exists to measure. This caps confidence honestly.

## Confidence rating for implementing Design v3: **7 / 10**
Diagnosis, feasibility (no proto change), upstream-gate clearance, and CE-quality are all resolved; the mechanism is
sound and cleanly buildable on the existing contract. Held back from 8+ by two genuine unknowns that only
implementation settles: the **keyword-neutrality magnitude** (the key residual) and the **pool-size↔latency** tuning
(the new subtlety). Both are bounded and are precisely what the build + eval A/B will resolve — not unknowns that
could invalidate the approach.

---

# Design v3 IMPLEMENTED + validated (2026-06-23) — recall-complete rerank pool, default-off

## What was built (extends existing structure; ~2 call sites + 1 helper + 2 config keys)
- **`HybridFusionUtils.spliceRecallComplete(fused, protectedHits, limit)`** — pure helper: guarantees every
  `protectedHit` is present in the returned list (displacing the lowest-fused non-protected hits), preserving the
  fused prefix; no-op (byte-identical) when all protected are already present. + `topN(result, n)`.
- **Call site 1 — `HybridSearchOps.executeHybrid`** (bm25+dense CC): `protected = topN(textResult) ∪
  topN(vectorResult)`, applied before `attachRetrieval` so provenance re-attaches to spliced hits.
- **Call site 2 — `SearchExecutor` branch fusion** (whole ⊕ chunk): the eval proved this is REQUIRED — call-site-1
  splices the needle into the whole-doc branch at a low synthetic score, and branch fusion re-buries it before the
  CE. Re-asserts the guarantee on the branch-fused list, identifying protected docs by the **dense/bm25 leg rank
  carried in their whole-doc provenance** (presence, not score). Applied before `attachBranchFusion`.
- **Config** (mirrors the Design v2 keys): `JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE` (bool, default false) +
  `JUSTSEARCH_HYBRID_RERANK_POOL_TOP_N` (int, default 10) → `ResolvedConfig.HybridSearch.{legRecallCompleteEnabled,
  legRecallCompleteTopN}`. Final order is **pure cross-encoder** (`KnowledgeSearchEngine.java:661-670`), so the
  spliced hit's synthetic score is irrelevant — once it is in the CE window the CE alone ranks it.
- Unit tests: `HybridFusionUtilsTest.SpliceRecallCompleteTests` (no-op equivalence, buried-hit splice, order,
  disjoint-leg coverage, null-safety). Build + governance (class-size) green.

## Validated — rigorous shared-index A/B (OFF vs v3 ON, `top_n`=10)
| Corpus | regime | OFF | v3 ON | Δ | (Design v2 was) |
|---|---|---|---|---|---|
| `golden/needle-burial-v1` | paraphrase target | 0.2408 | **0.4377** | **+82%** | +195% |
| `mixed/enron-qa` | personal email | 0.7441 | 0.7372 | **−0.9%** | −2.1% |
| `mixed/courtlistener-200` | long legal | 0.6204 | 0.6217 | **+0.2% (neutral)** | −2.7% |

## Honest verdict — much closer to the goal than v2, but not perfectly neutral
- v3 is **dramatically more keyword-safe than v2**: legal **−2.7% → neutral**, email **−2.1% → −0.9%**, while
  still recovering **+82%** on the buried-signal target. It is the better lever on every axis except raw needle gain.
- It is **not perfectly keyword-neutral** as theorized: the **displacement** mechanism — to fit dense-top-N into the
  capped 20-doc CE window, the splice drops the lowest-fused hits, and on a BM25-dominant corpus those are sometimes
  relevant — costs the −0.9% on email. So "keyword-neutral by construction" was too strong; the cost is small and
  bounded, not zero.
- v3 recovers **less** than v2 (+82% vs +195%) because v2 *also* strips lexical filler from the CE window (by
  down-weighting the lexical leg — the very thing that regresses keyword corpora); v3 keeps the filler, so the CE
  ranks the needle among it (~3rd–5th). 0.44 is the keyword-neutral recovery ceiling for *this* window size.
- **Ships default-off** (the −0.9% email residual blocks unconditional default-on by the same standard applied to
  v2), but it is a **default-on candidate** once the residual is closed.

## Path to full neutrality (tested-next levers, not yet run)
The −0.9% is **displacement**, so the fix is to **stop displacing** relevant hits from the CE window:
1. **Lower `top_n`** (e.g. 3): protects only the strongest leg candidates (the needle is dense-rank-1, still
   protected), so far fewer dense distractors displace relevant hits — a free config change (no rebuild).
2. **Widen the CE window** (raise `JUSTSEARCH_RERANK_TOP_K` so the pool covers full-fused-prefix + dense-top-N with
   no displacement) — costs CE latency (the pool↔latency dial), but should drive email to neutral.
Either is a tuning follow-up; the structural fix (the recall-complete pool) is implemented and proven.

## Browser validation (attempted; confounded surface — jseval is authoritative)
Validation tier for the user-visible ranking change. Outcome: **the controlled jseval A/B is the authoritative
end-to-end validation** (it drives the identical `/api/search` → Head → Worker → fusion → splice → **cross-encoder**
→ ranking path the UI uses, scored against qrels — +82% mean). The interactive **dev stack is a poor surface here**
for three reasons discovered during the attempt: (1) **the cross-encoder is OFF in dev mode by default**
(`dev-runner.cjs` — reranker/citation-scorer inactive unless `JUSTSEARCH_RERANK_MODEL_PATH` is set), and the whole
feature *feeds* the CE, so without it the pool change is inert; I enabled it via a temporary dev-runner env to
proceed; (2) the dev stack **auto-indexed stray `ssot/docs/help/*.md`** into the needle corpus, polluting the top
ranks; (3) the recovery is **partial and query-dependent** (mean 0.44, not 1.0), so a single hand-picked query is
not representative. Net: a clean UI screenshot would require reproducing the eval's controlled conditions (pure
corpus, matched CE config, qrel scoring) in the interactive stack — which adds no signal over the measured A/B. The
ranking behavior is validated; the dev-stack UI is simply the wrong instrument for a scale-sensitive, CE-dependent,
mean-effect feature. (Temporary enabling edits — config default + dev-runner reranker env — were reverted; build
green, default-off.)

---

# Design v4 (theorized 2026-06-24) — the lexical-side near-synonym recall safety net (the product-aimed direction)

> Long-term design theorization for the **remaining work**, general (not implementation-level). It follows the
> realization (§"why not" analysis) that the tempdoc's central purpose — buried facts in the product's *real*
> files — lives in the **lexical** regime, where every dense-side design (v1/v2/v3) could not help. The
> corpus/query-adaptive **router that would choose between regimes is the deferred Item-1** (out of scope here);
> this design instead strengthens the *one leg* the product's failures actually need.

## Thesis (one paragraph)
On the product's real personal/local files the buried-fact failure is almost always **lexical vocabulary
mismatch**: the distinctive term genuinely *is* in the document (so there is no dilution problem — the lexical and
learned-sparse legs match a term regardless of document length), but the user typed a **near-synonym** the index
term doesn't equal. The engine already bridges *some* of this via the multilingual **SPLADE** learned-sparse model,
but its coverage is **bounded by a frozen MS-MARCO-trained model** — arbitrary near-synonyms (e.g. *power station*↔
*reactor*) are not guaranteed to co-activate. The correct long-term design is a **conditional lexical-side recall
safety net**: when a search returns a *weak* signal (the likely-missed case), expand the query into a few semantic
variants via the **already-present local LLM** (extending the dormant `QueryUnderstandingService` seam), search the
variants on the **lexical + sparse** legs, and fuse. This bridges *arbitrary* near-synonyms on the **dilution-free**
side — the one place the product's buried-fact recall can actually be lifted. It **inverts the stub's priority**:
the stub filed multi-query expansion as a "helper, not the core fix" and aimed the core fix at dense — but dense is
the wrong regime for the product (F-003), so the "helper" is in fact the product-aimed lever.

## D4-1 — Reuse, do not replace
- **SPLADE learned-sparse** (`SpladeEncoder`, multilingual, default-on) — the *always-available* lexical bridge;
  keep it as the baseline. The design is its **on-demand complement**, not a replacement.
- **`QueryUnderstandingService`** (Head-side, local-LLM, schema + 8s deadline + graceful fallback; default-off,
  tempdoc 363) — the existing seam. It already calls the LLM and degrades safely; today it emits *filters*. Extend
  it to also emit **semantic query variants**.
- **The fusion layer** (`HybridFusionUtils`) and the **weak-result / zero-hit-retry** corrections seam — the
  trigger + merge machinery already exist.

## D4-2 — The structural gap (the one thing the present problem needs)
SPLADE's term-bridging is a *fixed* function of a frozen model — there is **no on-demand path** to bridge a
near-synonym the model never learned, and **no language-agnostic mechanism** other than the LLM can supply one
(ADR-0043 forbids per-language synonym artifacts; dense cannot help per the dilution limit + F-003). The missing
degree of freedom is a **learned, on-demand, language-agnostic query expansion** feeding the lexical legs.

## D4-3 — The seam (general statement)
> When a query's result is **weak-signal**, derive a small set of **query-term synonym/paraphrase variants** from
> the local LLM, run each on the lexical + sparse legs, and **fuse** the variant results into the candidate set.

The expansion form is **query-term synonym/paraphrase expansion** — *not* generative pseudo-document expansion
(Query2Doc/HyDE). This is a deliberate, evidence-grounded choice (§D4-7): on **private** files the LLM cannot know
the buried fact, so fabricating a pseudo-document would **hallucinate the answer**; expanding the *query's own
distinctive terms* into synonyms ("power station" → "reactor", "generating plant") uses only the LLM's **general
vocabulary** knowledge (which it has) without inventing corpus content (which it can't). Variants enter at a
**reduced fusion weight** so a wrong synonym cannot outrank an exact match (precision guard against query drift).

- The current behavior (no expansion) is the **degenerate case** — fires only on weak signal, so the common
  (strong-signal) query is byte-identical to today (precision + latency safe).
- Variants feed **only the dilution-free legs** (BM25 + SPLADE); the dense leg is untouched (orthogonal to v3).
- Carries **provenance** (which variant surfaced a hit) for legibility, like the existing per-stage trace.

## D4-4 — Scope discipline (what bounds it, and why it is not bigger)
- **Conditional, not always-on** — fires on weak signal only (reuse the existing weak-result/zero-hit trigger), so
  it cannot degrade the common keyword case or cost an LLM call per query. It is a *safety net*, the stub's word —
  but re-scoped as the product's lever, not a throwaway helper.
- **AI-mode enhancement with graceful degradation** — needs the local LLM loaded (opt-in `ai_activate`); with no
  LLM it is a no-op and search falls back to SPLADE exactly as today. So it is an *enhancement*, not a base-search
  guarantee.
- **No new model** — it reuses the LLM already in the stack. A *second learned SPLADE-synonym model* (the other
  candidate seam) is **deliberately NOT designed now**: the near-synonym gap's *size* is unmeasured, so adding a
  trained model would be structure for an unquantified case (premature).
- **ADR-0043-compliant** — the LLM is language-agnostic and the expansion is query-time, authored per-nothing; no
  per-language synonym file (forbidden) is introduced.

## D4-5 — Build order: the eval is the first artifact (do not build before it)
Like the long-doc case, this direction is **reasoned but not yet measured**. The present problem does **not** yet
*require* the expansion structure until an eval shows (a) the product's real files genuinely fail on near-synonym
buried terms beyond SPLADE's coverage, and (b) LLM variant-expansion recovers them **without a precision cost**.
So the first deliverable is a **near-synonym buried-term eval** (query the doc's fact with a synonym the doc does
not contain), measuring lexical-leg recall with vs without expansion, on realistic personal-file content. Building
the seam before that eval would repeat the build-ahead-of-evidence trap that already cost Design v1.

## D4-6 — Explicitly out of scope
The corpus/query-adaptive **router** (Item-1, deferred) that would *choose when* to expand vs trust dense vs trust
BM25; a **new trained synonym model**; **always-on** expansion; the **dense / very-long-doc severe-dilution** case
(a separate, also-eval-gated direction — the chunk/late-chunking seam of Design v1, revived only if a long-doc eval
shows the chunk path insufficient); **extraction quality** (register F-009 — the largest real-file bottleneck, but
a different problem entirely).

## D4-7 — External landscape (2024–2026): a validated direction, sharpened on the technique form
LLM query expansion is one of the most active retrieval-research areas, and a research pass (2026-06-24)
**confirmed the direction and corrected the mechanism** — the same payoff the Design-v1 research pass produced.
- **Confirms the lexical-expansion direction.** LLM query expansion *reliably* improves sparse/lexical retrieval
  for vocabulary mismatch: **Query2Doc improves BM25 up to +15.2% nDCG@10** on MS MARCO, ~3% nDCG@10 across
  out-of-domain BEIR sets; the survey places "selection-based" expansion as the form that *suits sparse* retrieval
  (HyDE's pseudo-doc-embedding is the *dense*-side dual). So feeding expansion to BM25+SPLADE is the validated lane.
  [Query2Doc (arXiv 2303.07678)] · [Best-practices of QE with LLMs (arXiv 2401.06311)] ·
  [QE survey (arXiv 2509.07794)].
- **Confirms conditional firing.** "Not All Queries Need Rewriting" finds **selective rewriting beats blanket
  application** — rewriting *hurts well-formed/specific queries* and *helps weak/ambiguous ones*, keyed on weak
  first-stage results / low retrieval confidence. This is exactly D4-4's weak-signal trigger, now literature-backed
  (also: DRAGIN / confidence-guided dynamic retrieval). [arXiv 2603.13301].
- **Corrects the mechanism (the load-bearing refinement).** The dominant *generative* form (Query2Doc/HyDE
  fabricates a pseudo-document) **assumes the LLM knows the answer** and risks "drift from fanciful generations" —
  acute for **private** buried facts the LLM has never seen. The survey states term-selection / corpus-grounded
  methods are *"inherently safer for unknown corpora because candidates derive from retrieved documents, not LLM
  imagination."* But pure corpus-grounding (PRF/CSQE) has a chicken-and-egg flaw *for the buried-fact-missed case*:
  the top docs it would mine are the **wrong** ones (the right doc was missed). So the correct form for *this*
  problem is the third option — **query-term synonym/paraphrase expansion** (expand the query's distinctive terms
  using the LLM's *general vocabulary*, which it has, without fabricating corpus content or mining wrong docs),
  feeding the lexical legs at reduced weight. The research thus **moved D4-3 off the naive "LLM generates variants"
  framing onto the hallucination-safe form.** [Corpus-Steered QE (arXiv 2402.18031)] · [QE survey (arXiv 2509.07794)].
- **Names the headline risk.** Query drift / precision loss is *the* known failure mode (and generative latency/cost)
  — mitigated here by (a) conditional firing, (b) term-only (non-fabricating) expansion, (c) reduced fusion weight.

Net: the research **validates v4's direction and trigger, and sharpens its mechanism** — confirming the
eval-gated plan while pre-empting the hallucination/drift trap that a naive generative implementation would hit.

---

# Reach & principle v4 (recognized, not built)

## The principle the whole 636 arc reveals — *aim the lever at the regime the corpus actually occupies*
Across v1→v4 the same shape recurs: the engine's quality levers must be matched to the **retrieval regime the
corpus actually lives in**, not the regime the problem's *framing* assumes. 636 framed buried-signal as a **dense
dilution** problem; the product's files occupy the **lexical** regime (F-003). Every dense-side design (v1 embedding
seam, v2 leg arbitration, v3 recall-complete pool) therefore under-served the product, and the lexical-side "helper"
turns out to be the product-aimed lever. **Name it: regime-matched levers** — *corpus-regime awareness must precede
lever design.* This is not a new seam; it is the thesis the deferred **corpus/query-adaptive recipe-weight
function** (580 §13.3 / FW-001 / Item-1) exists to operationalize — so v4 *conforms to* that future seam rather than
forking it. Candidate scope: every retrieval-quality effort (don't tune dense for a lexical corpus, or vice-versa);
existing code "violates" it only in the soft sense that the static fusion weights are regime-blind — which is
exactly what the deferred adaptive-weight work would fix. **Do not build the router now** (Item-1 deferred); record
the principle so the next lever is aimed correctly.

## Two narrower principles it conforms to (existing, do not fork)
- **Learned/on-demand over curated-per-language** (ADR-0043): vocabulary bridging must come from a language-agnostic
  *model* (SPLADE, the LLM), never an authored per-language synonym list. v4's LLM expansion is the allowed form;
  the forbidden form (a `synonyms_en.txt`) is the dead stub ADR-0043 already bans.
- **Recall safety nets fire on weak signal, not unconditionally** — v4 is the "retry harder only when the first
  pass was weak" pattern applied to *vocabulary* mismatch. (Confidence-pass correction: there is **no** zero-hit /
  spelling-corrections seam in the engine; the real analog is the **existing LLM query-expansion seam** below.)

---

# Pre-implementation confidence pass — Design v4 (2026-06-24)

> Read-only de-risking before any build (no feature code). Reduced the six named uncertainties via the existing
> `needle-burial-v1` index (its queries are real near-synonym paraphrases), a manual oracle-expansion probe, a
> read-only architecture trace, and a (blocked) local-LLM probe.

## Verified GREEN
- **Premise — a real lexical-side near-synonym gap exists.** On the near-synonym paraphrase the lexical legs are
  **weak**: `lexical` (BM25) **0.116**, `splade` **0.207**, `bm25_splade` **0.108** (vs dense 0.82). So **SPLADE
  does not already bridge these near-synonyms** — v4 has room. (#1)
- **Mechanism — synonym expansion recovers lexical recall, verified live.** Oracle-expanding a query with the doc's
  *actual* descriptor ("…power station in the upper wetlands?" + "reactor in the northern marshlands") moved the
  evidence doc `olmthorn1` from **not-in-top-5 → #1** (BM25 score 1.4→15.9) in `lexical` mode — BM25 found the
  distinctive term regardless of where it sits in the 6.8 KB doc (no dilution). Ceiling ≈0.7–0.9 (a few corpus docs
  share a descriptor — a synthetic-corpus artifact, not a method limit). (#2)
- **Architecture — mostly already exists (big de-risk).** An **LLM query-expansion-and-re-search seam is already
  built** (`KnowledgeSearchEngine.java:490-529`: async `startExpansionAsync` overlapping the base search, gated by
  query type + `onlineAiService.isAvailable()` with a graceful `AI_UNAVAILABLE` skip + a ~1.5s budget). v4 is an
  **extension**, not net-new: change **replace → fuse-at-reduced-weight** (reuse `HybridFusionUtils.fuseWithRRF` and
  the N-parallel-search pattern of `SearchPerSourceExecutor`), pin the expansion to **term/paraphrase form**, and
  add the **weak-signal gate**. Graceful degradation + the config-flag pattern (`QU_ENABLED` style) already exist.
  Estimated **medium**, not large. (#5, #6-degradation)

## Residuals (what the eval / build still must settle)
- **LLM synonym quality — NOT live-verified.** The worktree dev stack has **no model pack installed** (`packs: []`),
  so `ai_activate` failed — I could not live-probe the local model's synonyms. Reasoned low-risk (synonym/paraphrase
  is a core LLM capability) with one refinement: prompt for **related terms + paraphrases** (to catch the
  *hyponym* bridge power-station→reactor), not strict synonyms. Verify when a model is present. (#3)
- **Weak-signal trigger is imperfect (confirmed limitation).** Post-retrieval low-signal (`totalHits`/top-score) +
  pre-retrieval `QueryClassifier` exist, but **no signal distinguishes "missed buried fact" from "not in corpus"**
  (register F-019). So firing is a *fallback heuristic*, not a precise gate — it will sometimes fire on genuinely
  unanswerable queries (wasted LLM call + drift risk). Acceptable given conditional + reduced-weight, but real. (#4)
- **Precision / query-drift is the key unknown — eval-gated.** The research's headline risk. The guards
  (fuse-at-reduced-weight, conditional firing, term-only/non-fabricating) are designed for it, but only an A/B with
  the **LLM loaded** (the eval harness must `ai_activate`, unlike the headless runs that skip expansion) on a
  keyword-dominant corpus (enron) settles whether expansion stays keyword-neutral. (#6-precision)

## Confidence rating for implementing Design v4: **7 / 10**
The two load-bearing uncertainties — *the gap is real* and *the mechanism recovers it* — are **verified live**, and
the architecture is **mostly pre-built** (a large de-risk vs my earlier "extend QueryUnderstanding from scratch"
framing). Held back from 8+ by three genuine residuals that only the build+eval close: the **un-live-tested LLM
synonym quality**, the **imperfect trigger** (can't cleanly tell missed-fact from not-in-corpus), and the
**precision/drift** question (the research's named risk), which requires an AI-loaded keyword-corpus A/B. None could
invalidate the approach; they are exactly what the eval-gated first artifact exists to measure.

---

# Design v4 IMPLEMENTED (2026-06-24) — conditional synonym-expansion recall net, default-off

## What was built (reuse-heavy; the investigation collapsed the scope)
- **`SynonymExpansionOps`** (new, `app-services/.../worker/`) — the self-contained Design-v4 concern (extracted to
  keep `KnowledgeSearchEngine` under its 1000-LOC ceiling): a **semantic-synonym** system prompt (term-only,
  hyponym-aware), `topCoherenceRatio` (top2/top1 weak-signal gate), and `maybeExpand(...)` — fire only when the flag
  is on, the query is eligible (not navigational/exact, AI available), and the **base result is weak/incoherent**
  (ratio ≥ `incoherence_min`, default 0.9); then LLM-expand → **reuse `KnowledgeSearchEngine.mergeExpansion`** (which
  already appends terms at **`^0.3`** + an ALPHA_ONLY hallucination guard) → re-search → reranked by the CE.
- **`KnowledgeSearchEngine`** — one ~8-line call to `SynonymExpansionOps.maybeExpand` after the existing morphology
  expansion block, before the cross-encoder. Reference-equal return ⇒ **default-off is byte-identical**.
- **Config** (env-tunable, default off): `JUSTSEARCH_SYNONYM_EXPANSION_ENABLED` +
  `JUSTSEARCH_SYNONYM_EXPANSION_INCOHERENCE_MIN`.
- **No new structure** beyond the one ops class: reuses `mergeExpansion`, the LLM streamChat path, the
  `onlineAiService.isAvailable()` graceful-degradation gate, the budget, and the CE. The "fuse at reduced weight" the
  design theorized **already existed** as `mergeExpansion`'s `^0.3` (D4-3 corrected: single-merged, not multi-variant).

## Validated
- **Unit** (`KnowledgeHttpApiAdapterExpansionTest`): synonyms merge at `^0.3`; a fabricated non-alpha answer value
  (e.g. "indigo grist 0002") is **rejected** by ALPHA_ONLY (hallucination-safe); `topCoherenceRatio` returns ~1.0 for
  a flat top (fire) and < 0.9 for a peaked top (skip → keyword-neutral). **Build + governance (class-size) green.**
- **Mechanism** — already oracle-proven (confidence pass): supplying the doc's terms moves the buried doc to #1 on
  the dilution-free lexical legs.
- **No regression** — with the flag OFF (default), a live dev-stack search returns normal results unchanged.

## BLOCKED: the real-LLM end-to-end + browser validation (honest)
The synonym path needs the **online LLM**, and this worktree's dev stack has **no AI runtime/model variant
installed** (`ai_activate {default}` → "Variant not installed"; `packs: []`) — the GGUF files exist under `models/`
but are not registered as an installed pack, which is the **"Install AI"** flow (a heavier setup than a feature
validation should pull in). The **headless eval also does not load the online LLM**, so a jseval A/B would no-op the
flag. Therefore the two LLM-dependent tiers — the **real-LLM A/B** (does the actual local model produce synonyms
that recover the needle, and stay keyword-neutral on enron) and the **browser** UI check — could not be run here.
They are unblocked the moment an AI pack is installed (or the online-LLM model path is wired into the eval backend).
Everything not requiring a live LLM is done and green; the feature ships **default-off** pending that A/B.

---

# Merged + close-out (2026-06-24)

All three default-off levers (v2 leg-arbitration, v3 recall-complete pool, v4 synonym-expansion) are **merged to
`main`** — code, tests, this tempdoc + the register (D-004/Q-011 + the `needle-burial-v1` dataset row), and the
committed regression corpus. The merge re-pinned the governance baselines, so the **class-size** and **clone**
declared-growth changesets were updated to cover the full v2/v3/v4 config-layer + `HybridFusionUtils` helper growth;
both gates are clean for these files. The feature logic was extracted into `SynonymExpansionOps` to keep
`KnowledgeSearchEngine` under its size ceiling. Nothing changes production behavior (every lever default-off).

## What is DONE
- v2 (leg arbitration), v3 (recall-complete rerank pool): implemented, validated (shared-index A/B), shipped off.
- v4 (synonym-expansion recall net): implemented + unit-tested + build/governance green + no-regression confirmed;
  mechanism oracle-proven; **default-off**.

## The ONE open item (deferred, blocked path recorded)
- **v4 real-LLM A/B + browser** — needs a live local LLM, which the current checkout lacks (no installed AI
  pack); the headless eval also skips the online LLM. Run once AI is installed: shared-index A/B on
  `needle-burial-v1` (recovery toward the ~0.7 oracle ceiling) + `enron-qa` (keyword-neutral), then the UI check.

## Future directions (recorded, out of 636 scope — each its own eval-gated effort)
The **corpus/query-adaptive router** (Item-1, the regime-matching seam this whole arc points to); the **long-doc
late-chunking** embedding seam (Design v1, revived only behind a long-doc eval); **extraction quality** (F-009, the
largest real-file bottleneck, a different problem). None is required to close 636.

**Status: 636 closed for the merge-able work; the v4 real-LLM validation is the sole deferred remainder.**

---

# Live real-LLM validation attempt (2026-06-24) — infra unblocked on `main`, mechanism runs, findings recorded

The prior "blocked — no AI pack" was a *worktree* condition. On `main` the AI runtime **does activate**:
the flat CPU `llama-server` baseline is staged in `modules/ui/native-bin/`, and setting the chat model
path via `POST /api/settings/v2` (→ `models/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf`) satisfies the
`default`-variant activation (`RuntimeActivationService` falls back to the baseline exe for `default`).
No full "Install AI" GPU download was needed. So the validation *infrastructure* is reachable on `main`.

**What the live run established (dev stack, CPU LLM, a 4-doc buried-fact corpus):**
1. **The merged v4 code runs live without error** across hybrid + lexical modes and multiple queries.
2. **Clean flag-OFF baseline:** lexical `"saloon"` (a word in no document) → **0 hits**.
3. **The mechanism executes** — with the flag forced on + AI active, flat-base near-synonym queries
   returned results including the target.

**Three honest findings (these refine the design, not invalidate it):**
- **The weak-base trigger skips confident-distractor cases.** A near-synonym query with a strong
  *lexical* distractor produces a **peaked** base (top2/top1 < 0.9), so the coherence gate **does not
  fire** — the buried doc stays buried. This is the "imperfect trigger" residual, now confirmed live:
  v4 helps the *flat-base* subset of buried-fact failures, not the confident-wrong-winner subset.
- **Short docs don't need v4** — the dense leg already bridges near-synonyms when there's no dilution,
  so a crisp demo *requires a long-document corpus* (the dilution regime v4 targets). A single short
  target is recovered by dense regardless of the flag.
- **The local 8B model's synonyms are broad** for unusual terms → a precision cost (the `^0.3` weight
  mitigates but noise remains). This is exactly the research's named precision/drift risk.

**Why the confound-free quantitative A/B is still deferred (not done here):** isolating expansion's
*retrieval-quality* effect cleanly needs a homogeneous **needle-style long-doc corpus** (flat base by
construction) run through the **eval harness with qrels** — and the headless eval does not load the
online LLM, while the CPU self-test proved **flaky on restart** (first activation succeeded; subsequent
`clean:none` restarts returned "self-test inconclusive"). The rigorous A/B (needle recovery toward the
~0.7 oracle + enron keyword-neutral) is therefore best run with a **GPU** LLM pack. Net: the wiring is
live-confirmed and the behavior is characterized; the *graded* A/B remains the deferred remainder.

---

# GRADED + DECIDED (2026-06-24) — the three levers resolved

The deferred A/B was run (the `jseval --start-backend --llm` bridge already existed — no infra to build).
All three levers were graded through the full production `hybrid` + cross-encoder pipeline via
`/api/knowledge/search`, with the LLM online (GPU), on **two** corpora: `golden/needle-burial-v1` (the
synthetic buried-fact target — "does it help") and `mixed/enron-qa` (real personal email, baseline
0.7379 ≈ register 0.740, `comparable:True` — "does it regress"). Shared-index A/B; nDCG@10.

| Lever | needle (target) | enron (real email) | Verdict |
|---|---|---|---|
| **2 — recall-complete pool** | 0.2716 → **0.539 (+98%)**, R@10 0.35→0.85 | 0.7379 → 0.7376 (**−0.04%, neutral**) | **DEFAULT-ON** |
| **1 — leg-arbitration** | 0.2716 → **0.6105 (+125%)**, P@1 0.2→0.5 | 0.7379 → 0.7275 (**−1.4%, real regression**) | keep **default-off** |
| **3 — synonym expansion** | 0.2716 → **0.2716 (0%)** twice (incl. an always-fire isolation run) | — | **DELETED** |

## Decisions (implemented this commit)
- **Lever 2 → default-on.** Big lift on the buried-fact target, free on real email — no downside.
  `ResolvedConfigBuilder` `leg_recall_complete_enabled` default flipped `false → true`.
- **Lever 3 → deleted.** Two runs byte-identical to baseline, including an isolation run with the
  `!expansionApplied` guard removed and the coherence gate forced always-on. Root cause: in the full
  hybrid pipeline the **dense leg already supplies the semantic bridging** that query-side synonyms
  would — it is redundant (it only *looked* useful in earlier lexical-only probes), and it carried a
  per-query LLM cost. Removed `SynonymExpansionOps`, the `KnowledgeSearchEngine` call site, the two
  `EnvRegistry` keys, and the unit tests.
- **Lever 1 → kept default-off.** The one genuine tradeoff: +125% on the synthetic target but a
  consistent **−1.4% on real personal email** (matches the earlier independent worktree observation).
  Not a clean winner (shipping-on costs real quality), not a loser (proven on the buried-fact regime).
  It is the natural candidate for the deferred corpus/query-adaptive router (Item-1), which would fire
  it only on buried-fact-like queries; until then its trigger needs tightening before it earns
  default-on. Default-off is a *recorded* resting state, not limbo.

## What this closes
636's central thesis is now **measured, not asserted**: the buried-fact failure is a
fusion/recall-gating problem (Lever 2 fixes it cleanly), **not** a query-expansion problem (Lever 3 adds
nothing over the dense leg). The product ships the one clean win on by default; the powerful-but-costly
lever waits for the router; the redundant lever is gone. No merged-but-ungraded code remains.

---

# Process retrospective (2026-06-24) — what cost time, and the lesson

Recorded because the failure modes here were expensive and repeatable, not 636-specific.

- **Grade last instead of first — the dominant waste.** Maximum effort (design v1–v4, confidence
  passes, a live dev-stack validation, a hand-crafted corpus, repeated temp-edit→rebuild→restart
  cycles) went into **Lever 3**, which graded to **0%**. The deciding A/B (`jseval --start-backend
  --llm`, ~3 min) existed the whole time. The "validate the mechanism" probes looked promising only
  because they were lexical-only and hid the dense leg. **Lesson: when a measurement harness exists, it
  must *gate* the design, not certify it afterward — grade the hypothesis cheaply before investing.**
- **Synthetic-corpus tunnel vision.** Anchored on needle-burial-v1's +98%/+125% for most of the work;
  those are partly artifacts of a corpus built to favour the levers. The decisive facts (Lever 1's
  −1.4% on real email; Lever 3's redundancy with dense) only surfaced on the real corpus / full
  pipeline, at the end. **Lesson: weight the real corpus first; a synthetic benchmark is a unit test,
  not a verdict.**
- **The AI-runtime fragility tar pit.** Many turns on "why won't the LLM load" (variant-not-installed,
  model-path wiring, a flaky self-test). The flakiness was mis-attributed to "CPU randomness"; it was
  plausibly the unsigned `ggml-rpc.dll` Defender block (only connected once surfaced externally). **The
  prior session's "blocked, defer to GPU" was largely an artifact of this fragility — then `--llm` made
  it trivial.** Lesson: find the deterministic cause of "flaky" before declaring a tier unavailable.
- **Near-miss: almost built infra that already existed.** Framed an "eval→LLM bridge" as a build task;
  `jseval --llm` already did it. Explore-before-implementing caught it, barely.
- **Self-inflicted process cost:** editing docs in `main` while code was in the worktree made the merge
  intricate; a redundant tempdoc 639 (already 636's Design v1) had to be folded back; "implemented +
  validated" was claimed for Lever 3 several times before it graded to zero. Premature confidence
  dressed as rigor.

**Cross-cutting root cause:** optimised for *building and defending* hypotheses rather than *cheaply
falsifying* them. Grade-first, real-corpus-first, discriminating-test-first are one discipline.

---

# Lever 1 promoted to default-on (user decision, 2026-06-24) — both levers now ship on

Per user instruction, leg-arbitration (Lever 1) was flipped to **default-on**, so the production default
is now **both levers** (leg-arbitration + recall-complete). Before committing, the *combined* config was
graded (the earlier grades were per-lever; the lesson above says measure before shipping):

| Config | needle (target) | enron (real email) |
|---|---|---|
| baseline | 0.2716 | 0.7379 |
| Lever 1 only | 0.6105 (+125%) | 0.7275 (−1.4%) |
| Lever 2 only | 0.539 (+98%) | 0.7376 (−0.04%) |
| **Both (production default)** | **0.8012 (+195%, P@1 0.65, R@10 0.90)** | **0.7142 (−3.22%, R@10 0.84)** |

**Finding — the levers interact non-additively in both directions.** On the buried-fact target they
compound *synergistically* (0.80 > either alone — leg-arbitration's alpha precision + recall-complete's
pool recall combine). On real email the regression is **more than additive** (−3.22% vs Lever 1's −1.4%
alone; recall-complete widening the pool while leg-arbitration reshapes the alpha amplifies the
over-firing). The combined −3.22% was surfaced to the user (>2× the −1.4% the original decision rested
on); they **accepted the tradeoff** — the +195% buried-fact gain is judged worth −3.22% on typical
email. `leg_arbitration_enabled` default flipped `false → true`.

**Open follow-up (not blocking):** the negative enron interaction means leg-arbitration over-fires when
the recall-complete pool is active. Tightening its trigger (the dense-confidence / leg-divergence /
BM25-incoherence conditions, or making it pool-aware) is the path to keep most of the target gain at
lower real-email cost — the natural first task for the deferred adaptive router (Item-1).

> **↳ Superseded as a forward path by the two new development rules below (2026-06-24).** "The natural
> first task for the deferred adaptive router (Item-1)" is exactly the corpus/query-adaptive routing the
> new rules retire. Read this follow-up as dated history; the §"New development rules" section at the foot
> reframes the remaining work.

---

# New development rules + re-framing (2026-06-24) — corpus/query-type speculation retired

> Takeover pass. The user set **two standing rules for all further engine work** and confirmed that **both
> 636 levers now ship default-on** (verified against `main` this pass). This section records the rules and
> re-reads 636's whole v1→v4 arc through them. **Investigation + re-framing only — no new design or
> implementation** (per assignment). It relies on the tempdoc's existing, dated-this-month literature
> passes (ColBERT/late-interaction §C-3/E, late chunking §D-4, DAT/RRF §D2-7, Query2Doc §D4-7) rather than
> re-deriving them; the new element is a *product-philosophy* decision, not a literature question.

## The two rules (engine-wide, not 636-specific)

Since JustSearch has **no users yet**, speculating about the corpus or query mix they will run is
unfounded — and designing the engine around that speculation bakes a guess into the code.

1. **Stop thinking about the types of corpus or queries users might run.**
2. **Stop planning / designing code around (1).**

Corollary the user stated: *the goal is to improve the search engine as a capability, not to chase nDCG
on a presumed corpus* — though nDCG is part of how capability is measured. (Scope note: these are
engine-wide rules. Recommend promoting them to the search-quality register as a D-005-style decision when
636's register touch-up lands — **not done here**, to stay scoped.)

## Current shipped state (verified against `main` this pass)

- **Both levers default-on.** `index.hybrid.leg_arbitration_enabled` → `true`
  (`ResolvedConfigBuilder.java:1497`) and `index.hybrid.leg_recall_complete_enabled` → `true`
  (`:1513`, `top_n` 10 at `:1514`). The 3-way `AdaptiveWeightSelector` path stays off
  (`adaptive_weights_enabled=false`, `:1491`).
- **v4 deleted** — no `SynonymExpansion*` symbol exists in code (only in this tempdoc).
- Combined production grade (the user's accepted trade): needle **0.8012 (+195%)**, enron **0.7142
  (−3.22%)** — `feat(636): leg-arbitration default-on` (HEAD). Matches the user's "≈2× gain worth ≈−3%
  general nDCG" framing.
- **Register drift to reconcile at close (recorded, not fixed here):** register **F-024 is current**
  (both levers default-on, user decision) but **D-004 is stale** — still titled "SHIPPED (default off)"
  and still says "default-on is not recommended" (`search-quality-register.md:585,605`), which now
  contradicts the shipped default and these rules. The corpus-type-framed Open Questions / Future Work
  (Q-001 "gate CE off for corpus types", Q-002 short-navigational-query eval, FW-001 corpus-adaptive mode
  selection, FW-003/FW-004 EnronQA-specific) should likewise be re-read against the rules when 636's
  register touch-up lands.

## What the rules retire in this tempdoc (the corpus-speculation that was load-bearing)

A huge fraction of 636's narrative is corpus-type speculation about JustSearch's "real files." The rules
withdraw the privilege that reasoning rested on:

- **The "negative result for the personal-files goal" headline (the re-scope banner, §Review fix #2,
  §What remains) is retired as a framing.** It is built on register **F-003** read as *"personal email is
  the product's core BM25-dominant corpus, so a regression there is disqualifying."* Under rule 1, no eval
  is privileged as "the use case." `enron-qa` is **one capability eval among several**, not the product.
  So "this lever hurts the product's core corpus type" stops being a disqualifier and becomes what it
  always was numerically: **a measured trade across evals** (+195% on one, −3.22% on another) — which is
  exactly why the user could ship it on.
- **Item-1 — the corpus/query-adaptive router — is retired as the forward path.** It is, by definition,
  *code that detects the corpus/query regime and routes accordingly* — the precise category rule 2 forbids.
  The repeated follow-up "tighten leg-arbitration's trigger → first task for the deferred adaptive router
  (Item-1)" loses its driver. (FW-001's `CorpusProfile`/`isLongCorpus` regime switch is the archetype of
  the forbidden form, and was already a dangling zero-consumer seam.)
- **The "regime-matched levers — aim the lever at the regime the corpus occupies" principle (§Reach v4)
  is itself corpus-type thinking** and is retired as a forward design driver. The arc's meta-lesson was
  "match the lever to the corpus regime"; the rules replace it with "improve the engine's fixed capability,
  regime-blind."
- **Design v4 (LLM synonym expansion) stays dead, now doubly so** — it was already deleted on the merits
  (graded **0%** in the full pipeline; the dense leg already supplies the bridging), and its motivating
  thesis ("the product's real files occupy the *lexical* regime") is exactly the retired corpus speculation.

## The distinction the rules require (so they are not over-applied)

The rules forbid *speculating about the user's corpus*, **not** *all per-query behavior*. The line:

- **Forbidden — adaptivity keyed on an assumption about what users run.** "This looks like a personal-email
  corpus → switch to BM25-dominant weights / skip the CE." This is FW-001's shape: it encodes a guess about
  the deployment.
- **Allowed — a fixed rule that reacts to runtime signals from the actual query and its own results.**
  v2's leg-arbitration reads BM25-incoherence (intra-leg top2/top1), dense top-cosine confidence, and
  cross-leg rank divergence — **all measured per request, assuming nothing about the corpus**. v3's
  recall-complete pool is fully fixed. So **both shipped levers survive rule 2 as mechanisms**; what the
  rules retire is the *motivation* the tempdoc gave them ("serve the paraphrase regime vs the keyword
  regime"), which should now be re-read as "react to per-query leg coherence," not "guess the corpus."
- **Honest tension (recorded, not re-litigated):** v2 is nonetheless the most regime-*reactive* lever and
  the one carrying the −1.4%/−3.22% cost; under a strict "one good fixed pipeline beats per-query
  special-casing" reading, **v3 (always-on, never down-weights a leg) is the more principled keeper and v2
  the more questionable one.** The user flipped v2 on knowingly, accepting the cost; this is recorded as a
  live tension a future agent should weigh if the enron cost ever needs removing, **not** a recommendation
  to revert.

## What the rules reinforce (the corpus-agnostic capability lens)

- **v3 (recall-complete rerank pool) is the model of a rule-compliant improvement.** Structural, always-on,
  no regime detection, never down-weights a leg, measured neutral on the non-target eval. *"Fusion is a
  ranking step, not a recall gate"* is a corpus-agnostic correctness property — it makes the engine's
  strongest judge (the CE) see every leg's best candidates, for any corpus. This is the template for "what
  good looks like" under the rules.
- **Design v1 (context-decoupled chunk vector / late-chunking) gains, but does not auto-revive.** It is
  index-time, keyword-neutral by construction, no regime branching — a pure capability lever, and the
  rules **remove one of the arguments that buried it** ("the product is lexical-regime so dense can't
  help," F-003). But the §Direction-investigation evidence-against still stands at the scale we can measure
  (the chunk-dense path already fires; whole-doc dense already retrieves the needle at s6; the demonstrated
  bottleneck was *fusion*, which v2/v3 already address). Its only genuinely-open slice is the **very-long-
  doc regime** (50-page contracts/logs, head ≈0.01% of doc) — and per the existing build-order discipline
  that needs **its own eval built first**, which still does not exist. Net status unchanged by the rules:
  *investigated, evidence-against at measurable scale, open only for the untested very-long-doc case behind
  a not-yet-built eval.*

## Critical caveat the rules do NOT remove — synthetic-corpus skepticism

`golden/needle-burial-v1` is an **adversarial extreme**: 100% zero-lexical-overlap paraphrase queries over
near-identical filler twins. **"+195%" means "on the failure mode this corpus isolates," not "the engine
is ~3× better."** The honest capability claim is narrower and durable: *the levers fix a real structural
defect — a leg's correct answer is buried by fusion before the cross-encoder ever sees it — and this corpus
is its regression guard.* The §Process-retrospective's own "synthetic-corpus tunnel vision" lesson stands
and is **independent of the new rules** (a benchmark built to showcase a lever is a unit test, not a
verdict). Ship-decision method consistent with the rules: **treat every eval as a capability measure,
weight none as "the use case," and ship a +X%/−Y% lever when the trade improves the engine's overall
capability** — which is the judgment the user already exercised.

## Net status of 636 under the new rules

- **Shipped (kept):** v2 leg-arbitration + v3 recall-complete pool, both default-on (user decision). v4 deleted.
- **Retired by the rules** (not by cost): Item-1 corpus/query-adaptive router as the forward path; the
  "regime-matched levers" principle as a forward driver; the "negative-result-for-personal-files" framing.
- **Still genuinely open and rule-compatible:** Design v1's **very-long-doc** case (only behind a
  not-yet-built long-doc eval); and — outside 636's scope but the rules' clearest invitation —
  **corpus-agnostic component quality**, where register **F-009 names extraction quality (−15% to −33%
  nDCG) as the single largest measured bottleneck**: a regime-blind, capability-pure lever, though a
  different problem than buried-signal.
- **No new design or implementation this pass** (per assignment). The forward recommendation, stated for a
  future session: aim the next engine work at *fixed, regime-blind capability* (structural correctness like
  v3, or component quality like extraction), and **do not** resurrect any corpus/query-regime router.

---

# Theorization — directions, reframes, and the broader shape (2026-06-24, takeover)

> Broad, deliberately non-committal theorization from the context already gathered (no new experiments).
> Goal per assignment: surface solution directions, reframings, tradeoffs, risks, hidden assumptions, and
> whether 636 points at a recurring system shape — **without** choosing or designing. Everything here is
> eval-gated when it eventually moves; nothing is endorsed for build. Read it as a menu + a thesis, not a plan.

## 0. The deepest tension the new rules create (and the reframe that resolves it)

The rules ("no users → don't speculate about corpus/queries; improve the *engine*, not nDCG on a presumed
corpus") create a genuine epistemic problem: **with no defined workload, how do you know the engine
improved?** Every nDCG number in this tempdoc is "nDCG *on a corpus someone chose*" — and the rules forbid
privileging any such choice. Taken naively, the rules could paralyze all measurement.

The resolution worth adopting: **redefine "capability" away from a score on a guessed workload and toward
properties that hold for *any* workload.** Concretely, an engine improvement is one of:

1. **A stronger guarantee / invariant** — a property true by construction for every corpus (e.g. "the
   cross-encoder now sees every leg's top-N," v3; "no analysis is per-language," D-003).
2. **A removed leak** — a place where a correct candidate was being silently discarded by a weaker stage
   before a stronger one could judge it (the v3 defect class, generalized in §3 below).
3. **A better fixed component** — a strictly-better encoder / reranker / extractor, regime-blind by nature
   (a better lens helps every photo).

This is the *retrieval-quality form of the codebase's own "verify, don't guess"*: you don't guess the
workload; you make the engine **correct-by-construction and leak-free**, and you measure with **invariants
and oracle-probes** rather than with a single corpus's headline metric. nDCG stays useful — but as a
*regression guard across many corpora at once* (the cohort/ratchet idea, Q-010), not as the definition of
"better." **Capability = guarantees + leak-freeness + component quality.** This reframe is the lens for
everything below.

## 1. Hidden assumptions worth surfacing (each has shaped a conclusion)

- **"nDCG@10 is the capability metric."** Buried-signal is mostly a *recall* and *P@1* problem ("is the one
  right answer found at all, and is it on top?"), yet the arc measured graded nDCG over a list almost
  exclusively. A metric that asks **"is the answer in the union of any leg's top-N at all" (union recall@N)**
  is more diagnostic of the true ceiling and is regime-blind — see §4.
- **"The cross-encoder is the strongest judge."** With AI loaded, the strongest judge available is the
  **LLM** (full cross-attention + reasoning). v4 tried the LLM *generatively* (query expansion) and it
  graded 0%. But the LLM as a **discriminative reranker/oracle** over a small candidate pool is *untried*
  and is a different, safer use (no fabrication). At minimum it is the right **ceiling probe** (§5).
- **"The legs are fixed; we tune fusion/rerank."** The entire arc lived *downstream* of retrieval. But the
  register says retrieval is already "strong enough that the CE is marginal" (F-006) — i.e. **the ceiling
  is set by the legs**, and the largest regime-blind headroom is *upstream*: extraction quality
  (F-009, −15% to −33%) and the encoder/SPLADE models themselves. 636 never touched a leg. The buried-signal
  framing may be attacking a downstream *symptom* of upstream model/extraction limits.
- **"A synthetic needle corpus can validate the fix."** Already flagged (§new-rules); the deeper version is
  that *any* offline corpus is a workload guess — which §0 resolves by shifting to invariants/leaks.

## 2. The directions, as a menu (regime-blind, tradeoffs named, none chosen)

| Direction | What it is | Rule-fit | Headroom | Main risk |
|---|---|---|---|---|
| **A. Cascade-wide recall-completeness** | generalize v3's "no lossy gate upstream of a stronger judge" to *every* pre-judge truncation (low-signal cap, chunk-collapse, LambdaMART, RAG budget, CE window) | **high** (pure structural) | medium | cost blow-up if applied naively → needs *judge-aligned, budget-bounded* cuts, not "never truncate" (§3) |
| **B. Wide-funnel-into-strong-judge** (north-star architecture) | minimize per-corpus cleverness in routing; maximize recall breadth + a single strong fixed judge; pool-size↔latency the only knob | **highest** | high | bounded by judge quality (CE recovery ceiling ≈0.71, §pre-impl v3); latency |
| **C. Better judge** | improve the CE *where it is actually the bottleneck* — confident-but-ambiguous candidates — and/or LLM-as-reranker | **high** (component quality) | high in the hard regime | F-006 says CE-model swaps were neutral *on easy corpora*; AI-mode-only for the LLM path |
| **D. Recall-leakage probe** (diagnostic, not a feature) | an eval-time oracle that flags when *any* stage drops a doc the final judge / qrels would rank high — turns "leaks" into a measurable quantity | **highest** (it *is* the §0 instrument) | enabling | needs labels or a judge-oracle; measurement only, no user-facing change |
| **E. Representation richness** (Design v1, reframed) | late-chunking / context-decoupled chunk vectors — *"representation context ⟂ match granularity"* | **high** (index-time, regime-blind) | unknown at scale | evidence-against at measurable scale; eval for the very-long-doc case still unbuilt |
| **F. Legs / extraction quality** | better encoder, better SPLADE, better extraction (F-009) | **high** (component quality, the real upstream headroom) | **largest measured** | out of 636's scope; larger effort; a *different* problem |
| ~~G. Corpus/query-adaptive router~~ | detect regime → route | **forbidden** (rule 2) | — | retired |
| ~~H. ColBERT / multi-vector~~ | per-token index + MaxSim | low (disproportionate; not dilution-proof, §C-3/E) | — | redundant with chunk-unit + parent-collapse + CE (three existing granularity attacks) |

Two cross-cutting notes: **A, C, E are complementary, not competing** — A routes more candidates *to* the
judge, E makes the dense leg's candidates *better*, C makes the judge *sharper*; a world with all three on
is coherent. And **B is the architecture that A+C+F converge toward** — it is the *shape*, not a separate lever.

## 3. The leak invariant, stated carefully (the most rule-compatible structural direction)

v3's "fusion is a ranking step, not a recall gate" is one instance of a recurring defect: **a correct
candidate discarded by a weaker upstream stage before a stronger downstream stage can rescue it.** The
engine has *many* such boundaries (each a lossy gate sitting upstream of a stronger judge): the low-signal
`vectorOnlyCap`, `collapseChunkHitsToParents`, the LambdaMART reorder, the CE rerank window (topK=20), and
the RAG context budget feeding the LLM. Naively demanding *recall-completeness everywhere* explodes cost,
so the invariant must be stated as a **truncation discipline**, not an absolute:

> **Judge-aligned, auditable truncation.** Every stage that drops candidates should cut by the *strongest
> signal available at that point*, not a weaker proxy — and a dropped candidate should be *auditable*: one
> should be able to ask "would a stronger downstream stage have kept it?" A truncation that fails this is a
> recall gate masquerading as a ranking step.

This reframes "recall-complete" (expensive, absolute) into "**cut with the best judge you have, and make the
cut measurable**" (cheap, principled). The §2-D recall-leakage probe is the instrument that makes it
*measurable*; the v3 splice is one *fix* the probe would have motivated. Building the **probe before any
further fix** is the eval-first discipline applied to this whole class — and it is plausibly the single
highest-value, lowest-risk next artifact under the new rules, because it is a regime-blind *measurement* (it
cannot itself regress production) that converts "where does the cascade leak?" from speculation into data.

## 4. A metric reframe that the rules quietly demand

Because no workload may be privileged, the most defensible engine metric is one that measures a *property of
the cascade* rather than a *score on a corpus*:

- **Union recall@N (per leg → fused → reranked):** at each stage, is the gold doc still present? This
  decomposes any failure into "the leg never found it" (a *component/representation* problem, §2-E/F) vs
  "a stage dropped it" (a *leak*, §3) vs "the judge mis-ranked it" (a *judge* problem, §2-C). The arc's
  nDCG-only view conflated all three. A staged-recall view is regime-blind and *causally* diagnostic — it
  tells you *which* of the three improvement classes (§0) a given corpus's failures belong to, without
  asserting that corpus is "the use case."
- **P@1 over a diverse corpus *set*** (not a single corpus) as the headline "did the one right answer land
  on top" signal — averaged across the existing register corpora to avoid privileging any.

These don't require new infrastructure; they re-slice data jseval already produces. Recording them is the
measurement half of §0's reframe.

## 5. The judge-quality ceiling, and the LLM-as-oracle probe

The v3 pre-impl pass found a hard fact: even when the needle is *in* the CE window, the CE ranks it to only
≈0.71, not the 0.82 the dense leg alone achieves — **the CE is itself a ceiling.** This is regime-blind and
important: §2-B ("put the intelligence in the judge") is only as good as the judge. Two theorized probes,
both *measurement-first*:

- **LLM-as-oracle reranker (ceiling probe).** Run the local LLM as a *discriminative* reranker over the
  union pool on a held-out set; the gap between LLM-reranked and CE-reranked nDCG is the **headroom above the
  current judge.** If large, "better judge" (§2-C) is the high-leverage corpus-agnostic lever; if small, the
  ceiling is the *legs* (§2-F) and judge work is wasted. This single measurement *prioritizes* C-vs-F
  without guessing a workload. (Distinct from v4/F-021: this is the LLM *judging retrieved candidates*, not
  *generating* queries or a *learned fusion combiner* — the failure modes of both of those do not apply.)
- **CE-failure characterization.** Among candidates the CE sees but mis-ranks, is the failure systematic
  (e.g. long-doc truncation at 512 tokens, the `isRerankerEligible` length gate)? A regime-blind audit of
  *why the judge misses* is cheaper than a model swap and may reveal a structural fix (e.g. judge the
  *best chunk*, not the truncated parent — which ties back to E).

## 6. The broader shape (the thesis, held as theory)

Across v1→v4 the four named principles were: *representation-context ⟂ match-granularity* (v1),
*symmetric per-query leg arbitration* (v2), *fusion is a ranking step not a recall gate* (v3), and
*regime-matched levers* (v4). The new rules act as a **filter** that sorts them by durability:

- **Survive as structural/regime-blind:** v1 (representation richness) and v3 (the leak invariant, §3).
- **Borderline (signal-driven but regime-flavored):** v2 — survives as a *mechanism* (per-query signals,
  no corpus assumption) but its *motivation* ("serve regime X") is retired; it is also the one carrying the
  cost.
- **Retired:** v4 (regime-matched levers) — it *is* corpus speculation.

What remains, unified, is a single recurring system shape — call it the **funnel-and-judge invariant**:

> **Put the intelligence in fixed, strong, judge-like stages (the CE, the LLM-as-judge, the
> representation/extraction quality of the legs). Keep the upstream funnel dumb, broad, and lossless. Make
> every truncation judge-aligned and auditable. Spend the "cleverness budget" on the judge and the legs,
> never on a per-corpus router.**

This is *not* new infrastructure — it is a **design discipline / invariant** that retroactively explains why
v3 was the clean keeper, why v2 is the questionable one, why v4 was deleted, why F-021 (learned fusion) is
dead (intelligence in a *combiner*, not a judge), and why F-009 (extraction) is the largest untapped lever
(component quality of the legs). It also predicts the highest-value *measurements* (the §2-D leak probe and
the §5 judge-ceiling probe) before any next build. It conforms to existing seams (it is the engine's own
recall-then-precision staging, made strict) rather than proposing a parallel subsystem — so, per AHA /
explore-before-implementing, the move is to **recognize the shape and let the next lever be built *as* a step
toward it**, not to construct a "funnel-and-judge framework." Whether this invariant deserves promotion to a
register decision (a D-005 sibling to D-003's language-agnostic stance — *"capability is guarantees + leaks +
component quality; intelligence belongs to the judge, not a router"*) was **promoted to the search-quality register as D-005** (user decision 2026-06-24).

## 7. What this theorization explicitly does NOT do

No direction above is chosen; no interface, config, or code is proposed; no eval is run. The forward
discipline stays: **the first artifact is a measurement** (the §2-D leak probe and/or the §5 judge-ceiling
probe), because under the no-users rules a measurement cannot regress production and it is what tells us
*which* of §0's three improvement classes the next build should target. Design begins only after that, and
only on a direction that is structural/regime-blind (A/B/C/D/E/F), never a router (G).

---

# Long-term design — Staged Recall Accounting (2026-06-24, takeover)

> The settled long-term design for 636's remaining work, general (not implementation-level). Grounded on a
> primary-source pass of the engine's existing observability + eval infra and the closely-adjacent tempdocs
> (549 unified SearchTrace, 553 canonical-execution-record + the projection register, 580 §4c/Q-010 relevance
> ratchet, 635 the eval-corpus origin). It is **eval-harness structure, not a runtime feature** — which is
> exactly why it is the correct scope (see §D-2). Still no implementation; this settles the shape, the reuse,
> the build order, and the reach.

## Thesis (one paragraph)

After all the re-framing, 636's *actual present problem is not a missing runtime fix* — the one
**demonstrated** leak (a correct dense answer buried by fusion before the cross-encoder) is already fixed by
v3, default-on. Under the no-users rules, the problem that genuinely remains is an **observability gap**: the
engine can tell you *how much* each pipeline stage narrows the candidate set (SearchTrace already carries a
per-stage `cardinality` "funnel rung", `knowledge.proto:228-246`) but **not whether the narrowing kept the
*correct* answer** — so "where does the cascade lose the right document, and is the ceiling the legs, a
leak, or the judge?" is unanswerable for any corpus without re-deriving it by hand. The correct long-term
design is therefore **one durable instrument — Staged Recall Accounting** — a *projection* of the existing
SearchTrace + qrels that attributes every relevance failure to exactly one of three stages (**leg-recall /
cascade-leak / judge-rank**), turning the §0 reframe ("capability = guarantees + leaks + component quality")
from rhetoric into a measured share. It is built almost entirely by **re-slicing data jseval already
captures** (zero production change), with the one part that needs new visibility (which *internal* stage
leaked) **deferred** until the accounting shows the leak share is worth localizing. This is strictly smaller
than the generalized runtime "recall-complete everywhere" framework (which only *one* boundary's evidence
justifies) and strictly larger than "tune a threshold" (which cannot tell you *where* to tune) — its size is
the outcome of matching scope to the one thing the problem actually lacks: sight of its own recall funnel.

## D-1 — Reuse, do not replace: what already exists and is correct

The design **extends**; it builds almost nothing new. Verified present and sound on `main`:

- **The canonical execution record + its projection discipline.** `SearchTrace` is the single authority over
  "what the pipeline did" (tempdoc 553 §1: *"human explain, observability spans, **eval**, learning-to-rank
  features"* are all legitimate **projections**, never independent models). Per-stage `TraceStage` already
  carries `id` (closed stage vocab), `status`, `reason`, `ms`, and **`cardinality`** — the funnel rung
  (`knowledge.proto:228-246`; built `SearchTraceProjector.java:72-137`). jseval already reads this per query
  (`provenance.py:41-91`).
- **Per-leg isolation for free.** jseval already runs single-leg modes — `vector` = dense alone, `lexical` =
  BM25 alone, `splade` = SPLADE alone (`retriever.py:17-98`). **Union recall@N per leg is reconstructable
  today** by unioning per-mode qrel hits — no backend change.
- **Per-query gold tracking for free.** Each per-query record already has `predictedDocIds` (the ranked
  returned list), `totalRelevant`, `recallAtK`, `p1AtK` (`artifacts.py:174-234`). So *"gold reached the final
  list but ranked low"* vs *"gold absent from the final list"* is already separable.
- **Pre-truncation candidate IDs already exist — on spans.** The response body exposes only survivors, but
  the OpenInference projection emits per-leg pre-fusion doc IDs + the reranker's input/output
  (`OpenInferenceSpanProjection.java:33-40`; the privacy contract explicitly permits per-document id/score on
  spans, `execution-surfaces.v1.json:72`). The OTel sink already runs (`otlp-sink-ensure` hook). So the
  *which-stage* signal is not even new data — it is unread data.
- **A metric-agnostic regression spine.** The relevance ratchet checks per-corpus nDCG@10 vs baseline±tol
  (`relevance_gate.py`); the cohort envelope calibrates a metric set at ±2σ (`calibrate.py:51-62`). Adding a
  metric flows through both **with no gate change** (the envelope/bisection are metric-agnostic). This is the
  home for Q-010's "what to pin", answered with a more diagnostic quantity than raw nDCG.
- **An LLM-judge bridge.** `jseval --start-backend --llm` already boots the online LLM; `--ce/--no-ce`
  toggles the cross-encoder, injected per run (`cli.py:52,60-62`; `run.py:194-199`). The §5 judge-ceiling
  probe reuses this path as a jseval-side rerank replay.

None of these is the defect. The defect is that none of them is *composed* into a recall-funnel view.

## D-2 — The structural gap (the one thing the present problem requires)

The engine observes the **cardinality** funnel (each stage's surviving *count*) but not the **recall**
funnel (each stage's surviving *correctness*). Concretely, for a query whose gold doc is known: today you
can see "fusion narrowed 100→50→20" but not "the gold doc was alive at dense-retrieval and dead after
fusion." That single missing fact — **per-stage recall-survival of the gold set** — is the degree of freedom
the problem needs, and it is the thing that makes the three improvement classes (§0) *individually
measurable* instead of conflated inside one nDCG number.

## D-3 — The design (general statement)

A jseval-side **projection** that, per query (any corpus with qrels), classifies each missed gold doc into
exactly one bucket by walking the recall funnel:

> **leg-recall** (no leg surfaced it in top-N) → **cascade-leak** (a leg had it, a pre-judge stage dropped
> it) → **judge-rank** (it reached the judge but ranked low).

- The **buckets map 1:1 to the §0 classes**: leg-recall ⇒ *component/representation* (§2-E/F), cascade-leak
  ⇒ *a removed leak* (§3), judge-rank ⇒ *a better judge* (§2-C/§5). The instrument's output is a
  regime-blind **failure-attribution profile** — "this corpus's misses are 60% leg-recall, 10% leak, 30%
  judge" — which is precisely what tells a future session *which direction to build*, with no workload guess.
- It composes **only existing facts**: per-mode union (leg-recall), the final-list/qrel diff (judge-rank vs
  the residual leak bucket), and — for *which* internal stage leaked — the span doc-IDs or a deferred debug
  emission (D-4 Layer 3).
- Its aggregate buckets + union-recall@N are **pinned into the existing cohort envelope / relevance ratchet**
  so a future change that *introduces* a leak (or shrinks leg-recall) fails loudly — the recall-funnel
  becomes a continuously-guarded property, not a one-off probe.
- A **sibling judge-ceiling mode** (§5) swaps the CE for an LLM-oracle reranker over the union pool via the
  existing `--llm` path; the LLM−CE nDCG gap quantifies the judge-rank bucket's *headroom*, deciding §2-C vs
  §2-F without a workload guess.

## D-4 — Layering = the scope discipline (what is built now, deferred, and out)

The layers are ordered so that **no structure is built ahead of the evidence that it is needed**:

- **Layer 1 — leg-recall (now, zero production change).** Union recall@N from existing per-mode runs.
  Separates the *component* bucket. Pure re-slice of `per_query.json`.
- **Layer 2 — leak-vs-judge split (now, zero production change).** From the final list + qrels: gold present
  but low = judge-rank; gold absent yet present in some leg's top-N = cascade-leak. Gives the *size* of the
  leak bucket immediately, even without naming the stage.
- **Layer 3 — which-stage attribution (DEFERRED, evidence-gated).** Only if Layers 1–2 show a non-trivial
  leak share worth localizing: read the OTel span doc-IDs (preferred — no production change) or add a thin
  eval-gated `include_stage_candidates` debug **projection** of SearchTrace (registered per 553 if it touches
  production). **Do not build this speculatively** — only the fusion→CE boundary has a *demonstrated* leak;
  the others (low-signal cap, chunk-collapse, RAG budget) are unproven, so localizing them is structure for a
  case the problem does not yet include.
- **Out of scope (recorded, not built): the generalized runtime "recall-complete at every boundary"
  framework** (Theorization §2-A/§3). One proven leak does not justify runtime structure at five boundaries;
  the instrument is what would *prove* a second one and thereby earn that build.

## D-5 — Conformance: this is a projection of an existing seam, not a new subsystem

- It is an **eval projection of `SearchTrace`** — the exact projection class 553 §1 already sanctions —
  derived from the one canonical record + qrels, never a second authority over "what the pipeline did". It
  adds the **recall-weighted** reading of the same per-stage funnel SearchTrace already exposes by
  *cardinality*. (Living in jseval Python it is outside the `execution-surface` gate's Java/TS scan
  (`execution-surfaces.v1.json:26-42`); the projection *discipline* still binds, and Layer 3's optional
  production emission would register as a projection.)
- It conforms to **549's stage-keyed observability discipline** (every stage tagged; this adds one more
  per-stage fact to the same closed stage vocab) and to **580/Q-010 + the cohort-envelope/relevance-ratchet**
  (a metric on the existing spine, not a parallel gate).
- It **subsumes two open register items**: Q-011's chunk-dense-isolating eval (a special case of staged
  recall) and the deferred Design-v1 very-long-doc gate (the build-order discipline's "first artifact is the
  eval" — a long-doc corpus run through this instrument *is* that eval).

## D-6 — Build order (measurement is the first artifact, by construction)

Layer 1 → Layer 2 → pin into the ratchet → judge-ceiling sibling → (only if warranted) Layer 3. Each step is
eval-only and cannot regress production. The first *runtime* design begins **after** the attribution profile
exists, and targets whichever bucket it shows dominant — structural (leak) or component (legs/judge), never a
router.

---

# Reach & principle (Staged Recall Accounting)

## The principle

**A funnel must be observable by recall-survival, not just by cardinality.** Any multi-stage narrowing
(retrieve → fuse → collapse → rerank → budget → answer) should be auditable not only for *how many*
candidates each stage drops but for *whether each stage dropped a correct one*. Stated as the failure it
prevents: *a stage that reports only its surviving count can silently become a recall gate, and nothing
downstream — however strong — can rescue what it cannot see was lost.* This is the **"auditable" clause of
the funnel-and-judge invariant** (Theorization §6) made concrete: the instrument is how "make every
truncation judge-aligned and auditable" becomes true.

## It conforms to an existing seam — do not fork it

The engine **already embodies the sibling half**: the *cardinality* funnel (`TraceStage.cardinality`, the
"funnel rung"). This design adds the **recall** funnel onto the *same* SearchTrace record and the *same* eval
projection class (553 §1) — it is therefore **not a parallel observability system**; it must live as a
governed projection of `SearchTrace` (+ qrels), reuse the existing per-stage vocab and the
cohort-envelope/ratchet spine, and (for Layer 3) read the OpenInference spans that already carry the
per-stage doc payload. Building a separate "recall-tracking subsystem" would be the anti-pattern 553 exists
to prevent.

## Candidate scope beyond buried-signal (named, deliberately not built now)

The same coupling — *cardinality observed, recall-survival not* — recurs at every truncation upstream of a
stronger judge, which is evidence the principle is real, not speculative:

- **RAG context-budget → LLM** (`RagContextOps`): the token budget can drop a passage the answer needed; the
  LLM is the strongest judge and cannot rescue an unselected passage. **Violates the principle today** (budget
  reports size, not recall-survival).
- **Agent retrieval → grounding → citation** (580 §16): the persisted citation tuples (retrieved ⊃ grounding
  ⊃ cited, via `AgentCitationResolver`) **already are a real-query recall funnel** — the one place production
  partly persists recall-survival. This is the **natural first production consumer** of the principle, and it
  is *harvest, not build* (the signal already exists).
- **The runtime truncation sites** — low-signal `vectorOnlyCap`, `collapseChunkHitsToParents`, the CE rerank
  window: each reports cardinality, none reports recall-survival. These are the candidate runtime instrumentation
  sites — but only fusion→CE has a *demonstrated* leak (v3), so the rest stay candidate scope.
- **Autocomplete / suggest**: truncates with no recall view.

Per the assignment and AHA: the present problem requires only the **eval-side accounting** (it is what makes
the funnel *visible* and gates every future lever). A unified **production** recall-funnel instrument across
{RAG-budget, agent-citation, runtime truncations} is warranted only when the eval-side accounting *proves* a
second leak that needs runtime localization — at which point this note is the warrant, and the agent-citation
tuples are the cheapest first production foothold. Recognizing the shape now (so the next lever is built *as*
a step toward it) is the point; **building the general structure now would be premature abstraction.** Whether
the principle deserves a register decision (a D-005 sibling: *"capability = guarantees + leaks + component
quality; observe funnels by recall-survival, not cardinality; intelligence belongs to the judge, not a
router"*) was **promoted to the search-quality register as D-005** (user decision 2026-06-24) — a sibling stance to D-003.

---

# External research pass (2026-06-24) — what's frontier, what I checked, what it changes

> A targeted literature pass, scoped to the parts of this design that sit on the *active* research frontier
> (knowledge cutoff Jan 2026; this checks Feb–Jun 2026). **Deliberately narrow.** It does NOT re-survey
> chunking / late-chunking / ColBERT (the tempdoc's own §D-4/§ext passes are dated *this month* with URLs, and
> Design v1 is deferred), and it does NOT research the D-005 *stance* itself (a product/values decision no
> paper settles). It checks only: (a) LLM-as-judge / LLM-reranker, and (b) label-free retrieval evaluation —
> the two frontier areas my §5 probe, the funnel-and-judge clause, and D-005's no-users measurement rest on.
> Result: **the design is confirmed and its core principle is *named* in the literature; three concrete
> guardrails are added; nothing is reversed.**

## Finding 1 — my core principle already has a name: the "bounded recall problem" (conform, don't coin)

The v3 fix and the §3 leak-invariant ("fusion is a ranking step, not a recall gate") are the literature's
**bounded recall problem**: *"relevant documents not retrieved initially are permanently excluded from the
final ranking"* in a cascading retrieve-then-rerank pipeline (Guiding Retrieval using LLM-based Listwise
Rankers, **ECIR 2025**, [arXiv 2501.09186](https://arxiv.org/abs/2501.09186)). This is a strong
*conform-don't-coin* signal: 636 should use the established term, and Staged Recall Accounting is, in those
terms, **a bounded-recall *diagnostic*** — it measures where the bound bites.
- **A recognized forward direction beyond v3 (candidate, not built):** that paper's fix is more advanced than
  a one-shot union pool — the **listwise ranker feeds back into retrieval** (merge feedback docs from the
  most-relevant-so-far, re-retrieve), reporting **+13.23% nDCG@10 / +28.02% recall at *constant* LLM
  inference budget**. This is the "judge guides recall" loop — a *stronger* form of the funnel-and-judge
  invariant (the judge doesn't just sort the funnel, it *widens* it where it's confident). Recorded as a
  candidate successor to v3's static union pool, **eval-gated and not built now** (v3 already addresses the
  one demonstrated leak; this is the next rung only if the §D-2 accounting shows the static pool still leaks).

## Finding 2 — the §5 judge-ceiling probe needs bias guardrails (LLM-as-judge is unreliable raw)

LLM-as-judge is the single most-researched area touching this design, and the 2024–2026 record is a list of
*documented biases*, not a clean oracle: **position/order bias** (judge-model choice dominates it),
**self-preference** (judges favor their own generations, correlated with self-recognition; stronger in larger
models — [arXiv 2410.21819](https://arxiv.org/pdf/2410.21819), NeurIPS-2024 line), **length / familiarity
(low-perplexity) bias**, and a **2026 RAND** finding that *no judge is uniformly reliable and frontier models
exceed 50% error on bias benchmarks*, with **single-judge scores psychometrically unstable** (mitigation:
multi-judge panels). [Position-bias study, arXiv 2406.07791](https://arxiv.org/abs/2406.07791).
→ **Effect on the §5 judge-ceiling probe (guardrails, not a redesign):** it is a *biased ceiling estimate,
not ground truth*. Use a judge model **different** from any model the engine uses as generator (kills
self-preference); **swap candidate order** / prefer pairwise-with-swap (kills position bias); read only the
*coarse* signal ("is there large headroom over the CE?") not a precise number; if the call ever drives a ship
decision, use a **panel**, not one judge. This *sharpens* the probe's framing the tempdoc already had ("a
ceiling probe, not a verdict") into concrete construction rules.

## Finding 3 — D-005's no-users measurement is validated *and* bounded (a real caution)

The hardest D-005 question — "measure capability with no users and no in-domain labels" — has an active
literature. The encouraging half: LLM-generated relevance judgments track human IR metrics at the *trend*
level (~0.91 Pearson reported). The load-bearing caution: **LLM-generated qrels lose reliability exactly where
ship decisions live** — they "fail to preserve the statistical significance of system differences" and
**struggle to distinguish the best top-performing systems**, with fairness/bias risks; human assessment
"remains essential" for fine comparisons (Limitations of Automatic Relevance Assessments with LLMs,
[arXiv 2411.13212](https://arxiv.org/pdf/2411.13212); see also Judging the Judges,
[arXiv 2502.13908](https://arxiv.org/pdf/2502.13908), and Redefining Retrieval Evaluation in the Era of LLMs,
[arXiv 2510.21440](https://arxiv.org/html/2510.21440v1)).
→ **Effect on D-005 (a guardrail that the design already half-anticipated):** this is *why* Staged Recall
Accounting centers **recall-survival / presence** (coarse, robust — the regime automated judgments are
reliable in) rather than **graded nDCG on auto-labeled corpora** (fine discrimination — the regime they
fail in). It also names a **tempting-but-unsafe shortcut to avoid**: "auto-generate qrels with an LLM so
*any* corpus becomes a ship-gate" — the literature says that gate is unreliable at the precision a ship
decision needs. So: keep the **curated human qrels** of the register corpora as the ship-gate; treat any
LLM-generated qrels on new corpora as **coarse trend signal only**; and prefer recall/presence metrics over
fine nDCG when the labels are automated. (A one-line version of this caveat was added to D-005's enforcement.)

## Net effect on the design

No reversal. The pass (1) **names the core principle** (bounded recall) so 636 conforms to an established term
and gains a recognized *forward* rung (ranker-guided retrieval) recorded as candidate scope; (2) **hardens the
§5 probe** with LLM-judge bias guardrails; (3) **validates and bounds D-005's no-users measurement**, turning
the "recall-survival over graded-nDCG" choice from a stylistic preference into a literature-backed
*reliability* argument and flagging the LLM-auto-qrels shortcut as unsafe. What I deliberately did **not**
research — chunking/representation (already covered, deferred) and the D-005 stance (not a research question)
— stays unchanged.

---

# Pre-implementation confidence pass — Staged Recall Accounting (2026-06-24)

> A de-risk pass *before* any implementation (no feature code; no production/harness change). The design's
> load-bearing infra facts came from two Explore subagents — *hypotheses* per `audit-without-test` — so this
> pass **personally re-verified each at `file:line`** and **demonstrated the three-bucket decomposition on
> real on-disk data** with a throwaway probe (deleted after). Result: **the core design verifies green; two
> subagent over-claims were corrected (neither breaks it); two simplifications fell out.**

## Verified GREEN (personally, at file:line / by measurement)
- **Modes isolate legs.** `retriever.py:17-98`: `vector`=dense-only, `lexical`=BM25-only, `splade`=SPLADE-only,
  all with `crossEncoderEnabled:False` + `fusionAlgorithm:"none"`. The F-013 hint is confirmed (`SPLADE_PIPELINE`
  sets `sparseEnabled:False`). Multi-mode runs loop against **one** backend/index (`run.py:190`). → Layer-1
  union-recall is a clean re-slice.
- **The per-query artifact already carries what Layers 1–2 need.** `artifacts.py:207-217`: ordered
  `predictedDocIds`, `recallAtK`(R@10), `p1AtK`, `totalRelevant` (qrel-derived). **R@10 is already a default
  per-query metric** (`scoring.py:8`) — per-leg recall is *literally already computed*.
- **Doc-ID alignment is sound (the silent-correctness risk — resolved).** `resolve_doc_id`
  (`retriever.py:222-262`) normalizes every hit to a uniform BEIR doc ID (filename/path-leaf), the same
  namespace as qrels — so the feared "normalize-to-parent-ID step" **already exists and is used uniformly**.
  The live probe **reconciled my top-10 gold-presence against the harness's recorded `recallAtK` with 0
  mismatches across all 40 (20 queries × 2 modes) checks.**
- **The decomposition is non-degenerate and meaningful (the interrogate-results risk — resolved).** On the
  on-disk Phase-1 `needle-burial-v1` run (`vector` + `hybrid`, `qrels.json`), the buckets came out **0
  leg-miss / 11 cascade-leak / 9 reached-final** — i.e. the dense leg found the needle in *all 20* queries but
  the fused/final path *dropped it from top-10 on 11*. That is the v3 "fusion buries the dense-found needle"
  defect, now **quantified per-query by the instrument** — exactly the intended signal.
- **Extend-don't-fork verified at the implementation level too.** A `jseval/projections/` framework exists
  (`projections/base.py`): a projection is a pure `produce(run_dir)->dict` registered into an end-of-run batch
  runner that writes `projections/<name>.json`, with per-projection **failure quarantine**. A sibling,
  `stratified_metrics`, **already buckets by `first_relevant_rank` (`top-1/2-5/6-10/>10`)** — the judge-rank
  dimension. So Staged Recall Accounting is **one new registered projection** (tiny, isolated, pure), not a new
  subsystem — conforming to this framework *and* (conceptually) the 553 SearchTrace-projection seam.

## Corrections to subagent claims (both sized; neither breaks the design)
- **The ship-gate ratchet is NOT metric-agnostic.** `relevance_gate.py:67-70` hard-reads
  `per_mode.<mode>.aggregate_metrics.nDCG@10`. The **cohort envelope** is the metric-extensible one
  (`calibrate.py:51-57`, `CALIBRATED_QUALITY_METRICS`). So "pin recall-survival so a new leak fails loudly"
  has **two tiers**: *drift* detection is cheap (add the metric to the envelope tuple + emit it into
  `aggregate_metrics`); a *hard ship-gate* needs a **small extension** to make `relevance_gate.py`
  metric-parametric. Sized, not free — the subagent's "no gate change needed" was an over-statement.
- **`predictedDocIds` is response-order, not score-rank order.** Proven live: q0001's gold is at
  `predictedDocIds` position 5 yet `ndcg=p1=1.0`, because `vector_run.trec` has it at **score-rank 1** (0.576);
  ir_measures scores by the `score` field, the artifact lists results in response order. **Presence buckets are
  order-independent (solid); any rank-based sub-bucket (judge-rank) must read the score-ranked `*_run.trec`
  files** (which every run already writes), not `predictedDocIds`.

## Simplifications that fell out (design got *easier*)
- **The judge boundary is mode-diffable — Layer-3 needs no OTel-span dependency for the boundary that
  matters.** The fusion↔CE leak is isolable by a **CE-off vs CE-on `.trec` diff** (jseval already supports
  `--ce`, `run.py:194-199`). So the deferred Layer-3's reliance on reading OpenInference spans is *weaker* than
  assumed; spans become deep-optional (finer intra-fusion attribution only).
- **CE-off robustness confirmed empirically.** The Phase-1 hybrid run had CE off (flat `0.5` fusion scores) and
  the decomposition still works — the cascade-leak it shows is a *fusion*-stage leak. So the core (leg-recall /
  cascade-leak / final-rank) is valid CE-off; the *CE-specific* judge-rank refinement just needs a CE-on run.

## Not re-probed this pass (deferred sibling/deep scope — characterized, not booted)
- **§5 LLM-oracle judge-ceiling probe:** the AI runtime is *characterized* by the §GRADED close-out
  (activates on `main` via the CPU baseline + a settings model path; documented **fragile** — the unsigned
  `ggml-rpc.dll` Defender block, flaky self-test). Not booted here — booting risks the AI-runtime tar pit + the
  shared-dev-stack lease for low marginal confidence on a *deferred sibling*. Reachability accepted as
  characterized; the probe stays gated on an AI-loaded run when it is actually built.
- **OpenInference span per-doc payload (deep Layer-3):** left unverified-but-unneeded, since the simplification
  above removes the dependency for the boundary that matters.

## Net re-weight + confidence
The **core** (Layers 1–2 as one `jseval` projection over existing artifacts + cohort-envelope drift pinning)
is now strongly de-risked: every load-bearing infra fact is personally verified, the decomposition is
demonstrated working and meaningful on real data with **0 ID-alignment mismatches**, and the implementation
surface is a single pure, failure-quarantined projection. Held back from higher only by: the *judge-rank*
bucket needing CE-on eval runs (cost/fragility, but supported), the *hard ship-gate* needing a small
`relevance_gate.py` extension, and the genuinely-fragile AI runtime for the deferred §5 probe.

**Critical confidence rating for the remaining (still-unbuilt) work: 8 / 10.** (Core eval-projection ~8.5;
the full design incl. CE-on judge bucket + ship-gate extension + §5 probe ~7.5 — dragged by AI/CE runtime
fragility and the gate extension, none of which is a design-invalidating unknown.) The first build artifact is
unchanged: the Layers-1–2 projection (a measurement that cannot regress production), gated by the now-verified
existing harness — not the runtime generalization, not the §5 probe.

---

# IMPLEMENTED + validated (2026-06-24) — Staged Recall Accounting

> The settled design was implemented, reusing existing seams (no new subsystem; no production/runtime/UI
> change — it is eval-harness diagnostic infrastructure). All three pieces (core projection, focused
> leak-gate, §5 LLM judge-ceiling probe) are built + unit-tested; the core is end-to-end validated on real
> eval runs and **demonstrably tracks the shipped fix**. Layer-3 (deep intra-fusion attribution) stays
> deferred per the design's "only-if-warranted" discipline.

## What was built (all extends existing structure)
- **Core projection** `jseval/projections/staged_recall_accounting.py` (registered in `projections/__init__.py`)
  — a *pure* `produce(run_dir)` over existing artifacts (`qrels.json`, `{mode}_per_query.json` for presence,
  score-ranked `{mode}_run.trec` for rank), decomposing every judged query into **LEG_MISS / CASCADE_LEAK /
  JUDGE_RANK_LOW / OK_RANK1** + aggregate rates + per-leg union recall + a **self-reconciliation** block
  (its presence call vs the harness's recorded `recallAtK`). Mirrors the `stratified_metrics` sibling; runs
  automatically at end-of-run via the existing `run_all_discovered` (failure-quarantined). **Folds in the
  AI-free §5 ceiling**: `oracle_judge_ndcg_ceiling` (== `leg_union_recall`, a perfect judge over the current
  pool) and `judge_headroom_ceiling = leg_union_recall − final_ndcg`.
- **Focused leak-gate** `jseval/leak_gate.py` + `jseval leak-gate` CLI + `leak-gate-baselines.v1.json` —
  mirrors `relevance_gate.py` (pure `evaluate`, exit 0/1/2); a *ceiling* on `leak_rate` from the projection.
  A focused gate, not a cohort-envelope metric, because leak_rate is cross-mode (the confidence-pass finding
  that the per-mode ratchet is nDCG-locked). Un-pinned datasets do not gate.
- **§5 LLM judge-ceiling probe** `jseval/judge_ceiling.py` + `jseval judge-ceiling` CLI — pure core
  (`assemble_pool` / `_score_ranking` / `judge_ceiling_report`) over a `rank_fn` callable, reusing the proven
  OpenAI-compatible `/v1/chat/completions` path (`agent_retrieval_eval`). Order-swap position-sensitivity
  guardrail; graceful `AIUnavailable` → `AI_UNAVAILABLE` degradation (the AI-free ceiling already stands).
- **Tests** (mirror the per-projection / gate test pattern): `tests/test_projections_staged_recall_accounting.py`
  (incl. a regression pinning the `.trec`-rank-over-`predictedDocIds`-order finding), `tests/test_leak_gate.py`,
  `tests/test_judge_ceiling.py`.

## Validation (real runs + tests)
- **Unit suite: 877 passed** (full jseval suite minus the backend-requiring set), **no regressions**; the 22
  new tests (projection/gate/probe) green.
- **The instrument tracks the shipped fix (headline).** Shared decomposition on the *same* needle corpus,
  two pipeline states:

  | Run | final nDCG | leak_rate | CASCADE_LEAK | final_recall | OK_RANK1 | judge_headroom_ceiling | reconcile |
  |---|---|---|---|---|---|---|---|
  | old on-disk (CE-off, `vector`+`hybrid`) | 0.318 | **0.55** | 11/20 | 0.45 | 4 | 0.682 | 0 mism |
  | **fresh CE-on, both levers default-on** (`vector`+`lexical`+`splade`+`hybrid`) | **0.801** | **0.10** | **2/20** | 0.90 | 13 | 0.199 | 0 mism |

  The shipped levers cut the cascade-leak 11→2 (leak_rate 0.55→0.10) and the projection — auto-produced at
  end-of-run — **measures exactly that**, which is the instrument's whole purpose. Non-degenerate, realistic
  spread; **0 reconciliation mismatches** on both.
- **Auto-run wiring:** `run_all_discovered` produces `projections/staged_recall_accounting.json` among 9
  projections (`status: ok`).
- **Leak-gate CLI end-to-end:** pinned ceiling 0.10 vs leak 0.55 → **exit 1** (fail); un-pinned → **exit 0** (skip).
- **§5 AI-free ceiling** is delivered + validated on both runs (`judge_headroom_ceiling` 0.68 then 0.20 — a
  clean "judge/cascade is the bottleneck, not the legs" signal, since `leg_union_recall=1.0` throughout).
- **Non-synthetic register-corpus profile — DONE (courtlistener-200, CE-on, 4 modes, 200 judged).** A real
  legal known-item corpus, run in a clean window. Profile: **56 LEG_MISS / 14 CASCADE_LEAK / 20
  JUDGE_RANK_LOW / 110 OK_RANK1** → `leg_miss_rate 0.28`, `leak_rate 0.07`, `final_ndcg 0.609`,
  `leg_union_recall 0.685`, `judge_headroom_ceiling 0.076`; per-leg recall `lexical 0.64 / vector 0.25 /
  splade 0.175`; **reconciliation 0 mismatches / 200**. This is the diagnostic payoff: a **different failure
  regime** from the synthetic needle — needle was leak-dominated with `leg_union_recall=1.0` (fusion was the
  problem); courtlistener is **LEG_MISS-dominated** (the legs genuinely don't surface the gold for 28% of
  queries; only BM25/lexical carries the union on legal known-item) with a tiny judge ceiling. The instrument
  correctly localizes a *component/representation* bottleneck vs a *fusion-leak* one — exactly what Staged
  Recall Accounting is for. **Leak-gate validated on this real data:** fire (exit 1) at pinned ceiling
  0.05 < 0.07; pass (exit 0) at 0.15; skip (exit 0) un-pinned.
- **§5 live LLM-realistic judge-ceiling probe — DONE (needle run, GPU `Qwen3.5-9B-Q4_K_M`).** `llm_ndcg
  0.8236` vs `final_ndcg 0.8012` → `headroom_realized 0.022`, **`capture_fraction ≈ 0.11`** — the local 9B
  model captures only ~11% of the AI-free ceiling (0.199), and the order-swap guardrail reports
  `top1_agreement 0.20` (highly position-sensitive). This **empirically confirms the design's thesis**: the
  live judge is a weak, bias-contaminated reranker, so the **AI-free `judge_headroom_ceiling` is the
  decision-relevant figure** and the live number is "coarse signal only" (matching the literature pass). The
  AI runtime activated cleanly on GPU in 34.5s (the recent commit dropping the unsigned `ggml-rpc.dll`
  removed the documented blocker).
- **On the earlier failures (kept as history):** the *first* attempts at these two items failed (504 / 120s
  startup timeout / 503) and were **root-caused (§Root-cause below) to multi-agent contention** on the shared
  default port 33221 + data dir `tmp/headless-eval-data` with concurrent `jseval corpus-fidelity` recert runs
  from other sessions — *not* "fragility", not a code defect. Re-run in a quiet window, both completed
  immediately — itself confirming the diagnosis.

## Scope held (per the design discipline)
- **Layer-3 deep intra-fusion stage-attribution stays deferred** (only-if-warranted; not built). The
  nDCG-locked **relevance ratchet was left untouched** — the focused leak-gate is the clean fit. **No UI**
  (recall-survival needs qrels → eval-only; browser validation N/A).

## Root-cause of the eval-run failures (investigated to ground truth, 2026-06-24)
> Taken over on request ("investigate until confident"). My first pass mislabeled the failures "AI-runtime
> tar pit / dev-runtime fragility" — a **wrong attribution** the retrospective's own lesson ("find the
> deterministic cause of 'flaky' before declaring a tier unavailable") exists to prevent. The real cause is
> concrete and deterministic.

**Finding: multi-agent resource contention on the shared jseval default port + data dir.** Primary evidence:
1. **A live, healthy backend was running during the investigation** (4 java procs, pid 13344 LISTENING on
   33221), *actively ingesting* (`Combined backfill: docs=150, embed=150, splade=100, ner=100 … total=5895ms`)
   — but indexing **`golden/synth-multihop-prose-v2`**, none of my eval corpora. My probes were all read-only,
   so I didn't start it.
2. **Concurrent `jseval corpus-fidelity` recert processes from *other* agent sessions** —
   `python -m jseval corpus-fidelity --dataset {synth-tabular-v1,synth-code-v1,…} --start-backend --clean
   --embedding`, process-tree-traced to Claude Code shell snapshots (pids 54068, 42136/33728, …). The 635
   recert logs show them cycling backends 14:04→14:13+ with **`port=33221, data=…\tmp\headless-eval-data`** —
   the *same* defaults my evals use.
3. **`jseval.backend.start_backend`** defaults to `port=33221` + `tmp/headless-eval-data`, with **no
   mutual-exclusion lock**; `--clean` rmtree's that dir *before* the backend binds (`backend.py:37,64,71-89`).
   Two such workflows therefore race on the port (only one can bind) and on the data dir (one `--clean` wipes
   what the other is reading).
4. **The contention breaks everyone:** the recert's *own* `synth-code-v1` run also **FAILED** — not just mine.
5. **No leak / clean machine between collisions** (310 GB disk free, ~1 GB VRAM, no zombie after teardown) —
   so it was *transient contention*, not a persistent resource leak.
6. **`quick_health` reported "not running" throughout** because it tracks the **MCP dev-runner**, and is blind
   to these *jseval-managed* backends — so the existing ownership safety net could not warn of the collision.

**Mechanism per symptom (interleaving races):** 120 s startup timeout = my backend couldn't bind 33221 (a
recert backend held it); 503 `/api/indexing/roots` = a worker on a half-`--clean`ed / not-yet-ready index;
504 on `/api/knowledge/search` = a search over an index being wiped/rebuilt under it. The first needle run
succeeded because it hit a momentary clean window.

**Reusable lesson (logged to the inbox):** `jseval … --start-backend` evals collide with *any* concurrent
jseval backend workflow (recert, `calibrate` sub-runs, another session) because they all default to port
33221 + `tmp/headless-eval-data` with no lock, and `quick_health` cannot see them. This is a
harness/coordination gap, not a code defect in 636; do **not** kill other sessions' backends.

**Correction — how to actually run isolated (verified 2026-06-24, Track-B cross-corpus runs).** `jseval run
--start-backend` **cannot be port-isolated**: `_run_iteration` calls `backend.start_backend(...)` *without*
threading a custom `port=`/`data_dir=`, so even `--base-url <other-port>` or a config `api_port` only reaches
the spawned subprocess — the Python-side health-check + `--clean` still target port 33221 + `headless-eval-data`
and it connects to the colliding backend. The **working** isolation is to start your *own* eval backend via raw
gradle (env reaches Java directly, no Python overwrite) and drive it without `--start-backend`:
`JUSTSEARCH_API_PORT=33990 JUSTSEARCH_DATA_DIR=<dir> ./gradlew.bat :modules:ui:runHeadlessEval --no-configuration-cache --quiet`,
then per corpus `jseval run --base-url http://127.0.0.1:33990 --reset --dataset <slug> --modes … ` (no
`--start-backend`; `--reset` clears the index between corpora). Combined VRAM of two backends (~9.7 GB)
fits a 12 GB card. Closing the `jseval --start-backend` isolation gap (thread `port`/`data_dir` from
`--base-url`/config into `start_backend`) is a recorded harness follow-up.

---

# Staged Recall Accounting — guard ACTIVATED + cross-corpus profile (2026-06-24, follow-up)

> Closes the D-6 build-order step that was outstanding ("pin into the ratchet") and produces the instrument's
> stated output (D-3): a regime-blind, cross-corpus **failure-attribution profile**. Eval-only — no
> production/UI change; the runs were isolated (above) to coexist with a concurrent recert. The earlier
> critical-analysis pass flagged the guard as *dormant + a sibling not wired into the standing-ratchet
> discipline*; both are now resolved.

## 1. The guard is real (active + wired + validated)
- **Wired into the standing-ratchet discipline.** The `search-engine-hint` hook now surfaces **three** engine
  ratchets when retrieval source is edited — relevance (nDCG@10), perf (latency/throughput), and the new
  **leak** (cascade-leak rate) — and its example run switched to the leg modes so *one* eval feeds all three.
  Eval ratchets are hook + CLI discipline (not kernel gates — the governance registry has none), so leak
  conforms to the *same* path as its siblings (`hooks-reference.md` updated).
- **Ceilings are measured-derived, not hand-typed** — `leak_gate.derive_baselines` + `jseval leak-gate-derive`
  mirror the relevance ratchet's release-projection anti-fork (tempdoc 623): a corpus's `leak_rate_max` is its
  *measured* leak rate, `evaluate` adds the tolerance.
- **Pinned + active** (`leak-gate-baselines.v1.json`, previously empty): `needle 0.100 · courtlistener 0.070 ·
  scifact 0.013 · enron-qa 0.047`, each `tolerance_abs 0.05`. **Validated on real data:** `leak-gate` PASSes
  on the baseline run and FIREs (exit 1) on a tightened ceiling.
- **Why a sibling gate, not folded into the existing spine** (confirmed by code, settles the design-deviation
  flag): `relevance_gate` + the cohort envelope both read `per_mode.<mode>.aggregate_metrics`
  (`relevance_gate.py:67`, `calibrate.py:51`); the recall-survival metrics are **cross-mode/per-run**
  projection outputs, so they *cannot* live in the per-mode spine — the design's "flows through both with no
  gate change" was structurally infeasible. The focused gate over the projection is the correct home; the
  reuse it *does* honor is the gate-invocation discipline (the hook).

## 2. Cross-corpus failure-attribution profile (the instrument's payoff)
Four diverse register corpora (CE-on, 4 modes; capped runs re-`produce`d after a projection fix that restricts
attribution to *executed* queries — un-run qrels entries were being mis-counted as phantom `LEG_MISS`; the
uncapped needle/courtlistener runs are unchanged by it). **0 reconciliation mismatches on every corpus.**

| Corpus | regime | `leak` | `leg_miss` | `judge_low` | `ok` | `leg_union_recall` | `judge_headroom_ceiling` | dominant bucket |
|---|---|---|---|---|---|---|---|---|
| `golden/needle-burial-v1` (synthetic) | leak+judge | 0.100 | 0.000 | 0.250 | 0.650 | **1.000** | 0.199 | judge / leak |
| `mixed/courtlistener-200` (legal) | **leg-miss** | 0.070 | **0.280** | 0.100 | 0.550 | 0.685 | 0.076 | **leg-recall** |
| `scifact` (academic) | **judge-rank** | 0.013 | 0.067 | **0.253** | 0.667 | 0.933 | 0.158 | **judge-rank** |
| `mixed/enron-qa` (email) | **judge-rank** | 0.047 | 0.100 | **0.253** | 0.600 | 0.893 | 0.163 | **judge-rank** |

**The instrument distinguishes failure regimes** — its whole purpose: legal is **leg-recall-bound** (the legs
don't surface the gold; only BM25/lexical carries the union — per-leg `lexical 0.64 / vector 0.25 / splade
0.18`), academic + email are **judge-rank-bound** (legs find it ≥0.89, the judge mis-ranks it), and the
synthetic needle isolates the **leak/judge** tail with full leg recall. `judge_headroom_ceiling` correctly
collapses where the *legs* are the ceiling (courtlistener 0.076 — a perfect judge can't rank a doc that never
entered the pool) and rises where the *judge* is (needle/scifact/enron ~0.16–0.20).

**The regime-blind direction it points at (D-3's purpose — measurement, not a workload guess):**
- **Cascade-leak is small on every corpus (0.013–0.100, mean ≈ 0.06).** v3's shipped recall-complete pool
  holds across regimes; **leak is the dominant bucket nowhere.** So the next regime-blind lever is **not**
  another anti-leak fix, and **Layer 3 (which-internal-stage-leaked) correctly stays deferred** — D-4 gates it
  on "a non-trivial leak share worth localizing", which the cross-corpus data refutes.
- **The dominant headroom is the JUDGE (judge-rank, the largest cross-corpus bucket) and the LEGS (leg-miss,
  corpus-dependent).** This is exactly the §0 / D-005 split — *capability = guarantees + leaks + component
  quality* — now **measured**: a future regime-blind lever should target a sharper judge (§2-C / the §5
  judge-ceiling probe) and/or leg/component quality (§2-F: encoder / extraction, register F-009), per whichever
  bucket a corpus shows dominant. Forward runtime *design* stays a future session (D-6); this pass delivers the
  *measurement* that aims it.

## 3. Q-011 operationalized
The per-leg union recall isolates the legs per corpus (e.g. enron `lexical 0.88 ≫ splade 0.147` confirms email
is BM25-dominant, F-003; scifact all-legs-strong), so "which leg finds the buried fact, and does the final path
keep it?" is now a standing per-run measurement — the chunk-dense/leg-isolation reading Q-011 asked for.

## 4. Scope held
miracl-de (multilingual) was the optional 5th corpus (plan: "drop if time/contention forces it") — skipped to
keep momentum; the instrument runs on it anytime. No production/UI change (recall-survival needs qrels →
eval-only; browser validation N/A). Layer 3 + the very-long-doc seam + forward runtime design stay deferred.

---

# Session retrospective (2026-06-24, follow-up) — what slowed this work + what to change

> Whole-session reflection (the Staged Recall Accounting build + activation passes). Recurring costs and real
> risks only, not minor friction. The contention root-cause + the working isolation method are in §Root-cause;
> this captures the *new* reusable lessons + what worked, so the next engine-eval agent doesn't repeat them.

## What cost the most (recurring or risky) — ranked

1. **Shared-port/data-dir contention is a structural multi-agent gap, and `quick_health` cannot see it.**
   Two `jseval --start-backend` workflows (my evals + another session's `corpus-fidelity` recert) both default
   to **port 33221 + `tmp/headless-eval-data`** with no lock; the MCP `quick_health` ownership check only
   tracks the *dev-runner*, so it reports "not running" while a jseval backend is live. This collided **twice**
   this session and cost a mislabeled root cause the first time. **Change (tooling):** a port/data-dir lock
   shared across jseval backends (like the dev-runner's `active.json`), and `jseval --start-backend` should
   **fail fast** on a live 33221 backend rather than silently connecting to it / 120 s-timing-out. **Until
   then (habit):** `quick_health = not running` does **not** mean port 33221 is free — check live processes +
   the port directly before any `--start-backend` eval.

2. **`jseval --start-backend` cannot be port-isolated.** `_run_iteration` calls `start_backend()` without
   threading `port`/`data_dir`, so `--base-url <port>` / a config `api_port` reach only the spawned subprocess
   while the Python-side `--clean` + health-check stay on 33221. Discovering this burned ~8 tool calls. The
   working isolation is a **raw-gradle backend + `--base-url … --reset`** (no `--start-backend`) — §Root-cause.
   **Change (tooling, one-line plumbing):** thread the port/data-dir through; recorded follow-up.

3. **I mislabeled the contention "AI-runtime fragility" and deferred — the exact `interrogate-results` /
   "find-the-deterministic-cause-of-flaky" failure the repo warns about, with the lesson already in context.**
   I pattern-matched to a known "tar pit" story instead of looking; the real cause took ~5 calls once I did.
   **Change (habit):** on ANY backend-op failure, FIRST dump live processes + port owners + the backend logs
   (`<dataDir>/logs/headless-backend.log`, `worker.log`) before reaching for a "this is the known-flaky thing"
   explanation. A `jseval doctor` that prints "who's on 33221 / what's in the data dir / GPU" on failure would
   institutionalize this.

4. **A capped run (`--max-queries`) silently corrupted the projection** — un-executed qrels entries were
   counted as phantom `LEG_MISS` (scifact `leg_miss` 0.53 vs true 0.067), caught only because the number
   looked wrong. Fixed: attribution now restricts to *executed* queries (the reconciliation scope already
   did — the original inconsistency was a smell I missed at build time). **Change (habit):** when capping
   queries, sanity-check that per-query-aggregate metrics account for the un-run remainder; align any new
   attribution's scope with the reconciliation's from the start.

5. **The `scifact` slug is dual** — the *run* wants raw `scifact`, the *gate* wants `beir/scifact`; passing
   `beir/scifact` to the run fails "Cannot materialize unknown dataset", costing a re-launch. The quirk is
   acknowledged in a hook comment, not fixed. **Change (repo):** accept `beir/scifact` as a run alias, or flag
   the dual-slug explicitly in the Dataset-Catalog row (don't bury it in a hook comment).

6. **This tempdoc is ~2,400 lines of append-only history**, and the frontmatter `title:` is a single
   ~1,500-word paragraph cramming the whole v1→v4 arc — re-reading it for the critical-analysis pass took ~6
   large reads and it is genuinely hard to tell which design is *current*. **Change (tempdoc structure):** a
   tempdoc this large needs a short **"CURRENT STATE — read first"** block at the top (shipped /
   settled-remaining-design / deferred), with everything below explicitly dated history. `tempdoc-age-hint`
   flags staleness but not size; a size/TL;DR cue would help.

7. **Long opaque eval ingests + the `sleep`-block forced repetitive poll loops.** enron (5,486 email docs →
   ~20,600 chunks) took ~14 min, much of it a chunk-vector phase reporting `throughput=0d/s` (misleading — it
   *was* progressing by embedding %). With `sleep ≥1s` blocked, I fell back on bounded poll loops that ate
   turns. **Change (habit):** trust the background **completion notification** for long tasks instead of
   polling; **(tooling):** the readiness line could show an ETA / name the chunk-vector phase, and a
   `jseval await` would remove the poll-loop pattern.

## What worked unusually well (preserve)

- **Projection = pure `produce(run_dir)` over on-disk artifacts.** When the capped-run bug surfaced, I fixed
  the code and **re-`produce`d every projection from disk — zero eval re-runs** (~30 min saved). Pure-function-
  over-captured-artifacts is the pattern to keep for all eval projections.
- **The projection's self-reconciliation** (its presence call vs the harness `recallAtK`, 0 mismatches on all
  6 runs) gave per-corpus correctness confidence for free — a cheap built-in oracle worth replicating.
- **Measured-derived baselines** (`derive_baselines` mirroring the relevance ratchet's release-projection) —
  no hand-typed numbers to drift (tempdoc 623's anti-fork discipline, reused).
- **The user's critical-analysis-before-replan turn** ("did it satisfy the tempdoc?") caught the dormant-guard
  + sibling-gate gaps *before* "done" — the bidirectional-pass discipline earned its keep; keep inserting an
  explicit "does this satisfy the contract?" pass between implement and close.
- **Raw-gradle isolated backend + `--base-url --reset`** let the cross-corpus runs proceed *concurrently* with
  another session's recert (combined VRAM ~9.7 GB on a 12 GB card) once the `--start-backend` isolation gap was
  understood — the reusable recipe for multi-agent eval coexistence.

---

# Theorization — the retrospective issues, framings + the recurring shape (2026-06-24)

> Broad theorization (no design/implementation) on the §Session-retrospective issues: framings, directions,
> tradeoffs, hidden assumptions, and whether they share a shape. A *menu*, not a plan. These are
> harness/process issues, adjacent to 636's search subject — recorded here because the session surfaced them
> and because (below) they turn out to be instances of 636's own principles one layer out.

## The recurring shape (the broad finding): D-005's discipline is layer-invariant, and the harness/process violate it

The engine work in this tempdoc established three disciplines — **observe a funnel by recall-survival, not
cardinality** (the funnel principle); **one canonical record, projected not forked** (553/D-005); **every
narrowing auditable, no silent gate** (D-005). The session's *non-search* failures are violations of those
**same** invariants, one layer out (harness, eval-instrument, docs, agent-process):

- **Partial-view-as-complete** (the observability invariant). `quick_health` observes the *dev-runner* but not
  `jseval`-managed backends, so "not running" reads as "machine free" while a backend holds port 33221 — a
  partial coordination view masquerading as complete (verified: the dev-runner allocates `apiPort: 0` + writes
  a per-instance registry `runtime/instances/<uuid>/`; `jseval/backend.py` hardcodes 33221 and reads neither).
  Same shape: the **capped-run projection** counted *un-measured* queries in the denominator (unknown read as
  absent — the tri-state-collapsed-to-binary the slice-execution rule names); the **`throughput=0d/s`** readout
  is valid for the doc-ingest phase but read as "stalled" during the chunk-vector phase (a metric true for one
  phase, read as global).
- **Two-names / two-forks** (the canonical-record invariant). The **dual `scifact` slug** (run wants `scifact`,
  gate wants `beir/scifact`) is one entity with two names across subsystems. **Two backend-lifecycle managers**
  (`dev-runner.cjs` multi-instance-aware vs `jseval/backend.py` single-instance) are a fork of "manage a
  backend" — the contention is the drift between the forks. The **append-only tempdoc with no current-state
  slot** forks "what is true now" across 2,400 lines of history. Each is "projection-not-fork" violated: there
  should be one canonical dataset identity / one backend manager / one current-state header, projected to each
  consumer.
- **Label-as-silent-gate** (the auditability invariant). Writing **"fragile / flaky / tar pit"** is a *terminal
  label that hides the dropped diagnostic* — the process analogue of a stage reporting only its surviving count.
  Worse: a *documented* failure-story ("the AI-runtime tar pit") with matching symptoms became a ready-made
  non-diagnosis that **short-circuited** investigation. So a recorded failure-mode without a **discriminator** (a
  cheap test that confirms *this* mode vs a look-alike) is itself a silent mis-routing gate.

**The hypothesis worth holding (not yet a decision):** *observe-by-survival, one-canonical-record, and
no-silent-gate are layer-invariant systems-hygiene properties, not search-specific ones — so a team that fixes
them in the engine should audit for the same violations in the harness, the eval instruments, the docs, and its
own diagnosis habits.* D-005 may deserve a sibling note that says so. This is a lens, not a build.

## Solution-direction menus (named, tradeoffs surfaced, none chosen)

- **Contention (the biggest cost).** (A) **Dynamic-allocation-by-default** for jseval backends — adopt the
  dev-runner's `apiPort: 0` + a discoverable run-registry; *kills* contention by construction but breaks the
  fixed-33221 assumption many tools/docs encode (discovery cost). (B) **Lock + fail-fast** on 33221 + the data
  dir — cheap, serializes evals across agents; but the shared 12 GB GPU *already* forces near-serialization, so
  "serialize, don't isolate" may be the *simpler correct* answer and the isolation I built may be
  over-engineering. (C) **Collapse the two backend managers** — route eval backends through the dev-runner;
  fixes isolation + observability + the fork in one move, but is the largest change and `runHeadlessEval`
  eval-mode differs from dev-mode. (D) **Aggregate the existing per-instance registry** into `quick_health` /
  a `jseval ps` — observability only (makes collision *visible*, doesn't prevent it); cheapest. *Hidden
  assumption to test first:* do parallel agents ever *need* concurrent evals, or is serialize-via-lock
  sufficient? If the latter, B+D beats A/C.
- **Diagnosis gap.** Give every failure-handle/postmortem a **discriminator field** (the cheap test that
  confirms *this* failure vs a look-alike), and treat "flaky/fragile" as a *banned terminal word* in failure
  write-ups (must resolve to a cause or an explicit "unknown — next probe is X"). A `jseval doctor` that
  auto-dumps the discriminating facts (port owner, data-dir state, GPU, backend logs) on a backend failure
  would institutionalize "look before you label."
- **Measurement hygiene.** An instrument should **declare and uniformly apply its denominator** (the *measured*
  set). The capped-run bug was a denominator (all-qrels) wider than the numerator's detectable set (queried) —
  and the *same* projection got it right in reconciliation, wrong in bucketing. Direction: instruments
  self-assert scope-consistency (bucket scope == reconciliation scope), generalizable to any eval rate metric.
- **Identity.** One canonical dataset slug with an **alias map**, projected to run/gate/perf consumers — 553's
  projection-not-fork applied to dataset names.
- **Tempdoc structure.** A stable **"CURRENT STATE — read first"** header (rewritten in place: shipped /
  settled-remaining / deferred) + the append-only log below. The header is a *projection* of the latest design;
  the log is the audit trail. *Risk:* a rewritten-in-place header can itself go stale — a stale projection is
  worse than honest history — so it needs a freshness cue (or a lint that the header's "updated" date tracks the
  newest log entry).

## The enforcement-tier connection (why I repeated known mistakes)

I violated two prose-tier rules I *had in context* — `| tail` exit-masking (already in agent-lessons) and
`interrogate-results` / don't-label-flaky. The tier-register's own thesis explains it: prose ≈ 70% adherence,
and **time pressure + a ready-made story** drive the misses. So the session is *evidence* for the repo's own
"promote load-bearing prose to hooks" move: the two most-repeated, highest-risk agent mistakes here —
**backgrounding a run piped through `tail`** (correctness risk) and **shipping "fragile/flaky" as a diagnosis**
(investigation-terminating) — are concrete hook-tier candidates (a PreToolUse warn on `… | tail` of a
gradle/jseval run; a commit-time flag on "fragile/flaky/tar pit" in a tempdoc/postmortem without an adjacent
discriminator). Not built here — recorded as the highest-leverage, lowest-cost guards the retrospective implies.

## Hidden assumptions worth stating before any of this is designed

1. **Is the harness worth hardening at all?** With no users, the harness serves the *agents* — worth fixing
   *iff* agent-time is the bottleneck. This session says it is (many turns lost to contention + re-diagnosis).
2. **Will the contention persist?** Yes, while parallel worktrees + a recert loop run — it is structural, not
   incidental, so it will recur for every future eval agent.
3. **Serialize vs isolate.** The whole isolation effort assumes concurrent evals are *desirable*; the shared GPU
   suggests they may not be — challenge this before building dynamic allocation.

---

# Design theorization — the correct long-term design, scope-matched (2026-06-24)

> Per the design brief: theorize the *correct long-term design* for the remaining work / surfaced issues;
> investigate what exists; extend don't replace; match scope (no structure for cases the problem doesn't
> include); then judge reach. This **settles** the broad §Theorization menu above. General, not
> implementation-level.

## The judgment: the present problem requires NO new structure in 636

Investigated, three load-bearing facts decide the scope:

1. **The search problem is solved and handed off.** The instrument is built + activated, and its D-3 *purpose*
   is to **point** at the next lever — which it now does (the cross-corpus profile says **judge + legs, not
   leak**). D-6 explicitly makes the next *runtime* design a future session's job. Designing it here would be a
   **new** problem 636's original scope (buried signal) does not include — exactly the "structure for a case the
   problem doesn't yet have" the brief forbids. The instrument *is* the hand-off; pointing is done.

2. **The harness-coordination gap conforms to an existing authority — it is a FORK, not a missing feature.**
   The dev-runner already owns the machine-global coordination: `active.json` lease + `computeOwnershipVerdict`
   + dynamic `apiPort: 0` + an **isolation escape hatch** (`JUSTSEARCH_DEV_RUNNER_STATE_ROOT`, tempdoc 606).
   `jseval/backend.py` forked backend-lifecycle with **none** of it (fixed 33221, no lease) — the contention is
   the *drift between the two forks*. So the correct long-term design is **conform, not build**: the eval
   backend becomes a **participant** in the one authority — acquire/respect the shared lease (fail-fast on
   `CONTENTION`, and thereby *visible* to `quick_health`), or use the existing isolation mode when a private
   backend is genuinely needed. Both are **reuse** of existing dev-runner machinery; the size is small by
   construction. The serialize-vs-isolate default is a **parameter** (the shared 12 GB GPU argues *serialize*,
   with isolation as the escape hatch), **not** new structure.

3. **It belongs to an adjacent owner, not 636.** Multi-agent backend contention is **tempdoc 618's** subject
   (*agent-developer-velocity-friction* — it already names "Backend contention … owned by another active
   session (CONTENTION)"). 636 *surfaced* a concrete instance + the conform-not-fork direction; the disciplined
   move is to **record it and hand off**, not grow a coordination subsystem inside a search-quality tempdoc.

The other surfaced issues are scope-resolved without new 636 structure too: the capped-run denominator bug is
**already fixed** (attribution restricted to executed queries); the dual `scifact` slug and the tempdoc-bloat
are recorded harness/doc-hygiene findings (observations inbox) whose fix is an *alias* / a *current-state
header* (now added — §CURRENT STATE), not a subsystem. **Outcome: the correct design adds essentially no new
structure; it conforms existing forks to existing seams.** That the size came out near-zero is the *result* of
matching scope, not a target.

## Conformance: this design IS an instance of 553's principle (do not fork it)

The coordination design is **553's "one canonical authority over which records exist + who may project them;
every consumer a governed projection, never a parallel record"** applied to *backend instances* instead of
*execution records*: there must be **one** authority over which backends exist and who holds them, with the eval
backend a **participant**, not a second un-coordinated fork. Same shape, different domain — so it conforms to
553's seam (and the dev-runner's existing lease implementation of it) rather than inventing a parallel
coordination model. The eval *instruments* (Staged Recall Accounting) already conform to 553 on the data side
(a governed projection of `SearchTrace`); this extends the same discipline to the *lifecycle* side.

## Reach — the principle, its candidate scope, where code already violates it (recorded, NOT built)

**Principle (named): the D-005 discipline is _layer-invariant_ — _observe by survival, not by count; keep one
canonical authority and project from it; leave no silent gate_ — and it binds the harness, the eval
instruments, and the docs, not only the search engine.** Candidate scope + existing violations:

- **Coordination layer:** the jseval-vs-dev-runner backend **fork** (one-authority violated); `quick_health`'s
  partial view that reads "not running" while a jseval backend is live (observe-the-whole violated).
- **Eval-instrument layer:** the capped-run **denominator** that counted unmeasured queries (measure-the-
  measured-set violated — *fixed*); any future eval rate metric is a candidate (declare + uniformly apply the
  denominator).
- **Docs layer:** the dual `scifact` **slug** (one-canonical-identity violated); the append-only tempdoc with
  no current-state projection (one-authority-over-current-truth violated — the §CURRENT STATE header *is* that
  projection now).

**Do NOT build the generalized "apply D-005 at every layer" framework now.** Only the **coordination** instance
has a *demonstrated, repeated* cost (this session, twice, plus a wrong diagnosis), and even it conforms to an
existing seam (the lease) rather than needing new structure. Recognizing the shape so the *next* harness/doc
lever is built **as a step toward it** — not constructing the framework speculatively — is the point, and is the
same AHA / premature-abstraction discipline this whole tempdoc has practiced (one proven leak earned v3; one
proven instance earns the lease-conformance; the rest stay recorded candidate scope). Whether the layer-invariant
principle deserves promotion to a register decision (a D-005 corollary: *"these invariants bind every layer, not
just the engine"*) is left to the user — recorded, not self-promoted.

---

# Opportunity space — what to do with the instrument (research-informed, 2026-06-24)

> Pure research/theorization (no implementation; doc-only). What could be **polished, simplified, extended, or
> wrapped in UX** around Staged Recall Accounting, informed by a 2025–2026 pass over RAG-eval observability,
> failure taxonomies, recall-aware eval, and label-light evaluation. A recorded **menu**, not a plan — all
> eval/doc-side; the one runtime item stays D-6-future. No users / no rush; all viable.

## The instrument conforms to established field practice (conform, don't coin) — three corroborations
- **My three buckets ARE the canonical retrieval failure points.** `LEG_MISS` ↔ **FP1 Missing Content**,
  `CASCADE_LEAK` ↔ **FP3 Not-in-Context (consolidation drop)**, `JUDGE_RANK_LOW` ↔ **FP2 Missed Top-Ranked**
  (*Seven Failure Points*, [2401.05856](https://arxiv.org/abs/2401.05856)). The contribution is that the
  instrument makes FP1–FP3 **offline, per-query, measured shares with a regression gate** — that paper claims
  offline retrieval diagnosis is "difficult / only feasible during operation"; true *without* labels, refuted
  *with* qrels. The cascade-leak (FP3 as a measured share, distinct from FP1) is the sharpest piece.
- **The leak-gate is the field's standard regression-gate pattern** (measured baseline ± tolerance → fail the
  build; an established RAG-CI practice) — conform, not novel.
- **D-005's no-users caveat is doubly corroborated.** Nugget auto-eval (run-level Kendall τ≈0.89 but per-topic
  τ≈0.36–0.54 — "inadequate for fine-grained debugging", [2504.15068](https://arxiv.org/abs/2504.15068)) AND
  reference-free LLM judges (unreliable for *factual/fine* discrimination) both say: auto-labels = coarse
  run-level trend only; keep curated qrels for fine/ship decisions. D-005's stance is the field consensus.

## The menu (kind · viability · conforms-to · source)
| # | Idea | Kind | Note | Source |
|---|---|---|---|---|
| 1 | Name the buckets `FP1/FP2/FP3` in the projection output/docs | **polish** | trivial; pure legibility (conform-don't-coin, as done for "bounded recall") | 2401.05856 |
| 2 | §5 judge-ceiling: add a **multi-judge panel / pairwise-with-swap** | **polish** | literature: single judge psychometrically unstable; my live `top1_agreement 0.20` confirms high sensitivity | LLM-judge surveys |
| 3 | `jseval recall-profile --datasets …` — **one-command** cross-corpus profile + a "capability scorecard" | **simplify** | small wrapper over the existing `produce`; the capped-run fix makes it robust | existing projection |
| 4 | Per-bucket **fix recommendations** (attribution → matching lever) | **extend** | instrument already knows the dominant bucket → map: leg-miss→component/extraction (F-009); cascade-leak→recall-complete pool; judge-rank→sharper judge / judge-guided loop. Conforms to Doctor-RAG diagnose→localize→repair-per-type | [2604.00865](https://arxiv.org/abs/2604.00865) |
| 5 | **Answer-stage recall-survival** via nugget recall (label-light) | **extend** | extends the funnel retrieve→fuse→rerank→**answer**; auto-extracted nuggets **loosen the qrels dependency** (the instrument's main limit) and transfer to retrieval (do top-k docs contain the nuggets? = label-light retrieval recall-survival) | 2504.15068, [2411.09607](https://arxiv.org/abs/2411.09607) |
| 6 | Gate a **judge-guided recall loop** (RGS) as the next *runtime* lever | **extend** (runtime; **D-6-future**) | +13% nDCG / +28% recall at *constant* LLM budget; the leak-gate + judge-ceiling are exactly its gate (the instrument's purpose realized) | [2501.09186](https://arxiv.org/abs/2501.09186) |
| 7 | **Phoenix (OSS) as off-the-shelf UX** — point it at JustSearch's *existing* OpenInference/OTel spans | **UX** (reuse) | retrieval-span drill-down (docs+scores per stage). **↳ corrected (§Pre-completion confidence pass): NOT "~zero build" (Phoenix needs Python ≤3.12 + a server) and NOT the rich UI claimed — the spans carry no embeddings (→ no UMAP) and no qid (→ the recall-survival overlay is a code change, not "for free").** | Arize Phoenix / OpenInference |
| 8 | Recall-funnel **Sankey + per-query drill-down** | **UX** | native viz (band width = gold recall-survival per stage); a static artifact from the projection JSON, or via #7 | funnel/Sankey practice |

## The two strongest threads
- **UX = reuse, not build (#7).** The tempdoc deferred Layer-3 (which-stage attribution) as "only if a leak
  warrants it." But the *visualization* of the per-stage doc payload is off-the-shelf: JustSearch already emits
  OpenInference spans (`OpenInferenceSpanProjection`) + runs an OTel sink, and Phoenix ingests exactly that — so
  a developer-facing retrieval-funnel UI is a *configuration*, not a build, and conforms to the existing
  "governed projection of the OTel/SearchTrace record" seam (553/549) rather than forking a new UI.
  **↳ corrected (§Pre-completion confidence pass): "configuration, not a build" overstated it — Phoenix needs a
  Python ≤3.12 server (the 3.14 main env lacks the `sqlean` wheel), the spans carry no embeddings (no UMAP) and
  no qid (the recall-survival overlay needs a code change), so it is a *small build* delivering a trace-viewer,
  not a config delivering the rich UI.**
- **Loosening the qrels dependency is the highest-leverage extend (#5).** Today the instrument is eval-only
  because recall-survival needs curated doc-gold. Nugget recall (auto-extracted atomic facts) is *answer-stage*
  recall-survival with auto-labels, structurally transferable to *retrieval* — so it extends the instrument
  toward the no-users regime, with the same coarse-only caveat D-005 already records. The reliable hybrid the
  paper finds (human nuggets + auto-assign) mirrors D-005 exactly.

## Discipline (recorded, not built)
All eval/doc-side; none changes production. The one runtime lever (#6) stays D-6-future (the instrument is its
gate, not its builder). Per AHA, recording the menu ≠ building it; the lowest-cost / highest-legibility items
are the conform-polish (#1), the one-command profile (#3), and the **Phoenix-reuse UX (#7)** — none requires new
structure. Tooling map for orientation (not a recommendation to adopt): the field's 2026 stack splits RAGAS
(metric science) · DeepEval (CI gates) · TruLens (RAG-triad, span-attached) · Phoenix (retrieval viz); the
instrument is closest to a custom "metric-science + CI-gate", and #7 borrows only Phoenix's viz layer.

---

# Design theorization — the opportunity space, settled (2026-06-24)

> Settles the §Opportunity-space menu into the correct long-term design, scope-matched, after investigating
> what already exists. General, not implementation-level. Outcome: **almost no new structure** — because the
> structure the genuine extends would need *already exists*; the design is to recognize one shape and conform,
> not fork.

## The reframe (what the codebase investigation found)
The instrument's "gold signal" is **already a projection of a canonical per-query gold record**, and the
answer-stage half of the menu **already exists** as a separate instrument:
- **635's `queries.json` is the canonical gold record**: `{query, answer, evidence_ids, question_type}` — and
  the RAG-eval corpus adds **expected facts / golden answer / citation expectations** per query. **qrels is
  already a *projection* of `evidence_ids`** (`corpus_build.build_golden`). So Staged Recall Accounting consumes
  *one* projection (qrels, retrieval) of a record that also carries the gold *answer* and *facts*.
- **`rag_eval.py` / `RagQualityEvalTest` already measure the answer-stage recall-survival** the menu proposed as
  "extend #5": **`fact_coverage`** ("does the answer mention the required facts" — nugget recall by another
  name), `faithfulness`, `retrieval_recall`, `answer_similarity`, **`citation_recall`** (the 580 §16 citation
  funnel) — gated with the *same* ratio-threshold pattern as the leak-gate.

## The design: two instruments are two segments of ONE recall-survival funnel over ONE gold record
The correct long-term shape is **not a new answer-stage instrument** — it is to recognize that **Staged Recall
Accounting** (retrieval-stage recall-survival: leg-recall / cascade-leak / judge-rank, over the qrels
projection) and the **RAG quality eval** (answer-stage: fact-coverage / faithfulness / citation-recall, over the
answer/facts projection) are **two parallel, separately-gated forks of one recall-survival funnel over one
canonical gold record (635)**. The unified shape projects that one gold per stage — `evidence_ids → qrels` for
retrieval (exists), `answer → expected-facts/nuggets` for generation (exists) — so the full funnel **leg-recall
→ cascade-leak → judge-rank → fact-coverage → faithfulness → citation-recall** is one observable ("where does
the pipeline lose the answer"), not two disconnected gates.

**Scope judgment — recorded, NOT built.** 636's present problem (where's the *retrieval* bottleneck) is
**solved** by Staged Recall Accounting alone; the answer stage is a **different problem with its own owner**
(`rag_eval` / RAG quality). Composing the two is the right shape *only* when a forcing problem needs
**end-to-end** attribution across retrieval *and* generation — which 636 does not have. So the design adds no
structure now; it records the one-funnel/one-gold shape + the existing fork as the warrant for a future
composition. The **do-able-now** menu items need no design: the FP1/FP2/FP3 bucket rename, the one-command
profile, and the Phoenix-reuse UX (the OpenInference spans it consumes ARE the 553 Phase-D projection —
confirmed at `KnowledgeSearchEngine`/`SearchTraceMapper`) — none new structure.

## Conformance + reach — the principle is NOT new; conform to 637/553
The shape this reveals — *each stage's loss observable at that stage, as a projection of one canonical record,
never a symptom one layer up* — is **already named in the system**, so conform rather than coin:
- **637** ("make every stale state a *reasoned observable at its owning layer*, via the
  canonical-authority-and-projection seam, never surfacing one layer up as 'broken'") is the **sibling
  articulation** for *infra staleness*; Staged Recall Accounting is the same shape for *recall*. 637 already
  cites the same 553 seam.
- **553** is the canonical-record-+-projections seam both conform to; **549** is the staged funnel.
So the "layer-invariant D-005 discipline" named last turn is **an instance of 637/553's already-recognized
principle**, not a parallel one — honest status: *corroborated and already-seeded*, not novel. (This also
answers, more strongly than last turn, "is this an instance of an existing seam?" — yes, 637 is the proof.)

**The concrete existing violation it exposes (candidate scope, recorded not fixed):** the system runs **two
recall-survival instruments over the same gold without composing them or sharing the gold record** — a
two-forks-over-one-canonical-record violation (553) and a partial-funnel-view violation (each sees only its
segment). Where else the shape applies: the **citation funnel** (580 §16 — already a *production*
recall-survival, "harvest not build"), the **RAG context-budget** truncation. Per AHA, recognizing the
one-funnel shape so a future end-to-end need is met *as a step toward it* — not unifying the two instruments now
— is the point.

## Research posture + conditional triggers (no pass now — recorded for when a build is scheduled)
A targeted internet pass on the active-frontier areas was done this session (§Opportunity space) and **another
is not warranted now**: every item here is *recorded, not built*, so research would inform unscheduled builds
(research-ahead-of-need), and the frontier work already found (TruLens span-evals, Doctor-RAG
earliest-failure-localization, nugget eval) *corroborates* the design rather than threatening it. The
disciplined move is to record **what to research, and the trigger that makes it load-bearing** — so a future
agent researches exactly when, not before:
- **If the §5 judge-ceiling probe is polished →** research *LLM-judge panels / calibration / pairwise-with-swap*
  (the fastest-moving area; my live `top1_agreement 0.20` already flags single-judge instability).
- **If the qrels dependency is loosened (nugget extend #5) →** research *synthetic-qrels / LLM-nugget
  generation reliability* (the human-nuggets+auto-assign hybrid is the current reliable point — 2504.15068).
- **If the two instruments are composed into one end-to-end funnel →** research *unified retrieval+generation
  failure attribution / earliest-failure-point localization* (the surface my last pass only grazed; Doctor-RAG
  2604.00865 / AgentFixer 2603.29848 are the entry points).
- **If the Phoenix-reuse UX is adopted →** verify the *current* Phoenix ⇄ OpenInference span contract (a
  tool-API check, not a research question — do it at build time, not before, to avoid over-fitting to a moving API).

---

# Adjacent-work coordination (2026-06-24) — interference scan of tempdocs 616–656

> Scan of in-range tempdocs active in the last 5 h (635, 639, 640) + the in-range active worktree
> (`dead-code-638`). Records where neighbouring work touches 636's durable artifacts, so a future 636 agent
> (or the neighbours) coordinate rather than collide.

- **636 code is committed + intact, under a 635 commit (shared main checkout).** `aee90b5b5` —
  *"feat(635): commit D2/D3 cli.py hardening (+ the finished, shared 636 D-005 cli.py work)"* — the 635 agent
  faithfully committed my `cli.py` / `leak_gate.py` / `judge_ceiling.py` / `staged_recall_accounting.py` /
  `leak-gate-baselines.v1.json` (4-corpus pin verified present in `HEAD`). My **docs + the hook**
  (`search-engine-hint.mjs`, `search-quality-register.md`, `hooks-reference.md`, this tempdoc) remain
  **uncommitted** — to be staged explicitly (the shared-main hazard is live: stage own paths, never `-A`).
  **Re-verified intact post-churn (2026-06-24):** 909 jseval tests green (incl. 640's `metric_families`); the
  4 corpus projections re-`produce` identically with **0 reconciliation mismatches**; `leak-gate` PASS/FIRE
  hold — 640's registry **conformed** to the design (registered leak as projection-sourced), did not break it.
  The 639 *extend-don't-fork* ask is now recorded as register **Q-013**.
- **635 (eval corpus / self-demo recert) — the primary *operational* interferer (recurring).** Its
  `corpus-fidelity` recert runs jseval backends on the shared port 33221 + `tmp/headless-eval-data` + GPU, and
  **collided with this session's evals twice** (§Root-cause). Every future 636 eval (re-deriving a leak
  ceiling, re-running the cross-corpus profile, the ratchet the hook suggests) will collide while 635's recert
  runs. This is exactly the lease-conformance the §Design-theorization assigns to the harness/618.
- **640 (perf ratchet) — shares the hook file.** 640 *owns* `search-engine-hint.mjs` (it added the perf
  ratchet there, `3e386db40` / `ea16a8dd4`); my uncommitted edit adds the **leak** ratchet as the sibling
  third. No conflict now (my edit sits on 640's committed base), but the file is co-owned + 640 is active →
  coordinate before either re-edits it. The leak/perf/relevance ratchets are now siblings by design, not rivals.
- **639 (candidate-set integrity — ANN recall + dedup) — a stub *spawned by this session's* 636 coverage
  analysis; the long-term fork risk to watch.** It owns the ANN-recall / near-duplicate thread my eval surfaced
  (`ann_proof FAIL`) and dropped. **Coordination ask:** 639's "candidate-set completeness" measurement should
  **extend Staged Recall Accounting's leg-recall layer (a governed projection), not build a parallel recall
  instrument** — that would be the exact 553/one-authority fork the §Reach principle names. No conflict today
  (639 is a no-implementation stub); flagged so the design phase conforms.
- **638 (dead-code sweep, worktree `dead-code-638`) — low risk.** Isolated worktree on a pre-636 base; my new
  modules are referenced (cli commands + `__init__` registration + the hook), so a later sweep should not flag
  them — but a dead-code pass is inherently shared-infra-risky; worth a glance if it merges near 636 files.

---

# Pre-implementation confidence pass — the do-able-now items (2026-06-24)

> Read-only de-risk (no feature work) before implementing any opportunity-space item. It **corrects two
> over-optimistic claims** I made and pins per-item confidence.

- **#7 Phoenix UX is "config-level", NOT "zero build" (corrected).** Verified: JustSearch emits OpenInference
  **retriever AND reranker** spans (`OpenInferenceSpanProjection` — *better* than the "reranker only" I assumed),
  with the standard `retrieval.documents.` / `reranker.output_documents.` conventions; the OTLP export endpoint
  is **env-configurable with fan-out** (`OTEL_EXPORTER_OTLP_ENDPOINT`, `TracingBootstrap.java`). **But** the
  local OTLP sink **captures to NDJSON; it does not forward to Phoenix.** So #7 = *run Phoenix (OSS) + point/
  fan-out the OTLP exporter at it (one env var) + (for the eval buckets) join the projection's per-qid bucket
  onto the eval-run spans by qid* — a config + an OSS server + a small join; **not free, but not a code build.**
  Confidence 6–7.
- **#1 is an ANNOTATION, not a rename (corrected).** The projection tests assert on bucket *keys*
  (`b["OK_RANK1"]` …) and the leak-gate keys on `leak_rate` (a rate, not a name) — so add an `fp_mapping` field;
  do **not** rename the keys. Trivial + safe. Confidence 9.
- **#3 splits cleanly.** `produce()` is pure over a run dir's artifacts (qrels/per_query/trec — no backend), so a
  `recall-profile` that **re-produces from existing run dirs** (reusing `leak-gate-derive`'s `_<slug>` discovery)
  is trivial (conf 9); one that **runs fresh evals** inherits the unsolved multi-agent isolation gap (conf ~4).
- **#2 §5 panel is bounded** to prompt-variants of the **single** in-stack chat model (`llmModelPath`); a
  *diverse* panel — where the literature's reliability gain comes from — needs a 2nd model served separately.
  Confidence 5 (feasible, limited value as-is).
- **#4 fix recommendations**: a lookup over the dominant bucket → lever; low technical risk, but must be framed
  as a *candidate* lever, not a verdict (leg-miss → encoder / SPLADE / extraction is a judgment). Confidence 7.

**Overall confidence for the do-able-now work: ~7/10.** The high-value/low-risk items (#1 annotate, #3
re-produce, #4) are solid; the flashiest (#7) is real but **config-level + an OSS server**, not free; #2's value
is the main soft spot. No item is a *design*-invalidating surprise — the only correction is scope/effort
labelling, not feasibility.

---

# IMPLEMENTED — opportunity-space do-able-now items (2026-06-24)

## Phase A — `jseval recall-profile` (#1 + #3 + #4) — DONE, validated
- **#1 FP annotation:** `staged_recall_accounting.produce()` now emits `fp_mapping`
  (`LEG_MISS→FP1 Missing-Content`, `CASCADE_LEAK→FP3 Not-in-Context`, `JUDGE_RANK_LOW→FP2 Missed-Top-Ranked`) —
  an annotation, keys unchanged.
- **#3 cross-corpus profile:** new `jseval/recall_profile.py` (`build_recall_profile` → `recall-profile.v1`,
  conforming to `suite_profile.build_profile`'s snapshot shape) + `jseval recall-profile --datasets …` (reuses
  `leak-gate-derive`'s run-dir discovery, requires the **leg modes** present — a hybrid-only run can't decompose
  — re-`produce`s pure, no backend).
- **#4 candidate recommendations:** dominant-bucket → candidate lever (framed *candidate, not verdict*), folded
  into the profile JSON.
- **Validated:** full jseval suite **924 passed**; `recall-profile` over the 4 existing run dirs **reproduces
  the known profile** — leak smallest (CASCADE_LEAK mean **0.058**), **JUDGE_RANK_LOW dominant (0.214)**, 0
  reconciliation mismatches on all 4; the recommendation correctly points at the judge. No UI → no browser.

## Phase B — Phoenix dev-observability UI (#7) — JustSearch side EMPIRICALLY validated; renderer ENV-BLOCKED
- **The JustSearch side (the part 636 owns) is empirically confirmed live.** With `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed`
  + `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` set, a real search through an isolated backend emitted, and an OTLP
  collector captured, the full OpenInference span set — **`openinference.span.kind` CHAIN / RETRIEVER /
  RERANKER**, with `retrieval.documents.*.document.id`, `reranker.output_documents.*`, `reranker.model_name`.
  These are exactly the spans Phoenix ingests + renders.
- **Recipe (empirically corrected — the confidence pass missed the tracing gate):** OpenInference span emission
  is **gated on `tracing_level != "none"`** (`DefaultWorkerAppServices.java:111`; default `none`), so the recipe
  is THREE env vars, not one: `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` (+ `JUSTSEARCH_HEAD_TRACING_LEVEL=detailed`)
  **and** `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=<Phoenix OTLP collector, e.g. http://localhost:6006/v1/traces>`,
  then run Phoenix and open its UI. Still config-level, no JustSearch code.
- **The Phoenix *renderer* is environment-blocked — the live UI was NOT browser-validated.** `arize-phoenix`
  hard-requires `import sqlean` (its SQLite store), and `sqlean.py` (3.x) has **no Python-3.14 wheel** and fails
  to build from source on this Windows env (deterministic; a 0.1 "wheel" on PyPI is a placeholder squatter). So
  Phoenix can't run here. Per the verify-the-UI rule, **#7 is NOT "successfully implemented"** — the live UI +
  browser screenshot could not be produced. To stand it up: run Phoenix on **Python ≤3.12** (or a container),
  then apply the recipe above; the spans are proven Phoenix-shaped, so the renderer is the only missing piece.
  (Env left clean: the squatter + the phoenix-specific packages were uninstalled; jseval + the OTLP sink
  verified working post-cleanup; the generic OTel upgrade is benign.)

## Deferred (unchanged): #2 §5 judge-panel (value-bounded) + the recorded-not-built extends (funnel
composition, nugget loosen-qrels, judge-guided loop).

---

# Pre-completion confidence pass — #7 (the env-blocked Phoenix UI), 2026-06-24

> Read-only de-risk before any attempt to *complete* #7. **Corrects two over-claimed #7 outcomes** and resolves
> the "is it even completable here" gate.

- **Completable here? YES (gate passes).** A **Python 3.12.13** interpreter exists on the machine
  (`F:/image-gen/python/...cpython-3.12.13...`), so Phoenix can run in an isolated 3.12 venv (Docker is not
  available). So #7 is not permanently env-blocked — it needs a 3.12 venv, not this 3.14 env.
- **CORRECTION — no UMAP.** The captured JustSearch RETRIEVER spans carry `retrieval.documents.*.{id,content,score}`
  but **no embedding vectors**, so Phoenix's **UMAP embedding-clustering view will NOT work**. The realistic #7
  outcome is a **trace-tree + retrieval/rerank drill-down** (each stage's docs + scores) — useful, but the
  menu's "UMAP clustering" claim is wrong for these spans.
- **CORRECTION — the recall-survival overlay is NOT "free".** The spans carry **neither a qid nor the query
  text**, so "attach the recall-survival bucket as a span eval → the deferred Layer-3 visualization for free"
  is false: joining eval-time qrels-buckets to production spans needs a **code change** (emit a stable qid on
  the span), not a join. So that part is a small *build*, not a config.
- **Recipe (verified):** Phoenix OTLP/HTTP collector is `http://localhost:6006/v1/traces`; the backend needs
  `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` + `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:6006/v1/traces`
  (the span flow itself was already empirically confirmed).
- **#2 §5 panel:** the AI runtime serves a single chat model (`llmModelPath`) → a "panel" is prompt-variants
  only (not the diverse panel the reliability gain needs). Value-bounded; stays deferred.

**Net:** completing #7 is *feasible* (~6–7) but delivers a **basic trace-viewer**, not the rich
"UMAP + recall-survival overlay" the menu implied — and the multi-component standup (3.12 venv → Phoenix →
backend → OTEL → browser) is fragile. So the honest question is **value-vs-effort at the reduced scope**, not
feasibility.

---

# Disposition of the theoretical / recorded-not-built items (2026-06-24)

> Triage of every *theoretical* thread this tempdoc carries: does it **move** to another tempdoc, **stay** here,
> or warrant a **new** one. Driven by the spun-out children — 639 (candidate-set side) already exists; this
> triage spawns 643 (judge side). Goal: each thread has exactly one owner so nothing is lost when 636 closes,
> and 636 stops being the catch-all (the §6 bloat finding).

| Theoretical thread | Disposition | Why |
|---|---|---|
| Candidate-set completeness / ANN recall (the LEG_MISS / leg side) + dedup | **MOVED → 639** (done) | 639 spun out of 636's coverage analysis; owns the candidate-set *as a set*, the ANN-recall gate **upstream** of 636's fusion funnel. Already cross-linked both ways. |
| Judge-stage ranking quality (JUDGE_RANK_LOW — the profile's **dominant** bucket) / sharper reranker / judge-guided recall loop | **NEW → 643** (this triage) | Symmetric judge-side counterpart to 639; was a bare "D-6 future-tempdoc" line, the biggest *measured* gap, unowned. Its measurement (§5 judge-ceiling probe) already exists, so 643 starts at design-gated-on-measurement. |
| The unified recall-survival funnel (Staged Recall Accounting ⊕ `rag_eval` answer-stage, over one 635 gold) | **STAY (recorded warrant) → new tempdoc only WHEN forced** | A cross-instrument *composition*, not a single-lever owner; no forcing problem (no end-to-end attribution need) → spinning it out now would be the speculative structure the discipline forbids. The nugget / loosen-qrels extend folds in here. |
| Design v1 — the very-long-doc embedding seam (representation-context ⟂ match-granularity) | **STAY in 636** | 636's original headline; eval-gated (the eval doesn't exist). An earlier attempt to spin it into a separate tempdoc was **folded back as redundant** (see §"folded back" history) — do not re-spin. |
| Layer 3 — *which internal stage* leaked (cascade-leak localization) | **STAY in 636** | The instrument's own deferred layer; funnel-**internal**, distinct from 639's upstream ANN gate. Deferred because leak is not the dominant bucket. |
| Harness-coordination fork (jseval backend ↔ dev-runner lease) | **MOVED → 618** (recorded) | 618 (agent-developer-velocity-friction) owns multi-agent backend contention; 636 surfaced the instance + the conform-not-fork direction and handed it off. |
| The D-005 **layer-invariant** principle (observe-by-survival; one canonical authority; no silent gate) | **CONFORMS → 637** (pointer, stay-named-here) | 637 (silent-staleness → *observe at the owning layer*) is the general form; 636 named a derived instance and points at it. No move — 637 already owns the generalization. |

**One-line:** the two halves of 636's coverage analysis now each have a home — **639 (candidate-set/leg)** and
**643 (judge)**; the genuinely-636 threads (embedding seam, Layer 3) **stay**; the cross-cutting ones are
**handed off** (coordination→618, principle→637); and the only *non-spun* design (the unified funnel) **stays
recorded** until a forcing problem earns it a tempdoc.

---

# Session handoff state — READ IF CONTINUING (2026-06-24)

> What a fresh agent needs that `main` alone does not show. The instrument (Staged Recall Accounting projection,
> `leak_gate`, §5 `judge_ceiling`) **is committed on main**; the Phase-A usability layer below **is not**.

- **✅ Phase A (`recall-profile` #1/#3/#4) is COMMITTED — `78e765717` (2026-06-24), scoped to 9 files**
  (`recall_profile.py` + its test, `cli.py`, `staged_recall_accounting.py` + its test, this tempdoc, 639, 643,
  and the register). It is now on local `main` (not yet pushed to `origin` — `main` was ahead by 3, two of which
  are other agents'). So "Phase A shipped" is now literally true on local `main`. (Was working-tree-only until
  this commit.)
- **⚠ Commit must be SCOPED — the working tree holds OTHER agents' uncommitted work** (`modules/app-api/**` +
  `modules/ui-web/**` schema/fixtures, tempdocs 634/635/640, observations). Stage my paths explicitly
  (`git add` the files below); never `git add -A`. My set also includes `docs/reference/search-quality-register.md`
  (F-025 now names owners 639/643).
- **⚠ Docs-regen before committing the register — but DO NOT blindly run `skills-sync.mjs`:** it is
  **non-idempotent** (appends ~1086 lines/run, bloats the search-quality `SKILL.md` — known bug, logged in the
  inbox). `llmstxt-generate.mjs` is safe but reads the working tree, so run it *after* the other agents' doc
  edits are out of the tree, or it folds their 634/635/640 changes into `llms.txt`. Safest: commit my code +
  tempdocs first; regenerate the register-derived skill block in a separate, verified step.
- **Verification evidence + its limits:** `pytest tests/test_recall_profile.py tests/test_projections_staged_recall_accounting.py`
  = **35 green, fully reproducible** (pure, no backend). BUT the headline "`recall-profile` reproduces the
  4-corpus profile (judge-rank dominant, leak smallest)" was produced from **~62 ephemeral run dirs under
  `tmp/eval-results/` that are gitignored and will NOT survive a fresh checkout** — to re-verify that claim a
  fresh agent must re-run the leg-mode evals (per `search-engine-hint`) on ≥2 corpora, not just trust the note.
- **Unverified / deferred (do not forget):**
  - **#7 Phoenix renders the funnel *usefully*** — UNVERIFIED. Only span *emission* is empirically confirmed;
    Phoenix itself was never stood up (env), so whether its UI is a usable funnel view vs. a raw trace tree is a
    verify-at-build-time check (see §Pre-completion confidence pass).
  - **643's lever is design-only** — the judge-rank gap is *measured* (§5 probe) but no lever is designed/built.
  - The unified funnel + Design v1 embedding seam stay **eval-gated** (the gating evals do not exist).
- **Coordination:** tempdoc **645** ("split the jseval `cli.py` monolith") targets the same `cli.py` I added the
  `recall-profile` command to — whoever does 645 must carry that command across.
