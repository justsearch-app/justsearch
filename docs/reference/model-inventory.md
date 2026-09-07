---
title: Model Inventory
type: reference
status: stable
description: "Canonical reference for all models used in JustSearch."
---

# Model Inventory

Canonical reference for all models used in JustSearch, covering production (packaged app),
search runtime (dev/eval), and agent-quality stacks. This document is the single source of
truth for model identity decisions. Tempdocs may propose changes; this document records what
was adopted.

## Active Models

### Search Runtime (ONNX)

These are the models used by the Worker process for retrieval. Packaged identity, hashes, sizes,
and download URLs come from `model-registry.v2.json`. The public repository tracks supporting
metadata but not model weights; the install flow downloads weights, while private development
checkouts may provide the same layout under `models/` for discovery at startup.

#### ONNX Embedding: `EmbeddingGemma-300M` (Q4 GPU / INT8 CPU) — **legacy backup**

| Property | Value |
|----------|-------|
| Identity | `google/embeddinggemma-300m` |
| Variant | Q4 GPU default (188 MB), INT8 CPU fallback (298 MB) |
| Size | 188 MB (Q4 GPU) / 298 MB (INT8 CPU) |
| Source | [onnx-community/embeddinggemma-300m-ONNX](https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX) (FP32), then local quantization |
| Precision | Q4 (GPU), INT8 (CPU fallback) |
| Params | 308M (Gemma 3, T5Gemma init) |
| Inputs | `input_ids`, `attention_mask` (no `token_type_ids`) |
| Outputs | `last_hidden_state`, `sentence_embedding` |
| Max seq len | 2048 tokens |
| Dims | 768 (Matryoshka: 128/256/512/768) |
| Pooling | MEAN_POOL (configured via `pooling_config.json`) |
| Prefixes | Task-specific (configured via `prefix_config.json`) |
| Repo path | `models/onnx/embeddinggemma-300m/model.onnx` (Q4, 188 MB), `model_int8.onnx` (INT8, 298 MB) |
| Manifest | `model_manifest.json`: cpu=`model_int8.onnx`, gpu=`model.onnx` |
| Provenance | No `build.json` is tracked for this legacy package in the public repository |
| Discovery | Historical package only; the current `EmbeddingOnnxModelDiscovery` does not automatically fall back to this directory |
| Adopted | Tempdoc 312 (INT8 default since Phase 5); tempdoc 334 (Q4 GPU default) |

Why EmbeddingGemma: #1 open model under 500M on MTEB English/multilingual/code.
Higher quality than nomic (nDCG@10 0.71 vs 0.56 on SciFact). Does NOT support
FP16 — model card states "activations do not support fp16 or its derivatives."

Q4 GPU is 1.9x faster than INT8 on GPU (390ms per 100-doc batch vs INT8).
INT8 (298 MB) is used as CPU fallback when GPU is unavailable.

#### ONNX Embedding: `nomic-embed-text-v1.5` (INT8) — **fallback**

| Property | Value |
|----------|-------|
| Identity | `nomic-ai/nomic-embed-text-v1.5` |
| Variant | INT8 quantized (`model_quantized.onnx` renamed to `model.onnx`) |
| Size | 131 MB |
| Source | [nomic-ai/nomic-embed-text-v1.5/onnx/model_quantized.onnx](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/tree/main/onnx) |
| Precision | Mixed INT8/FP32 (120 INT8 + 119 FP32 tensors) |
| Producer | `onnx.quantize 0.1.0` |
| Inputs | `input_ids`, `token_type_ids`, `attention_mask` |
| Outputs | `last_hidden_state` |
| Repo path | `models/onnx/embedding/model.onnx` |
| Discovery | Historical local fallback only; the current ONNX embedding discoverer does not select this directory automatically |
| Adopted | Tempdoc 268 (ONNX-first for search runtime); superseded as default by tempdoc 312 |

Why INT8: tempdoc 268 established quality parity with GGUF Q8 after overlap fixes. INT8
is 4x smaller than FP32 (131 MB vs 549 MB) with negligible retrieval quality difference.

#### ONNX Embedding: `gte-multilingual-base` (FP16 GPU / FP32 CPU) — **current default**

| Property | Value |
|----------|-------|
| Identity | `Alibaba-NLP/gte-multilingual-base` |
| Variant | FP16 GPU default (627,988,827 bytes), FP32 CPU (1,255,502,649 bytes); both are shipped in the registry |
| Source | [Alibaba-NLP/gte-multilingual-base](https://huggingface.co/Alibaba-NLP/gte-multilingual-base) |
| Repo path | `models/onnx/gte-multilingual-base/` (`model_fp16.onnx` FP16, `model.onnx` FP32) |
| Manifest | `model_manifest.json`: cpu=`model.onnx`, gpu=`model_fp16.onnx` |
| Discovery | Primary: `EmbeddingOnnxModelDiscovery` tries `gte-multilingual-base/` first (hardcoded `MODEL_NAME`). Quality: nDCG@10 0.7132 on SciFact (matches EmbeddingGemma baseline 0.7128). 39s faster pipeline. 70+ languages. |
| Adopted | Tempdoc 358 (selected as production default over EmbeddingGemma-300M); tempdoc 374 (packaged in model registry for Install AI) |

The manifest and registry agree on the CPU/GPU split. FP16 on the CPU remains unsupported and
unusably slow because the ORT CPU execution provider inserts casts around FP16 operations.

#### SPLADE: `opensearch-neural-sparse-encoding-multilingual-v1` (baked-PRESPARSE, FP32 CPU / FP16 GPU) — **current default**

| Property | Value |
|----------|-------|
| Identity | `opensearch-project/opensearch-neural-sparse-encoding-multilingual-v1` |
| Architecture | BERT-multilingual MLM (12 layers, 768 dim, 105K vocab, log1p activation) |
| Variant | FP32 baked-PRESPARSE CPU (345 MB), FP16 baked-PRESPARSE GPU (173 MB) |
| Inputs | `input_ids`, `attention_mask` (no `token_type_ids`) |
| Outputs | `output_idx` (INT64, [B,256]), `output_weights` (FLOAT32, [B,256]) |
| Output format | `PRESPARSE` — ReLU + log1p + ReduceMax + TopK baked into ONNX graph |
| Repo path | `models/splade/naver-splade-v3/model.onnx` (FP32), `model_fp16.onnx` (FP16) |
| Manifest | `model_manifest.json`: cpu=`model.onnx`, gpu=`model_fp16.onnx` |
| Discovery | `SpladeModelDiscovery` looks for `model.onnx` + `tokenizer.json` + `vocab.txt` under `<modelRoot>/splade/naver-splade-v3/` |
| Provenance | `build.json` in model directory (tempdoc 348) |
| Adopted | Tempdoc 343 Phase C (replaced distill variant with multilingual-v1, PRESPARSE conversion required to avoid ORT closeSession crash with 105K vocab MLM_LOGITS format) |

**Note:** the directory is named `naver-splade-v3` for historical reasons (it previously
held `naver/splade-v3`). The current model is the OpenSearch multilingual-v1 variant.

**Legacy backup:** The previous model (`opensearch-neural-sparse-encoding-doc-v3-distill`, 6L DistilBERT, 30K vocab, double-log1p activation) is backed up to `models/splade/naver-splade-v3-backup/`.

Build command (reproducible):

```bash
pip install -r scripts/models/requirements.txt
python scripts/models/build-splade.py \
    --hf-model opensearch-project/opensearch-neural-sparse-encoding-multilingual-v1 \
    --output-dir models/splade/naver-splade-v3
```

The FP16 pipeline is order-dependent (tempdoc 334 Phase 7, 4 attempts to discover):
1. ORT transformer optimize the base FP32 MLM model
2. Convert entire graph to FP16 (`keep_io_types=False`)
3. Append PRESPARSE ops in FP16 (matching internal tensor types)
4. Cast `output_weights` FP16→FP32 for Java ORT `getFloatBuffer()` compatibility

See `scripts/models/bake_presparse_fp16.py` for the low-level bake script and
`scripts/models/build-splade.py` for the end-to-end build.

### Rerankers (ONNX)

#### Cross-Encoder Reranker: `Alibaba-NLP/gte-multilingual-reranker-base` — **current default**

| Property | Value |
|----------|-------|
| Identity | `Alibaba-NLP/gte-multilingual-reranker-base` |
| Variant | FP16 GPU (default), FP32 CPU fallback |
| Size | 340,858,200 bytes (FP32 CPU), 629,118,152 bytes (FP16 GPU) |
| Params | 306M (GTE encoder, 12L/768d, head_dim=64, 250K vocab, 70+ langs) |
| Repo path | `models/onnx/reranker/model.onnx` (FP32), `model_fp16.onnx` (FP16) |
| Provenance | No `build.json` is tracked for this package in the public repository |
| Process | **Worker** (migrated from Head in tempdoc 360) |
| GPU default | `true` (mem=2048MB, seq=512) |
| Latency | ~175ms for 20 docs on GPU (12x faster than previous INT8 CPU model at 2400ms) |
| Status | **Active** — index-half side via the `rerank` port call (tempdocs 205, 248, 317, 360, 343) |

The checked-in `build-crossencoder.py` command is not a builder for this current package: it emits
one pre-built INT8 CPU variant, while the registry requires FP32 CPU and FP16 CUDA variants. A new
candidate therefore needs independently reviewed build provenance until a compatible role builder
is added.

Historical builder shape:

```bash
python scripts/models/build-crossencoder.py \
    --hf-model Alibaba-NLP/gte-multilingual-reranker-base \
    --output-dir models/onnx/reranker
```

**Legacy backup:** The previous model (`gte-reranker-modernbert-base`, INT8, 144 MB, 149M params, EN-focused) is backed up to `models/onnx/reranker-modernbert-backup/`.

#### Cross-Encoder Reranker: `Xenova/ms-marco-MiniLM-L-6-v2` (legacy backup)

| Property | Value |
|----------|-------|
| Identity | `Xenova/ms-marco-MiniLM-L-6-v2` |
| Size | 23 MB |
| Repo path | `models/onnx/reranker-minilm-backup/model.onnx` |
| Status | **Legacy backup** — retained for rollback, no `build.json` |

#### Citation Scorer: `ms-marco-MiniLM-L-6-v2`

| Property | Value |
|----------|-------|
| Identity | `cross-encoder/ms-marco-MiniLM-L-6-v2` |
| Size | 22 MB |
| Repo path | `models/onnx/citation-scorer/model.onnx` |
| Provenance | No `build.json` is tracked for this package in the public repository |
| Status | **Active** — upgraded from L-2 variant (tempdoc 343 Phase A.3). Score separation +27% over L-2; +4.16 MRR@10 on MS MARCO dev. |

**Legacy backup:** The previous model (`Xenova/ms-marco-MiniLM-L-2-v2`, 16 MB) is backed up to `models/onnx/citation-scorer-l2-backup/`.

### Packaged / Registry Defaults (GGUF)

These are the models defined in `modules/configuration/src/main/resources/ai/model-registry.v2.json`.
They are downloaded by the desktop app's AI install flow and used by the Brain (inference)
process.

#### Chat: `Qwen_Qwen3.5-9B-Q4_K_M.gguf`

| Property | Value |
|----------|-------|
| Identity | `Qwen/Qwen3.5-9B` |
| Size | 5.5 GB (5,889,811,552 bytes) |
| Status | **Current packaged default** — registry `id: chat` (`model-registry.v2.json`) |

The chat model (served by llama-server / Brain process) now serves three roles beyond RAG chat:

1. **RAG chat / Q&A / Summarization / VDU** — the original use case.
2. **Query Understanding preprocessing** (~1s async, tempdoc 363) — translates natural language queries into structured search parameters (filters, sort, reformulated query) before search execution. Bypassed when llama-server is offline.
3. **Context Sufficiency classification** (~1.3s, retrieve-context only, tempdoc 363) — post-search LLM call that assesses whether retrieved context is sufficient to answer the query. Surfaces a confidence signal to downstream consumers (e.g., MCP agent layer).

#### Embedding (legacy local GGUF fallback): `nomic-embed-text-v1.5.Q4_K_M.gguf`

| Property | Value |
|----------|-------|
| Identity | `nomic-ai/nomic-embed-text-v1.5-GGUF` |
| Size | 84 MB |
| Status | **Not a registry package** — retained only as a legacy local resolver candidate |

`EmbeddingModelResolver` prefers Q8_0 over Q4_K_M when both exist (Q8_0 recovers +0.025
nDCG on dense retrieval).

#### Vision Projection: `mmproj-F16.gguf`

| Property | Value |
|----------|-------|
| Size | 918 MB (918,165,952 bytes) |
| Status | **Current packaged default** — registry `chat` supporting file, paired with the chat model |

### Agent-Quality Runtime

Model choices originally explored for agent-quality evaluation (tempdoc 227). The chat model
adopted there (`Qwen_Qwen3.5-9B-Q4_K_M.gguf`) has since **graduated to the packaged default**
(see Chat, above); the embedding row below remains an agent-quality-only choice, not in the registry.

| Layer | Model | Status |
|-------|-------|--------|
| Chat | `Qwen_Qwen3.5-9B-Q4_K_M.gguf` (5.5 GB) | Now the packaged default — see the Chat entry above |
| Embedding | `nomic-embed-text-v1.5.Q8_0.gguf` (140 MB) | Observed runtime choice (not packaged) |

### NER

| Property | Value |
|----------|-------|
| Identity | `Davlan/distilbert-base-multilingual-cased-ner-hrl` |
| Format | ONNX |
| Variant | INT8 CPU default (135,115,720 bytes), FP16 GPU (269,622,486 bytes) |
| Languages | 10 (en, fr, de, es, pt, it, nl, ar, zh, ru) |
| Repo path | `models/onnx/ner/` (`model.onnx` INT8, `model_fp16.onnx` FP16) |
| Manifest | `model_manifest.json`: cpu=`model.onnx`, gpu=`model_fp16.onnx` |
| Provenance | No `build.json` is tracked for this package in the public repository |
| Quality | F1=0.908 (CoNLL-2003 validation): PER 0.953, ORG 0.839, LOC 0.942 — see F-011 in search-quality-register.md. Multilingual variant measured: PER 0.942, ORG 0.849, LOC 0.911 on CoNLL-2003 test (tempdoc 343 Phase A.2). |
| Status | **Active** — the registry supplies the package to the default install set |

**Legacy backup:** The previous model (`dslim/distilbert-NER`, EN-only) is backed up to `models/onnx/ner-distilbert-en-backup/`.

## Model Fingerprinting

The Worker computes SHA-256 fingerprints of active model files at boot via `Sha256SidecarCache`.
`EmbeddingFingerprint.discoverModelPath()` uses `ModelManifest.loadOrDefault()` to determine
which file to fingerprint and respects the CPU/GPU selection. Sidecars cache individual hashes.
`IndexFingerprint` then incorporates the embedding, SPLADE, and NER model digests into one
`index_fingerprint` commit-metadata value; changed bytes in any of those three roles require the
blue/green migration evidence named by the promotion planner. Reranker, citation, and chat packages
do not affect index compatibility.

Sidecar format: `sha256:<hex64> mtime:<epoch-millis> size:<bytes>`

Sidecar files are gitignored (generated at runtime).

## Model Provenance (`build.json`)

Model build scripts can emit a `build.json` recording how a model was produced. In the public
repository, only `models/splade/naver-splade-v3/build.json` is currently tracked. Absence is not a
successful integrity result and is not provenance: a promotion candidate must supply equivalent
immutable provenance before it can be considered ready.

Schema:

- `source`: HuggingFace model ID + commit hash (auto-captured by build scripts)
- `variants`: keyed by filename, each with `description`, `transformations`
  (ordered list of steps), `output_sha256` (hash of the produced `.onnx` bytes)
- `build_command`: exact command to reproduce
- `tool_versions`: Python package versions used during the build

Build scripts in `scripts/models/`:

| Script | Models | What it does |
|--------|--------|--------------|
| `build-splade.py` | SPLADE | HF export + FP32/FP16 baked-PRESPARSE |
| `build-ner.py` | NER | Download pre-built INT8 + FP16 from onnx-community |
| `build-embedding.py` | EmbeddingGemma | Q4 merge + INT8 quantization |
| `build-crossencoder.py` | Citation scorer and legacy CPU-only cross-encoder shapes | Download one pre-built INT8 variant; it does not build the current reranker FP32/FP16 pair |
| `check-integrity.py` | Directories that already contain `build.json` | Verify SHA-256 hashes + schema; missing provenance directories are skipped |
| `verify-model.py` | ONNX candidates | CPU smoke test only; production CUDA verification uses the Gradle `verifyModel` path |

Integrity check: `python scripts/models/check-integrity.py`

`model_promotion_planner.py` is the write-free admission layer above these package-specific tools.
It accepts one explicit registry package and staged candidate, validates closure and immutable
provenance, and reports publication, remote-byte, CPU/GPU, live, quality, projection, and migration
evidence as blockers. Its deterministic review bundle retains the canonical provenance, complete
remote-verification facts, evidence references, and generated projection results/diffs. A candidate
cannot be ready without explicit approval evidence tied to its proposed license. The planner never
builds, uploads, edits the registry, or activates a model.

## Discovery Paths

### ONNX Embedding (`EmbeddingOnnxModelDiscovery`)

1. `JUSTSEARCH_EMBED_ONNX_MODEL_PATH` env var (explicit override)
2. `<modelRoot>/onnx/gte-multilingual-base/` for each resolved model root, requiring the conventional
   model/tokenizer pair or a complete manifest/FP16 variant layout

The current discoverer has no automatic EmbeddingGemma or nomic fallback.

### GGUF Embedding (`EmbeddingModelResolver`)

1. `JUSTSEARCH_MODEL_PATH` env var
2. Resolved model roots from `ResolvedConfig`
3. `~/.justsearch/models/`

Candidate filenames (preference order): `nomic-embed-text-v1.5.Q8_0.gguf`,
`nomic-embed-text-v1.5.Q4_K_M.gguf`, `nomic-embed-text-v1.5.f16.gguf`

### SPLADE (`SpladeModelDiscovery`)

1. `JUSTSEARCH_SPLADE_MODEL_PATH` env var
2. `<modelRoot>/onnx/splade/`
3. `<modelRoot>/splade/naver-splade-v3/` (dev layout)
4. `<repoRoot>/models/splade/naver-splade-v3/`
5. `<baseDir>/models/splade/naver-splade-v3/`

Requires: `model.onnx` + `tokenizer.json` + `vocab.txt`

## Known Divergences

### AppData models differ from repo-root models

The desktop shell installs models to `%APPDATA%\io.justsearch.shell\models\`. These may
be older or different variants than the repo-root models:

| Model | AppData | Repo-root |
|-------|---------|-----------|
| Embedding | FP32 (547 MB, pytorch 2.1.0) | Q4 GPU (188 MB) / INT8 CPU (298 MB) |
| SPLADE | Old presparse or MLM logits export | Distill baked-PRESPARSE (345 MB FP32 / 173 MB FP16) |

`SpladeEncoder` auto-detects `MLM_LOGITS` vs `PRESPARSE` format at init.
Eval results are not directly comparable across different model variants.

### Stale script references

These files reference outdated model names or paths and should be reconciled to the registry/disk
truth:

| File | Stale reference | Should be (registry + disk) |
|------|----------------|-----------|
| `scripts/verify-prerequisites.mjs` | `models/citation-scorer/ms-marco-MiniLM-L2-v2` | `models/onnx/citation-scorer/` |

(The `Qwen3VL-8B-Instruct` naming drift previously tracked here — in
`scripts/verify-prerequisites.mjs`'s chat/mmproj checks and `modules/ui/inference-model-id.txt` —
was fixed: the script now reads the expected filename live from `model-registry.v2.json`'s `chat`
package instead of hardcoding it, so it cannot drift again the same way. Verified 2026-07-01.)

## Model identity (Repo-Root layout)

**This table describes model *identity* — which model, which precision, which purpose — not
whether the file is currently present on this machine or in this git checkout.** The public repo
does not commit the model binaries themselves (they are fetched on first run via the "Install AI"
flow, per ADR-0024); a canonical reference asserting per-file presence/status as a static fact goes
stale the moment the snapshot/distribution policy changes, which is exactly what happened here
(tempdoc 656 §Investigation A.1) — this table used to claim every path below was present with a
size and status, which was true of the private development checkout this doc was originally written
against, but is not true of a fresh `justsearch-public` clone.

**For the live, authoritative answer to "is this actually present on this machine right now,"**
query `GET /api/ai/models/status` (tempdoc 656 Task 4) — it reconciles this same registry against
actual on-disk state and reports per-package completeness, rather than a table that can only ever
describe a snapshot in time.

| Path | `build.json` | Identity |
|------|-------------|--------|
| `models/onnx/embeddinggemma-300m/model.onnx` | No tracked file | Q4, historical GPU default |
| `models/onnx/embeddinggemma-300m/model_int8.onnx` | No tracked file | INT8, historical CPU fallback |
| `models/onnx/gte-multilingual-base/model_fp16.onnx` | No tracked `build.json` | FP16, packaged/registry GPU default |
| `models/onnx/gte-multilingual-base/model.onnx` | No tracked `build.json` | FP32, packaged/registry CPU default |
| `models/onnx/embedding/model.onnx` | No | Nomic INT8, legacy fallback |
| `models/splade/naver-splade-v3/model.onnx` | Yes | FP32 baked-PRESPARSE, CPU (multilingual-v1) |
| `models/splade/naver-splade-v3/model_fp16.onnx` | Yes | FP16 baked-PRESPARSE, GPU (multilingual-v1) |
| `models/onnx/reranker/model.onnx` | No tracked `build.json` | GTE-multilingual-reranker FP32 |
| `models/onnx/reranker/model_fp16.onnx` | No tracked `build.json` | GTE-multilingual-reranker FP16, GPU default |
| `models/onnx/reranker-minilm-backup/model.onnx` | No | Legacy MiniLM, backup only |
| `models/onnx/citation-scorer/model.onnx` | No tracked `build.json` | MiniLM-L6 INT8 |
| `models/onnx/ner/model.onnx` | No tracked `build.json` | Multilingual-NER-HRL INT8, CPU |
| `models/onnx/ner/model_fp16.onnx` | No tracked `build.json` | Multilingual-NER-HRL FP16, GPU |
| Registry GGUF models (chat, chat-compact, mmproj) | Per-candidate GGUF source manifest | Downloaded by the install/dev flow; weights are not tracked here |

## Open Decisions

1. ~~Should ONNX embedding + SPLADE enter `model-registry.v2.json` for packaged app distribution?~~ **Settled:** both are already registry packages (`"id": "embedding"`, `"id": "splade"`) — `modules/configuration/src/main/resources/ai/model-registry.v2.json`.
2. ~~Should the legacy MiniLM reranker be removed from the registry?~~ **Settled:** it is not a registry package; `reranker` is the GTE multilingual package.
3. ~~Should `Qwen3.5-9B` replace `Qwen3VL-8B-Thinking` as the packaged chat model?~~ **Settled:** registry package `chat` is Qwen3.5-9B.
4. ~~Should NER be included in the default `models/` set or remain dev-only?~~ **Settled:** registry package `ner` is in the default install set.
5. Should the AppData models be updated to match the repo-root canonical variants?
