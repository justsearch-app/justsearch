# JustSearch

**JustSearch finds what is inside your files, quickly and accurately, whether you know exactly what you are looking for or only vaguely. It is for anyone who keeps files on their computer, as a search app of its own or as a tool inside other apps and AI agents through MCP.**

It is local-first: your files are read, indexed and searched on your own computer, and the built-in
assistant answers on it too.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![CI](https://github.com/justsearch-app/justsearch/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/justsearch-app/justsearch/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/justsearch-app/justsearch?label=release)](https://github.com/justsearch-app/justsearch/releases/latest)
<!-- mcp-name: io.github.justsearch-app/justsearch -->
<!-- ^ Official MCP Registry ownership marker (modelcontextprotocol.io/registry/package-types);
     must match "name" in packaging/mcpb/server.json. Do not remove. -->

<p align="center">
  <img src="docs/assets/hero-search.png" width="820"
       alt="JustSearch results for keyboard shortcuts, with matching words highlighted">
</p>
<p align="center">
  <img src="docs/assets/hero-answer.png" width="820"
       alt="JustSearch answers what the global search shortcut preserves, with citations and a passage from the keyboard shortcuts guide">
</p>

## What it does

- **Search by words or by meaning.** Type the exact phrase, or describe what you remember. Results
  from both ways of searching are combined, and a second model can put the best matches first.
- **Answers with sources.** Ask a question and get an answer that cites the passages it came from,
  written by a model running on your computer.
- **Reads what you have.** PDF, Office, email and most common document formats, and the text in
  scanned pages and images.
- **Works in many languages.** One setup for all of them; measured on English, German and French.
- **Works offline.** Searching by words works without internet from the start; searching by meaning
  and built-in answers do once the optional models are installed. JustSearch needs no account and
  has no built-in usage analytics.
- **A tool for AI agents.** Claude, Codex, Cursor and other MCP clients can search your files
  through JustSearch.

## Install

Download the package for your system from the
[latest release](https://github.com/justsearch-app/justsearch/releases/latest).

| | Windows | Linux |
|---|---|---|
| **Package** | `JustSearch_<version>_x64-setup.exe` | `JustSearch_<version>_amd64.deb` |
| **System** | Windows 11, 64-bit (tested). Windows 10 is expected to work but is not tested. | Ubuntu 24.04 or newer, x86_64 |
| **Install** | Run the installer. It installs per user and does not itself ask for administrator rights. | In the download folder, with one JustSearch package in it: `sudo apt install ./JustSearch_*_amd64.deb` |
| **Answers** | With a supported NVIDIA graphics card sold as 8 GB or more (the app checks for at least 7.5 GB of graphics memory) | Not in this release: on Linux, Install AI offers search only |

Then start JustSearch and add a folder. Searching by words works as soon as the first files are
read. **AI Brain → Install AI** adds searching by meaning and, where supported, answers. Before the first
download it shows the parts, their sizes and their terms: roughly 2–4 GB for searching by meaning,
or about 10 GB for the full Windows setup with answers. Allow disk space for the downloads, the
unpacked programs and the search index, which grows with your files. We recommend 16 GB of RAM; that is not a measured
minimum.

The Windows installer is code-signed; the publisher shown is **Elias Justus**. Windows SmartScreen
may still show a reputation warning. To check that the signature is valid and names that publisher,
see [Verify your download](docs/how-to/verify-your-download.md). macOS is not supported
([NON-GOALS](NON-GOALS.md)). To build from source, see [`CONTRIBUTING.md`](CONTRIBUTING.md).

**Official downloads.** JustSearch is free. The app and its MCP bundle are published only on this
repository's [Releases page](https://github.com/justsearch-app/justsearch/releases); the Windows installer there is
code-signed by Elias Justus.
A copy sold for money, or offered by another store or site, does not come from this project; please
report it in an [issue](https://github.com/justsearch-app/justsearch/issues).

## Use it from an AI agent

JustSearch has a built-in MCP server, so an AI agent can search the files you have added, get
passages with their sources, browse the added folders and ask to add files. JustSearch asks you
in the app before it adds anything, unless you already allowed it. The app must be running. The
release includes a small bridge, `justsearch-mcp.mcpb`, that finds the running app and signs in to
it by itself. Use the one from the release you installed, and if you once set
`JUSTSEARCH_API_PORT`, remove it so the bridge can find the app:

- **Claude Desktop** (versions with Extensions): open the `.mcpb` file there.
- **Other clients that can launch a local MCP server:** the `.mcpb` file is a zip archive. Unpack
  all of it into a folder and set `node <folder>/server/index.js` as the server command. It needs
  Node.js 18 or later, on the same computer and user account as JustSearch. For Claude Code:

  ```bash
  claude mcp add justsearch -- node /path/to/that-folder/server/index.js
  ```

The client you connect receives the retrieved passages and absolute file paths without redaction
and may send them to its model provider. Details:
[MCP server reference](docs/reference/mcp-production-server.md).

## Benchmarks

How well the search finds the right passages. The score, nDCG@10, looks at the first ten results:
how well they match the question and how high the best ones appear; 1.0 is ideal. From release
`linux-complete-qrels-2026-11-02`:

| Collection | nDCG@10 | What it is |
|---|---|---|
| BEIR / SciFact | **0.759** | Scientific claims, a standard public benchmark |
| Enron-QA | 0.806 | Questions about business email |
| MIRACL-de (German) | 0.986 | Wikipedia questions, small document pool |
| MIRACL-fr (French) | 0.990 | Wikipedia questions, small document pool |
| Legal (CLERC case-retrieval) | WITHHELD | Legal citation retrieval. Withheld: re-ranking missed its 200 ms deadline on 25 of 200 queries |

How to read this: the numbers were measured on Linux with an NVIDIA RTX 4070, with words, meaning
and re-ranking combined. Automatic search on CPU leaves re-ranking off by default; turn on **Best match
first** in AI Brain to use it. The German and French tests searched
much smaller samples of Wikipedia than the published MIRACL benchmark, so those two scores are not
comparable with published results. On SciFact, the single-model retrievers we cite report 0.665–0.710
under their own setups; a combined system is expected to score above single models, so this is not
a like-for-like win. Everything behind the table, including how to reproduce it, is
in the [methodology](docs/reference/benchmarks/methodology.md); CI checks the scores above against
the release file. What the research project studies is in [`RESEARCH.md`](RESEARCH.md).

### Agent-utility publication status

**Agent-utility campaign freeze (2026-11-09):** Fresh and resumed live agent-utility campaigns are frozen. Their commands and runbooks remain available for historical reproduction and offline analysis, along with published evidence and offline replay. Neighboring experiments retain their existing status.

We also tested whether giving an AI agent JustSearch helps it answer more accurately or more
efficiently. The rules for publishing the result were set before the test:

<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->

Accepted publication `agent-utility-hero-2026-07-28` (record `c5a75457b264`, policy `agent-utility-public-v4`). Agents adopted JustSearch, but the campaign did not establish an efficiency or accuracy improvement across every required stratum.

Accuracy intervals are recomputed from the published observations under v5 question-level semantics (95% question-cluster bootstrap; seeds are replicates within questions). Point estimates and the historical adoption-only outcome are unchanged. Token and cost intervals remain descriptive cell-level intervals. Provenance: `scripts/jseval/public-agent-utility/question-level.v1.json`; reproduce with `python -m jseval.utility_public_projection --check`. The hash-pinned v4 bundle is unchanged.

[See the per-stratum evidence, provenance, and limitations](docs/reference/benchmarks/agent-utility.md).

<!-- agent-utility:generated:end -->

## Privacy

Reading, indexing, searching and the built-in assistant run on your computer. JustSearch uses the
network only for these:

- **Install AI** downloads models and the programs that run them from `github.com` and
  `huggingface.co`, after you agree.
- **Update checks** run by default in desktop builds with a configured release URL, which includes
  the published installers, about 30 seconds after the interface starts. They reveal your IP
  address and the time, and send no file content and no searches. You can switch the automatic check off, and an
  update is downloaded only when you ask.
- **An MCP client you connect** receives retrieved passages and absolute file paths without
  redaction and may send them to its model provider. Other tools you configure receive the inputs
  passed to them.
- **Tracing** is off by default. If you enable it and configure a destination, that destination can
  receive file paths and excerpts of documents.

**Report a problem** copies a diagnostic summary when it can and opens the bug report page in your
browser; you choose what to paste and send. The
[threat model](docs/reference/security/threat-model.md) and the
[policy reference](docs/explanation/15-enterprise-policy.md) explain the network activity and its
switches.

## How it is built

- **Desktop shell:** Tauri (Rust), with a Lit web-components frontend. It starts and supervises the
  Engine.
- **Engine:** one Java process that serves the local API and the MCP server and owns the Lucene
  index and the search pipeline. It reads simple formats itself and uses separate processes for
  formats such as PDF, Office files and scanned images (Apache Tika, plus OCR).
- **Models:** an embedding model
  ([gte-multilingual-base](https://huggingface.co/Alibaba-NLP/gte-multilingual-base)) and a
  re-ranking model run inside the Engine; answers come from a separate local `llama-server`
  (llama.cpp).

More in the [system overview](docs/explanation/01-system-overview.md) and the
[API contract map](docs/reference/api-contract-map.md).

JustSearch is developed with AI coding agents (Claude Code and OpenAI Codex) working under a
written agreement, [`AGENTS.md`](AGENTS.md): the agent that builds a change is never the
only judge of it, and a failure that was reported on the running app is checked on the running app.
The checks, the release steps and the design history are in
this repository ([`MAINTAINING.md`](MAINTAINING.md),
[cutting a release](docs/how-to/cut-a-release.md), `docs/tempdocs/`). Three decisions shape the
project:

- **Your computer does the work.** Cloud processing would add running costs and privacy obligations
  this project cannot carry ([NON-GOALS](NON-GOALS.md)).
- **Numbers are published under rules set beforehand.** A result that does not meet them is
  withheld or reported as it is, such as the legal row and the agent-utility result above.
- **A release is published without rebuilding.** The release process builds and signs a draft,
  tests those same Windows and Linux packages, and publishes those files; the release guide says
  which checks run.

## Status

**Alpha.** Windows and Linux; public since June 2026. What changed in each version is in the
[changelog](CHANGELOG.md).

## Contributing

Contributions welcome: see [`CONTRIBUTING.md`](CONTRIBUTING.md) (CLA required) and read
[`NON-GOALS.md`](NON-GOALS.md) first so a change fits the project's scope. You do not need any of
the agent machinery to contribute. Security: [`SECURITY.md`](SECURITY.md).

## License

[Apache-2.0](LICENSE). Bundled model and dependency licenses: [`NOTICE`](NOTICE) / [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES).
