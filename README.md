# JustSearch

**A private retrieval backend for your AI agents — and a neural search engine with a cited, on-device AI assistant for your own files. Hybrid search (BM25 + dense vectors + learned-sparse + reranking) plus grounded Q&A, summarization, and extraction over your documents — 100% on your machine. Windows-only for now.**


[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![CI](https://github.com/justsearch-app/justsearch/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/justsearch-app/justsearch/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/justsearch-app/justsearch?include_prereleases&label=release)](https://github.com/justsearch-app/justsearch/releases)
<!-- badges: <<nDCG benchmark badge>> — deferred: no workflow publishes a benchmark badge yet -->
<!-- mcp-name: io.github.justsearch-app/justsearch -->
<!-- ^ Official MCP Registry ownership marker (modelcontextprotocol.io/registry/package-types);
     must match "name" in packaging/mcpb/server.json. Do not remove. -->

<!-- HERO VISUAL PLACEHOLDER (m1-shippability):
     Add a hero screenshot + short demo GIF here. Exact shots to take and how:
     docs/m1-screenshot-instructions.md
     Suggested markup once assets exist in docs/assets/:
     <p align="center"><img src="docs/assets/hero-search.png" width="820" alt="JustSearch: hybrid search over a local folder, with cited AI answers"></p>
-->

JustSearch is local-first: it indexes your local documents — PDF, email, Office, and most common document
formats (text extraction is backed by [Apache Tika](https://tika.apache.org/), plus OCR for scanned PDFs and
images) — and answers questions over them with cited passages, **without anything leaving your machine.**
It combines three retrieval paradigms (keyword, dense-vector, learned-sparse) with a cross-encoder reranker,
and exposes that retrieval over the **Model Context Protocol (MCP)** so any AI agent — local or cloud — can
use it as a **private retrieval backend**: indexing and retrieval stay on your device. The built-in
assistant keeps the whole loop on-device. When you connect an external AI client, the retrieved passages
and their metadata are handed to that client, and what happens to them from there is governed by that
client and its model provider, not by JustSearch.

It is multilingual by construction: the embedding model
([gte-multilingual-base](https://huggingface.co/Alibaba-NLP/gte-multilingual-base)) supports 70+ languages;
retrieval quality is benchmarked on English, German, and French (see [Benchmarks](#benchmarks)).

## Install (Windows)

1. **Download** [`JustSearch_0.3.0_x64-setup.exe`](https://github.com/justsearch-app/justsearch/releases/download/v0.3.0/JustSearch_0.3.0_x64-setup.exe)
   (261 MB) from the [v0.3.0 release](https://github.com/justsearch-app/justsearch/releases/tag/v0.3.0).
2. **Run it.** The installer is **code-signed** — the publisher shown is **Elias Justus**. Windows
   SmartScreen may still show a reputation prompt while the signing certificate is new (reputation
   accrues with downloads); if it does, the "More info" panel names the verified publisher. The
   install is per-user; no admin rights needed.
3. **Launch JustSearch** and point it at a folder. On first launch the app asks consent to download its AI
   models (**~9 GB**, one time, from GitHub Releases + Hugging Face) — after that it runs **fully offline**.

Installer SHA-256 (also in [`SHA256SUMS`](https://github.com/justsearch-app/justsearch/releases/download/v0.3.0/SHA256SUMS)):

```text
465b9a90f763471bf5b6e33261d5eb17d3cf58981acb5d58d8d67dfd4da2d33f  JustSearch_0.3.0_x64-setup.exe
```

<!-- hash verified 2026-09-07 against the asset downloaded from the published v0.3.0 GitHub
     release (Authenticode Valid, CN=Elias Justus, SSL.com timestamped; 261,223,328 bytes measured). -->

Full walkthrough (hash and publisher verification, Windows reputation guidance,
build-from-source alternative): [`docs/how-to/verify-your-download.md`](docs/how-to/verify-your-download.md).

## System requirements

| | |
|---|---|
| **OS** | **Windows 10 or 11, 64-bit (x64) only** — macOS/Linux are not in the current scope ([NON-GOALS](NON-GOALS.md)). Verified on Windows 11; Windows 10 x64 is the expected WebView2 baseline but is not yet explicitly tested. WebView2, the VC++ runtime, and a Java runtime are bundled by the installer; there is nothing to install first. |
| **Disk** | 261 MB installer + ~0.7 GB installed + **~9 GB one-time model download** + the search index (grows with your corpus). Plan for **≥ 15 GB free**. |
| **RAM** | **16 GB recommended.** 8 GB is a conservative floor for keyword/semantic search only (not a benchmarked minimum; chat needs the GPU below regardless of RAM). The on-device chat model (~5.9 GB file) is loaded into memory when answering. |
| **GPU** | **Required for chat.** Search and semantic ranking run on CPU — no GPU needed for those. Chat requires an NVIDIA GPU with **≥ 8 GB VRAM** (the app needs ~7.5 GB free VRAM to run the chat model). Without a supported NVIDIA GPU, or below that VRAM floor, no install path offers the chat model — chat does **not** fall back to CPU, so the app is search-only. |
| **Network** | Only for the one-time model download. Nothing else, ever — see [Privacy](#privacy). |

## Two ways to use it

**As a private MCP retrieval backend for agents** *(the fast path for developers)*
JustSearch exposes its retrieval over MCP at `POST /mcp` — **in-process Streamable HTTP** on the loopback API
(no separate process, no Node.js). The desktop app listens on **`http://127.0.0.1:8080`** by default; if 8080
is already taken it falls back to a random free port. To pin a port, set the `JUSTSEARCH_API_PORT` environment
variable before launching; the actual port in use is always written as `head.apiPort` in
`%APPDATA%\io.justsearch.shell\runtime\manifest.json`. Details + a 2-minute Claude Desktop walkthrough:
[`docs/reference/mcp-production-server.md`](docs/reference/mcp-production-server.md). Connect your agent:

- **Claude Code:** `claude mcp add justsearch --transport http http://127.0.0.1:8080/mcp`
- **Codex CLI:** `codex mcp add justsearch --url http://127.0.0.1:8080/mcp`
- **Cursor / VS Code** (clients that accept an HTTP `url` directly — e.g. `.cursor/mcp.json`):
  ```json
  { "mcpServers": { "justsearch": { "url": "http://127.0.0.1:8080/mcp" } } }
  ```
- **Claude Desktop** — add it as a **Connector** (Settings → Connectors → Add custom connector → the URL above),
  or bridge stdio→HTTP with `mcp-remote` in `claude_desktop_config.json`:
  ```json
  { "mcpServers": { "justsearch": { "command": "npx", "args": ["mcp-remote", "http://127.0.0.1:8080/mcp"] } } }
  ```

All three need an app build that serves `POST /mcp`, which the v0.1.0 installer release may predate — if your
client lists no tools, POST a `tools/list` JSON-RPC request at the URL above before debugging the client; a 404
means that build has no MCP endpoint, so use a newer release or a from-source build.

Six tools: `justsearch_answer` (RAG, primary), `justsearch_search`, `justsearch_browse`, `justsearch_ingest`,
`justsearch_status`, `justsearch_runtime_manifest`. The server binds to loopback and never uploads anything
itself, but the passages it returns go to whichever client you connected — a cloud-hosted agent will forward
them to its model provider like any other tool result.

Does wiring an agent to these tools measurably improve its answers? We benchmark that under a fail-closed
publication policy. The one accepted publication so far is *adoption-only*: agents used the tools, but no
stratum showed an accuracy or efficiency improvement — see the
[status note](#agent-utility-publication-status) below and
[`docs/reference/benchmarks/agent-utility.md`](docs/reference/benchmarks/agent-utility.md).

**As a desktop app** *(for non-developers)*
Follow [Install (Windows)](#install-windows) above, point it at a folder, and search.
*(Windows; alpha; code-signed as of v0.2.0 — see [Status](#status).)*

## Why JustSearch

Desktop file search is either **fast-but-lexical** (Everything — filenames, instantly; Recoll — full-text and
even OCR, but keyword matching only) or **smart-but-cloud** (NotebookLM, Copilot — capable, but your files
leave your machine). Self-hosted server stacks (RAGFlow, Open WebUI, QAnything) can combine semantic retrieval,
offline operation, multilingual models, and OCR — if you deploy and configure a Docker stack yourself.
JustSearch aims at the gap between those worlds: as far as we know, the only **single-install desktop app**
that ships **{true hybrid retrieval × fully offline × multilingual × OCR}** out of the box — here is the
[sourced comparison](docs/comparison.md) behind that sentence, and if we've missed a tool, please
[open an issue](https://github.com/justsearch-app/justsearch/issues) and we'll list it.

- **Hybrid retrieval, not single-model RAG** — BM25 + dense vectors + SPLADE learned-sparse, fused and reranked
  by a cross-encoder. Most local RAG tools use one embedding model and basic chunking.
- **Fully offline & provable** — the UI is hard-locked to `127.0.0.1` by CSP; there is no telemetry exporter.
  The only outbound call is a one-time model download. ([How to verify](#privacy).)
- **Multilingual by construction** — one locale-invariant pipeline (ICU + a multilingual model stack), no
  per-language tuning. Competitive nDCG on German and French, not just English.
- **Vision OCR** — extracts text from scanned PDFs and images, so they're searchable too.
- **BYO-LLM** — runs your own local model via llama.cpp; no API keys, no per-token cost.

## Benchmarks

Retrieval quality (nDCG@10) from one reproducible release run (`scripts/jseval/release.v1.json`, RTX 4070,
~300 queries/corpus; measured on the canonical 2026-08-14 release tree — the first release scored on the
corrected delivered-ranking basis, tempdoc 803). Numbers are
the **default `hybrid` config** unless noted:

| Corpus | nDCG@10 | Note |
|---|---|---|
| BEIR / SciFact | **0.757** | in the range of published single-model retrievers (ColBERTv2 0.693, SPLADE++ 0.71) — but read this as *system vs. component*: ours is a full hybrid+rerank pipeline, theirs are single models |
| Enron-QA | 0.796 | |
| MIRACL-de (German) | 0.857 | multilingual — no per-language tuning |
| MIRACL-fr (French) | 0.884 | |
| Legal (CLERC case-retrieval) | 0.578 | citation-retrieval task on real US federal case law; replaces an earlier bespoke legal corpus that had no reproducible construction path (tempdoc 666) |

External-baseline figures are cited from published papers (SIGIR/NAACL; sources + split caveats in
`release.v1.json`) — **not** re-run by us, and not directly apples-to-apples: a hybrid+rerank *system* is
expected to exceed single-model *components*, and MIRACL baselines are a different (dev) split. The honest
reading is "a reproducible offline hybrid system lands in the range of strong published retrievers," not
"we beat them" — full comparison-class notes in
[the methodology](docs/reference/benchmarks/methodology.md#how-to-read-the-comparison-system-vs-component).
Per-corpus nDCG@10 floors are checked against a pinned baseline at release-composition time
(`python -m jseval relevance-gate`, `scripts/jseval/relevance-ratchet-baselines.v1.json`); that is a
local gate run when a release is composed, not an automated CI job. The README table itself is checked
against the release object in CI (`scripts/ci/check-readme-benchmark-numbers.mjs`).

Reproduce (from `scripts/jseval`): `python -m jseval run --start-backend --dataset beir/scifact --modes hybrid`
then `python -m jseval relevance-gate --dataset beir/scifact`. Slugs: `beir/scifact`, `mixed/enron-qa`,
`mixed/legal-clerc-200`, `mixed/miracl-de-2k`, `mixed/miracl-fr-2k`.
Full methodology, comparison-class caveats, and reproduction:
[`docs/reference/benchmarks/methodology.md`](docs/reference/benchmarks/methodology.md). The table above is
projected from `scripts/jseval/release.v1.json` (the canonical 2026-08-14 release), not hand-transcribed.
For the broader research angle — open questions, what's deferred, and how to get involved — see
[`RESEARCH.md`](RESEARCH.md).

### Agent-utility publication status

Separate from retrieval quality above, we also measure *agent utility* (does an agent with JustSearch tools
answer better than one without?) under a fail-closed publication policy:

<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->

Accepted publication `agent-utility-hero-2026-07-28` (record `c5a75457b264`, policy `agent-utility-public-v4`). Agents adopted JustSearch, but the campaign did not establish an efficiency or accuracy improvement across every required stratum.

[See the per-stratum evidence, provenance, and limitations](docs/reference/benchmarks/agent-utility.md).

<!-- agent-utility:generated:end -->

## Quickstart (build from source)

> Prereqs to **build + test**: **JDK 25** (the Gradle toolchain auto-resolves it) and **Node.js** (for the
> `modules/ui-web` frontend) — nothing else. Building/contributing needs **no GPU, no Rust toolchain, and no model
> download**; the ~9 GB models below are runtime-only (fetched on first *run* of the app).

```bash
git clone https://github.com/justsearch-app/justsearch && cd justsearch
./gradlew.bat build              # build (Windows; use ./gradlew on *nix once cross-platform)
```

To **run** the full desktop app, the easy path is the [installer](#install-windows) above; running the
three-process stack from source is a developer workflow — see [`CONTRIBUTING.md`](CONTRIBUTING.md). First run
downloads the models once (**~9 GB** — the ~5.9 GB local chat model dominates; fetched from GitHub Releases +
HuggingFace), then runs fully offline.

## Architecture

Three local processes, isolated for reliability and so the UI **never touches the index**:

- **Head** — the Tauri desktop shell + a loopback-only API gateway (Lit/web-components frontend).
- **Worker** — owns the Lucene index + the retrieval pipeline (BM25/dense/SPLADE/rerank) + OCR.
- **Inference** — a local `llama-server` for chat/RAG.

They talk over gRPC on `127.0.0.1`. More: [`docs/explanation/01-system-overview.md`](docs/explanation/01-system-overview.md).
The public API surface is mapped in [`docs/reference/api-contract-map.md`](docs/reference/api-contract-map.md).

## Privacy

JustSearch itself sends nothing off your machine, and you can check:
- The webview's Content-Security-Policy pins network access to `127.0.0.1` — it *cannot* reach the public internet.
- No analytics/telemetry exporter exists in the code.
- The MCP server binds to loopback. The one egress path is the one you choose: an external MCP client you
  connect receives retrieved passages and file paths as tool results, and a cloud-hosted client forwards
  those to its model provider. Use the built-in assistant or a local client to keep the whole loop on-device.
- The only outbound request is the one-time model download (from GitHub Releases + HuggingFace); after that, run
  a network monitor and watch it stay silent. Threat model: [`docs/reference/security/threat-model.md`](docs/reference/security/threat-model.md).

## Status

**Alpha** (`0.3.0`), **Windows-only** (macOS/Linux are not in the current scope). The installer is
**code-signed** (publisher: Elias Justus) as of v0.2.0; SmartScreen reputation for the new certificate
accrues with downloads, so an early reputation prompt is possible and names the verified publisher. In active
development since 2025; published 2026. Built in the open with heavy AI-agent assistance — the development
tooling, the governance/discipline gates, and the design history (`docs/tempdocs/`) all live in this repo, and
commits are co-authored.

## Contributing

Contributions welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md) (CLA required). Please read
[`NON-GOALS.md`](NON-GOALS.md) first so a change fits the project's scope. Security: [`SECURITY.md`](SECURITY.md).
You **don't** need any of the agent/governance machinery to contribute — it's published as transparency
([`MAINTAINING.md`](MAINTAINING.md)), not a required path. Interested in the research side (open questions,
what's deferred, collaboration) rather than contributing code? See [`RESEARCH.md`](RESEARCH.md).

## License

[Apache-2.0](LICENSE). Bundled model and dependency licenses: [`NOTICE`](NOTICE) / [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES).
