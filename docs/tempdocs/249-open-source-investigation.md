---
title: "249 — Open-source investigation (plan + 10 findings)"
type: tempdocs
status: done
consolidated: 2026-06-09
---

# 249 — Open-source investigation (plan + per-tool findings)

> Consolidated 2026-06-09 (tempdoc-hygiene pass) from the 249 open-source-investigation batch — the plan + 10 per-tool findings docs that all shared #249. Each section is the original verbatim; originals retired to git.

---

## Investigation plan

*(from `249-open-source-investigation-plan.md`)*

### 249: Open-Source Investigation Plan

**Purpose:** Identify open-source projects we can learn from and plan how to
investigate them. JustSearch's hybrid retrieval stack (BM25 + SPLADE + dense KNN
+ cross-encoder + LambdaMART) is more sophisticated than most desktop apps, so
the most useful peers are search engines and RAG frameworks, not other desktop
apps.

**Related:** tempdoc 248 (tech landscape scan), tempdoc 234 (retrieval
architecture alternatives), `docs/future-features/technology-alternatives-research.md`

---

## 1. Landscape Summary

### Where JustSearch sits

JustSearch is a **local-first desktop search app** that embeds Lucene directly
(no server wrapper) and runs local LLM inference via llama-server. Its retrieval
pipeline — three-way hybrid (BM25 + SPLADE + dense KNN) with RRF fusion,
cross-encoder reranking, and LambdaMART feature-based reranking — is
architecturally ahead of every open-source desktop app surveyed.

The most popular desktop RAG apps (GPT4All 77k stars, AnythingLLM 55k stars,
PrivateGPT 57k stars) all use **pure vector retrieval** with no BM25 or SPLADE
leg. Their retrieval quality is significantly weaker than hybrid approaches.

The projects we can learn the most from are **search engines** that implement
similar or more advanced hybrid retrieval, and **RAG frameworks** that have
solved pipeline composition problems well.

### Projects surveyed (23 total)

| Tier | Project | Stars | Why it matters |
|------|---------|-------|----------------|
| **Study deeply** | Vespa | 6.8k | Most sophisticated open-source hybrid search; SPLADE + dense + BM25 + ColBERT multi-phase ranking |
| **Study deeply** | OpenSearch (neural sparse) | — | Production-proven SPLADE-on-Lucene; same Lucene foundation as JustSearch |
| **Study deeply** | Infinity (InfiniFlow) | 4.4k | Four-way hybrid (BM25+dense+sparse+ColBERT) in an embeddable binary; sub-ms latency |
| **Study selectively** | Milvus (+ Milvus Lite) | 43.1k | Three-way hybrid with Tantivy BM25; embeddable via Lite; filtered ANN strategies |
| **Study selectively** | Qdrant | 29.2k | Relevance feedback API; sparse vectors; quantization strategies; Rust HNSW |
| **Study selectively** | RAGFlow | 74k | Document layout parsing before chunking; template-based chunking |
| **Study selectively** | qmd | 11.2k | Solo-dev TypeScript app with identical pipeline (BM25+vector+RRF+LLM-rerank); LLM query expansion |
| **Reference only** | Haystack | 24.4k | Clean pipeline composition model (component graph) |
| **Reference only** | LlamaIndex | 47.3k | QueryFusionRetriever (RRF), cross-encoder post-processing node |
| **Reference only** | Onyx (Danswer) | 17.6k | Enterprise RAG on Vespa; knowledge graph layer |
| **Not relevant** | GPT4All, AnythingLLM, PrivateGPT, Khoj | 77k-32k | Pure vector retrieval; nothing to learn on search quality |
| **Not relevant** | Tantivy, Quickwit, Stract | 14.6k-2.4k | No vector/SPLADE support; Lucene is more capable for hybrid |
| **Not relevant** | Meilisearch, Typesense, Weaviate | 54k-15.7k | Simpler hybrid; no SPLADE; licensing concerns (BUSL, GPL) |
| **Not relevant** | DocFetcher, Recoll | <1k | BM25 only; legacy architecture |

---

## 2. Deep Investigation Targets

### 2.1 Vespa — Multi-phase ranking and hybrid search

**What:** The most architecturally mature open-source hybrid search engine.
Java + C++, Apache 2.0, daily releases, 94k commits.

**Why study it:**
- First-class SPLADE via built-in `splade-embedder` component
- Multi-phase ranking: WAND/ANN retrieval → approximate re-rank → global
  re-rank with cross-encoder via ONNX
- Native ColBERT late-interaction support
- Score normalization and fusion techniques for hybrid queries
- Per-phase timing and explain output

**What to investigate:**
- [x] **Hybrid query execution model** — How does Vespa combine BM25 + SPLADE +
  dense in a single query? Is it parallel fanout with fusion, or sequential
  filtering? How does it handle the "which leg to trust" problem?
- [x] **Score normalization** — What normalization technique does Vespa use before
  fusing sparse and dense scores? Min-max? Z-score? Per-query or global?
- [x] **SPLADE integration** — How does Vespa store SPLADE sparse vectors? What
  data structure? How does it differ from Lucene's FeatureField (which has an
  8-bit mantissa precision limitation)?
- [x] **Multi-phase ranking pipeline** — How are ranking phases defined, composed,
  and debugged? What observability does each phase expose?
- [x] **ColBERT MaxSim reranking** — How is late-interaction scoring implemented?
  What's the storage overhead for per-token vectors?

**Key resources:**
- GitHub: github.com/vespa-engine/vespa
- Blog: "Redefining Hybrid Search Possibilities with Vespa"
- Docs: docs.vespa.ai (ranking, query profiles, searchers)

### 2.2 OpenSearch Neural Sparse — SPLADE on Lucene at scale

**What:** OpenSearch wraps Lucene and adds neural sparse search (SPLADE-compatible)
as a production feature. Same Lucene foundation as JustSearch.

**Why study it:**
- The largest production deployment of SPLADE on Lucene's FeatureField
- Custom sparse encoding models (opensearch-neural-sparse-encoding-v1/v2)
- Neural Sparse ANN (approximate sparse retrieval) for speed at scale
- Search Pipelines (GA in 3.4.0) — modular processor composition

**What to investigate:**
- [x] **FeatureField usage** — 8-bit mantissa accepted, not solved. No workaround needed.
  SPLADE-v3 weights (0-2.5) safely within range. `max_token_score` WAND optimization
  transferable (~4x latency reduction).
- [x] **Neural sparse ANN** — SEISMIC cluster-summary gating. Not applicable at desktop
  scale. Two-phase token pruning is a transferable pattern if needed.
- [x] **Search Pipelines architecture** — 3-type processor model (request, phase results,
  response). Composable normalization + reranking. Applicable as architectural pattern.
- [x] **Score normalization in hybrid queries** — min_max + arithmetic_mean with tuned
  weights beats RRF (+4.5-7.8% nDCG@10). Zero-exclusion for single-leg docs. **Highest
  impact finding.**
- [x] **Sparse model v2 improvements** — DistilBERT halves params at equal quality.
  Doc-only mode uses IDF-weighted tokenization (no neural inference at query time).

**Findings:** [`249-opensearch-findings.md`](249-opensearch-findings.md)

**Key resources:**
- GitHub: github.com/opensearch-project/OpenSearch
- Docs: docs.opensearch.org — neural sparse search, search pipelines
- Blog: opensearch.org/blog — hybrid search best practices

### 2.3 Infinity — Four-way hybrid in an embeddable binary

**What:** C++ search engine from InfiniFlow (same team as RAGFlow). Does
BM25 + dense + sparse + ColBERT tensor reranking in a single binary with
sub-millisecond latency. 4.4k stars, Apache 2.0.

**Why study it:**
- Most complete hybrid retrieval in an embeddable package
- ColBERT multi-vector reranking as a native index type (not post-processing)
- Designed as RAGFlow's backend, so optimized for RAG workloads
- Claims 0.1ms query latency on millions of vectors

**What to investigate:**
- [x] **Four-way fusion** — How does Infinity fuse BM25 + dense + sparse +
  ColBERT scores? What weights/normalization? Is ColBERT used as a reranker
  on fused candidates, or as a fourth retrieval leg?
- [x] **ColBERT as native index type** — How are per-token vectors stored and
  queried? What's the storage overhead vs quality gain? How does MaxSim
  scoring integrate with the index scan?
- [x] **Latency engineering** — How does Infinity achieve 0.1ms queries? What
  data structures, SIMD optimizations, memory layout decisions?
- [x] **Sparse vector indexing** — How does Infinity index SPLADE vectors?
  Inverted index? Block-max? How does it compare to Lucene FeatureField or
  Seismic?

**Key resources:**
- GitHub: github.com/infiniflow/infinity
- Blog: infiniflow.org/blog — "Best Hybrid Search Solution"
- RAGFlow integration code

---

## 3. Selective Investigation Targets

### 3.1 Qdrant — Relevance feedback and quantization

**What to learn:**
- [x] **Relevance feedback API** — Three recommend strategies (average_vector,
  best_score, sum_scores) + discovery API with context pairs. average_vector is
  directly implementable in Lucene via synthetic query vector + MoreLikeThisQuery.
- [x] **Quantization strategies** — Scalar Int8 (same as JustSearch), binary (not
  viable at 768 dims), PQ (not recommended). Key pattern: rescore + oversampling
  (retrieve N*factor candidates quantized, rescore top-K with float32).
- [x] **Sparse vector indexing** — Custom inverted index with inline WAND pruning
  via `max_next_weight`. Functionally equivalent to Lucene FeatureField + WAND.
  No changes needed for JustSearch.
- [x] **Filtered vector search** — Three-strategy adaptive approach (brute-force
  scan for selective filters, HNSW for broad filters, filterable HNSW subgraphs).
  Threshold-based fallback is directly applicable to JustSearch.

**Findings:** [`249-qdrant-findings.md`](249-qdrant-findings.md)

**Key resources:**
- Docs: qdrant.tech/documentation
- Blog: qdrant.tech/articles (especially "Modern Sparse Neural Retrieval")

### 3.2 Milvus — Filtered ANN and DiskANN

**What to learn:**
- [x] **Filtered ANN strategies** — Dual-Pool HNSW traversal (two priority queues),
  brute-force fallback at <10% match rate, bitmap index for low-cardinality filters.
  Selectivity-based fallback is transferable to JustSearch.
- [x] **DiskANN** — Vamana graph + PQ on SSD. Not applicable at desktop scale (<1M
  docs). AISAQ (2.6.4+) eliminates PQ from RAM — future reference only.
- [x] **Hybrid fusion** — RRF (k=60, same as JustSearch) + WeightedRanker with
  arctan normalization. Arctan has ranking inversion bug (fixed via normalize=false).
  No learned reranking (no cross-encoder, no LambdaMART).
- [x] **Segment compaction** — LSM-tree model with L0/MixCompaction/Clustering
  Compaction. Clustering Compaction (24x QPS) is interesting but not transferable
  to single-node Lucene.

**Findings:** [`249-milvus-findings.md`](249-milvus-findings.md)

**Key resources:**
- Docs: milvus.io/docs — hybrid search, filtered search, DiskANN
- GitHub: github.com/milvus-io/milvus

### 3.3 RAGFlow — Document layout parsing

**What to learn:**
- [ ] **Layout-aware PDF parsing** — Custom models for tables, figures, headers.
  How does this improve chunking quality vs pure text extraction (Tika)?
- [ ] **Template-based chunking** — Domain-specific chunk boundaries for papers,
  manuals, code docs. How are templates defined?
- [ ] **Deep document understanding** — What models power their visual parsing?
  What's the accuracy/speed trade-off?

**Key resources:**
- GitHub: github.com/infiniflow/ragflow
- Docs: ragflow.io

### 3.4 qmd — LLM query expansion

**What to learn:**
- [x] **LLM query expansion before retrieval** — Three-type expansion (lex/vec/hyde)
  via fine-tuned 1.7B model with GBNF grammar. Strong-signal gating skips expansion
  when BM25 is confident. No published before/after recall metrics.
- [x] **LLM-as-reranker via GGUF** — Qwen3-Reranker-0.6B via node-llama-cpp
  RankingContext API. Parallel context pool. Position-aware score blending (75/25,
  60/40, 40/60 by rank tier). No published latency benchmarks.

**Findings:** [`249-qmd-findings.md`](249-qmd-findings.md)

**Key resources:**
- GitHub: github.com/tobi/qmd

---

## 4. Investigation Methodology

### 4.1 Session structure

Each chat session focuses on **one project only**. This keeps context focused
and avoids shallow coverage across multiple projects.

Each project investigation has **two phases**:

**Phase 1 — Reconnaissance.** Build context and plan the deep-dive.

1. **Load JustSearch baseline** — Consult canonical docs (`docs/llms.txt` →
   `docs/explanation/`, `docs/reference/`) and relevant tempdocs (especially
   tempdoc 245 for eval results) to understand JustSearch's current
   implementation of the features being investigated. This establishes the
   comparison baseline. If the docs don't cover a specific detail, read the
   relevant JustSearch source code directly.
2. **Skim the external project** — Read docs, blog posts, and repo structure
   to understand the project's architecture and identify where the answers to
   our checklist items actually live (which modules, which files, which docs
   pages).
3. **Produce a recon plan** — A written message to the user listing:
   - Which JustSearch docs/source were consulted for the baseline
   - For each checklist item: which external docs pages, source files, or blog
     posts to read, and in what order
   - Items that look unlikely to be answerable from docs + source alone
   - Anything to skip and why

**The recon plan is a user checkpoint.** The user reviews and approves (or
adjusts) before the deep-dive begins. This prevents wasting context on the
wrong files.

**Phase 2 — Deep-dive.** Execute the approved recon plan.

1. **Read sources** — For each checklist item, read the identified docs and
   source code. Focus on the specific features listed in §2/§3, not the entire
   codebase. For large source files (>500 lines), target specific functions or
   classes identified during recon rather than reading the full file. Use code
   search (grep, symbol lookup) to locate the relevant section, then read only
   that section with surrounding context.
2. **Assess applicability** — Compare what the external project does against
   the JustSearch baseline loaded in Phase 1. Determine whether each technique
   is directly applicable, needs adaptation, or is not applicable.
3. **Document findings** — Write up each investigated feature per §4.5.
4. **Flag unresolved items** — If a checklist item cannot be answered from docs
   and source code, mark it as **unresolved** in the findings doc with a note
   on what would be needed to answer it (e.g., "requires running the project,"
   "requires benchmarking," "implementation is in generated/obfuscated code").
   Do not guess or skip silently.
5. **Capture bonus findings** — If the investigation reveals something valuable
   that wasn't in the original checklist (e.g., an unanticipated optimization,
   a relevant design pattern), document it in an "Additional Findings" section
   in the sub-document. Keep it brief — these are leads for future work, not
   full investigations.

### 4.2 Benchmarking

Investigation sessions **do not benchmark or prototype**. The agent cannot run
these external projects locally, and prototyping within JustSearch is a separate
implementation task.

Instead, findings should document:
- Benchmarks **reported by the project** (their own measurements, papers cited)
- What a JustSearch prototype or benchmark **would look like** (which modules to
  change, what to measure, estimated effort)
- Whether the technique's claimed gains are plausible given JustSearch's corpus
  size and hardware constraints

Actual benchmarking happens in dedicated implementation sessions after the
investigation phase is complete.

### 4.3 Investigation order

| Priority | Target | Focus | Effort | Notes from §5 research |
|----------|--------|-------|--------|------------------------|
| 1 | OpenSearch neural sparse | FeatureField precision, score normalization | 1 session | **DONE.** Findings: `249-opensearch-findings.md` |
| 2 | Anserini | Fake-words vs FeatureField comparison, ImpactSimilarity | 1 session | **DONE.** Findings: `249-anserini-findings.md` |
| 3 | Vespa | Hybrid fusion, multi-phase ranking, SPLADE storage | 1-2 sessions | **DONE.** Findings: `249-vespa-findings.md` |
| 4 | Docling | HybridChunker, TableFormer, docling-java integration | 1 session | **DONE.** Findings: `249-docling-findings.md` |
| 5 | Qdrant | Relevance feedback API, quantization, filtered ANN | 1 session | **DONE.** Findings: `249-qdrant-findings.md` |
| 6 | Infinity | Four-way fusion, ColBERT indexing | 1 session | **DONE.** Findings: `249-infinity-findings.md` |
| 7 | Pyserini | Convex combination fusion, min-max normalization | 0.5 session | **DONE.** Findings: `249-pyserini-findings.md` |
| 8 | qmd | LLM query expansion, LLM-as-reranker | 0.5 session | **DONE.** Findings: `249-qmd-findings.md` |
| 9 | Milvus | Filtered ANN, DiskANN | 0.5 session | **DONE.** Findings: `249-milvus-findings.md` |
| 10 | SentenceTransformers | Loss functions, failure modes, Matryoshka | 0.5 session | **DONE.** Findings: `249-sentencetransformers-findings.md` |
| 11 | RAGFlow | Layout-aware PDF parsing | 0.5 session | **Downgraded.** Docling covers the same ground better |
| — | vLLM, SGLang, ExLlamaV2, kobold.cpp | — | — | **Removed as investigation targets.** §5.3 research answered all questions — nothing left to investigate |
| — | FlagEmbedding, Tevatron, ColBERT | — | — | **Removed.** §5.4 research answered key questions; deep-dive not warranted |
| — | FastRAG, MegaParse, ir-datasets, BEIR toolkit | — | — | **Removed.** FastRAG archived, MegaParse abandoned, others are reference-only |

**Why OpenSearch first:** Same Lucene foundation. Same FeatureField, same KNN.
Lowest friction from investigation to implementation.

**Why Anserini at #2:** The fake-words vs FeatureField comparison is the single
most architecturally interesting finding from §5.1 research. Deep-diving into
Anserini's SPLADE indexing code will answer the FeatureField precision question
more concretely than any other project.

### 4.4 What we're NOT doing

- Not evaluating whether to replace Lucene with any of these
- Not building integrations with external search engines
- Not adopting server-based architectures
- Not investigating projects that are less sophisticated than what we already have
  (GPT4All, AnythingLLM, PrivateGPT, Tantivy)
- Not benchmarking or prototyping during investigation sessions (see §4.2)

### 4.5 Output format

Each investigation session produces a **sub-document** linked from this tempdoc:
`docs/tempdocs/249-<project-name>-findings.md` (e.g.,
`249-opensearch-findings.md`). This keeps the plan document stable and avoids
bloating it with findings.

Each sub-document has the following structure:

```
---
title: "249: <Project> Investigation Findings"
type: tempdoc
status: active
created: <date>
parent: 249-open-source-investigation-plan.md
---

## Recon Summary
<Brief summary of the approved recon plan: what baseline was loaded, what
sources were targeted, anything skipped.>

## Findings
<One subsection per checklist item investigated. Each subsection contains:>

### <Pattern name> — e.g., "Score-normalized hybrid fusion"
- **JustSearch baseline** — how JustSearch currently handles this (from docs/source)
- **How it works** — concrete technical description of the external project's approach
- **Evidence** — benchmarks, papers, or source code references
- **Applicability** — direct / needs adaptation / not applicable
- **Adoption cost** — what files/modules in JustSearch would change
- **Expected impact** — quantified if possible (e.g., "+X% nDCG@10")

## Additional Findings
<Valuable discoveries outside the original checklist. Brief description of
each finding and why it's worth following up. These are leads, not full
investigations.>

## Unresolved Items
<Checklist items that could not be answered. For each:>
- <Item description> — <what would be needed to resolve it>

## Cross-Cutting Observations
<Which §7 key questions this project contributed evidence toward, and what
that evidence is. Do NOT attempt to fully answer §7 questions from a single
project — just note the contribution.>
```

### 4.6 Cross-cutting synthesis

The §7 key questions span multiple projects and cannot be answered from any
single investigation session. After all deep investigation sessions (§2) are
complete, a dedicated synthesis session will:

1. Read all findings sub-documents
2. Collate evidence for each §7 question across projects
3. Produce a summary document (`docs/tempdocs/249-synthesis.md`) with
   conclusions and recommended next steps for JustSearch

---

## 5. Additional Categories (not yet researched)

### 5.1 IR Research Toolkits

Academic IR toolkits — reference implementations with rigorous evaluation.

| Project | Stars | License | Tier | Key value for JustSearch |
|---------|-------|---------|------|------------------------|
| **Anserini** | 1.1k | Apache-2.0 | **Deep** | Same Lucene foundation; SPLADE "fake words" vs our FeatureField comparison |
| **Pyserini** | 2.0k | Apache-2.0 | **Selective** | HybridSearcher with convex combination fusion; min-max normalization |
| **PyTerrier** | 495 | MPL-2.0 | **Selective** | Declarative pipeline operators (`>>`, `+`, `%`); `pt.Experiment` with significance testing |
| **RankLLM** | 580 | Apache-2.0 | **Reference** | RankZephyr 7B listwise reranking; SIGIR 2025 paper |
| **BEIR toolkit** | 2.1k | Apache-2.0 | **Reference** | Uses `trec_eval` for nDCG — verify our implementation matches |
| **ir-datasets** | 381 | Apache-2.0 | **Reference** | Unified dataset loading if expanding beyond BEIR |
| **FastRAG** | 1.8k | Apache-2.0 | **Not relevant** | Archived project, Intel/Haystack-specific |

**Key findings:**

1. **SPLADE indexing: Anserini uses "fake words", JustSearch uses FeatureField.**
   Anserini repeats each term N times as text (quantized to integers), indexed in
   a standard TextField. JustSearch stores float weights directly in FeatureField.
   Lucene committer Adrien Grand (issue #11799) explicitly recommends FeatureField
   as the proper approach — our architecture is validated. However, FeatureField
   has an 8-bit mantissa limitation and a (0, 64] weight range we should verify
   SPLADE-v3 weights respect.

2. **Convex combination outperforms RRF for hybrid fusion.** Pyserini's
   `HybridSearcher` uses `alpha * sparse + dense` (linear interpolation), not RRF.
   Zhuang et al. (TOIS 2023) shows convex combination beats RRF in both in-domain
   and out-of-domain, is sample-efficient (~40 queries to tune), and doesn't lose
   score information like RRF does. Worth testing against our RRF.

3. **JustSearch's eval harness lacks statistical significance testing.** PyTerrier
   implements paired t-tests with Bonferroni/Holm-Sidak correction as standard.
   Without this, nDCG differences <0.01-0.02 are likely noise.

4. **Evaluation pitfalls documented across toolkits:**
   - Tie-breaking: `trec_eval` breaks ties by lexicographic doc ID
   - nDCG variants: `log2` vs `exp-log2` discounting produce different absolutes
   - SPLADE "wacky weights" (arxiv 2110.11540): extreme weights to stopwords
     cause 50x slowdowns; need 32-bit accumulators
   - Shallow BEIR judgments bias recall metrics

**Deep-dive files:**
- `anserini/src/.../collection/JsonVectorCollection.java` — fake-words expansion
- `anserini/src/.../search/similarity/ImpactSimilarity.java` — pure freq*boost scoring
- `pyserini/search/hybrid/_searcher.py` — convex combination with normalization
- `pyterrier/experiments.py` — significance testing framework
- Zhuang et al., "Analysis of Fusion Functions for Hybrid Retrieval" (TOIS 2023)

### 5.2 Document Processing Pipelines

Tools for extracting structure from documents. JustSearch uses Tika + ChunkSplitter
(500-tok, content-aware modes: MARKDOWN/CODE/CSV/JSON). No layout or table parsing.

| Project | Stars | License | Layout Model | Table Extraction | GPU Req? | Tier |
|---------|-------|---------|-------------|-----------------|----------|------|
| **Docling** (IBM) | 54.5k | MIT | RT-DETRv2 (Transformer) | TableFormer (93.6% acc) | No (CPU-first) | **Deep** |
| **MinerU** | 55.2k | AGPL-3.0 | DocLayout-YOLO (10x faster) | StructEqTable | No (GPU helps) | **Selective** |
| **Marker** | 32.0k | GPL-3.0 | Surya SegFormer/EfficientViT | Markdown tables + LLM | GPU needed | **Selective** |
| **Unstructured** | 14.1k | Apache-2.0 | Detectron2/YOLOX (CNN) | HTML tables | No | **Selective** |
| **Surya** | 19.4k | GPL-3.0 | Modified EfficientViT | JSON/MD/HTML | GPU preferred | **Reference** |
| **ChunkNorris** | 23 | Apache-2.0 | None (heuristic) | None | No | **Reference** |
| **MegaParse** | 7.3k | Apache-2.0 | Delegates to LLMs | LLM-based | Cloud LLM | **Not relevant** |

**Key findings:**

1. **Docling is the clear adoption path for JustSearch.** MIT license, CPU-feasible
   (~0.3 pg/sec x86), best-in-class table extraction (93.6% vs Tabula 67.9%),
   and critically: **has an official Java API** (`docling-java`) communicating via
   `docling-serve` REST sidecar. Also has `Granite-Docling-258M` — a tiny VLM
   (Apache 2.0) that handles layout+tables+equations in one forward pass on CPU.

2. **Docling's HybridChunker is the natural evolution of ChunkSplitter.** Stage 1:
   split by document structure (sections, paragraphs, tables). Stage 2: token-aware
   refinement (split oversized, merge undersized). Stage 3: semantic splitting for
   elements still too large. Same idea as JustSearch's approach but layout-aware.

3. **Semantic chunking underperforms or ties with fixed-token chunking for retrieval.**
   Vecta 2026 benchmark: recursive 512-token = 69% accuracy, semantic = 54%.
   NAACL 2025: fixed 200-word matched or beat semantic. ChunkNorris ACL 2025:
   heuristic chunking outperformed ML-based. JustSearch's current approach is
   competitive; structure-aware improvements (headings, tables) are better than
   embedding-similarity chunking.

4. **Marker/Surya/MinerU have licensing problems.** GPL-3.0 (Marker, Surya) and
   AGPL-3.0 (MinerU) create copyleft obligations for a desktop app. Marker is also
   very slow on CPU (16 sec/page). MegaParse is abandoned (last commit Feb 2025).

**Recommended adoption path:** Ship `docling-serve` as optional PDF enhancer
sidecar. Java communicates via `docling-java`. When available: layout-aware parsing,
TableFormer table extraction, structured chunking. When not: fall back to Tika.

**Deep-dive files:**
- `docling-project/docling-core` — `chunking/` (HierarchicalChunker, HybridChunker)
- `docling-project/docling-serve` — REST API server
- `docling-project/docling-java` — Java client
- `ibm-granite/granite-docling-258M` on HuggingFace
- `unstructured/chunking/` — by_title strategy (section-boundary-aware chunking)
- ChunkNorris ACL 2025 paper: arxiv.org/abs/2602.00010

### 5.3 LLM Inference Engines

JustSearch uses llama.cpp via FFM. Goal: learn techniques from other engines,
not replace llama.cpp. Critical baseline: llama.cpp already has more than
JustSearch wires (see table below).

**Already in llama.cpp but NOT wired in JustSearch:**

| Feature | llama.cpp API | JustSearch status | Priority |
|---------|--------------|-------------------|----------|
| **GBNF grammar / JSON schema** | `llama_sampler_init_grammar()` | Not wired; using regex `JsonGrammarGuard` | **High** |
| **Flash Attention** | `--flash-attn` flag | Unknown if enabled | **High** |
| **KV cache quantization** | `--cache-type-k Q8_0` | Not wired | **Medium** |
| **Speculative decoding** | `--model-draft` flag | Not wired | Low (small benefit for 7-8B) |
| **Batched inference** | `llama_batch` API | Not wired (single-request) | Low (single-user) |

**Already in llama.cpp AND already in JustSearch:**
- Prefix caching (`PrefixCache.java` via `llama_kv_cache_seq_cp`)
- KV cache shifting (`KvCacheAdapter.shiftSequence()` via `llama_kv_cache_seq_add`)

| Project | Tier | Key technique | Applicable? |
|---------|------|--------------|-------------|
| **vLLM** | **Reference** | PagedAttention (KV paging) | No — multi-user only |
| **SGLang** | **Selective** | RadixAttention (prefix cache tree) | Already have prefix caching; learn multi-turn extension |
| **ExLlamaV2** | **Reference** | EXL2 per-column mixed precision | No — different format, no CPU offload |
| **kobold.cpp** | **Selective** | Context shifting (KV eviction) | Already have `shiftSequence()`; learn eviction policy |

**Key findings:**

1. **Wiring GBNF grammar is the highest-value LLM improvement.** JustSearch's
   `JsonGrammarGuard` is a regex character filter, NOT schema enforcement. llama.cpp
   already has `llama_sampler_init_grammar()`. vLLM/SGLang use XGrammar (<40us/token).
   JustSearch just needs to expose the existing C API via FFM.

2. **PagedAttention is irrelevant for desktop.** 2-24x throughput comes from packing
   concurrent requests. Single-user = one KV cache = near-zero fragmentation.

3. **SGLang confirms JustSearch's prefix caching is correct.** `PrefixCache.java`
   already implements copy-on-write prefix sharing. SGLang adds caching generation
   outputs for multi-turn — relevant if JustSearch adds conversation-style interactions.

4. **Speculative decoding: low priority.** 1.2-1.5x speedup for 7-8B on RTX 4070.
   Draft model adds ~600-800MB VRAM. Revisit for 13B+ models.

5. **kobold.cpp's context shifting uses the same `llama_kv_cache_seq_add` that
   JustSearch already exposes** as `KvCacheAdapter.shiftSequence()`. The missing
   piece is the eviction policy (detect overflow → drop oldest → shift remaining).

**Deep-dive files:**
- `llama.cpp/grammars/README.md` — GBNF grammar spec
- `llama.cpp/include/llama.h` — search `llama_sampler_init_grammar`
- `llama.cpp/docs/speculative.md` — speculative decoding options
- SGLang paper: arxiv.org/abs/2312.07104 (RadixAttention)

### 5.4 Embedding/Reranking Model Training

Not training our own models — understanding internals, failure modes, and evaluation
methodology for the models we use.

| Project | Stars | License | Tier | Key value |
|---------|-------|---------|------|-----------|
| **SentenceTransformers** | 18k+ | Apache-2.0 | **Deep** | Loss functions, failure modes, MTEB eval methodology |
| **FlagEmbedding** (BGE) | 10.9k | MIT | **Selective** | Self-knowledge distillation, hard negative mining |
| **ColBERT** | 3.7k | MIT | **Reference** | PLAID index storage, MaxSim scoring |
| **Tevatron** | 712 | Apache-2.0 | **Reference** | Dense retrieval training patterns |

**Key findings:**

1. **GPL-trained LambdaMART's failure is a documented anti-pattern.** Training on
   only synthetic hard negatives without mixing easier negatives causes rerankers to
   become overly strict. SentenceTransformers v4 documents this: mix hard negatives
   (60-70%) with random negatives (30-40%). JustSearch's "LambdaMART consistently
   hurts" finding matches this exactly.

2. **256-dim Matryoshka embeddings are viable.** nomic-embed-text-v1.5 supports
   truncation to 256 dims with only -1.24 MTEB points loss. 3x KNN speed, 67%
   vector storage reduction. Requires `layer_norm → truncate → L2 normalize`
   post-processing. Needs BEIR eval before shipping.

3. **Known embedding failure modes:**
   - Q4 quantization destroys instruction sensitivity (arxiv 2601.14277: up to 20%
     IFEval loss). Already measured in JustSearch: +0.025 nDCG Q4→Q8.
   - Short query asymmetry: 3-5 token queries compress into same 768-dim space as
     2000-token documents. SPLADE + BM25 partially compensate.
   - d=768 theoretical capacity: ~500K-4M documents before retrieval quality
     degrades. Safe for desktop (<1M docs).
   - Cosine similarity saturation: many embeddings cluster narrowly, making top-K
     discrimination hard. Cross-encoder reranking recovers this.

4. **BGE-M3's self-knowledge distillation is relevant for hybrid fusion design.**
   During training, dense + sparse + multi-vector scores are ensembled as a teacher,
   and each mode is trained to match the ensemble. This mutual reinforcement is the
   training-time equivalent of JustSearch's runtime RRF fusion.

5. **ColBERT storage is impractical for desktop.** 300-token document = ~10.5 KB
   per document (2-bit residual compression). 10-50x larger than single-vector.
   Cross-encoder reranking of top-K is the right desktop architecture.

6. **A small domain-fine-tuned cross-encoder outperforms large general-purpose ones.**
   SentenceTransformers v4: 150M fine-tuned > 278M general-purpose. If JustSearch
   ever trains a custom reranker, the eval data already exists.

**Deep-dive files:**
- [sbert.net/docs/loss_overview.html](https://sbert.net/docs/sentence_transformer/loss_overview.html)
- [HuggingFace blog: train-reranker](https://huggingface.co/blog/train-reranker) (v4 cross-encoder training)
- BGE M3-Embedding paper: arxiv.org/abs/2402.03216 (self-knowledge distillation)
- [nomic-embed-text-v1.5 HuggingFace card](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5) (Matryoshka dims)
- arxiv.org/abs/2508.21038 (theoretical embedding capacity limits)

---

## 7. Key Questions to Answer

Cross-cutting questions the investigation should resolve. Evidence from §5 research
is noted where available.

1. **Is Lucene's FeatureField 8-bit mantissa a real quality bottleneck for SPLADE?**
   OpenSearch operates at scale with the same limitation — do they work around it?
   What precision do Vespa and Infinity use?
   *§5.1 evidence:* Anserini avoids FeatureField entirely (uses "fake words" trick
   instead). Lucene committer recommends FeatureField but acknowledges the precision
   limit. FeatureField weight range is (0, 64]. SPLADE "wacky weights" (arxiv
   2110.11540) can exceed this — verify SPLADE-v3 weights stay in range.

2. **What's the best score normalization for hybrid fusion?**
   RRF vs min-max vs z-score vs learned weights.
   *§5.1 evidence:* Pyserini uses convex combination with per-query min-max
   normalization, NOT RRF. Zhuang et al. (TOIS 2023) shows convex combination
   outperforms RRF in both in-domain and out-of-domain, is sample-efficient (~40
   queries), and preserves score information that RRF discards.

3. **Is relevance feedback (Qdrant-style) feasible with Lucene?**
   Can we implement "more like this / less like this" using Lucene's MoreLikeThis
   query + KNN vector proximity? What does the UX look like?

4. **Does ColBERT late-interaction reranking add value over cross-encoder?**
   *§5.4 evidence:* Cross-encoders still beat ColBERT by ~5-10 nDCG. ColBERT
   storage is 10-50x larger than single-vector. Not practical for desktop.
   JustSearch's BM25+dense+SPLADE→cross-encoder architecture is correct.

5. **Can LLM query expansion improve recall without unacceptable latency?**
   qmd does this. What's the measured recall improvement vs the latency cost
   of an LLM call before retrieval?

6. **What filtered ANN strategy works best at desktop scale (100K-1M docs)?**
   Milvus and Qdrant have different approaches. Lucene's approach (pre-filter
   then KNN) may be suboptimal for narrow filters.

7. **Should JustSearch wire llama.cpp's native GBNF grammar?** *(new from §5.3)*
   Current `JsonGrammarGuard` is a regex character filter, not schema enforcement.
   llama.cpp has `llama_sampler_init_grammar()` — exposing it via FFM would give
   guaranteed-valid JSON output with near-zero overhead.

8. **Is 256-dim Matryoshka embedding viable for JustSearch?** *(new from §5.4)*
   nomic-embed-text-v1.5 supports truncation to 256 dims (-1.24 MTEB). 3x KNN
   speed, 67% storage reduction. Needs BEIR eval to measure retrieval-specific
   impact before shipping.

9. **Why does GPL-trained LambdaMART hurt quality?** *(answered by §5.4)*
   Training on only synthetic hard negatives without mixing easier negatives causes
   rerankers to become overly strict — a documented anti-pattern in
   SentenceTransformers v4. Mix 60-70% hard + 30-40% random negatives.

---

## 8. Sources

### Deep investigation targets
- [Vespa — GitHub](https://github.com/vespa-engine/vespa) (6.8k stars, Apache 2.0)
- [Vespa — Hybrid Search Blog](https://blog.vespa.ai/redefining-hybrid-search-possibilities-with-vespa/)
- [OpenSearch — GitHub](https://github.com/opensearch-project/OpenSearch) (Apache 2.0)
- [OpenSearch — Neural Sparse Docs](https://docs.opensearch.org/latest/vector-search/ai-search/neural-sparse-search/)
- [OpenSearch — Search Pipelines](https://docs.opensearch.org/latest/search-plugins/search-pipelines/index/)
- [Infinity — GitHub](https://github.com/infiniflow/infinity) (4.4k stars, Apache 2.0)
- [Infinity — Hybrid Search Blog](https://infiniflow.org/blog/best-hybrid-search-solution)

### Selective investigation targets
- [Qdrant — GitHub](https://github.com/qdrant/qdrant) (29.2k stars, Apache 2.0)
- [Qdrant — Sparse Retrieval Article](https://qdrant.tech/articles/modern-sparse-neural-retrieval/)
- [Milvus — GitHub](https://github.com/milvus-io/milvus) (43.1k stars, Apache 2.0)
- [Milvus — Hybrid Search Docs](https://milvus.io/docs/hybrid-search.md)
- [RAGFlow — GitHub](https://github.com/infiniflow/ragflow) (74k stars, Apache 2.0)
- [qmd — GitHub](https://github.com/tobi/qmd) (11.2k stars, MIT)

### IR research toolkits (§5.1)
- [Anserini — GitHub](https://github.com/castorini/anserini) (1.1k stars, Apache 2.0)
- [Pyserini — GitHub](https://github.com/castorini/pyserini) (2.0k stars, Apache 2.0)
- [PyTerrier — GitHub](https://github.com/terrier-org/pyterrier) (495 stars, MPL-2.0)
- [RankLLM — GitHub](https://github.com/castorini/rank_llm) (580 stars, Apache 2.0)
- Zhuang et al., "Analysis of Fusion Functions for Hybrid Retrieval" (TOIS 2023)
- SPLADE "wacky weights": arxiv.org/abs/2110.11540

### Document processing (§5.2)
- [Docling — GitHub](https://github.com/docling-project/docling) (54.5k stars, MIT)
- [Docling Java — GitHub](https://github.com/docling-project/docling-java)
- [Granite-Docling-258M](https://huggingface.co/ibm-granite/granite-docling-258M) (Apache 2.0)
- [MinerU — GitHub](https://github.com/opendatalab/MinerU) (55.2k stars, AGPL-3.0)
- [Marker — GitHub](https://github.com/datalab-to/marker) (32.0k stars, GPL-3.0)
- [Unstructured — GitHub](https://github.com/Unstructured-IO/unstructured) (14.1k stars, Apache 2.0)
- ChunkNorris ACL 2025: arxiv.org/abs/2602.00010

### LLM inference (§5.3)
- [llama.cpp Grammars](https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md)
- [llama.cpp Speculative Decoding](https://github.com/ggml-org/llama.cpp/blob/master/docs/speculative.md)
- SGLang RadixAttention: arxiv.org/abs/2312.07104
- PagedAttention: arxiv.org/abs/2309.06180

### Model training (§5.4)
- [SentenceTransformers — GitHub](https://github.com/huggingface/sentence-transformers) (18k+, Apache 2.0)
- [FlagEmbedding — GitHub](https://github.com/FlagOpen/FlagEmbedding) (10.9k stars, MIT)
- [ColBERT — GitHub](https://github.com/stanford-futuredata/ColBERT) (3.7k stars, MIT)
- BGE M3-Embedding: arxiv.org/abs/2402.03216
- Matryoshka RL: arxiv.org/abs/2205.13147
- Embedding capacity limits: arxiv.org/abs/2508.21038
- [nomic-embed-text-v1.5](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5)

### Reference projects
- [Haystack — GitHub](https://github.com/deepset-ai/haystack) (24.4k stars, Apache 2.0)
- [LlamaIndex — GitHub](https://github.com/run-llama/llama_index) (47.3k stars, MIT)
- [Onyx — GitHub](https://github.com/onyx-dot-app/onyx) (17.6k stars, MIT)

### Context (less relevant but surveyed)
- [GPT4All — GitHub](https://github.com/nomic-ai/gpt4all) (77.2k stars, MIT)
- [AnythingLLM — GitHub](https://github.com/Mintplex-Labs/anything-llm) (55.2k stars, MIT)
- [PrivateGPT — GitHub](https://github.com/zylon-ai/private-gpt) (57.1k stars, Apache 2.0)
- [Tantivy — GitHub](https://github.com/quickwit-oss/tantivy) (14.6k stars, MIT)
- [Lucene FeatureField precision discussion](https://www.mail-archive.com/dev@lucene.apache.org/msg317809.html)

---

## Staleness review (2026-05-18)

This review applies to all eleven constituent documents. Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 77 days at audit time.

---

## anserini-findings

*(from `249-anserini-findings.md`)*

### 249: Anserini Investigation Findings

## Recon Summary

**JustSearch baseline sources consulted:**
- `docs/explanation/18-adapters-lucene-deep-dive.md` (module structure, hybrid fusion)
- `docs/tempdocs/245-search-quality-strategy.md` (eval results: SPLADE+Dense RRF = 0.704 nDCG@10 SciFact)
- `FieldMapper.java:249-258` (SPLADE indexed via `FeatureField(fieldId, token, weight)`)
- `LuceneIndexRuntime.java:1520-1546` (SPLADE searched via `FeatureField.newLinearQuery`)
- `HybridFusionUtils.java:48-188` (RRF fusion: K=60, vectorWeight=0.75, no normalization)
- `SpladeEncoder.java:254-296` (SPLADE activation: `max_t(log1p(ReLU(logits)))`)

**External sources read:**
- Anserini: `JsonVectorCollection.java`, `ImpactSimilarity.java`, `BagOfWordsQueryGenerator.java`,
  `DefaultLuceneDocumentGenerator.java`, `SimpleImpactSearcher.java`, `RunsFuser.java`,
  `ScoredDocsFuser.java`, BEIR regression YAMLs (SciFact/ArguAna/NFCorpus)
- Pyserini: `search/hybrid/_searcher.py` (HybridSearcher)
- Lucene: `FeatureField.java`, `FeatureQuery.java` (encoding/decoding internals)
- arXiv 2110.11540 (SPLADE "wacky weights")

---

## Findings

### 1. Fake-words vs FeatureField — SPLADE indexing precision

**JustSearch baseline:** Stores SPLADE float weights directly in Lucene `FeatureField`.
Each token-weight pair becomes `new FeatureField("splade", token, weight)`. At search time,
`FeatureField.newLinearQuery("splade", token, weight)` produces `score = queryWeight * decodedFeatureValue`.
FeatureField encodes floats by right-shifting the IEEE 754 bit representation by 15 bits
(`floatBits >>> 15`), preserving sign + 8 exponent bits + 8 mantissa bits. The bottom 15
mantissa bits are discarded, giving ~0.39% relative precision.

**How Anserini does it:** Converts SPLADE float weights to integers *before* indexing (in
the Python preprocessing pipeline, not in Anserini itself). `JsonVectorCollection.java`
reads `e.getValue().asInt()` and repeats each token N times as text in a standard
`TextField`. At search time, `ImpactSimilarity` scores `freq * boost` where `freq` is
the integer TF (document weight) and `boost` is the query weight via `BoostQuery`. No
IDF, no length normalization — both explicitly return 1.0.

The `DefaultLuceneDocumentGenerator` indexes with `DOCS_AND_FREQS` (no positions), and
`ImpactSimilarity.computeNorm()` returns constant 1 (norms stored but irrelevant).

**Evidence:**
- Anserini `ImpactSimilarity.java`: `score(float freq, long norm) { return freq * boost; }`
- Lucene `FeatureField.java`: `int freqBits = Float.floatToIntBits(featureValue); stream.setValues(fieldsData, freqBits >>> 15);`
- Lucene `FeatureField` javadoc: "values are stored with a relative precision of 2^-8 = 0.00390625"
- Lucene committer Adrien Grand (issue #11799): recommends FeatureField as the proper approach
  for learned sparse weights

**Precision comparison:**

| Approach | Encoding | Precision loss | Weight range |
|----------|----------|---------------|--------------|
| JustSearch (FeatureField) | float → 17-bit int via `>>> 15` | ~0.39% relative (8-bit mantissa) | (0, 64] for query weights; >= Float.MIN_NORMAL for feature values |
| Anserini (fake words) | float → int (rounding in Python preprocessing) | Up to 50% for small values (e.g., 0.7 → 1); ~0.37% at scale 100x | Unbounded integers (limited by Lucene max TF) |

Both approaches compute the same mathematical operation (sparse dot product), but via
different mechanisms. FeatureField preserves relative precision uniformly across the value
range. Anserini's integer approach has absolute precision loss that hurts small weights
disproportionately, but avoids any precision issue for large integer weights.

**Applicability:** Not applicable as a change — JustSearch's FeatureField approach is
validated as correct and arguably more precise than Anserini's fake-words for float-valued
SPLADE output. No change needed.

**Adoption cost:** N/A

**Expected impact:** N/A — confirms current approach is sound.

---

### 2. SPLADE weight ranges and FeatureField limits

**JustSearch baseline:** `SpladeEncoder.postProcess()` computes `max_t(log1p(ReLU(logits)))`
per vocabulary position. The `log1p` transform bounds the output: even a logit of 10,000
produces `log1p(10000) ≈ 9.21`. Typical SPLADE-v3 weights range from 0 to approximately
5-8 for high-weight terms. No clamping is applied — the only filtering is skipping special
tokens ([PAD], [UNK], [CLS], [SEP], [MASK]) and zero-weight terms.

FeatureField's query weight range is (0, 64] and feature values must be >= `Float.MIN_NORMAL`
(~1.175e-38). Both constraints are trivially satisfied by SPLADE-v3's float output.

**How Anserini handles weight ranges:** Anserini sidesteps the question entirely — integer
weights in JSON have no upper bound limit in the standard TextField. The wacky weights
paper (arXiv 2110.11540) documents SPLADEv2 integer weights up to 251 (e.g., `##rogen: 251`,
`receptor: 242`). These are quantized integers from the preprocessing pipeline, not raw
float outputs.

**Evidence:**
- JustSearch `SpladeEncoder.java:277-280`: `float relu = Math.max(0.0f, seqLogits[t][v]); float activated = (float) Math.log1p(relu);` — log1p caps output growth
- arXiv 2110.11540: SPLADEv2 integer weights reach 251; comma token gets weight 68
  ("non-sensical"). Paper predates SPLADE-v3 which adds stronger FLOPS regularization
- FeatureField: `MAX_WEIGHT = 64` (for query weights); feature values have no explicit
  upper bound but must be positive normal floats

**Risk assessment:** SPLADE-v3 float weights (0-8 range) are well within FeatureField's
constraints. The "wacky weights" problem from SPLADEv2 manifests as large *integers* in
Anserini's encoding, not large *floats* in JustSearch's. The log1p transform in
JustSearch's encoder provides a natural ceiling. **No risk of exceeding FeatureField
limits.**

**Applicability:** Not applicable — no change needed.

**Expected impact:** Confirms no precision bottleneck exists in the current implementation.

---

### 3. Score normalization and hybrid fusion

**JustSearch baseline:** Online RRF fusion in `HybridFusionUtils.fuseWithRRF()`. Each
result leg is rank-converted: `score = 1/(K + rank)` with K=60. Vector leg is weighted by
`vectorWeight` (default 0.75). No score normalization — raw Lucene scores from each leg
are discarded; only rank order matters. Fusion happens at search time with parallel
execution of text and vector legs.

**How Anserini/Pyserini does it:**

*Anserini* fusion is **offline only** — `RunsFuser.java` and `ScoredDocsFuser.java` operate
on TREC-format run files, not live search results. Available methods: RRF (k=60 default),
average, interpolation (2 runs), weighted sum. Min-max normalization per topic is available
before fusion. There is no online hybrid search path in Anserini.

*Pyserini* `HybridSearcher` implements a weighted linear sum:
```
score = alpha * sparse_score + dense_score     (default)
score = sparse_score + alpha * dense_score     (weight_on_dense=True)
```
This is **not** a true convex combination (coefficients don't sum to 1). Optional per-result-set
min-max normalization maps scores to [-0.5, +0.5]:
```
normalized = (score - (min + max) / 2) / (max - min)
```
Alpha defaults to 0.1 (heavily favoring the unweighted leg). No built-in tuning methodology.

**Evidence:**
- Pyserini `_searcher.py`: min-max normalization centers at midpoint, maps to [-0.5, +0.5]
- Bruch, Gai, Ingber, "An Analysis of Fusion Functions for Hybrid Retrieval" (ACM TOIS
  2024, arXiv:2210.11934) — **note: previously cited as "Zhuang et al." in tempdoc 245
  and this investigation; corrected here.** Key findings:
  - CC ("TM2C2") outperforms RRF on **all 9 datasets tested** (MS MARCO + 8 BEIR)
  - MS MARCO in-domain: CC 0.454 vs RRF 0.425 nDCG@1000 (+0.029 absolute, +6.8% relative)
  - Per-dataset BEIR numbers not extractable from paper (PDF-only tables)
  - Normalization choice is irrelevant — proven theoretically that any linear transform
    produces rank-equivalent results with appropriate alpha
  - Alpha requires only "a handful" of labeled queries to converge (~10-50)
  - RRF's k parameter generalizes poorly out-of-domain; CC's alpha does not
  - Paper tests BM25+MiniLM (main), SPLADE+BM25, BM25+TAS-B, SPLADE+TAS-B (appendices)
  - **Metric: nDCG@1000, not @10** — gains at @10 may differ
- JustSearch `HybridFusionUtils.java:57-60`: RRF constant K configurable from `resolvedConfig`
- Anserini `RunsFuser.java`: offline-only fusion on TREC run files

**Applicability:** Needs adaptation. The Pyserini convex combination formula is directly
implementable in JustSearch's `HybridFusionUtils`, but requires:
1. Score normalization before combining (min-max per-query, as Pyserini does — though
   Bruch et al. prove normalization choice is irrelevant with appropriate alpha)
2. Alpha calibration (grid search over a handful of queries per Bruch et al.)
3. JustSearch's standalone SPLADE eval mode (tempdoc 245 item 2a) to produce calibration
   scores for the sparse leg independently

**Adoption cost:** Moderate — 2-3 files changed:
- `HybridFusionUtils.java`: add `fuseWithConvexCombination()` alongside existing `fuseWithRRF()`
- `HybridSearchOps.java`: wire fusion strategy selection (config-driven)
- Configuration: add `hybrid_search.fusion_strategy` (rrf | cc) and `hybrid_search.cc_alpha`

**Expected impact:** +0.029 nDCG@1000 on MS MARCO (the only confirmed per-dataset delta).
At nDCG@10 the gain may be smaller or larger. CC wins on all 9 datasets tested. The gain
is largest when one leg is significantly stronger than the other (which is JustSearch's
situation — SPLADE dominates on SciFact). RRF's rank-only fusion discards score magnitude
information that convex combination preserves.

---

### 4. Published SPLADE-v3 baselines

**JustSearch baseline:** Measured on 3 BEIR datasets with SPLADE+Dense RRF hybrid.

**Anserini published SPLADE-v3 (standalone, no dense fusion):**

| Dataset | Anserini SPLADE-v3 | JustSearch SPLADE+Dense RRF | Delta | Notes |
|---------|-------------------|---------------------------|-------|-------|
| SciFact | **0.714** | 0.704 | -0.010 | JustSearch hybrid is 98.6% of standalone SPLADE-v3 |
| ArguAna | **0.487** | 0.352 | -0.135 | Large gap — SPLADE underperforms on argument text in JustSearch |
| NFCorpus | **0.363** | 0.333 | -0.030 | JustSearch at 91.7% of standalone SPLADE-v3 |

**Anserini published BGE-base-en-v1.5 HNSW (for reference):**

| Dataset | BGE-base HNSW | SPLADE-v3 | Delta |
|---------|--------------|-----------|-------|
| SciFact | 0.741 | 0.714 | Dense +0.027 |
| ArguAna | 0.636 | 0.487 | Dense +0.149 |
| NFCorpus | 0.374 | 0.363 | Dense +0.011 |

**Evidence:** Anserini BEIR regression YAMLs (`beir-v1.0.0-{scifact,arguana,nfcorpus}.splade-v3.cached.yaml`).
Anserini has **no hybrid regression YAMLs** — SPLADE and dense are always evaluated separately.
Hybrid evaluation is done via Pyserini's `HybridSearcher` API, not Anserini regressions.

**Key interpretation:** The SciFact gap (-0.010) is small and attributable to JustSearch's
FeatureField encoding precision + Q8_0 embedding quantization in the dense leg. The ArguAna
gap (-0.135) is large and consistent with the known pattern that SPLADE underperforms on
argument-style text (both Anserini standalone and JustSearch hybrid show this). The gap
cannot be explained by FeatureField precision alone — it likely reflects a combination of
Q8_0 dense embedding quantization and RRF's suboptimal fusion on datasets where one leg
strongly dominates.

**Applicability:** Direct — these numbers serve as calibration targets for JustSearch.

---

### 5. Anserini's evaluation methodology

**JustSearch baseline:** Custom eval harness in PowerShell scripts; nDCG@10 computed
by the eval script; no automated regression validation.

**How Anserini does it:** Automated regression framework with YAML-defined expected results.
Each regression YAML specifies exact expected metric values (e.g., `nDCG@10: [0.7140]`).
CI runs search, computes metrics via `trec_eval`, and fails if results don't match expected
values within tolerance. Regressions cover all BEIR datasets × all model variants.

**Evidence:** Anserini regression YAML structure includes `models[].results.nDCG@10` with
expected values that CI validates on every commit. The `--removeQuery` flag strips query
terms from results (standard BEIR practice). Uses `trec_eval` with log2 discounting.

**Applicability:** Needs adaptation. JustSearch could adopt the pattern of expected-result
validation in its eval harness (fail if nDCG deviates from recorded baseline by more than
a tolerance). This would catch regressions from code changes.

**Adoption cost:** Low — add expected-value assertions to the eval PowerShell scripts.

**Expected impact:** Prevents silent quality regressions. Does not improve quality directly.

---

### 6. nDCG computation variant mismatch

**JustSearch baseline:** `beir-eval-win.ps1:463-474` computes DCG with the **exponential**
gain formula: `gain = 2^rel - 1`. This is the variant from Burges et al. (2005).

**How Anserini/trec_eval does it:** `trec_eval` uses the **standard** gain formula:
`gain = rel`. The raw relevance value enters the DCG denominator directly, without
exponentiation. Anserini invokes `trec_eval -m ndcg_cut.10` with no variant flags.

**Impact on comparisons:**
- For **binary relevance** (rel ∈ {0, 1}): both variants produce **identical** results,
  because `2^0 - 1 = 0` and `2^1 - 1 = 1`. SciFact and ArguAna use binary relevance,
  so all JustSearch comparisons on these datasets are valid.
- For **graded relevance** (rel ∈ {0, 1, 2}): the exponential variant inflates gains
  for rel=2 documents (`gain = 3` vs `gain = 2`). **NFCorpus uses graded relevance**,
  so JustSearch's NFCorpus nDCG values are not directly comparable to Anserini's.

**Evidence:**
- JustSearch `beir-eval-win.ps1:469`: `$gain = ([Math]::Pow(2.0, $rel) - 1.0)`
- `trec_eval` `m_ndcg_cut.c`: `gain = rel_level` (raw relevance, no exponentiation)
- SciFact qrels: binary (0/1). ArguAna qrels: binary (0/1). NFCorpus qrels: graded (0/1/2).

**Applicability:** Direct fix needed for NFCorpus comparisons. Either switch JustSearch's
eval to standard DCG (matching trec_eval), or document the variant difference and apply
a correction factor when comparing.

**Adoption cost:** Trivial — change one line in `Compute-Dcg`: `$gain = [double]$rel`
instead of `$gain = ([Math]::Pow(2.0, $rel) - 1.0)`. However, this changes all
historical nDCG values for graded-relevance datasets.

**Expected impact:** NFCorpus nDCG values will decrease slightly when switching to
standard DCG. SciFact and ArguAna values are unaffected. Enables apples-to-apples
comparison with all published BEIR numbers (which universally use trec_eval).

---

## Additional Findings

### A1. FeatureField enables WAND/block-max dynamic pruning for SPLADE

FeatureField stores weights as term frequencies, which means Lucene's WAND (Weak-AND)
and block-max optimization applies to SPLADE queries automatically. This is the same
optimization that makes BM25 fast — it prunes documents that cannot beat the current
top-K threshold without fully scoring them. Anserini's fake-words approach gets the same
WAND benefit since it also uses standard TF. Both approaches benefit equally from
Lucene's dynamic pruning infrastructure.

This is worth noting because some external SPLADE implementations (e.g., on custom
inverted indexes) must implement their own pruning. JustSearch gets it for free.

### A2. Anserini's query-side weight handling via BoostQuery vs FeatureField's built-in weight

Anserini applies query weights via `BoostQuery(TermQuery(term), weight)`, which wraps
the entire term query with a multiplicative boost. JustSearch passes the query weight
directly to `FeatureField.newLinearQuery(field, term, weight)`, which incorporates it
into the scoring function internally. Both achieve `score = docWeight * queryWeight`,
but FeatureField's approach is cleaner — the weight is part of the query semantics
rather than a post-hoc boost wrapper.

### A3. Anserini treats SPLADE as pre-computed, JustSearch encodes at index+query time

Anserini reads pre-computed SPLADE vectors from JSON files (generated by a separate
Python pipeline). JustSearch runs SPLADE encoding live at both index time
(`SpladeEncoder.encode()` during document ingestion) and query time (encoding the
query string with the same ONNX model). This means:
- JustSearch can re-encode with a better model without re-exporting JSON files
- JustSearch's SPLADE quality is tied to the ONNX runtime + model file it ships
- Anserini can swap SPLADE models by pre-computing new JSON exports

### A4. SPLADE query latency risk from wacky weights is lower in JustSearch

The wacky weights paper (arXiv 2110.11540) reports 50x slowdown in Lucene from extreme
SPLADEv2 weights. JustSearch uses SPLADE-v3 which has stronger FLOPS regularization,
AND the log1p transform naturally compresses weight magnitudes. A SPLADEv2 integer weight
of 251 (Anserini-style) corresponds to a JustSearch float weight of ~5.5 (`log1p(250) ≈ 5.52`).
In FeatureField terms, this produces far fewer postings to score than 251 fake-word
repetitions, because FeatureField stores a single posting per (doc, term) regardless of
weight magnitude. The wacky weights latency problem is **structurally eliminated** by
FeatureField — postings list length is always 1 per (doc, term), unlike Anserini where
posting length equals the integer weight.

### A5. Bruch et al. citation correction and metric mismatch

The fusion paper previously cited as "Zhuang et al. (TOIS 2023)" throughout tempdoc 245
and 249 is actually **Bruch, Gai, Ingber (ACM TOIS 2024)**, arXiv:2210.11934,
DOI:10.1145/3596512. The paper reports **nDCG@1000** (and Recall@1000), not nDCG@10.
JustSearch evaluates at @10. The CC vs RRF delta at @10 may differ from the reported
+0.029 at @1000. This should be noted when citing the expected impact of switching
fusion strategies.

### A6. Eval methodology gaps are minor for current datasets

Two eval methodology differences between JustSearch and Anserini/trec_eval were verified:
- **Self-match exclusion:** Anserini's `--removeQuery` flag prevents documents from appearing
  in their own query results. JustSearch's eval script has no equivalent. However, this is
  irrelevant for the 3 evaluated datasets (SciFact, ArguAna, NFCorpus) because queries and
  documents are different text types in all three. Would matter for Quora or FEVER.
- **Tie-breaking:** Lucene breaks ties by internal doc ID (lower first); trec_eval breaks
  by lexicographic doc ID. Irrelevant in practice — neural retrieval with float scores
  produces essentially zero exact ties.

### A7. Pyserini quantization assumes weight_range=5

Pyserini's SPLADE encoder hardcodes `weight_range = 5` for the float→int conversion,
assuming SPLADE weights fall in [0, 5]. SPLADE-v3 with log1p activation can produce
weights up to ~8-9 for extreme logits, which would quantize to integers > 256. This
is not a problem for JustSearch (FeatureField handles any float in range), but means
Anserini's published SPLADE-v3 regressions may have slightly lossy quantization for
the highest-weight terms. This is a minor Anserini-specific limitation, not a JustSearch
concern.

---

## Unresolved Items

### U1. Exact SPLADE-v3 float weight distribution

The recon plan anticipated that SPLADE-v3 weight distributions might not be available from
Anserini's regressions. Confirmed: the regression YAMLs contain only evaluation metrics,
not weight statistics. Anserini's pre-computed JSON files (hosted on Hugging Face) contain
the actual integer weights but are too large to inspect remotely.

**To resolve:** Run JustSearch's `SpladeEncoder` on a BEIR dataset and histogram the output
float weights. This would establish the actual weight distribution and confirm no values
approach FeatureField's limits. Low urgency given the log1p analysis above.

### ~~U2. Anserini's integer quantization strategy~~ — RESOLVED

**Resolution:** Found in `pyserini/encode/_splade.py`, method
`_get_encoded_query_token_wight_dicts`:

```python
weight_range = 5       # assumes SPLADE weights fall in [0, 5]
quant_range = 256
weight_quanted = round(weight / weight_range * quant_range)
```

Formula: `round(weight / 5 * 256)`. Uses Python's banker's rounding. No clamping — values
above 5.0 produce integers > 256. Both document and query encoders use the same formula.
The "fake words" expansion then repeats each token `weight_quanted` times.

**Precision comparison (now complete):**

| Weight | JustSearch FeatureField | Pyserini fake-words | Winner |
|--------|----------------------|-------------------|--------|
| 0.1 | ~0.1 (0.39% error) | round(5.12) = 5 → 5/256*5 = 0.098 (2% error) | FeatureField |
| 1.0 | ~1.0 (0.39% error) | round(51.2) = 51 → 0.996 (0.4% error) | Tie |
| 3.5 | ~3.5 (0.39% error) | round(179.2) = 179 → 3.496 (0.1% error) | Tie |
| 5.0 | ~5.0 (0.39% error) | round(256) = 256 → 5.0 (exact) | Fake-words |
| 7.0 | ~7.0 (0.39% error) | round(358.4) = 358 → 6.992 (0.1% error) | Tie |

**Conclusion:** Both approaches have comparable precision for typical SPLADE weight ranges.
FeatureField is slightly better for very small weights (< 0.5) where integer quantization
loses more. Neither approach introduces meaningful scoring errors.

---

## Cross-Cutting Observations

### §7 Q1: Is FeatureField 8-bit mantissa a real quality bottleneck for SPLADE?

**Contribution: No.** SPLADE-v3 float weights (0-8 range after log1p) lose ~0.39% relative
precision in FeatureField encoding. JustSearch's SciFact gap vs Anserini is -0.010 nDCG —
but Anserini uses pre-computed integer weights that also lose precision (float→int rounding).
The precision difference between approaches is negligible compared to other factors
(embedding quantization Q4/Q8, fusion strategy, model differences).

Additionally, FeatureField has a structural advantage: one posting per (doc, term) regardless
of weight, which eliminates the SPLADE query latency problem that plagues Anserini's
fake-words approach (50x slowdown from wacky weights in Lucene per arXiv 2110.11540).

### §7 Q2: Best score normalization for hybrid fusion?

**Contribution: Strong.** Bruch et al. (TOIS 2024) proves theoretically that normalization
choice is irrelevant for convex combination — any linear transform produces rank-equivalent
results with appropriate alpha. In practice, min-max is used (Pyserini maps to [-0.5, +0.5]).
CC outperforms RRF on all 9 datasets tested (+0.029 nDCG@1000 on MS MARCO, the only
confirmed per-dataset delta). CC is sample-efficient (~handful of queries to tune alpha)
while RRF's k generalizes poorly out-of-domain. The remaining question for Vespa/Infinity
investigations is whether more sophisticated approaches (learned weights, adaptive per-query
fusion) add value beyond static CC.

### §7 Q9: Why does GPL-trained LambdaMART hurt quality?

**Contribution: Supporting.** Anserini's approach of evaluating SPLADE standalone (without
a trained combiner) avoids the overfitting problem entirely. Their SPLADE-v3 standalone
nDCG@10 of 0.714 on SciFact exceeds JustSearch's hybrid 0.704. This supports the finding
that a well-tuned SPLADE retriever + simple fusion (RRF or CC) outperforms a learned
combiner trained on synthetic data. The right path is better fusion strategy (CC), not
learned reranking with GPL data.

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.

---

## docling-findings

*(from `249-docling-findings.md`)*

## Recon Summary

**JustSearch baseline loaded from:**
- `modules/indexer-worker/.../extract/ContentExtractor.java` — Tika 3.2.3, default config, 10MB text / 100MB file / 30MB Office limits
- `modules/indexing/.../chunking/ChunkSplitter.java` — 500-tok target, 50-tok overlap, 2000-char threshold, 5 modes (DEFAULT/MARKDOWN/CODE/CSV/JSON), boundary priority: paragraph > sentence > word
- `modules/indexer-worker/.../extract/TextQualityAnalyzer.java` + `PdfImageRenderer` + `OcrProcessor` — VDU fallback for garbage PDFs (render 150 DPI PNG → OCR)
- `modules/indexing/.../chunking/ChunkDocumentWriter.java` — chunks as separate Lucene docs with parent_doc_id, char/line offsets, heading context
- No table/layout awareness exists in the current pipeline

**External sources targeted:**
- Docling technical paper (arxiv 2408.09869)
- Docling main repo (`docling/pipeline/`) — PDF conversion pipeline
- docling-core repo (`docling_core/transforms/chunker/`) — HierarchicalChunker, HybridChunker
- docling-java repo — Java client (`docling-serve-client/`), Java types (`docling-core/`), testcontainers
- docling-serve repo — REST API, deployment options
- Granite-Docling-258M HuggingFace model card

**Nothing skipped.** All four checklist items have accessible documentation.

---

## Findings

### 1. Layout-aware PDF parsing — RT-DETR + TableFormer

- **JustSearch baseline** — Tika 3.2.3 with default PDFBox parser extracts a flat text stream. No layout detection, no table structure. Garbage PDFs fall back to full-page OCR (150 DPI PNG → OCR), which also produces flat text. Tables become whitespace-separated cell values with no row/column delineation.
- **How it works** — Docling runs a five-stage per-page pipeline: (1) preprocess
  (PDF backend loads page, renders bitmap at 72 DPI, extracts programmatic text
  tokens with coordinates), (2) OCR (optional, for scanned pages), (3) layout
  detection (Heron/RT-DETRv2 with ResNet-50, 42.9M params, detects 17 element
  classes), (4) table structure recognition (TableFormer, only on regions the
  layout stage classified as Table), (5) assembly (reading order inference,
  figure-caption matching, language detection, metadata labeling). The output is
  a typed `DoclingDocument` that preserves hierarchy (body tree with child
  ordering = reading sequence), typed elements (`TextItem`, `SectionHeaderItem`,
  `TableItem`, `PictureItem`, `CodeItem`, `FormulaItem`), bounding boxes on
  every element, and full provenance (page number, bbox, char span).

  **Layout model (Heron):** RT-DETRv2 with ResNet-50 backbone, trained on
  150,000 documents (open + proprietary). Detects 17 classes: the original
  DocLayNet 11 (Caption, Footnote, Formula, List-item, Page-footer,
  Page-header, Picture, Section-header, Table, Text, Title) plus 6 additions
  (Document Index, Code, Checkbox-Selected, Checkbox-Unselected, Form,
  Key-Value Region). Heron-101 (ResNet-101 variant) achieves **78.0% mAP** on
  canonical DocLayNet, a +23.9% improvement over the prior RT-DETR model.
  Runtime: **0.028 sec/image on A100** (layout detection alone). Human
  inter-annotator agreement ceiling on DocLayNet is ~82-83% mAP, so the model
  is within ~5% of human performance.

  **TableFormer:** Vision-transformer with ResNet-18 backbone + structure
  decoder (2 encoder + 4 decoder layers) + DETR-inspired cell bbox decoder.
  Handles multi-column headers, row/column spans, empty cells, partial/missing
  borders. TEDS: **98.5% simple / 95.0% complex on PubTabNet**, **96.8%
  overall on FinTabNet**. Processing: **2-6 sec/table on CPU** (dependent on
  cell count). The table stage receives only table-classified bounding boxes
  from the layout model — it does not process the full page.

  **Contrast with Tika:** Tika produces a flat character stream with no
  structural annotation. Tables become whitespace-separated cell values. No
  bounding boxes, no element typing, no reading order beyond what PDFBox
  happens to produce. The DoclingDocument preserves all of this structure,
  making it available for downstream chunking and search indexing.

- **Evidence** — Docling technical report (arxiv 2408.09869): throughput
  benchmarks. Apple M3 Max 4 threads: 1.27 pages/sec (native backend, 6.2 GB
  RAM) or 2.18 pages/sec (pypdfium, 2.56 GB). Intel Xeon E5-2690 4 threads:
  0.60-0.94 pages/sec. GPU acceleration for the full pipeline was "work in
  progress" at time of the report. Heron layout model paper (arxiv
  2509.11720): mAP numbers. TableFormer paper (arxiv 2203.01017): TEDS
  numbers. DocLayNet paper (arxiv 2206.01062): 80,863 annotated pages across
  financial reports, scientific articles, laws, tenders, manuals, patents.

- **Applicability** — **Needs adaptation.** Docling runs as a Python process
  (docling-serve sidecar), not in-JVM. The layout + table pipeline is CPU-
  feasible (no GPU required for the default pipeline), but at 0.6-2.2
  pages/sec it is significantly slower than Tika's text extraction (~100+
  pages/sec). This makes it unsuitable as a mandatory step for all documents.
  The natural integration is as an **optional enhancer for PDFs** where Tika's
  flat extraction loses critical structure (tables, figures, complex layouts).
  The existing VDU fallback path (triggered by `TextQualityAnalyzer`) provides
  the architectural pattern: detect poor extraction quality, then route to a
  higher-quality (slower) pipeline.

- **Adoption cost** — Medium. JustSearch would need: (1) a new
  `DoclingExtractor` adapter in `modules/indexer-worker` that calls
  docling-serve via the Java client, (2) decision logic for when to route to
  Docling vs Tika (file type, extraction quality, user preference), (3)
  mapping from `DoclingDocument` structure (typed elements, table grids,
  heading hierarchy) to JustSearch's existing index schema (parent doc +
  chunks). The gRPC/REST boundary already exists (Head → Worker), so adding
  another HTTP call to a local sidecar is architecturally consistent.

- **Expected impact** — High for PDF-heavy corpora. Tables that are currently
  unsearchable (flat text with no structure) would become properly indexed with
  row/column awareness. Section headings would be accurately detected (not
  heuristic regex), improving chunk heading metadata quality. Figure captions
  would be linked to their figures. The quantitative impact depends on corpus
  composition — corpora dominated by tabular PDFs (financial reports, research
  papers) would see the largest gains.

### 2. HybridChunker — Structure-aware chunking

- **JustSearch baseline** — `ChunkSplitter` is a text-boundary chunker: it finds paragraph/sentence/word boundaries within a search window around the target chunk size. It has content-aware modes (MARKDOWN fenced-code awareness, CSV quote-tracking, JSON string-safety) but no document-structure awareness — it cannot chunk by section, table, or figure because Tika doesn't provide that structure. The `contextualize()` equivalent is limited to markdown heading extraction for the `chunk_heading_text` field.

- **How it works** — Docling's chunking is a two-layer system:

  **Layer 1 — HierarchicalChunker:** Depth-first traversal of the
  DoclingDocument tree. Each document element becomes one chunk: one paragraph
  = one chunk, one table = one chunk (serialized as triplets, not markdown
  pipes), one list group = one chunk (all list items merged). Heading items
  (`TitleItem`, `SectionHeaderItem`) are **not** emitted as chunks — they are
  captured in a `heading_by_level` dictionary and attached as metadata
  (breadcrumb stack) to subsequent content chunks. When a new heading is
  encountered, all headings at the same or deeper level are evicted. Captions
  on floating items (tables, pictures) are prepended to the item's text.

  **Layer 2 — HybridChunker:** Three-pass refinement on top of hierarchical
  chunks. Pass 1 (split by doc items): if a chunk contains multiple
  `DocItem` references (e.g., a merged list), split using a greedy sliding
  window that expands items until `max_tokens` is reached. Pass 2 (plain text
  split): chunks still over `max_tokens` are split using **semchunk** — a
  heuristic boundary finder that tries: largest newline sequence > tabs >
  punctuation-space > punctuation > character. This is approximate, not true
  sentence tokenization. Pass 3 (merge peers): adjacent chunks with identical
  heading breadcrumbs are merged greedily up to `max_tokens`.

  **Token counting:** All budget checks use `contextualize(chunk)`, which
  prepends heading breadcrumbs to the chunk text. The heading overhead is
  subtracted before calling the text splitter, so headings never silently
  steal token budget from content. Default tokenizer:
  `sentence-transformers/all-MiniLM-L6-v2` (256 max tokens). Can be
  overridden with any HuggingFace or tiktoken tokenizer.

  **Table serialization for chunking:** Uses `TripletTableSerializer` (not
  markdown pipes): `row_header, col_header = cell_value` joined with `. `.
  Example: `Alice, Age = 30. Alice, City = NYC. Bob, Age = 25.` This makes
  every cell self-contained for embedding — a key design choice. Tables are
  always one chunk at the hierarchical level; if they exceed `max_tokens`,
  pass 2 splits at `.` boundaries (which may split mid-triplet).

  **`contextualize()` output format:** Heading breadcrumbs joined by `\n`,
  then chunk text appended with `\n`. No labels like "Section:" or "##" —
  bare text. Example: `"Introduction\nMethods\n<chunk text>"`. Fields
  excluded from contextualization: `schema_name`, `version`, `doc_items`,
  `origin`.

- **Evidence** — Source code in `docling-core` repo:
  `docling_core/transforms/chunker/hierarchical_chunker.py`,
  `hybrid_chunker.py`, `base.py`. ChunkNorris ACL 2025 (arxiv 2602.00010)
  found heuristic chunking outperforms ML-based chunking for retrieval.
  Vecta 2026 benchmark: recursive 512-token = 69% accuracy, semantic = 54%.
  NAACL 2025: fixed 200-word matched or beat semantic chunking.

- **Applicability** — **Directly applicable** (with Docling sidecar) or
  **pattern-transferable** (without sidecar). Two adoption paths:

  1. **With docling-serve:** Use HybridChunker server-side via the
     `chunkSourceWithHybridChunker()` Java API. JustSearch receives
     pre-chunked content with heading metadata and table triplets. This
     replaces `ChunkSplitter` for documents processed through Docling.

  2. **Without docling-serve (pattern transfer):** Port specific ideas to
     `ChunkSplitter`: (a) the triplet table serialization pattern could be
     applied when Tika's output can be recognized as tabular, (b) the
     `contextualize()` breadcrumb approach validates and extends JustSearch's
     existing `chunk_heading_text`, (c) the three-pass split/merge strategy
     is more principled than ChunkSplitter's fixed search window but may
     not be worth the complexity without structural input from Docling.

  JustSearch's content-specific modes (CSV quote-tracking, JSON string-
  safety, MARKDOWN fenced-code awareness) have **no equivalent in Docling**.
  These are complementary — Docling is better for PDFs, ChunkSplitter is
  better for structured text formats. A hybrid approach would route PDFs
  through Docling and keep ChunkSplitter for markdown, code, CSV, and JSON.

- **Adoption cost** — Low (pattern transfer) to Medium (full integration).
  Pattern transfer: modify `ChunkSplitter` to support a "DOCLING" mode that
  consumes pre-structured chunks from the Docling Java client. Full
  integration: implement a `DoclingChunkWriter` that maps `DocChunk` objects
  to JustSearch's chunk Lucene documents, preserving the heading breadcrumbs,
  table triplet text, and provenance metadata.

- **Expected impact** — Moderate. The biggest win is **table chunking** —
  triplet serialization makes table cells individually searchable and
  embeddable, whereas JustSearch currently produces un-structured flat text
  for tables. The heading breadcrumb enrichment adds ~5-15 tokens of context
  per chunk that helps embedding models disambiguate sections. The merge-
  peers pass reduces chunk count for repetitive sections (lists, bullet
  points), improving index efficiency.

### 3. docling-java / docling-serve integration path

- **JustSearch baseline** — No external document processing sidecar. Tika runs in-process in the Worker JVM. Content extraction is synchronous with a 60-second timeout (`TimeboxedContentExtractor`). The VDU fallback (PDF rendering + OCR) runs in the Head process and communicates results back to Worker via gRPC.
- **How it works** — The docling-java ecosystem has four Maven artifacts under
  group `ai.docling` (current version: **0.4.7**, pre-1.0):

  1. **`docling-core`** — Java types mirroring the Python `DoclingDocument`.
     A single ~1340-line class with static inner types. Covers all element
     types (`TitleItem`, `SectionHeaderItem`, `TableItem`, `PictureItem`,
     `CodeItem`, `FormulaItem`, `ListItem`, `KeyValueItem`, `FormItem`),
     `ProvenanceItem` (page number, bounding box, char span), content layers
     (`BODY`, `FURNITURE`, etc.), and 22 `DocItemLabel` values. The model is
     **structurally faithful** to the Python schema — tables have full
     `TableData` with `grid: List<List<TableCell>>`, `num_rows`, `num_cols`.
     Minor gap: `table_cells` in `TableData` is typed as `List<Object>` (the
     typed access is only through `grid`).

  2. **`docling-serve-api`** — Framework-agnostic interfaces for conversion,
     chunking, health, and task management. Five API interfaces covering sync
     and async variants of conversion and chunking, plus health check and
     task polling.

  3. **`docling-serve-client`** — HTTP client using JDK `HttpClient` + Jackson.
     Supports both Jackson 2 and Jackson 3 (separate implementations). Forces
     HTTP/1.1 for `http://` URLs (FastAPI HTTP/2 upgrade workaround). Error
     handling: HTTP 422 → `ValidationException`, other 4xx/5xx →
     `DoclingServeClientException`. **No retry logic** — caller must implement
     retries. Async pattern: posts to `/v1/convert/source/async`, polls
     `/v1/status/poll/{task_id}` at fixed interval, times out after
     `asyncTimeout`.

  4. **`docling-testcontainers`** — `DoclingServeContainer` extending
     Testcontainers `GenericContainer`. Health check via `GET /health`.
     Default startup timeout: 1 minute (**insufficient** for the 4-11 GB
     images without pre-cached layers — must configure to 5+ minutes).

  **docling-serve** (Python, FastAPI): The REST sidecar runs on port 5001.
  Full API: sync/async conversion (`POST /v1/convert/source`,
  `/v1/convert/file`), sync/async chunking (`POST /v1/chunk/source`,
  `/v1/chunk/file`), task management (`GET /v1/status/poll/{task_id}`,
  `GET /v1/result/{task_id}`, `WS /v1/status/ws/{task_id}`), health
  (`GET /health`), metrics (`GET /metrics`), management endpoints (memory
  stats, cache clearing). Supports per-request pipeline selection (pass
  `pipeline: vlm` in request body). Container images: CPU-only (4.4 GB),
  CUDA 12.8 (11.4 GB), default multi-arch (8.7 GB amd64 / 4.4 GB arm64).
  Configurable via environment variables: `UVICORN_HOST/PORT`,
  `DOCLING_SERVE_ENG_LOC_NUM_WORKERS`, `DOCLING_SERVE_MAX_NUM_PAGES`,
  `DOCLING_SERVE_MAX_FILE_SIZE`, `DOCLING_SERVE_ARTIFACTS_PATH` (model cache
  for air-gapped environments), `DOCLING_SERVE_SINGLE_USE_RESULTS` (prevents
  disk filling).

- **Evidence** — docling-java GitHub: 83 stars, 14 forks, 11 releases since
  Nov 2025, 14 open issues (none labeled bug). Active IBM-team development
  (3-4 core contributors, weekly dependency bumps). docling-serve: 1,285
  stars, 266 forks, v1.14.0 (2026-02-25), 108 open issues. Maven Central
  confirms all four artifacts at `repo1.maven.org/maven2/ai/docling/` (search
  API has indexing lag). Version history: 0.1.3 → 0.4.7, no 1.0 milestone.
  Open issues of note: #350 (increase code coverage), #351 (no linting in CI),
  #339 (422 response model mismatch), #206 (referenced image export broken).

- **Applicability** — **Needs adaptation.** The client is pre-1.0 with no
  stable API guarantee, no built-in retry, and a known 422 model mismatch.
  However, the core document model is complete and the API surface is clean.
  The recommended integration path for JustSearch:

  1. Run `docling-serve-cpu:v1.14.0` as Docker sidecar bound to
     `127.0.0.1:5001` (matches JustSearch's loopback-only invariant).
  2. Use `ai.docling:docling-serve-client:0.4.7` for HTTP communication.
  3. Convert PDFs via `POST /v1/convert/source` with `to_formats: ["json"]`
     to get structured `DoclingDocument`.
  4. Use `image_export_mode: "embedded"` (not "referenced" — issue #206).
  5. Add JustSearch-owned retry wrapper around `convertSource()`.
  6. Fallback to Tika when docling-serve is unavailable or for non-PDF
     formats where Docling adds no value.

  **Dependency concern:** Jackson 2 in `docling-serve-api` + Jackson 3 in
  the client creates a coordination issue. JustSearch uses Jackson 2
  throughout — using the Jackson 2 client variant avoids this.

- **Adoption cost** — Medium-high. Requires: (1) Docker as a runtime
  dependency (or pip-installed docling-serve), (2) a new `DoclingExtractor`
  adapter in `modules/indexer-worker` or `modules/app-services`, (3) sidecar
  lifecycle management (start/stop/health-check docling-serve alongside
  JustSearch's existing process management), (4) mapping from
  `DoclingDocument` to JustSearch's index schema, (5) retry and timeout
  handling, (6) a Gradle dependency on `ai.docling:docling-serve-client`.
  The Docker dependency is the biggest cost — JustSearch currently has no
  Docker dependency. Alternative: `pip install docling-serve` directly, but
  this requires Python on the user's system.

- **Expected impact** — High (for users who opt in). Layout-aware extraction
  for PDFs with tables, figures, and complex structure. The sidecar model
  keeps the core app lightweight while optionally enabling advanced document
  understanding. The main risk is adoption friction — requiring Docker or
  Python is a significant step for a desktop app that currently has zero
  external runtime dependencies beyond the JVM.

### 4. Granite-Docling-258M — Tiny VLM for layout + tables + OCR

- **JustSearch baseline** — VDU fallback uses full-page OCR (no layout model). No VLM in the pipeline. The Brain process (llama-server) manages the only GPU model (chat/embedding), with single-tenant GPU policy (mutual exclusion between embedding and generative models).

- **How it works** — A 258M-parameter vision-language model (siglip2-base-
  patch16-512 vision encoder + pixel shuffle projector + Granite 165M LLM).
  Apache 2.0 license. Replaces the entire default Docling pipeline (RT-DETR
  layout + OCR + TableFormer) with a **single forward pass per page**. The
  VLM ingests a rasterized page image and outputs DocTags — a structured
  format that Docling post-processes into a `DoclingDocument`. Supports:
  full-page conversion, chart-to-table, formula-to-LaTeX, code-to-text,
  table-to-OTSL, location-specific OCR, and structural QA.

  **Key accuracy numbers (vs SmolDocling-256M predecessor):**
  | Task | SmolDocling | Granite-258M |
  |------|-------------|--------------|
  | Layout MAP | 0.23 | **0.27** |
  | OCR F1 | 0.80 | **0.84** |
  | Code F1 | 0.915 | **0.988** |
  | Equation F1 | 0.947 | **0.968** |
  | Table TEDS (structure) | 0.82 | **0.97** |
  | Table TEDS (w/content) | 0.76 | **0.96** |
  | OCRBench | 338 | **500** |

  **Inference paths:** PyTorch transformers (CPU or CUDA), vLLM (Linux, GPU),
  Docling CLI (`--pipeline vlm`), GGUF via llama.cpp (Q8_0: 178 MB, F16:
  332 MB), MLX (Apple Silicon), Ollama (`ibm/granite-docling:258m`). In
  docling-serve, selected per-request via `pipeline: vlm` in request body.

  **Known bug:** Original checkpoint shipped with `use_cache: false` causing
  O(N^2) generation. GGUF/MLX distributions are unaffected. PyTorch users
  must pass `use_cache=True` to `model.generate()`.

- **Evidence** — HuggingFace model card (ibm-granite/granite-docling-258M).
  GPU throughput: RTX 4070 achieves 231 t/s (torch.compile) or 395-506 t/s
  (GGUF), translating to **3-7 sec/page** at 1500 tokens. Full VLM pipeline
  via Docling: 2.8-3.2 pages/sec on RTX 5070, 3.6-4.5 pages/sec on RTX
  5090. CPU throughput: no authoritative IBM benchmark. Community reports
  suggest ~35-40 sec/page via PyTorch, estimated 15-30 sec/page via
  llama.cpp GGUF Q8_0 (extrapolated). VRAM requirement: confirmed working
  on RTX 4070 (8 GB) with bfloat16; GGUF Q8_0 likely fits in 2-3 GB.
  SmolDocling model card confirms Granite-Docling is the successor with
  active support.

- **Applicability** — **Future enhancement, not near-term.** Three factors
  make this impractical for immediate adoption:

  1. **CPU speed is prohibitive.** At 15-40 sec/page on CPU, it is 5-15x
     slower than TableFormer (2-6 sec/table) and 10-60x slower than the
     default Docling pipeline (0.6-2.2 pages/sec). For a desktop app
     processing 100+ page documents, this means minutes per document.

  2. **GPU conflicts with single-tenant policy.** Loading the VLM (~520 MB
     bfloat16 or 178 MB GGUF Q8_0) on the same GPU as the embedding model
     (139 MB Q8_0) and chat model violates JustSearch's mutual exclusion
     invariant. Sequential loading (load VLM for ingestion, unload for
     search/chat) is architecturally complex and adds latency.

  3. **The default pipeline is good enough.** RT-DETR + TableFormer achieves
     78% mAP (layout) and 96.8% TEDS (tables) at 0.6-2.2 pages/sec on CPU.
     The VLM's 97% TEDS is better (+0.2), but the speed penalty is
     disproportionate.

  **When it becomes relevant:** If JustSearch ever runs on systems with
  dedicated document-processing GPUs (e.g., a secondary GPU), or if
  llama.cpp CPU inference for 258M models improves to <5 sec/page, or if
  ONNX export becomes available (SmolDocling had ONNX, Granite does not
  yet). The GGUF path via llama.cpp is the most promising route since
  JustSearch already has llama.cpp FFM bindings.

- **Adoption cost** — Low to Medium (when the time comes). The model is
  available in GGUF format compatible with llama.cpp, which JustSearch
  already integrates. The docling-serve sidecar can switch between default
  and VLM pipelines per-request. The main cost is VRAM management — either
  accept the single-tenant policy violation or implement sequential
  loading/unloading.

- **Expected impact** — Marginal over the default pipeline for most
  documents. The +0.2 TEDS improvement matters for documents with very
  complex tables (financial statements, dense data tables). The biggest
  unique value is **single-pass processing** — one model handles layout,
  OCR, tables, formulas, and code, eliminating the multi-model pipeline
  complexity. This simplification may matter more as the pipeline grows.

---

## Additional Findings

1. **Triplet table serialization for embedding.** Docling's
   `TripletTableSerializer` (`row_header, col_header = cell_value` joined
   with `. `) is a deliberate design choice for making table cells self-
   contained in embedding space. This pattern is transferable to JustSearch
   independently of Docling — if JustSearch ever gains table structure
   awareness (from any source), serializing tables as triplets for the
   embedding/chunk index would be more effective than markdown pipes or flat
   text.

2. **Jackson 2/3 dual support.** docling-java supports both Jackson 2 and
   Jackson 3, which is relevant because JustSearch uses Jackson 2. The
   Jackson 2 client variant avoids dependency conflicts without requiring
   a Jackson migration.

3. **docling-serve per-request pipeline switching.** The sidecar supports
   selecting the processing pipeline (standard vs VLM) per request via the
   API body. This means JustSearch could route different documents through
   different pipelines based on file characteristics — e.g., scanned PDFs
   through the VLM pipeline, programmatic PDFs through the standard pipeline.
   The `DOCLING_SERVE_OPTIONS_CACHE_SIZE=2` env var controls how many
   `DocumentConverter` instances are cached, avoiding model reload on
   pipeline switches.

4. **Docling's enrichment plugins are all optional and off by default.**
   Code understanding, formula extraction, picture classification, and
   picture description are disabled unless explicitly enabled. This means
   the sidecar's baseline resource usage is just layout + table detection,
   with enrichments available on-demand for specific document types.

5. **`force_backend_text` option in VLM pipeline.** A `VlmPipelineOptions`
   field that may allow using native PDF text (skipping OCR) while still
   using the VLM for layout and table tasks. Behavior is undocumented — if
   confirmed, this would reduce VLM processing time for programmatic PDFs.

6. **No overlap parameter in Docling chunking.** JustSearch's `ChunkSplitter`
   uses 50-token overlap between consecutive chunks. Docling's HybridChunker
   has no explicit overlap — its strategy is to merge undersized consecutive
   chunks with matching headings instead. This is a different approach to the
   same problem (context continuity at chunk boundaries). Whether overlap or
   merge-peers produces better retrieval is an empirical question.

---

## Unresolved Items

1. **Granite-Docling-258M CPU inference speed via llama.cpp** — No published
   benchmark exists. The 15-30 sec/page estimate is extrapolated from
   llama.cpp scaling behavior at this parameter count. Would need to run the
   GGUF model on a representative desktop CPU to get authoritative numbers.

2. **docling-serve VLM pipeline cache behavior** — Whether the converter cache
   (`DOCLING_SERVE_OPTIONS_CACHE_SIZE`) keeps both standard and VLM pipeline
   instances warm simultaneously, or whether switching pipelines per-request
   incurs a model reload penalty.

3. **`force_backend_text` exact behavior** — Whether this VLM pipeline option
   preserves native PDF text while using VLM only for layout/table tasks, or
   whether it has a different effect.

4. **The KV cache bug fix status** — Whether the `use_cache: false` bug in
   the original Granite-Docling-258M checkpoint has been patched in the main
   model weights, or only in GGUF/MLX distributions. Docling's `VlmPipeline`
   may handle this internally.

5. **RTX 4070 VLM pipeline throughput** — The Docling GPU benchmark page
   only tested RTX 5070/5090. RTX 4070 (the more common desktop card)
   performance must be interpolated or measured directly.

---

## Cross-Cutting Observations

**No direct contributions to §7 key questions** from the parent tempdoc. The
Docling investigation addresses document processing quality (extraction and
chunking), which is upstream of the search quality questions in §7 (score
normalization, FeatureField precision, relevance feedback, ColBERT, query
expansion, filtered ANN, GBNF grammar, Matryoshka embeddings, LambdaMART).

However, one **indirect connection** exists: improved document structure
(tables, headings, figures) from Docling would produce better input for the
embedding and SPLADE models, potentially improving retrieval quality
independently of any fusion or reranking changes. This is complementary to
the search-layer improvements investigated in other §2/§3 targets.

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.

---

## infinity-findings

*(from `249-infinity-findings.md`)*

### 249: Infinity Investigation Findings

## Recon Summary

**JustSearch baseline loaded from:** `HybridFusionUtils.java`,
`HybridSearchOps.java`, `SearchOrchestrator.java`, `FieldMapper.java`,
`LuceneIndexRuntime.java`, `CrossEncoderReranker.java`,
`LambdaMartReranker.java`, `JustSearchCodec.java`, tempdoc 245 eval results.

**External sources read:**
- Infinity GitHub (`infiniflow/infinity`) — `physical_fusion.cppm`,
  `physical_fusion_impl.cpp`, `physical_match.cppm`, EMVB directory
  (`emvb_search.cppm`, `emvb_index.cppm`, `product_quantizer.cppm`,
  `eigen_svd.cppm`), BMP directory (`bmp_alg.cppm`, `bmp_ivt.cppm`,
  `bmp_fwd.cppm`, `bp_reordering.cppm`),
  `blockmax_wand_iterator.cppm`, `benchmark.md`
- Infinity online docs: search guide, benchmark reference, Python SDK API
- Infinity blog: "Best Hybrid Search Solution", "Multi-way Retrieval
  Evaluations", "Fastest Hybrid Search"
- Academic papers: EMVB (arXiv 2404.02805, ECIR 2024), BMP (arXiv 2405.01117,
  SIGIR 2024), Seismic (arXiv 2404.18812, SIGIR 2024), Recursive Graph
  Bisection (arXiv 1602.08820), "Balancing the Blend" (arXiv 2508.01405, 2025)
- BMP paper Table 4: beta tuning data (MRR@10 vs latency at 10 beta levels)

**Nothing skipped.** All four checklist items investigated.

---

## Findings

### 1. Four-way fusion — RRF, weighted sum, and ColBERT reranking

- **JustSearch baseline** — Three-way RRF in `HybridFusionUtils.java`:
  `1/(K+rank)` for sparse leg, `vectorWeight/(K+rank)` for vector leg.
  K=60, vectorWeight=0.75, bm25ScoreBoostWeight=0.002. No score normalization
  before fusion. Second RRF pass merges chunk results. No ColBERT reranking.

- **How it works** — Infinity's `physical_fusion` operator supports four fusion
  methods: `kRRF`, `kWeightedSum`, `kMatchTensor` (ColBERT), `kMax`.

  **RRF:** `1/(rank_constant + rank)` summed across all child legs. Default
  `rank_constant = 60`, configurable. No per-leg weighting in RRF mode —
  every leg contributes equally. This is pure RRF per the Cormack & Clarke 2009
  paper.

  **Weighted sum:** Each leg's raw scores are normalized, then accumulated as
  `Σ weights[i] * normalized_score[i]`. Three normalization options:
  - **minmax** (default): `(score - batch_min) / (batch_max - batch_min)` —
    local, per-query, per-leg. Returns 0 when max=min.
  - **atan**: `atan(score) / pi + 0.5` — maps unbounded scores to (0, 1).
    Useful for raw inner-product distances.
  - **l2**: `score / sqrt(Σ score_i^2)` — unit-hypersphere projection per leg.

  **MatchTensor (ColBERT):** MaxSim operator. Can run as either a first-stage
  retriever (using the EMVB index) or as a reranker on fused candidates from a
  prior RRF/weighted-sum stage. When reranking: receives top-N document IDs
  from the prior stage, computes ColBERT MaxSim scores, replaces fusion scores.

  **Execution model:** Fragment-based pipeline executor. Individual match
  operators (`PhysicalMatch`, `PhysicalKnnScan`, `PhysicalMatchSparse`) each
  report TaskletCount=1. Parallelism is implicit at the scheduler level —
  results from multiple legs arrive as separate fragments and are collected
  asynchronously. Not explicitly parallel in operator code.

  **Observability:** Trace-level logging of `(doc, child, raw_score,
  normalized_score)` tuples. No per-phase timing instrumentation.

- **Evidence** — Source: `physical_fusion_impl.cpp`, `physical_fusion.cppm`.
  Infinity search guide confirms RRF K=60 default and normalization options.
  Blog post confirms ColBERT is "primarily used as a reranker."

- **Applicability** — Needs adaptation. The weighted-sum with minmax
  normalization is the most interesting alternative to JustSearch's pure RRF.
  It preserves score magnitude information that RRF discards, which Zhuang
  et al. (TOIS 2023, cited in tempdoc 249 §5.1) showed outperforms RRF.
  The atan and l2 normalizations are niche (useful for inner-product distances
  or high-dimensional score spaces that JustSearch doesn't encounter).

- **Adoption cost** — Add a `WeightedSumFusion` method alongside RRF in
  `HybridFusionUtils.java`. Requires per-leg min/max computation (one extra
  pass over results) and per-leg weight configuration. Moderate: ~200 lines,
  confined to `modules/adapters-lucene`. The existing `vectorWeight` and
  `bm25ScoreBoostWeight` parameters map naturally to weighted-sum leg weights.

- **Expected impact** — The Zhuang et al. TOIS 2023 paper measured convex
  combination (equivalent to weighted-sum with minmax normalization) at +1-3%
  nDCG over RRF on BEIR datasets. Sample-efficient to tune (~40 queries).
  Worth A/B testing against current RRF on SciFact/NFCorpus.

---

### 2. ColBERT as native index type — EMVB storage and scoring

- **JustSearch baseline** — No ColBERT. Cross-encoder reranking via
  MiniLM-L6-v2 (ONNX, 200ms deadline, top-20). 373ms CPU for 10 candidates
  (exceeds budget). Prior assessment (tempdoc 249 §5.4): "ColBERT storage is
  10-50x larger than single-vector. Not practical for desktop."

- **How it works** — Infinity implements EMVB (Efficient Multi-Vector Backward,
  ECIR 2024) as a native ColBERT index. Key design:

  **Storage:** Per-token vectors compressed via OPQ (Optimized Product
  Quantization). Each token stores a u32 centroid assignment (4 bytes) plus
  a PQ residual code (SUBSPACE_NUM bytes, typically 16-32). At m=16 subspaces
  with 8-bit codes: **20 bytes per token**. For a 300-token document:
  **6,000 bytes** (5.86 KB).

  Comparison to single-vector (768-dim float32 = 3,072 bytes):
  - EMVB m=16, 300 tokens: **2.0x** overhead (not 10-50x)
  - EMVB m=32, 300 tokens: **3.5x** overhead
  - Uncompressed ColBERT (128-dim float32, 300 tokens): 50x overhead

  **The §5.4 claim is accurate only for uncompressed ColBERT. With EMVB PQ
  compression, actual overhead is 2-4x.** At 100K docs (avg 200 tokens), the
  ColBERT index would be ~400 MB — comparable to a dense HNSW index.

  **MaxSim scoring:** AVX2 SIMD with 32-byte aligned transposed matrices.
  Template-instantiated for query lengths in multiples of 32 (32, 64, ..., 256).
  `GetMaxSim32Width<N>` processes 32 query tokens simultaneously using 4
  parallel `__m256` registers. Scalar fallback for non-AVX2.

  **Four-stage candidate pipeline:**
  1. **Centroid pruning:** For each query token, find relevant centroids
     (top-`nprobe` by score). Traverse inverted lists to collect candidate
     documents.
  2. **Hit-frequency filter:** Rank candidates by how many distinct query
     tokens they cover. Keep top `n_doc_to_score`.
  3. **PQ-approximated MaxSim:** Use centroid scores (not full embeddings)
     as proxy for token similarity. AVX2-accelerated. Keep top
     `out_second_stage`.
  4. **Residual PQ refinement:** Add PQ residual corrections to centroid
     scores. Compute final MaxSim. Return top-k.

  **OPQ training:** 6-iteration alternating optimization. Each iteration:
  rotate embeddings → train PQ (k-means per subspace) → encode/decode →
  compute `A = X^T * X_hat` → SVD via Eigen → update rotation `R = U * V^T`.
  Convergence tracked via L2 distance between successive rotation matrices.

- **Evidence** — EMVB paper (arXiv 2404.02805, ECIR 2024):
  - MS MARCO (8.8M passages): R@100 = 90.7%, R@1000 = 97.5% (m=16, matches
    PLAID quality)
  - Latency: 68ms for k=100 on MS MARCO (single-threaded CPU). PLAID: 180ms.
  - Memory: 20 bytes/embedding (m=16) vs PLAID 36 bytes/embedding

- **Applicability** — Not directly applicable to JustSearch's Lucene stack.
  EMVB requires a purpose-built multi-vector index (centroid-based inverted
  list + PQ codebooks + forward index) that Lucene does not provide. Porting
  would require either a JNI wrapper around the C++ implementation or a custom
  Lucene codec. However, the storage overhead analysis is directly applicable:
  the 10-50x claim should be revised to 2-4x with PQ compression.

  For desktop-scale ColBERT reranking (not full-corpus retrieval), the
  simpler approach is to store per-token embeddings as raw float32 in a
  side-car file and compute MaxSim at rerank time on the top-100 candidates
  from the existing hybrid pipeline. At 100 candidates × 300 tokens × 128-dim
  float32 = 15 MB — fits in L3 cache. This avoids the EMVB index complexity
  entirely.

- **Adoption cost** — High for full EMVB index (custom Lucene codec or JNI
  bridge, PQ training infrastructure, OPQ rotation). Low-medium for simple
  ColBERT reranking on top-100 (store per-token vectors, compute MaxSim in
  Java — comparable to the existing cross-encoder path).

- **Expected impact** — ColBERT quality is in the top-5 dense retrieval
  methods but still behind cross-encoders by ~5-10 nDCG (per §5.4). The main
  value is latency: ColBERT MaxSim on 100 candidates would be sub-millisecond
  vs 373ms for the current cross-encoder. If quality is comparable, the latency
  win is decisive. Needs BEIR eval to measure.

---

### 3. Latency engineering — Block-Max WAND and SIMD

- **JustSearch baseline** — Lucene's built-in MaxScore/WAND for BM25 (tuned
  for BM25 impact distributions). FeatureField SPLADE queries use
  BooleanQuery with SHOULD clauses — no block-max pruning. Virtual thread
  parallelism for BM25+KNN. HNSW ef_search=100. No formal latency SLO.

- **How it works** — Infinity achieves sub-2ms BM25 and sub-11ms SPLADE via:

  **Block-Max WAND for BM25** (`blockmax_wand_iterator.cppm`): Precomputed
  float32 max BM25 scores per block (vs Lucene's `(tf, dl)` pairs that require
  recomputation). Lazy sorting (every 3 iterations). Fast pivot estimation for
  >50 candidates.

  **BMP for SPLADE** (see Finding 4 below).

  **MLAS (Microsoft Linear Algebra Subprograms):** Hardware-adaptive SIMD
  matrix multiply for dense vector operations. Handles AVX2/AVX-512/SSE
  automatically.

  **In-process colocation:** All four retrieval legs in one C++ process.
  Zero IPC overhead. JustSearch's Head→Worker gRPC adds 1-5ms per query.

  **Result caching:** `result_cache_manager.cppm` in the storage layer.

- **Evidence** — Infinity benchmarks (16-core i5-13500H, 32GB):
  - Enwiki 33M docs BM25: P95 = **1.37ms** (vs Elasticsearch P95 = 14.75ms)
  - SIFT1M dense HNSW: **16,320 QPS** (vs Qdrant 1,303, ES 934)
  - No hybrid latency benchmark published

- **Applicability** — Mixed. The in-process architecture and SIMD intrinsics
  are not portable to Java. The precomputed block-max BM25 scores are
  architecturally embedded in Infinity's posting format. However, Lucene's
  MaxScore already implements WAND for BM25 — the ~10x gap is primarily
  C++/JVM overhead plus Infinity's flat-array memory layout, not an
  algorithmic difference JustSearch can close.

  **One directly applicable technique:** Lucene ships `BPIndexReorderer` in
  `lucene-misc` (Lucene 9.x) — Recursive Graph Bisection for document
  reordering. Apply during index optimization. Expected 10-30% BM25 query
  throughput improvement. Zero query-time cost.

- **Adoption cost** — BPIndexReorderer: low (call during segment merge in
  `modules/adapters-lucene`). In-process colocation: impossible without
  violating architectural invariant #1.

- **Expected impact** — BPIndexReorderer: 10-30% BM25 latency reduction
  (literature). Verify on JustSearch's corpus before shipping.

---

### 4. Sparse vector indexing — BMP vs Lucene FeatureField

- **JustSearch baseline** — Lucene `FeatureField` with float32 weights. One
  entry per non-zero SPLADE token. Queried as BooleanQuery with SHOULD clauses.
  Lucene's MaxScore optimizer was tuned for BM25 distributions, not learned
  sparse weights. FeatureField has 8-bit mantissa (SmallFloat) and (0, 64]
  weight range.

- **How it works** — Infinity uses BMP (Block-Max Posting), a purpose-built
  SPLADE index from the SIGIR 2024 paper by Mallia et al.

  **Three data structures:**
  1. **BMPIvt (inverted index):** Per-term posting lists with per-block maximum
     scores. Two storage modes: compressed (sparse block IDs, for low-frequency
     terms) and raw (dense array, for high-frequency terms — 1.5-2.5x
     additional speedup for SPLADE since most blocks are populated).
  2. **BlockFwd (forward index):** Per-block storage of all documents' complete
     sparse vectors. Enables tight-loop scoring after block selection. Two-
     pointer merge for query-document inner product.
  3. **doc_ids:** Internal-to-external ID mapping.

  **Block size:** Configurable, default 16 in Infinity (paper recommends 32 as
  sweet spot). Each block is a contiguous range of document IDs.

  **Pruning algorithm:**
  - Compute per-block upper bound: `U(q, B) = Σ_t w(t,q) * max_impact(t, B)`
  - Process blocks in descending upper-bound order
  - Skip when: `ub_score * alpha < kth_heap_score`
  - Alpha ∈ [0, 1], default 1.0 (exact). Lower = approximate, faster.

  **Beta query-term pruning:** Before search, discard the lowest-weight
  query terms. SPLADE queries expand to 50-200 terms; the tail terms have
  negligible weights. Beta controls the fraction of terms kept.

  **Score precision:** Float32 throughout — both storage and scoring. No
  8-bit mantissa limitation. No (0, 64] weight range constraint.

  **SIMD:** `_mm_prefetch(..., _MM_HINT_T0)` on block data arrays before
  scoring loops. Flat-array layout enables auto-vectorization of the
  multiply-accumulate scoring kernel.

  **Precomputed kth-score:** `Optimize()` precomputes per-term k-th element
  score via `std::nth_element`, used to set initial thresholds before block
  processing.

- **Evidence** — BMP paper (arXiv 2405.01117, SIGIR 2024):
  - SPLADE at k=10: BMP = **10.5ms**, MaxScore = 120.6ms, BMW = 614.2ms
  - **11.5x speedup** over MaxScore (the closest Lucene analog)
  - BMW is paradoxically *slower* than MaxScore for SPLADE because heavy
    query expansion defeats WAND's pivot estimation
  - BMP paper also notes that Seismic (cluster-based approximate retrieval)
    achieves **187-531 microseconds** but with recall trade-offs

  **Beta tuning data** (BMP paper Table 4, SPLADE CoCondenser-EnsembleDistil,
  MS MARCO 8.8M passages, MRR@10 baseline = 37.97):

  | Beta (fraction kept) | Latency | MRR@10 | Delta |
  |---------------------|---------|--------|-------|
  | 1.0 (all terms) | 3.2 ms | 37.97 | baseline |
  | 0.8 (keep 80%) | 2.8 ms | 38.04 | +0.07 |
  | 0.6 (keep 60%) | 2.2 ms | 38.12 | +0.15 |
  | **0.5 (keep 50%)** | **1.8 ms** | **38.13** | **+0.16** |
  | 0.4 (keep 40%) | 1.5 ms | 37.78 | -0.19 |
  | 0.3 (keep 30%) | 1.2 ms | 37.31 | -0.66 |
  | 0.2 (keep 20%) | 0.9 ms | 35.81 | -2.16 |

  **Key insight:** Pruning 50% of SPLADE query terms **improves** quality
  (+0.16 MRR@10) while nearly halving latency. Tail terms add noise, not
  signal. The quality cliff is at ~40% kept; anything above 50% is safe.

  **Block size sensitivity** (exact retrieval, k=10): b=32 = 10.5ms (sweet
  spot), b=16 = 11.0ms, b=8 = 15.0ms. For approximate retrieval with alpha
  tuning, b=64 gives best compression/latency balance.

  Lucene issue #11799 confirms: using FeatureField for SPLADE is acknowledged
  as "a bit of hacking" with no native block-max inverted structure for
  impact-sorted learned sparse weights in Lucene as of 2026.

- **Applicability** — Mixed. Two techniques are directly applicable today;
  the full BMP algorithm requires a custom Lucene codec.

  **Directly applicable now:**
  1. **Beta-style query-term pruning.** Before issuing
     `FeatureField.newLinearQuery()`, sort SPLADE tokens by weight descending,
     keep the top 50% (`ceil(terms.size() * 0.5)`), discard the rest. BMP
     paper data shows this **improves** MRR@10 by +0.16 while cutting latency
     ~1.8x. The quality cliff is at 40% kept; 50% is safe with margin.
     Reduces SHOULD clause count from ~80-120 to ~40-60. Implementable in
     `LuceneIndexRuntime.java` today. Without block-max pruning, beta is
     JustSearch's **only** SPLADE query optimization lever, making it even
     more impactful than in BMP (where block-max already provides speedup).
  2. **BP document reordering.** Lucene 9.x ships `BPIndexReorderer` in
     `lucene-misc`. Apply during segment merge/optimize. Benefits both BM25
     and FeatureField queries via better posting list locality.

  **Needs custom Lucene codec:**
  3. **True block-max inverted index.** Replacing FeatureField with a custom
     `PostingsFormat` that stores per-block max float32 impact scores would
     close the 11.5x algorithmic gap. Requires custom `PostingsFormat`,
     `LeafReader` extension, and query implementation. The upstream Lucene
     issue #11799 is open and unresolved.

- **Adoption cost** —
  - Beta query-term pruning: low (~30 lines in `LuceneIndexRuntime.java`).
    Recommended starting point: keep top 50% of terms by weight (beta=0.5).
  - BPIndexReorderer: low (call during merge in `modules/adapters-lucene`)
  - Custom block-max codec: very high (custom PostingsFormat + Query + tests,
    estimated weeks of work, ongoing maintenance as Lucene evolves)

- **Expected impact** —
  - Beta pruning: 1.8x speedup measured in BMP paper (with block-max);
    expect even larger speedup on FeatureField (no block-max to amortize
    term count). Quality strictly improves at beta=0.5 (+0.16 MRR@10).
    Must verify on SciFact with JustSearch's SPLADE-v3.
  - BPIndexReorderer: 10-30% BM25/SPLADE latency reduction
  - Custom block-max codec: up to 11.5x SPLADE speedup (BMP paper numbers),
    but only relevant if SPLADE latency is a bottleneck at JustSearch's
    corpus scale (100K-1M docs)

---

## Additional Findings

### A. BP document reordering is already in Lucene

Infinity's `bp_reordering.cppm` implements Recursive Graph Bisection
(Dhulipala et al., arXiv 1602.08820) for document ID reordering. Lucene 9.x
ships `BPIndexReorderer` in `lucene-misc` with identical parameters (min doc
freq 4096, min partition 32, max iterations 20). This was not known from
JustSearch's existing docs. Cost is index-time only; query-time benefit is
passive via better posting list delta-encoding and cache locality.

### B. EMVB storage overhead invalidates §5.4 desktop impracticality claim

The prior assessment that ColBERT storage is "10-50x larger than single-vector"
and "not practical for desktop" (tempdoc 249 §5.4) applies only to uncompressed
ColBERT. With EMVB's OPQ compression (m=16, 8-bit), actual overhead is 2-4x.
At 100K documents with avg 200 tokens/doc: ~400 MB ColBERT index fits
comfortably in 16 GB RAM alongside all other indexes. The storage concern is
not a blocker at desktop scale.

### C. ColBERT reranking as cross-encoder replacement

Infinity demonstrates that ColBERT reranking (MatchTensor) chains cleanly
after RRF fusion: run hybrid retrieval → take top-N → compute ColBERT MaxSim
→ rerank. JustSearch could implement this as a third phase in
`SearchOrchestrator`: after chunk-merge RRF, take top-100 and compute MaxSim
using stored per-token embeddings. This would replace the cross-encoder path
(which already exceeds its 200ms deadline) with sub-millisecond MaxSim
scoring. Quality comparison (ColBERT MaxSim vs cross-encoder MiniLM-L6-v2)
needs BEIR eval.

### D. Seismic — cluster-based alternative to BMP

The BMP paper benchmarks Seismic (arXiv 2404.18812, SIGIR 2024), a
cluster-based approximate sparse retrieval method achieving 187-531
microseconds on MS MARCO. Seismic trades exact retrieval for speed via
cluster-level pruning. At desktop scale (100K-1M docs), this may be worth
investigating as an alternative to both FeatureField and a full BMP codec.
Reference implementation: github.com/TusKANNy/seismic.

### E. Weakest-link phenomenon explains JustSearch's Arguana RRF regression

The "Balancing the Blend" paper (arXiv 2508.01405, 2025) confirms the
**weakest-link phenomenon** across 13 BEIR datasets using BGE-M3: adding a
weak retriever via RRF can hurt performance relative to the strong retriever
alone. Examples from the paper:
- Touche-2020: BM25-only = 0.650, FTS+DVS RRF = 0.604 (-0.046)
- TREC-COVID: BM25-only = 0.839, all hybrid configs score lower

This directly explains JustSearch's Arguana result: dense-only = 0.370, RRF =
0.289 (-0.081). This is not a JustSearch bug — it is a documented property of
rank fusion when one leg is weak on a dataset. Mitigation: per-query adaptive
fusion (suppress the weak leg when its signal is low), or weighted-sum where
the weak leg gets near-zero weight.

### F. Quality benchmarks: JustSearch competitive with BGE-M3

Comparison against the "Balancing the Blend" paper (BGE-M3, full precision) on
SciFact:

| Configuration | BGE-M3 paper | JustSearch | Gap |
|---------------|-------------|------------|-----|
| BM25-only | 0.704 | 0.660 | -0.044 |
| Dense-only | 0.715 | 0.694 (Q8) | -0.021 |
| Sparse+Dense RRF | 0.716 | 0.702-0.706 | -0.010 to -0.014 |
| Best hybrid (no reranker) | 0.748 (FTS+DVS RRF) | 0.706 | -0.042 |
| Best overall (with ColBERT TRF) | 0.762 | — | — |

The sparse+dense RRF gap (0.010-0.014) is within model quality differences
(nomic-embed Q8 vs BGE-M3 full precision). The BM25 gap (0.044) is larger and
may warrant investigation of tokenization/preprocessing differences.

Key paper finding: **three-way RRF does NOT consistently beat two-way.** On
SciFact, FTS+DVS RRF (0.748) beats FTS+SVS+DVS RRF (0.739). Three-way only
wins when all three paths are individually strong. JustSearch's current
SPLADE+Dense RRF without BM25 is a reasonable design choice.

### G. Weighted-sum outperforms RRF on long documents (Infinity MLDR test)

Infinity's blog reports that on MLDR (200K long documents): 20% dense + 80%
sparse weighted-sum "significantly outperformed" RRF. No numerical values
published (results in bar graphs only). This is consistent with the Zhuang
et al. TOIS 2023 finding that convex combination > RRF, especially when
score distributions differ significantly between legs (as they do for long
documents where BM25 scores are high and dense similarity scores are moderate).

### H. Infinity's BM25 10x advantage is mostly C++/JVM overhead

The 10.8x BM25 gap (Infinity 1.37ms vs ES 14.75ms on Enwiki 33M) persists
even though both use Block-Max WAND. The gap is attributed to: precomputed
float32 max scores (vs Lucene's recomputed BM25 upper bounds), flat-array
memory layout (vs JVM object heap), `_mm_prefetch` intrinsics (vs JIT-managed
prefetch), and in-process colocation (vs network layer). This gap is
structural and not closeable within JustSearch's Java/Lucene architecture.

---

## Unresolved Items

- **End-to-end hybrid latency** — Infinity publishes no benchmark for four-way
  (BM25 + dense + sparse + ColBERT) hybrid search latency. The 0.1ms claim
  from the README applies to dense HNSW only. Would require running Infinity
  locally with a benchmark harness to measure.

- **EMVB recall-vs-compression curves** — The EMVB paper reports R@100 and
  R@1000 at m=16 and m=32, but no sweep across compression levels (m=4, 8,
  16, 32, 64). The quality-vs-storage trade-off at fine granularity is not
  documented. Would require running the EMVB benchmark with varying parameters.

- **BP reordering quantified impact on JustSearch** — Infinity's
  `bp_reordering.cppm` and Lucene's `BPIndexReorderer` implement the same
  algorithm, but the 10-30% throughput improvement is from academic literature
  on different corpora. Impact on JustSearch's specific corpus and query
  distribution is unknown. Requires local benchmarking.

---

## Cross-Cutting Observations

**Q1 (FeatureField 8-bit mantissa):** BMP stores float32 natively with no
precision loss. This confirms that the 8-bit mantissa in Lucene's FeatureField
is a real limitation — purpose-built SPLADE indexes (BMP, Infinity, Seismic)
all use full-precision floats. However, the precision limitation may matter
less than the algorithmic limitation: the 11.5x gap between BMP and MaxScore
is dominated by block-max pruning, not score precision.

**Q2 (best score normalization for hybrid fusion):** Infinity supports both
RRF (K=60, pure rank-based) and weighted-sum with minmax/atan/l2
normalization. Their architecture explicitly separates the two approaches as
alternative fusion methods, not a single pipeline. Infinity's MLDR test found
weighted-sum (20% dense / 80% sparse) significantly outperformed RRF on long
documents. The "Balancing the Blend" paper (arXiv 2508.01405) found no
universal winner — performance is dataset-dependent and the weakest-link
phenomenon matters more than fusion method choice. This is consistent with
Zhuang et al. TOIS 2023 (§5.1) but adds nuance: weighted-sum wins when leg
quality is asymmetric; RRF is more robust when leg quality is unknown.

**Q4 (ColBERT vs cross-encoder):** EMVB with PQ compression reduces ColBERT
storage to 2-4x single-vector (not 10-50x as §5.4 claimed). At desktop scale,
storage is not the blocker. The real trade-off is quality (cross-encoder wins
by ~5-10 nDCG) vs latency (ColBERT MaxSim is sub-ms vs 373ms for cross-
encoder). The "Balancing the Blend" paper shows ColBERT TRF reranking adds
+0.014 nDCG on SciFact (0.748 → 0.762), confirming measurable but modest
quality gains. For desktop UX where perceived latency matters, ColBERT
reranking on top-100 may be preferable to a cross-encoder on top-20,
especially if the cross-encoder already exceeds its latency budget.

**Q5 (LLM query expansion):** Not directly addressed by Infinity, but the BMP
beta parameter data provides an indirect answer: SPLADE's learned query
expansion already generates 50-200 terms, and pruning the bottom 50% actually
**improves** precision. This suggests the bottleneck is not query recall
(which expansion addresses) but query precision — adding more terms adds
noise. LLM query expansion may face the same problem unless carefully
constrained.

**Q9 (GPL-trained LambdaMART):** The weakest-link phenomenon documented in
the "Balancing the Blend" paper provides additional context for LambdaMART's
failure: if LambdaMART's weights amplify a weak leg's contribution on certain
queries, it would actively hurt quality. The GPL synthetic queries may
produce weights that don't account for per-query leg strength variation.

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.

---

## milvus-findings

*(from `249-milvus-findings.md`)*

## Recon Summary

**JustSearch baseline loaded from:**
- `ReadPathOps.java` (lines 300-320) — filtered KNN via `KnnFloatVectorQuery`
- `LuceneIndexRuntime.java` (lines 1464-1500) — vector query K resolution
- `ComponentsFactory.java` (lines 152-205) — HNSW params (M=16, efConstruction=200),
  TieredMergePolicy (segsPerTier=15), SoftDeletesRetentionMergePolicy wrapper
- `JustSearchCodec.java` — Float32 and Int8 scalar quantized HNSW formats
- `HybridFusionUtils.java` (lines 22-297) — RRF (k=60) and convex combination fusion
- `HybridSearchOps.java` (lines 248-371) — parallel leg execution, low-signal gating

**External sources targeted:**
- Milvus official docs (milvus.io/docs), Zilliz blog + learn articles, GitHub
  discussions and issues, DeepWiki source analysis, arXiv 2602.11443 (filtered
  ANN systematic analysis), Knowhere GitHub issues, MEP 16 design proposal

**Skipped:** Nothing skipped — all four checklist items are well-covered by
available sources.

---

## Findings

### Filtered ANN strategies — Dual-Pool traversal with brute-force fallback

- **JustSearch baseline** — Always pre-filters via `KnnFloatVectorQuery(field,
  vec, k, filter)`. Lucene applies the filter before HNSW traversal, reducing the
  candidate set. No adaptive strategy: never switches to brute-force for narrow
  filters, no selectivity detection. `resolveVectorQueryK()` allows an ef_search
  override but does not adapt to filter selectivity.

- **How it works** — Milvus implements a multi-strategy approach to filtered ANN:

  1. **Bitset pre-filtering.** Boolean expressions are parsed into an AST,
     compiled to a physical plan, and executed per-segment to produce a bitset of
     matching entity IDs. The bitset is passed to the vector index. This is
     architecturally equivalent to Lucene's filter-then-KNN approach.

  2. **Dual-Pool HNSW traversal** (`NeighborSetDoublePopList`). Confirmed in
     Knowhere source (`thirdparty/faiss/faiss/cppcontrib/knowhere/impl/Neighbor.h`).
     Maintains two priority queues: `valid_ns_` for filter-matching results and
     `invalid_ns_` for navigation-only nodes. Each neighbor gets status `kValid`
     or `kInvalid`. The invalid pool is bounded by the worst valid distance —
     filtered-out nodes are only traversed if closer than the worst valid result.
     Pop selects from whichever pool has the nearest candidate. This prevents the
     search budget (`ef`) from being cannibalized by non-matching nodes. Standard
     HNSW wastes ef slots on filtered-out nodes, degrading recall under narrow
     filters. Additionally, `kAlpha = filter_ratio * 0.7` scales graph exploration
     aggressiveness based on filter selectivity.

  3. **Brute-force fallback.** When **>=93% of nodes** in a segment are filtered
     out (leaving <=7% valid), Milvus bypasses HNSW entirely and performs a
     sequential kNN scan over the filtered subset. The threshold is defined in
     `knowhere/src/index/hnsw/impl/IndexConditionalWrapper.h`:
     ```
     kHnswSearchKnnBFFilterThreshold = 0.93f
     kHnswSearchRangeBFFilterThreshold = 0.97f  (range queries)
     kHnswSearchBFTopkThreshold = 0.5f  (also triggers if k >= 50% of valid)
     ```
     Decision is per-segment, per-query (`WhetherPerformBruteForceSearch()` in
     `IndexConditionalWrapper.cc`). A **post-search fallback** also exists: if
     HNSW returns fewer than k valid results and enough valid vectors exist,
     brute-force runs as a safety net. GitHub issue #29723 requests making the
     threshold configurable — at 20M vectors, 7% = 1.4M brute-force, still slow.

  4. **Bitmap Index** (Milvus 2.5+). For low-cardinality scalar fields (<500
     distinct values), a bitmap index accelerates bitset generation. Achieves
     **6.8x speedup at 50% filtering** compared to no scalar index. Inverted
     index outperforms bitmap as cardinality increases.

  5. **Partition pruning.** Partition Key routes data by scalar field hash into
     physical partitions. Search restricts to matching partitions only. Partition
     Key Isolation creates a separate vector index per partition key value — the
     most aggressive form of scope restriction.

  6. **Iterative filtering.** Alternative mode for complex filter expressions.
     Runs vector search as an iterator, evaluating scalar predicates one entity at
     a time until topK results are collected. Designed for cases where bitset
     generation is expensive.

  Key insight from arXiv 2602.11443: "Algorithmic adaptations within the engine
  often override raw index performance" — the adaptive execution strategy matters
  more than the choice of index type. IVF indexes outperform HNSW in
  low-selectivity regimes due to efficient cluster pruning, but Milvus's
  Dual-Pool adaptation narrows the gap significantly.

- **Evidence** —
  - arXiv 2602.11443: Milvus achieves "superior recall stability at low
    selectivities" compared to standard implementations.
  - Knowhere source: `IndexConditionalWrapper.h` defines thresholds;
    `NeighborSetDoublePopList` in `Neighbor.h` implements dual-pool;
    `faiss_hnsw.cc` orchestrates the decision and post-search fallback.
  - Knowhere issue #256: At 1M vectors with ~96% filtered out (~40K valid),
    brute-force becomes faster than HNSW. Root cause: HNSW does random memory
    access; brute-force does sequential access.
  - Milvus 2.5 blog: Bitmap index achieves 6.8x speedup at 50% filtering ratio.
  - GitHub issue #29723: 93% threshold doesn't scale — at 20M vectors, 7% = 1.4M
    brute-force, still slow.

- **Applicability** — **Needs adaptation.** The Dual-Pool concept is not directly
  implementable in Lucene — `KnnFloatVectorQuery` is a sealed API that doesn't
  expose the HNSW traversal internals. However, two patterns are transferable:

  1. **Brute-force fallback with selectivity detection.** Before running
     `KnnFloatVectorQuery`, estimate filter selectivity (e.g., via
     `IndexSearcher.count(filter)` or Lucene's `Weight.count()`). If below a
     threshold, switch to exact kNN over the filtered doc set. This is a
     straightforward change in `ReadPathOps.java`.

  2. **Oversampling for narrow filters.** When filter selectivity is moderate
     (10-50%), increase the query K (already supported via `vectorEfSearchOverride`)
     to compensate for filtered-out candidates in the HNSW graph. Qdrant's
     findings (see `249-qdrant-findings.md`) confirm this pattern: retrieve
     N*factor candidates, then rescore top-K.

- **Adoption cost** — Low-medium. Changes to `ReadPathOps.java` (add selectivity
  check before KNN query construction) and potentially `LuceneIndexRuntime.java`
  (exact kNN fallback path). No schema changes. No index rebuild required.

- **Expected impact** — Improved recall for filtered searches with narrow filters
  (e.g., searching within a specific folder when most documents are elsewhere).
  Quantification requires benchmarking with JustSearch's actual filter patterns.

---

### DiskANN — Vamana graph + PQ on SSD for billion-scale indexes

- **JustSearch baseline** — HNSW only, stored via Lucene's `Lucene99HnswVectorsFormat`
  (or `Lucene99HnswScalarQuantizedVectorsFormat` for Int8). MMapDirectory for
  file access. No disk-based vector index. At <1M docs on desktop hardware with
  16-32GB RAM, the entire HNSW index fits comfortably in memory.

- **How it works** — Milvus implements DiskANN through the Knowhere library (not a
  direct fork of Microsoft's DiskANN). Core algorithm:

  1. **Vamana graph construction.** Single-layer graph (unlike HNSW's multi-layer).
     Two-pass construction: alpha=1.0 then alpha=1.2. Uses medoid (most central
     vector) as search entry point. Greedy search + robust prune heuristic ensures
     exponentially decreasing distances between consecutive node neighbors.

  2. **Product Quantization (PQ) in RAM.** Full-precision vectors on SSD, PQ
     compressed vectors in RAM (controlled by `PQCodeBudgetGBRatio`, default
     12.5% of data size). PQ provides approximate distance for initial candidate
     filtering; SSD reads provide exact distances for top candidates.

  3. **SSD layout.** Each node is a fixed-size data structure (embedding +
     neighbor list colocated). Addressed by `node_index * struct_size`. One SSD
     I/O per node visit. NVMe strongly recommended.

  4. **Search.** Beam search with configurable width
     (`BeamWidthRatio * CPU_cores`). Cache hotspot: nodes within C hops of medoid
     (C=3-4 recommended). `search_list` parameter (default 100) controls
     candidate list size.

  Parameters: MaxDegree=56, SearchListSize=100, PQCodeBudgetGBRatio=0.125,
  SearchCacheBudgetGBRatio=0.10.

  Update handling: Vamana graph is immutable. Milvus handles dynamism at the
  segment level (LSM-tree model): new data goes to growing segments with
  brute-force search, sealed segments get DiskANN built asynchronously. Deletes
  are soft-delete + compaction rebuild. Milvus does NOT use FreshDiskANN.

  **AISAQ** (Milvus 2.6.4+, Dec 2025): Eliminates PQ from RAM entirely — 3200x
  memory reduction vs DiskANN for 1B vectors (32GB -> ~10MB). Two modes:
  Performance (redundant PQ on disk, single I/O) and Scale (compact storage,
  more I/Os). Developed by KIOXIA.

- **Evidence** —
  - Zilliz benchmark (SIFT-1B, 64GB RAM, 16-core): 5,000 QPS at >95% recall@1,
    <3ms average latency.
  - Build time: 1M vectors in 129s (Vamana) vs 219s (HNSW). 1B vectors: ~5 days.
  - Memory: Only PQ codes in RAM (~12.5% of data) vs HNSW requiring full index.
  - Vectroid comparison: HNSW sub-ms latency vs DiskANN 2-5ms. HNSW >99% recall
    easy vs DiskANN 95%+ with tuning.

- **Applicability** — **Not applicable at current scale.** JustSearch targets <1M
  documents on desktop hardware with 16-32GB RAM. At 768 dimensions with Int8
  quantization, 1M vectors = ~768 MB — well within RAM. DiskANN's value starts
  at 100M+ vectors where RAM is insufficient.

  Lucene does not support DiskANN natively. Implementing it would require either
  a custom Lucene codec or an external index alongside Lucene — both are high
  effort with no benefit at desktop scale.

  The AISAQ evolution (near-zero RAM for billions of vectors) is worth tracking
  as a future reference if JustSearch ever expands to server-side deployments or
  enterprise-scale indexes.

- **Adoption cost** — N/A (not recommended for adoption).

- **Expected impact** — N/A at desktop scale. At 100M+ vectors, DiskANN would
  reduce RAM from ~75 GB (HNSW) to ~9.4 GB (PQ in RAM) at the cost of 2-5ms
  latency vs sub-ms.

---

### Hybrid fusion — RRF + WeightedRanker with arctan normalization

- **JustSearch baseline** — Two fusion modes:
  1. **RRF** (default): `score = sum(1/(k + rank))`, k=60. Low-signal gating
     down-weights vector leg when top vector score < 0.40. Vector-only document
     capping prevents semantic noise. BM25 score boost optional.
  2. **Convex Combination (CC)**: `score = alpha * norm_dense + (1-alpha) * norm_sparse`
     with min-max normalization per leg to [0,1]. Configurable alpha.

  Both modes execute BM25 and vector legs in parallel via virtual threads.
  Candidate limiting via multipliers (default 10x, max 100).

- **How it works** — Milvus provides two built-in rerankers, no others:

  1. **RRFRanker.** Formula: `score(d) = SUM_i(1 / (k + rank_i(d)))` where rank
     is 1-indexed. k default = 60, valid range (0, 16384), recommended [10, 100].
     Ignores raw scores entirely — rank-based only. Results appearing in only some
     legs receive scores only from those legs (missing legs contribute zero).
     Implementation in `internal/proxy/reScorer.go`.

  2. **WeightedRanker.** Formula: `score(d) = SUM_i(weight_i * normalize(distance_i(d)))`.
     Metric-specific normalization to [0,1] (confirmed from `reScorer.go`):
     - COSINE: `(1 + distance) * 0.5` — linear rescaling, [-1,1] → [0,1]
     - IP: `0.5 + arctan(distance) / PI` — maps (-inf,+inf) → (0,1)
     - L2: `1.0 - 2 * arctan(distance) / PI` — maps [0,+inf) → (0,1], inverted
     - BM25: `2 * arctan(distance) / PI` — differs from IP (no +0.5 offset,
       since BM25 scores are non-negative), maps [0,+inf) → [0,1)

     Weights must be in [0,1], specified per search path. No defaults — user must
     provide weights. As of Milvus 2.5 (PR #40905), `norm_score=false` skips
     normalization entirely (activation function becomes identity).

  **Known issue:** Arctan normalization can cause **ranking inversions** — items
  A and B can swap ranking positions after normalization + fusion (GitHub issue
  #40836). Root cause: arctan is **concave** for positive inputs (derivative
  `1/(1+x^2)` decreases as x grows). By Jensen's inequality, concave functions
  penalize score spread — an item with one strong leg and one weak leg gets a
  lower normalized sum than an item with uniform moderate scores, even when the
  raw weighted sum favors the first item. Concrete example from the issue:
  A (0.1, 0.9) = 0.500 raw → 0.6325 arctan; B (0.39, 0.6) = 0.495 raw →
  0.6452 arctan — **inverted**.

  **BM25 integration (Milvus 2.5+ Sparse-BM25):** Tantivy provides tokenization
  only (stemming, stop-words, language-specific processing). BM25 scores are
  represented as sparse vectors stored in a `SPARSE_INVERTED_INDEX`. At query
  time, text is tokenized into a sparse query vector (IDF-weighted), scored via
  inner product against document sparse vectors (TF values). This design reuses
  Milvus's sparse vector infrastructure (WAND, PQ, SQ) rather than maintaining a
  separate inverted index. Heuristic-based pruning discards low-value entries.

  **Architecture:** Multi-route retrieval + post-fusion. Each search modality
  runs as an independent `AnnSearchRequest` against its own vector field/index.
  Up to 10 vector fields per collection (default 4). Results merged in proxy
  layer. No integrated scoring during retrieval — fusion is strictly
  post-retrieval.

  **No learned reranking.** No cross-encoder, no LambdaMART, no custom reranker
  plugin mechanism. Only RRF and WeightedRanker.

- **Evidence** —
  - Source code: `internal/proxy/reScorer.go` — complete RRF + Weighted
    implementation.
  - GitHub issue #40836: concrete example of arctan ranking inversion.
  - GitHub PR #40905: `normalize=false` fix merged April 2025.
  - Zilliz blog: architecture overview confirms post-retrieval-only fusion.

- **Applicability** — **Partially applicable.** Two findings are actionable:

  1. **Arctan normalization as alternative to min-max.** JustSearch's CC mode uses
     min-max normalization which is sensitive to outliers (one extreme score
     compresses all others). Arctan is globally stable (same mapping regardless of
     result set distribution) but compresses high scores nonlinearly — the ranking
     inversion issue shows this can hurt. Worth benchmarking both on JustSearch's
     eval datasets.

  2. **The ranking inversion bug illuminates a key tradeoff.** Both min-max and
     arctan preserve within-leg ordering (both are monotonic). The inversion
     happens post-fusion because arctan nonlinearly compresses *relative gaps*
     between scores. Min-max is a linear rescaling, so it preserves the ratio of
     score differences and cannot cause post-fusion inversions within a single
     leg's contribution. However, min-max is batch-dependent (min/max come from
     the result set) while arctan is query-independent. The practical tradeoff:

     | Property | min-max (JustSearch CC) | arctan (Milvus) |
     |----------|------------------------|-----------------|
     | Gap preservation | Linear (exact) | Nonlinear (concave compression) |
     | Outlier sensitivity | High (one extreme score dominates) | Low (saturates) |
     | Post-fusion inversion | No (within single leg) | Yes (Jensen's inequality) |
     | Query independence | No (depends on result set) | Yes (fixed mapping) |

  The BM25-as-sparse-vectors design is architecturally interesting but not
  transferable — JustSearch benefits from Lucene's native inverted index with
  BM25 scoring, which is more mature and efficient for keyword search.

- **Adoption cost** — Low. Adding arctan normalization as an alternative to
  min-max in `HybridFusionUtils.java` would be ~20 lines of code. Benchmarking
  against min-max on existing eval datasets is the main cost.

- **Expected impact** — Uncertain without benchmarking. Arctan may improve
  stability for queries with score outliers; may hurt for queries with tightly
  clustered scores. The OpenSearch finding (min-max + arithmetic mean beats RRF
  by +4.5-7.8% nDCG@10 — see `249-opensearch-findings.md`) suggests min-max is
  the better normalization for JustSearch's use case.

---

### Segment compaction — LSM-tree model with three compaction types

- **JustSearch baseline** — Lucene's `TieredMergePolicy` with segsPerTier=15
  (50% above Lucene default of 10, reducing merge frequency by ~15%). Wrapped in
  `SoftDeletesRetentionMergePolicy` for configurable deletion retention. No
  custom segment handling. Lucene handles segment merging automatically based on
  segment count and size tiers.

- **How it works** — Milvus uses an LSM-tree-inspired segment architecture with
  three compaction types:

  1. **L0 Compaction** (delete propagation). L0 segments are deletion-only: they
     buffer streaming deletes in memory, flush to blob storage by size. L0
     compaction propagates delete records into L1+ segment deltalogs. This
     decouples streaming deletes from batch data — batch segments are immutable
     except through compaction. No concurrent compaction within a channel during
     L0 operations.

  2. **MixCompaction** (minor/major merge). Standard segment merge. Combines
     multiple small sealed segments into larger ones. Triggered when:
     - Count of "small segments" (size < `maxSize * smallProportion`) exceeds a
       threshold (default 10 segments)
     - \>20% of rows in a segment are deleted
     - Delta binlogs exceed 10MB
     - Time since last compaction exceeds `max_compaction_interval`
     Configuration: maxSize=512MB-1024MB (version-dependent),
     smallProportion=0.5, compactableProportion=0.85. Major compaction
     additionally purges deleted records and triggers index rebuilds.

  3. **Clustering Compaction** (Milvus 2.4.7+). Redistributes entities across
     segments based on a scalar clustering key. Generates a **PartitionStats**
     global index mapping segments to key value ranges. Enables query-time segment
     pruning — queries with predicates on the clustering key skip irrelevant
     segments entirely. Benchmark: **24x QPS improvement** on 20M-row, 768-dim
     LAION dataset with 99% segment pruning on point queries. Configurable
     trigger intervals (600s check, 3600s min, 72h forced).

  **Segment lifecycle states:** Growing (accepting inserts, in RAM) → Sealed
  (size/time threshold reached, no writes) → Flushed (persisted to object
  storage as columnar binlogs) → Indexed (vector index built by IndexNode) →
  Compacted (merged) → Dropped (GC'd).

  **Index interaction:** After compaction, IndexNode rebuilds vector indexes for
  new merged segments. `indexBasedCompaction=true` (default) restricts compaction
  to already-indexed segments, avoiding redundant work. Sealed segments remain
  queryable via brute-force while indexes build.

- **Evidence** —
  - MEP 16 design proposal: original compaction rationale and trigger thresholds.
  - GitHub discussion #28565: concrete parameter values confirmed from source.
  - GitHub issue #27349: L0 segment architecture design document.
  - GitHub `docs/user_guides/clustering_compaction.md`: 24x QPS benchmark.

- **Applicability** — **Not directly applicable.** Milvus's LSM-tree segment
  model is fundamentally different from Lucene's. Key differences:

  | Aspect | Milvus | Lucene (JustSearch) |
  |--------|--------|---------------------|
  | Segment model | Immutable + append-only | Immutable + merge |
  | Delete handling | L0 segments + deltalogs | Soft deletes in live bits |
  | Merge trigger | DataCoord policies + thresholds | TieredMergePolicy automatic |
  | Clustering | Explicit clustering compaction | No equivalent |
  | Index rebuild | Separate IndexNode rebuilds | Merged in-process |

  The **Clustering Compaction** concept is the most interesting pattern. At
  desktop scale with a single Lucene index, the equivalent would be Lucene's
  `SortingMergePolicy` (sort segments by a field) combined with index-time
  routing. However, Lucene's `IndexSearcher` doesn't support segment-level
  pruning based on value ranges in the same way Milvus's PartitionStats does.
  Lucene 9.4+ has `PointValues`-based segment pruning for numeric ranges, but
  this is limited to point fields and doesn't extend to arbitrary scalar
  predicates.

- **Adoption cost** — N/A for direct adoption. The clustering compaction pattern
  could inspire a future optimization where JustSearch sorts segments by
  folder path (most common filter dimension), enabling Lucene to skip segments
  during filtered search. This would require investigation into Lucene's
  `SortingMergePolicy` and `NumericDocValuesField`-based segment pruning —
  estimated as a medium-effort research task.

- **Expected impact** — N/A at current scale. Lucene's TieredMergePolicy is
  well-suited for JustSearch's single-node, <1M document workload. The 24x QPS
  improvement Milvus reports is for point queries on a 20M-row distributed
  system — not comparable to JustSearch's scale.

---

## Additional Findings

### BM25 as sparse vectors (Sparse-BM25 architecture)

Milvus 2.5 represents BM25 term frequencies as sparse vectors rather than using
a traditional inverted index. Tantivy provides tokenization only. This design
reuses sparse vector infrastructure (WAND, PQ, SQ) and enables BM25 to
participate in hybrid search as just another vector field. The tradeoff: less
mature than dedicated inverted index implementations (Lucene, Elasticsearch) and
requires maintaining global term distribution statistics. Not transferable to
JustSearch (Lucene's native BM25 is more efficient), but an interesting
architectural pattern for systems that want to unify all retrieval under a
vector abstraction.

### AISAQ — next-generation disk index (Milvus 2.6.4+)

AISAQ eliminates PQ codes from DRAM entirely — 3200x memory reduction vs DiskANN
for 1B vectors (32GB -> ~10MB). Two modes: Performance (redundant PQ on disk,
single I/O per node, comparable latency to DiskANN) and Scale (compact storage,
more I/Os). Developed by KIOXIA. Depends heavily on SSD IOPS. Worth tracking if
JustSearch ever needs server-side or enterprise-scale deployments.

### IVF outperforms HNSW under aggressive filtering

arXiv 2602.11443 finds that IVF (Inverted File Index) often outperforms HNSW in
low-selectivity regimes (filters eliminating >90% of candidates). IVF's
two-level structure (coarse-grained centroid pruning + fine-grained distance
within clusters) naturally handles filter-induced data sparsity better than
HNSW's graph traversal. Lucene does not support IVF natively, but this finding
validates the brute-force fallback approach: when HNSW can't navigate efficiently
due to narrow filters, a simpler sequential scan is better.

---

## Unresolved Items

None — all four checklist items were answerable from documentation, source code
references, GitHub discussions, and the academic paper. No items require running
Milvus or benchmarking to resolve.

---

## Cross-Cutting Observations

### Q2: Best score normalization for hybrid fusion

Three normalization approaches now compared across investigations:

| Aspect | OpenSearch | Pyserini | Milvus |
|--------|-----------|----------|--------|
| **Normalization** | min-max to [0,1] | min-max to [0,1] (rank-equivalent variants exist) | arctan (metric-specific) |
| **Fusion** | Weighted arithmetic mean | Convex combination | Weighted sum |
| **Missing-doc handling** | Zero-exclusion (divides by 1, not N) | Missing = 0.0 | Missing = 0.0 |
| **nDCG over RRF** | +4.5% avg, up to +7.8% (3 independent sources) | Rank-equivalent to OpenSearch | No quantified claims |
| **Outlier sensitivity** | High | High | Low (arctan saturates) |
| **Known issues** | None | Div-by-zero unguarded (JustSearch guards this) | Ranking inversions (#40836) |

**Recommendation:** JustSearch should prioritize **min-max + weighted arithmetic
mean with zero-exclusion** (the OpenSearch approach). JustSearch's existing CC
mode is already close — the gap is adding zero-exclusion logic and testing the
arithmetic mean combination. Arctan is a low-cost secondary benchmark (~20 lines)
but has weaker evidence and the concavity-induced inversion risk. Bruch et al.
(TOIS 2024) proves that the normalization formula itself matters less than the
alpha weight — **alpha calibration on BEIR datasets is the highest-value next
step**, regardless of normalization choice.

### Q6: Filtered ANN strategy at desktop scale (100K-1M docs)

Milvus's key insight: the **brute-force fallback threshold** is the critical
design parameter. Source-verified: Knowhere uses `kHnswSearchKnnBFFilterThreshold
= 0.93` (93% filtered out = 7% remaining). At a 1M-entity segment, 7% = 70K
vectors for brute-force — fast on modern hardware. A secondary trigger fires if
`k >= 50% of remaining valid vectors`. Both decisions are per-segment, per-query.

JustSearch's documents are typically <1M, so the entire index is one or a few
Lucene segments. A simple selectivity check (estimate filtered doc count, fall
back to exact kNN if below threshold) would capture most of the benefit without
Milvus's Dual-Pool complexity. The Dual-Pool traversal is not implementable in
Lucene (`KnnFloatVectorQuery` doesn't expose HNSW internals), but the fallback
pattern is straightforward.

Concrete proposal: In `ReadPathOps.java`, before constructing
`KnnFloatVectorQuery`, run `IndexSearcher.count(filter)`. If
`count < threshold` (e.g., 1000 docs or < 7% of total docs, mirroring Milvus's
93% threshold), use exact kNN over the filtered set instead of HNSW. This is
architecturally simpler than Milvus's approach but addresses the same
fundamental problem. Also add the secondary trigger: if `k >= count * 0.5`,
brute-force is more efficient regardless of the absolute count.

### Q1: FeatureField precision (tangential)

Milvus stores sparse vectors (including SPLADE-equivalent) as native float32
sparse vectors with no precision limitation. This contrasts with Lucene's
FeatureField 8-bit mantissa. However, Milvus's approach is not transferable
since JustSearch is committed to Lucene. The OpenSearch finding (FeatureField
precision is accepted, not solved) remains the most relevant data point.

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.

---

## opensearch-findings

*(from `249-opensearch-findings.md`)*

## Recon Summary

### JustSearch baseline consulted

- **Canonical docs:** `docs/llms.txt` → `docs/explanation/01-system-overview.md` (architecture),
  `docs/tempdocs/245-search-quality-strategy.md` (eval results, known gaps),
  `docs/tempdocs/248-technology-landscape-scan.md` (landscape context)
- **Source code (for details not in docs):**
  - `FieldMapper.java` lines ~249-257 — FeatureField indexing of SPLADE weights
  - `LuceneIndexRuntime.java` lines 1520-1546 — SPLADE query execution via
    `FeatureField.newLinearQuery` SHOULD clauses
  - `HybridFusionUtils.java` — RRF fusion algorithm (k=60, vectorWeight=0.75,
    bm25ScoreBoostWeight=0.002)
  - `HybridSearchOps.java` — low-signal gating, vector-skip heuristic, over-retrieval
    multipliers (10x both legs)
  - `SpladeEncoder.java` — naver/splade-v3 ONNX, `max(log1p(ReLU(logits)))` activation,
    256-token context, no top-K sparsification
  - `SearchOrchestrator.java` — flat procedural search routing (no pipeline abstraction)

### JustSearch baseline summary

| Feature | JustSearch current state |
|---------|------------------------|
| SPLADE storage | `FeatureField("splade", token, weight)` per non-zero entry. No top-K pruning. |
| SPLADE query | `FeatureField.newLinearQuery` SHOULD clauses in BooleanQuery |
| SPLADE model | naver/splade-v3, BERT-base, shared doc/query encoder, 256-token context |
| Hybrid fusion | RRF (k=60, vectorWeight=0.75) + 0.002 BM25 boost tiebreaker. No score normalization. |
| Score normalization | None. RRF discards score magnitude. Known gap (tempdoc 245/248). |
| Search pipeline | No pipeline abstraction in execution path. Flat `switch` in `SearchOrchestrator`. `search.v1.json` is telemetry-only. Pipeline engine removed (ADR 0007). |
| Post-retrieval | LambdaMART (hurts quality with GPL data), cross-encoder (TEXT mode only, not in HYBRID), QPP signals (computed but unused) |

### External sources targeted

Recon identified these sources per checklist item:

**Q1: FeatureField usage / 8-bit mantissa precision**
- `NeuralSparseQueryBuilder.java` (neural-search plugin) — builds `FeatureField.newLinearQuery` SHOULD clauses, identical to JustSearch
- `SparseVectorFieldMapper.java` (OpenSearch 3.3+) — new `sparse_vector` field type with SEISMIC codec
- `ByteQuantizer.java` — explicit 8-bit byte quantization for SEISMIC storage
- Lucene issue #11799 — committer Adrien Grand confirms FeatureField as correct approach
- Neural-search RFC #284 — `max_token_score` workaround for WAND upper bound, acknowledges 8-bit mantissa is "acceptable for now"

**Q2: Neural sparse ANN**
- OpenSearch docs: "Neural sparse ANN search" — SEISMIC algorithm overview
- OpenSearch blog: "Scaling neural sparse search to billions" — benchmarks (11.77ms at 90% recall)
- `SeismicBaseScorer.java` — cluster-summary gating + heap-factor pruning implementation
- `SeismicPostingClusterer.java` — clustering algorithm for posting lists
- Neural-search RFC #646 — earlier two-phase processor approach (pre-SEISMIC)

**Q3: Search Pipelines architecture**
- OpenSearch docs: "Search pipelines" — 3 processor types (request, phase results, response)
- OpenSearch docs: "Debugging a search pipeline" — `verbose_pipeline=true` per-request timing
- OpenSearch docs: "Search pipeline metrics" — node-level aggregate stats
- OpenSearch RFC #16705 + PR #17559 — phase results processor stats (draft)

**Q4: Score normalization in hybrid queries**
- `RRFNormalizationTechnique.java` — rank-based fusion (k default 60)
- `MinMaxScoreNormalizationTechnique.java` — per-query min-max normalization
- `ArithmeticMeanScoreCombinationTechnique.java` — weighted arithmetic mean combination
- OpenSearch docs: "Normalization processor" — min_max, l2, z_score normalization + 3 combination methods
- AWS Big Data Blog: "Hybrid Search with OpenSearch" — min_max + arithmetic_mean recommended
- o19s hybrid search optimization (GitHub) — independent validation

**Q5: Sparse model v2 improvements**
- HuggingFace: `opensearch-neural-sparse-encoding-v2-distill` model card — full BEIR numbers
- OpenSearch blog: "Improving search efficiency with v2 models" — v1→v2 comparison
- arXiv 2411.04403 — academic paper backing v2 training methodology

### Items unlikely to be answerable

None — all 5 checklist items have strong source material identified during recon.

### Suggested skips

None — all items are directly relevant to JustSearch's current architecture.

---

## Findings

### Q1: FeatureField encoding — OpenSearch confirms the 8-bit mantissa is accepted, not solved

- **JustSearch baseline** — Stores SPLADE weights in `FeatureField("splade", token, weight)`
  using `FeatureField.newLinearQuery` for queries. No awareness of or workaround for the
  8-bit mantissa precision limitation inherent to FeatureField's internal encoding (~0.4%
  relative precision). No top-K pruning of sparse vectors.

- **How it works** — OpenSearch has **two code paths**:

  **Legacy path (OpenSearch 2.x–3.x, `rank_features` field type):** Identical to JustSearch.
  `NeuralSparseQueryBuilder` builds a `BooleanQuery` with `FeatureField.newLinearQuery`
  SHOULD clauses per token. The 8-bit mantissa limitation is inherited directly from Lucene's
  `FeatureField` encoding (float → 16-bit normalized value with 8-bit mantissa). OpenSearch's
  RFC #284 explicitly states this is "acceptable for now if there is no easy fix." Their
  workaround is for a *different* problem: `max_token_score` re-enables WAND pruning by
  providing a realistic upper bound (instead of `Float.MAX_VALUE`), yielding ~4x latency
  reduction with <0.1% quality loss. This addresses query speed, not storage precision.

  **SEISMIC path (OpenSearch 3.3+, `sparse_vector` field type):** The new `SparseVectorFieldMapper`
  still writes `FeatureField` entries (for Lucene postings compatibility), but additionally
  stores weights in a custom binary format using explicit 8-bit byte quantization:
  `quantized = round(weight * 255 / ceiling)`. This is intentionally *lower* precision than
  FeatureField (8-bit integer vs 8-bit mantissa float), traded for faster cluster-level dot
  products in the SEISMIC algorithm. The `quantization_ceiling_ingest` parameter controls the
  scaling.

- **Evidence**
  - Lucene issue #11799: committer Adrien Grand confirms FeatureField is the proper approach
    for SPLADE weights (validates JustSearch's architecture)
  - Neural-search RFC #284: "acceptable for now" on 8-bit mantissa
  - `ByteQuantizer.java`: `round(weight * 255 / ceiling)` — explicit 8-bit integer quantization
  - FeatureField Javadoc: "uses 9 significant bits" (1 sign + 8 mantissa) for the stored value
  - Lucene `FeatureField.setFeatureValue()` source: accepts any positive finite float — no
    upper bound at 64 or elsewhere. The (0, 64] range mentioned in some SPLADE literature
    refers to typical weight distributions, not a FeatureField storage limit.

- **Weight range analysis (§7 Q1 follow-up):** JustSearch has **no clamping** anywhere in
  the SPLADE pipeline. `SpladeEncoder.postProcess()` stores `log1p(ReLU(logit))` as-is
  (only filtering zeros and special tokens). `FieldMapper` passes weights directly to
  `new FeatureField()` with no validation. Lucene's `FeatureField` would throw only for
  non-finite or sub-normal floats — any positive normal float is accepted. In practice,
  SPLADE-v3's `log1p` activation naturally limits weights: typical range is 0.0-2.5, with
  rare extremes around 4-5. The "wacky weights" concern (arxiv 2110.11540) was about early
  SPLADE versions with unconstrained activations — SPLADE-v3's `log1p` provides implicit
  soft clamping. No defensive clamping is needed, but the pipeline would crash at index time
  if ONNX ever produced `Inf` (extremely unlikely).

- **Applicability** — **Direct validation.** JustSearch's FeatureField approach is confirmed as
  correct by both Lucene committers and OpenSearch's production deployment. The 8-bit mantissa
  is a known, accepted limitation — OpenSearch chose not to work around it either. The
  `max_token_score` optimization (WAND pruning) is applicable if query latency becomes a concern.
  The weight range concern from §7 Q1 is resolved: SPLADE-v3 weights are well within
  FeatureField's valid range, and OpenSearch operates with the same unclamped approach.

- **Adoption cost**
  - `max_token_score` WAND optimization: modify `LuceneIndexRuntime.searchSplade()` to set
    a realistic score upper bound on `FeatureField.newLinearQuery`. Requires profiling SPLADE
    weight distributions to set the bound. ~1 file, low effort.
  - SEISMIC-style quantization: not applicable at desktop scale (designed for billions of docs).

- **Expected impact**
  - `max_token_score`: up to ~4x SPLADE query latency reduction (OpenSearch's measurement),
    with <0.1% quality loss. JustSearch's current SPLADE queries are not latency-critical
    at desktop scale, so this is a future optimization, not urgent.
  - Precision: no action needed. The 8-bit mantissa is acceptable — OpenSearch's billion-doc
    production deployment validates this.

### Q2: Neural sparse ANN — SEISMIC is impressive but not applicable at desktop scale

- **JustSearch baseline** — SPLADE queries use exact exhaustive search via BooleanQuery with
  SHOULD clauses. No approximation. At desktop scale (100K-1M docs), this is fast enough.

- **How it works** — OpenSearch 3.3 ships **SEISMIC** (Spilled Clustering of Inverted Lists
  with Summaries for Maximum Inner Product Search):

  1. **Index time:** Each token's posting list is partitioned into clusters of ~`n_postings`
     documents. Per-cluster summary vectors are computed (top tokens retained per
     `summary_prune_ratio`). Weights are byte-quantized.

  2. **Query time:** For each query token, the scorer computes `cluster_summary.dotProduct(queryVector)`.
     Clusters scoring below `min_heap_score / heap_factor` are skipped entirely. A min-heap of
     size K tracks the best results, and its minimum score dynamically raises the threshold as
     better results are found.

  3. **Hybrid behavior:** Segments below `approximate_threshold` docs use exact search (standard
     FeatureField). Segments above use the SEISMIC codec. Per-segment, not per-index.

  OpenSearch also has an older **two-phase processor** (from 2.15): splits query tokens by
  `prune_ratio` (default 0.4) into high-weight (phase 1, retrieval) and low-weight (phase 2,
  rescore over `requestSize * expansion_rate` window). Measured 28-60% speedup, <0.04% nDCG loss.

- **Evidence**
  - SEISMIC benchmarks: 11.77ms avg at 90% recall (vs 125ms exact, vs 41ms BM25)
  - Two-phase processor: 27.9% speedup (doc-only), 59.6% (bi-encoder), <0.04% nDCG loss on BEIR
  - Source: `SeismicBaseScorer.java`, RFC #646, OpenSearch blog

- **Applicability** — **Not applicable at desktop scale.** SEISMIC is designed for billions of
  documents where exact search is prohibitively slow. At JustSearch's target of 100K-1M docs,
  exact SPLADE search completes in single-digit milliseconds. The two-phase processor is
  marginally interesting as a latency optimization pattern but not needed.

  **One transferable idea:** the two-phase token pruning concept (high-weight tokens for
  retrieval, low-weight for rescore) could reduce BooleanQuery clause count if SPLADE queries
  ever become latency-sensitive. This is a simple optimization: sort tokens by weight, take
  top-N for phase 1, rescore with the rest.

- **Adoption cost** — N/A for SEISMIC. Two-phase pruning: modify `LuceneIndexRuntime.searchSplade()`
  to split query tokens by weight threshold. ~1 file, low effort, but no demonstrated need.

- **Expected impact** — No measurable impact at current scale. File for future reference if
  corpus sizes grow to 10M+ docs.

### Q3: Search Pipelines — a clean processor composition model JustSearch lacks

- **JustSearch baseline** — No pipeline abstraction in the search execution path. Search mode
  routing is a flat `switch` in `SearchOrchestrator.execute()`. Post-retrieval steps (LambdaMART,
  cross-encoder, QPP) are wired ad-hoc with mode-specific conditionals. The `search.v1.json`
  pipeline definition exists but is telemetry-only — the pipeline engine was removed (ADR 0007).
  Architectural constraint: SPLADE runs only in HYBRID mode, cross-encoder only in TEXT mode;
  they cannot be composed.

- **How it works** — OpenSearch Search Pipelines (GA in 2.10+) define three processor types:

  1. **Request processors** — modify the `SearchRequest` before it hits shards. Example:
     `neural_sparse_two_phase_processor` rewrites query tokens into high/low sets.
  2. **Phase results processors** — run at the coordinator between query and fetch phases.
     Example: `normalization-processor` normalizes and combines scores from multiple sub-queries.
  3. **Response processors** — transform the final `SearchResponse`. Example: `rename_field`,
     `truncate_hits`.

  Execution is sequential within each processor list, all on the coordinator node:
  ```
  Request → [RequestProcessors] → shard query → [PhaseResultsProcessors] → fetch → [ResponseProcessors] → Response
  ```

  Pipelines are applied per-request (`?search_pipeline=my_pipeline`) or as index defaults.
  They can be created, updated, and deleted at runtime via REST API.

  **Observability:** Two mechanisms:
  - `?verbose_pipeline=true` on any request → per-processor `duration_millis`, status,
    input/output data in the response
  - `GET /_nodes/stats/search_pipeline` → cumulative count/time/failed per processor type

- **Evidence**
  - OpenSearch docs: search pipelines, normalization processor, debugging
  - RFC #16705 + PR #17559: phase results processor stats
  - The `normalization-processor` is the key piece — it's how hybrid score fusion is expressed
    as a composable pipeline step rather than hardcoded logic

- **Applicability** — **Needs adaptation.** JustSearch's single-process architecture doesn't
  need coordinator/shard separation, but the *compositional model* is valuable:
  - Expressing hybrid fusion as a pluggable processor (instead of hardcoded `fuseWithRRF`)
    would make it easy to swap RRF for score-normalized fusion (see Q4)
  - Per-processor timing (`verbose_pipeline`) would replace the current ad-hoc debug scores
    that get overwritten by chunk merge
  - Runtime-configurable pipelines would enable A/B testing different fusion strategies without
    code changes

  The three-phase model (request → phase results → response) maps to JustSearch's architecture
  as: query rewriting → score fusion/reranking → response formatting.

- **Adoption cost** — This is an architectural change, not a quick win. Introducing a pipeline
  abstraction into `SearchOrchestrator` would touch `SearchOrchestrator.java`,
  `HybridSearchOps.java`, `HybridFusionUtils.java`, and the configuration layer. Estimated
  effort: medium (1-2 weeks). The pipeline *engine* was already removed for good reason
  (over-engineered DAG framework) — what's needed is a simpler sequential processor chain,
  not a DAG.

- **Expected impact** — No direct quality improvement. Enables faster iteration on fusion
  strategies (which *do* have quality impact — see Q4). Fixes the debug score overwrite bug
  as a side effect (each processor owns its output, no overwrites).

### Q4: Score normalization — min-max + arithmetic mean beats RRF

- **JustSearch baseline** — Pure RRF with k=60. No score normalization. The only score-aware
  term is `bm25ScoreBoostWeight=0.002` (tiebreaker, not normalization). Known gap documented
  in tempdoc 245 and 248: "RRF with k=60 is suboptimal; score-normalized fusion achieves
  +4.5-7.8% nDCG@10 over k=60 RRF" (TopK.io study cited in 248).

- **How it works** — OpenSearch provides two separate hybrid fusion mechanisms:

  **Mechanism 1 — `normalization-processor` (score-based):**
  - Normalization: `min_max` (default), `l2`, or `z_score`
    - `min_max`: `(score - min) / (max - min)` per sub-query, optional configurable bounds
    - `l2`: normalizes by the L2 norm of the score vector (`score / sqrt(sum(score_i^2))`).
      Preserves relative score ratios better than min-max when score distributions are
      non-uniform. Less commonly recommended — min_max is the default for good reason.
    - `z_score`: only compatible with `arithmetic_mean` combination
  - Combination: `arithmetic_mean` (default), `geometric_mean`, or `harmonic_mean`
    - All support per-sub-query `weights` array (floats summing to 1.0)
    - `arithmetic_mean` excludes zero scores from its denominator (prevents dilution when a
      doc appears in only one leg)

  **Mechanism 2 — RRF processor (rank-based):**
  - Formula: `score = sum(1 / (rank_constant + rank_i))` per sub-query
  - `rank_constant` default 60 (valid 1-10,000). No per-sub-query weights.
  - Uses global cross-shard rank (not per-shard).

  **OpenSearch's recommendation:** `min_max + arithmetic_mean` with tuned weights produces
  the best results. RRF is "plug & play with no labeled data" but slightly less accurate
  than tuned score-based fusion.

  **Practical guidance from OpenSearch team:**
  - Use RRF when you have no labeled queries (zero-config baseline)
  - Use `min_max + arithmetic_mean` with tuned weights when you have an evaluation set
  - `geometric_mean` and `harmonic_mean` penalize results missing from one leg (zero kills
    the product/harmonic term) — only useful when all legs are expected to match every doc
  - Result window of 100-200 recommended for datasets up to 10M docs

- **Evidence**
  - AWS Big Data Blog: "Hybrid Search with OpenSearch" — min_max + arithmetic_mean recommended
  - OpenSearch docs: normalization processor parameter reference
  - Source: `MinMaxScoreNormalizationTechnique.java`, `ArithmeticMeanScoreCombinationTechnique.java`
  - Corroborates Pyserini finding (tempdoc 249 §5.1): convex combination outperforms RRF
    (Zhuang et al., TOIS 2023)
  - Corroborates TopK.io study (tempdoc 248): +4.5-7.8% nDCG@10 over k=60 RRF

- **Applicability** — **Directly applicable.** JustSearch already has the raw scores available
  in `HybridSearchOps.executeHybrid()` (both legs return scored results before RRF). Implementing
  min-max normalization + weighted arithmetic mean requires:
  1. After both legs return, compute per-leg min/max scores
  2. Normalize each score to [0, 1] via `(score - min) / (max - min)`
  3. Combine: `weight_text * norm_text + weight_vector * norm_vector`
  4. Exclude zero scores from denominator (handle docs appearing in only one leg)

  The `arithmetic_mean` zero-exclusion detail is important — it matches the reality that many
  docs will only appear in one of the two legs (text or vector).

- **Adoption cost** — Modify `HybridFusionUtils.java` to add a `fuseWithNormalizedScores()`
  method alongside the existing `fuseWithRRF()`. Add a config toggle to `ResolvedConfig.HybridSearch`.
  ~2 files, low-medium effort. Can coexist with RRF (config-selectable).

- **Expected impact** — **+4.5-7.8% nDCG@10** over k=60 RRF. Source attribution:
  - **+4.5% average, up to +7.8% on some datasets:** TopK.io study (cited in tempdoc 248 §4.2)
    comparing score-normalized fusion vs RRF k=60 across multiple BEIR datasets
  - **Convex combination outperforms RRF in-domain and out-of-domain:** Zhuang et al.
    (TOIS 2023), measured via Pyserini's `HybridSearcher` (cited in tempdoc 249 §5.1)
  - **min_max + arithmetic_mean recommended:** OpenSearch team (AWS Big Data Blog) — qualitative
    recommendation without published per-dataset nDCG tables

  Three independent sources converge on the same conclusion: score-based fusion beats RRF.
  This is the single highest-impact finding from this investigation. The weights need tuning
  (JustSearch has an eval harness from tempdoc 245 that can do this), but even default 0.5/0.5
  should improve over RRF k=60.

### Q5: Sparse model v2 — distillation halves parameters with equal quality

- **JustSearch baseline** — Uses naver/splade-v3 (BERT-base, ~110M params). Shared encoder
  for documents and queries. 256-token context. CPU + optional GPU inference.

- **How it works** — OpenSearch trained custom sparse models in two generations:

  **v1 (BERT-base, 133M params):**
  - Training: InfoNCE contrastive pretraining
  - BEIR nDCG@10: 0.524 (bi-encoder), FLOPS: 11.4
  - Also offered doc-only variant: document encoded at index time, query tokenized with
    simple tokenizer at search time (zero neural inference for queries)

  **v2 (DistilBERT, 67M params for v2-distill):**
  - Training: knowledge distillation from heterogeneous teacher (not InfoNCE)
  - BEIR nDCG@10: 0.528 (bi-encoder, +0.004 over v1), FLOPS: 8.3 (-27%)
  - Ingestion throughput: 1.39x faster on GPU, 1.74x faster on CPU
  - Also v2-mini (33M params, doc-only only)
  - Doc-only variant v2-distill-doc: 0.504 nDCG@10 at 1.8 FLOPS

  **v3-distill-doc** also exists: 0.517 nDCG@10 at 1.8 FLOPS (+0.013 over v2-distill-doc).

  Key insight: the doc-only mode is the most cost-effective for production — zero neural
  inference at query time, only simple tokenization. The quality trade-off is -0.024 nDCG@10
  (v2-distill bi vs doc-only). For OpenSearch's scale, this is worthwhile.

- **Evidence**
  - HuggingFace model card: full BEIR per-dataset numbers
  - OpenSearch blog: v1→v2 comparison with throughput benchmarks
  - arXiv 2411.04403: academic paper backing the distillation methodology

- **Applicability** — **Needs adaptation, but two transferable ideas:**

  1. **Doc-only mode (query-time tokenization without neural inference):** JustSearch currently
     runs `spladeEncoder.encode()` for every query (full BERT forward pass). A doc-only approach
     replaces this with three cheap steps: (a) tokenize the query with a standard WordPiece
     tokenizer, (b) create a binary indicator vector (1 where token is present, 0 otherwise),
     (c) multiply by a precomputed IDF weight table (`idf.json` shipped with the model). The
     result is an IDF-weighted sparse query vector — not uniform weights. This eliminates
     query-time GPU/CPU inference entirely. Quality cost: ~0.02 nDCG@10 (v2-distill: 0.528
     bi-encoder vs 0.504 doc-only). At JustSearch's scale the latency saving is small (SPLADE
     encoding is ~10-20ms on GPU), but it removes a hard dependency on the encoder being
     available at query time.

  2. **Distillation for smaller models:** If JustSearch ever trains or fine-tunes its own
     sparse model, DistilBERT (67M) matches BERT-base (133M) quality with half the params and
     27% fewer FLOPS. This is future reference — JustSearch currently uses an off-the-shelf model.

  The OpenSearch models themselves (v1/v2/v3) are NOT directly usable — they're trained on
  MS MARCO and would need evaluation against JustSearch's workload. naver/splade-v3 is likely
  already competitive or better (SPLADE-v3 is the SOTA sparse model as of 2025).

- **Adoption cost**
  - Doc-only query mode: add a `queryWithoutEncoder()` path in `SearchOrchestrator` that uses
    WordPiece tokenization + IDF table lookup instead of `spladeEncoder.encode()`. Requires
    `tokenizer.json` and an `idf.json` weight table (precomputed from corpus statistics or
    shipped with the model). ~2-3 files, medium effort.
  - Model swap: just replace the ONNX file and re-index. The pipeline is model-agnostic.

- **Expected impact**
  - Doc-only mode: eliminates ~10-20ms query-time inference, at cost of ~0.02 nDCG@10. Not
    urgent at desktop scale.
  - Distilled model: ~1.5x faster indexing. Relevant only if indexing throughput becomes a
    bottleneck (currently ~4.8 docs/sec, limited by single-threaded indexing loop, not encoder).

---

## Additional Findings

### A1: WAND pruning via `max_token_score` — a free latency win

OpenSearch RFC #284 revealed that without `max_token_score`, `FeatureField.newLinearQuery`
uses `Float.MAX_VALUE` as the WAND upper bound, effectively disabling Lucene's WAND
optimization (which skips posting list entries that can't exceed the current top-K threshold).
Setting a realistic upper bound (~10.0 for typical SPLADE weights) re-enables WAND with ~4x
latency improvement and <0.1% quality loss. JustSearch has the same `Float.MAX_VALUE` default.
This is essentially free performance — profile SPLADE weight distributions, set a ceiling.

### A2: Zero-exclusion in arithmetic mean fusion

OpenSearch's `ArithmeticMeanScoreCombinationTechnique` excludes zero scores from the
denominator when averaging. This is a subtle but important detail: if a doc appears only in
the BM25 leg (vector score = 0), the arithmetic mean divides by 1 (not 2). Without this,
docs that appear in only one leg are systematically penalized. JustSearch's RRF handles this
differently (rank-based, so missing docs just don't contribute a rank term), but any future
score-based fusion must include this zero-exclusion logic.

### A3: Per-segment approximate/exact hybrid in SEISMIC

SEISMIC's `approximate_threshold` parameter enables a per-segment strategy: small segments
use exact search, large segments use approximate. This is interesting for Lucene-based systems
where segment sizes vary widely after merges. Not immediately useful for JustSearch, but a
good pattern to know if approximate retrieval is ever needed.

### A4: Filtered hybrid search — JustSearch already follows best practice (§7 Q6)

OpenSearch has four filter mechanisms for hybrid search: legacy boolean wrapping (post-filter,
can under-return), efficient KNN filter (passed into `KnnFloatVectorQuery`, adaptive), explicit
`post_filter` (after normalization, for display-only filtering), and "common filter" (OpenSearch
3.0, distributes filter to both legs pre-search).

**JustSearch already uses the correct approach.** `ReadPathOps.searchVector()` passes the filter
directly to `new KnnFloatVectorQuery(field, queryVector, queryK, filter)`, which triggers
Lucene's adaptive three-way strategy:
- If filtered set P ≤ k: exact brute-force on filtered subset
- Otherwise: HNSW traversal with inline filter skip
- If HNSW visits > P nodes without completing: fallback to exact

`QueryFilterBuilder` builds the same filter for both BM25 and vector legs independently —
functionally equivalent to OpenSearch 3.0's "common filter."

**ACORN-1 algorithm** (Lucene ~9.9+): when >10% of HNSW neighbors are filtered out, expands
to neighbors-of-neighbors (up to M×M=1024 candidates). Up to 5x faster at medium filter
selectivity (10-50% filtered out). Worth verifying whether JustSearch's bundled Lucene version
includes this optimization.

**No changes needed.** JustSearch's filtered hybrid search is architecturally correct. The only
action item is checking the Lucene version for ACORN-1 support.

### A5: Cross-encoder reranking after hybrid fusion — technically possible, quality unclear

OpenSearch supports composing hybrid fusion + cross-encoder reranking in a single pipeline:
normalization runs as a `phase_results_processor` (on score lists, pre-fetch), then the rerank
processor runs as a `response_processor` (on fetched document text, post-fetch). No mutual
exclusion — they operate on different data at different pipeline stages.

**JustSearch's exclusion is a quality judgment, not a technical limitation.** The gate in
`KnowledgeHttpApiAdapter.isRerankerEligible()` hard-codes `mode == SearchMode.SEARCH_MODE_TEXT`.
The comment explains: "HYBRID mode already has semantic ranking from embeddings; reranker sees
less context and hurts quality." Contract tests explicitly assert this as a "harmful combination."

OpenSearch's architecture confirms this composition is structurally sound — but OpenSearch's docs
are silent on whether reranking after hybrid fusion actually improves quality. JustSearch's own
eval data (NFCorpus: cross-encoder 0.325 vs SPLADE+Dense RRF 0.337) suggests the dense signal
already present in hybrid results reduces the cross-encoder's incremental value.

**Possible action:** If score-normalized fusion (Q4) changes the quality landscape, re-evaluate
whether cross-encoder after hybrid adds value. The pipeline architecture supports it — the guard
is a single line of code. But this requires eval data, not architectural changes.

---

## Unresolved Items

None — all 5 original checklist items were fully answered from docs and source code.

**Follow-up items from additional findings (not blocking):**
- A4: Verify whether JustSearch's bundled Lucene version includes ACORN-1 filtered HNSW
  optimization (~Lucene 9.9+).
- A5: After implementing score-normalized fusion (Q4), re-evaluate cross-encoder after hybrid
  with the eval harness to determine if the quality gate should be relaxed.

---

## Cross-Cutting Observations

**§7 Key Questions this investigation contributes to:**

- **"What score normalization technique should replace RRF?"** — OpenSearch's production
  experience, independent academic research (Zhuang et al.), and the TopK.io benchmark all
  converge on the same answer: `min_max + weighted arithmetic_mean`. This is now supported
  by three independent sources across tempdocs 248, 249-§5.1 (Pyserini), and this
  OpenSearch investigation.

- **"Is FeatureField the right storage for SPLADE?"** — Yes. Validated by Lucene committer
  (issue #11799), by OpenSearch's production deployment, and by the fact that OpenSearch's
  *new* approach (SEISMIC) still writes FeatureField entries alongside its custom binary
  format. The 8-bit mantissa is accepted as a non-issue at practical SPLADE weight ranges.

- **"Is there a better SPLADE model than naver/splade-v3?"** — Not clearly. OpenSearch's
  custom models (v1/v2/v3) are competitive but not strictly better than naver/splade-v3.
  The key insight is that distilled models (67M params) match full BERT (133M) — but this
  is a future optimization for JustSearch, not an urgent change.

- **"What filtered ANN strategy works best at desktop scale?" (§7 Q6)** — JustSearch already
  follows best practice: filter passed directly to `KnnFloatVectorQuery`, Lucene handles the
  adaptive strategy (exact when P ≤ k, HNSW with skip otherwise, fallback if too many visits).
  OpenSearch confirms this approach at production scale. No changes needed. ACORN-1 (Lucene
  ~9.9+) provides additional speedup at medium selectivity — verify bundled Lucene version.

- **"Why does GPL-trained LambdaMART hurt quality?" (§7 Q9)** — Indirectly relevant.
  OpenSearch's approach separates score normalization and fusion into a dedicated pipeline
  processor, making the learning-to-rank layer optional. If JustSearch implements proper
  score-normalized fusion (Q4), the need for LambdaMART as a post-fusion quality layer
  diminishes — the fusion itself becomes the quality lever. This aligns with §5.4's finding
  that LambdaMART's failure is a training data problem (GPL synthetic-only), not an
  architectural one. Better fusion may make the reranker unnecessary rather than needing to
  fix its training.

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.

---

## pyserini-findings

*(from `249-pyserini-findings.md`)*

### 249: Pyserini Investigation Findings

## Recon Summary

**JustSearch baseline sources consulted:**
- `HybridFusionUtils.java` (full, 298 lines) — RRF and CC fusion implementations
- `HybridSearchOps.java` (full, 460 lines) — fusion strategy selection, parallel execution, low-signal gating
- `ResolvedConfig.java` HybridSearch record — `fusionStrategy`, `ccAlpha`, `rrfK`, etc.
- `docs/explanation/18-adapters-lucene-deep-dive.md` — module structure
- `249-anserini-findings.md` (Finding #3) — prior Pyserini surface coverage

**External sources read:**
- Pyserini: `search/hybrid/_searcher.py` (HybridSearcher, 85 lines)
- Pyserini: `fusion/_base.py` (4 fusion methods, 130 lines)
- Pyserini: `trectools/_base.py` (TrecRun + rescore/normalize, 300 lines)
- Pyserini: `tests/core/test_fusion.py` (350+ lines, expected values for all methods)
- Anserini: `fusion/RunsFuser.java` (210 lines, includes `weighted()` method)
- Anserini: `fusion/ScoredDocsFuser.java` (`normalizeScores()`, `rescore()`)

**Key structural finding:** Pyserini has **two completely separate fusion codepaths** — the
online `HybridSearcher` (inline linear combination) and the offline `pyserini.fusion` module
(TREC run-based, 4 methods). These use different formulas, different normalization, and
different missing-doc strategies.

---

## Findings

### 1. HybridSearcher normalization vs JustSearch CC — different formulas, equivalent ranking

**JustSearch baseline:** `HybridFusionUtils.fuseWithCC()` applies standard min-max
normalization to [0,1]:

```java
normSparse = (score - sparseMin) / sparseRange;  // [0, 1]
normDense  = (score - denseMin)  / denseRange;   // [0, 1]
ccScore    = alpha * normDense + (1 - alpha) * normSparse;
```

Missing docs get `0.0` for the absent leg. All-equal scores normalize to `1.0`.
Alpha defaults to `0.5` and directly controls the dense-leg weight.

**How Pyserini does it:** `HybridSearcher._hybrid_results()` uses **midpoint normalization**
to [-0.5, +0.5]:

```python
normalized = (score - (min + max) / 2) / (max - min)
```

This is algebraically equivalent to `(score - min) / (max - min) - 0.5` — i.e., standard
min-max shifted down by 0.5. The output range is [-0.5, +0.5] instead of [0, 1].

Missing docs get `min_score` from the other leg (before normalization), which normalizes to
-0.5. Pyserini then applies a non-convex weighting:

```python
score = alpha * sparse_score + dense_score         # default
score = sparse_score + alpha * dense_score         # weight_on_dense=True
```

With default `alpha=0.1`, this computes `0.1 * sparse + 1.0 * dense` — making the result
**heavily dense-biased**. The coefficients do NOT sum to 1.

**Evidence:**
- Pyserini `_searcher.py`: `min_dense_score = min(dense_hits.values()) if len(dense_hits) > 0 else 0`
- Pyserini `_searcher.py`: `dense_score = min_dense_score` for docs missing from dense leg
- JustSearch `HybridFusionUtils.java:252-258`: `normSparse = 0.0` when doc not in sparse results
- Bruch et al. (TOIS 2024, arXiv:2210.11934): proves any monotone normalization produces
  rank-equivalent results with appropriate alpha tuning

**Applicability:** Not applicable as a change — JustSearch's CC implementation is already
more principled than Pyserini's HybridSearcher. Specifically:

| Aspect | JustSearch CC | Pyserini HybridSearcher |
|--------|--------------|------------------------|
| Normalization range | [0, 1] | [-0.5, +0.5] |
| Missing doc score | 0.0 (= normalized min) | min_score (= -0.5 normalized) |
| Coefficients sum to 1 | Yes (convex) | No (asymmetric) |
| Alpha semantic | Dense weight (intuitive) | Sparse weight or dense, via flag (confusing) |
| Empty leg handling | All-equal → 1.0 | Hardcoded min=0, max=1 |

The two approaches produce equivalent rankings (per Bruch et al.) when alpha is tuned
appropriately, but JustSearch's is cleaner: true convex combination, intuitive alpha,
standard normalization range.

**Adoption cost:** N/A

**Expected impact:** Validates JustSearch's CC is correctly designed. No change needed.

---

### 2. Offline fusion module — four methods compared

**JustSearch baseline:** Two fusion strategies: RRF (`fuseWithRRF`) and CC (`fuseWithCC`),
selected via `hybridSearch.fusionStrategy` config.

**How Pyserini does it:** The `pyserini.fusion` module offers four offline methods operating
on TREC run files via `TrecRun.rescore()` + `TrecRun.merge(SUM)`:

| Method | Formula | Normalization | N-run support |
|--------|---------|---------------|---------------|
| **RRF** | `1/(k+rank)` per run, then sum | None (rank-only) | Any N |
| **Interpolation** | `alpha * run1 + (1-alpha) * run2` on raw scores | None | Exactly 2 |
| **Average** | `(1/N) * score` per run, then sum | None | Any N |
| **Normalize** | min-max [0,1] per topic per run, then sum | min-max [0,1] | Any N |

**Key insight: `interpolation` is the true Bruch et al. convex combination.** It operates on
raw scores with no normalization. Bruch et al. proves this is rank-equivalent to any
normalization + alpha tuning, meaning:

- Pyserini's `interpolation` on raw scores
- Pyserini's `normalize` (min-max [0,1]) + sum
- JustSearch's CC (min-max [0,1]) + weighted sum

All produce the same ranking given appropriate weight tuning. The advantage of JustSearch's
approach (normalize then weight) is that `alpha=0.5` is intuitively "balanced", whereas with
raw scores the right alpha depends on the score magnitude ratio between legs.

**`average` and `interpolation` produce identical rankings for 2 runs.** Average with N=2 gives
`0.5 * run1 + 0.5 * run2`, which is `interpolation(alpha=0.5)`. The test expected values
confirm this — the `average` and `interpolation` (alpha=0.5) tests have identical expected
results.

**Evidence:**
- `fusion/_base.py`: `interpolation` requires exactly 2 runs, scales by `alpha` and `1-alpha`
- `fusion/_base.py`: `average` scales each run by `1/N` — equivalent to equal-weighted interpolation
- `trectools/_base.py` NORMALIZE rescore: `(score - low) / (high - low)`, all-equal → 1.0
- `trectools/_base.py` merge: pandas `groupby(['topic','docid']).sum()`, sorts by (topic asc, score desc, docid asc)
- Anserini `ScoredDocsFuser.normalizeScores()`: same formula, same all-equal edge case (→ 1.0f)

**Applicability:** Not applicable. JustSearch already implements the rank-equivalent approach
(normalize + weighted sum). Adding raw-score interpolation would be redundant.

**Expected impact:** Confirms JustSearch's CC is equivalent to Pyserini's most principled
fusion method (`interpolation`), with the added benefit of intuitive alpha semantics.

---

### 3. Missing-doc imputation — functionally equivalent strategies

**JustSearch baseline:** `fuseWithCC()` iterates the union of doc IDs from both legs. If a
doc is absent from a leg, its normalized score for that leg is `0.0`:

```java
double normSparse = 0.0;
if (sparseScores.containsKey(docId)) {
    normSparse = sparseRange > 0
        ? (sparseScores.get(docId) - finalSparseMin) / sparseRange
        : 1.0;
}
```

After min-max [0,1] normalization, the minimum-scoring *present* doc gets `0.0`. A *missing*
doc also gets `0.0`. So missing = minimum-present.

**How Pyserini does it:** Three different approaches across the codebase:

1. **HybridSearcher:** Missing doc gets `min_score` from the other leg (raw score). After
   midpoint normalization, this becomes `-0.5`. The minimum-scoring present doc also
   normalizes to `-0.5`. So missing = minimum-present — same as JustSearch.

2. **Offline fusion (TrecRun-based):** Missing docs are simply absent from the ranking.
   During `merge(SUM)`, only docs that appear in at least one run contribute. A doc in
   run A but not run B gets only run A's score — run B contributes `0`, not `min_score`.
   This means missing = 0 contribution, NOT missing = minimum-present.

3. **Anserini `ScoredDocsFuser.merge()`:** Same as Python offline — score summation only for
   docs present in at least one run. Missing leg contributes 0.

**Evidence:**
- Pyserini `_searcher.py`: `dense_score = min_dense_score` for missing docs
- Pyserini `trectools/_base.py` merge: `combined_df.groupby(['topic','docid']).sum()` — only
  present docs participate
- JustSearch `HybridFusionUtils.java:251-264`: explicit 0.0 for missing docs

**Analysis of the test data:** The test expected values reveal the practical impact of imputation
strategy. For the `normalize` method (analogous to JustSearch's CC), the self-match document
`con01a` ranks:
- **#1 in interpolation/average** (score 149.46) — raw BM25 self-match score is enormous
- **#4 in normalize** (score 1.0) — normalized to 1.0 on BM25 leg, but absent from dense top-10,
  so dense contributes 0. Other docs present in both legs sum to >1.0
- **#10 in RRF** (score 0.016) — rank 1 on BM25 leg gives `1/61`, but absent from dense top-10
  gives 0. Other docs ranked high on both legs sum higher

This demonstrates: **score-preserving fusion (interpolation/CC) rewards extreme single-leg
scores more than rank-based fusion (RRF)**. A document with a very high BM25 score but no
dense match ranks #1 under interpolation but #10 under RRF.

**Applicability:** Not applicable as a change. JustSearch's imputation (0.0 for missing docs)
is functionally identical to Pyserini's HybridSearcher (min_score → normalized minimum) for
the online path. The offline zero-contribution approach is a consequence of the TREC run format,
not a deliberate design choice.

**Expected impact:** N/A — confirms current approach is correct.

---

### 4. N-way weighted fusion for 3-leg search

**JustSearch baseline:** `fuseWithCC()` and `fuseWithRRF()` both accept exactly two result
sets (sparse + dense). There is no API for 3-way fusion (BM25 + SPLADE + dense) in a single
call. The current workaround is the two-stage pipeline in `SearchOrchestrator.mergeChunkResults()`,
which fuses BM25+SPLADE as one leg and dense as the other.

**How Anserini does it:** `RunsFuser.weighted()` generalizes interpolation to N runs with
per-run weights:

```java
public static ScoredDocs weighted(List<ScoredDocs> runs, List<Double> weights, int depth, int k) {
    for (int i = 0; i < runs.size(); i++) {
        ScoredDocsFuser.rescore(RescoreMethod.SCALE, 0, weights.get(i), runs.get(i));
    }
    return ScoredDocsFuser.merge(runs, depth, k);
}
```

Combined with the `min_max_normalization` flag, this gives: normalize each leg to [0,1], then
`score = w1 * norm_bm25 + w2 * norm_splade + w3 * norm_dense`. Weights are CLI-configurable
via `--weights "0.3,0.3,0.4"`.

Pyserini's `average` method is a special case: all weights = 1/N. RRF is also N-way (rank-based,
no weights needed). Only `interpolation` is limited to 2 runs.

**Evidence:**
- Anserini `RunsFuser.java`: `weighted()` method with `List<Double> weights` parameter
- Anserini `RunsFuser.java`: `parseWeights()` parses comma-separated string `"0.7,0.3"`
- Anserini `RunsFuser.java`: `normalizeRuns()` applies `ScoredDocsFuser.normalizeScores()` to
  each run before fusion when `-min_max_normalization` is set

**Applicability:** Needs adaptation. If JustSearch implements true 3-way fusion (BM25 + SPLADE
+ dense as independent legs rather than SPLADE merged into the BM25 leg), a `fuseWithWeightedCC`
method following Anserini's pattern would be straightforward:

```java
// Conceptual — normalize each leg to [0,1], then weighted sum
score = w_bm25 * norm_bm25 + w_splade * norm_splade + w_dense * norm_dense;
```

This is a direct generalization of the existing `fuseWithCC()`. The main prerequisite is
producing three independent result sets, which requires changes in `SearchOrchestrator` to
run BM25 and SPLADE legs separately (currently they're combined in a single Lucene query).

**Adoption cost:** Medium:
- `HybridFusionUtils.java`: add `fuseWithWeightedCC(List<SearchResult> legs, List<Double> weights, ...)`
- `SearchOrchestrator`: separate BM25 and SPLADE into independent legs
- Config: add per-leg weight configuration

**Expected impact:** Enables independent weight tuning per retrieval method, which Bruch et al.
recommends as superior to merged-leg approaches. The 2-way CC is a fine starting point; 3-way
is a future enhancement.

---

## Additional Findings

### A1. Pyserini's alpha default (0.1) is calibrated for BM25+dense, not BM25+SPLADE

Pyserini's `HybridSearcher` defaults to `alpha=0.1`, meaning `0.1 * sparse + 1.0 * dense`.
This was tuned for BM25 (weak sparse) + contriever/BGE (strong dense) on BEIR. JustSearch's
hybrid search combines BM25+SPLADE (strong sparse) + dense, where the sparse leg is
significantly stronger. JustSearch's default `alpha=0.5` (balanced) is more appropriate for
this configuration. The optimal alpha for JustSearch would need to be calibrated on its own
eval datasets, but starting at 0.5 is more defensible than copying Pyserini's 0.1.

### A2. Fusion method determines self-match ranking behavior

The test data reveals that `interpolation` (score-preserving CC) ranks the self-match document
`con01a` at #1 (score 149.46), while `normalize` (min-max CC) ranks it at #4 (score 1.0) and
RRF ranks it at #10 (score 0.016). This happens because `con01a` has an extremely high BM25
score (it's the query document appearing in the corpus) but doesn't appear in the dense top-10.

**Implication for JustSearch:** Score-preserving fusion (raw CC without normalization) would
amplify BM25 self-match bias. JustSearch's min-max normalization correctly prevents this by
mapping all scores to [0,1] before combining. This is a feature, not a limitation — it prevents
documents with extreme BM25 scores from dominating the fused ranking purely on score magnitude.

### A3. Tie-breaking in merge is docid-ascending

Both Pyserini and Anserini break ties by ascending docid (lexicographic). JustSearch's CC
also breaks ties by `a.getKey().compareTo(b.getKey())` (ascending docid). JustSearch's RRF
has a more sophisticated tie-breaking chain: RRF score → BM25 raw score → vector raw score →
docid. This gives RRF an advantage in producing stable, meaningful orderings for tied documents.

### A4. HybridSearcher has a division-by-zero risk

When normalization is enabled and one leg returns results that all have the same score
(`max == min`), the normalization formula `(score - midpoint) / (max - min)` divides by zero.
Pyserini does NOT guard against this. JustSearch's CC guards correctly:
```java
normSparse = sparseRange > 0 ? (...) : 1.0;
```

This is a genuine bug in Pyserini's HybridSearcher (though unlikely to trigger in practice
since BM25 and KNN almost never produce identical scores for all top-K documents).

---

## Unresolved Items

### U1. Optimal alpha for JustSearch's CC fusion

The investigation establishes that CC is rank-equivalent across normalizations with appropriate
alpha, but does not determine what alpha value is optimal for JustSearch's specific BM25+SPLADE
+ dense configuration. Bruch et al. reports alpha converges with ~10-50 labeled queries via grid
search. This requires running JustSearch's eval harness with multiple alpha values on the BEIR
datasets — an implementation task, not an investigation task.

### U2. Whether 3-way fusion outperforms 2-way

The investigation identifies the 3-way weighted CC pattern from Anserini, but cannot determine
whether separating BM25 and SPLADE into independent legs would improve quality over the current
merged approach. This requires eval benchmarking with both configurations.

---

## Cross-Cutting Observations

### §7 Q2: Best score normalization for hybrid fusion?

**Contribution: Strong — confirms CC over RRF, clarifies normalization irrelevance.**

Pyserini provides concrete evidence from two independent implementations:

1. The `HybridSearcher` (online, [-0.5, +0.5] normalization) and the `interpolation` method
   (offline, raw scores) produce equivalent rankings per Bruch et al.'s proof. JustSearch's
   CC (online, [0,1] normalization) is a third equivalent variant.

2. The test data quantifies the RRF vs CC ranking difference: for documents with extreme
   single-leg scores (e.g., BM25 self-match), RRF ranks them last (#10) while CC methods rank
   them much higher (#1 raw, #4 normalized). This demonstrates RRF's information loss in action
   — it discards the magnitude of the score advantage.

3. Anserini's `min_max_normalization + weighted` method is exactly equivalent to JustSearch's
   CC approach, confirming that min-max [0,1] + alpha weighting is a standard, well-tested
   pattern.

**Updated evidence for Q2:**
- JustSearch's CC implementation is validated as correct and equivalent to the most principled
  approaches in both Pyserini and Anserini
- The remaining question is alpha calibration, not algorithm choice
- 3-way weighted CC is the natural extension if BM25+SPLADE legs are ever separated

### §7 Q9: Why does GPL-trained LambdaMART hurt quality?

**Contribution: Supporting.** Pyserini's test data shows that simple fusion methods (RRF,
interpolation, normalize) all produce reasonable rankings without any learned component.
The test data uses Arguana (an argument retrieval dataset where JustSearch's LambdaMART
performed worst), and all four Pyserini fusion methods produce coherent rankings. This
further supports the finding that simple fusion outperforms overfit learned reranking.

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.

---

## qdrant-findings

*(from `249-qdrant-findings.md`)*

### 249: Qdrant Investigation Findings

## Recon Summary

*Phase 1 recon approved. Phase 2 deep-dive complete.*

### JustSearch baseline consulted

| Area | Current implementation | Key files |
|------|----------------------|-----------|
| **Relevance feedback** | Not implemented. No MoreLikeThis, no "find similar" API. | — |
| **Vector quantization** | Int8 scalar quantization via `Lucene99HnswScalarQuantizedVectorsFormat`. M=16, efConstruction=200, efSearch=100. 768-dim vectors. | `JustSearchCodec.java`, `ComponentsFactory.java:152-164` |
| **Sparse vector indexing** | `FeatureField` per SPLADE token, queried via `FeatureField.newLinearQuery` in a `BooleanQuery(SHOULD)`. Standard Lucene WAND/impact scoring. | `FieldMapper.java:249-258`, `LuceneIndexRuntime.java:1520-1545` |
| **Filtered vector search** | Pre-filter only — filter passed as 4th arg to `KnnFloatVectorQuery`. No post-filter or adaptive strategy. Known degradation on highly selective filters. | `ReadPathOps.java:317-318`, `ChunkSearchOps.java:363-364` |

### Qdrant source plan (per checklist item)

**Item 1: Relevance feedback API**

Qdrant has two APIs — **Recommend** and **Discovery** — that implement relevance
feedback without requiring model retraining:

- **Recommend API**: accepts positive/negative point IDs or raw vectors. Three
  strategies: average vector (default, fast), best score (per-candidate
  evaluation, slower), sum scores.
- **Discovery API** (v1.7+): context pairs (positive-negative vector pairs) that
  partition the search space. Can combine with a target vector for guided search.

Sources to read in deep-dive:
1. [Qdrant Explore docs](https://qdrant.tech/documentation/concepts/explore/) — full API spec, scoring formulas, strategy comparison *(already skimmed)*
2. Qdrant source: `lib/segment/src/vector_storage/query_scorer/` — actual scoring implementations for average/best/sum strategies
3. Qdrant source: `lib/segment/src/spaces/` — distance function wrappers used by recommend
4. Assess feasibility: Can JustSearch implement similar with Lucene `MoreLikeThisQuery` (BM25 leg) + averaged KNN vector (dense leg)?

**Item 2: Quantization strategies**

Qdrant offers three quantization levels with a two-phase search (quantized
coarse pass + original-vector rescore):

| Type | Compression | Speed | Accuracy | Notes |
|------|-------------|-------|----------|-------|
| Scalar (int8) | 4x | ~2x | 0.99 | Universal default. Same as JustSearch's Lucene SQ. |
| Binary (1-bit) | 32x | ~40x | 0.95 | Only for high-dim, centered distributions. |
| Product (PQ) | up to 64x | 0.5x | 0.70 | Memory-first, speed-last. |

Key Qdrant innovation: **rescore + oversampling**. Retrieve N * oversampling_factor
candidates using quantized vectors, then rescore top-K with original float32
vectors. This is a pattern JustSearch could adopt.

Sources to read in deep-dive:
1. [Qdrant quantization guide](https://qdrant.tech/documentation/guides/quantization/) — config params, recommendations *(already skimmed)*
2. [Scalar quantization article](https://qdrant.tech/articles/scalar-quantization/) — implementation details, benchmarks
3. [Binary quantization article](https://qdrant.tech/articles/binary-quantization/) — model compatibility, 40x claim benchmarks
4. [Product quantization article](https://qdrant.tech/articles/product-quantization/) — codebook design, sub-vector splitting
5. Qdrant source: `lib/quantization/` — actual quantization implementations
6. Assess: Lucene already has Int8 SQ. What Qdrant adds is the rescore/oversampling pattern and binary quantization — are these applicable?

**Item 3: Sparse vector indexing**

Qdrant uses an inverted index for sparse vectors with **exact search** (no
approximation). Dot-product similarity only. This is architecturally simpler
than Lucene's FeatureField + WAND optimization.

Sources to read in deep-dive:
1. [Sparse vectors article](https://qdrant.tech/articles/sparse-vectors/) — SPLADE integration, memory comparison *(already skimmed)*
2. Qdrant source: `lib/sparse/src/index/inverted_index/` — actual inverted index implementation (posting lists, compression)
3. Qdrant source: search for `SparseIndex` or `InvertedIndex` — query execution path
4. Compare: Lucene FeatureField has WAND optimization (early termination) which Qdrant's exact search lacks. At desktop scale (<1M docs), does this matter? What about Qdrant's memory layout advantages?

**Item 4: Filtered vector search**

Qdrant has the most sophisticated filtered ANN strategy of any project surveyed:

- **Three strategies**: HNSW-first (high selectivity), payload-index scan (low selectivity), filterable HNSW (mid-range)
- **Filterable HNSW**: extends graph with extra edges per payload category. Max 2x edge overhead regardless of category count.
- **ACORN** (v1.16.0+): second-hop neighbor exploration when direct neighbors are filtered out
- **Adaptive switching** via `full_scan_threshold_kb`

Sources to read in deep-dive:
1. [Indexing concepts](https://qdrant.tech/documentation/concepts/indexing/) — HNSW params, filterable HNSW, ACORN *(already skimmed)*
2. [Filterable HNSW article](https://qdrant.tech/articles/filterable-hnsw/) — percolation theory, edge augmentation *(already skimmed)*
3. Qdrant source: `lib/segment/src/index/hnsw_index/` — graph construction with payload edges
4. Qdrant source: search for `full_scan_threshold` — adaptive strategy selection logic
5. Assess: Lucene's pre-filter-only approach degrades on selective filters. Could JustSearch implement a threshold-based fallback (exhaustive scan for narrow filters)?

### Bonus area: Hybrid fusion

Qdrant's hybrid query system surfaced two fusion methods beyond RRF:

- **Weighted RRF** (v1.17.0): per-leg weights so stronger retrievers dominate
- **DBSF** (Distribution-Based Score Fusion, v1.11.0): normalizes scores using
  mean +/- 3 std deviations, then sums. This is a z-score-family normalization,
  directly relevant to §7 Q2 (best score normalization for hybrid fusion).

Sources: [Hybrid queries docs](https://qdrant.tech/documentation/concepts/hybrid-queries/)

### Items unlikely to be fully answerable from docs/source

- Qdrant's internal inverted index implementation details (posting list
  compression, block-max) may be in Rust source that's hard to read in a
  single session. Will attempt but may end up as "unresolved."
- Actual benchmarks for filtered HNSW recall at different selectivity levels
  — Qdrant's docs give qualitative descriptions, not hard numbers.

### Suggested skip

None — all 4 checklist items are well-covered by Qdrant's public docs and source.

---

## Findings

### 1. Relevance Feedback — Recommend and Discovery APIs

- **JustSearch baseline** — No relevance feedback feature exists. No MoreLikeThis,
  no "find similar" API, no positive/negative example support.
- **How it works** — Qdrant provides two complementary APIs:

  **Recommend API** — accepts positive and negative point IDs or raw vectors, with
  three scoring strategies:

  | Strategy | Formula | Performance | Use case |
  |----------|---------|-------------|----------|
  | `average_vector` (default) | `search_vec = 2 * avg(positives) - avg(negatives)` | Same as standard ANN (preprocessing only) | General "more like this" |
  | `best_score` (v1.6+) | For each candidate: `max_pos_sim > max_neg_sim ? sigmoid(max_pos) : -sigmoid(max_neg)` | O(candidates * examples) | Few examples, diverse cluster shapes |
  | `sum_scores` | `sum(pos_similarities) - sum(neg_similarities)` | O(candidates * examples) | Many examples, reported +5.6 nDCG@20 |

  The `average_vector` strategy preprocesses examples into a single synthetic
  vector, then runs standard ANN — zero overhead at query time. The
  `scaled_fast_sigmoid(x) = 0.5 * (x / (1 + |x|) + 1.0)` maps scores to (0,1).

  **Discovery API** (v1.7+) — uses context pairs (positive, negative) to partition
  the search space into zones:

  - Each context pair divides the space: points closer to the positive are in the
    "positive zone" (+1), closer to the negative are in the "negative zone" (-1).
  - Discovery score = `integer_rank + sigmoid(target_similarity)`. Rank differences
    (how many positive zones a point belongs to) always dominate over target
    similarity — a point in 3/3 positive zones beats one in 2/3 zones regardless
    of target distance.
  - Context search (no target): uses triplet loss
    `sum(sigmoid(min(sim(v+) - sim(v-) - epsilon, 0)))` — finds diverse points
    satisfying zone constraints.
  - Requires high ef (64-128) due to irregular scoring landscape.

  **Bonus: `FeedbackQuery` (experimental, not in public API)** — learned linear
  combination: `a * sim(target) + sum(confidence^b * c * (sim(pos) - sim(neg)))`.
  Coefficients are per-dataset trained. Not yet shipped.

- **Evidence** — Qdrant docs report +5.6 nDCG@20 with 2-8 positive pseudo-relevance
  feedback examples using `sum_scores`. Source code confirmed in
  `lib/segment/src/vector_storage/query/reco_query.rs` and
  `lib/collection/src/recommendations.rs`.

- **Applicability** — **Directly applicable (average_vector); needs adaptation
  (best_score, discovery).**

  The `average_vector` strategy maps cleanly to Lucene:

  ```
  Dense leg:  compute 2*avg(pos_vecs) - avg(neg_vecs) → KnnFloatVectorQuery
  Sparse leg: Lucene MoreLikeThisQuery on positive doc IDs (BM25 terms)
  Fusion:     standard RRF/CC fusion via existing HybridSearchOps
  ```

  This requires: (a) a gRPC request type carrying positive/negative doc IDs,
  (b) Worker fetches stored vectors via `FloatVectorValues`, (c) computes
  synthetic query vector, (d) runs existing hybrid search pipeline. No Lucene API
  changes needed.

  `best_score` is more expensive — requires multiple KNN queries (one per example)
  then merging with the sigmoid formula. Feasible with 2-5 examples.

  `discovery` requires custom Lucene scorer integration — medium effort.

  MLT limitation: `MoreLikeThisQuery` does not natively subtract negative examples.
  Workaround: add `BooleanQuery.MUST_NOT` from MLT terms extracted from negatives.

- **Adoption cost** — Medium. New gRPC message in `indexing.proto`, new endpoint
  in `KnowledgeHttpApiAdapter`, new method in `SearchOrchestrator`. ~500 lines for
  `average_vector` strategy. Dense leg is trivial; sparse leg (MLT integration)
  needs some experimentation.

- **Expected impact** — Enables "find similar documents" and "more/less like this"
  UX. The +5.6 nDCG@20 from pseudo-relevance feedback is meaningful. No direct
  search quality regression risk since this is a new feature, not a change to
  existing search.

---

### 2. Quantization Strategies — Rescore + Oversampling Pattern

- **JustSearch baseline** — Int8 scalar quantization via
  `Lucene99HnswScalarQuantizedVectorsFormat` (7-bit, dynamic confidence interval).
  M=16, efConstruction=200, efSearch=100. 768-dim nomic-embed-text-v1.5 vectors.
  JustSearch has efSearch oversampling (requests more candidates from HNSW graph),
  but does **not** rescore candidates with original float32 vectors.

- **How it works** — Qdrant offers three quantization levels:

  | Type | Compression | Speed | Recall | Best for |
  |------|-------------|-------|--------|----------|
  | Scalar (int8) | 4x | ~2x | 0.99 | Universal default |
  | Binary (1-bit) | 32x | ~40x | 0.95* | High-dim (>=1024), centered distributions |
  | Product (PQ) | up to 64x | 0.5x | 0.70 | Memory-constrained, speed not critical |

  *Binary recall requires rescoring + oversampling.

  **The key pattern is two-phase search:**

  ```
  Phase 1 (coarse): HNSW traversal using quantized vectors
    → retrieve limit * oversampling_factor candidates (e.g., 2.4x)
  Phase 2 (rescore): fetch original float32 vectors for candidates
    → recompute exact cosine similarity → re-rank → return top-limit
  ```

  Qdrant stores **both** quantized and original float32 vectors. Rescore is
  enabled by default. Recommended oversampling: 1.5-2x for scalar, 3-4x for binary.

  **Scalar quantization benchmarks:**
  - Arxiv-titles-384: 60.64% latency reduction, 0.3% precision loss
  - Gist-960: 28-42% latency improvement
  - Memory-constrained (2GB): SQ + rescore = 30 RPS at 0.989 precision vs 2 RPS
    without quantization

  **Binary quantization model compatibility at 768 dims:**

  | Model | Dims | Recall | Oversampling |
  |-------|------|--------|-------------|
  | Gemini | 768 | 0.9563 | 3x |
  | Mistral Embed | 768 | 0.9445 | 3x |
  | OpenAI ada-002 | 1536 | 0.98 | 4x |
  | Cohere v2.0 | 4096 | 0.98 | 2x |

  768-dim models are in the "danger zone" (Qdrant docs explicitly warn against
  dims < 1024 for binary quantization). nomic-embed-text-v1.5 was not tested.

  **New variants (Qdrant 2025):** 1.5-bit (24x compression) and 2-bit (16x
  compression) with asymmetric quantization (query encoded differently from
  stored vectors). These address the near-zero value problem of 1-bit at
  moderate compression.

- **Evidence** — Benchmarks from Qdrant's scalar quantization article and binary
  quantization article. Source code confirmed: `lib/quantization/` implements
  the rescore pattern; `KnnFloatVectorQuery` in Lucene does NOT have rescore.

- **Applicability** — **Needs adaptation (rescore pattern); not applicable (binary
  quantization at 768 dims).**

  The critical gap: Lucene's `Lucene99HnswScalarQuantizedVectorsFormat` does
  **not store original float32 vectors** alongside quantized ones. There is no
  native rescore mechanism in `KnnFloatVectorQuery` or `HnswGraphSearcher`.

  To implement rescore in JustSearch:
  1. Store float32 vectors in a separate `StoredField` or second `KnnFloatVectorField`
  2. Run `KnnFloatVectorQuery` with high k (= limit * oversampling_factor)
  3. For each candidate doc ID, fetch the stored float32 vector
  4. Recompute exact cosine similarity against query vector
  5. Re-rank and truncate to limit

  This is mechanically possible but non-trivial: requires custom post-processing
  after `KnnFloatVectorQuery` returns, and doubles vector storage.

  Binary quantization is **not recommended** for 768-dim nomic-embed-text-v1.5
  (recall drops to 0.94-0.96, below acceptable threshold without extensive
  oversampling).

  Product quantization is not recommended (slower than unquantized, poor accuracy).

- **Adoption cost** — High for rescore pattern. Requires: (a) schema change to
  store float32 vectors alongside quantized, (b) custom query wrapper in
  `LuceneIndexRuntime`, (c) index format migration. Estimated 1000+ lines across
  `adapters-lucene` and `indexer-worker`.

- **Expected impact** — Moderate. JustSearch already uses Int8 SQ with 0.99
  accuracy. Rescore would recover the remaining ~1% precision at the cost of
  doubled vector storage and added query complexity. Most valuable if JustSearch
  later adopts more aggressive quantization (2-bit or binary) where the precision
  gap is larger.

---

### 3. Sparse Vector Indexing — Inverted Index with WAND Pruning

- **JustSearch baseline** — SPLADE sparse vectors stored in Lucene `FeatureField`,
  one entry per token. Queried via `FeatureField.newLinearQuery` per token in a
  `BooleanQuery(SHOULD)`. Lucene applies WAND optimization (block-max early
  termination) via `MaxScoreScorer` and `BlockMaxImpacts` in the codec.

- **How it works** — Qdrant uses a custom inverted index (`lib/sparse/src/index/`),
  **not** a standard search engine codec:

  **Data structure:** `Vec<PostingList>` indexed directly by dimension ID (O(1)
  lookup, not a HashMap). Each posting entry is 12 bytes:

  ```
  PostingElementEx {
    record_id: u32,      // 4 bytes
    weight: f32,         // 4 bytes
    max_next_weight: f32 // 4 bytes — enables inline WAND pruning
  }
  ```

  The `max_next_weight` field stores the maximum weight among all subsequent
  entries in the sorted posting list — this is the key to WAND-style early
  termination.

  **Correction from recon:** Qdrant **does** implement WAND-style pruning, not
  exact-only search. The `prune_longest_posting_list()` function checks if the
  best possible future score from a posting list (bounded by `max_next_weight *
  query_weight`) can beat the current k-th best result. If not, the entire list
  is skipped forward. This is functionally equivalent to Lucene's WAND.

  **Query execution:** Batched WAND over 10,000-ID windows. The longest posting
  list is promoted to the front (pivot selection heuristic). Score accumulation
  uses `unsafe get_unchecked_mut` for speed.

  **Four storage variants:**

  | Variant | Mutability | WAND | Compression |
  |---------|-----------|------|-------------|
  | RAM | Mutable | Yes (`max_next_weight` inline) | None (12 bytes/entry) |
  | Mmap | Immutable | Yes | None (mmap'd 12-byte entries) |
  | Compressed RAM | Immutable | **No** (`reliable_max_next_weight = false`) | BitPacker4x IDs + optional f16/u8 weights |
  | Compressed Mmap | Immutable | **No** | Same as compressed RAM, disk-backed |

  **Compression:** BitPacker4x (SIMD) delta-encodes sorted IDs in chunks of 64.
  Weights can be f16 (2 bytes) or u8 (1 byte). Compressed entries ~3-5 bytes/entry
  vs 12 bytes uncompressed. Trade-off: compressed variants **lose WAND
  acceleration**.

- **Evidence** — Source code in `lib/sparse/src/index/search_context.rs` (WAND
  pruning loop), `inverted_index_ram.rs` (data structure), `compressed_posting_
  list.rs` (BitPacker4x compression). Memory: 1M docs * 100 tokens * 12 bytes =
  1.2 GB uncompressed, matching Qdrant's published figure.

- **Applicability** — **Not applicable (no action needed).**

  Lucene and Qdrant both implement WAND-style pruning for sparse queries. The
  approaches are architecturally different but functionally equivalent:

  | Aspect | Qdrant | Lucene FeatureField |
  |--------|--------|-------------------|
  | WAND bounds | Inline `max_next_weight` per entry (4 extra bytes) | `BlockMaxImpacts` in codec `.pay` files (per-block, cache-friendly) |
  | ID compression | BitPacker4x SIMD (chunks of 64) | FOR/PForDelta or VInt (codec-dependent) |
  | Weight precision | f32 (or f16/u8 compressed) | 8-bit log-linear approximation |
  | Mutability | Mutable RAM + immutable compressed | Immutable segments with periodic merge |

  At JustSearch's desktop scale (<1M docs, ~100 tokens/doc), both achieve
  similar throughput — WAND typically evaluates 1-5% of postings. Memory is
  comparable (~120 MB for 100K docs). No changes to JustSearch's SPLADE indexing
  are warranted based on this comparison.

  **One transferable insight:** Qdrant's compressed variant trades WAND for memory.
  If JustSearch ever needs to reduce SPLADE memory footprint, weight quantization
  (f16 or u8) could be explored in a custom Lucene codec — but this is a
  Lucene-level change, not an application-level one.

- **Adoption cost** — N/A.
- **Expected impact** — N/A. JustSearch's current approach is already competitive.

---

### 4. Filtered Vector Search — Adaptive Strategy Selection

- **JustSearch baseline** — Pre-filter only: filter `BitSet` passed as 4th argument
  to `KnnFloatVectorQuery` constructor. Lucene applies the filter during HNSW graph
  traversal. No adaptive strategy, no fallback to exhaustive scan for highly
  selective filters. Known degradation when filter passes very few candidates.

- **How it works** — Qdrant implements a three-strategy adaptive approach:

  **Strategy selection** (in `HNSWIndex::search()`):

  ```
  1. Estimate filter cardinality from payload index
  2. If max_cardinality < full_scan_threshold → brute-force scan (Strategy A)
  3. If min_cardinality > full_scan_threshold → HNSW with filter (Strategy B)
  4. If uncertain → sample documents to resolve, then pick A or B
  ```

  `full_scan_threshold` is configured via `full_scan_threshold_kb` (default 10,000
  KB). Converted to vector count at startup: `threshold_kb * 1024 / avg_vector_
  bytes`. For 768-dim float32 (3,072 bytes/vector): ~3,333 vectors.

  **Strategy A (brute-force):** Score only the filtered documents exhaustively.
  Best when few documents pass the filter — O(filtered_count * dims) vs HNSW's
  O(ef * log(N)) which degrades when the graph is sparsely connected in the
  filtered subspace.

  **Strategy B (HNSW with filter):** Standard HNSW traversal, skip nodes that fail
  the filter. This is what Lucene does (JustSearch's current approach).

  **Strategy C (filterable HNSW, index-time):** When payload fields have
  `enable_hnsw=true`, Qdrant builds per-payload HNSW subgraphs at index time.
  Extra edges connect nodes within the same payload category. Max 2x edge
  overhead regardless of category count (each node only appears in its own
  category's subgraph).

  **ACORN algorithm (v1.16.0+):** Query-time enhancement for selective filters
  (selectivity <= 0.4). When a graph neighbor fails the filter, ACORN explores
  that node's neighbors as **relay points** (second-hop exploration):

  ```
  For each candidate:
    1. Fetch 1-hop neighbors
    2. Neighbors passing filter → score directly
    3. Neighbors failing filter → explore THEIR neighbors (2-hop)
    4. 2-hop neighbors passing filter → score
  ```

  This recovers graph connectivity below the percolation threshold. For m=16,
  the critical threshold is `p_c = 1/m = 6.25%` — below this, the filtered
  subgraph fragments into disconnected components.

- **Evidence** — Source code in `lib/segment/src/index/hnsw_index/` (strategy
  selection), `search_on_level_acorn()` (ACORN implementation with separate
  `hop1_visited` and `hop2_visited` bitsets). Percolation theory basis documented
  in Qdrant's filterable HNSW article.

- **Applicability** — **Directly applicable (threshold fallback); not applicable
  (filterable HNSW, ACORN).**

  **Threshold-based brute-force fallback** — the most actionable finding.
  JustSearch can check `filter.cardinality()` before constructing the KNN query:

  ```java
  int filteredCount = filter.cardinality();  // BitSet.cardinality() is O(n/64)
  if (filteredCount < FULL_SCAN_THRESHOLD) {
      return exactKnnSearch(queryVector, filter, k);
  } else {
      return new KnnFloatVectorQuery(field, queryVector, k, filter);
  }
  ```

  An appropriate threshold for JustSearch (768-dim, <1M docs): ~3,000-5,000
  vectors. Below this, exhaustive scan is both faster and higher recall than
  filtered HNSW.

  **Filterable HNSW** — requires a custom `KnnVectorsFormat` to build per-field
  subgraphs. High effort, significant index size increase. Not feasible at the
  application layer.

  **ACORN** — requires modifying `HnswGraph.search()` internals. This is a
  Lucene upstream contribution opportunity, not a local JustSearch change.

- **Adoption cost** — Low for threshold fallback. ~50 lines in `ReadPathOps` and
  `ChunkSearchOps`. The `exactKnnSearch` method would iterate filtered docs,
  compute cosine similarity, and maintain a top-K heap.

- **Expected impact** — Improved recall for highly selective filters (date ranges,
  specific tags, folder scopes on small document sets). At JustSearch's scale,
  most queries filter >10% of documents (above percolation threshold), so the
  impact is narrow but prevents the worst-case recall failures. Zero cost for
  unfiltered or broadly-filtered queries (the check adds one O(n/64) cardinality
  call).

---

## Additional Findings

### A1. DBSF — Distribution-Based Score Fusion

Qdrant's hybrid query system (v1.11.0+) offers **DBSF** as an alternative to RRF:

```
normalized_score = (raw_score - (mean - 3*stddev)) / ((mean + 3*stddev) - (mean - 3*stddev))
final_score = sum(normalized_scores across legs)
```

This is a z-score-family normalization that maps each leg's scores to [0, 1]
using the distribution's mean and 3-sigma spread. Unlike RRF (which discards
score magnitudes and uses only ranks), DBSF preserves score distances — a
document that scores 0.95 on both legs will rank higher than one scoring 0.70
on both, even if their ranks are the same.

**Comparison with other fusion methods (from §7 Q2 evidence):**

| Method | Score info | Tunable | Source |
|--------|-----------|---------|--------|
| RRF (k=60) | Rank only | k constant | JustSearch (current) |
| Weighted RRF (Qdrant v1.17) | Rank + per-leg weights | Per-leg weight | Qdrant |
| DBSF (Qdrant v1.11) | z-score normalized | None | Qdrant |
| min-max + arithmetic mean | min-max normalized | Per-leg weight | OpenSearch |
| Convex combination | Raw scores | alpha | Pyserini |

DBSF is most similar to OpenSearch's min-max normalization but uses mean/stddev
instead of min/max, making it more robust to outlier scores.

### A2. Weighted RRF

Qdrant v1.17.0 added per-leg weights to RRF:

```
weighted_rrf_score = sum(weight_i / (k + rank_i))
```

With `weight=3.0` for the semantic leg and `weight=1.0` for the keyword leg,
the semantic retriever's rankings dominate. This addresses the "which leg to
trust" problem without changing the RRF formula — only scaling each leg's
contribution.

JustSearch's `HybridFusionUtils.fuseWithRRF()` could adopt per-leg weights with
minimal change (~10 lines).

### A3. Qdrant's SPLADE max_next_weight is inline WAND

The recon phase assumed Qdrant uses exact-only sparse search. This is incorrect.
Qdrant's `PostingElementEx` includes a 4-byte `max_next_weight` field that enables
WAND-style early termination. The trade-off: compressed posting lists disable
this (set `reliable_max_next_weight = false`), sacrificing speed for memory. This
compression/speed trade-off is a design point JustSearch could consider if SPLADE
memory becomes a concern.

---

## Unresolved Items

- **Filtered HNSW recall benchmarks at specific selectivity levels** — Qdrant's
  docs describe the three-strategy approach qualitatively but do not publish
  recall@K numbers at (e.g.) 1%, 5%, 10%, 50% filter selectivity. Would require
  running Qdrant with controlled experiments to measure.

- **Binary quantization compatibility with nomic-embed-text-v1.5** — Qdrant tested
  Gemini and Mistral Embed at 768 dims (0.94-0.96 recall) but not nomic. The
  model's embedding distribution (how centered, how information-dense per
  dimension) determines BQ compatibility. Would require benchmarking.

- **ACORN recall improvement magnitude** — The algorithm is well-documented in
  source code, but no benchmarks comparing ACORN vs standard filtered HNSW at
  various selectivity levels were found in Qdrant's public docs.

---

## Cross-Cutting Observations

**§7 Q2 (Best score normalization for hybrid fusion):** Qdrant contributes two
additional data points: DBSF (z-score normalization, sum) and weighted RRF
(per-leg weights). Combined with OpenSearch's min-max + arithmetic mean and
Pyserini's convex combination, there are now four distinct alternatives to
JustSearch's unweighted RRF. The evidence increasingly suggests that score-aware
fusion (DBSF, convex combination, min-max) outperforms rank-only fusion (RRF)
— but all require score normalization as a prerequisite.

**§7 Q3 (Relevance feedback feasibility with Lucene):** Qdrant's `average_vector`
recommend strategy is directly implementable in Lucene — compute a synthetic
query vector from positive/negative examples, then run standard KNN. The
`MoreLikeThisQuery` provides the BM25/sparse leg. This confirms relevance
feedback is feasible without replacing Lucene, answering the key question
affirmatively.

**§7 Q6 (Filtered ANN strategy at desktop scale):** Qdrant's threshold-based
fallback (brute-force scan when filter cardinality < threshold) is the
most practical improvement for JustSearch. The percolation theory basis
(p_c = 1/m = 6.25% for m=16) provides a principled threshold. ACORN and
filterable HNSW are Lucene-level changes not feasible at the application layer.

**§7 Q1 (FeatureField 8-bit mantissa):** Qdrant uses full f32 weights for sparse
vectors (no precision limitation). However, their compressed variant offers
f16 and u8 weight options, suggesting that even within Qdrant, reduced-precision
weights are acceptable for sparse retrieval. This is consistent with OpenSearch's
finding that FeatureField's 8-bit mantissa is "accepted, not solved" — the
precision loss is tolerable for SPLADE-v3 weight ranges.

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.

---

## qmd-findings

*(from `249-qmd-findings.md`)*

## Recon Summary

**JustSearch baseline loaded from:**
- `KnowledgeHttpApiAdapter.java` (lines 65–100, 427–486, 867–926) — morphological query expansion, cross-encoder reranking, LambdaMART reranking
- `SearchOrchestrator.java` — hybrid pipeline flow (BM25 + KNN parallel, RRF fusion, SPLADE routing)
- `modules/reranker/CrossEncoderReranker.java` — ONNX cross-encoder (ms-marco-MiniLM-L6-v2, 200ms deadline, TEXT mode only)
- `modules/app-services/.../gpl/LambdaMartReranker.java` — LightGBM 2-feature reranker

**qmd sources read:**
- `src/store.ts` (lines 2909–3100) — `hybridQuery` full pipeline, `expandQuery`, `rerank`, `reciprocalRankFusion`, `searchFTS`, `searchVec`
- `src/llm.ts` — `expandQuery()` prompt + GBNF grammar, `rerank()` via `RankingContext` pool, context pool management, model loading
- `finetune/reward.py` — GRPO reward function (140-point rubric)
- `finetune/data/qmd_expansion_handcrafted.jsonl` — 50 canonical training examples
- `src/bench-rerank.ts` — benchmark harness (no published results)
- `test/eval.test.ts` — Hit@K harness, 24 queries across 4 difficulty tiers
- `finetune/eval.py` — model evaluation script

**Nothing skipped.** Both checklist items fully investigated from source code.

---

## Findings

### 1. LLM Query Expansion — Typed routing with fine-tuned model

- **JustSearch baseline** — Morphological-only expansion (inflectional variants:
  plural, past tense, gerund, nominalization). TEXT+SIMPLE mode only, 1500ms
  budget, async fire-and-forget. Explicitly disabled for HYBRID because the dense
  vector leg already covers recall gaps. No semantic expansion, no hypothetical
  document generation. Expansion tokens appended to original query and re-searched
  with LUCENE syntax. Hallucination guard: all tokens must be pure alphabetic.

- **How it works** — qmd implements a three-type query expansion system using a
  purpose-trained 1.7B parameter model (`tobil/qmd-query-expansion-1.7B`, Q4_K_M
  GGUF, ~1.1 GB). The pipeline:

  1. **BM25 probe with strong-signal gating.** Before calling the LLM, qmd runs a
     BM25 search and checks if the top result is decisive: score >= 0.85 AND gap to
     second result >= 0.15. If so, expansion is skipped entirely — the BM25 result
     is strong enough. This avoids LLM latency on navigational/exact-keyword queries.

  2. **Grammar-constrained generation.** The expansion model is prompted with
     `/no_think Expand this search query: {query}` and constrained via a GBNF grammar:
     ```
     root ::= line+
     line ::= type ": " content "\n"
     type ::= "lex" | "vec" | "hyde"
     content ::= [^\n]+
     ```
     This guarantees structured output — every line is typed as `lex`, `vec`, or
     `hyde`. Generation params: temperature=0.7, topK=20, topP=0.8,
     repeatPenalty=0.5, maxTokens=600.

  3. **Typed routing.** Each expansion variant is routed to the appropriate
     retrieval backend:
     - `lex` (keyword variant) → FTS/BM25 only. Example: "ssh key auth setup"
     - `vec` (semantic paraphrase) → vector search only. Example: "how to set up
       ssh key-based authentication instead of passwords"
     - `hyde` (hypothetical document excerpt) → vector search only. Example:
       "Generate an SSH key pair with ssh-keygen -t ed25519. Copy the public key
       to ~/.ssh/authorized_keys..."

  4. **Batch embedding + parallel retrieval.** All vec/hyde texts are embedded in a
     single `embedBatch()` call, then vector lookups run with precomputed embeddings.
     FTS queries are synchronous and run first (instant). Original query gets both
     FTS and vector search.

  5. **Weighted RRF fusion.** The original query's result lists get 2x weight in
     RRF; expansion variants get 1x. RRF uses k=60 with top-rank bonuses (+0.05
     for rank 0, +0.02 for ranks 1-2). Fused results are sliced to candidateLimit=40
     before reranking.

  6. **Entity preservation guard.** After generation, each expansion line is checked:
     at least one query term must appear in the expansion text. Lines that fail this
     check are discarded. On total failure, a fallback is used:
     `[{hyde: "Information about {query}"}, {lex: query}, {vec: query}]`.

  7. **Caching.** Expansion results are cached in SQLite keyed by (query,
     model_hash). Repeated queries skip the LLM entirely.

- **Evidence** —
  - The expansion model was trained via SFT + GRPO on 50 handcrafted examples
    covering technical (SSH, Docker, Git, Python, Kubernetes) and historical
    topics. The GRPO reward function scores on 6 axes: Format (30pts), Diversity
    (30pts), HyDE quality (20pts), Output structure (20pts), Entity preservation
    (20pts), Think avoidance bonus (20pts). Total possible: 140 points, normalized
    to 0-1.
  - Harsh penalty: if any lex line echoes the original query verbatim, the total
    score is capped at 50%.
  - The eval harness (`test/eval.test.ts`) tests 24 queries across 4 difficulty
    tiers: Easy (exact keywords), Medium (semantic), Hard (vague/indirect), Fusion
    (requires both lexical + semantic). Hybrid thresholds: Easy >= 80% Hit@3,
    Medium >= 50% Hit@3, Hard >= 35% Hit@5, Fusion >= 50% Hit@3.
  - No published before/after metrics comparing expansion-on vs expansion-off.
    The evaluation framework tests the full pipeline (which includes expansion)
    but does not isolate expansion's contribution.
  - No latency numbers published. The README mentions "~1s penalty" for model
    reload after 5-min inactivity timeout, but no steady-state expansion latency.

- **Applicability** — Needs adaptation. The three core ideas are transferable:
  1. **Strong-signal gating** is directly applicable — JustSearch could skip LLM
     expansion when BM25 top-1 score is decisive, saving latency on easy queries.
     JustSearch already has a 1500ms budget for expansion; gating would make many
     queries complete in <100ms.
  2. **Typed routing** (lex→BM25, vec/hyde→KNN) is architecturally interesting but
     requires changes to JustSearch's expansion pipeline. Currently expansion
     tokens are appended to the original query and re-searched as a single BM25
     query. Routing vec/hyde variants to KNN would require the Head process to
     issue a second gRPC call to the Worker for vector search, or (better) send
     expansion variants alongside the original query so the Worker can do
     multi-path retrieval in one call.
  3. **HyDE (Hypothetical Document Embeddings)** is the most novel technique.
     Instead of expanding the query, the LLM generates what a relevant document
     would say, and that text is embedded for vector search. This can bridge the
     query-document vocabulary gap. However, it requires a capable generative
     model (~1B+ params) at query time.

  **Not transferable:** The fine-tuned 1.7B expansion model itself. JustSearch
  would need to either (a) fine-tune a similar model on domain-relevant data,
  (b) use a general-purpose chat model with a structured prompt, or (c) use
  llama.cpp's GBNF grammar with the user's existing chat model to get structured
  lex/vec/hyde output.

- **Adoption cost** — Medium-high.
  - **Strong-signal gating:** Low cost. Add a score threshold check in
    `KnowledgeHttpApiAdapter` before firing the expansion LLM call. ~20 lines.
  - **Typed routing:** Medium cost. Requires protocol changes: the expansion
    response must carry typed variants, and the Worker's `SearchOrchestrator`
    must accept multiple query paths (BM25 terms + KNN vectors) in a single
    request. Touches `indexing.proto`, `SearchOrchestrator`, and
    `KnowledgeHttpApiAdapter`. Alternatively, the Head process issues parallel
    gRPC calls (simpler but higher latency).
  - **HyDE:** High cost. Requires the generative model to be available at
    search time (currently only used for chat, not search). GBNF grammar
    support would need to be wired through the FFM bridge. The expansion prompt
    and generation parameters would need tuning.
  - **Affected modules:** `modules/app-services` (Head), `modules/indexer-worker`
    (Worker), `modules/ipc-common` (proto), potentially `modules/ai-bridge`
    (GBNF grammar wiring).

- **Expected impact** — Uncertain without benchmarks. qmd publishes no
  before/after numbers isolating expansion's contribution. Theoretical analysis:
  - Strong-signal gating: pure latency win, no quality change.
  - Typed routing (lex→BM25, vec→KNN): marginal quality improvement for queries
    where keyword variants and semantic paraphrases retrieve different relevant
    documents. Most benefit on "fusion" queries that need both signals.
  - HyDE: published literature (Gao et al., 2022) reports +5-20% recall
    improvement on zero-shot retrieval tasks, but with significant latency cost
    (500ms-2s for generation). Benefit decreases when the retrieval system
    already has strong dense search (as JustSearch does in HYBRID mode).
  - JustSearch's existing morphological expansion is already disabled for HYBRID
    mode with the rationale that "the dense vector leg already addresses recall
    gaps." The same rationale applies to semantic expansion — adding it to HYBRID
    mode may provide diminishing returns.

### 2. LLM-as-Reranker via GGUF — Qwen3-Reranker through llama.cpp

- **JustSearch baseline** — ONNX cross-encoder (ms-marco-MiniLM-L6-v2, ~22M
  params). TEXT mode only, 200ms deadline, top-20 candidates. Input:
  `title + " " + query_focused_snippet` (~1500 chars). Mutually exclusive with
  LambdaMART. GPU optional (CUDA via ONNX Runtime). The model produces a single
  relevance logit per query-document pair.

- **How it works** — qmd uses Qwen3-Reranker-0.6B (~640MB, Q8_0 GGUF) loaded
  through `node-llama-cpp`'s `LlamaRankingContext` API. The pipeline:

  1. **Context pool.** Multiple ranking contexts are created in parallel:
     `min(8, floor(freeVRAM * 0.25 / perContextMB))` on GPU, or
     `floor(cpuCores / 4)` on CPU. Each context: 2048 tokens, flash attention
     enabled. This allows parallel document scoring.

  2. **Token budget.** Each document is truncated to fit:
     `maxDocTokens = 2048 - 200 (template overhead) - queryTokens`. The model
     tokenizes the document, slices to `maxDocTokens`, then detokenizes back to
     text.

  3. **Chunk-first reranking.** qmd does NOT rerank full documents. Before
     reranking, each candidate is chunked (900 tokens, 15% overlap), and the best
     chunk per document is selected by keyword overlap with the query. Only this
     chunk is sent to the reranker. This is critical for performance — reranking
     full bodies is O(tokens) and would be prohibitively slow.

  4. **Parallel scoring.** Documents are distributed across the context pool in
     round-robin chunks. Each context calls `rankAll(query, chunk)` — a
     first-class llama.cpp API that internally computes the yes/no token logprob
     difference as the relevance score. All contexts run in parallel via
     `Promise.all`.

  5. **Position-aware score blending.** After reranking, the reranker score is
     blended with the RRF retrieval score using position-dependent weights:
     - RRF rank 1-3: 75% RRF + 25% reranker (protect strong retrieval signals)
     - RRF rank 4-10: 60% RRF + 40% reranker (balanced)
     - RRF rank 11+: 40% RRF + 60% reranker (trust reranker for long-tail)
     The RRF score is computed as `1 / rrfRank` (not the raw RRF fusion score).
     The reranker's raw score is used directly (not normalized to [0,1]).

  6. **Caching.** Reranker scores are cached in SQLite keyed by
     `(query, file, model, chunk_text)`. Cache includes the chunk text because
     the reranker is chunk-sensitive — same file with different chunk selection
     should produce different scores.

- **Evidence** —
  - Qwen3-Reranker-0.6B is a decoder-only LLM fine-tuned for reranking via the
    "yes"/"no" token logprob method. It's architecturally different from
    JustSearch's cross-encoder (ms-marco-MiniLM-L6-v2), which is an
    encoder-only model producing a relevance logit.
  - `bench-rerank.ts` tests parallelism configurations [1, 2, 4, 8] contexts
    with 40 documents, 3 iterations. Measures median latency, throughput
    (docs/sec), peak RSS, VRAM per context. No published benchmark results in
    the repo or README.
  - Qwen3-Reranker-0.6B has 600M params vs MiniLM-L6-v2's 22M params — 27x
    larger. Published Qwen3-Reranker results (Qwen team blog) claim competitive
    with larger cross-encoders on BEIR. Being decoder-only, it can leverage
    llama.cpp's GPU acceleration, KV caching, and flash attention.
  - The position-aware blending is a notable design choice: rather than fully
    trusting the reranker (which could demote a strong BM25 hit that the reranker
    misjudges), the system protects high-retrieval-confidence results. This is
    similar in spirit to JustSearch's "low-signal gating" for vector scores.

- **Applicability** — Needs adaptation. Two transferable patterns, one
  non-transferable:

  1. **GGUF reranker via llama.cpp** is partially applicable. JustSearch already
     has a llama.cpp FFM bridge (`modules/ai-bridge`), but it's wired for
     generative inference (chat), not ranking. llama.cpp's C API does have
     ranking capabilities, but JustSearch would need to:
     - Add a new FFM binding for ranking (or repurpose the embedding path)
     - Handle the single-tenant GPU policy: the reranker model would compete
       with the embedding model and chat model for VRAM. qmd runs all three
       models in the same process with lazy loading and 5-min inactivity dispose.
       JustSearch's architecture has separate processes (Head, Worker, Brain)
       which complicates shared model management.
     - The ONNX cross-encoder is significantly faster per-document (22M params
       vs 600M) but potentially lower quality. The right comparison is quality
       per latency unit, not raw model size.

  2. **Position-aware score blending** is directly applicable. JustSearch
     currently replaces the retrieval order entirely when cross-encoder fires
     (top-K are reranked, rest unchanged). Blending retrieval and reranker scores
     by position would be a low-cost improvement that protects against reranker
     failures. Implementation: ~30 lines in `KnowledgeHttpApiAdapter`.

  3. **Chunk-first reranking** is already partially implemented in JustSearch.
     The cross-encoder receives `title + " " + query_focused_snippet` (~1500
     chars), not the full document body. qmd's approach is more systematic —
     chunk the document first, pick the best chunk by keyword overlap, then
     rerank that chunk. JustSearch's snippet extraction is simpler (first query
     match with surrounding context) but serves the same purpose.

  **Not transferable:** `node-llama-cpp`'s `RankingContext` API. This is a
  JavaScript-specific binding. JustSearch would need to implement the equivalent
  via the C API (`llama_decode` + logprob extraction for yes/no tokens) through
  its existing FFM bridge, or use ONNX Runtime with a GGUF-to-ONNX conversion.

- **Adoption cost** — Variable by pattern:
  - **Position-aware blending:** Low cost. Modify `KnowledgeHttpApiAdapter` where
    cross-encoder results are applied. Instead of reordering top-K by reranker
    score alone, blend with retrieval position. ~30 lines, single file.
  - **GGUF reranker via llama.cpp:** High cost. New FFM bindings in
    `modules/ai-bridge`, GPU scheduling changes (mutual exclusion with
    embedding and chat), model download/discovery in `modules/reranker`,
    new gRPC endpoint or Head-side inference. Touches 4+ modules.
  - **Chunk-first reranking improvement:** Low-medium cost. Replace the current
    snippet extraction with proper chunking + keyword-overlap selection.
    Mostly contained in `modules/reranker`. The ChunkSplitter already exists
    in the indexing pipeline — could be reused at rerank time.

- **Expected impact** —
  - **Position-aware blending:** Small but positive. Protects against reranker
    disagreements with strong retrieval signals. Expected to reduce the cases
    where the cross-encoder demotes a clearly relevant BM25 top hit. No
    published numbers from qmd, but the pattern is sound — it's a
    conservative interpolation that preserves retrieval signal.
  - **GGUF reranker (Qwen3-Reranker-0.6B):** Potentially significant quality
    improvement over MiniLM-L6-v2 (27x more parameters, decoder architecture
    with richer language understanding). However, latency would increase
    substantially. MiniLM-L6-v2 can score 20 documents in <200ms on CPU;
    Qwen3-Reranker-0.6B would likely need GPU and 500ms+ even with parallel
    contexts. The quality/latency trade-off depends on JustSearch's use case
    — interactive search favors speed (current approach), while RAG/chat
    pipelines could tolerate longer reranking.
  - **Chunk-first reranking:** Marginal improvement. JustSearch's current
    snippet approach already targets the most relevant portion of each document.
    Systematic chunking might capture better chunks for documents where the
    query-relevant section isn't near the first keyword match.

---

## Additional Findings

### A1. Strong-signal gating as a latency optimization

qmd's BM25 probe before expansion is an elegant latency optimization pattern.
The insight: for queries where keyword search already produces a high-confidence
result (score >= 0.85, gap to rank 2 >= 0.15), the expensive LLM expansion call
adds latency without improving quality. JustSearch could adopt this pattern not
just for expansion but for any optional search enhancement — skip cross-encoder
reranking when BM25 top-1 is decisive, skip SPLADE when BM25 alone suffices.
The thresholds would need calibration for JustSearch's score distribution.

### A2. GBNF grammar for structured LLM output

qmd uses llama.cpp's native GBNF grammar to guarantee structured output from the
expansion model. This is directly relevant to §7 key question #7 (should
JustSearch wire GBNF grammar?). qmd's grammar is simple but effective — 4 lines
constraining output to typed lines. JustSearch's `JsonGrammarGuard` is a regex
character filter that doesn't enforce schema structure. Wiring GBNF via the FFM
bridge would benefit any JustSearch feature that needs structured LLM output
(expansion, chat citations, settings inference).

### A3. Fine-tuning a tiny model for a specific task

qmd trains a 1.7B model specifically for query expansion rather than using a
general-purpose chat model. The training uses only 50 handcrafted examples +
GRPO with a rule-based reward function (no LLM judge). This is a compelling
pattern: a tiny purpose-trained model outperforms prompt-engineering a larger
model, at lower latency and VRAM cost. However, it requires maintaining a
training pipeline and model artifacts, which adds operational complexity for a
desktop app.

### A4. Inactivity-based model lifecycle

qmd lazy-loads models on first use and disposes them after 5 minutes of
inactivity (`DEFAULT_INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000`). This is similar
to JustSearch's approach but applied to all three models (embedding, generation,
ranking) independently. The pattern prevents VRAM exhaustion when models aren't
actively needed, with a ~1s cold-start penalty on reload. JustSearch's Brain
process already manages model lifecycle but at a coarser granularity (process-
level activate/deactivate rather than per-model dispose).

---

## Unresolved Items

- **Expansion quality impact** — qmd publishes no before/after metrics isolating
  expansion's contribution to search quality. The eval harness tests the full
  pipeline but does not compare expansion-on vs expansion-off. To assess this for
  JustSearch, we would need to run our own A/B evaluation (e.g., on BEIR datasets)
  with and without LLM expansion.

- **GGUF reranker latency** — `bench-rerank.ts` exists but no results are
  published in the repo, README, or any discoverable blog post. We don't know the
  actual throughput of Qwen3-Reranker-0.6B at different parallelism levels. To
  assess feasibility for JustSearch's 200ms deadline, we would need to either (a)
  run qmd's benchmark locally, or (b) benchmark the model independently through
  llama.cpp.

- **Expansion model quality vs general-purpose model** — No comparison exists
  between the fine-tuned 1.7B expansion model and using a general-purpose chat
  model (e.g., Qwen3-4B, Llama 3.1-8B) with the same structured prompt. It's
  unclear how much of the expansion quality comes from the fine-tuning vs the
  prompt format + grammar constraint.

---

## Cross-Cutting Observations

### §7 Question #5: Can LLM query expansion improve recall without unacceptable latency?

qmd demonstrates a working implementation but provides no quantitative evidence
of recall improvement. The architecture is sound: typed routing ensures lex
variants improve BM25 recall while vec/hyde variants improve vector recall,
without cross-contamination. The strong-signal gating avoids latency on easy
queries. However, the lack of published before/after metrics means this question
remains **partially answered** — the "can it be built" is answered (yes), but the
"does it improve recall measurably" is not.

Key design insight: qmd disables expansion when BM25 is confident, which means
expansion only fires on queries where the system is uncertain — exactly the cases
where recall improvement is most needed. This conditional activation strategy is
more efficient than always-on expansion.

### §7 Question #7: Should JustSearch wire llama.cpp's native GBNF grammar?

qmd's query expansion relies critically on GBNF grammar to produce structured
output. The grammar is 4 lines and guarantees every output line is typed. This is
stronger evidence that GBNF wiring would be valuable — not just for JSON
enforcement (the original question), but for any structured LLM output task
including query expansion. qmd's implementation via `node-llama-cpp` wraps the
same underlying `llama_sampler_init_grammar()` C API that JustSearch would wire
through FFM.

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.

---

## sentencetransformers-findings

*(from `249-sentencetransformers-findings.md`)*

## Recon Summary

**JustSearch baseline loaded from:** tempdoc 245 (eval results, quantization experiments),
`docs/explanation/05-ai-architecture.md` (embedding backend), and source code:
`EmbeddingService.java` (model loading, prefixes, 768-dim fixed), `EmbeddingModelResolver.java`
(Q8_0 > Q4_K_M discovery), `FieldMapper.java` (KnnFloatVectorField), `ReadPathOps.java`
(KnnFloatVectorQuery), `KnowledgeHttpApiAdapter.java` (cross-encoder gating).

**External sources read:** SentenceTransformers GitHub (`MultipleNegativesRankingLoss.py`,
`MatryoshkaLoss.py`, `GISTEmbedLoss.py`, `SpladeLoss.py`, `SpladePooling.py`), sbert.net
docs (training overview, loss overview, evaluation overview, cross-encoder training, pretrained
models), HuggingFace blog (train-reranker, matryoshka introduction), nomic-embed-text-v1.5
model card.

**Nothing skipped.** All 6 checklist items investigated.

---

## Findings

### 1. Loss Functions for Retrieval — How Embedding Quality Is Made

- **JustSearch baseline** — Uses nomic-embed-text-v1.5, a production embedding model trained
  with a two-stage pipeline: (1) unsupervised contrastive pretraining on weakly-related pairs
  (StackExchange Q&A, Amazon reviews, news), then (2) supervised finetuning on labeled search
  data with hard-example mining. JustSearch consumes the model but does not train it.

- **How it works** — SentenceTransformers provides 20+ loss functions for dense embeddings.
  The three most relevant for retrieval:

  **MultipleNegativesRankingLoss (MNRL):** The standard retrieval loss. Given a batch of N
  (anchor, positive) pairs, computes an NxN similarity matrix where diagonal entries are true
  positives and all off-diagonal entries are in-batch negatives. Cross-entropy loss over rows
  with `scale=20.0` (temperature=0.05). Quality is **directly proportional to batch size** —
  more in-batch negatives = stronger training signal. `CachedMNRL` variant enables effective
  batch sizes of 65K+ on a single GPU via gradient caching.

  **GISTEmbedLoss:** Improves on MNRL by adding a frozen "guide" model that identifies
  false negatives in the batch. For each candidate negative, the guide computes similarity
  to the anchor; if it exceeds the guide's positive similarity minus a margin, the negative
  is suppressed (score set to -inf). This prevents the model from being penalized for pushing
  away semantically similar documents that happen to be in the same batch. Cost: ~2x forward
  passes per batch (guide runs under `no_grad`).

  **MatryoshkaLoss:** A wrapper that runs any base loss (MNRL, GISTEmbedLoss, etc.) at
  multiple truncated dimensions simultaneously (e.g., [768, 512, 256, 128, 64]). Each
  dimension's loss is weighted and summed. The model learns to frontload important information
  in early dimensions. Implementation: monkey-patches `model.forward` to cache the full-dim
  output, then slices + re-L2-normalizes for each smaller dimension. Negligible training
  overhead since the transformer runs only once.

- **Evidence** — MNRL is the most widely used retrieval loss; all top MTEB models use it
  or its cached variant. GISTEmbedLoss was introduced to address MNRL's false-negative
  problem, which is documented as the primary quality limitation of in-batch negative
  sampling. nomic-embed-text-v1.5 was trained with contrastive + supervised finetuning
  (documented on HuggingFace model card).

- **Applicability** — **Reference only.** JustSearch does not train embedding models.
  Understanding these loss functions informs model selection: prefer models trained with
  GISTEmbedLoss or CachedMNRL (large effective batch size) over models trained with vanilla
  MNRL on small batches. When evaluating new embedding models, check training methodology
  in model cards.

- **Adoption cost** — Zero (informational finding).

- **Expected impact** — Indirect. Better model selection criteria for future embedding model
  upgrades.

### 2. Hard Negative Failure Modes — Why GPL-LambdaMART Hurts

- **JustSearch baseline** — GPL-trained LambdaMART consistently degrades quality across all
  3 evaluated datasets: SciFact -0.009, Arguana -0.10, NFCorpus -0.021 (hybrid nDCG vs RRF).
  GPL generates synthetic queries from an 8B LLM, scores (query, doc) pairs with a
  cross-encoder, then uses these as LambdaMART training features. The training eval nDCG
  (0.76-0.88) does not transfer to real BEIR queries.

- **How it works** — SentenceTransformers v4 documents this as a known anti-pattern:

  > "If you only use hard negatives, your model may unexpectedly perform worse for easier
  > tasks. This can mean that reranking the top 200 results from a first-stage retrieval
  > system can actually give worse top-10 results than reranking the top 100."

  The `mine_hard_negatives()` utility provides explicit controls to mitigate this:
  - `range_min` / `range_max`: rank band from which negatives are sampled (skip the closest
    matches to avoid false negatives)
  - `absolute_margin` / `relative_margin`: minimum score gap between positive and negative
  - `max_score`: ceiling on similarity to exclude likely false negatives
  - `sampling_strategy`: `"top"` (hardest in range) or `"random"` (uniform from range)

  The recommended approach is to **mix hard negatives (60-70%) with random/soft negatives
  (30-40%)**. Hard-only training makes the reranker overly strict — it learns to reject
  anything that isn't a near-exact match, which is counterproductive when reranking a pool
  of roughly-relevant documents.

  GPL's failure maps exactly to this anti-pattern: synthetic queries from an LLM produce a
  narrow distribution of "hard" negatives (documents the cross-encoder scores highly but
  aren't the gold answer). There are no random/easy negatives to teach the model that
  "somewhat relevant" is better than "irrelevant." The LambdaMART model learns to penalize
  all non-perfect matches.

- **Evidence** — HuggingFace blog "Train Your Own Reranker" (SentenceTransformers v4)
  explicitly documents the hard-negative-only failure mode. The blog shows a 150M
  domain-finetuned model (77.14 nDCG@10) outperforming a 1.54B general-purpose reranker
  (75.40) — demonstrating that training data quality matters more than model size.

- **Applicability** — **Directly applicable.** If JustSearch ever retrains LambdaMART or
  a cross-encoder, the training data must mix hard + random negatives.

- **Adoption cost** — Changes to the GPL training pipeline (`beir-eval-win.ps1` and any
  LambdaMART training scripts). The `mine_hard_negatives()` utility is Python-only, but the
  principle (mix hard + random) can be applied in any training framework. Estimated: 1-2
  files, low effort to adjust negative sampling ratio.

- **Expected impact** — Potentially large. The -0.009 to -0.10 nDCG penalty from
  GPL-LambdaMART would likely be recovered or reversed with proper negative mixing.
  However, this requires real user queries — GPL synthetic queries may be fundamentally
  too narrow regardless of negative mixing strategy.

### 3. Matryoshka Viability for JustSearch — 256-dim Truncation

- **JustSearch baseline** — Fixed 768-dim embeddings throughout. `EmbeddingService` produces
  768-dim float[], `FieldMapper` stores them as `KnnFloatVectorField`, `ReadPathOps` queries
  with `KnnFloatVectorQuery`. Dimension is validated against config
  (`ResolvedConfig.Index.vectorDimension`). No truncation or post-processing pipeline exists.

- **How it works** — nomic-embed-text-v1.5 was trained with MatryoshkaLoss and natively
  supports truncation to 512, 256, 128, or 64 dimensions. Performance at each level (MTEB
  aggregate):

  | Dimension | MTEB Score | Delta from 768 | Storage Reduction |
  |-----------|-----------|---------------|-------------------|
  | 768 (full) | 62.28 | — | — |
  | 512 | 61.96 | -0.32 (-0.5%) | 33% |
  | 256 | 61.04 | -1.24 (-2.0%) | 67% |
  | 128 | 59.34 | -2.94 (-4.7%) | 83% |
  | 64 | 56.10 | -6.18 (-9.9%) | 92% |

  **Critical post-processing requirement:** Matryoshka truncation requires three steps in
  order: (1) layer normalization, (2) prefix truncation to target dimension, (3) L2
  normalization. Skipping layer normalization produces degraded results because the prefix
  of a unit-norm vector is not itself unit-norm; the first dimensions may have different
  variance characteristics than later ones.

  MatryoshkaLoss trains all dimensions simultaneously by running the base loss on each
  truncated prefix. The `shrink()` function in the source is simply
  `F.normalize(tensor[..., :dim], p=2, dim=-1)`. The model learns to frontload the most
  discriminative information in early dimensions.

  For retrieval specifically: NanoBEIR evaluation shows 2x dimension reduction (768→384)
  costs only 1.47% nDCG@10 (0.5031→0.4957). At 4x reduction (768→192), the cost is ~3%.

- **Evidence** — nomic-embed-text-v1.5 model card (MTEB scores at each dimension),
  Kusupati et al. 2022 (original Matryoshka paper), SentenceTransformers Matryoshka
  training example (NanoBEIR benchmarks), HuggingFace Matryoshka blog (98.37% performance
  retention at 64-dim on STSBenchmark).

- **Applicability** — **Directly applicable.** JustSearch already uses nomic-embed-text-v1.5,
  which has native Matryoshka support. No model change needed.

- **Adoption cost** — Medium. Changes required:
  1. `EmbeddingService.java` — Add post-processing after `embed()`: layer normalization →
     prefix truncation → L2 normalization. The layer normalization is the non-trivial part;
     it requires computing mean and variance across the 768 dimensions, then normalizing
     before truncation. (~30 lines)
  2. `ResolvedConfig.java` / `ResolvedConfigBuilder.java` — Add configurable
     `vectorDimension` (default 768, option for 256). Propagate to FieldCatalogDef.
  3. `FieldMapper.java` — Already dimension-aware; will work with truncated vectors.
  4. `ReadPathOps.java` / `ChunkSearchOps.java` — Query vectors must be truncated with
     the same pipeline before KNN search.
  5. Schema migration — Dimension change requires full reindex (HNSW structure is
     dimension-dependent). Blue/green migration or explicit reindex trigger.
  6. **GGUF complication** — llama.cpp embedding output is raw 768-dim. The layer
     normalization step is normally done inside the model (HuggingFace Transformers). With
     GGUF, this normalization must be done externally in Java. Need to verify whether
     llama.cpp's embedding output already includes layer norm or not. This is the primary
     uncertainty.

- **Expected impact** — At 256-dim: 67% vector storage reduction, ~3x KNN search speed
  (HNSW distance computation scales linearly with dimension), -1.24 MTEB points aggregate
  loss. **Needs JustSearch-specific BEIR eval** to measure retrieval-specific impact (MTEB
  aggregate includes non-retrieval tasks like classification and clustering).

### 4. SPLADE Training Internals — Understanding the Model We Use

- **JustSearch baseline** — Uses NAVER SPLADE-v3 (BEIR-13 nDCG@10 = 51.7, 109M params).
  Stored in Lucene FeatureField. Sparse vectors are generated at both index time and query
  time. FeatureField has an 8-bit mantissa limitation and (0, 64] weight range.

- **How it works** — SentenceTransformers provides the complete SPLADE training stack:

  **SpladePooling:** Converts MLM logits to sparse vectors. Pipeline: raw logits →
  attention mask → `ReLU` → `log1p` → max-pool across token dimension. Output shape:
  `[batch_size, vocab_size]` (~30K-50K dimensions, mostly zeros). The `log1p(ReLU(x))`
  activation is the standard SPLADE activation — ReLU zeroes negative logits, log1p
  compresses large positive values to prevent extreme weights.

  **SpladeLoss:** Wraps a base ranking loss (e.g., `SparseMultipleNegativesRankingLoss`)
  and adds FLOPS regularization. The regularizer penalizes non-zero activations to enforce
  sparsity — fewer non-zero terms = faster inverted index lookup. Two separate weights
  control query vs document sparsity: `query_regularizer_weight` (typically 5e-5) and
  `document_regularizer_weight` (typically 3e-5). Heavier document regularization is
  standard because documents are indexed once but queried many times — sparser documents
  reduce index size and search latency.

  **FLOPS regularization formula:** `FlopsLoss(X) = sum_j(mean_i(|x_ij|))^2` — for each
  vocabulary term j, compute the mean absolute activation across all samples i in the batch,
  then sum the squares. This penalizes terms that are active across many documents (common
  terms that don't discriminate). Optional `threshold` parameter only penalizes embeddings
  exceeding a target sparsity level.

  **"Wacky weights" problem** (documented in arXiv 2110.11540): Without sufficient FLOPS
  regularization, SPLADE can assign extreme weights to stopwords (the, a, is), causing
  50x query latency slowdowns. These weights can also exceed Lucene FeatureField's (0, 64]
  range. SPLADE-v3 uses regularization to control this, but the issue is inherent to the
  architecture.

- **Evidence** — SentenceTransformers source code (`SpladeLoss.py`, `SpladePooling.py`),
  SPLADE paper (Formal et al., SIGIR 2021), "Wacky Weights" paper (Mackenzie et al.,
  arXiv 2110.11540). SentenceTransformers SPLADE benchmark: NAVER SPLADE-v3 = 51.7
  BEIR-13 nDCG@10, vs BM25 baseline 45.6 (+6.1 points).

- **Applicability** — **Reference.** JustSearch uses SPLADE-v3 as a black box. Understanding
  the training internals informs:
  1. Weight range verification: SPLADE-v3's regularization keeps weights within FeatureField's
     (0, 64] range for well-trained models, but JustSearch should add a clamping safety net.
  2. Model selection: inference-free SPLADE variants (doc-only expansion, no query-side
     neural inference) could eliminate query-time SPLADE latency entirely. OpenSearch's
     `neural-sparse-encoding-doc-v3-gte` achieves 54.6 BEIR-13 nDCG@10 — 2.9 points above
     SPLADE-v3 — with zero query-side overhead.
  3. Understanding why SPLADE fails on Arguana: argument text has high term overlap between
     relevant and irrelevant documents. SPLADE's learned term weighting doesn't help when
     the discriminative signal isn't in individual terms but in their composition.

- **Adoption cost** — Zero for understanding; low for weight clamping (~5 lines in
  `LuceneIndexRuntime.java`). Inference-free SPLADE model swap is a separate investigation.

- **Expected impact** — Informational. Weight clamping prevents a theoretical edge case.
  Inference-free SPLADE model swap could eliminate query-side SPLADE latency (~10-50ms
  per query) while improving quality by +2.9 nDCG points.

### 5. Cross-Encoder Training and Selection — MiniLM-L6-v2 Validated

- **JustSearch baseline** — Uses `cross-encoder/ms-marco-MiniLM-L6-v2` (ONNX, CPU/GPU).
  Only fires in TEXT mode (not HYBRID). Adds +1.3 nDCG over BM25-only on SciFact.

- **How it works** — SentenceTransformers v4 provides a complete cross-encoder training
  framework. Key findings for JustSearch:

  **MiniLM-L6-v2 is the documented sweet spot.** MS MARCO benchmark from sbert.net:

  | Model | Layers | nDCG@10 (TREC DL 19) | MRR@10 | Throughput |
  |-------|--------|---------------------|--------|-----------|
  | MiniLM-L2-v2 | 2 | 71.01 | 34.85 | 4,100 docs/s |
  | MiniLM-L4-v2 | 4 | 73.04 | 37.70 | 2,500 docs/s |
  | **MiniLM-L6-v2** | **6** | **74.30** | **39.01** | **1,800 docs/s** |
  | MiniLM-L12-v2 | 12 | 74.31 | 39.02 | 960 docs/s |

  L6 matches L12 quality at 2x throughput. L2 is 5x faster at only -4.5 nDCG — worth
  considering if latency is critical.

  **Domain finetuning beats model size.** A 150M domain-finetuned model (77.14 nDCG@10)
  outperforms a 1.54B general-purpose reranker (75.40 nDCG@10). This means if JustSearch
  ever needs a better reranker, finetuning MiniLM-L6 on domain data would likely outperform
  switching to a larger model.

  **Training requires mixed negatives.** The train-reranker blog recommends
  `mine_hard_negatives()` with `range_min=10` (skip top-10 to avoid false negatives),
  `range_max=100`, and `sampling_strategy="top"`. Then mix in random negatives for 30-40%
  of the training set. Use `BinaryCrossEntropyLoss` with `pos_weight=torch.tensor(num_negatives)`
  to balance the positive/negative ratio.

  **CrossEncoderNanoBEIREvaluator** evaluates on 11 NanoBEIR datasets with BM25 first-stage
  (reranks top-100, not exhaustive) — this matches realistic pipeline usage.

- **Evidence** — sbert.net cross-encoder pretrained models page (MS MARCO benchmarks),
  HuggingFace train-reranker blog (150M vs 1.54B comparison, negative mixing guidance),
  sbert.net cross-encoder training overview.

- **Applicability** — **Directly applicable** for two decisions:
  1. JustSearch's MiniLM-L6-v2 choice is validated as optimal for the speed/quality trade-off.
  2. If a custom reranker is ever trained, the recipe is clear: finetune MiniLM-L6 on
     domain data with mixed hard+random negatives.

- **Adoption cost** — Zero (validates existing choice). Custom reranker training would
  require a Python training pipeline (~1 day effort) plus ONNX export.

- **Expected impact** — Confirms current architecture. A domain-finetuned cross-encoder
  could potentially add +2-3 nDCG points over the general-purpose MiniLM-L6, based on
  the 150M vs 1.54B comparison showing domain > size.

### 6. Evaluation Methodology — NanoBEIR and Metric Gaps

- **JustSearch baseline** — Manual PowerShell eval script (`beir-eval-win.ps1`). 3 BEIR
  datasets (SciFact, Arguana, NFCorpus). nDCG@10 only. No MRR, no Recall@k, no significance
  testing. No automated multi-dataset evaluation.

- **How it works** — SentenceTransformers provides automated evaluators:

  **NanoBEIREvaluator:** Loads 13 downsized BEIR datasets from HuggingFace automatically.
  Computes 5 metric families per dataset: nDCG@10, MRR@10, MAP@100, Recall@k, Accuracy@k
  (k=1,3,5,10). Reports per-dataset and aggregate mean scores. Zero configuration required.

  **CrossEncoderNanoBEIREvaluator:** Same 13 datasets but with BM25 first-stage retrieval
  + cross-encoder reranking of top-100. Excludes Arguana and touche2020 by default. Metrics:
  MRR@k, nDCG@k, MAP per dataset + aggregate.

  **SparseNanoBEIREvaluator:** Dedicated evaluator for SPLADE-style models. Mirrors the
  dense NanoBEIR evaluator with sparse-aware similarity computation.

  **InformationRetrievalEvaluator:** Configurable evaluator for custom datasets. Supports
  custom k-values, multiple score functions, prompt injection, and prediction export.

  **Metric gap:** JustSearch reports only nDCG@10. For desktop search (primarily known-item
  refinding), **MRR** (rank of the one right document) and **Precision@1** are more
  meaningful than nDCG@10 (which weights multiple relevant documents by position). Tempdoc
  245 already flags this as unknown #7.

  **Significance testing gap:** Neither JustSearch nor SentenceTransformers' standard
  evaluation tooling includes statistical significance testing. This is a field-wide gap —
  significance testing appears in academic BEIR papers (PyTerrier implements paired t-tests
  with Bonferroni correction) but not in practitioner evaluation frameworks. JustSearch's
  nDCG differences of <0.01-0.02 are likely within noise for 300-query evals.

- **Evidence** — sbert.net evaluation overview (9 evaluator types), NanoBEIREvaluator docs
  (13 datasets, 5 metric families), CrossEncoderNanoBEIREvaluator docs (BM25 first-stage).

- **Applicability** — **Needs adaptation.** JustSearch can't use NanoBEIREvaluator directly
  (it's a Python evaluation framework for HuggingFace models, not a Lucene search pipeline).
  But the dataset selection (13 NanoBEIR datasets) and metric families (nDCG + MRR + MAP +
  Recall + Accuracy) are directly adoptable as the standard to measure against.

- **Adoption cost** — Medium. Three improvements:
  1. **Add MRR@10 to eval script** — ~20 lines in `beir-eval-win.ps1`. Low effort, high
     value for desktop search relevance.
  2. **Expand dataset coverage** — Add NanoBEIR datasets beyond the current 3. Each dataset
     requires BEIR-format conversion + ingestion into JustSearch. ~0.5 day per dataset.
  3. **Significance testing** — Bootstrap resampling or paired t-tests on per-query scores.
     Requires storing per-query nDCG (not just aggregate), then running stats. ~50 lines.

- **Expected impact** — MRR@10 would reveal whether JustSearch puts the right document at
  rank 1 (critical for desktop search). Expanded dataset coverage would catch regressions
  that are invisible on 3 datasets (e.g., the Arguana anomaly where RRF catastrophically
  hurts). Significance testing would distinguish real improvements from noise.

---

## Additional Findings

### A. Inference-Free SPLADE Models

SentenceTransformers benchmarks show inference-free SPLADE variants (document-side expansion
only, query-time uses IDF-weighted tokenization instead of neural inference) achieving
state-of-the-art quality: `opensearch-neural-sparse-encoding-doc-v3-gte` at 54.6 BEIR-13
nDCG@10, +2.9 points above SPLADE-v3 (51.7). These eliminate query-time SPLADE latency
entirely. Worth a dedicated investigation for query latency reduction.

### B. GISTEmbedLoss for False-Negative Filtering

GISTEmbedLoss addresses a fundamental limitation of MNRL-based training: in-batch false
negatives. If the training batch contains semantically similar documents with different
labels, MNRL penalizes them as negatives, actively harming embedding quality. GISTEmbedLoss
uses a frozen guide model to suppress these false negatives. When evaluating new embedding
models, prefer those trained with GISTEmbedLoss or equivalent false-negative mitigation.

### C. Matryoshka Two-Stage Retrieval Pattern

The Matryoshka community documents a powerful two-stage pattern: search with truncated
embeddings (fast, approximate), then rerank with full-dimension embeddings (accurate).
This is distinct from cross-encoder reranking — it uses the same model at different
dimensions. Could be composed with JustSearch's existing cross-encoder reranking for a
three-stage pipeline: 256-dim KNN → 768-dim rescore → cross-encoder rerank.

### D. Cross-Encoder Direction Independence

`CrossEncoderNanoBEIREvaluator` uses BM25 first-stage retrieval, reranking only the
top-100 documents. This matches JustSearch's architecture (BM25/hybrid first-stage +
cross-encoder reranking) and validates the evaluation methodology. The evaluator's
default exclusion of Arguana and touche2020 aligns with JustSearch's own findings that
these datasets have quality issues.

### E. SPLADE Benchmark Context

JustSearch's SPLADE-v3 (51.7 BEIR-13 nDCG@10) is the second-best symmetric SPLADE model
on SentenceTransformers' benchmarks. The best symmetric model is OpenSearch's
neural-sparse-encoding-v2-distill at 52.8. The 1.1-point gap suggests limited headroom
from model-only improvements within the symmetric SPLADE architecture.

---

## Unresolved Items

1. **BEIR nDCG@10 at 256-dim for nomic-embed-text-v1.5** — The model card reports MTEB
   aggregate scores (62.28 → 61.04 at 256-dim) but not per-dataset BEIR retrieval scores
   at truncated dimensions. MTEB aggregate includes non-retrieval tasks (classification,
   clustering) which may mask retrieval-specific degradation. **Needs JustSearch BEIR eval
   at 256-dim** to measure actual retrieval impact.

2. **GGUF layer normalization behavior** — Matryoshka truncation requires layer normalization
   before truncation. In HuggingFace Transformers, this is handled inside the model. With
   GGUF via llama.cpp, it's unclear whether the embedding output already includes layer
   norm or whether JustSearch must apply it externally. **Needs llama.cpp embedding output
   verification** (compare raw llama.cpp output vs HuggingFace output for the same input).

3. **Quantization x Matryoshka interaction** — No documentation covers whether GGUF
   quantization (Q4/Q8) affects Matryoshka truncation quality differently than fp32. Q4
   already destroys instruction sensitivity; it may also degrade dimension-ordering quality
   that Matryoshka relies on. **Needs A/B eval**: Q8_0 at 768-dim vs Q8_0 at 256-dim.

---

## Cross-Cutting Observations

### Key Question #8: Is 256-dim Matryoshka Embedding Viable?

**Evidence from this investigation:** nomic-embed-text-v1.5 natively supports Matryoshka
with -1.24 MTEB points at 256-dim (-2.0%). NanoBEIR benchmarks show 2x dimension reduction
costs only 1.47% nDCG@10. The post-processing pipeline (layer norm → truncate → L2 norm)
is well-documented. The primary uncertainty is the GGUF layer normalization behavior and
quantization interaction. **Verdict: viable pending two verification experiments.**

### Key Question #9: Why Does GPL-Trained LambdaMART Hurt Quality?

**Evidence from this investigation:** SentenceTransformers v4 documents this as a known
anti-pattern. Training with only hard negatives makes rerankers overly strict. GPL's
synthetic query generation produces a narrow distribution of hard negatives without random
negatives to calibrate the model's discrimination threshold. The fix is to mix 60-70% hard
negatives with 30-40% random negatives — but this requires real user queries, which GPL
cannot provide. **Verdict: fully explained. GPL-LambdaMART is not recoverable without
real user query data.**

### Key Question #2: Best Score Normalization for Hybrid Fusion

**Partial evidence:** SentenceTransformers' evaluation framework confirms that embedding
similarity scores are not directly comparable across models (dense vs sparse). This
reinforces the finding from Vespa/OpenSearch investigations that per-query normalization
(min-max or z-score) before fusion is critical. JustSearch's CC implementation already
uses min-max normalization — this is architecturally correct.

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.

---

## vespa-findings

*(from `249-vespa-findings.md`)*

### 249: Vespa Investigation Findings

## Recon Summary

All 5 checklist items from §2.1 were fully answered from Vespa's public
documentation and blog posts. No source code reading was necessary. 12
external URLs were read across the five items.

### JustSearch baseline consulted

- `docs/explanation/18-adapters-lucene-deep-dive.md` — hybrid fusion architecture,
  RRF algorithm, HNSW config, FeatureField mapping, low-signal gating
- `docs/reference/contracts/search-pipeline-invariants.md` — blocked combinations
  (HYBRID blocks cross-encoder/expansion), LambdaMART as the HYBRID-mode reranker
- `docs/tempdocs/245-search-quality-strategy.md` — isolation matrix results
  (SciFact nDCG: BM25 0.660, Dense 0.671, SPLADE+Dense 0.704), GPL-LambdaMART
  hurts quality, SPLADE is biggest quality lever
- `docs/tempdocs/249-open-source-investigation-plan.md` §5.1 — Pyserini convex
  combination outperforms RRF (Zhuang et al. TOIS 2023), FeatureField 8-bit
  mantissa limitation, SPLADE wacky weights risk
- Source: `HybridFusionUtils.java` — RRF with K=60, vectorWeight=0.75,
  bm25ScoreBoostWeight=0.002, per-leg debug scores
- Source: `HybridSearchOps.java` — parallel BM25+KNN via CompletableFuture,
  3x/2x over-retrieval, low-signal gating (vector top score < 0.40)
- Source: `FieldMapper.java` — SPLADE stored as `FeatureField(fieldName, token, weight)`
- Source: `LuceneIndexRuntime.java` — SPLADE query via `FeatureField.newLinearQuery`
- Source: `SearchOrchestrator.java` — mode routing, SPLADE replaces BM25 leg,
  chunk merge via second RRF pass
- Source: `LambdaMartReranker.java` — 2-feature min-max normalized, LightGBM
- Source: `CrossEncoderReranker.java` — ONNX cross-encoder, TEXT mode only
- Source: `KnowledgeHttpApiAdapter.java` — pipeline assembly: LambdaMART first,
  cross-encoder fallback

---

## Findings

### 1. Hybrid query execution — parallel disjunction vs JustSearch's parallel futures

- **JustSearch baseline** — BM25 and KNN run as separate Lucene queries in
  parallel via `CompletableFuture` on virtual threads. BM25 fetches 3x candidates,
  KNN fetches 2x. Results are merged post-hoc via RRF in `fuseWithRRF()`. When
  SPLADE is available, it replaces BM25 as the sparse leg (same two-way fusion).

- **How it works** — Vespa models each retrieval signal as a distinct query
  operator within a single unified YQL query:
  - **`weakAnd()`** for BM25/lexical (IDF-weighted, linguistic processing)
  - **`wand()`** for SPLADE/learned sparse (integer-weight inner product, WAND
    dynamic pruning with exact top-k guarantee)
  - **`nearestNeighbor()`** for dense KNN (HNSW index, `targetHits` per shard)

  Three composition operators combine legs:
  - **OR** (disjunction): docs matching *any* leg are included. Two
    `targetHits:10` legs via OR produce ~87 total candidates — natural
    over-retrieval without explicit multipliers.
  - **AND** (conjunction): docs must match *all* legs. High precision, low recall.
  - **RANK** (asymmetric): only the first operand retrieves candidates; remaining
    operands contribute ranking features without fetching additional docs. Most
    resource-efficient for hybrid search.

  Three-way retrieval example:
  ```
  ({targetHits:100}nearestNeighbor(embedding,q))
    OR userQuery()
    OR ({targetHits:10}wand(sparse_rep, @spladeTerms))
  ```

  `targetHits` is per-content-node, not global. Each shard independently retrieves
  its `targetHits` neighbors before the stateless container merges. For ANN, when
  a boolean filter matches <2% of the corpus, Vespa automatically falls back from
  approximate HNSW to exact brute-force search.

- **Evidence** — Vespa hybrid tutorial, NN search guide, blog part 1. Vespa blog
  cites BM25+ColBERT hybrid achieving 0.481 nDCG@10 vs 0.453 BM25-only on 13
  BEIR datasets (12/13 wins). WAND pruning reduces full document evaluations by
  >90% vs brute-force OR on MS MARCO (7.9M→196K evaluations, identical top-k).

- **Applicability** — Needs adaptation. JustSearch uses Lucene directly (no YQL),
  so the operator model doesn't transfer. However, the RANK pattern (retrieve via
  one leg, score with features from others) is directly applicable.

- **Adoption cost** — Two adoptable techniques:
  1. **RANK-style asymmetric retrieval** (medium): retrieve via KNN, then score
     candidates using SPLADE features without a separate SPLADE retrieval pass.
     Requires changing `SearchOrchestrator` from parallel-futures to sequential
     retrieve-then-rescore. SPLADE query infra already exists.
  2. **Three-way fusion (BM25+SPLADE+dense)** (medium): keep BM25 as fallback
     when SPLADE is weak (dataset-dependent — Arguana shows SPLADE hurts). Requires
     `fuseWithRRF` to handle three legs and a third parallel future.

- **Expected impact** — RANK-style: reduced latency on SPLADE leg (no retrieval
  pass, only scoring). Three-way: improved robustness on datasets where SPLADE
  hurts (Arguana: dense-only 0.370 best, RRF with SPLADE = 0.289).

### 2. Score normalization — ATAN / min-max / RRF comparison

- **JustSearch baseline** — RRF-only fusion with K=60 and vectorWeight=0.75.
  RRF is rank-based, so raw score magnitudes don't affect rank assignment.
  However, the additive `bm25ScoreBoostWeight * rawBm25Score` term IS
  magnitude-sensitive — a workaround for BM25 being more trustworthy on some
  queries. LambdaMART applies per-result-set min-max normalization AFTER fusion
  on sparse+vector debug scores. No normalization before fusion.

- **How it works** — Vespa documents 5 hybrid scoring strategies:

  | Approach | Normalization | Phase | Quality (nDCG@10) |
  |----------|--------------|-------|-------------------|
  | Raw multiplicative | None (closeness is [0,1], gates BM25) | first | 0.3287 |
  | Raw additive | None (BM25 dominates) | first | 0.3244 |
  | **ATAN-normalized additive** | `2*atan(val/8)/pi` → ~(0,1) | first | **0.3410** |
  | **Min-max (normalize_linear)** | `(x-min)/(max-min)` → [0,1] | global | **0.3387** |
  | RRF (reciprocal_rank_fusion) | Rank-based `1/(k+rank)` | global | 0.3195 |

  Key: **RRF underperforms BM25-only** (0.3195 vs 0.3210 baseline) on this
  dataset. ATAN normalization wins (+0.020 over baseline) despite being a static
  function with no cross-document statistics.

  ATAN formula: `scale(x) = 2 * atan(x / C) / pi` where C is a bandwidth constant
  (Vespa uses C=8). At x=8, scale=0.5. Maps unbounded BM25 to ~(0,1) without
  needing min/max across the result set.

  `normalize_linear(feature)`: per-query min-max over the `rerank-count` window
  in global-phase. Requires seeing all candidates' scores (only available after
  scatter-gather merge). Formula: `(x - min) / (max - min)`.

  `reciprocal_rank_fusion(a, b, ...)`: syntactic sugar for
  `reciprocal_rank(a) + reciprocal_rank(b) + ...` with default k=60.

  For BM25+ColBERT hybrid (blog part 2): min-max normalization in a custom Java
  Searcher over top-2000 candidates, then convex combination
  `alpha * normalized_colbert + (1-alpha) * normalized_bm25`. Result: 0.481
  nDCG@10 vs 0.453 BM25-only across 13 BEIR datasets.

- **Evidence** — Vespa hybrid tutorial (5-approach comparison), blog part 2
  (BEIR 13-dataset hybrid), Zhuang et al. TOIS 2023 (convex combination >
  RRF, ~40 queries to tune alpha, both in-domain and out-of-domain).

- **Applicability** — Directly applicable. Two concrete approaches:

  1. **ATAN normalization** (lowest friction): add `scale(bm25) = 2*atan(bm25/C)/pi`
     before the additive BM25 term in `fuseWithRRF`. Replaces the
     `bm25ScoreBoostWeight * rawBm25Score` workaround with a principled
     normalization. No cross-document statistics needed — runs anywhere.

  2. **Min-max + convex combination** (strongest research backing): after
     retrieving candidates, compute min/max of both BM25 and vector scores,
     normalize to [0,1], combine as `alpha * normalized_vector + (1-alpha) *
     normalized_bm25`. JustSearch's `vectorWeight=0.75` becomes the initial alpha.

- **Adoption cost** —
  1. ATAN: single function addition in `HybridFusionUtils.java`. No schema
     changes. ~20 lines of code. Low risk.
  2. Min-max convex: post-retrieval normalization step in `SearchOrchestrator`
     or `HybridFusionUtils`. Similar to existing LambdaMART normalization but
     applied BEFORE combination instead of after. Medium effort (~100 lines).

- **Expected impact** — ATAN: +0.020 nDCG@10 on Vespa's benchmark. Min-max
  convex: Zhuang 2023 shows it outperforms RRF on BEIR. JustSearch's existing
  BEIR eval (SciFact, Arguana, NFCorpus) provides the ~40 queries needed to
  tune alpha. The `bm25ScoreBoostWeight=0.002` workaround would become
  unnecessary.

### 3. SPLADE storage — tensor + WAND vs FeatureField

- **JustSearch baseline** — SPLADE weights stored as Lucene
  `FeatureField(fieldName, token, weight)` — one FeatureField per BERT-vocab
  token with non-zero weight. FeatureField has 8-bit mantissa precision and
  a (0, 64] weight range. Query builds `BooleanQuery` with SHOULD clauses,
  each `FeatureField.newLinearQuery("splade", token, weight)`. Lucene uses
  WAND/MAXSCORE dynamic pruning internally on BooleanQuery SHOULD clauses.
  Single representation for both retrieval and scoring.

- **How it works** — Vespa uses a dual representation:

  **Ranking path**: `tensor<bfloat16>(token{})` — mapped tensor keyed by token
  strings with bfloat16 (8-bit mantissa, 8-bit exponent) float weights. Stored
  as a paged attribute (`attribute: paged`) for memory-efficient access. Used
  for precise dot-product scoring in second-phase ranking:
  `sum(query(q) * attribute(splade_embedding))`.

  **Retrieval path** (optional): `weightedset<int>` with `attribute: fast-search`
  — integer weights (float scaled and rounded). The `wand()` operator performs
  WAND dynamic pruning with exact top-k by inner product. Required only when
  SPLADE is used for first-stage candidate retrieval (not just re-ranking).

  The official Vespa SPLADE sample app does NOT use the `weightedset` retrieval
  path. It retrieves with BM25+weakAnd on text fields, then re-ranks using the
  tensor dot-product in second-phase. This is the practical production
  recommendation.

  | Path | Field type | Precision |
  |------|-----------|-----------|
  | Retrieval (WAND) | `weightedset<int>` | int32 (~3-4 decimal digits after scaling) |
  | Ranking (default) | `tensor<bfloat16>(token{})` | 8-bit mantissa (same as FeatureField) |
  | Ranking (max precision) | `tensor<float>(token{})` | float32 (23-bit mantissa) |

  WAND efficiency: 7-term query on 8.8M passages reduces full evaluations from
  7.9M to 196K (98% reduction) with identical top-k results.

- **Evidence** — Vespa embedding docs, WAND docs, SPLADE sample app. No
  head-to-head nDCG comparison of float32 vs bfloat16 SPLADE precision published
  by Vespa (they default to bfloat16, implying the quality difference is
  acceptable). SPLADE `log1p(ReLU(x))` outputs are in [0, ~5] — well within
  FeatureField's (0, 64] range.

- **Applicability** — Needs adaptation. Key finding: **Vespa's default bfloat16
  ranking path has the SAME 8-bit mantissa precision as Lucene's FeatureField.**
  The precision "limitation" is not unique to JustSearch — Vespa considers it
  acceptable for SPLADE ranking.

  The float32 ranking path (full precision) could be replicated in JustSearch
  by storing a second copy of SPLADE weights in a `BinaryDocValues` field. This
  would enable a two-phase approach: FeatureField for retrieval (current
  behavior), float32 DocValues for re-ranking top-k candidates.

  The WAND retrieval path is already implemented: Lucene's BooleanQuery with
  SHOULD clauses over FeatureField uses WAND/MAXSCORE internally, which is
  functionally equivalent to Vespa's `wand()` operator.

- **Adoption cost** —
  1. **No action needed for 8-bit precision concern** — Vespa validates this
     precision level as production-acceptable for SPLADE.
  2. **Dual-field pattern** (optional, medium cost): add a `BinaryDocValues`
     field storing float32 SPLADE weights for re-ranking. Requires changes to
     `FieldMapper.java` (index-time), a new re-ranking step in
     `SearchOrchestrator` (query-time), and schema changes in `fields-catalog`.
     Only worthwhile if future precision analysis shows measurable nDCG loss.

- **Expected impact** — The 8-bit precision concern is resolved: it's an
  industry-accepted trade-off, not a JustSearch limitation. The dual-field
  pattern is a potential optimization but without published quality data showing
  bfloat16→float32 improves SPLADE nDCG, the ROI is uncertain.

### 4. Multi-phase ranking — declarative rank-profiles vs layered pipeline

- **JustSearch baseline** — Three-layer pipeline hardcoded in Java across 3
  modules: (1) `adapters-lucene`: parallel BM25+KNN, RRF fusion, low-signal
  gating; (2) `indexer-worker/SearchOrchestrator`: mode routing, SPLADE
  selection, chunk merge; (3) `app-services/KnowledgeHttpApiAdapter`: LambdaMART
  first, cross-encoder fallback. No declarative phase composition. Debug scores
  are post-fusion only (chunk-merge RRF overwrites per-leg scores). No per-phase
  timing. HYBRID blocks cross-encoder and expansion. No configurable rerank-count.

- **How it works** — Vespa declares ranking phases inside `rank-profile` blocks
  in the schema file:

  ```
  rank-profile hybrid inherits default {
    first-phase {
      expression: bm25(body) + closeness(field, embedding)
    }
    second-phase {
      rerank-count: 200
      expression: xgboost("model.xgboost")
    }
    global-phase {
      rerank-count: 50
      expression: reciprocal_rank_fusion(bm25_score, vector_score)
    }
    match-features: bm25(body) closeness(field, embedding)
    summary-features: bm25(body) closeness(field, embedding)
  }
  ```

  **Three phases with independent candidate windows:**
  - **First-phase** (per content node, all matched docs): cheap expression
    (BM25, closeness, arithmetic). `rank-score-drop-limit` prunes docs below
    an absolute score threshold.
  - **Second-phase** (per content node, top `rerank-count`): expensive scoring
    (XGBoost, ONNX, tensor expressions). Default rerank-count=100.
  - **Global-phase** (stateless container, globally merged top-M): cross-document
    normalization (`normalize_linear`, `reciprocal_rank_fusion`). Default
    rerank-count=100, overridable per-query.

  **Observability:**
  - `match-features`: per-hit intermediate signals shipped from content nodes
    to container and exposed in the API response. Enables both global-phase
    expressions and offline analysis.
  - `summary-features`: per-hit feature breakdown in query response for
    debugging.
  - `ranking.listFeatures` query parameter: dumps ALL computed features.
  - **Mutable attributes**: persistent per-document counters incremented at
    `on-match`, `on-first-phase`, `on-second-phase`, `on-summary`. Tracks
    how often each document is retrieved and ranked at each phase.

  Rank-profiles support `inherits` for DRY composition and are hot-reloadable
  without Java recompilation.

  **Searcher-based reranking** (programmatic alternative): a Java `Searcher`
  class intercepts query results, reads `match-features` via `FeatureData`
  objects, computes new scores, and re-sorts. Used when cross-document
  normalization (min-max) or external model scoring is needed.

- **Evidence** — Vespa phased ranking docs, ranking expressions reference,
  searcher reranking docs. The Vespa blog's BM25+ColBERT hybrid uses exactly
  this pattern: BM25 first-phase → ColBERT second-phase → Searcher-based
  min-max normalization → linear combination = 0.481 nDCG@10.

- **Applicability** — Partially applicable. JustSearch is single-node (no
  distributed scatter-gather), so the first/global phase distinction is less
  meaningful. But several patterns transfer directly:

  1. **Configurable rerank-count** — the most glaring gap.
  2. **Pre-fusion match-features** — fixes the debug score overwrite bug.
  3. **Per-phase timing** — prerequisite for informed tuning.
  4. **rank-score-drop-limit analog** — graduated score thresholds.

- **Adoption cost** —
  1. **Configurable rerank-count** (low): add `crossEncoderRerankCount` config
     in `KnowledgeHttpApiAdapter`, cap candidates before cross-encoder. Single
     parameter, no architectural change.
  2. **Pre-fusion match-features** (medium): capture per-leg raw scores into
     an immutable debug field BEFORE `fuseWithRRF` chunk-merge overwrites them.
     Fixes `denseVectorEvidenceAvailableRate: 0`. Changes in
     `HybridFusionUtils.java` and `SearchOrchestrator.java`.
  3. **Per-phase timing** (low): add `StopWatch` or Micrometer spans around
     Lucene retrieval, RRF fusion, cross-encoder reranking. ~50 lines.
  4. **Declarative rank-profile DSL** (high, long-term): full schema-defined
     phases would require a ranking DSL, profile loader, and rewiring
     `SearchOrchestrator`. Multi-sprint effort. Incremental path: externalize
     rerank-count and score thresholds to YAML config first.

- **Expected impact** — Rerank-count: bounded, predictable cross-encoder
  latency. Pre-fusion features: fixes a known metrics bug and enables per-leg
  quality analysis. Phase timing: identifies latency bottlenecks per query.
  These are infrastructure improvements that compound across all future quality
  work.

### 5. ColBERT MaxSim — late-interaction reranking viability

- **JustSearch baseline** — No ColBERT. Cross-encoder reranking via MiniLM-L6-v2
  (ONNX, CPU/GPU). Cross-encoder adds +1.3 nDCG@10 on BM25 (SciFact: 0.660 →
  0.673). SPLADE hybrid (0.704) outperforms cross-encoder without inference cost.
  Prior research (§5.4): cross-encoders beat ColBERT by ~5-10 nDCG; ColBERT
  storage is 10-50x larger than single-vector.

- **How it works** — Vespa's ColBERT embedder produces one contextualized vector
  per input token. MaxSim scoring: for each query token Q_i, compute dot product
  against every document token D_j, keep the maximum, then sum across query
  tokens: `score = sum_i(max_j(dot(Q_i, D_j)))`.

  **Storage**: mixed tensor `tensor<int8>(dt{}, x[128])` where `dt{}` is a
  mapped dimension handling variable-length documents. int8 quantization (1-bit
  binarization to 16 bytes per 128-dim vector) gives 32x compression vs float32.

  **Storage overhead per document:**

  | Doc length | ColBERT int8 (16 B/tok) | JustSearch single-vector (3,072 B) |
  |------------|------------------------|-----------------------------------|
  | 100 tokens | 1,600 B | 3,072 B (ColBERT cheaper) |
  | 192 tokens | 3,072 B | 3,072 B (breakeven) |
  | 300 tokens | 4,800 B | 3,072 B (ColBERT 1.6x more) |
  | 2,950 tokens (MLDR avg) | ~47 KB | 3,072 B (ColBERT 15x more) |

  ColBERT runs in **second-phase** ranking on top ~50 candidates. The `paged`
  attribute offloads cold token vectors to disk (Linux mmap-style).

  **Quality** (Vespa benchmarks, ColBERT vs bi-encoder baseline):

  | Dataset | E5 bi-encoder | + ColBERT rerank | + ColBERT compressed |
  |---------|--------------|-----------------|---------------------|
  | trec-covid | 0.7449 | 0.7939 | 0.8003 |
  | nfcorpus | 0.3246 | 0.3434 | 0.3323 |
  | fiqa | 0.3747 | 0.3919 | 0.3885 |

  Compressed int8 ColBERT is at parity or better than full-precision on
  trec-covid. On long-context (MLDR): ColBERT variants outperform all
  single-vector models and BM25.

  Vespa's blog does NOT compare ColBERT to cross-encoders directly. The
  positioning is ColBERT as a cheaper alternative to cross-encoder reranking.

- **Evidence** — Vespa ColBERT blog, long-context blog. ColBERT adds ~4-5
  nDCG@10 over bi-encoder. Prior research (§5.4) shows cross-encoders beat
  ColBERT by ~5-10 nDCG. ColBERT's advantage is lower inference cost (no
  full attention over query+doc concatenation).

- **Applicability** — Not applicable for JustSearch at current scale. Reasons:
  1. Storage cost is only favorable for docs <192 tokens. JustSearch's
     `CHUNK_THRESHOLD_CHARS` means many stored units exceed this.
  2. Cross-encoder quality exceeds ColBERT by 5-10 nDCG (established in §5.4).
  3. SPLADE hybrid (0.704) already outperforms cross-encoder (0.673) — adding
     ColBERT on top of SPLADE+Dense RRF has diminishing returns.
  4. Lucene has no native mixed-tensor type — requires custom binary storage.
  5. The `paged` disk-offload benefit is Vespa-specific (mmap attribute).

- **Adoption cost** — High: custom binary storage in Lucene, ColBERT model
  inference infrastructure, MaxSim scoring in Java. Estimated multi-sprint
  effort for uncertain quality gain over existing cross-encoder.

- **Expected impact** — Marginal at best. ColBERT's value proposition is
  bridging the gap between bi-encoder and cross-encoder quality at lower
  inference cost. JustSearch already has both a cross-encoder AND SPLADE
  hybrid that exceeds cross-encoder quality. ColBERT would add complexity
  without clear quality improvement at desktop scale.

---

## Additional Findings

### RANK operator as a general optimization pattern

Vespa's RANK operator (retrieve via one leg, score with features from all legs)
is a broadly applicable optimization beyond just hybrid search. In JustSearch
terms: any time a new scoring signal is added (SPLADE, cross-encoder, new
embedding model), the default approach would be to run it as a parallel retrieval
leg. The RANK pattern says: don't — retrieve via the strongest leg, then rescore
candidates using the new signal. This reduces the cost of adding new signals from
O(corpus) retrieval to O(candidates) rescoring.

### ATAN normalization as a universal score compressor

The `2*atan(x/C)/pi` function is more generally useful than just for BM25. Any
unbounded score (BM25, TF-IDF, click counts, recency boosts) can be mapped to
~(0,1) with a single tunable bandwidth parameter C. This is cheaper than min-max
(no cross-document scan) and more stable than z-score (no mean/stddev estimation).
JustSearch could adopt this as a standard normalization primitive across all
scoring components.

### Mutable match counters for quality feedback

Vespa's mutable attributes (`on-match`, `on-first-phase`, etc.) that persist
per-document counts of how often a document was retrieved, ranked, and returned
are a novel observability pattern. In JustSearch terms: a document that is
frequently retrieved (high `on-match` count) but rarely returned to users (low
`on-summary` count) indicates a precision problem. This could be implemented
as SQLite counters updated after each search without Lucene schema changes.

### Vespa confirms JustSearch's SPLADE precision is industry-standard

The single most important finding for §7.1: Vespa's default SPLADE storage
(`tensor<bfloat16>`) uses the same 8-bit mantissa precision as Lucene's
FeatureField. Vespa uses this in production for major deployments. The
FeatureField precision concern raised in §7.1 is resolved — it is an
industry-accepted trade-off, not a JustSearch-specific limitation.

### ONNX cross-encoder pre-tokenization pattern

Vespa pre-tokenizes documents at **index time** using `hugging-face-tokenizer`
and stores token IDs as attribute tensors (`text_token_ids`). At ranking time,
built-in functions (`tokenInputIds()`, `tokenTypeIds()`, `tokenAttentionMask()`)
assemble the `[CLS] query [SEP] doc [SEP]` sequence from cached tokens — no
re-tokenization. One ONNX inference call per candidate, sequential (no batching).

Performance: rerank-count=24 with MiniLM (22.7M params, int8 quantized) drops
throughput from ~1895 QPS to <100 QPS on 9M passages (18x reduction).

JustSearch's `CrossEncoderReranker` re-tokenizes query+document pairs at
inference time via `RerankerTokenizer`. Pre-tokenizing document tokens at index
time and storing them as a Lucene stored field would eliminate repeated
tokenization overhead. Adoption cost: medium — requires changes to index schema
(`FieldMapper.java`), indexing pipeline (`IndexingDocumentOps.java`), and
`CrossEncoderReranker.java` to accept pre-tokenized input.

### ATAN bandwidth parameter C requires corpus calibration

Vespa's ATAN normalization uses C=8 without any documented justification,
sensitivity analysis, or guidance for choosing C. No ablation of C=4 vs C=8
vs C=16 exists in Vespa's docs.

C is the **half-saturation point**: at BM25=C, the normalized score = 0.5.
The choice depends on the BM25 score distribution of the target corpus:
- C=4: aggressive saturation, good for low-valued BM25 distributions
- C=8: Vespa default, assumes typical BM25 scores span ~0-20
- C=16: wider dynamic range, good for BM25 scores routinely reaching 20-40

**Before adopting ATAN, JustSearch must profile its BM25 score distribution**
across SciFact/Arguana/NFCorpus to pick an appropriate C. The existing eval
infrastructure can measure ATAN sensitivity by running the same queries with
C=4, 8, 16 and comparing nDCG@10.

### Vespa's 5-approach benchmark is on NFCorpus (same as JustSearch eval)

The 5-approach normalization comparison uses **NFCorpus** (3,633 docs, 323
queries). JustSearch's existing NFCorpus results: BM25=0.308, Hybrid RRF=0.333.
Vespa's ATAN on NFCorpus: 0.341. The +0.008 gap (0.333 → 0.341) suggests ATAN
normalization could yield modest but measurable improvement on this dataset.
Note: Vespa's BM25 baseline is 0.321 vs JustSearch's 0.308 — the gap may
partly reflect different BM25 tuning (k1, b parameters) rather than
normalization alone.

---

## Unresolved Items

None. All 5 checklist items were fully answered from Vespa's documentation.

The following items could NOT be answered because Vespa's docs don't publish
the data, but they are outside the original checklist scope:
- **float32 vs bfloat16 SPLADE nDCG comparison** — Vespa doesn't publish this.
  Would require our own A/B test if the precision concern resurfaces.
- **ColBERT vs cross-encoder direct quality comparison** — Vespa positions
  ColBERT as an alternative, not a replacement. No head-to-head nDCG data.
- **Per-phase latency breakdown** — Vespa documents bounded cost characteristics
  but doesn't expose per-phase timing as a structured metric in query responses.
- **ATAN C parameter sensitivity** — Vespa provides no sensitivity analysis.
  JustSearch must profile its own BM25 distribution and test C=4/8/16.

---

## Cross-Cutting Observations

### §7.1 — Is Lucene's FeatureField 8-bit mantissa a real quality bottleneck?

**Vespa evidence: No.** Vespa's default SPLADE ranking path uses `tensor<bfloat16>`
with the same 8-bit mantissa. This is production-accepted for major search
deployments. SPLADE `log1p(ReLU(x))` outputs are in [0, ~5], well within
FeatureField's (0, 64] range. The 8-bit mantissa is not a binding constraint for
SPLADE-v3.

### §7.2 — What's the best score normalization for hybrid fusion?

**Vespa evidence: ATAN normalization or min-max convex combination, not RRF.**
On Vespa's benchmark, ATAN wins (0.3410 nDCG@10) over RRF (0.3195) and min-max
(0.3387). RRF underperformed BM25-only on this dataset. This aligns with Zhuang
et al. TOIS 2023 finding that convex combination outperforms RRF. JustSearch
should test ATAN normalization and/or min-max convex combination against current
RRF on SciFact/Arguana/NFCorpus.

### §7.4 — Does ColBERT add value over cross-encoder?

**Vespa evidence: Not for desktop.** ColBERT adds ~4-5 nDCG@10 over bi-encoder
retrieval, but Vespa's own docs don't claim it matches cross-encoder quality.
Prior research (§5.4) shows cross-encoder wins by 5-10 nDCG. ColBERT's advantage
is lower inference cost at scale — irrelevant for single-user desktop search.
JustSearch's BM25+SPLADE+Dense→cross-encoder architecture remains the right
approach.

### §7.6 — What filtered ANN strategy works best?

**Vespa evidence (partial):** When a boolean filter matches <2% of the corpus,
Vespa automatically falls back from approximate HNSW to exact brute-force ANN.
This is a simple heuristic that JustSearch could replicate in
`ReadPathOps.java`. Needs Qdrant/Milvus investigation for more strategies.

---

## Sources

- [Hybrid Text Search Tutorial](https://docs.vespa.ai/en/learn/tutorials/hybrid-search.html)
- [Nearest Neighbor Search Guide](https://docs.vespa.ai/en/querying/nearest-neighbor-search-guide.html)
- [Redefining Hybrid Search Possibilities (blog part 1)](https://blog.vespa.ai/redefining-hybrid-search-possibilities-with-vespa/)
- [Improving Zero-Shot Ranking (blog part 2)](https://blog.vespa.ai/improving-zero-shot-ranking-with-vespa-part-two/)
- [Phased Ranking](https://docs.vespa.ai/en/ranking/phased-ranking.html)
- [Ranking Expressions and Features](https://docs.vespa.ai/en/ranking/ranking-expressions-features.html)
- [Reranking in Searcher](https://docs.vespa.ai/en/ranking/reranking-in-searcher.html)
- [Embedding (SPLADE embedder)](https://docs.vespa.ai/en/rag/embedding.html)
- [WAND: Accelerated OR Search](https://docs.vespa.ai/en/using-wand-with-vespa.html)
- [Announcing ColBERT Embedder](https://blog.vespa.ai/announcing-colbert-embedder-in-vespa/)
- [Long-Context ColBERT](https://blog.vespa.ai/announcing-long-context-colbert-in-vespa/)
- [Ranking With ONNX Models](https://docs.vespa.ai/en/ranking/onnx.html)
- [Cross-Encoder Ranking](https://docs.vespa.ai/en/ranking/cross-encoders.html)
- [Pretrained Transformer Models for Search (part 4)](https://blog.vespa.ai/pretrained-transformer-language-models-for-search-part-4/)
- Zhuang et al., "Analysis of Fusion Functions for Hybrid Retrieval" (TOIS 2023)

---

## Staleness review (2026-05-18)

See the [shared staleness review](#staleness-review-2026-05-18), which applies to all eleven constituent documents.
